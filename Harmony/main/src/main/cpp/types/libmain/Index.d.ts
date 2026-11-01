export interface OcrLine {
  x0: number; y0: number;
  x1: number; y1: number;
  x2: number; y2: number;
  x3: number; y3: number;
  confidence: number;
  text: string;
}

export interface CellReading {
  text: string;
  confidence: number;
  hasInk: boolean;
}

export const create: (detPath: string, recPath: string, charsetPath: string) => number;
export const destroy: (handle: number) => void;
export const recognize: (handle: number, pixels: Uint8Array, width: number, height: number,
                         isBgr: number) => OcrLine[];
export const recognizeCells: (handle: number, pixels: Uint8Array, width: number, height: number,
                              isBgr: number, cells: Int32Array, scale: number) => CellReading[];
