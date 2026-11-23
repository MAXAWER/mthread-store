import Foundation

/// Тонкая обёртка над GitHub REST API. Токен — из `gh auth token`.
final class GitHub {
    let repo: String
    private let token: String
    private let session = URLSession(configuration: .ephemeral)

    init(repo: String) throws {
        self.repo = repo
        self.token = try GitHub.loadToken()
    }

    static func loadToken() throws -> String {
        let candidates = ["/usr/local/bin/gh", "/opt/homebrew/bin/gh", NSHomeDirectory() + "/.local/bin/gh", "/usr/bin/gh"]
        guard let gh = candidates.first(where: { FileManager.default.isExecutableFile(atPath: $0) }) else {
            throw fail("Не найден GitHub CLI (gh). Установите его и выполните в терминале: gh auth login")
        }
        let out = try run(gh, ["auth", "token"]).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !out.isEmpty else { throw fail("Нет входа в GitHub. Выполните в терминале: gh auth login") }
        return out
    }

    // MARK: запросы

    struct APIError: LocalizedError {
        let status: Int
        let message: String
        var errorDescription: String? { "GitHub \(status): \(message)" }
    }

    func request(_ method: String, _ path: String, body: Any? = nil, accept: String = "application/vnd.github+json") async throws -> Data {
        let url = URL(string: path.hasPrefix("https://") ? path : "https://api.github.com" + path)!
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalAndRemoteCacheData, timeoutInterval: 120)
        req.httpMethod = method
        setHeaders(&req, accept: accept)
        if let body {
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        await Pacer.shared.wait(write: method != "GET")
        let (data, resp) = try await session.data(for: req)
        try check(data, resp)
        return data
    }

    private func setHeaders(_ req: inout URLRequest, accept: String) {
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue(accept, forHTTPHeaderField: "Accept")
        req.setValue("2022-11-28", forHTTPHeaderField: "X-GitHub-Api-Version")
        req.setValue("MThreadStoreAdmin/1.0 (personal app catalog)", forHTTPHeaderField: "User-Agent")
    }

    /// Ошибки — без автоповторов: если GitHub просит притормозить, останавливаемся и говорим, сколько ждать.
    private func check(_ data: Data, _ resp: URLResponse) throws {
        let http = resp as? HTTPURLResponse
        let status = http?.statusCode ?? 0
        guard !(200..<300).contains(status) else { return }
        let msg = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["message"] as? String
            ?? String(data: data, encoding: .utf8) ?? ""
        let limited = status == 429 || (status == 403 && (http?.value(forHTTPHeaderField: "x-ratelimit-remaining") == "0"
            || http?.value(forHTTPHeaderField: "retry-after") != nil || msg.localizedCaseInsensitiveContains("rate limit")))
        if limited {
            var seconds = Int(http?.value(forHTTPHeaderField: "retry-after") ?? "") ?? 0
            if seconds == 0, let reset = Double(http?.value(forHTTPHeaderField: "x-ratelimit-reset") ?? "") {
                seconds = max(60, Int(reset - Date().timeIntervalSince1970))
            }
            throw fail("GitHub просит сделать паузу. Подождите \(max(1, (max(seconds, 60) + 59) / 60)) мин и повторите.")
        }
        throw APIError(status: status, message: msg)
    }

