/**
 * apply() 冒烟（2026-11-16）：用最小 ctx 桩跑一遍插件挂载，抓出"apply-begin → 没到 apply-ok"的真实错误。
 *
 * <p>背景：DSH 里 .startup.json 停在 apply-begin，说明 apply() 中途抛错；
 * DSH 桌面版没有可读日志，所以在这里复现并打印堆栈。</p>
 *
 * <p>桩策略：Proxy 让任何未知 ctx 属性都返回一个可调用的空函数（同时可当对象继续取属性），
 * webServer.register 记录注册的路由，effect 立即执行回调并返回 disposer —— 足够让 apply 跑到底。</p>
 */
import { pathToFileURL } from 'node:url';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
// 让插件的启动探针写到 .startup-smoke.json，别覆盖真实探针
globalThis.__SMARTQUIZ_SMOKE__ = true;

const routes = [];
function dummyFn() {
  return dummyFn;
}
const anyProxy = new Proxy(dummyFn, {
  get(target, prop) {
    if (prop === 'then') return undefined;       // 避免被当成 Promise
    if (prop === 'length') return 0;
    return anyProxy;
  },
  apply() {
    return anyProxy;
  },
});

const webServer = {
  port: 18218,
  register(opts) {
    routes.push(opts);
    return () => {};
  },
};

const ctx = new Proxy({}, {
  get(target, prop) {
    if (prop === 'webServer') return webServer;
    if (prop === 'effect') return (fn) => {
      try { const d = fn(); return typeof d === 'function' ? d : () => {}; } catch (e) { throw e; }
    };
    if (prop === 'on') return () => () => {};
    if (prop === 'get') return () => undefined;
    if (prop === 'logger') return { info: () => {}, warn: () => {}, error: () => {}, debug: () => {} };
    if (prop === 'then') return undefined;
    if (prop === 'inject') return () => {};
    return anyProxy;
  },
});

let mod;
try {
  mod = await import(pathToFileURL(join(here, 'index.js')).href);
} catch (e) {
  console.error('IMPORT FAIL: ' + (e && e.stack ? e.stack : e));
  process.exit(1);
}

try {
  await mod.apply(ctx, {});
  console.log('APPLY OK, routes=' + routes.length);
  const seen = new Map();
  const dups = [];
  for (const r of routes) { const k = (r.kind||'') + ':' + (r.path||''); if (seen.has(k)) dups.push(k); else seen.set(k, 1); }
  console.log('重复路由: ' + (dups.length ? dups.join(', ') : '无'));
  for (const r of routes.slice(0, 5)) console.log('  route ' + (r.path || r.kind || '?'));
  process.exit(0);
} catch (e) {
  console.error('APPLY FAIL: ' + (e && e.stack ? e.stack : e));
  console.error('已注册路由数=' + routes.length);
  const last = routes[routes.length - 1];
  if (last) console.error('最后注册: ' + JSON.stringify({ kind: last.kind, path: last.path }));
  process.exit(1);
}
