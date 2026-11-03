import { fileIo } from '@kit.CoreFileKit';
import { http } from '@kit.NetworkKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';

export interface ModelPaths {
  det: string;
  rec: string;
  charset: string;
}

interface ModelSpec {
  /** 主源文件名（网盘上多了 .pak 后缀）。 */
  remote: string;
  /** 备用源文件名（GitHub Release 用原名）。 */
  backup: string;
  local: string;
  size: number;
  sha256: string;
}

interface SignResponse {
  code: number;
  data: string;
  msg: string;
}

const VERSION = 'mnn-1';
const SHARE_KEY = 'Qdws1';
const SHARE_API = 'https://pan.fuwushang.net/api/v3/share/download';
const BACKUP_BASE = 'https://github.com/Bailensn/Sort/releases/download/models-v1';

/**
 * 模型不随包发：rawfile 里的 models.zip 已经不打了，首启联网拉。
 * 字节数与 sha256 和 tools/models.mnn.lock 一致，校验不过就当没下。
 */
const FILES: ModelSpec[] = [
  {
    remote: 'det.mnn.pak',
    backup: 'det.mnn',
    local: 'det.mnn',
    size: 61993116,
    sha256: '25b63452e40842c65df963c67bbd0e8714cf51a4d0b3b1966b4c6a92e7249131',
  },
  {
    remote: 'rec.mnn.pak',
    backup: 'rec.mnn',
    local: 'rec.mnn',
    size: 76498560,
    sha256: 'c5dfb3d7cd2af443c213bc050fc394cb2a40b66f0619b8228d44d9878c77d78b',
  },
  {
    remote: 'rec.yml.pak',
    backup: 'rec.yml',
    local: 'rec.yml',
    size: 150580,
    sha256: '991b700facf5b50a7de193468207d5f4255b538dde0d312ae3b7c7a9b6873129',
  },
];

function modelDir(context: common.UIAbilityContext): string {
  return `${context.filesDir}/ocr-models`;
}

function readText(path: string): string {
  if (!fileIo.accessSync(path)) return '';
  try {
    return fileIo.readTextSync(path);
  } catch (e) {
    return '';
  }
}

function fileSize(path: string): number {
  if (!fileIo.accessSync(path)) return -1;
  try {
    return fileIo.statSync(path).size;
  } catch (e) {
    return -1;
  }
}

/** 只比大小，不重算哈希：140 MB 每次启动都算一遍太慢。 */
function ready(dir: string): boolean {
  if (readText(`${dir}/version`).trim() !== VERSION) return false;
  for (const spec of FILES) {
    if (fileSize(`${dir}/${spec.local}`) !== spec.size) return false;
  }
  return true;
}

/** C++ 的 Model::Import 读不到 rawfile，必须落到沙箱再给绝对路径。 */
export async function ensureModels(context: common.UIAbilityContext): Promise<ModelPaths> {
  const dir = modelDir(context);
  if (!fileIo.accessSync(dir)) {
    fileIo.mkdirSync(dir);
  }

  if (!ready(dir)) {
    // 换了模型版本就整个重来，避免新旧混用
    const version = readText(`${dir}/version`).trim();
    if (version !== '' && version !== VERSION) {
      fileIo.rmdirSync(dir);
      fileIo.mkdirSync(dir);
    }
    for (const spec of FILES) {
      await fetchOne(dir, spec);
    }
    fileIo.writeTextSync(`${dir}/version`, VERSION);
  }

  return {
    det: `${dir}/det.mnn`,
    rec: `${dir}/rec.mnn`,
    charset: `${dir}/rec.yml`,
  };
}

async function fetchOne(dir: string, spec: ModelSpec): Promise<void> {
  const target = `${dir}/${spec.local}`;
  for (let attempt = 0; attempt < 2; attempt++) {
    if (fileSize(target) === spec.size) return;
    // 主源失败立刻吃备用源；两轮都不成就是真失败
    const sources: string[] = [];
    const signed: string = await signUrl(spec.remote);
    if (signed !== '') {
      sources.push(signed);
    }
    sources.push(`${BACKUP_BASE}/${spec.backup}`);
    for (const url of sources) {
      const ok: boolean = await download(url, target);
      if (ok && (await verify(target, spec))) return;
    }
  }
  throw new Error(`模型下载失败：${spec.local}`);
}

/** 换一个签名直链；失败返回空串。直链约 1 小时过期，所以每次都现换。 */
async function signUrl(remote: string): Promise<string> {
  const request = http.createHttp();
  try {
    const url = `${SHARE_API}/${SHARE_KEY}?path=${encodeURIComponent('/' + remote)}`;
    const response = await request.request(url, {
      method: http.RequestMethod.PUT,
      expectDataType: http.HttpDataType.STRING,
      connectTimeout: 15000,
      readTimeout: 30000,
      extraData: '',
    });
    if (response.responseCode !== 200) return '';
    const body = JSON.parse(response.result as string) as SignResponse;
    if (body.code !== 0 || body.data === undefined || body.data === '') return '';
    return body.data;
  } catch (e) {
    return '';
  } finally {
    request.destroy();
  }
}

/** 流式下载，边收边写盘：一个 76 MB 的文件全灌进内存会直接把手机压垮。 */
function download(url: string, path: string): Promise<boolean> {
  return new Promise<boolean>((resolve: (value: boolean) => void) => {
    const request = http.createHttp();
    let file: fileIo.File | null = null;
    let settled = false;
    const finish = (ok: boolean): void => {
      if (settled) return;
      settled = true;
      if (file !== null) {
        try {
          fileIo.closeSync(file);
        } catch (e) {
          // 关不上也无所谓，反正是失败路径
        }
      }
      request.destroy();
      resolve(ok);
    };

    try {
      file = fileIo.openSync(
        path,
        fileIo.OpenMode.CREATE | fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.TRUNC,
      );
    } catch (e) {
      finish(false);
      return;
    }

    request.on('dataReceive', (data: ArrayBuffer) => {
      try {
        if (file !== null) {
          fileIo.writeSync(file.fd, data);
        }
      } catch (e) {
        finish(false);
      }
    });
    request.on('dataEnd', () => {
      finish(true);
    });
    request.requestInStream(
      url,
      { method: http.RequestMethod.GET, connectTimeout: 15000, readTimeout: 60000 },
      (err: BusinessError) => {
        if (err) {
          finish(false);
        }
      },
    );
  });
}

async function verify(path: string, spec: ModelSpec): Promise<boolean> {
  if (fileSize(path) !== spec.size) return false;
  const actual: string = await sha256(path);
  return actual === spec.sha256;
}

async function sha256(path: string): Promise<string> {
  const md = cryptoFramework.createMd('SHA256');
  const file = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
  try {
    const buffer = new ArrayBuffer(1 << 20);
    while (true) {
      const read = fileIo.readSync(file.fd, buffer);
      if (read <= 0) break;
      await md.update({ data: buffer.slice(0, read) });
    }
    const digest = await md.digest();
    return toHex(digest.data);
  } finally {
    fileIo.closeSync(file);
  }
}

function toHex(data: Uint8Array): string {
  let out = '';
  for (let i = 0; i < data.length; i++) {
    const byte = data[i];
    out += byte < 16 ? `0${byte.toString(16)}` : byte.toString(16);
  }
  return out;
}
