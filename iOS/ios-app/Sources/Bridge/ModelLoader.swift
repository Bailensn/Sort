import Compression
import Foundation

/// 把 bundle 里压缩过的模型解到缓存目录，返回可用的文件路径。
/// 压缩格式：4 字节小端原始长度 + zlib 流（CI 用 Python 的 zlib 压出来）。
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
            let packed = Bundle.main.url(forResource: name, withExtension: "zlib"),
            let raw = try? Data(contentsOf: packed),
            raw.count > 4
        else { return nil }

        let size = raw.prefix(4).withUnsafeBytes { $0.load(as: UInt32.self) }
        let payload = raw.dropFirst(4)
        var output = Data(count: Int(size))

        let written = output.withUnsafeMutableBytes { out -> Int in
            guard let outBase = out.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            return payload.withUnsafeBytes { input -> Int in
                guard let inBase = input.bindMemory(to: UInt8.self).baseAddress else { return 0 }
                return compression_decode_buffer(
                    outBase, Int(size), inBase, payload.count, nil, COMPRESSION_ZLIB
                )
            }
        }
        guard written == Int(size) else { return nil }
        guard (try? output.write(to: target)) != nil else { return nil }
        return target.path
    }
}
