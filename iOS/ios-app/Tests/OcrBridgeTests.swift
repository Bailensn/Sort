import XCTest
@testable import Sort

final class OcrBridgeTests: XCTestCase {

    /// 模型路径不存在时应该返回 nil，而不是拿着空句柄往下跑。
    func testCreateFailsOnMissingModels() {
        let bridge = OcrBridge(
            detModelPath: "/nonexistent/det.onnx",
            recModelPath: "/nonexistent/rec.onnx",
            charsetPath: "/nonexistent/rec.yml"
        )
        XCTAssertNil(bridge)
    }

    /// 字符表读不到时同样是 nil；这条走的是 C++ 侧 loadCharsetFromFile 的失败分支。
    func testCreateFailsOnMissingCharset() {
        let temp = FileManager.default.temporaryDirectory.appendingPathComponent("empty.yml")
        FileManager.default.createFile(atPath: temp.path, contents: Data())
        let bridge = OcrBridge(
            detModelPath: temp.path,
            recModelPath: temp.path,
            charsetPath: temp.path
        )
        XCTAssertNil(bridge)
    }
}
