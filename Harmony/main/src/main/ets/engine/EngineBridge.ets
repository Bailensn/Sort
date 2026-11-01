import native from 'libmain.so';
import { CellReading, OcrLine } from 'libmain.so';

/** ArkTS 侧的 OCR 封装：句柄是 number，图片缓冲直接交给 C++。 */
export class EngineBridge {
  private handle: number;

  constructor(detModelPath: string, recModelPath: string, charsetPath: string) {
    this.handle = native.create(detModelPath, recModelPath, charsetPath);
  }

  get isReady(): boolean {
    return this.handle !== 0;
  }

  recognize(pixels: Uint8Array, width: number, height: number, isBgr: boolean): OcrLine[] {
    if (this.handle === 0) return [];
    return native.recognize(this.handle, pixels, width, height, isBgr ? 1 : 0);
  }

  recognizeCells(pixels: Uint8Array, width: number, height: number, isBgr: boolean,
                 cells: Int32Array, scale: number): CellReading[] {
    if (this.handle === 0) return [];
    return native.recognizeCells(this.handle, pixels, width, height, isBgr ? 1 : 0, cells, scale);
  }

  release(): void {
    if (this.handle !== 0) {
      native.destroy(this.handle);
      this.handle = 0;
    }
  }
}
