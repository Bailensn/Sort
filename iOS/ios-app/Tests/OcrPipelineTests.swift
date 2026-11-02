import UIKit
import XCTest

@testable import Sort

/// 真模型跑一遍完整链路：bundle 里的 .gz → 解压 → ORT 前向 → DB 后处理 → CTC 解码。
/// 只在模拟器上跑，不需要真机。
final class OcrPipelineTests: XCTestCase {

    func testRecognizesFixtureTable() throws {
        let bridge = try XCTUnwrap(OcrBridge.bundled(), "引擎建不起来：模型没打进 bundle 或后端没链上")

        let url = try XCTUnwrap(
            Bundle(for: Self.self).url(forResource: "table", withExtension: "png"),
            "测试包里没有 table.png"
        )
        let image = try XCTUnwrap(UIImage(contentsOfFile: url.path)?.cgImage)

        let lines = bridge.recognize(image)
        for line in lines {
            print("[ocr] \(line.text)  \(line.confidence)")
        }

        let text = lines.map(\.text).joined(separator: " ")
        XCTAssertGreaterThanOrEqual(lines.count, 10, "只识别到 \(lines.count) 行：\(text)")
        XCTAssertTrue(text.contains("900000000001"), "识别结果：\(text)")
    }
}
