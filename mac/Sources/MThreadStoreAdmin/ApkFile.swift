import AppKit
import CommonCrypto
import CryptoKit
import Foundation

/// Что удалось прочитать из APK.
struct ApkInfo {
    var url: URL
    var id: String?
    var versionCode: Int64?
    var versionName: String?
    var label: String?
    var icon: NSImage?
    var size: Int64
}

enum ApkFile {
    static var aapt2: String? {
        let sdk = ProcessInfo.processInfo.environment["ANDROID_HOME"] ?? NSHomeDirectory() + "/Library/Android/sdk"
        let tools = (try? FileManager.default.contentsOfDirectory(atPath: sdk + "/build-tools"))?.sorted() ?? []
        return tools.reversed().map { "\(sdk)/build-tools/\($0)/aapt2" }.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    static func analyze(_ url: URL) throws -> ApkInfo {
        let size = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int64) ?? 0
        var info = ApkInfo(url: url, size: size)
        guard let aapt2 else { return info }
        let out = try run(aapt2, ["dump", "badging", url.path])
        if let m = out.firstMatch(of: #/package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'/#) {
            info.id = String(m.1)
            info.versionCode = Int64(m.2)
            info.versionName = String(m.3)
        }
        if let m = out.firstMatch(of: #/application-label-ru:'([^']*)'/#) ?? out.firstMatch(of: #/application-label:'([^']*)'/#) {
            info.label = String(m.1)
        }
        // Самая крупная растровая иконка; у адаптивных ищем png/webp с тем же именем.
        var paths = out.matches(of: #/application-icon-(\d+):'([^']+)'/#)
            .sorted { Int($0.1)! > Int($1.1)! }.map { String($0.2) }
        let entries = (try? run("/usr/bin/zipinfo", ["-1", url.path]))?.split(separator: "\n").map(String.init) ?? []
        for p in paths where p.hasSuffix(".xml") {
            let base = (p as NSString).lastPathComponent.replacingOccurrences(of: ".xml", with: "")
            paths += entries.filter { $0.hasPrefix("res/mipmap") && ($0.hasSuffix("/\(base).png") || $0.hasSuffix("/\(base).webp")) }
        }
        for p in paths where p.hasSuffix(".png") || p.hasSuffix(".webp") {
            if let data = try? unzip(url, entry: p), let img = NSImage(data: data), img.size.width >= (info.icon?.size.width ?? 0) {
                info.icon = img
            }
        }
        return info
    }

    private static func unzip(_ zip: URL, entry: String) throws -> Data {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
        p.arguments = ["-p", zip.path, entry]
        let out = Pipe()
        p.standardOutput = out
        try p.run()
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return data
    }
}

enum Crypto {
    static func randomHex(_ bytes: Int) -> String {
        var b = [UInt8](repeating: 0, count: bytes)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes, &b)
        return b.map { String(format: "%02x", $0) }.joined()
    }

    static func sha256(_ url: URL) throws -> String {
        let h = try FileHandle(forReadingFrom: url)
        defer { try? h.close() }
        var hasher = SHA256()
        while let chunk = try h.read(upToCount: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// AES-256-CTR с 128-битным счётчиком (как `openssl enc -aes-256-ctr` и Java `AES/CTR/NoPadding`).
    /// CTR-режим CommonCrypto переносит только младшие 64 бита счётчика, поэтому поток ключа считаем сами через ECB.
    static func encryptCTR(_ src: URL, to dst: URL, keyHex: String, ivHex: String, progress: (Double) -> Void) throws {
        let key = bytes(keyHex)
        var counter = bytes(ivHex)
        var ref: CCCryptorRef?
        let st = CCCryptorCreate(CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionECBMode),
                                 key, key.count, nil, &ref)
        guard st == kCCSuccess, let ref else { throw fail("Не удалось запустить шифрование (\(st))") }
        defer { CCCryptorRelease(ref) }
        FileManager.default.createFile(atPath: dst.path, contents: nil)
        let input = try FileHandle(forReadingFrom: src), output = try FileHandle(forWritingTo: dst)
        defer { try? input.close(); try? output.close() }
        let total = Double((try? FileManager.default.attributesOfItem(atPath: src.path)[.size] as? Int64) ?? 1)
        let chunkSize = 1 << 20 // кратно 16, поэтому блоки счётчика не рвутся между кусками
        var counters = [UInt8](repeating: 0, count: chunkSize)
        var stream = [UInt8](repeating: 0, count: chunkSize)
        var done = 0.0
        while let chunk = try input.read(upToCount: chunkSize), !chunk.isEmpty {
            let blocks = (chunk.count + 15) / 16
            for b in 0..<blocks {
                counters.replaceSubrange(b * 16..<(b + 1) * 16, with: counter)
                var i = 15
                while i >= 0 { counter[i] &+= 1; if counter[i] != 0 { break }; i -= 1 }
            }
            var moved = 0
            let r = CCCryptorUpdate(ref, counters, blocks * 16, &stream, stream.count, &moved)
            guard r == kCCSuccess else { throw fail("Ошибка шифрования (\(r))") }
            var out = [UInt8](chunk)
            for i in 0..<out.count { out[i] ^= stream[i] }
            try output.write(contentsOf: out)
            done += Double(chunk.count)
            progress(done / total)
        }
    }

    private static func bytes(_ hex: String) -> [UInt8] {
        var out: [UInt8] = []
        var i = hex.startIndex
        while i < hex.endIndex {
            let j = hex.index(i, offsetBy: 2)
            out.append(UInt8(hex[i..<j], radix: 16) ?? 0)
            i = j
        }
        return out
    }
}

extension NSImage {
    /// Уменьшенная копия в PNG (иконки) или JPEG (скриншоты), чтобы телефоны не качали мегабайты.
    func encoded(maxSide: CGFloat, png: Bool) -> (Data, String)? {
        guard let cg = cgImage(forProposedRect: nil, context: nil, hints: nil) else { return nil }
        let k = min(1, maxSide / CGFloat(max(cg.width, cg.height)))
        let w = Int(CGFloat(cg.width) * k), h = Int(CGFloat(cg.height) * k)
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        guard let scaled = ctx.makeImage() else { return nil }
        let rep = NSBitmapImageRep(cgImage: scaled)
        if png { return rep.representation(using: .png, properties: [:]).map { ($0, "png") } }
        return rep.representation(using: .jpeg, properties: [.compressionFactor: 0.86]).map { ($0, "jpg") }
    }
}
