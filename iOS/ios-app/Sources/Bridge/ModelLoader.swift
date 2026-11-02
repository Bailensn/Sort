import Compression
import Foundation

/// 把 bundle 里 gzip 过的模型解到缓存目录，返回可用的文件路径。
enum ModelLoader {

    private static let files = ["det.onnx", "rec.onnx", "rec.yml"]

    static func ensureModels() -> (det: String, rec: String, charset: String)? {
        guard let dir = cacheDirectory() else { return nil }

        var paths: [String: String] = [:]
        for name in files {
            guard let target = unpack(name, into: dir) else { return nil }
            paths[name] = target
        }
        guard
            let det = paths["det.onnx"],
            let rec = paths["rec.onnx"],
            let charset = paths["rec.yml"]
        else { return nil }
        return (det, rec, charset)
    }

    private static func cacheDirectory() -> URL? {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
        guard let dir = base?.appendingPathComponent("ocr-models", isDirectory: true) else {
            return nil
        }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private static func unpack(_ name: String, into dir: URL) -> String? {
        let target = dir.appendingPathComponent(name)
        if FileManager.default.fileExists(atPath: target.path) { return target.path }

        guard
            let packed = Bundle.main.url(forResource: name, withExtension: "gz"),
            let raw = try? Data(contentsOf: packed)
        else { return nil }

        let bytes = [UInt8](raw)
        guard let start = deflateStart(bytes) else { return nil }

        // 原始长度在 gzip 尾部的 ISIZE 里，省得再存一份 4 字节头
        let count = bytes.count
        let size = Int(bytes[count - 4])
            | Int(bytes[count - 3]) << 8
            | Int(bytes[count - 2]) << 16
            | Int(bytes[count - 1]) << 24
        guard size > 0 else { return nil }

        let payload = Data(bytes[start..<(count - 8)])
        var output = Data(count: size)

        let written = output.withUnsafeMutableBytes { out -> Int in
            guard let outBase = out.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            return payload.withUnsafeBytes { input -> Int in
                guard let inBase = input.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                return compression_decode_buffer(
                    outBase, size, inBase, payload.count, nil, COMPRESSION_ZLIB
                )
            }
        }
        guard written == size else { return nil }
        // 原子写：上次崩溃留下的半个文件会让下次直接命中缓存
        guard (try? output.write(to: target, options: .atomic)) != nil else { return nil }
        return target.path
    }

    /// 跳过 gzip 头（含 FEXTRA/FNAME/FCOMMENT），返回 deflate 流起点。
    private static func deflateStart(_ bytes: [UInt8]) -> Int? {
        guard bytes.count > 18, bytes[0] == 0x1f, bytes[1] == 0x8b, bytes[2] == 8 else {
            return nil
        }
        let flags = bytes[3]
        var offset = 10

        if flags & 0x04 != 0 {
            guard offset + 2 <= bytes.count else { return nil }
            offset += 2 + (Int(bytes[offset]) | Int(bytes[offset + 1]) << 8)
        }
        if flags & 0x08 != 0 { offset = skipZeroTerminated(bytes, from: offset) }
        if flags & 0x10 != 0 { offset = skipZeroTerminated(bytes, from: offset) }
        if flags & 0x02 != 0 { offset += 2 }

        guard offset > 0, offset + 8 < bytes.count else { return nil }
        return offset
    }

    private static func skipZeroTerminated(_ bytes: [UInt8], from offset: Int) -> Int {
        var index = offset
        while index < bytes.count, bytes[index] != 0 { index += 1 }
        return index + 1
    }
}
