import AppKit
import Foundation

/// Точка входа: без аргументов — окно, с `--publish` — публикация из терминала тем же кодом и с теми же паузами.
///
///     MThreadStoreAdmin --publish app.apk [--name N] [--short S] [--category C] [--description-file F]
///                       [--changelog T] [--encrypted] [--icon icon.png] [--screenshot s.png ...]
@main
enum Main {
    static func main() {
        let args = Array(CommandLine.arguments.dropFirst())
        guard args.first == "--publish" else {
            MThreadStoreAdminApp.main()
            return
        }
        Task { @MainActor in
            let code: Int32
            do {
                try await CLI.publish(args)
                code = 0
            } catch {
                FileHandle.standardError.write(Data("Ошибка: \(error.localizedDescription)\n".utf8))
                code = 1
            }
            exit(code)
        }
        dispatchMain()
    }
}

@MainActor
enum CLI {
    static func publish(_ args: [String]) async throws {
        var opts: [String: String] = [:]
        var screenshots: [String] = []
        var encrypted = false
        var i = 0
        while i < args.count {
            let a = args[i]
            switch a {
            case "--encrypted": encrypted = true
            case "--screenshot": i += 1; screenshots.append(args[i])
            default:
                guard a.hasPrefix("--"), i + 1 < args.count else { throw fail("Непонятный аргумент: \(a)") }
                i += 1
                opts[String(a.dropFirst(2))] = args[i]
            }
            i += 1
        }
        guard let config = StoreConfig.load() else { throw fail("Нет store.properties — сначала настройте админку") }
        guard let apkPath = opts["publish"] else { throw fail("Не указан APK") }

        let info = try await Task.detached { try ApkFile.analyze(URL(fileURLWithPath: apkPath)) }.value
        guard let id = info.id else { throw fail("Не удалось прочитать packageName из APK") }
        let github = try await GitHub.client(config.repo)
        let existing = try await github.readCatalog().apps.first { $0.id == id }

        var draft = Draft(apk: info, id: id, existing: existing)
        if let v = opts["name"] { draft.name = v }
        if let v = opts["short"] { draft.short = v }
        if let v = opts["category"] { draft.category = v }
        if let v = opts["changelog"] { draft.changelog = v }
        if let f = opts["description-file"] { draft.description = try String(contentsOfFile: f, encoding: .utf8) }
        draft.encrypted = encrypted
        if let f = opts["icon"], let img = NSImage(contentsOfFile: f), let (d, ext) = img.encoded(maxSide: 512, png: true) {
            draft.icon = .local(d, ext)
        }
        for f in screenshots {
            if let img = NSImage(contentsOfFile: f), let (d, ext) = img.encoded(maxSide: 1600, png: false) {
                draft.screenshots.append(.local(d, ext))
            }
        }
        if draft.name.isEmpty { draft.name = id }
        print("Публикую \(draft.name) \(info.versionName ?? "") (\(id)), \(formatSize(info.size))\(encrypted ? ", закрытое" : "")")

        var lastLine = ""
        let publisher = Publisher(config: config, github: github) { step, progress in
            let line = progress.map { "\(step) \(Int($0 * 100))%" } ?? step
            // Печатаем шаги и каждые 10% прогресса, а не каждый байт.
            let bucket = progress.map { "\(step) \(Int($0 * 10))" } ?? step
            if bucket != lastLine {
                lastLine = bucket
                print(line)
                fflush(stdout)
            }
        }
        try await publisher.publish(draft)
        print("Готово: \(draft.name)")
    }
}