    func json(_ method: String, _ path: String, body: Any? = nil) async throws -> [String: Any] {
        let data = try await request(method, path, body: body)
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    // MARK: каталог и коммиты

    func readCatalog() async throws -> Catalog {
        do {
            let data = try await request("GET", "/repos/\(repo)/contents/catalog.json", accept: "application/vnd.github.raw")
            return try JSONDecoder().decode(Catalog.self, from: data)
        } catch let e as APIError where e.status == 404 {
            return Catalog()
        }
    }

    /// Один коммит: путь → содержимое (nil — удалить).
    func commit(_ files: [String: Data?], message: String) async throws {
        let branch = try await json("GET", "/repos/\(repo)")["default_branch"] as? String ?? "main"
        let ref = try await json("GET", "/repos/\(repo)/git/ref/heads/\(branch)")
        let head = (ref["object"] as? [String: Any])?["sha"] as? String ?? ""
        let commit = try await json("GET", "/repos/\(repo)/git/commits/\(head)")
        let baseTree = (commit["tree"] as? [String: Any])?["sha"] as? String ?? ""
        var tree: [[String: Any]] = []
        for (path, content) in files {
            if let content {
                let blob = try await json("POST", "/repos/\(repo)/git/blobs", body: ["content": content.base64EncodedString(), "encoding": "base64"])
                tree.append(["path": path, "mode": "100644", "type": "blob", "sha": blob["sha"] as? String ?? ""])
            } else {
                tree.append(["path": path, "mode": "100644", "type": "blob", "sha": NSNull()])
            }
        }
        let newTree = try await json("POST", "/repos/\(repo)/git/trees", body: ["base_tree": baseTree, "tree": tree])
        let newCommit = try await json("POST", "/repos/\(repo)/git/commits",
                                       body: ["message": message, "tree": newTree["sha"] as? String ?? "", "parents": [head]])
        _ = try await request("PATCH", "/repos/\(repo)/git/refs/heads/\(branch)", body: ["sha": newCommit["sha"] as? String ?? ""])
    }

    // MARK: релизы

    func release(tag: String, title: String) async throws -> [String: Any] {
        do {
            return try await json("GET", "/repos/\(repo)/releases/tags/\(tag.urlPath)")
        } catch let e as APIError where e.status == 404 {
            return try await json("POST", "/repos/\(repo)/releases",
                                  body: ["tag_name": tag, "name": title, "body": "MThread Store: \(title)", "make_latest": "false"])
        }
    }

    func deleteAsset(_ id: Int) async throws {
        _ = try await request("DELETE", "/repos/\(repo)/releases/assets/\(id)")
    }

    func deleteRelease(tag: String) async throws {
        do {
            let rel = try await json("GET", "/repos/\(repo)/releases/tags/\(tag.urlPath)")
            if let id = rel["id"] as? Int { _ = try await request("DELETE", "/repos/\(repo)/releases/\(id)") }
            _ = try await request("DELETE", "/repos/\(repo)/git/refs/tags/\(tag.urlPath)")
        } catch let e as APIError where e.status == 404 {}
    }

    /// Загружает файл ассетом релиза, сообщая долю отправленного.
    func uploadAsset(uploadURL: String, file: URL, name: String, progress: @escaping (Double) -> Void) async throws -> [String: Any] {
        let base = uploadURL.components(separatedBy: "{").first ?? uploadURL
        var comps = URLComponents(string: base)!
        comps.queryItems = [URLQueryItem(name: "name", value: name)]
        var req = URLRequest(url: comps.url!, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 3600)
        req.httpMethod = "POST"
        setHeaders(&req, accept: "application/vnd.github+json")
        req.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        let delegate = UploadProgress(progress)
        await Pacer.shared.wait(write: true)
        let (data, resp) = try await session.upload(for: req, fromFile: file, delegate: delegate)
        try check(data, resp)
        return (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    func user() async throws -> String { try await json("GET", "/user")["login"] as? String ?? "" }

    func repoExists(_ name: String) async throws -> Bool {
        do { _ = try await request("GET", "/repos/\(name)"); return true } catch let e as APIError where e.status == 404 { return false }
    }

    func createRepo(_ name: String) async throws {
        _ = try await request("POST", "/user/repos",
                              body: ["name": name, "private": false, "auto_init": true, "description": "MThread Store — каталог приложений"])
    }
}

/// Запросы по одному и с паузами — как советует GitHub: не меньше секунды между изменяющими запросами.
/// Резкие всплески автоматических действий и стали причиной блокировки аккаунта в октябре 2026.
actor Pacer {
    static let shared = Pacer()
    private var last = Date.distantPast

    func wait(write: Bool) async {
        // Слот занимаем до сна: параллельные вызовы встают в очередь, а не просыпаются вместе.
        let slot = max(Date(), last.addingTimeInterval(write ? 1.5 : 0.3))
        last = slot
        let delay = slot.timeIntervalSinceNow
        if delay > 0 { try? await Task.sleep(for: .seconds(delay)) }
    }
}

private final class UploadProgress: NSObject, URLSessionTaskDelegate {
    let onProgress: (Double) -> Void
    init(_ onProgress: @escaping (Double) -> Void) { self.onProgress = onProgress }
    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        if totalBytesExpectedToSend > 0 { onProgress(Double(totalBytesSent) / Double(totalBytesExpectedToSend)) }
    }
}

extension String {
    var urlPath: String { addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? self }
}

/// Запуск внешней утилиты и чтение stdout.
@discardableResult
func run(_ tool: String, _ args: [String]) throws -> String {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: tool)
    p.arguments = args
    let out = Pipe()
    p.standardOutput = out
    p.standardError = Pipe()
    try p.run()
    let data = out.fileHandleForReading.readDataToEndOfFile()
    p.waitUntilExit()
    return String(data: data, encoding: .utf8) ?? ""
}
