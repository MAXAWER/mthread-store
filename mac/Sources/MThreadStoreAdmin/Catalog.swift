import Foundation

/// Формат catalog.json — общий с Android-клиентом (docs/design.md).
struct Catalog: Codable {
    var schema: Int = 1
    var updated: String?
    var apps: [CatalogApp] = []
}

struct CatalogApp: Codable, Identifiable, Hashable {
    var id: String
    var name: String = ""
    var short: String = ""
    var description: String = ""
    var changelog: String = ""
    var category: String = ""
    var icon: String?
    var screenshots: [String] = []
    var versionCode: Int64 = 0
    var versionName: String = ""
    var size: Int64 = 0
    var sha256: String = ""
    var encrypted: Bool = false
    var iv: String?
    var url: String = ""
    var updated: String?

    var title: String { name.isEmpty ? id : name }

    init(id: String) { self.id = id }

    // Старые записи могли не иметь части полей — читаем мягко.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        short = try c.decodeIfPresent(String.self, forKey: .short) ?? ""
        description = try c.decodeIfPresent(String.self, forKey: .description) ?? ""
        changelog = try c.decodeIfPresent(String.self, forKey: .changelog) ?? ""
        category = try c.decodeIfPresent(String.self, forKey: .category) ?? ""
        icon = try c.decodeIfPresent(String.self, forKey: .icon)
        screenshots = try c.decodeIfPresent([String].self, forKey: .screenshots) ?? []
        versionCode = try c.decodeIfPresent(Int64.self, forKey: .versionCode) ?? 0
        versionName = try c.decodeIfPresent(String.self, forKey: .versionName) ?? ""
        size = try c.decodeIfPresent(Int64.self, forKey: .size) ?? 0
        sha256 = try c.decodeIfPresent(String.self, forKey: .sha256) ?? ""
        encrypted = try c.decodeIfPresent(Bool.self, forKey: .encrypted) ?? false
        iv = try c.decodeIfPresent(String.self, forKey: .iv)
        url = try c.decodeIfPresent(String.self, forKey: .url) ?? ""
        updated = try c.decodeIfPresent(String.self, forKey: .updated)
    }
}

extension Catalog {
    func encoded() throws -> Data {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return try enc.encode(self)
    }
}

func isoNow() -> String {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime]
    return f.string(from: Date())
}

func formatSize(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
}
