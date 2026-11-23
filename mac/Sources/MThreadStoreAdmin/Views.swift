import AppKit
import SwiftUI
import UniformTypeIdentifiers

@main
struct MThreadStoreAdminApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup("MThread Store") {
            ContentView()
                .environment(model)
                .frame(minWidth: 860, minHeight: 560)
        }
        .defaultSize(width: 1100, height: 720)
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("Добавить APK…") { pickAPK(model) }.keyboardShortcut("o")
            }
            CommandGroup(after: .newItem) {
                Button("Обновить каталог") { Task { await model.reload() } }.keyboardShortcut("r")
            }
        }
    }
}

@MainActor
func pickAPK(_ model: AppModel) {
    let panel = NSOpenPanel()
    panel.allowedContentTypes = [UTType(filenameExtension: "apk") ?? .data]
    panel.allowsMultipleSelection = false
    if panel.runModal() == .OK, let url = panel.url { model.importAPK(url) }
}

struct ContentView: View {
    @Environment(AppModel.self) private var model
    @State private var dropTargeted = false

    var body: some View {
        @Bindable var model = model
        Group {
            if model.config == nil {
                SetupView()
            } else {
                NavigationSplitView {
                    Sidebar().navigationSplitViewColumnWidth(min: 240, ideal: 280)
                } detail: {
                    if model.draft != nil {
                        EditorView()
                    } else {
                        DropHint()
                    }
                }
            }
        }
        .onDrop(of: [.fileURL], isTargeted: $dropTargeted) { providers in
            loadURLs(providers) { urls in if let apk = urls.first(where: { $0.pathExtension.lowercased() == "apk" }) { model.importAPK(apk) } }
            return true
        }
        .overlay { if dropTargeted { DropOverlay() } }
        .sheet(isPresented: Binding(get: { model.job != nil }, set: { if !$0 { model.job = nil } })) {
            JobSheet()
        }
    }
}

// MARK: боковая панель

struct Sidebar: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        List(selection: Binding(get: { model.selection }, set: { model.select($0) })) {
            if model.selection == AppModel.newID, let d = model.draft {
                Label(d.name.isEmpty ? d.id : d.name, systemImage: "plus.app").tag(AppModel.newID)
            }
            Section("Приложения · \(model.catalog.apps.count)") {
                ForEach(model.apps) { app in
                    HStack(spacing: 10) {
                        RemoteIcon(path: app.icon, size: 34)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(app.title).lineLimit(1)
                            Text([app.versionName, formatSize(app.size), app.category].filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        Spacer()
                        if app.encrypted { Image(systemName: "lock.fill").foregroundStyle(.orange).help("Закрытое") }
                    }
                    .padding(.vertical, 2)
                    .tag(app.id)
                }
            }
        }
        .searchable(text: $model.search, placement: .sidebar, prompt: "Поиск")
        .overlay {
            if model.catalog.apps.isEmpty && !model.loading {
                ContentUnavailableView("Пусто", systemImage: "shippingbox", description: Text("Перетащите APK в окно"))
            }
        }
        .toolbar {
            ToolbarItemGroup {
                Button { Task { await model.reload() } } label: { Label("Обновить", systemImage: "arrow.clockwise") }
                    .disabled(model.loading)
                Button { pickAPK(model) } label: { Label("Добавить APK", systemImage: "plus") }
            }
        }
        .safeAreaInset(edge: .bottom) {
            if let err = model.loadError {
                Text(err).font(.caption).foregroundStyle(.red).padding(8)
            } else if let repo = model.config?.repo {
                Link(repo, destination: URL(string: "https://github.com/\(repo)")!)
                    .font(.caption).foregroundStyle(.secondary).padding(8)
            }
        }
    }
}

// MARK: редактор

struct EditorView: View {
    @Environment(AppModel.self) private var model
    @State private var confirmDelete = false
    @State private var screenshotsTargeted = false

