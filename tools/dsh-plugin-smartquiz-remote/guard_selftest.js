// guard_selftest.js —— 校验 index.js 里的本机判定能否挡住隧道转发
//
// 复刻 index.js 的判定逻辑（改动守卫时必须同步更新这里）：
//   isLoopback  只看 socket 源地址（隧道转发进来的请求也满足 ✗）
//   isLocal     白名单：源回环 + Host 必须正好是「回环主机 + DSH 主 webServer 端口」，
//               且经局域网服务器(8218)进来的请求一律不算本机
//
// 场景对照（花生壳这类隧道把公网请求转发到 127.0.0.1:8218，源地址必然是回环 ✗）：
//   ① 隧道到达 8218，Host=隧道域名             → 必须拒绝
//   ② 隧道经 19387 且保留隧道 Host              → 必须拒绝
//   ③ 隧道把 Host 改写成 127.0.0.1:8218         → 必须拒绝
//   ④ 本机浏览器正常访问 19387                  → 必须放行
//   ⑤ 本机浏览器用 localhost:19387              → 必须放行
//   ⑥ 经 19387 的隧道但 Host 带 8218 端口        → 必须拒绝
//   ⑦ 主服务器上 Host 为空                      → 必须拒绝（缺 Host 不可信）
import { createServer, request as httpRequest } from 'node:http';

// 用临时端口，避免撞上正在运行的真实服务（19387 / 8218）
const MAIN_PORT = 29387;
const LAN_PORT = 28218;

const isLoopback = (req) => {
  const a = req.socket?.remoteAddress ?? '';
  return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
};
/** 修复后的判定（与 index.js 保持一致） */
const isLocal = (req, mainPort) => {
  if (req.__viaLanServer === true) return false;
  if (!isLoopback(req)) return false;
  const host = String(req.headers?.host ?? '').trim().toLowerCase();
  if (host === '') return false;
  const allowed = mainPort > 0
    ? [`127.0.0.1:${mainPort}`, `localhost:${mainPort}`, `[::1]:${mainPort}`]
    : ['127.0.0.1', 'localhost', '[::1]'];
  return allowed.includes(host);
};
/** 修复前的判定（仅源地址）——仅用于对照，说明漏洞为什么存在 */
const isLocalLegacy = (req) => {
  const a = req.socket?.remoteAddress ?? '';
  return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
};

/** 主 webServer（DSH 自带，只听 127.0.0.1）；/legacy 路径用修复前的判定做对照 */
const main = createServer((req, res) => {
  const legacy = req.url === '/legacy';
  const ok = legacy ? isLocalLegacy(req) : isLocal(req, MAIN_PORT);
  res.writeHead(ok ? 200 : 403, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({ ok, where: 'main', legacy }));
});
/** 局域网服务器（0.0.0.0，隧道映射的就是它），标记 __viaLanServer */
const lan = createServer((req, res) => {
  req.__viaLanServer = true;
  const legacy = req.url === '/legacy';
  const ok = legacy ? isLocalLegacy(req) : isLocal(req, MAIN_PORT);
  res.writeHead(ok ? 200 : 403, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({ ok, where: 'lan', legacy }));
});

await new Promise((r) => main.listen(MAIN_PORT, '127.0.0.1', r));
await new Promise((r) => lan.listen(LAN_PORT, '0.0.0.0', r));

/**
 * 发请求并**保留自定义 Host 头**。
 * ★ 不能用 fetch：实测 node 的 fetch（undici）会忽略调用方给的 host 头，
 *   服务器永远收到 `127.0.0.1:<port>` —— 拿它测 Host 判定会全绿/全红地骗人。
 *   http.request 默认也会补 Host，必须 setHost: false 才会原样发出。
 */
const rawGet = (url, extraHeaders = {}) => new Promise((resolve) => {
  const u = new URL(url);
  const req = httpRequest({
    host: u.hostname,
    port: u.port,
    path: u.pathname,
    method: 'GET',
    setHost: false,
    headers: extraHeaders,
  }, (res) => {
    res.resume();
    res.on('end', () => resolve(res.statusCode));
  });
  req.on('error', (e) => resolve('ERR:' + e.message));
  req.end();
});

const cases = [
  ['① 隧道到达 8218（Host=隧道域名）', `http://127.0.0.1:${LAN_PORT}/pair.json`, { host: 'ir158286vw95.vicp.fun' }, 403],
  ['② 经 19387 但保留隧道 Host', `http://127.0.0.1:${MAIN_PORT}/pair.json`, { host: 'ir158286vw95.vicp.fun' }, 403],
  ['③ 隧道改写 Host 为 127.0.0.1:8218', `http://127.0.0.1:${LAN_PORT}/pair.json`, { host: `127.0.0.1:${LAN_PORT}` }, 403],
  ['④ 本机浏览器访问 19387', `http://127.0.0.1:${MAIN_PORT}/pair.json`, { host: `127.0.0.1:${MAIN_PORT}` }, 200],
  ['⑤ 本机浏览器用 localhost:19387', `http://127.0.0.1:${MAIN_PORT}/pair.json`, { host: `localhost:${MAIN_PORT}` }, 200],
  ['⑥ 经 19387 的隧道但 Host 带 8218 端口', `http://127.0.0.1:${MAIN_PORT}/pair.json`, { host: `127.0.0.1:${LAN_PORT}` }, 403],
];

let failed = 0;
for (const [name, url, headers, want] of cases) {
  const got = await rawGet(url, headers);
  const pass = got === want;
  if (!pass) failed += 1;
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}  → 期望 ${want}，实际 ${got}`);
}
// ⑦ 不给 Host 头：node 的 HTTP/1.1 解析器直接回 400（请求根本没进到守卫代码），
//    同样不构成"本机"，因此 400 与 403 都算通过。
const noHost = await rawGet(`http://127.0.0.1:${MAIN_PORT}/pair.json`, {});
const noHostPass = noHost === 400 || noHost === 403;
if (!noHostPass) failed += 1;
console.log(`${noHostPass ? 'PASS' : 'FAIL'}  ⑦ 主服务器上未给 Host 头  → 期望 400/403，实际 ${noHost}`);

// 对照：修复前只看源地址 → 隧道请求会被放行（这正是漏洞）
console.log('');
console.log('对照：修复前只按源地址判断（isLocalLegacy，走 /legacy 路径）');
let legacyLeaks = 0;
for (const [name, url, headers] of cases.slice(0, 3)) {
  const legacyUrl = url.replace('/pair.json', '/legacy');
  const got = await rawGet(legacyUrl, headers);
  if (got === 200) legacyLeaks += 1;
  console.log(`  ${got === 200 ? '放行（漏洞）' : '拒绝'}  ${name}  → HTTP ${got}`);
}

main.close();
lan.close();
console.log('');
console.log(failed === 0 ? '全部通过 ✓' : `有 ${failed} 项未通过 ✗`);
console.log(legacyLeaks >= 2 ? `对照确认：修复前有 ${legacyLeaks} 个隧道场景会被放行 → 漏洞真实存在` : '对照异常：修复前应当放行隧道场景');
process.exit(failed === 0 ? 0 : 1);
