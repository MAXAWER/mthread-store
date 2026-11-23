import AppKit
import Foundation
import Observation

struct JobState {
    var title: String
    var step = "Подготовка…"
    var progress: Double?
    var error: String?
    var done = false
}

@MainActor
@Observable
final class AppModel {
    var config: StoreConfig? = StoreConfig.load()
    var catalog = Catalog()
    var loading = false
    var loadError: String?
    var selection: String?   // id приложения или newID
    var draft: Draft?
    var job: JobState?
    var search = ""

    static let newID = "__new__"

    init() {
        migrateOldConfig()
        Task { await reload() }
    }

    var apps: [CatalogApp] {
        let list = catalog.apps.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        let q = search.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return list }
        return list.filter { [$0.title, $0.id, $0.category, $0.short].contains { $0.localizedCaseInsensitiveContains(q) } }
    }

    var categories: [String] {
        Array(Set(catalog.apps.map { $0.category.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })).sorted()
    }

    /// Раньше настройки лежали в папке проекта — переносим один раз.
    private func migrateOldConfig() {
        guard config == nil else { return }
        let old = URL(fileURLWithPath: NSHomeDirectory() + "/Documents/1APPS/MThread Store/store.properties")
        guard FileManager.default.fileExists(atPath: old.path) else { return }
        try? FileManager.default.createDirectory(at: StoreConfig.directory, withIntermediateDirectories: true)
        try? FileManager.default.copyItem(at: old, to: StoreConfig.file)
        config = StoreConfig.load()
    }

    func reload() async {
        guard let config else { return }
        loading = true
        defer { loading = false }
        do {
            catalog = try await GitHub.client(config.repo).readCatalog()
            loadError = nil
            if let id = selection, id != Self.newID, draft?.apk == nil, let app = catalog.apps.first(where: { $0.id == id }) {
                draft = Draft(existing: app)
            }
        } catch {
            loadError = error.localizedDescription
        }
    }

    func select(_ id: String?) {
        selection = id
        if let id, let app = catalog.apps.first(where: { $0.id == id }) { draft = Draft(existing: app) }
        else if id == nil { draft = nil }
    }

    // MARK: APK и картинки

    func importAPK(_ url: URL) {
        guard url.pathExtension.lowercased() == "apk" else { return showError("Нужен файл .apk") }
        Task {
            do {
                // aapt2 и unzip — внешние процессы, ждём их не на главном потоке.
                let info = try await Task.detached { try ApkFile.analyze(url) }.value
                guard let id = info.id else {
                    return showError(ApkFile.aapt2 == nil
                        ? "Не найден aapt2 из Android SDK (build-tools) — без него APK не прочитать."
                        : "Не удалось прочитать packageName из APK")
                }
                let existing = catalog.apps.first { $0.id == id }
                draft = Draft(apk: info, id: id, existing: existing)
                selection = existing?.id ?? Self.newID
            } catch {
                showError(error.localizedDescription)
            }
        }
    }

    func addScreenshots(_ urls: [URL]) {
        for url in urls {
            if let img = NSImage(contentsOf: url), let (data, ext) = img.encoded(maxSide: 1600, png: false) {
                draft?.screenshots.append(.local(data, ext))
            }
        }
    }

    func setIcon(_ url: URL) {
        if let img = NSImage(contentsOf: url), let (data, ext) = img.encoded(maxSide: 512, png: true) {
            draft?.icon = .local(data, ext)
        }
    }

    func iconFromAPK() {
        if let img = draft?.apk?.icon, let (data, ext) = img.encoded(maxSide: 512, png: true) { draft?.icon = .local(data, ext) }
    }

    // MARK: действия

    func publish() {
        guard let draft, config != nil else { return }
        guard !draft.name.trimmingCharacters(in: .whitespaces).isEmpty else { return showError("Введите название") }
        runJob(draft.name) { pub in try await pub.publish(draft) } onSuccess: { [weak self] in
            self?.selection = draft.id
            self?.draft = nil
            await self?.reload()
            self?.select(draft.id)
        }
    }

    func delete() {
        guard let draft, draft.existing != nil else { return }
        runJob(draft.name) { pub in try await pub.delete(draft.id) } onSuccess: { [weak self] in
            self?.select(nil)
            await self?.reload()
        }
    }

    func setup(name: String) {
        job = JobState(title: "Создаю репозиторий")
        Task {
            do {
                config = try await Publisher.setup(name: name, existingKey: nil)
                job = nil
                await reload()
            } catch {
                job?.error = error.localizedDescription
            }
        }
    }

    private func runJob(_ title: String, _ work: @escaping (Publisher) async throws -> Void, onSuccess: @escaping () async -> Void) {
        guard let config else { return }
        job = JobState(title: title)
        Task {
            do {
                let publisher = Publisher(config: config, github: try await GitHub.client(config.repo)) { step, progress in
                    Task { @MainActor in
                        self.job?.step = step
                        self.job?.progress = progress
                    }
                }
                try await work(publisher)
                job = nil
                await onSuccess()
            } catch {
                job?.error = error.localizedDescription
            }
        }
    }

    func showError(_ text: String) {
        job = JobState(title: "Ошибка", error: text)
    }
}
