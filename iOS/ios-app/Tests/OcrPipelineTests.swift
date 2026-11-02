import UIKit
import XCTest

@testable import Sort

/// 真模型跑完整链路：bundle 里的 .gz → 解压 → ORT 前向 → DB 后处理 → CTC 解码。
/// 拆成三段，挂了以后一眼能看出是哪一步（上一轮整条链跑挂，看不出原因）。
final class OcrPipelineTests: XCTestCase {

    func testModelsUnpack() throws {
        let paths = try XCTUnwrap(ModelLoader.ensureModels(), "模型没解出来，看看 gzip 那段")

        for (name, path) in [("det", paths.det), ("rec", paths.rec), ("charset", paths.charset)] {
            let size = fileSize(path)
            print("[model] \(name) \(size) B \(path)")
            XCTAssertGreaterThan(size, 1000, "\(name) 太小，解压出来的数据不对")
        }

        let detSize = fileSize(paths.det)
        XCTAssertGreaterThan(detSize, 50_000_000, "det 只有 \(detSize) B，原始模型约 62 MB")
    }

    private func fileSize(_ path: String) -> Int {
        let attrs = try? FileManager.default.attributesOfItem(atPath: path)
        return (attrs?[.size] as? Int) ?? 0
    }

    func testEngineCreates() throws {
        let bridge = try XCTUnwrap(
            OcrBridge.bundled(),
            "引擎建不起来，engine_ocr_last_error = \(engine_ocr_last_error())"
        )
        _ = bridge
    }

    func testRecognizesFixtureTable() throws {
        let bridge = try XCTUnwrap(OcrBridge.bundled(), "引擎建不起来")

        let url = try XCTUnwrap(
            Bundle(for: Self.self).url(forResource: "table", withExtension: "png"),
            "测试包里没有 table.png"
        )
        let image = try XCTUnwrap(UIImage(contentsOfFile: url.path)?.cgImage)
        print("[ocr] 输入 \(image.width)x\(image.height)")

        let lines = bridge.recognize(image)
        for line in lines {
            print("[ocr] \(line.text)  \(line.confidence)")
        }

        let text = lines.map(\.text).joined(separator: " ")
        XCTAssertGreaterThanOrEqual(lines.count, 10, "只识别到 \(lines.count) 行：\(text)")
        XCTAssertTrue(text.contains("900000000001"), "识别结果：\(text)")
    }
}
