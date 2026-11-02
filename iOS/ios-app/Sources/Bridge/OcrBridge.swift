import CoreGraphics
import Foundation

/// 一个识别框，坐标是原图像素。
struct OcrLine {
    let left: Float
    let top: Float
    let right: Float
    let bottom: Float
    let text: String
    let confidence: Float
}

struct OcrCellReading {
    let text: String
    let confidence: Float
    let hasInk: Bool
}

/// OCR 的 Swift 入口；图片转成 RGB 缓冲后全交给 C++。
final class OcrBridge {

    private var handle: OpaquePointer?

    init?(detModelPath: String, recModelPath: String, charsetPath: String) {
        handle = engine_ocr_create(detModelPath, recModelPath, charsetPath, 4)
        if handle == nil { return nil }
    }

    deinit {
        if let handle { engine_ocr_destroy(handle) }
    }

    /// 用 bundle 里压缩过的模型建引擎：先解到缓存目录，缺文件就返回 nil。
    static func bundled() -> OcrBridge? {
        guard let paths = ModelLoader.ensureModels() else { return nil }
        return OcrBridge(detModelPath: paths.det, recModelPath: paths.rec, charsetPath: paths.charset)
    }

    func recognize(_ image: CGImage) -> [OcrLine] {
        guard let handle, let buffer = Self.rgbBytes(image) else { return [] }

        var collected: [OcrLine] = []
        withUnsafeMutablePointer(to: &collected) { pointer in
            let context = Unmanaged.passUnretained(LineBox(context: pointer)).toOpaque()
            _ = buffer.bytes.withUnsafeBufferPointer { bytes in
                engine_ocr_recognize(handle, bytes.baseAddress, Int32(buffer.width),
                                     Int32(buffer.height), 0, 0, collectLine, context, nil)
            }
        }
        return collected
    }

    /// cells 是原图像素坐标，每四个一组：left/top/right/bottom。
    func recognizeCells(_ image: CGImage, cells: [Int32], scale: Float = 1) -> [OcrCellReading] {
        guard let handle, !cells.isEmpty, let buffer = Self.rgbBytes(image) else { return [] }

        var collected: [OcrCellReading] = []
        withUnsafeMutablePointer(to: &collected) { pointer in
            let context = Unmanaged.passUnretained(CellBox(context: pointer)).toOpaque()
            cells.withUnsafeBufferPointer { cellBytes in
                buffer.bytes.withUnsafeBufferPointer { pixels in
                    cellBytes.baseAddress?.withMemoryRebound(to: engine_ocr_cell.self,
                                                             capacity: cells.count / 4) { rects in
                        _ = engine_ocr_recognize_cells(
                            handle, pixels.baseAddress, Int32(buffer.width), Int32(buffer.height),
                            0, 0, rects, Int32(cells.count / 4), scale, collectCell, context
                        )
                    }
                }
            }
        }
        return collected
    }

    // MARK: - CGImage → RGB

    private struct RgbBuffer {
        let bytes: [UInt8]
        let width: Int
        let height: Int
    }

    private static func rgbBytes(_ image: CGImage) -> RgbBuffer? {
        let width = image.width
        let height = image.height
        guard width > 0, height > 0 else { return nil }

        // data 传 nil 让 CGContext 自己分配：传 Swift 数组的 & 只在初始化调用期间有效，
        // draw 时那块内存可能已经被回收
        guard let context = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8,
            bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let base = context.data else { return nil }

        // C 侧只吃三通道，这里顺手把 alpha 丢掉。
        let rgba = base.bindMemory(to: UInt8.self, capacity: width * height * 4)
        var rgb = [UInt8](repeating: 0, count: width * height * 3)
        var source = 0
        var target = 0
        while target < rgb.count {
            rgb[target] = rgba[source]
            rgb[target + 1] = rgba[source + 1]
            rgb[target + 2] = rgba[source + 2]
            source += 4
            target += 3
        }
        return RgbBuffer(bytes: rgb, width: width, height: height)
    }
}

// 回调只能拿到 void*，用一层薄盒子把数组地址带进去。
private final class LineBox {
    let context: UnsafeMutablePointer<[OcrLine]>
    init(context: UnsafeMutablePointer<[OcrLine]>) { self.context = context }
}

private final class CellBox {
    let context: UnsafeMutablePointer<[OcrCellReading]>
    init(context: UnsafeMutablePointer<[OcrCellReading]>) { self.context = context }
}

private func collectLine(_ item: UnsafePointer<engine_ocr_item>?,
                         _ userData: UnsafeMutableRawPointer?) {
    guard let item, let userData else { return }
    let box = Unmanaged<LineBox>.fromOpaque(userData).takeUnretainedValue()
    box.context.pointee.append(
        OcrLine(
            left: item.pointee.x0, top: item.pointee.y0,
            right: item.pointee.x2, bottom: item.pointee.y2,
            text: item.pointee.text.map { String(cString: $0) } ?? "",
            confidence: item.pointee.confidence
        )
    )
}

private func collectCell(_ index: Int32, _ text: UnsafePointer<CChar>?, _ confidence: Float,
                         _ hasInk: Int32, _ userData: UnsafeMutableRawPointer?) {
    guard let userData else { return }
    let box = Unmanaged<CellBox>.fromOpaque(userData).takeUnretainedValue()
    box.context.pointee.append(
        OcrCellReading(
            text: text.map { String(cString: $0) } ?? "",
            confidence: confidence,
            hasInk: hasInk != 0
        )
    )
    _ = index
}
