/**
 * 二维码生成（服务端，2026-11-16）。
 *
 * <p>为什么放服务端：用户质问"扫码 UI 就能做，为何要弹到外部浏览器生成？"
 * —— 旧实现是点按钮后 `start url` 打开 `/pair` 页面，因为浏览器端才有 qrcodegen。
 * 现在把 QR 直接渲染成 SVG 由插件端点返回，电脑面板里用 `&lt;img&gt;` 内联显示，
 * 既不用弹浏览器，也保持"服务端生成、客户端只渲染"的分工。</p>
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const PLUGIN_DIR = join(dirname(fileURLToPath(import.meta.url)), '..');
let qrcodeLib = null;

/** 加载同目录的 qrcodegen.js（浏览器 UMD 脚本，这里用 module/exports 分支跑在 Node 里）。 */
function loadQrcode() {
  if (qrcodeLib) return qrcodeLib;
  const src = readFileSync(join(PLUGIN_DIR, 'qrcodegen.js'), 'utf8');
  const mod = { exports: {} };
  // eslint-disable-next-line no-new-func
  new Function('module', 'exports', 'window', 'self', src)(mod, mod.exports, undefined, undefined);
  qrcodeLib = mod.exports;
  return qrcodeLib;
}

/**
 * 把文本编码成二维码 SVG。
 *
 * @param {string} text 内容（例如 dshpair://… 配对串）
 * @param {{cell?: number, margin?: number, level?: 'L'|'M'|'Q'|'H'}} [opts]
 * @returns {string} SVG 字符串
 */
export function svgFor(text, opts = {}) {
  const value = String(text ?? '');
  if (!value) throw new Error('empty text');
  const cell = Number(opts.cell) > 0 ? Number(opts.cell) : 4;
  const margin = Number(opts.margin) >= 0 ? Number(opts.margin) : 2;
  const level = opts.level || 'M';
  const qrcode = loadQrcode();
  // qrcodegen 的 typeNumber 是"最小版本"，容量不够 make() 会抛 —— 逐级放大直到装下
  for (let type = 1; type <= 40; type += 1) {
    try {
      const qr = new qrcode(type, level);
      qr.addData(value);
      qr.make();
      return qr.createSvgTag(cell, margin);
    } catch {
      // 换更大版本重试
    }
  }
  throw new Error('二维码内容过长');
}
