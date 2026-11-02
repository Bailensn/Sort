import { fileIo } from '@kit.CoreFileKit';
import { zlib } from '@kit.BasicServicesKit';
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

  if (!fileIo.accessSync(`${dir}/${FILES[0]}`)) {
    await unpack(context, dir);
  }

  return {
    det: `${dir}/det.ms`,
    rec: `${dir}/rec.ms`,
    charset: `${dir}/rec.yml`,
  };
}

/** 包里只放一个 models.zip，省 90MB 安装包体积。 */
async function unpack(context: common.UIAbilityContext, dir: string): Promise<void> {
  const zip = `${dir}/models.zip`;
  const data = await context.resourceManager.getRawFileContent('models/models.zip');
  const file = fileIo.openSync(
    zip,
    fileIo.OpenMode.CREATE | fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.TRUNC,
  );
  try {
    fileIo.writeSync(file.fd, data.buffer as ArrayBuffer);
  } finally {
    fileIo.closeSync(file);
  }

  try {
    await zlib.decompressFile(zip, dir);
  } finally {
    if (fileIo.accessSync(zip)) {
      fileIo.unlinkSync(zip);
    }
  }
}