    var body: some View {
        @Bindable var model = model
        if let draft = Binding($model.draft) {
            Form {
                Section {
                    HStack(alignment: .center, spacing: 16) {
                        MediaView(media: draft.wrappedValue.icon, height: 72, corner: 16)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(draft.wrappedValue.id).font(.headline).textSelection(.enabled)
                            Text(versionLine(draft.wrappedValue)).foregroundStyle(.secondary)
                            HStack {
                                Button("Сменить иконку…") { pickImages(multiple: false) { model.setIcon($0[0]) } }
                                if draft.wrappedValue.apk?.icon != nil && draft.wrappedValue.existing?.icon != nil {
                                    Button("Иконка из APK") { model.iconFromAPK() }
                                }
                            }
                            .controlSize(.small)
                        }
                    }
                    if let warn = draft.wrappedValue.versionWarning {
                        Label(warn, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                    }
                }

                Section("Карточка") {
                    TextField("Название", text: draft.name)
                    TextField("Коротко", text: draft.short, prompt: Text("Одна строка в списке"))
                    HStack {
                        TextField("Категория", text: draft.category, prompt: Text("Например: Инструменты"))
                        if !model.categories.isEmpty {
                            Menu {
                                ForEach(model.categories, id: \.self) { c in Button(c) { draft.wrappedValue.category = c } }
                            } label: { Image(systemName: "list.bullet") }
                            .menuStyle(.borderlessButton).fixedSize()
                        }
                    }
                    LabeledContent("Описание") {
                        TextEditor(text: draft.description).frame(minHeight: 110).font(.body)
                    }
                    LabeledContent("Что нового") {
                        TextEditor(text: draft.changelog).frame(minHeight: 60).font(.body)
                    }
                }

                Section {
                    Toggle(isOn: draft.encrypted) {
                        Text("Закрытое приложение")
                        Text("APK шифруется: скачать и установить его можно только через MThread Store.")
                    }
                    .disabled(draft.wrappedValue.apk == nil && draft.wrappedValue.existing != nil)
                    if draft.wrappedValue.apk == nil && draft.wrappedValue.existing != nil {
                        Text("Чтобы сменить «закрытое/открытое», добавьте APK заново.").font(.caption).foregroundStyle(.secondary)
                    }
                }

                Section("Скриншоты") {
                    ScrollView(.horizontal) {
                        HStack(spacing: 10) {
                            ForEach(Array(draft.wrappedValue.screenshots.enumerated()), id: \.element.id) { i, shot in
                                MediaView(media: shot, height: 200, corner: 10)
                                    .contextMenu {
                                        Button("Левее") { move(i, by: -1) }.disabled(i == 0)
                                        Button("Правее") { move(i, by: 1) }.disabled(i == draft.wrappedValue.screenshots.count - 1)
                                        Divider()
                                        Button("Удалить", role: .destructive) { model.draft?.screenshots.remove(at: i) }
                                    }
                            }
                            Button { pickImages(multiple: true) { model.addScreenshots($0) } } label: {
                                VStack(spacing: 6) {
                                    Image(systemName: "plus").font(.title2)
                                    Text("Добавить").font(.caption)
                                }
                                .frame(width: 110, height: 200)
                                .background(RoundedRectangle(cornerRadius: 10).strokeBorder(style: StrokeStyle(lineWidth: 1.5, dash: [5])))
                            }
                            .buttonStyle(.plain).foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 4)
                    }
                    .onDrop(of: [.fileURL], isTargeted: $screenshotsTargeted) { providers in
                        loadURLs(providers) { model.addScreenshots($0.filter { $0.pathExtension.lowercased() != "apk" }) }
                        return true
                    }
                    Text("Правый клик по скриншоту — порядок и удаление. Можно перетаскивать картинки сюда.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .formStyle(.grouped)
            .navigationTitle(draft.wrappedValue.name.isEmpty ? draft.wrappedValue.id : draft.wrappedValue.name)
            .toolbar {
                ToolbarItemGroup(placement: .primaryAction) {
                    if draft.wrappedValue.existing != nil {
                        Button(role: .destructive) { confirmDelete = true } label: { Label("Удалить", systemImage: "trash") }
                    }
                    Button { model.publish() } label: { Label("Опубликовать", systemImage: "paperplane.fill") }
                        .buttonStyle(.borderedProminent)
                        .keyboardShortcut("s")
                }
            }
            .confirmationDialog("Удалить «\(draft.wrappedValue.name)» из магазина?", isPresented: $confirmDelete) {
                Button("Удалить", role: .destructive) { model.delete() }
            } message: {
                Text("У тех, кто уже установил, приложение останется.")
            }
        }
    }

    private func move(_ i: Int, by d: Int) {
        guard var shots = model.draft?.screenshots, shots.indices.contains(i + d) else { return }
        shots.swapAt(i, i + d)
        model.draft?.screenshots = shots
    }

    private func versionLine(_ d: Draft) -> String {
        if let apk = d.apk {
            let new = "\(apk.versionName ?? "?") (\(apk.versionCode ?? 0)) · \(formatSize(apk.size))"
            if let e = d.existing { return "\(e.versionName) → \(new)" }
            return new
        }
        if let e = d.existing { return "\(e.versionName) (\(e.versionCode)) · \(formatSize(e.size))" }
        return ""
    }
}

// MARK: картинки

struct RemoteIcon: View {
    @Environment(AppModel.self) private var model
    let path: String?
    let size: CGFloat

    var body: some View {
        MediaView(media: path.map(Media.remote), height: size, corner: size * 0.24, square: true)
    }
}

struct MediaView: View {
    @Environment(AppModel.self) private var model
    let media: Media?
    let height: CGFloat
    var corner: CGFloat = 8
    var square = false

    var body: some View {
        Group {
            switch media {
            case .local(let data, _):
                if let img = NSImage(data: data) { Image(nsImage: img).resizable().scaledToFit() } else { placeholder }
            case .remote(let path):
                AsyncImage(url: URL(string: (model.config?.rawBase ?? "") + path)) { phase in
                    if let image = phase.image { image.resizable().scaledToFit() } else { placeholder }
                }
            case nil:
                placeholder
            }
        }
        .frame(width: square || media == nil ? height : nil, height: height)
        .clipShape(RoundedRectangle(cornerRadius: corner))
    }

    private var placeholder: some View {
        RoundedRectangle(cornerRadius: corner).fill(.quaternary)
            .overlay(Image(systemName: "app.dashed").foregroundStyle(.secondary))
            .frame(width: height, height: height)
    }
}

@MainActor
func pickImages(multiple: Bool, _ done: @escaping ([URL]) -> Void) {
    let panel = NSOpenPanel()
    panel.allowedContentTypes = [.image]
    panel.allowsMultipleSelection = multiple
    if panel.runModal() == .OK, !panel.urls.isEmpty { done(panel.urls) }
}

func loadURLs(_ providers: [NSItemProvider], _ done: @escaping @MainActor ([URL]) -> Void) {
    let group = DispatchGroup()
    var urls: [URL] = []
    let lock = NSLock()
    for p in providers {
        group.enter()
        _ = p.loadObject(ofClass: URL.self) { url, _ in
            if let url { lock.lock(); urls.append(url); lock.unlock() }
            group.leave()
        }
    }
    group.notify(queue: .main) { MainActor.assumeIsolated { done(urls) } }
}

// MARK: прочие экраны

struct DropHint: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        ContentUnavailableView {
            Label("Перетащите APK сюда", systemImage: "square.and.arrow.down.on.square")
        } description: {
            Text("Новая версия существующего приложения обновит его в магазине.")
        } actions: {
            Button("Выбрать APK…") { pickAPK(model) }.buttonStyle(.borderedProminent)
        }
    }
}

struct DropOverlay: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 16)
            .strokeBorder(Color.accentColor, style: StrokeStyle(lineWidth: 3, dash: [10]))
            .background(Color.accentColor.opacity(0.08))
            .padding(10)
            .allowsHitTesting(false)
    }
}

