import { fileIo } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';

export interface ModelPaths {
  det: string;
  rec: string;
  charset: string;
}

const FILES: string[] = ['det.ms', 'rec.ms', 'rec.yml'];

/**
 * rawfile 不是文件系统路径，C++ 的 Model::Import 读不到；
 * 先解到沙箱目录，再把绝对路径交给 native。
 */
export async function ensureModels(context: common.UIAbilityContext): Promise<ModelPaths> {
  const dir = `${context.filesDir}/ocr-models`;
  if (!fileIo.accessSync(dir)) {
    fileIo.mkdirSync(dir);
  }

  for (const name of FILES) {
    const target = `${dir}/${name}`;
    const data = await context.resourceManager.getRawFileContent(`models/${name}`);
    const file = fileIo.openSync(
      target,
      fileIo.OpenMode.CREATE | fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.TRUNC,
    );
    try {
      fileIo.writeSync(file.fd, data.buffer as ArrayBuffer);
    } finally {
      fileIo.closeSync(file);
    }
  }

  return {
    det: `${dir}/det.ms`,
    rec: `${dir}/rec.ms`,
    charset: `${dir}/rec.yml`,
  };
}
