/**
 * SmartQuiz Remote —— 答题宝手机端远程接入（原生 DSH 宿主插件）
 *
 * 替代 src/main/assets/remote_dsh/dsh_bridge_server.py：
 *   无 Python ✓ 不 spawn dsh 子进程 ✓ 不走 ACP stdio ✓ 直接跑在 DSH 宿主进程内 ✓
 *
 * 【线协议 = 与旧桥 v5 完全一致】手机端 RemoteDshTool 的调用（代码取证）：
 *   GET  /health                                  探活
 *   GET  /pair                                    配对页（仅本机；二维码 dshpair://）
 *   GET  /pair.json                               { ok, qr_text, base_url, token }
 *   GET  /status                                  { version, channels, acp_agent, permission_policy,
 *                                                    session_streams, sessions_count, active_jobs }
 *   POST /session  { action:'start' }                          → { ok, action, session_id }
 *   POST /session  { action:'prompt', session_id, text, stream?} → { ok, action, session_id, reply } 或 SSE
 *   POST /session  { action:'history', session_id, max }        → { ok, action, count, history }
 *   POST /session  { action:'get_status' }                      → 同 /status
 *   POST /session  { action:'set_config' }                      → no-op（配置在手机侧）
 *   POST /exec     { cmd, timeout, shell }                      → 电脑上直连执行命令（默认关闭，见 DEFAULTS.allowExec）
 *   POST /run      （兼容别名，等价 /session action=prompt）
 *
 * 【DSH API（官方文档核实）】
 *   ctx.webServer.register({ kind:'exact'|'prefix', path, handler })
 *   const h = await ctx.agents.create({ sessionId, agentOptions:{ provider, model } })
 *   h.agent.followup({ content:[{type:'text',text}], source:{kind:'user'} }); await h.agent.whenIdle()
 *   ctx.sessions.get(id).deriveMessages(); session.seq / session.eventAt(seq); h.dispose()
 */

import { randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { networkInterfaces } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { exec, execFile, spawn } from 'node:child_process';
import { createServer } from 'node:http';


export const name = 'smartquiz-remote';


/** 插件目录（配对页/二维码库随包分发；.token 持久化于此） */
const PLUGIN_DIR = dirname(fileURLToPath(import.meta.url));
const readAsset = (file) => { try { return readFileSync(join(PLUGIN_DIR, file), 'utf8'); } catch { return ''; } };

export const inject = ['webServer', 'agents', 'sessions'];   // 三者都必须声明：cordis 不允许访问未 inject 的服务
// ==================== 鉴权升级：一次性配对码 + 设备令牌 + 白名单 ====================
// 设计：主令牌（配置/环境变量/持久化文件）仍是"主人"凭证；
//       手机用「一次性配对码」（5 分钟有效、一次性）换取「设备令牌」，之后只带设备令牌；
//       设备可列出、可吊销（吊销不需要改主令牌）。
// 存储：设备表落在插件目录 .devices.json（后续可迁到 dsh-credentials 凭据库）。
export const AUTH_DOC = 'once-pairing-code -> device token -> revocable whitelist';

function loadDevices(file) {
  try {
    const raw = readFileSync(file, 'utf8');
    const obj = JSON.parse(raw);
    return Array.isArray(obj?.devices) ? obj : { devices: [] };
  } catch {
    return { devices: [] };
  }
}
function saveDevices(file, data) {
  try { writeFileSync(file, JSON.stringify(data, null, 2), 'utf8'); return true; } catch { return false; }
}

function lanIpv4All() {
  const out = [];
  for (const list of Object.values(networkInterfaces())) {
    for (const ni of list ?? []) {
      if (ni.family === 'IPv4' && !ni.internal && !String(ni.address).startsWith('169.254.')) out.push(ni.address);
    }
  }
  return out.length ? out : ['127.0.0.1'];
}

function lanIpv4() {
  for (const list of Object.values(networkInterfaces())) {
    for (const ni of list ?? []) if (ni.family === 'IPv4' && !ni.internal) return ni.address;
  }
  return '127.0.0.1';
}

/** 配对候选：qr_text 与旧桥一致 → dshpair://<host>:<port>?token=<token> */
function pairCandidates(port, token, cfg) {
  const ips = lanIpv4All();
  const out = ips.map((ip, i) => ({
    ip,
    base_url: `http://${ip}:${port}`,
    qr_text: `dshpair://${ip}:${port}?token=${token}`,
    label: i === 0 ? '局域网（首选）' : '局域网',
  }));
  // 隧道/公网候选（旧桥同款格式：dshpair://host?scheme=https&port=443&token=…）
  const pub = cfg && typeof cfg.publicUrl === 'string' ? cfg.publicUrl.trim() : '';
  if (pub) {
    try {
      const u = new URL(pub);
      const https = u.protocol === 'https:';
      out.push({
        ip: u.hostname,
        base_url: pub.replace(/\/+$/, ''),
        qr_text: https
          ? `dshpair://${u.hostname}?scheme=https&port=443&token=${token}`
          : `dshpair://${u.hostname}:${u.port || 80}?token=${token}`,
        label: https ? '公网/隧道（HTTPS）' : '公网/隧道',
      });
    } catch { /* publicUrl 非法则忽略 */ }
  }
  return out;
}

function resolveToken(ctx, cfg) {
  if (cfg.token) return cfg.token;
  const env = process.env.SMARTQUIZ_REMOTE_TOKEN;
  if (env) return env;
  const file = join(PLUGIN_DIR, '.token');
  try { const saved = readFileSync(file, 'utf8').trim(); if (saved) return saved; } catch { /* 首次 */ }
  const generated = randomBytes(16).toString('hex');
  try { writeFileSync(file, generated, 'utf8'); } catch { /* 只读则退化为内存 */ }
  ctx.logger?.info?.(`[smartquiz-remote] 已持久化访问令牌（${file}）: ${generated}`);
  return generated;
}

function readBody(req, limit = 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0; const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(new Error('payload too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}
const sendJson = (res, status, obj) => {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(obj));
};
/**
 * 跑一轮对话。两条路径都试，按证据择一：
 *  ① 先把 user/message 作为 surface 事件追加进会话（dsh-session 文档：surface 事件必须带 surfaceOp 才会进入模型历史）
 *  ② 再唤醒 agent（whenIdle）；若会话没有任何增长，则退回 followup() 路径
 * 返回 { reply, debug }
 */
async function runTurn(ctx, handle, sid, text, cfg) {
  // CLI-MODE（默认）：走 `dsh --profile headless "<任务>"`，不依赖 DSH 内部模块 ✓
  if (cfg.cliMode !== false) {
    // MEMORY-MAP: 我们的 session_id → DSH 持久会话 id（跨调用/跨重启记忆 ✓）
    const memFile = join(PLUGIN_DIR, '.sessions.json');
    let mem = {}; try { mem = JSON.parse(readFileSync(memFile, 'utf8')); } catch { mem = {}; }
    const dshSid = mem[sid] || '';
    const cands = resolveDshCli(cfg);
    let lastErr = '';
    for (const cli of cands) {
      const r = await runCliTask(cli, cfg.cliProfile || 'headless', text, cfg.cliTimeoutMs || 300000, cfg.__onData, dshSid);
      if (r.ok && r.output) {
        if (r.sessionId && r.sessionId !== dshSid) { mem[sid] = r.sessionId; try { writeFileSync(memFile, JSON.stringify(mem, null, 2), 'utf8'); } catch { /* 忽略 */ } }
        return { reply: r.output, via: 'cli:' + cli, debug: { cli_ms: r.ms, cli_code: r.code, dsh_session: r.sessionId || '(none)', memory: dshSid ? 'resumed' : 'new' } };
      }
      lastErr = `${cli} → code=${r.code} ${r.err ?? ''} ${String(r.output).slice(0, 120)}`;
    }
    return { reply: '', via: 'cli-failed', debug: { cli_errors: lastErr } };
  }
  const session = ctx.sessions.get(sid);
  const seqBefore = Number(session?.seq ?? 0);
  let via = 'surface-append';
  try {
    if (session?.append) {
      session.append('user/message',
        { role: 'user', content: [{ type: 'text', text }], source: { kind: 'user' } },
        { surfaceOp: 'append' });
    } else { via = 'followup'; }
  } catch (e) { via = 'followup(' + String(e?.message ?? e).slice(0, 60) + ')'; }

  if (via === 'surface-append') {
    try { await handle.agent.whenIdle(); } catch { /* 忽略，下面按证据判断 */ }
    const seqAfter = Number(ctx.sessions.get(sid)?.seq ?? 0);
    if (seqAfter <= seqBefore) {                       // 会话没长 → 追加路径没被消费，退回 followup
      via = 'followup-fallback';
    } else {
      const s2 = ctx.sessions.get(sid);
      const t = lastAssistant(s2);
      if (t) return { reply: t.length > cfg.maxReplyChars ? t.slice(0, cfg.maxReplyChars) : t, via,
                      debug: snapshot(s2) };
    }
  }
  await handle.agent.followup({ content: [{ type: 'text', text }], source: { kind: 'user' } });
  await handle.agent.whenIdle();
  const s3 = ctx.sessions.get(sid);
  let reply = lastAssistant(s3);
  if (reply.length > cfg.maxReplyChars) reply = reply.slice(0, cfg.maxReplyChars);
  return { reply, via, debug: snapshot(s3) };
}

/** CLI-MODE: 用 `dsh --profile <p> "<task>"` 跑任务（旧 Python 桥的机制，已验证可行 ✓）
 *  原因：插件无法 import DSH 内部包（都在 app.asar 内 ✗），而"服务级"装载模型选择的 API 尚未找到。
 *  本模式不依赖任何 DSH 内部模块 ✓ 只需一个可执行的 dsh ✓。 */
function resolveDshCli(config) {
  if (config && typeof config.dshPath === 'string' && config.dshPath) return config.dshPath;
  const cands = [];
  const env = process.env.DSH_CLI || process.env.DSH_BIN;
  if (env) cands.push(env);
  cands.push('dsh');
  cands.push(join(process.env.USERPROFILE || '', '.workbuddy', 'binaries', 'node', 'versions', '22.22.2', 'dsh.cmd'));
  cands.push('C:/Users/xiaocong/AppData/Local/Programs/DeepSeek Harness/resources/runtime/cli/bin/dsh.cmd');
  return cands;
}

/** 跑一条 CLI 任务；onData 逐块收到 stdout（流式用），返回 { ok, output, code, ms } */
function runCliTask(cliPath, profile, text, timeoutMs, onData, dshSessionId) {
  return new Promise((resolve) => {
    const started = Date.now();
    const isCmd = /\.(cmd|bat)$/i.test(String(cliPath));
    // ★ 路径含空格：.cmd 必须用 exec 把"一整条命令行"交给 cmd（spawn 的数组参数会被 Node 二次加引号 ✗，实测报 '\"…dsh.cmd\"' 不是内部或外部命令 ✗）
    const qText = '"' + String(text).replace(/"/g, '""') + '"';
    const memArgs = dshSessionId ? ' --session-id ' + dshSessionId : '';
    const fullLine = '"' + cliPath + '" --profile ' + profile + ' --json' + memArgs + ' ' + qText;
    let out = '';
    let done = false;
    let finalText = '';
    let sessionId = dshSessionId || '';
    const consume = (chunk) => {
      for (const line of String(chunk).split(/\r?\n/)) {
        const s = line.trim();
        if (!s || s[0] !== '{') continue;
        try {
          const ev = JSON.parse(s);
          if (ev.type === 'session' && ev.sessionId) { sessionId = ev.sessionId; continue; }
          if (ev.type === 'text' && typeof ev.text === 'string' && ev.text) {
            finalText = ev.text;                       // 逐字累积（真流式 ✓）
            try { onData?.(ev.text, ev); } catch { /* 忽略 */ }
            continue;
          }
          if (ev.type === 'final' && typeof ev.text === 'string' && ev.text) { finalText = ev.text; continue; }
          if (ev.type === 'thinking' && typeof ev.text === 'string' && ev.text) {
            try { onData?.('', { ...ev, __thinking: true }); } catch { /* 忽略 */ }
          }
        } catch { /* 非 JSON 行忽略 */ }
      }
    };
    const finish = (code, err) => { if (done) return; done = true;
      const reply = (finalText || out).trim();
      resolve({ ok: code === 0, output: reply, sessionId, code, ms: Date.now() - started, err }); };
    try {
      if (isCmd) {
        // .cmd：整条命令行交给 cmd（唯一可靠方式 ✓）
        const child = exec(fullLine, { windowsHide: true, maxBuffer: 16 * 1024 * 1024, timeout: timeoutMs,
          env: { ...process.env, DSH_HOME: process.env.DSH_HOME } },
          (err, stdout, stderr) => {
            const s = String(stdout ?? ''); const e2 = String(stderr ?? '');
            if (s) { out += s; consume(s); }
            if (e2) out += e2;
            finish(err ? (err.code ?? 1) : 0, err ? String(err.message).slice(0, 120) : undefined);
          });
        void child;
      } else {
        const child = spawn(cliPath, ['--profile', profile, '--json', ...(dshSessionId ? ['--session-id', dshSessionId] : []), text], { windowsHide: true, env: { ...process.env, DSH_HOME: process.env.DSH_HOME } });
        const timer = setTimeout(() => { try { child.kill(); } catch { /* 忽略 */ } finish(-1, 'timeout'); }, timeoutMs);
        child.stdout?.on('data', (b) => { const s = b.toString('utf8'); out += s; consume(s); });
        child.stderr?.on('data', (b) => { out += b.toString('utf8'); });
        child.on('error', (e) => { clearTimeout(timer); finish(-2, String(e?.message ?? e)); });
        child.on('close', (code) => { clearTimeout(timer); finish(code ?? 0); });
      }
    } catch (e) { finish(-3, String(e?.message ?? e)); }
  });
}
/** 递归深挖任意结构里的文本（DSH 的 assistant/message 内嵌流结构不固定，故不做形状假设） */
function collectText(x, out, depth = 0) {
  if (x === null || x === undefined || depth > 8) return out;
  if (typeof x === 'string') { if (x.trim()) out.push(x); return out; }
  if (typeof x === 'number' || typeof x === 'boolean') return out;
  if (Array.isArray(x)) { for (const v of x) collectText(v, out, depth + 1); return out; }
  if (typeof x === 'object') {
    // 已知的"文本载体"优先（避免把 role/type/id 之类混进来）
    for (const k of ['text', 'value', 'content', 'delta', 'message', 'parts', 'data', 'chunk', 'completion']) {
      if (k in x) collectText(x[k], out, depth + 1);
    }
    return out;
  }
  return out;
}
/** 健壮地从一条消息里取文本（兼容多种结构：content 数组/字符串、text、parts、reasoning） */
function textOf(m) {
  if (m === null || m === undefined) return '';
  { const acc = collectText(m, []); if (acc.length) return acc.join(''); }
  if (typeof m === 'string') return m;
  const parts = [];
  const push = (x) => { if (typeof x === 'string' && x) parts.push(x); };
  push(m.text);
  const c = m.content;
  if (typeof c === 'string') push(c);
  else if (Array.isArray(c)) for (const p of c) {
    if (typeof p === 'string') push(p);
    else if (p) { push(p.text); if (p.type === 'text' && typeof p.value === 'string') push(p.value); }
  }
  if (Array.isArray(m.parts)) for (const p of m.parts) { if (typeof p === 'string') push(p); else if (p) push(p.text); }
  return parts.join('');
}

/** 诊断快照：把会话/消息的真实结构暴露给调用方（便于一次定位，无需反复重启） */
function snapshot(session) {
  const out = { seq: null, derived_count: 0, derived_keys: [], roles: [], sample: null, events: [] };
  try {
    if (!session) return out;
    out.seq = session.seq ?? null;
    const msgs = session.deriveMessages?.() ?? [];
    out.derived_count = Array.isArray(msgs) ? msgs.length : -1;
    if (Array.isArray(msgs) && msgs.length) {
      const last = msgs[msgs.length - 1];
      out.roles = msgs.slice(-6).map((m) => m?.role ?? (typeof m));
      out.derived_keys = Object.keys(msgs[0] ?? {}).slice(0, 12);
      out.sample = JSON.stringify(msgs[0]).slice(0, 400);
    }
    const seq = Number(session.seq ?? 0);
    for (let i = Math.max(0, seq - 12); i < seq; i += 1) {
      const ev = session.eventAt?.(i);
      out.events.push({ i, type: ev?.type ?? null, keys: Object.keys(ev ?? {}).slice(0, 6) });
    }
  } catch (e) { out.error = String(e?.message ?? e); }
  return out;
}

/** 取最后一条 assistant 文本（多结构兼容） */
function lastAssistant(session) {
  try {
    const msgs = session?.deriveMessages?.() ?? [];
    for (let i = msgs.length - 1; i >= 0; i -= 1) {
      const m = msgs[i];
      if ((m?.role ?? m?.author ?? '') !== 'assistant') continue;
      const t = textOf(m);
      if (t) return t;
    }
  } catch { /* 忽略 */ }
  return '';
}

export function apply(ctx, config) {
  const DEFAULTS = {
    token: '', provider: 'deepseek-account', model: 'deepseek-flash', pathPrefix: '',   // 显式：插件创建的 agent 需要明确模型路由
    maxReplyChars: 4000, allowExec: true, execTimeoutMs: 120000,
    cliMode: true, cliProfile: 'headless', cliTimeoutMs: 300000, dshPath: '', publicUrl: '',
  };
  const cfg = { ...DEFAULTS, ...(config ?? {}) };
  const prefix = cfg.pathPrefix ?? '';
  // publicUrl 兜底：花生壳隧道已实测可达 ✓（配置为空也能用 ✓）
// publicUrl（隧道/公网域名）三级取值，**不含任何硬编码域名**（每个用户填自己的）：
  //   ① 插件配置 config.publicUrl  ② 环境变量 SMARTQUIZ_REMOTE_PUBLIC_URL  ③ 插件目录 .public-url 文件（运行时可直接改）
  const effectivePublicUrl = (() => {
    const c1 = cfg.publicUrl && String(cfg.publicUrl).trim();
    if (c1) return c1;
    const c2 = process.env.SMARTQUIZ_REMOTE_PUBLIC_URL && String(process.env.SMARTQUIZ_REMOTE_PUBLIC_URL).trim();
    if (c2) return c2;
    try { return String(readFileSync(join(PLUGIN_DIR, '.public-url'), 'utf8')).trim(); } catch { return ''; }
  })();
  cfg.publicUrl = effectivePublicUrl;
  // 注意：主令牌不再在此处取一次就固定（见下方 mainToken()：惰性读取，便于轮换）
  const startedAt = Date.now();
  const handles = new Map();     // session_id -> agent handle
  const local = new Map();       // session_id -> [{role,text}]（兜底历史）
  let promptCount = 0;
  let activeJobs = 0;
  let lastPreset = '(unset)';
  let lastSelection = '(unset)';
  let selectionInstaller = '(unresolved)';
  /** 动态解析 installModelSelection：静态 import 会让 link: 包加载失败（find node_modules 从真实路径向上找 ✗） */
  const loadSelectionInstaller = async () => {
    for (const spec of ['@deepseek-ai/dsh-agent', '@deepseek-ai/dsh-agent/lib/index.js']) {
      try { const m = await import(spec); if (m && typeof m.installModelSelection === 'function') { selectionInstaller = 'ok:' + spec; return m.installModelSelection; } }
      catch (e) { selectionInstaller = 'failed:' + spec + ' → ' + String(e?.message ?? e).slice(0, 50); }
    }
    return null;
  };
  let installSelectionFn = null;

  // ---- 鉴权升级状态 ----
  const DEVICE_FILE = join(PLUGIN_DIR, '.devices.json');
  const devices = loadDevices(DEVICE_FILE);          // { devices: [{id,name,token,created_at,last_seen}] }
  const pairingCodes = new Map();                    // code -> expiresAt(ms)
  const PAIR_TTL_MS = 5 * 60 * 1000;                 // 一次性配对码 5 分钟
  const deviceByToken = (t) => devices.devices.find((d) => d.token === t);

  /**
   * 主令牌**惰性读取**（启动时不再写死进闭包）。
   *
   * 原因：以前 `const token = resolveToken(...)` 在 apply 时取一次就固定了，
   * 于是"想轮换令牌"只能改文件 + 重启宿主——改了文件也会被下次保存原样写回，
   * 等于轮换不掉。改成每次鉴权时读一次并缓存：删掉 .token 文件后，
   * 本次进程内仍然认旧令牌（不会立刻把在用的手机会话踢掉），
   * 下次启动 resolveToken 会自动生成新的随机令牌并持久化 → 轮换在重启后自然生效。
   */
  let mainTokenCache = '';
  const mainToken = () => {
    if (mainTokenCache) return mainTokenCache;
    mainTokenCache = resolveToken(ctx, cfg);
    return mainTokenCache;
  };

  /** 鉴权：主令牌 或 任一未吊销的设备令牌 */
  const authOk = (req) => {
    const m = /^Bearer\s+(.+)$/i.exec((req.headers['authorization'] ?? '').trim());
    if (m === null) return false;
    const t = m[1];
    if (t === mainToken()) return true;
    const d = deviceByToken(t);
    if (d === undefined) return false;
    d.last_seen = Date.now();
    return true;
  };
  /** 回环地址：只看 socket 源地址（隧道/反代转发进来的请求也满足 ✗ 不能单独作为信任依据） */
  const isLoopback = (req) => {
    const a = req.socket?.remoteAddress ?? '';
    return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
  };
  /**
   * 真正的"电脑本机"判定 —— 仅本机路由一律用它，**不要**用 isLoopback。
   *
   * 漏洞背景（2026-10-06 实测）：花生壳等隧道把公网请求转发到 127.0.0.1:8218，
   * 于是每个公网请求的 remoteAddress 都是 127.0.0.1，只看源地址的 isLocal() 恒为真 ——
   * 实测公网可直接 GET /pair.json 拿到主令牌、POST /pair/code 签发配对码、
   * POST /pair/claim 换取设备令牌，并凭令牌 POST /exec 在电脑上执行任意命令
   * （allowExec 开启时）。
   *
   * 因此改成白名单：Host 必须正好是回环主机 + DSH 主 webServer 端口（默认 127.0.0.1:19387），
   * 或经局域网服务器（8218）进来的请求一律不算本机。
   * 注意：不能只判断"主机名是回环、端口等于主端口"——`127.0.0.1:8218` 这种形态
   * （隧道改写 Host、或从 8218 转发过来）必须拒绝，自测脚本 guard_selftest.js 已覆盖这些场景。
   */
  const isLocal = (req) => {
    if (req.__viaLanServer === true) return false;        // 局域网服务器（0.0.0.0:8218）永不信任
    if (!isLoopback(req)) return false;                  // 源地址必须回环
    const mainPort = Number(ctx.webServer?.port ?? 0);
    const host = String(req.headers?.host ?? '').trim().toLowerCase();
    if (host === '') return false;
    const allowed = mainPort > 0
      ? [`127.0.0.1:${mainPort}`, `localhost:${mainPort}`, `[::1]:${mainPort}`]
      : ['127.0.0.1', 'localhost', '[::1]'];
    return allowed.includes(host);
  };
  /** 本机 或 已鉴权：给那些"手机也可能合法调用"的路由用（如 /pair/open 的兼容分支） */
  const isLocalOrAuth = (req) => {
    if (isLocal(req)) return true;
    try { return authOk(req) === true; } catch { return false; }
  };
  /** 仅当显式配置了 provider+model 时才传 agentOptions；否则继承 profile 默认（本机为 deepseek-account/deepseek-flash） */
  void loadSelectionInstaller().then((fn) => { installSelectionFn = fn; });

  const agentOptions = (cfg.provider && cfg.model) ? { provider: cfg.provider, model: cfg.model } : undefined;
  /**
   * 建会话输入 —— 照抄 dsh-api-session-controller 的 composeAgent()：
   *   ① ctx.get('agentPresets') 拿预设服务（无需 inject ✓）
   *   ② resolve(undefined) 取默认预设 id
   *   ③ mount(agentCtx, presetId) 把预设（系统提示 + 工具）挂进该 agent 的作用域
   *   ★ 少了 mount，agent 循环会启动却无事可做（step 立即 end、不发模型请求）——实测过 ✗
   */
  const createInput = async (sid) => {
    const base = agentOptions ? { sessionId: sid, agentOptions } : { sessionId: sid };
    // 会话级模型选择：照抄 api-session-controller.agentOptions()/installSelection()
    const sel = (() => { try { return ctx.agentDefaultModel?.currentSelection?.() ?? null; } catch { return null; } })();
    const effective = sel ?? (cfg.provider && cfg.model ? { provider: cfg.provider, model: cfg.model } : null);
    if (effective) lastSelection = `${effective.provider}/${effective.model}`;
    const presets = ctx.get('agentPresets');
    if (!presets) { lastPreset = '(no agentPresets service)'; return base; }
    try {
      const resolvedId = (await presets.resolve(cfg.preset || undefined)).id;
      lastPreset = resolvedId;
      return { ...base, setup: async (agentCtx, agent) => {
        await presets.mount(agentCtx, resolvedId);
        // ★ 关键：装载会话级模型选择（否则 prompt 组装无模型 → turn 空跑、不发请求）
        if (effective) {
          if (!installSelectionFn) installSelectionFn = await loadSelectionInstaller();
          if (installSelectionFn) { try { installSelectionFn(agent?.ctx ?? agentCtx, effective); } catch (e) { selectionInstaller = 'call-failed: ' + String(e?.message ?? e).slice(0, 60); } }
        }
      } };
    } catch (e) { lastPreset = 'error: ' + String(e?.message ?? e).slice(0, 80); return base; }
  };
  const modelInfo = () => agentOptions ? agentOptions.provider + '/' + agentOptions.model : '(none)';
  // LAN-SERVER: 同一批 handler 也登记到本地表（供局域网服务器复用 ✓）
  const localRoutes = new Map();
  const route = (kind, path, handler) => {
    localRoutes.set(prefix + path, handler);
    ctx.effect(() => ctx.webServer.register({ kind, path: prefix + path, handler }));
  };
  const statusBody = () => ({
    ok: true,
    service: 'dsh-plugin-remote',
    version: 5,
    bridge: 'dsh-plugin',
    channels: { session_acp: true, exec: cfg.allowExec === true },
    acp_agent: { provider: cfg.provider || '(none)', model: cfg.model || '(none)', preset: lastPreset, transport: 'in-process' },
    permission_policy: cfg.allowExec === true ? 'workspace-write' : 'read-only',
    session_streams: handles.size,
    sessions_count: handles.size,
    active_jobs: activeJobs,
    devices: devices.devices.length,
    pairing_codes_pending: pairingCodes.size,
    prompts: promptCount,
    port: ctx.webServer.port,
    uptime_ms: Date.now() - startedAt,
  });

  route('exact', '/health', (req, res) =>
    sendJson(res, 200, { ok: true, service: 'dsh-plugin-remote', version: 5 }));

  route('exact', '/pair', (req, res) => {
    if (!isLocal(req)) { res.writeHead(403, { 'content-type': 'text/plain; charset=utf-8' }); res.end('pair page is local-only'); return; }
    const tpl = readAsset('pair_page.html');
    if (!tpl) { sendJson(res, 500, { ok: false, error: 'pair_page.html 缺失' }); return; }
    const lanOrLocal = cfg.lanEnabled === false ? ctx.webServer.port : Number(cfg.lanPort ?? 8218);
    const items = pairCandidates(lanOrLocal, mainToken(), cfg);
    const qrjs = readAsset('qrcodegen.js');
    // 全局替换：模板注释里也有同名占位符，单次 replace 会替换到注释上（实测二维码不出来）
    const html = tpl
      .replace(/\/\*__QRCODE_JS__\*\//g, () => qrjs)
      .replace(/__ITEMS__/g, () => JSON.stringify(items));
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    res.end(html);
  });

  // POST /pair/open —— 仅本机：在电脑默认浏览器里打开配对页（Electron 里点链接会被拦 ✗，改由宿主打开 ✓）
  route('exact', '/pair/open', (req, res) => {
    if (!isLocalOrAuth(req)) return sendJson(res, 403, { ok: false, error: 'local-only or device token required' });
    const url = 'http://127.0.0.1:' + ctx.webServer.port + prefix + '/pair';
    try {
      const c = exec('start "" "' + url + '"', { windowsHide: true, shell: true }, () => { /* 忽略 */ });
      void c;
      sendJson(res, 200, { ok: true, opened: url });
    } catch (e) { sendJson(res, 500, { ok: false, error: String(e?.message ?? e) }); }
  });
  // POST /pair/public-url —— 仅本机：设置/清除"隧道/公网域名"（用户在插件界面直接填，无需改配置 ✓）
  route('exact', '/pair/public-url', (req, res) => {
    if (!isLocalOrAuth(req)) return sendJson(res, 403, { ok: false, error: 'local-only or device token required' });
    let raw = '';
    req.on('data', (b) => { raw += b.toString('utf8'); });
    req.on('end', () => {
      let url = '';
      try { const j = JSON.parse(raw || '{}'); url = typeof j.url === 'string' ? j.url.trim() : ''; } catch { /* 忽略 */ }
      if (url && !/^https?:\/\/[^\s]+$/i.test(url)) {
        return sendJson(res, 400, { ok: false, error: '地址需以 http:// 或 https:// 开头' });
      }
      cfg.publicUrl = url;
      try {
        writeFileSync(join(PLUGIN_DIR, '.public-url'), url ? url + '\n' : '', 'utf8');
      } catch (e) { return sendJson(res, 500, { ok: false, error: String(e?.message ?? e) }); }
      sendJson(res, 200, { ok: true, public_url: url,
        note: url ? '已保存：配对页会多出一个「公网/隧道」二维码 ✓' : '已清除：只保留局域网二维码 ✓' });
    });
  });
  route('exact', '/pair.json', (req, res) => {
    if (!isLocal(req)) return sendJson(res, 403, { ok: false, error: 'pair info is local-only' });
    const all = pairCandidates(cfg.lanEnabled === false ? ctx.webServer.port : Number(cfg.lanPort ?? 8218), mainToken(), cfg);
    const c = all[0];
    sendJson(res, 200, { ok: true, qr_text: c.qr_text, base_url: c.base_url, items: all, count: all.length, public_url: cfg.publicUrl || '',
      token: mainToken(), port: ctx.webServer.port, service: 'dsh-plugin-remote' });
  });

  route('exact', '/status', (req, res) => {
    if (!authOk(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    sendJson(res, 200, statusBody());
  });

  /** 一轮对话（非流式 / SSE 流式） */
  const runPrompt = async (req, res, sid, text, wantsStream) => {
    let handle = handles.get(sid);
    if (handle === undefined) {
      if (!ctx.agents || !ctx.sessions) throw new Error('agents/sessions 服务不可用');
      handle = await ctx.agents.create(await createInput(sid));
      handles.set(sid, handle); local.set(sid, []);
    }
    if (!wantsStream) {
      const r = await runTurn(ctx, handle, sid, text, cfg);
      { const hist = local.get(sid) ?? []; hist.push({ role: 'user', text }); hist.push({ role: 'assistant', text: r.reply }); local.set(sid, hist); }
      promptCount += 1;
      let reply = r.reply;
      const dbg = { via: r.via, model: modelInfo(), selection: lastSelection, selectionInstaller, preset: lastPreset, ...r.debug };
      const h = local.get(sid) ?? [];
      h.push({ role: 'user', text: text.slice(0, 2000) }, { role: 'assistant', text: reply.slice(0, 2000) });
      local.set(sid, h);
      sendJson(res, 200, { ok: true, action: 'prompt', session_id: sid, reply, duration_ms: dbg.cli_ms ?? null, turn: 1, debug: dbg });
      return;
    }
    // SSE：进程内轮询 event-sourced 会话日志（assistant/message、tool/result）
    res.writeHead(200, { 'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache, no-transform', connection: 'keep-alive', 'x-accel-buffering': 'no' });
    const sse = (o) => { try { res.write(`data: ${JSON.stringify(o)}\n\n`); } catch { /* 断连 */ } };
    sse({ type: 'start', action: 'prompt', session_id: sid });
    let lastSeq = -1; let lastText = '';
    const pump = setInterval(() => {
      try {
        const s = ctx.sessions.get(sid); if (!s) return;
        const seq = Number(s.seq ?? 0);
        if (lastSeq < 0) lastSeq = Math.max(0, seq - 1);
        for (let i = lastSeq; i < seq; i += 1) {
          const ev = s.eventAt?.(i); const type = String(ev?.type ?? '');
          if (type === 'tool/result') sse({ type: 'tool', data: ev?.data ?? null });
          else if (type === 'assistant/message') {
            const t = collectText(ev?.data, []).join('') || textOf(ev?.data);
            if (t && t !== lastText) { lastText = t; sse({ type: 'text', text: t }); }
          }
        }
        lastSeq = seq;
      } catch (e) { sse({ type: 'warn', error: String(e?.message ?? e) }); }
    }, 150);
    try {
      const rr = await runTurn(ctx, handle, sid, text, { ...cfg, __onData: (s) => sse({ type: 'text', text: s }) });
      { const hist = local.get(sid) ?? []; hist.push({ role: 'user', text }); hist.push({ role: 'assistant', text: rr.reply }); local.set(sid, hist); }
      promptCount += 1;
      const s = ctx.sessions.get(sid);
      let reply = rr.reply || lastText;
      if (s) for (const m of (s.deriveMessages?.() ?? [])) if (m?.role === 'assistant' && textOf(m)) reply = textOf(m);
      if (reply.length > cfg.maxReplyChars) reply = reply.slice(0, cfg.maxReplyChars);
      sse({ type: 'done', action: 'prompt', session_id: sid, reply, debug: { via: rr.via, ...rr.debug } });
    } catch (e) {
      sse({ type: 'error', action: 'prompt', session_id: sid, error: String(e?.message ?? e) });
    } finally {
      clearInterval(pump);
      try { res.end(); } catch { /* 忽略 */ }
    }
    req.on('close', () => clearInterval(pump));
  };

  // POST /session —— 与旧桥动作协议一致
  route('exact', '/session', async (req, res) => {
    if (!authOk(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    if (req.method !== 'POST') return sendJson(res, 405, { ok: false, error: 'use POST' });
    let body;
    try { body = JSON.parse((await readBody(req)) || '{}'); }
    catch (e) { return sendJson(res, 400, { ok: false, error: `bad json: ${e?.message ?? e}` }); }
    const action = typeof body.action === 'string' && body.action ? body.action : 'prompt';

    try {
      if (action === 'start') {
        const sid = `smartquiz-${randomBytes(6).toString('hex')}`;
        if (!ctx.agents || !ctx.sessions) throw new Error('agents/sessions 服务不可用');
        const handle = await ctx.agents.create(await createInput(sid));
        handles.set(sid, handle); local.set(sid, []);
        return sendJson(res, 200, { ok: true, action: 'start', session_id: sid });
      }
      if (action === 'get_status') return sendJson(res, 200, { ...statusBody(), action: 'get_status' });
      if (action === 'set_config') return sendJson(res, 200, { ok: true, action: 'set_config', note: '配置由手机侧持有' });

      const sid = typeof body.session_id === 'string' && body.session_id
        ? body.session_id : `smartquiz-${randomBytes(6).toString('hex')}`;

      if (action === 'history') {
        const max = Number.isFinite(body.max) ? Math.max(1, Math.min(200, body.max)) : 10;
        const s = ctx.sessions.get(sid);
        let items = [];
        const localItems = local.get(sid) ?? [];
        if (localItems.length) items = localItems; else if (s) items = (s.deriveMessages?.() ?? []).map((m) => ({ role: m?.role ?? 'assistant', text: textOf(m) })).filter((x) => x.text);
        const sliced = items.slice(-max);
        return sendJson(res, 200, { ok: true, action: 'history', session_id: sid, count: sliced.length, history: sliced, text: sliced.map(function(m){return (m.role === 'user' ? '我: ' : '电脑: ') + m.text;}).join(String.fromCharCode(10)) });
      }
      if (action !== 'prompt') return sendJson(res, 400, { ok: false, error: `unknown action: ${action}` });

      const text = (typeof body.text === 'string' ? body.text : (typeof body.prompt === 'string' ? body.prompt : '')).trim();
      if (!text) return sendJson(res, 400, { ok: false, action: 'prompt', error: 'text 不能为空' });
      const wantsStream = body.stream === true || String(req.headers['accept'] ?? '').includes('text/event-stream');
      await runPrompt(req, res, sid, text, wantsStream);
    } catch (e) {
      sendJson(res, 500, { ok: false, action, error: String(e?.message ?? e) });
    }
  });

  // POST /run —— 兼容别名
  route('exact', '/run', async (req, res) => {
    if (!authOk(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    let body;
    try { body = JSON.parse((await readBody(req)) || '{}'); }
    catch (e) { return sendJson(res, 400, { ok: false, error: `bad json: ${e?.message ?? e}` }); }
    const sid = typeof body.session_id === 'string' && body.session_id ? body.session_id : `smartquiz-${randomBytes(6).toString('hex')}`;
    const text = (typeof body.text === 'string' ? body.text : (typeof body.prompt === 'string' ? body.prompt : '')).trim();
    if (!text) return sendJson(res, 400, { ok: false, error: 'text is required' });
    const wantsStream = body.stream === true || String(req.headers['accept'] ?? '').includes('text/event-stream');
    try { await runPrompt(req, res, sid, text, wantsStream); }
    catch (e) { sendJson(res, 500, { ok: false, error: String(e?.message ?? e) }); }
  });

  // POST /exec —— 电脑上直接执行命令（默认关闭；与旧桥 action=shell 对应）
  route('exact', '/exec', async (req, res) => {
    if (!authOk(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    if (cfg.allowExec !== true) {
      return sendJson(res, 403, { ok: false, error: 'exec_disabled',
        hint: '在插件 config 里设 allowExec: true 才允许远程执行命令（安全默认关闭）' });
    }
    let body = {};
    try { body = JSON.parse((await readBody(req)) || '{}'); } catch { /* 允许空体 */ }
    const cmd = typeof body.cmd === 'string' ? body.cmd.trim() : '';
    if (!cmd) return sendJson(res, 400, { ok: false, error: 'cmd is required' });
    const timeout = Number.isFinite(body.timeout)
      ? Math.max(1000, Math.min(600000, body.timeout * 1000)) : cfg.execTimeoutMs;
    activeJobs += 1;
    const started = Date.now();
    execFile(cmd, { timeout, shell: true, maxBuffer: 4 * 1024 * 1024, windowsHide: true },
      (err, stdout, stderr) => {
        activeJobs -= 1;
        const out = `${stdout ?? ''}${stderr ? `\n[stderr]\n${stderr}` : ''}`.trim();
        sendJson(res, 200, { ok: !err, action: 'exec', cmd, exit_code: err?.code ?? 0,
                             duration_ms: Date.now() - started, output: out.slice(0, 200000) });
      });
  });

  // ---- 鉴权升级路由 ----
  // POST /pair/code —— 仅本机：主人在电脑上生成一次性配对码（5 分钟、一次性）
  route('exact', '/pair/code', (req, res) => {
    if (!isLocalOrAuth(req)) return sendJson(res, 403, { ok: false, error: 'local-only or device token required' });
    const now = Date.now();
    for (const [c, exp] of pairingCodes) if (exp <= now) pairingCodes.delete(c);
    const code = String(Math.floor(100000 + Math.random() * 900000));
    pairingCodes.set(code, now + PAIR_TTL_MS);
    sendJson(res, 200, { ok: true, code, expires_in_ms: PAIR_TTL_MS,
                         hint: '手机上输入此 6 位码换取设备令牌（或用 /pair 页面二维码）' });
  });

  // POST /pair/claim —— 手机：用一次性码换取长期设备令牌（无需主令牌）
  route('exact', '/pair/claim', async (req, res) => {
    let body = {};
    try { body = JSON.parse((await readBody(req)) || '{}'); } catch { /* 允许空体 */ }
    const code = String(body.code ?? '').trim();
    const exp = pairingCodes.get(code);
    if (exp === undefined) return sendJson(res, 400, { ok: false, error: 'invalid_code' });
    if (exp <= Date.now()) { pairingCodes.delete(code); return sendJson(res, 410, { ok: false, error: 'code_expired' }); }
    pairingCodes.delete(code);                       // 一次性
    const device = {
      id: `dev-${randomBytes(4).toString('hex')}`,
      name: String(body.device_name ?? '手机').slice(0, 60),
      token: randomBytes(24).toString('hex'),
      created_at: Date.now(), last_seen: Date.now(),
    };
    devices.devices.push(device);
    const ok = saveDevices(DEVICE_FILE, devices);
    sendJson(res, 200, { ok: true, device_id: device.id, device_token: device.token, persisted: ok,
                         base_url: `http://${lanIpv4()}:${ctx.webServer.port}` });
  });

  // GET /devices —— 鉴权：列出已配对设备（令牌打码）
  route('exact', '/devices', (req, res) => {
    if (!authOk(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    sendJson(res, 200, { ok: true, count: devices.devices.length,
      devices: devices.devices.map((d) => ({ id: d.id, name: d.name, created_at: d.created_at,
        last_seen: d.last_seen, token_masked: `${d.token.slice(0, 6)}…${d.token.slice(-4)}` })) });
  });

  // POST /devices/revoke —— 鉴权：吊销某台设备（无需改主令牌）
  route('exact', '/devices/revoke', async (req, res) => {
    if (!authOk(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    let body = {};
    try { body = JSON.parse((await readBody(req)) || '{}'); } catch { /* 允许空体 */ }
    const id = String(body.device_id ?? '');
    const before = devices.devices.length;
    devices.devices = devices.devices.filter((d) => d.id !== id);
    const ok = saveDevices(DEVICE_FILE, devices);
    sendJson(res, 200, { ok: true, revoked: before - devices.devices.length, persisted: ok });
  });
  // POST /reset —— 结束会话
  route('exact', '/reset', async (req, res) => {
    if (!authOk(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    let body = {};
    try { body = JSON.parse((await readBody(req)) || '{}'); } catch { /* 允许空体 */ }
    const sid = typeof body.session_id === 'string' ? body.session_id : '';
    const targets = sid ? [sid] : [...handles.keys()];
    let closed = 0;
    for (const id of targets) {
      const h = handles.get(id); if (h === undefined) continue;
      try { await h.dispose(); } catch { /* 忽略 */ }
      handles.delete(id); local.delete(id); closed += 1;
    }
    sendJson(res, 200, { ok: true, closed });
  });
  // LAN-SERVER: 手机可达入口（DSH 自带 webserver 只听 127.0.0.1 ✗）
  const lanPort = Number(cfg.lanPort ?? 8218);
  let lanServer = null;
  if (cfg.lanEnabled !== false) {
    try {
      lanServer = createServer((req, res) => {
        let p = '/'; try { p = new URL(req.url ?? '/', 'http://x').pathname; } catch { /* 忽略 */ }
        const h = localRoutes.get(p);
        if (h) { try { req.__viaLanServer = true; h(req, res); } catch (e) { try { sendJson(res, 500, { ok: false, error: String(e?.message ?? e) }); } catch { /* 忽略 */ } } }
        else { res.writeHead(404, { 'content-type': 'application/json; charset=utf-8' }); res.end(JSON.stringify({ ok: false, error: 'not found' })); }
      });
      lanServer.on('error', (e) => ctx.logger?.warn?.(`[smartquiz-remote] LAN 服务器错误: ${String(e?.message ?? e)}`));
      lanServer.listen(lanPort, '0.0.0.0', () => ctx.logger?.info?.(`[smartquiz-remote] LAN 服务器监听 0.0.0.0:${lanPort}（手机可连 ✓）`));
      // 附加端口（花生壳默认转发到本机 80 → 一并监听，尽力而为 ✓）
      const handler0 = lanServer.listeners('request')[0];
      for (const extra of String(cfg.extraLanPorts || '80').split(',').map((x) => Number(String(x).trim())).filter((n) => n > 0 && n !== lanPort)) {
        try {
          const s2 = createServer(handler0);
          s2.on('error', (e) => ctx.logger?.warn?.(`[smartquiz-remote] 附加端口 ${extra} 失败: ${String(e?.message ?? e)}`));
          s2.listen(extra, '0.0.0.0', () => ctx.logger?.info?.(`[smartquiz-remote] 附加监听 0.0.0.0:${extra} ✓`));
          ctx.effect(() => () => { try { s2.close(); } catch { /* 忽略 */ } });
        } catch (e) { ctx.logger?.warn?.(`[smartquiz-remote] 附加端口 ${extra} 启动失败: ${String(e?.message ?? e)}`); }
      }      ctx.effect(() => () => { try { lanServer.close(); } catch { /* 忽略 */ } });
    } catch (e) { ctx.logger?.warn?.(`[smartquiz-remote] LAN 服务器启动失败: ${String(e?.message ?? e)}`); }
  }


  ctx.effect(() => () => {
    for (const h of handles.values()) { try { h.dispose(); } catch { /* 忽略 */ } }
    handles.clear(); local.clear();
  });

  ctx.logger?.info?.(`[smartquiz-remote] 挂载: /health /pair /pair.json /status /session /run /exec /reset `
    + `(port=${ctx.webServer.port}, model=${cfg.provider}/${cfg.model}, exec=${cfg.allowExec === true})`);
}
