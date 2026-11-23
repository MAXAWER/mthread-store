import Foundation

/// Репозиторий и ключ шифрования. Лежат в ~/Library/Application Support/MThread Store/store.properties —
/// оттуда же их берёт сборка Android-приложения.
struct StoreConfig: Equatable {
    var repo: String
    var key: String

    static let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("MThread Store", isDirectory: true)
    static let file = directory.appendingPathComponent("store.properties")

    static func load() -> StoreConfig? {
        guard let text = try? String(contentsOf: file, encoding: .utf8) else { return nil }
        var props: [String: String] = [:]
        for line in text.split(whereSeparator: \.isNewline) {
            let s = line.trimmingCharacters(in: .whitespaces)
            guard !s.hasPrefix("#"), let eq = s.firstIndex(of: "=") else { continue }
            props[String(s[..<eq]).trimmingCharacters(in: .whitespaces)] = String(s[s.index(after: eq)...]).trimmingCharacters(in: .whitespaces)
        }
        guard let repo = props["repo"], let key = props["key"], !repo.isEmpty, key.count == 64 else { return nil }
        return StoreConfig(repo: repo, key: key)
    }

    func save() throws {
        try FileManager.default.createDirectory(at: Self.directory, withIntermediateDirectories: true)
        let text = "# MThread Store. НЕ публиковать: здесь ключ шифрования закрытых APK.\nrepo=\(repo)\nkey=\(key)\n"
        try text.write(to: Self.file, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: Self.file.path)
    }

    var rawBase: String { "https://raw.githubusercontent.com/\(repo)/HEAD/" }
}

enum StoreError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case .message(let m) = self { return m }; return nil }
}

func fail(_ message: String) -> StoreError { .message(message) }
