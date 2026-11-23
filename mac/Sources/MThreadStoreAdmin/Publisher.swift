import AppKit
import CryptoKit
import Foundation

/// Картинка либо уже в репозитории (путь), либо новая с диска.
enum Media: Hashable {
    case remote(String)
    case local(Data, String) // данные, расширение

    var id: String {
        switch self {
        case .remote(let p): return p
        case .local(let d, _): return SHA256.hash(data: d).prefix(8).map { String(format: "%02x", $0) }.joined()
        }
    }
}

/// Что редактируется в правой панели.
struct Draft {
    var id: String
    var name = ""
    var short = ""
    var description = ""
    var changelog = ""
    var category = ""
    var encrypted = false
    var icon: Media?
    var screenshots: [Media] = []
    var apk: ApkInfo?
    var existing: CatalogApp?

    init(id: String) { self.id = id }

    init(existing: CatalogApp) {
        id = existing.id
        name = existing.name
        short = existing.short
        description = existing.description
        changelog = existing.changelog
        category = existing.category
        encrypted = existing.encrypted
        icon = existing.icon.map(Media.remote)
        screenshots = existing.screenshots.map(Media.remote)
        self.existing = existing
    }

    /// Новый APK: для известного приложения — новая версия с прежними описанием и картинками.
    init(apk: ApkInfo, id: String, existing: CatalogApp?) {
        if let existing {
            self.init(existing: existing)
            changelog = ""
        } else {
            self.init(id: id)
            name = apk.label ?? ""
        }
        self.apk = apk
        if icon == nil, let img = apk.icon, let (data, ext) = img.encoded(maxSide: 512, png: true) { icon = .local(data, ext) }
    }

    var versionWarning: String? {
        guard let apk, let existing, let code = apk.versionCode else { return nil }
        return code <= existing.versionCode ? "versionCode \(code) не больше текущего \(existing.versionCode) — телефоны не увидят обновление." : nil
    }
}

/// Публикация в GitHub: APK в Releases, картинки и catalog.json — одним коммитом.
struct Publisher {
    let config: StoreConfig
    let github: GitHub
    var step: (String, Double?) -> Void

    private static let maxAsset: Int64 = 2 * 1024 * 1024 * 1024

    func publish(_ draft: Draft) async throws {
        guard draft.id.range(of: #"^[A-Za-z0-9_.]+$"#, options: .regularExpression) != nil else {
            throw fail("Некорректный packageName: \(draft.id)")
        }
        step("Читаю каталог…", nil)
        var catalog = try await github.readCatalog()
        let existing = catalog.apps.first { $0.id == draft.id }
        var app = existing ?? CatalogApp(id: draft.id)
        app.name = draft.name
        app.short = draft.short
        app.description = draft.description
        app.changelog = draft.changelog
        app.category = draft.category

        var files: [String: Data?] = [:]
        func path(_ m: Media) -> String {
            switch m {
            case .remote(let p): return p
            case .local(let data, let ext):
                let p = "media/\(draft.id)/\(m.id).\(ext)"
                files[p] = data
                return p
            }
        }
        app.icon = draft.icon.map(path)
        app.screenshots = draft.screenshots.map(path)

        if let apk = draft.apk {
            guard apk.size <= Self.maxAsset else { throw fail("GitHub принимает файлы до 2 ГБ, этот APK больше") }
            guard let code = apk.versionCode else { throw fail("Не удалось прочитать versionCode из APK") }
            step("Считаю контрольную сумму…", nil)
            app.sha256 = try Crypto.sha256(apk.url)
            app.size = apk.size
            app.versionCode = code
            app.versionName = apk.versionName ?? String(code)

            var uploadFile = apk.url
            var assetName = "\(draft.id)-\(code).apk"
            var tempFile: URL?
            if draft.encrypted {
                let iv = Crypto.randomHex(16)
                let tmp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".enc")
                try Crypto.encryptCTR(apk.url, to: tmp, keyHex: config.key, ivHex: iv) { step("Шифрую…", $0) }
                uploadFile = tmp
                tempFile = tmp
                assetName += ".enc"
                app.encrypted = true
                app.iv = iv
            } else {
                app.encrypted = false
                app.iv = nil
            }
            defer { if let tempFile { try? FileManager.default.removeItem(at: tempFile) } }

            step("Готовлю релиз…", nil)
            let release = try await github.release(tag: draft.id, title: app.title)
            let assets = release["assets"] as? [[String: Any]] ?? []
            for a in assets where a["name"] as? String == assetName {
                try await github.deleteAsset(a["id"] as? Int ?? 0)
            }
            step("Загружаю APK в GitHub…", 0)
            let asset = try await github.uploadAsset(uploadURL: release["upload_url"] as? String ?? "", file: uploadFile, name: assetName) {
                step("Загружаю APK в GitHub…", $0)
            }
            app.url = asset["browser_download_url"] as? String ?? ""
            for a in assets where a["name"] as? String != assetName { // старые версии больше не нужны
                try await github.deleteAsset(a["id"] as? Int ?? 0)
            }
        } else if existing == nil {
            throw fail("Для нового приложения нужен APK")
        } else if draft.encrypted != existing?.encrypted {
            throw fail("Чтобы сменить «закрытое/открытое», добавьте APK заново")
        }

        app.updated = isoNow()
        if let i = catalog.apps.firstIndex(where: { $0.id == app.id }) { catalog.apps[i] = app } else { catalog.apps.append(app) }
        catalog.updated = app.updated

        let oldMedia = Set([existing?.icon].compactMap { $0 } + (existing?.screenshots ?? []))
        let newMedia = Set([app.icon].compactMap { $0 } + app.screenshots)
        for p in oldMedia.subtracting(newMedia) { files[p] = .some(nil) }
        files["catalog.json"] = try catalog.encoded()
        step("Обновляю каталог…", nil)
        let verb = existing == nil ? "Добавлено" : "Обновлено"
        try await github.commit(files, message: "\(verb): \(app.title) \(app.versionName)")
    }

    func delete(_ id: String) async throws {
        step("Удаляю…", nil)
        var catalog = try await github.readCatalog()
        guard let app = catalog.apps.first(where: { $0.id == id }) else { return }
        catalog.apps.removeAll { $0.id == id }
        var files: [String: Data?] = [:]
        for p in [app.icon].compactMap({ $0 }) + app.screenshots { files[p] = .some(nil) }
        files["catalog.json"] = try catalog.encoded()
        try await github.commit(files, message: "Удалено: \(app.title)")
        try await github.deleteRelease(tag: id)
    }

    /// Публичный репозиторий с пустым каталогом.
    static func setup(name: String, existingKey: String?) async throws -> StoreConfig {
        let login = try await GitHub(repo: "").user()
        let repo = name.contains("/") ? name : "\(login)/\(name)"
        let gh = try GitHub(repo: repo)
        if try await !gh.repoExists(repo) {
            try await gh.createRepo(String(repo.split(separator: "/")[1]))
            try await Task.sleep(for: .seconds(2)) // GitHub не сразу готов к коммитам в новый репозиторий
        }
        let catalog = try await gh.readCatalog()
        if catalog.updated == nil && catalog.apps.isEmpty {
            try await gh.commit(["catalog.json": try catalog.encoded()], message: "Каталог MThread Store")
        }
        let config = StoreConfig(repo: repo, key: existingKey ?? Crypto.randomHex(32))
        try config.save()
        return config
    }
}
