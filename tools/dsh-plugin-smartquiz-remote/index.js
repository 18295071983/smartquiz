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

import { randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { chmodSync, existsSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { networkInterfaces, homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { exec, execFile, spawn } from 'node:child_process';
import { createServer } from 'node:http';


export const name = 'smartquiz-remote';


/** 插件目录（配对页/二维码库随包分发；.token 持久化于此） */
const PLUGIN_DIR = dirname(fileURLToPath(import.meta.url));
const readAsset = (file) => { try { return readFileSync(join(PLUGIN_DIR, file), 'utf8'); } catch { return ''; } };

/**
 * 写「含密钥」的文件：只给当前用户读写。
 *
 * 这些文件里是主令牌 / 设备令牌 / 会话映射，任一泄漏都等于远程执行权。
 * 原来用 writeFileSync(file, data, 'utf8')，在 Linux/macOS 上按 umask 022 会落成 0644 ——
 * 同一台机器上的其他用户（或备份/同步工具）能直接读走。POSIX 上强制 0600；
 * Windows 没有 POSIX mode（chmod 基本是 no-op，不会报错），ACL 由用户目录继承。
 */
function writeSecret(file, data) {
  try {
    writeFileSync(file, data, { encoding: 'utf8', mode: 0o600 });
    try { chmodSync(file, 0o600); } catch { /* Windows / 只读盘 */ }
    return true;
  } catch { return false; }
}

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
  return writeSecret(file, JSON.stringify(data, null, 2));
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
  writeSecret(file, generated);
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
/**
 * 把"电脑端那条会话"的最近记录拼成前缀，交给新会话当上下文。
 *
 * 为什么不直接 --session-id 续写：DSH 的会话存储是**单写者**的，同一条会话
 * 同一时刻只能有一个写句柄。桌面端开着它时，子进程续写会直接报
 * "already owned by an active write owner"。复制上下文另起一条，代价是
 * 两边从此刻开始各自演化（不是同一条 session），收益是永远不打架。
 */
function carryPreamble(carried, text) {
  const lines = [];
  let budget = 4000;                                   // 前缀上限，别把上下文挤爆
  for (const m of carried.history) {
    const who = m?.role === 'user' ? '用户' : '助手';
    const body = String(m?.text ?? '').trim();
    if (!body) continue;
    const line = `${who}: ${body.length > 600 ? body.slice(0, 600) + '…' : body}`;
    if (line.length > budget) break;
    budget -= line.length;
    lines.push(line);
  }
  if (!lines.length) return text;
  return [
    '【接续上下文】以下是电脑端会话 ' + String(carried.target ?? '') + ' 的最近记录。',
    '请在理解这些内容的基础上接着回答最后的问题，不要复述历史。',
    '',
    ...lines,
    '',
    '---',
    text,
  ].join('\n');
}

async function runTurn(ctx, handle, sid, text, cfg) {
  // CLI-MODE（默认）：走 `dsh --profile headless "<任务>"`，不依赖 DSH 内部模块 ✓
  if (cfg.cliMode !== false) {
    // MEMORY-MAP: 我们的 session_id → DSH 持久会话 id（跨调用/跨重启记忆 ✓）
    const memFile = join(PLUGIN_DIR, '.sessions.json');
    let mem = {}; try { mem = JSON.parse(readFileSync(memFile, 'utf8')); } catch { mem = {}; }
    /**
     * mem 的两种值：
     *   'session-xxx'                         → 直接续写这条（正常的手机会话）
     *   { target, history }                   → 刚从电脑端「接过来」的会话：把上下文
     *                                           作为前缀塞进新会话（copy-on-write），
     *                                           不再 --session-id 续写 —— 因为 DSH 的
     *                                           会话是单写者的，电脑端开着那条时续写会
     *                                           报 "already owned by an active write owner"。
     */
    const raw = mem[sid];
    const carried = raw !== null && typeof raw === 'object' ? raw : null;
    const dshSid = carried ? '' : (typeof raw === 'string' ? raw : '');
    const promptText = carried && Array.isArray(carried.history) && carried.history.length
      ? carryPreamble(carried, text)
      : text;
    const cands = resolveDshCli(cfg);
    let lastErr = '';
    for (const cli of cands) {
      const r = await runCliTask(cli, cfg.cliProfile || 'headless', promptText, cfg.cliTimeoutMs || 300000, cfg.__onData, dshSid, cfg.workingDir);
      if (r.ok && r.output) {
        if (r.sessionId && r.sessionId !== dshSid) { mem[sid] = r.sessionId; writeSecret(memFile, JSON.stringify(mem, null, 2)); }
        return { reply: r.output, via: 'cli:' + cli, debug: { cli_ms: r.ms, cli_code: r.code, dsh_session: r.sessionId || '(none)', memory: carried ? 'carried' : (dshSid ? 'resumed' : 'new') } };
      }
      lastErr = `${cli} → code=${r.code} ${r.err ?? ''} ${String(r.output).slice(0, 120)}`;
    }
    return { reply: '', via: 'cli-failed', error: '电脑端执行失败：' + (lastErr || '未知错误（dsh --profile ' + (cfg.cliProfile || 'headless') + ' 没有返回内容）'), debug: { cli_errors: lastErr } };
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
function runCliTask(cliPath, profile, text, timeoutMs, onData, dshSessionId, workingDir) {
  return new Promise((resolve) => {
    const started = Date.now();
    const isCmd = /\.(cmd|bat)$/i.test(String(cliPath));
    /**
     * 子进程的工作目录。
     *
     * ★ 为什么重要：DSH 按 **cwd** 把会话归到某个"工作区"（sessions/--<slug>--/）。
     * 不指定时子进程继承宿主进程的 cwd —— 桌面端的 host 跑在
     * `~/.workbuddy/dsh-home/profiles/desktop`，于是手机发起的会话全落在那个
     * 目录名下，桌面端侧栏里既不在项目工作区、也不容易找到。
     * 指定成项目目录后，手机会话就和桌面端同一个工作区，能在侧栏里点开接着聊。
     */
    const workdir = (() => {
      const dir = String(workingDir || '').trim();
      if (!dir) return undefined;
      try { return existsSync(dir) ? dir : undefined; } catch { return undefined; }
    })();
    /** 子进程用的 profile：空值会让命令行变成 `--profile  --json`（DSH 会报 invalid profile） */
    const prof = String(profile || '').trim() || 'headless';
    /**
     * 子进程环境。
     *
     * ★ 2026-11-10 修复：DSH 0.2.2 起桌面端走 account 路由，而 `--profile headless`
     *   的默认路由是 `deepseek-official`，需要 API Key。凭据在
     *   $DSH_HOME/.credentials.yaml 的 refs.DEEPSEEK_API_KEY 里，但子进程的
     *   credentials service 不一定会把它注入 LLM 路由（实测 MISSING_CREDENTIAL），
     *   于是 CLI 任务只回空串、phone 端看到「电脑没回话」。
     *   这里直接把它作为环境变量补进去；已有环境变量时不动（用户可以自己覆盖）。
     */
    const cliEnv = (() => {
      const env = { ...process.env, DSH_HOME: process.env.DSH_HOME };
      // ★ 必须剥掉宿主的会话上下文，否则子进程会"继承"桌面端正在用的会话：
      //   实测 DSH_SESSION_ID 被 CLI 采纳后，手机任务直接写进了用户当前的桌面会话
      //   （读回来的历史里全是桌面端的推理过程）。手机任务必须是独立会话。
      for (const key of ['DSH_SESSION_ID', 'DSH_WEB_URL', 'DSH_SHELL', 'DSH_PROFILE_DIR', 'DSH_PROFILE']) {
        delete env[key];
      }
      if (!env.DEEPSEEK_API_KEY) {
        const k = readDeepseekApiKey();
        if (k) env.DEEPSEEK_API_KEY = k;
      }
      return env;
    })();
    // ★ .cmd 走 stdin：正文不拼进命令行（换行会被 cmd 截断）
    const memArgs = dshSessionId ? ' --session-id ' + dshSessionId : '';
    const fullLine = '"' + cliPath + '" --profile ' + prof + ' --json' + memArgs + ' -';
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
        // .cmd：整条命令行交给 cmd（唯一可靠方式 ✓）。
        // ★ 正文走 stdin（`-`），不要拼进命令行：cmd 会把换行当成命令分隔符，
        //   多行 prompt 只会有第一行生效（实测：接续上下文只到了标题那一行）。
        const child = exec(fullLine, { windowsHide: true, maxBuffer: 16 * 1024 * 1024, timeout: timeoutMs,
          env: cliEnv, cwd: workdir },
          (err, stdout, stderr) => {
            const s = String(stdout ?? ''); const e2 = String(stderr ?? '');
            if (s) { out += s; consume(s); }
            if (e2) out += e2;
            finish(err ? (err.code ?? 1) : 0, err ? String(err.message).slice(0, 120) : undefined);
          });
        try { child.stdin?.write(text); child.stdin?.end(); } catch { /* 子进程已退出 */ }
        void child;
      } else {
        const child = spawn(cliPath, ['--profile', prof, '--json', ...(dshSessionId ? ['--session-id', dshSessionId] : []), text], { windowsHide: true, env: cliEnv, cwd: workdir });
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

/**
 * 读取 DeepSeek API Key（用于面板显示账户余额）。
 *
 * 来源与 DSH 自己的凭据库一致：$DSH_HOME/.credentials.yaml 的 refs.DEEPSEEK_API_KEY。
 * 只读不写；读不到就返回空串（面板显示"未配置"而不是报错）。
 * ★ 这个值只在本机插件进程内使用，转发给 api.deepseek.com（它本来就是这把钥匙的归属方），
 *   不写日志、不回传给手机端。
 */
function readDeepseekApiKey() {
  const home = process.env.DSH_HOME || join(homedir(), '.workbuddy', 'dsh-home');
  const file = join(home, '.credentials.yaml');
  let text = '';
  try { text = readFileSync(file, 'utf8'); } catch { return ''; }
  // 只需取 refs 段里 DEEPSEEK_API_KEY 的值，按行扫描比引入 YAML 解析器更省事
  const m = /^\s*DEEPSEEK_API_KEY\s*:\s*(\S+)\s*$/m.exec(text);
  return m ? m[1] : '';
}

export function apply(ctx, config) {
  const DEFAULTS = {
    token: '', provider: 'deepseek-account', model: 'deepseek-flash', pathPrefix: '',   // 显式：插件创建的 agent 需要明确模型路由
    maxReplyChars: 4000, allowExec: true, execTimeoutMs: 120000,
    cliMode: true, cliProfile: 'headless', cliTimeoutMs: 300000, dshPath: '', publicUrl: '',
    workingDir: '',   // CLI 任务的 cwd：决定手机会话归到哪个工作区（空=继承宿主 cwd）
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
  /** 同时最多挂 5 个待用配对码：多到溢出说明有人在狂刷 /pair/code */
  const PAIR_MAX_PENDING = 5;
  /**
   * 配对码尝试节流。
   *
   * 配对码只有 6 位（90 万种），拿到一枚就能换长期设备令牌 → 令牌能调 /exec。
   * 原来 /pair/claim 对错误码没有任何计数：只要有一枚有效码存在，同网段的人就能
   * 在 5 分钟窗口里无限次试。这里按来源地址计失败次数，超过阈值直接 429。
   */
  const PAIR_MAX_FAILS = 8;
  const PAIR_WINDOW_MS = 5 * 60 * 1000;
  const pairAttempts = new Map();                    // ip -> { count, resetAt }
  const pairThrottled = (ip) => {
    const rec = pairAttempts.get(ip);
    return Boolean(rec) && rec.resetAt > Date.now() && rec.count >= PAIR_MAX_FAILS;
  };
  const pairFail = (ip) => {
    const now = Date.now();
    let rec = pairAttempts.get(ip);
    if (!rec || rec.resetAt <= now) { rec = { count: 0, resetAt: now + PAIR_WINDOW_MS }; pairAttempts.set(ip, rec); }
    rec.count += 1;
    if (pairAttempts.size > 256) {                       // 简单的表大小上限，避免被大量源地址撑爆
      for (const [k, v] of pairAttempts) if (v.resetAt <= now) pairAttempts.delete(k);
    }
  };
  /** 定长比较：令牌比较不做提前返回（`===` 会在第一个不同字节处退出） */
  const safeEqual = (a, b) => {
    const x = Buffer.from(String(a ?? '')); const y = Buffer.from(String(b ?? ''));
    return x.length === y.length && x.length > 0 && timingSafeEqual(x, y);
  };
  const deviceByToken = (t) => devices.devices.find((d) => safeEqual(d.token, t));

  // ---- 远程执行命令开关（allowExec）：面板可切换、落盘、下次启动仍生效 ----
  // 优先级：面板写入的 .allow-exec 文件 > 插件配置 allowExec。
  // 为什么要有文件：配置文件是"随包分发"的默认值，用户在面板上的选择不该被一次重载抹掉，
  // 也不该要求用户去手改 YAML。
  const ALLOW_EXEC_FILE = join(PLUGIN_DIR, '.allow-exec');
  const readAllowExecFile = () => {
    try {
      const v = readFileSync(ALLOW_EXEC_FILE, 'utf8').trim();
      if (v === 'true') return true;
      if (v === 'false') return false;
    } catch { /* 没写过 */ }
    return null;
  };
  let allowExec = (() => { const f = readAllowExecFile(); return f !== null ? f : cfg.allowExec === true; })();
  const writeAllowExec = (v) => {
    return writeSecret(ALLOW_EXEC_FILE, v ? 'true' : 'false');
  };

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
  /** 主令牌最近一次被成功使用的时间（内存态，只用于面板显示"当前有连接在用主令牌"） */
  let mainTokenLastSeen = 0;
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
    if (safeEqual(t, mainToken())) { mainTokenLastSeen = Date.now(); return true; }
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
    channels: { session_acp: true, exec: allowExec === true },
    acp_agent: { provider: cfg.provider || '(none)', model: cfg.model || '(none)', preset: lastPreset, transport: 'in-process' },
    permission_policy: allowExec === true ? 'workspace-write' : 'read-only',
    session_streams: handles.size,
    sessions_count: handles.size,
    active_jobs: activeJobs,
    devices: devices.devices.length,
    // 主令牌最近一次被使用的时间：手机端只有在"还没换成设备令牌"或"用设备令牌失败退回主令牌"时才走主令牌，
    // 有值就说明当前有连接在用主令牌（面板据此提示，避免把"设备数 0"误读成"没人连着"）
    main_token_last_seen: mainTokenLastSeen,
    pairing_codes_pending: pairingCodes.size,
    prompts: promptCount,
    port: ctx.webServer.port,
    uptime_ms: Date.now() - startedAt,
  });

  route('exact', '/health', (req, res) =>
    sendJson(res, 200, { ok: true, service: 'dsh-plugin-remote', version: 5 }));

  // ---------------- 电脑端会话：列出 / 只读查看 ----------------
  // 目的：手机不只知道"自己那条会话"，还能看到电脑上正在聊/聊过的会话（只读），
  // 需要时再 attach 过去接着聊（写入口唯一性见 /session action=attach 的说明）。
  const persistence = () => { try { return ctx.get('sessionPersistence'); } catch { return undefined; } };

  /** 持久化事件 → {role,text}；只取 user/assistant 的文本，不碰工具结果与推理 */
  const messageOf = (ev) => {
    const t = String(ev?.type ?? '');
    if (t !== 'user/message' && t !== 'assistant/message') return null;
    const data = ev?.data ?? {};
    // 只保留真人输入：DSH 把每次的 runtime context / system-reminder 也记成
    // user/message（source.kind 不是 'user'），原样回传手机只会是一屏噪声。
    const kind = data?.source?.kind;
    if (t === 'user/message' && typeof kind === 'string' && kind !== 'user') return null;
    const body = t === 'assistant/message' ? (data.message ?? data) : data;
    const text = textOf(body).trim();
    if (!text) return null;
    return { role: t === 'user/message' ? 'user' : 'assistant', text };
  };

  /** 读一条已落盘会话的最近 N 条消息（read 句柄，不抢写所有权；只读尾部，避免整条日志进内存） */
  async function readStoredMessages(id, max = 20) {
    const svc = persistence();
    if (!svc || typeof svc.open !== 'function') throw new Error('sessionPersistence 不可用');
    const handle = await svc.open(id, 'read');
    try {
      let offset;
      try {
        const st = await svc.stat?.(id);
        const total = Number(st?.eventCount ?? 0);
        if (total > 0) offset = Math.max(0, total - Math.max(40, max * 6));
      } catch { /* stat 不可用就读全量 */ }
      const got = offset === undefined ? await handle.read() : await handle.read(offset);
      const events = Array.isArray(got) ? got : (got?.events ?? []);
      const out = [];
      for (const ev of events) { const m = messageOf(ev); if (m) out.push(m); }
      return out.slice(-Math.max(1, Math.min(200, max)));
    } finally { try { await handle.close?.(); } catch { /* 已关 */ } }
  }

  /** 列出已落盘会话，按最近写入排序 */
  async function listStoredSessions(limit = 20) {
    const svc = persistence();
    if (!svc || typeof svc.list !== 'function') return [];
    const all = await svc.list();
    const rows = (Array.isArray(all) ? all : []).map((snap) => {
      const h = snap?.header ?? snap ?? {};
      let mtime = 0;
      const p = snap?.location;
      if (typeof p === 'string' && p) { try { mtime = statSync(p).mtimeMs; } catch { /* 读不到就算了 */ } }
      return {
        session_id: String(h.id ?? ''),
        cwd: String(h.cwd ?? ''),
        created_at: Number(h.createdAt ?? 0) || null,
        mtime: mtime || null,
        events: Number(snap?.eventCount ?? 0) || null,
      };
    }).filter((r) => r.session_id !== '');
    /**
     * 名单是给"看电脑端在聊什么"用的，而 `smartquiz-*` 是插件自己的手机会话 id。
     * 它们也会被落盘（早期非 CLI 模式在宿主里 create 过 agent），但读出来是空壳
     * （没有 cwd、没有一句话），混在列表里只会让手机端多出一串点不开的条目。
     */
    const desktopRows = rows.filter((r) => !r.session_id.startsWith('smartquiz-'));
    desktopRows.sort((a, b) => (b.mtime ?? b.created_at ?? 0) - (a.mtime ?? a.created_at ?? 0));
    const picked = desktopRows.slice(0, Math.max(1, Math.min(200, limit)));
    // 给前若干条补一句"最后说了什么"，否则手机端只看到一串 session-id，选不出是哪条
    for (const row of picked.slice(0, Math.min(10, picked.length))) {
      try {
        const recent = await readStoredMessages(row.session_id, 3);
        const lastUser = [...recent].reverse().find((m) => m.role === 'user');
        const snippet = (lastUser ?? recent[recent.length - 1])?.text ?? '';
        if (snippet) row.last = snippet.slice(0, 120);
      } catch { /* 单条读失败不影响列表 */ }
    }
    return picked;
  }

  route('exact', '/sessions.json', async (req, res) => {
    if (!authOk(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    try {
      const q = new URL(req.url ?? '/', 'http://dsh.invalid');
      const limit = Number(q.searchParams.get('limit') ?? 20);
      const sessions = await listStoredSessions(Number.isFinite(limit) ? limit : 20);
      sendJson(res, 200, { ok: true, count: sessions.length, sessions });
    } catch (e) {
      sendJson(res, 500, { ok: false, error: String(e?.message ?? e) });
    }
  });

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

  // POST /config —— 【仅本机】切换"允许远程执行命令"（allowExec），并落盘到 .allow-exec。
  //
  // 为什么坚持仅本机（不接受 device token）：这是"拿到令牌能干什么"的开关本身。
  // 若手机也能改，一台被吊销/丢失的设备就能把执行能力重新打开；反过来手机误关之后
  // 也没法自己开回来（/exec 已被拒）——必须由人在电脑上决定。手机端只在状态里显示当前值。
  route('exact', '/config', async (req, res) => {
    if (!isLocal(req)) return sendJson(res, 403, { ok: false, error: 'config is local-only' });
    let body = {};
    try { body = JSON.parse((await readBody(req)) || '{}'); } catch { /* 允许空体 */ }
    if (typeof body.allowExec !== 'boolean') {
      return sendJson(res, 400, { ok: false, error: 'allowExec must be boolean',
        current: { allowExec, persisted: readAllowExecFile() } });
    }
    allowExec = body.allowExec;
    const persisted = writeAllowExec(allowExec);
    sendJson(res, 200, { ok: true, allowExec,
      permission_policy: allowExec ? 'workspace-write' : 'read-only', persisted,
      note: allowExec
        ? '已允许远程执行命令（手机 /exec 与 shell 动作可用）'
        : '已禁止远程执行命令（手机只能对话，不能在你电脑上跑命令）' });
  });

  // GET /balance —— 【仅本机】查 DeepSeek 账户余额（面板显示用）。
  // 走官方 /user/balance，用 DSH 凭据库里的 DEEPSEEK_API_KEY；结果缓存 60 秒，
  // 避免面板反复展开时频繁打接口。只在本机暴露：余额属于账户信息，没必要给手机或公网。
  let balanceCache = { at: 0, data: null };
  route('exact', '/balance', async (req, res) => {
    if (!isLocal(req)) return sendJson(res, 403, { ok: false, error: 'balance is local-only' });
    const key = readDeepseekApiKey();
    if (!key) {
      return sendJson(res, 200, { ok: false, error: 'no_api_key',
        hint: '未在凭据库里找到 DEEPSEEK_API_KEY（$DSH_HOME/.credentials.yaml）' });
    }
    if (balanceCache.data && Date.now() - balanceCache.at < 60_000) {
      return sendJson(res, 200, Object.assign({ ok: true, cached: true }, balanceCache.data));
    }
    try {
      const ac = new AbortController();
      const timer = setTimeout(() => ac.abort(), 15000);
      const r = await fetch('https://api.deepseek.com/user/balance', {
        headers: { Authorization: `Bearer ${key}`, Accept: 'application/json' },
        signal: ac.signal,
      });
      clearTimeout(timer);
      const text = await r.text();
      if (!r.ok) {
        return sendJson(res, 200, { ok: false, error: `HTTP ${r.status}`, detail: text.slice(0, 200) });
      }
      let j = {};
      try { j = JSON.parse(text); } catch { /* 保持空对象 */ }
      const infos = Array.isArray(j.balance_infos) ? j.balance_infos : [];
      const usd = infos.find((x) => String(x.currency).toUpperCase() === 'USD');
      const cny = infos.find((x) => String(x.currency).toUpperCase() === 'CNY');
      const pick = cny || usd || infos[0] || {};
      const data = {
        is_available: j.is_available === true,
        currency: pick.currency ?? '',
        total_balance: pick.total_balance ?? '',
        granted_balance: pick.granted_balance ?? '',
        topped_up_balance: pick.topped_up_balance ?? '',
        infos,
      };
      balanceCache = { at: Date.now(), data };
      sendJson(res, 200, Object.assign({ ok: true, cached: false }, data));
    } catch (e) {
      sendJson(res, 200, { ok: false, error: 'request_failed', detail: String(e?.message ?? e) });
    }
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
        writeSecret(join(PLUGIN_DIR, '.public-url'), url ? url + '\n' : '');
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
    // CLI 模式下不需要（也不该）在宿主里建 agent：任务由 `dsh --profile headless`
    // 子进程执行。之前无论哪种模式都先 create 一次，attach 到电脑端已有会话后
    // 会撞上 "session already exists"（那条 id 在宿主里已经存在）。
    const cliMode = cfg.cliMode !== false;
    let handle = handles.get(sid);
    if (!cliMode && handle === undefined) {
      if (!ctx.agents || !ctx.sessions) throw new Error('agents/sessions 服务不可用');
      handle = await ctx.agents.create(await createInput(sid));
      handles.set(sid, handle); local.set(sid, []);
    }
    if (!wantsStream) {
      const r = await runTurn(ctx, handle, sid, text, cfg);
      const dbg = { via: r.via, model: modelInfo(), selection: lastSelection, selectionInstaller, preset: lastPreset, ...r.debug };
      // 失败要说出来：以前 CLI 起不来时回 ok:true + 空 reply，手机端只显示「电脑没回话」
      if (!r.reply && r.error) {
        return sendJson(res, 200, { ok: false, action: 'prompt', session_id: sid, reply: '', error: r.error, debug: dbg });
      }
      { const hist = local.get(sid) ?? []; hist.push({ role: 'user', text }); hist.push({ role: 'assistant', text: r.reply }); local.set(sid, hist); }
      promptCount += 1;
      let reply = r.reply;
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
      if (!reply && rr.error) sse({ type: 'error', action: 'prompt', session_id: sid, error: rr.error });
      else sse({ type: 'done', action: 'prompt', session_id: sid, reply, debug: { via: rr.via, ...rr.debug } });
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
      if (action === 'read') {
        // 只读查看电脑端任意一条会话（默认读 body.session_id；也可用 target_session_id）
        const target = String(body.target_session_id ?? body.session_id ?? '').trim();
        if (!target) return sendJson(res, 400, { ok: false, action, error: '需要 session_id' });
        const max = Number.isFinite(body.max) ? Math.max(1, Math.min(200, body.max)) : 20;
        try {
          const history = await readStoredMessages(target, max);
          return sendJson(res, 200, {
            ok: true, action: 'read', session_id: target, count: history.length, history,
            text: history.map((m) => (m.role === 'user' ? '我: ' : '电脑: ') + m.text).join('\n'),
          });
        } catch (e) {
          return sendJson(res, 500, { ok: false, action: 'read', error: String(e?.message ?? e) });
        }
      }
      if (action === 'attach') {
        // 把电脑端某条会话「接」到手机上继续。
        // 注意：DSH 会话是单写者的，直接续写会与桌面端抢写句柄（报 already owned），
        // 所以这里用 copy-on-write：读最近的记录当上下文，下一条消息在**新会话**里
        // 带着这段上下文开始。两边从此各自演化，但不会再冲突。
        const target = String(body.target_session_id ?? '').trim();
        if (!target) return sendJson(res, 400, { ok: false, action, error: '需要 target_session_id' });
        const max = Number.isFinite(body.max) ? Math.max(1, Math.min(200, body.max)) : 20;
        let history = [];
        try { history = await readStoredMessages(target, max); } catch (e) {
          return sendJson(res, 500, { ok: false, action, error: '读取电脑端会话失败：' + String(e?.message ?? e) });
        }
        const memFile = join(PLUGIN_DIR, '.sessions.json');
        let mem = {}; try { mem = JSON.parse(readFileSync(memFile, 'utf8')); } catch { mem = {}; }
        mem[sid] = { target, history, at: Date.now() };
        writeSecret(memFile, JSON.stringify(mem, null, 2));
        handles.delete(sid); local.delete(sid);        // 旧句柄/旧历史作废
        return sendJson(res, 200, {
          ok: true, action: 'attach', session_id: sid, attached_to: target, count: history.length, history,
          mode: 'copy-on-write',
          note: '已接过来：下一条消息会带上这段上下文在新会话里继续（电脑端那条不会被改动）',
        });
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
    if (allowExec !== true) {
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
    while (pairingCodes.size >= PAIR_MAX_PENDING) pairingCodes.delete(pairingCodes.keys().next().value);
    // ★ 用 CSPRNG：Math.random 是可预测的 PRNG，不能用来生成凭据
    const code = String(randomInt(100000, 1000000));
    pairingCodes.set(code, now + PAIR_TTL_MS);
    sendJson(res, 200, { ok: true, code, expires_in_ms: PAIR_TTL_MS,
                         hint: '手机上输入此 6 位码换取设备令牌（或用 /pair 页面二维码）' });
  });

  // POST /pair/claim —— 手机换取长期设备令牌。两条信道都支持：
  //   ① body.code         一次性配对码（5 分钟、一次性、可在手机上输入）
  //   ② Authorization     已持有主令牌（配对页二维码里就带着它）→ 直接登记为可信设备
  // 为什么要加 ②（2026-10-06）：手机端扫码只是把**主令牌**存进配置（见 Android
  // RemoteDshPairBridge），从不调用本接口，于是 /status 的 devices 恒为 0 ——
  // 面板上"没有设备"却在正常对话，主人既看不到谁连着、也无法单独吊销某台设备。
  // 让手机用二维码里的令牌换一张**设备令牌**（权限更小、可单独吊销、能看到 last_seen），
  // 手机侧改为优先使用设备令牌。注意：主令牌本来就能做一切（含 /exec），
  // 所以"凭主令牌可登记设备"并未扩大权限面。
  route('exact', '/pair/claim', async (req, res) => {
    let body = {};
    try { body = JSON.parse((await readBody(req)) || '{}'); } catch { /* 允许空体 */ }
    const clientIp = String(req.socket?.remoteAddress ?? 'unknown');
    if (pairThrottled(clientIp)) {
      return sendJson(res, 429, { ok: false, error: 'too_many_attempts', hint: '配对码尝试过于频繁，请 5 分钟后再试' });
    }
    const code = String(body.code ?? '').trim();
    let via = '';
    if (code) {
      const exp = pairingCodes.get(code);
      if (exp === undefined) { pairFail(clientIp); return sendJson(res, 400, { ok: false, error: 'invalid_code' }); }
      if (exp <= Date.now()) { pairingCodes.delete(code); pairFail(clientIp); return sendJson(res, 410, { ok: false, error: 'code_expired' }); }
      pairingCodes.delete(code);                     // 一次性
      via = 'code';
    } else if (authOk(req)) {
      via = 'main_token';
    } else {
      return sendJson(res, 401, { ok: false, error: 'need_code_or_token' });
    }
    pairAttempts.delete(clientIp);                   // 成功一次即清零，正常配对不受影响
    const rawName = String(body.device_name ?? '手机').trim().slice(0, 60);
    const name = rawName || '手机';
    // 同名设备视为"同一台重新登记"：复用并替换旧记录，而不是无限追加。
    // （手机端每次配对/补登记都会 claim；不去重的话设备表会像以前那样堆出一串同名条目）
    const sameName = devices.devices.find((d) => String(d.name ?? '').trim().toLowerCase() === name.toLowerCase());
    const device = {
      id: sameName ? sameName.id : `dev-${randomBytes(4).toString('hex')}`,
      name,
      token: randomBytes(24).toString('hex'),
      created_at: sameName ? sameName.created_at : Date.now(),
      last_seen: Date.now(),
    };
    if (sameName) devices.devices = devices.devices.filter((d) => d !== sameName);
    devices.devices.push(device);
    const ok = saveDevices(DEVICE_FILE, devices);
    sendJson(res, 200, { ok: true, via, device_id: device.id, device_token: device.token, persisted: ok,
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

  ctx.logger?.info?.(`[smartquiz-remote] 挂载: /health /pair /pair.json /status /config /session /run /exec /reset `
    + `(port=${ctx.webServer.port}, model=${cfg.provider}/${cfg.model}, exec=${allowExec === true})`);
}
