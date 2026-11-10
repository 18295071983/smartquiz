// 修复 DSH 会话日志里缺 id 的 user/message（旧插件 in-process 追加路径的遗留）。
// 保持物理格式：第一个 zstd frame 只放 header 行，其余事件放后续 frame。
// 用法：node fix-message-ids.mjs <sessionsRoot> [--apply]
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import crypto from 'node:crypto';

const root = process.argv[2];
const apply = process.argv.includes('--apply');
const MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);

function decode(buf) {
  const starts = [];
  for (let i = 0; i < buf.length - 3; i += 1) {
    if (buf[i] === 0x28 && buf[i + 1] === 0xb5 && buf[i + 2] === 0x2f && buf[i + 3] === 0xfd) starts.push(i);
  }
  let text = '';
  for (let k = 0; k < starts.length; k += 1) {
    const seg = buf.subarray(starts[k], k + 1 < starts.length ? starts[k + 1] : buf.length);
    try { text += zlib.zstdDecompressSync(seg).toString('utf8'); } catch { /* 坏帧跳过 */ }
  }
  return text;
}

function encode(lines) {
  const header = lines[0] + '\n';
  const rest = lines.slice(1).join('\n') + (lines.length > 1 ? '\n' : '');
  const parts = [zlib.zstdCompressSync(Buffer.from(header, 'utf8'))];
  if (rest.trim()) parts.push(zlib.zstdCompressSync(Buffer.from(rest, 'utf8')));
  return Buffer.concat(parts);
}

const files = [];
(function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name === 'session.v4.jsonl.zstd') files.push(p);
  }
})(root);

let touched = 0;
for (const file of files) {
  let text;
  try { text = decode(fs.readFileSync(file)); } catch { continue; }
  const lines = text.split('\n').filter((l) => l.trim());
  let n = 0;
  const out = lines.map((line) => {
    let ev;
    try { ev = JSON.parse(line); } catch { return line; }
    if (ev.type === 'user/message' && ev.data && !ev.data.id) {
      ev.data.id = crypto.randomUUID();
      ev.data.role = ev.data.role || 'user';
      n += 1;
      return JSON.stringify(ev);
    }
    return line;
  });
  if (!n) continue;
  const name = path.basename(path.dirname(file));
  if (!apply) { console.log(`${name}: would fix ${n}`); touched += 1; continue; }
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  fs.copyFileSync(file, `${file}.bak-ids-${stamp}`);
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, encode(out));
  fs.renameSync(tmp, file);
  // 回读验证
  const re = decode(fs.readFileSync(file)).split('\n').filter((l) => l.trim());
  const bad = re.filter((l) => { try { const e = JSON.parse(l); return e.type === 'user/message' && !e.data?.id; } catch { return false; } }).length;
  console.log(`${name}: fixed ${n}, recheck_bad=${bad}`);
  touched += 1;
}
console.log(`\nfiles=${files.length} touched=${touched} applied=${apply}`);