struct SetupView: View {
    @Environment(AppModel.self) private var model
    @State private var name = "mthread-store"

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "shippingbox.fill").font(.system(size: 48)).foregroundStyle(.tint)
            Text("Первая настройка").font(.title.bold())
            Text("Будет создан публичный репозиторий на вашем GitHub: каталог, скриншоты и APK. Закрытые APK шифруются, ключ хранится в «~/Library/Application Support/MThread Store».")
                .multilineTextAlignment(.center).foregroundStyle(.secondary).frame(maxWidth: 460)
            TextField("Имя репозитория", text: $name).frame(width: 280)
            Button("Создать") { model.setup(name: name) }.buttonStyle(.borderedProminent).controlSize(.large)
        }
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct JobSheet: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let job = model.job ?? JobState(title: "")
        VStack(alignment: .leading, spacing: 12) {
            Text(job.error == nil ? job.title : "Не получилось").font(.headline)
            if let err = job.error {
                Text(err).foregroundStyle(.secondary).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
                HStack { Spacer(); Button("Закрыть") { model.job = nil }.keyboardShortcut(.defaultAction) }
            } else {
                Text(job.step).foregroundStyle(.secondary)
                if let p = job.progress {
                    ProgressView(value: p) { EmptyView() } currentValueLabel: { Text("\(Int(p * 100))%") }
                } else {
                    ProgressView().progressViewStyle(.linear)
                }
            }
        }
        .padding(24)
        .frame(width: 420)
        .interactiveDismissDisabled(job.error == nil)
    }
}
