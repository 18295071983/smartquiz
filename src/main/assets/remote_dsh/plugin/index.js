/**
 * SmartQuiz Remote —— 答题宝手机端远程接入（原生 DSH 宿主插件）
 *
 * 替代 src/main/assets/remote_dsh/dsh_bridge_server.py：
 *   无 Python ✓ 不 spawn dsh 子进程 ✓ 不走 ACP stdio ✓ 直接跑在 DSH 宿主进程内 ✓
 *
 * 【线协议 = 与旧桥 v5 完全一致】手机端 RemoteDshTool 的调用（代码取证）：
 *   GET  /health                                  探活；带令牌/本机时返回自检（cwd/dsh_home/preset/exec）
 *   GET  /reconcile?session_id=&digest=&last_seq= 双端对账（轻量：只回摘要 + 回执水位线）
 *   POST /reconcile { session_id, messages?, digest?, last_seq?, acks?, light? }  全量比对 / light 摘要
 *   POST /ack      { session_id, seq?, ids? }     回执水位线（落盘 .acks.json，只增不减）
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

import { createHash, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { chmodSync, existsSync, readdirSync, readFileSync, statSync, writeFileSync, appendFileSync } from 'node:fs';
import { networkInterfaces, homedir, constants as osConstants, setPriority } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { exec, execFile, spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { SERVER_CAPS, filterViewCards, eventAllowed, normalizeCaps, summarize, allows } from './caps.js';


/** CLI 子进程的权限档位（apply 里由 cfg 覆盖；runCliTask 在模块作用域用） */
const CLI_PERM = { mode: 'danger-full-access', approval: 'never' };

/**
 * 进程内 SSE 订阅表（2026-11-12）。
 *
 * 为什么放模块作用域：`agent/assistant-stream` 是宿主级事件，token 增量要在**订阅了这条会话**
 * 的 /events 连接上实时写出。放在 apply 闭包里也能跑，但 CLI 子进程路径（runCliTask 的 onData）
 * 不经过 apply 的闭包，统一放这里两边都能用。
 *
 * 订阅者形如 `{ sid, caps, send(obj) }`；`sid=''` 表示广播给所有连接（日程变化）。
 * 写失败不在这里清理：连接关闭时由订阅者自己 `STREAM_HUB.delete(sub)`。
 */
const STREAM_HUB = new Set();

function streamBroadcast(sid, obj) {
  if (!obj) return;
  for (const sub of STREAM_HUB) {
    if (!sub) continue;
    if (sid && sub.sid !== sid) continue;
    try {
      sub.send(obj);
    } catch {
      // 断连/背压：由连接自己的 close 回调移除
    }
  }
}

/**
 * cwd 守卫的最近一次告警。
 *
 * ★ 背景：DSH 的 spawn 在 cwd 不存在时也报 `ENOENT`，指向的却是可执行文件，
 *   看起来像 "powershell.exe / rg.exe 丢了"。实际是工作目录是 asar 内部虚拟路径
 *   （或已被删掉的目录），OS 层根本不存在。这里把降级事实记下来，/health 暴露出去，
 *   避免下次再靠猜。
 */
const CWD_GUARD = { lastWarning: null };

/**
 * 发送幂等：客户端带 client_msg_id，同一 (会话, id) 10 分钟内只受理一次。
 * 覆盖"网络抖了、手机重试，但电脑其实已经执行完"这种最恼人的重复执行。
 */
const CLIENT_MSG_SEEN = new Map();
function clientMsgDuplicated(sid, id) {
  if (!id) return false;
  const key = String(sid) + '|' + String(id);
  const now = Date.now();
  const prev = CLIENT_MSG_SEEN.get(key);
  if (prev && now - prev < 10 * 60 * 1000) return true;
  CLIENT_MSG_SEEN.set(key, now);
  if (CLIENT_MSG_SEEN.size > 500) {
    const oldest = CLIENT_MSG_SEEN.keys().next().value;
    if (oldest !== undefined) CLIENT_MSG_SEEN.delete(oldest);
  }
  return false;
}

/**
 * 双端对账的"回执水位线"：sessionId → { acked_seq, acked_ids[], updated_at }。
 *
 * ★ 为什么要落盘：手机断线重连后要问"我上次确认到哪了"，这个答案不能存在内存里，
 *   否则桌面端一重启就退化成"全量重拉"。文件放插件目录（与 .token 同级）。
 * ★ 为什么 lazy：PLUGIN_DIR 在模块里晚于本常量初始化，这里只捕获闭包、用到时才拼路径。
 */
const ACK_STORE = (() => {
  const file = () => join(PLUGIN_DIR, '.acks.json');
  let data = null;
  const load = () => {
    if (data) return data;
    data = {};
    try {
      const j = JSON.parse(readFileSync(file(), 'utf8'));
      if (j && typeof j === 'object' && !Array.isArray(j)) data = j;
    } catch { /* 首次运行 / 文件损坏都按空处理 */ }
    return data;
  };
  return {
    file,
    get(sid) { return load()[String(sid)] ?? null; },
    set(sid, patch) {
      const all = load();
      const prev = all[String(sid)] ?? { acked_seq: 0, acked_ids: [] };
      const ids = new Set([...(prev.acked_ids ?? []), ...(patch.acked_ids ?? [])].map(String));
      const next = {
        acked_seq: Math.max(Number(prev.acked_seq) || 0, Number(patch.acked_seq) || 0),
        // 只留最近 500 条 id：回执是水位线辅助，不是审计日志
        acked_ids: [...ids].slice(-500),
        updated_at: Date.now(),
      };
      all[String(sid)] = next;
      try { writeFileSync(file(), JSON.stringify(all, null, 2), 'utf8'); } catch { /* 落盘失败不影响本次响应 */ }
      return next;
    },
  };
})();

/** 消息的真实 id（DSH 给 user/assistant 消息都带 id；缺省时回 null，不伪造） */
function messageIdOf(ev) {
  const t = String(ev?.type ?? '');
  if (t !== 'user/message' && t !== 'assistant/message') return null;
  const data = ev?.data ?? {};
  const body = t === 'assistant/message' ? (data.message ?? data) : data;
  const id = body?.id ?? data?.id ?? null;
  return typeof id === 'string' && id ? id : null;
}

// ==================== CLI 子进程调度：直连模式"电脑卡"的根治 ====================
//
// 直连模式每来一句话都会 `dsh --profile headless` 起一个**完整的 DSH 宿主进程**：
// 实测冷启动 ~1.5s、峰值内存 ~256MB、一轮普通任务 6s+。手机端等不到回复就会重发，
// HTTP 超时/切后台又会把连接断掉 —— 而这些子进程以前没有任何人管：不排队、不取消、不杀。
// 连发 3~5 句就能同时堆 3~5 个宿主（1GB+ 内存 + 多个模型请求），电脑自然卡。
//
// 这里做四件事：
//   ① 并发闸门（cliMaxConcurrent，默认 1）：排队执行，绝不并行堆进程；
//   ② 队列上限（cliMaxQueued，默认 3）：再多就直接回"忙"，不让请求无限堆积；
//   ③ 客户端断开 / 超过手机给的 timeout → 杀掉**整棵**进程树 —— Windows 上只 kill
//      cmd.exe 会留下 DeepSeek Harness.exe 孤儿（实测客户端断开 12s 后仍在跑）；
//   ④ 子进程降到 BelowNormal 优先级：跑任务时电脑仍然能正常响应。
const CLI_SLOT = { running: 0, queued: 0, max: 1, maxQueued: 3 };
const cliWaiters = [];
/** 客户端断开后留给子进程收尾的时间；到点还没退出就连整棵树一起杀 */
const CLI_ABORT_GRACE_MS = 3000;

const abortError = () => { const e = new Error('aborted'); e.code = 'aborted'; return e; };

/** 杀整棵进程树：Windows 用 taskkill /T，POSIX 用进程组（spawn 时 detached） */
function killTree(pid) {
  if (!pid) return;
  try {
    if (process.platform === 'win32') {
      const p = spawn('taskkill', ['/pid', String(pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
      p.on('error', () => { /* taskkill 异常不影响主流程 */ });
    } else {
      try { process.kill(-pid, 'SIGKILL'); } catch { try { process.kill(pid, 'SIGKILL'); } catch { /* 已退出 */ } }
    }
  } catch { /* 进程已退出 */ }
}

/** 降低子进程优先级：CPU 抢得再凶，桌面端也不会被拖住 */
function lowerPriority(pid) {
  if (!pid) return;
  try {
    const p = osConstants?.priority?.PRIORITY_BELOW_NORMAL;
    if (typeof setPriority === 'function' && p !== undefined) setPriority(pid, p);
  } catch { /* 平台/权限不支持时忽略 */ }
}

/** 取一个 CLI 槽位：并发上限内立即给；否则排队（排队中被取消则立刻失败，不会 spawn） */
function acquireCliSlot(signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(abortError()); return; }
    const release = () => {
      CLI_SLOT.running = Math.max(0, CLI_SLOT.running - 1);
      const next = cliWaiters.shift();
      CLI_SLOT.queued = cliWaiters.length;
      if (next) {
        next.cleanup();
        CLI_SLOT.running += 1;
        next.resolve(() => release());
      }
    };
    if (CLI_SLOT.running < CLI_SLOT.max) {
      CLI_SLOT.running += 1;
      resolve(release);
      return;
    }
    if (cliWaiters.length >= CLI_SLOT.maxQueued) {
      reject(Object.assign(new Error('busy'), { code: 'busy' }));
      return;
    }
    const waiter = { resolve, reject, cleanup: () => {} };
    if (signal) {
      const onAbort = () => {
        const i = cliWaiters.indexOf(waiter);
        if (i >= 0) cliWaiters.splice(i, 1);
        CLI_SLOT.queued = cliWaiters.length;
        reject(abortError());
      };
      try { signal.addEventListener('abort', onAbort, { once: true }); } catch { /* 忽略 */ }
      waiter.cleanup = () => { try { signal.removeEventListener('abort', onAbort); } catch { /* 忽略 */ } };
    }
    cliWaiters.push(waiter);
    CLI_SLOT.queued = cliWaiters.length;
  });
}

export const name = 'smartquiz-remote';


/** 插件目录（配对页/二维码库随包分发；.token 持久化于此） */
const PLUGIN_DIR = dirname(fileURLToPath(import.meta.url));
const readAsset = (file) => { try { return readFileSync(join(PLUGIN_DIR, file), 'utf8'); } catch { return ''; } };

// ==================== 直连会话协商：两端认同一个电脑会话 id ====================
//
// 旧行为：手机先 `action=start` 拿一个自己生成的 `smartquiz-xxx` 当会话 id，插件再让
// CLI 起一条**另一条** `session-xxx` 真会话，两边靠 `.sessions.json` 的映射绑在一起。
// 映射只在"成功"时落盘：超时/取消/清配置就丢，下一次又是新会话 → 电脑侧栏越攒越多，
// 手机和电脑说的不是同一条对话。
//
// 现在两端协商：电脑端决定并持久化「对话码 → 电脑会话 id」，每次回话都把
// `dsh_session_id` 带回给手机；手机存下来、下次作为 `target_session_id` 带回来。
// 对话码留空 = 默认直连对话（一台电脑一条）；填 `work` 这类短码 = 各自一条专属对话。
const SESSION_MAP_FILE = join(PLUGIN_DIR, '.sessions.json');function readSessionMap() {
  try {
    const j = JSON.parse(readFileSync(SESSION_MAP_FILE, 'utf8'));
    return j && typeof j === 'object' ? j : {};
  } catch { return {}; }
}
function writeSessionMap(mem) {
  return writeSecret(SESSION_MAP_FILE, JSON.stringify(mem, null, 2));
}
/** 对话码 → 绑定槽：留空是默认直连对话，填了就是该码的专属对话 */
function bindingKeyOf(code) {
  const c = String(code ?? '').trim().slice(0, 32);
  return c ? '@code:' + c : '@direct';
}

/**
 * 消息来源台账（`.origins.jsonl`）：区分"手机上说的"与"电脑上说的"。
 *
 * 为什么必须单独记：两条路最终都落成 `user/message` 事件，`source.kind` 都是 'user'，
 * **事件本身不携带来源**。插件只知道自己代手机发了哪条（文本 + 时间），
 * 于是按 (会话 id, 文本 hash, 时间窗) 反查；查不到的即为电脑端直接输入。
 *
 * 这是"尽力而为"的匹配：同一句话在 10 分钟内被电脑端也打了一遍，会归到手机 —— 可接受。
 */
const ORIGIN_FILE = join(PLUGIN_DIR, '.origins.jsonl');
function originHash(text) {
  return createHash('sha1').update(String(text ?? '').trim()).digest('hex').slice(0, 16);
}
const ORIGIN = (() => {
  let cache = null;
  const load = () => {
    if (cache) return cache;
    cache = [];
    try {
      for (const line of readFileSync(ORIGIN_FILE, 'utf8').split('\n')) {
        if (!line.trim()) continue;
        try { const o = JSON.parse(line); if (o && o.sid && o.h) cache.push(o); } catch { /* 跳过坏行 */ }
      }
    } catch { /* 首次运行 */ }
    if (cache.length > 2000) cache = cache.slice(-2000);
    return cache;
  };
  return {
    /** 记录"这条消息是插件代手机发的" */
    record(sid, text, src = 'phone') {
      if (!sid || !text) return;
      const rec = { sid: String(sid), h: originHash(text), at: Date.now(), src };
      try { load().push(rec); } catch { /* ignore */ }
      try { appendFileSync(ORIGIN_FILE, JSON.stringify(rec) + '\n', 'utf8'); } catch { /* 落盘失败不影响发送 */ }
    },
    /** 'phone' | 'desktop' */
    originOf(sid, text, time) {
      if (!sid || !text) return 'desktop';
      const h = originHash(text);
      const t = Number(time) || 0;
      const hit = load().find((o) => o.sid === sid && o.h === h && Math.abs((o.at || 0) - t) < 10 * 60 * 1000);
      return hit ? (hit.src || 'phone') : 'desktop';
    },
  };
})();

/**
 * 会话绑定的**唯一读写入**（`.sessions.json`）。
 *
 * 以前 /v2/session、legacy direct_state/direct_bind、runTurn 三处各自
 * readSessionMap()/writeSessionMap()，谁都能改、谁都不校验 —— 正是"三份状态"的来源之一。
 * 现在存储层收口到这里；"会话是否还存在"的校验留在调用方（需要 ctx）。
 */
const sessionBinding = {
  /** 这个对话码绑到哪条电脑会话（'' = 还没绑） */
  get(code) {
    const v = readSessionMap()[bindingKeyOf(code)];
    return typeof v === 'string' ? v.trim() : '';
  },
  /** 绑定/解绑；返回是否存在绑定 */
  set(code, id) {
    const m = readSessionMap();
    const k = bindingKeyOf(code);
    if (id) m[k] = String(id).trim(); else delete m[k];
    writeSessionMap(m);
    return Boolean(id);
  },
  /** 列出所有非字符串（attach 留下的 {target,history}）以外的绑定，供诊断用 */
  dump() {
    const m = readSessionMap();
    const out = {};
    for (const [k, v] of Object.entries(m)) if (typeof v === 'string') out[k] = v;
    return out;
  },
};

// 迁移：旧版「选择会话」会把空对话码写成 @direct，导致直连和跟随共用一条。
// 现在 @direct 只给手机专属直连；若它等于 @code:follow，就清掉，直连下次重新建。
try {
  const m = readSessionMap();
  const follow = m['@code:follow'];
  if (follow && typeof follow === 'string' && m['@direct'] === follow) {
    delete m['@direct'];
    writeSessionMap(m);
  }
} catch { /* 迁移失败不影响主流程 */ }

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
 * 把一次 HTTP 响应的生命周期变成 AbortSignal。
 *
 * 手机端读超时（timeout+30s）、切后台、主动取消都会把连接断掉：这时 `res` 会触发
 * 'close' 而 `writableEnded` 仍是 false —— 这就是"手机不要这条回复了"的可靠信号。
 * 直连模式的 CLI 子进程必须跟着停，否则电脑会替一个没人等的任务继续烧 CPU。
 */
function requestAbort(res) {
  const ac = new AbortController();
  const onClose = () => { if (!res.writableEnded) { try { ac.abort(); } catch { /* 忽略 */ } } };
  try { res.on('close', onClose); } catch { /* 忽略 */ }
  return ac;
}
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

async function runTurn(ctx, handle, sid, text, cfg, runOpts) {
  const opts = runOpts ?? {};
  // CLI-MODE（默认）：走 `dsh --profile headless "<任务>"`，不依赖 DSH 内部模块 ✓
  if (cfg.cliMode !== false) {
    const mem = readSessionMap();
    /**
     * 绑定优先级：
     *   ① 手机带回来的 target_session_id（两端协商的结果）
     *   ② 对话码对应的持久绑定（@direct / @code:xxx）—— 手机丢了 id 也能接上
     *   ③ 旧版按手机 session_id 存的映射（兼容老数据）
     * ② 里的 { target, history } 是 attach（copy-on-write）留下的对象，另走前缀逻辑。
     */
    const slot = bindingKeyOf(opts.sessionCode);
    const slotRaw = mem[slot];
    const legacyRaw = mem[sid];
    const carried = slotRaw !== null && typeof slotRaw === 'object'
      ? slotRaw
      : (legacyRaw !== null && typeof legacyRaw === 'object' ? legacyRaw : null);
    const boundSid = (() => {
      if (typeof slotRaw === 'string' && slotRaw) return slotRaw;
      if (typeof legacyRaw === 'string' && legacyRaw) return legacyRaw;
      return '';
    })();
    const explicitSid = String(opts.targetSessionId || '').trim();
    const dshSid = carried ? '' : (explicitSid || boundSid);
    /** 一拿到电脑会话 id 就落盘（超时/取消也记）：下次续同一条，不会再冒出新会话 */
    const remember = (id) => {
      if (!id || carried) return;
      if (mem[slot] === id && !(mem[sid] && mem[sid] !== id)) return;
      mem[slot] = id;
      if (explicitSid && explicitSid !== id) mem[sid] = id;   // 兼容旧的按手机 id 查询
      writeSessionMap(mem);
    };
    const promptText = carried && Array.isArray(carried.history) && carried.history.length
      ? carryPreamble(carried, text)
      : text;
    const cands = resolveDshCli(cfg);
    /**
     * 单次预算：不能超过手机这一条请求自己的 timeout（以前固定 5 分钟 ——
     * 手机 120s 就放弃、连接也断了，子进程却还在跑满 5 分钟）。
     * 排队时间也扣在这里面：deadline - now。
     */
    const hardCap = Math.max(3000, Number(cfg.cliTimeoutMs) || 300000);
    const budgetMs = Number.isFinite(opts.deadline)
      ? Math.min(hardCap, Math.max(3000, opts.deadline - Date.now()))
      : hardCap;
    let lastErr = '';
    for (const cli of cands) {
      let r;
      try {
        r = await runCliTask(cli, cfg.cliProfile || 'headless', promptText, budgetMs, cfg.__onData, dshSid, cfg.workingDir,
          { signal: opts.signal, budgetMs });
      } catch (e) {
        if (e?.code === 'busy') {
          return { reply: '', via: 'busy', error: '电脑端正在执行上一条消息（直连一次只跑一条），这条没有发出去，请稍后再发' };
        }
        if (e?.code === 'aborted') {
          return { reply: '', via: 'cli-aborted', error: '手机端已取消/超时，电脑侧已停止执行' };
        }
        return { reply: '', via: 'cli-error', error: '电脑端执行异常：' + String(e?.message ?? e).slice(0, 200) };
      }
      if (r.aborted) {
        remember(r.sessionId);
        return { reply: '', via: 'cli-aborted', error: '手机端已取消/超时，电脑侧已停止执行', dshSession: r.sessionId || dshSid };
      }
      remember(r.sessionId);
      if (r.ok && r.output) {
        // CLI 会话 id 只有跑完才知道；记一笔来源，供 /v2/view 标"手机/电脑"
        ORIGIN.record(r.sessionId || dshSid, promptText);
        return { reply: r.output, via: 'cli:' + cli, dshSession: r.sessionId || dshSid, debug: { cli_ms: r.ms, cli_spawn_ms: r.spawn_ms ?? null, cli_code: r.code, dsh_session: r.sessionId || '(none)', memory: carried ? 'carried' : (dshSid ? 'resumed' : 'new') } };
      }
      lastErr = `${cli} → code=${r.code} ${r.err ?? ''} ${String(r.output).slice(0, 120)}`;
      if (r.code === -1) {
        // 超时别再换下一个候选重跑：那等于把同一轮任务再做一遍，白烧 CPU
        const secs = Math.round(budgetMs / 1000);
        const partial = r.output ? r.output + '\n\n（超过 ' + secs + 's 未完成，电脑侧已中断）' : '';
        return {
          reply: partial,
          via: 'cli-timeout',
          error: '这一轮在 ' + secs + 's 内没跑完，已中断（电脑侧进程已结束）',
          dshSession: r.sessionId || dshSid,
          debug: { cli_ms: r.ms, cli_code: r.code },
        };
      }
    }
    return { reply: '', via: 'cli-failed', error: '电脑端执行失败：' + (lastErr || '未知错误（dsh --profile ' + (cfg.cliProfile || 'headless') + ' 没有返回内容）'), dshSession: dshSid, debug: { cli_errors: lastErr } };
  }
  const session = ctx.sessions.get(sid);
  const seqBefore = Number(session?.seq ?? 0);
  ORIGIN.record(sid, text);   // 宿主内轮次：这条是插件代手机发的
  let via = 'surface-append';
  try {
    if (session?.append) {
      // ★ 必须带 id：DSH 校验要求消息事件有"identified message"，
      //   只写 {role,content,source} 会落成坏数据（seq 27 lacks an identified message），
      //   之后这条会话在 GUI 与手机两侧都读不出来（历史加载失败：corrupt）。
      session.append('user/message',
        { id: randomBytes(16).toString('hex'), role: 'user', content: [{ type: 'text', text }], source: { kind: 'user' } },
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
      if (t) return { reply: t.length > cfg.maxReplyChars ? t.slice(0, cfg.maxReplyChars) : t, via, dshSession: sid,
                      debug: snapshot(s2) };
    }
  }
  await handle.agent.followup({ id: randomBytes(16).toString('hex'), role: 'user', content: [{ type: 'text', text }], source: { kind: 'user' } });
  await handle.agent.whenIdle();
  const s3 = ctx.sessions.get(sid);
  let reply = lastAssistant(s3);
  if (reply.length > cfg.maxReplyChars) reply = reply.slice(0, cfg.maxReplyChars);
  return { reply, via, dshSession: sid, debug: snapshot(s3) };
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

/**
 * 跑一条 CLI 任务；onData 逐块收到 stdout（流式用）。
 * 返回 { ok, output, code, ms, err, aborted }；`aborted` = 客户端断开后被杀。
 *
 * ★ 所有 CLI 任务都经过 acquireCliSlot()：并发上限内才真起进程，否则排队。
 *   opts.signal  —— 手机请求的 AbortSignal（连接断开/超时触发），到点杀整棵进程树
 *   opts.budgetMs —— 这次任务最多跑多久（手机给的 timeout 与 cliTimeoutMs 取小）
 */
function runCliTask(cliPath, profile, text, timeoutMs, onData, dshSessionId, workingDir, opts) {
  const signal = opts?.signal;
  const budgetMs = Math.max(3000, Math.min(Number(opts?.budgetMs) || Number(timeoutMs) || 300000, 6 * 3600 * 1000));
  return acquireCliSlot(signal).then((release) => new Promise((resolve) => {
    const started = Date.now();
    let spawnAt = 0;   // 子进程真正起来的时刻（用于衡量“起进程”占整轮耗时的比例）
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
      try {
        if (statSync(dir).isDirectory()) return dir;
      } catch { /* 落到下面记告警 */ }
      // ★ 不静默：目录无效时降级为"继承宿主 cwd"，但必须留痕（/health 可见）
      CWD_GUARD.lastWarning = {
        at: Date.now(),
        requested: dir,
        fallback: '(继承宿主 cwd)',
        reason: '目录不存在或不可访问；可能是 asar 内部虚拟路径，或目录已被删除',
      };
      return undefined;
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
    // ★ 2026-11-10：CLI 子进程默认 workspace-write，Windows 上会撞
    //   "sandbox-local windows-acl temp grant materialization failed"（手机直连时看到的就是这句）。
    //   这里显式给权限档位，默认全权，可用配置覆盖。
    const permMode = CLI_PERM.mode;
    const approvalPolicy = CLI_PERM.approval;
    const permArgs = ' --permission-mode ' + permMode + ' --approval-policy ' + approvalPolicy;
    const fullLine = '"' + cliPath + '" --profile ' + prof + ' --json' + memArgs + permArgs + ' -';
    let out = '';
    let settled = false;
    let finalText = '';
    let sessionId = dshSessionId || '';
    let childProc = null;
    let timer = null;
    let abortTimer = null;
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
    /** 杀掉这一轮的进程树（子进程可能已经自己退出，killTree 会静默忽略） */
    const killChild = () => { try { if (childProc?.pid) killTree(childProc.pid); } catch { /* 忽略 */ } };
    const finish = (code, err) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      if (abortTimer) clearTimeout(abortTimer);
      if (signal) { try { signal.removeEventListener('abort', onAbort); } catch { /* 忽略 */ } }
      try { release(); } catch { /* 忽略 */ }
      const reply = (finalText || out).trim();
      resolve({ ok: code === 0, output: reply, sessionId, code, ms: Date.now() - started, spawn_ms: spawnAt ? spawnAt - started : null, err, aborted: code === -4 });
    };
    /**
     * 客户端断开（手机 HTTP 超时 / 切后台 / 主动取消）：
     * 先给 3 秒收尾，再把整棵进程树杀掉 —— 否则这个 250MB 的 DSH 宿主会一直
     * 跑到自己的 5 分钟上限，连发几句就把电脑堆卡（本次要修的核心问题）。
     */
    const onAbort = () => {
      if (settled || abortTimer) return;
      abortTimer = setTimeout(() => { killChild(); finish(-4, 'aborted'); }, CLI_ABORT_GRACE_MS);
    };
    if (signal) {
      if (signal.aborted) { finish(-4, 'aborted'); return; }
      try { signal.addEventListener('abort', onAbort, { once: true }); } catch { /* 忽略 */ }
    }
    timer = setTimeout(() => { killChild(); finish(-1, 'timeout'); }, budgetMs);
    try {
      if (isCmd) {
        // .cmd：整条命令行交给 cmd（唯一可靠方式 ✓）。
        // ★ 正文走 stdin（`-`），不要拼进命令行：cmd 会把换行当成命令分隔符，
        //   多行 prompt 只会有第一行生效（实测：接续上下文只到了标题那一行）。
        // ★ 超时/取消都由上面自己的 timer + killTree 处理，不用 exec 的 timeout
        //   （exec 的 timeout 只杀 cmd.exe，会留下 DeepSeek Harness.exe 孤儿）。
        const child = exec(fullLine, {
          windowsHide: true, maxBuffer: 16 * 1024 * 1024,
          env: cliEnv, cwd: workdir, detached: process.platform !== 'win32',
        },
          (err, stdout, stderr) => {
            const s = String(stdout ?? ''); const e2 = String(stderr ?? '');
            if (s) { out += s; consume(s); }
            if (e2) out += e2;
            finish(err ? (err.code ?? 1) : 0, err ? String(err.message).slice(0, 120) : undefined);
          });
        childProc = child;
        lowerPriority(child.pid);
        try { child.on?.('spawn', () => { spawnAt = Date.now(); }); } catch { /* 已退出 */ }
        try { child.stdin?.write(text); child.stdin?.end(); } catch { /* 子进程已退出 */ }
      } else {
        const child = spawn(cliPath, ['--profile', prof, '--json', ...(dshSessionId ? ['--session-id', dshSessionId] : []), '--permission-mode', CLI_PERM.mode, '--approval-policy', CLI_PERM.approval, text], {
          windowsHide: true, env: cliEnv, cwd: workdir, detached: process.platform !== 'win32',
        });
        childProc = child;
        lowerPriority(child.pid);
        try { child.on?.('spawn', () => { spawnAt = Date.now(); }); } catch { /* 已退出 */ }
        child.stdout?.on('data', (b) => { const s = b.toString('utf8'); out += s; consume(s); });
        child.stderr?.on('data', (b) => { out += b.toString('utf8'); });
        child.on('error', (e) => { finish(-2, String(e?.message ?? e)); });
        child.on('close', (code) => { finish(code ?? 0); });
      }
    } catch (e) { finish(-3, String(e?.message ?? e)); }
  }));
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
/**
 * 把一条消息拆成「回答正文」和「思考内容」。
 *
 * DSH 的 assistant/message 里 content 是混合 part 数组，形如
 *   [{type:'reasoning', text:'…'}, {type:'text', text:'…'}]
 * 之前用 collectText 一把抓，把 reasoning 也拼进了正文 —— 手机上看到的就是
 * "先说一段思考，再跟答案"。这里按 part.type 分开。
 */
function partsOf(message) {
  const out = { answer: '', thinking: '' };
  const walk = (content) => {
    if (typeof content === 'string') { out.answer += content; return; }
    if (!Array.isArray(content)) return;
    for (const p of content) {
      if (!p || typeof p !== 'object') continue;
      const t = String(p.type ?? '');
      const think = t === 'reasoning' || t === 'thinking' || t === 'redacted_thinking';
      const txt = typeof p.text === 'string' ? p.text : (typeof p.content === 'string' ? p.content : '');
      if (!txt) continue;
      if (think) out.thinking += txt;
      else out.answer += txt;
    }
  };
  const body = (message && typeof message === 'object' && message.message && typeof message.message === 'object')
    ? message.message
    : message;
  if (body && typeof body === 'object') {
    walk(body.content);
    if (!out.answer && typeof body.text === 'string') out.answer = body.text;
  } else if (typeof message === 'string') {
    out.answer = message;
  }
  return out;
}

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

/**
 * 从 `from` 开始扫事件，取最后一条 assistant/message 的文本。
 *
 * 为什么不用 deriveMessages：resume 出来的会话里它可能还没重算，取到的是旧内容或空串
 * （实测：seq 21→34 明明跑了，reply 却是空）。事件是落盘的原始事实，更可靠。
 */
function assistantPartsSince(session, from) {
  const out = { answer: '', thinking: '' };
  try {
    const seq = Number(session?.seq ?? 0);
    for (let i = Math.max(0, Number(from) || 0); i < seq; i += 1) {
      const ev = session.eventAt?.(i);
      if (ev?.type !== 'assistant/message') continue;
      const p = partsOf(ev?.data);
      if (p.answer && p.answer.trim()) out.answer = p.answer;
      if (p.thinking && p.thinking.trim()) out.thinking = p.thinking;
    }
  } catch { /* 忽略 */ }
  return out;
}

function assistantTextSince(session, from) {
  return assistantPartsSince(session, from).answer;
}
/** 取最后一条 assistant 文本（多结构兼容） */
function lastAssistant(session) {
  try {
    const msgs = session?.deriveMessages?.() ?? [];
    for (let i = msgs.length - 1; i >= 0; i -= 1) {
      const m = msgs[i];
      if ((m?.role ?? m?.author ?? '') !== 'assistant') continue;
      const t = (partsOf(m).answer || '').trim();
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
    // 直连模式最多同时跑几个 `dsh` 子进程 / 队列里最多排几条。
    // 默认 1 + 3：实测每个子进程 ~256MB，串行执行才不会把电脑堆卡。
    cliMaxConcurrent: 1, cliMaxQueued: 3,
    /** 热 agent 复用：CLI 跑完后台 resume 成宿主内句柄，下一轮不再冷启动 */
    warmReuse: false,
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
  // 手机直连 / CLI 任务的权限档位（默认全权，避免 Windows 上 windows-acl 授权失败）
  CLI_PERM.mode = String(cfg.cliPermissionMode || 'danger-full-access').trim();
  CLI_PERM.approval = String(cfg.cliApprovalPolicy || 'never').trim();
  // 直连并发闸门（见 CLI_SLOT 注释）：默认一次只跑一条，排队上限 3
  CLI_SLOT.max = Math.max(1, Math.min(4, Number(cfg.cliMaxConcurrent) || 1));
  CLI_SLOT.maxQueued = Math.max(0, Math.min(20, Number(cfg.cliMaxQueued) || 0));
  // 注意：主令牌不再在此处取一次就固定（见下方 mainToken()：惰性读取，便于轮换）
  const startedAt = Date.now();
  /**
   * 反向指令队列：PC → 手机。只放行"纯 UI / 轻状态"工具，执行仍在手机侧完成。
   * 泛洪保护：最多 50 条、10 分钟过期；执行结果回传后再由 PC 读取。
   */
  const cmdQueue = new Map();
  const CMD_ALLOW = new Set(['ui_component', 'ui_component_plugin', 'task', 'reminder']);
  const CMD_MAX = 50;
  const CMD_TTL_MS = 10 * 60 * 1000;
  const pruneCommands = () => {
    const now = Date.now();
    for (const [id, c] of cmdQueue) {
      if (now - c.createdAt > CMD_TTL_MS) cmdQueue.delete(id);
    }
    while (cmdQueue.size > CMD_MAX) {
      const oldest = cmdQueue.keys().next().value;
      if (oldest === undefined) break;
      cmdQueue.delete(oldest);
    }
  };
  const handles = new Map();
  /** 由 sync 自己 resume 出来的 agent 句柄（sessionId → handle），避免每次重开 */
  const resumedHandles = new Map();     // session_id -> agent handle
  /** 热 agent 复用上限：留太多会长期占内存，超过就淘汰最老的 */
  const WARM_MAX = 4;
  const warmAgentFor = (sid) => {
    if (!sid) return null;
    const h = resumedHandles.get(sid);
    if (!h) return null;
    return h?.agent ?? h ?? null;
  };
  const rememberWarm = (sid, h) => {
    if (!sid || !h) return;
    resumedHandles.set(sid, h);
    while (resumedHandles.size > WARM_MAX) {
      const oldest = resumedHandles.keys().next().value;
      if (oldest === undefined) break;
      resumedHandles.delete(oldest);
    }
  };
  
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
  // ===== 2026-11-12 新增：实时桥（token 增量 / 日程 / 计划 / 子代理 / 问答答案） =====
  //
  // 背景：手机端以前只能看到"回合/步骤"粒度的持久事件；日程与计划根本不在会话日志里；
  // 子代理只有一条登记卡；agent 反问只能只读。这些都能在宿主内补，不要求 DSH 改协议：
  //   · token 增量：`agent/assistant-stream` 的瞬态 chunk（草稿，不落盘）；
  //   · 日程：`ctx.schedule` 管理 API + `schedule/changed` 事件；
  //   · 计划：`ctx.planMode.get/set`（持久 `plan/mode` 事件仍由会话日志转发）；
  //   · 子代理：根会话日志里的 `subagent/catalog` + 子会话事件 → 实时活动流；
  //   · 反问：`typertGateway` 的 pending 事件 settle（与审批同一套网关）。

  // 可选能力：用 ctx.get(name) 读取（不经 inject），避免为不存在的服务建"永远等待"的子插件。
  const serviceOf = (name) => {
    try {
      return ctx.get?.(name) ?? null;
    } catch {
      return null;
    }
  };
  const scheduleSvc = () => serviceOf('schedule');
  const planSvc = () => serviceOf('planMode');
  const questionsSvc = () => serviceOf('userQuestions');
  const liveAgentOf = (sid) => {
    if (!sid) return null;
    try { return ctx.agents?.get?.(sid) ?? null; } catch { return null; }
  };
  /** 子会话 id → 根会话 id（由 subagent/catalog 事件建立） */
  const childParent = new Map();
  const childLabel = new Map();

  const sessionEventBrief = (event) => {
    const t = String(event?.type ?? '');
    const d = event?.data ?? {};
    if (t === 'assistant/message') {
      const text = (d.message?.content ?? [])
        .filter((p) => p && p.type !== 'reasoning')
        .map((p) => p.text ?? '').join('').trim();
      return text ? { kind: 'text', text: text.slice(0, 200) } : null;
    }
    if (t === 'tool/call') {
      return { kind: 'tool', tool: String(d.name ?? '工具'), args: String(d.arguments ?? '').slice(0, 120) };
    }
    if (t === 'tool/result') {
      return { kind: 'tool-result', toolCallId: String(d.toolCallId ?? ''), error: Boolean(d.isError) };
    }
    if (t === 'turn/end') {
      return { kind: 'turn-end', stop: String(d.stopReason ?? d.stop_reason ?? '') };
    }
    if (t === 'step/start' || t === 'step/end') {
      return { kind: 'step', step: Number(d.step ?? 0) || 0, phase: t };
    }
    return null;
  };

  /** 读一条会话的全部事件：优先持久化日志（冷会话），否则读内存会话 */
  const readSessionEvents = async (sid) => {
    try {
      const svc = persistence();
      if (svc && typeof svc.open === 'function') {
        const h = await svc.open(sid, 'read');
        try {
          const got = await h.read();
          return Array.isArray(got) ? got : (got?.events ?? []);
        } finally { try { await h.close?.(); } catch { /* 已关 */ } }
      }
    } catch { /* 落盘日志不可用则退回内存 */ }
    try {
      const s = ctx.sessions.get(sid);
      if (s && Number.isFinite(s.seq)) {
        const out = [];
        for (let i = 1; i <= s.seq; i += 1) {
          const e = s.eventAt?.(i);
          if (e) out.push(e);
        }
        return out;
      }
    } catch { /* 会话不在内存 */ }
    return [];
  };

  /** 子代理目录：根会话日志里的 subagent/catalog + 每个子会话的实时状态/最近活动 */
  const subagentsOf = async (sid) => {
    const events = await readSessionEvents(sid);
    const children = [];
    const seen = new Set();
    for (const ev of events) {
      if (String(ev?.type) !== 'subagent/catalog') continue;
      const d = ev?.data ?? {};
      const childId = String(d.childId ?? '');
      if (!childId || seen.has(childId)) continue;
      seen.add(childId);
      childParent.set(childId, sid);
      const label = String(d.label ?? '');
      if (label) childLabel.set(childId, label);
      children.push({
        child_id: childId,
        label,
        mode: String(d.mode ?? ''),
        created_at: String(d.childCreatedAt ?? ''),
      });
    }
    for (const c of children) {
      try { c.live = Boolean(ctx.agents?.get?.(c.child_id)); } catch { c.live = false; }
      try {
        const evs = await readSessionEvents(c.child_id);
        c.events = evs.length;
        for (let i = evs.length - 1; i >= 0; i -= 1) {
          const brief = sessionEventBrief(evs[i]);
          if (!brief) continue;
          c.last = brief;
          c.last_at = Number(evs[i]?.time) || 0;
          break;
        }
        for (let i = evs.length - 1; i >= 0; i -= 1) {
          if (String(evs[i]?.type) !== 'assistant/message') continue;
          const text = (evs[i]?.data?.message?.content ?? [])
            .filter((p) => p && p.type !== 'reasoning')
            .map((p) => p.text ?? '').join('').trim();
          if (text) { c.last_text = text.slice(-400); break; }
        }
      } catch { /* 单个子会话读失败不影响列表 */ }
    }
    return children;
  };

  // ---- 实时桥：token 增量 ----
  // 只在宿主内 agent 上有效（CLI 子进程没有进程内流）；手机端按 parts.stream 能力订阅。
  try {
    ctx.on('agent/assistant-stream', (payload) => {
      const agent = payload?.agent;
      const frame = payload?.frame;
      const sid = String(agent?.id ?? '');
      if (!sid || !frame) return;
      if (frame.type === 'chunk') {
        const c = frame.chunk;
        if (!c) return;
        const base = { session_id: sid, turn: frame.turn, step: frame.step, attempt: frame.attemptId };
        if (c.type === 'text-delta') {
          streamBroadcast(sid, { v: 2, type: 'stream/delta', data: { ...base, kind: 'text', text: String(c.text ?? '') } });
        } else if (c.type === 'reasoning-delta') {
          streamBroadcast(sid, { v: 2, type: 'stream/delta', data: { ...base, kind: 'reasoning', text: String(c.text ?? '') } });
        } else if (c.type === 'tool-call-delta') {
          streamBroadcast(sid, { v: 2, type: 'stream/delta', data: { ...base, kind: 'tool', tool: String(c.name ?? ''), tool_call_id: String(c.id ?? ''), arguments_delta: String(c.argumentsDelta ?? '') } });
        }
      } else if (frame.type === 'end') {
        streamBroadcast(sid, { v: 2, type: 'stream/end', data: { session_id: sid, outcome: frame.outcome?.kind ?? 'committed' } });
      }
    });
  } catch (e) { /* 宿主没有该事件（旧版） */ }

  // ---- 实时桥：日程变化 / 子代理活动 ----
  try {
    ctx.on('schedule/changed', () => {
      streamBroadcast('', { v: 2, type: 'schedule/changed', ts: Date.now() });
    });
  } catch { /* 旧版没有 schedule */ }

  try {
    ctx.on('session/event', (session, event) => {
      const sid = String(session?.id ?? '');
      if (!sid || !event) return;
      const t = String(event.type ?? '');
      if (t === 'subagent/catalog') {
        const d = event.data ?? {};
        const childId = String(d.childId ?? '');
        if (childId) {
          childParent.set(childId, sid);
          if (d.label) childLabel.set(childId, String(d.label));
        }
        return;
      }
      const root = childParent.get(sid);
      if (!root) return;
      const brief = sessionEventBrief(event);
      if (!brief) return;
      streamBroadcast(root, {
        v: 2, type: 'subagent/activity',
        data: {
          root_id: root, child_id: sid, label: childLabel.get(sid) ?? '',
          event: t, ts: Number(event.time) || Date.now(), ...brief,
        },
      });
    });
  } catch { /* 旧版没有 session/event 监听 */ }

  /** 能力校验：不声明 caps 的旧客户端一律放行（与 caps.js 同一口径） */
  const capsAllowed = (caps, cap) => allows(caps, cap);

  route('exact', '/v2/schedule', async (req, res) => {
    if (!authOk(req) && !isLocal(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    const svc = scheduleSvc();
    if (!svc) {
      return sendJson(res, 503, {
        ok: false, v: 2, error: 'schedule_unavailable',
        hint: '这个 DSH 版本没有 schedule 服务（或未在 profile 里启用）',
      });
    }
    let body = {};
    let action = 'catalog';
    let caps = '';
    let id = '';
    try {
      if (String(req.method || 'GET').toUpperCase() === 'GET') {
        const q = new URL(req.url ?? '/', 'http://dsh.invalid');
        action = String(q.searchParams.get('action') || 'catalog');
        id = String(q.searchParams.get('id') || '');
        caps = q.searchParams.get('caps') ?? '';
      } else {
        body = JSON.parse((await readBody(req)) || '{}');
        action = String(body.action || 'catalog');
        id = String(body.id || '');
        caps = body.caps ?? '';
      }
    } catch { /* 用默认值 */ }
    if (!capsAllowed(caps, 'schedule.read')) {
      return sendJson(res, 403, { ok: false, error: 'caps_denied', need: 'schedule.read' });
    }
    try {
      switch (action) {
        case 'list': {
          const r = await svc.list({
            active: typeof body.active === 'boolean' ? body.active : undefined,
            limit: Number(body.limit) || undefined,
            offset: Number(body.offset) || undefined,
          });
          return sendJson(res, 200, { ok: true, v: 2, action, ...r, caps: summarize(caps) });
        }
        case 'view': {
          const r = await svc.view({ id });
          return sendJson(res, 200, { ok: true, v: 2, action, id, result: r, caps: summarize(caps) });
        }
        case 'history': {
          const r = await svc.history({ id, limit: Number(body.limit) || 20, before: body.before });
          return sendJson(res, 200, { ok: true, v: 2, action, id, result: r, caps: summarize(caps) });
        }
        case 'run': {
          if (!capsAllowed(caps, 'schedule.write')) {
            return sendJson(res, 403, { ok: false, error: 'caps_denied', need: 'schedule.write' });
          }
          const r = await svc.run({ id });
          return sendJson(res, 200, { ok: true, v: 2, action, id, result: r, caps: summarize(caps) });
        }
        case 'edit': {
          if (!capsAllowed(caps, 'schedule.write')) {
            return sendJson(res, 403, { ok: false, error: 'caps_denied', need: 'schedule.write' });
          }
          const want = String(body.edit_action || 'update');
          const request = want === 'delete'
            ? { id, action: 'delete' }
            : { id, action: 'update', patch: body.patch ?? {} };
          // 只有调用方显式带了 expectedUpdatedAt 才做乐观比较；
          // 传 null 会被理解为"与缺失的修订比较"，会把已有修订的任务判成 schedule_conflict
          if (body.expectedUpdatedAt !== undefined) request.expectedUpdatedAt = body.expectedUpdatedAt;
          const r = await svc.edit(request);
          return sendJson(res, 200, { ok: true, v: 2, action, id, result: r, caps: summarize(caps) });
        }
        case 'catalog':
        default: {
          const tasks = await svc.catalog();
          return sendJson(res, 200, { ok: true, v: 2, action: 'catalog', tasks, caps: summarize(caps) });
        }
      }
    } catch (e) {
      return sendJson(res, 400, {
        ok: false, v: 2, error: 'schedule_failed',
        detail: String(e?.message ?? e).slice(0, 300),
      });
    }
  });

  route('exact', '/v2/plan', async (req, res) => {
    if (!authOk(req) && !isLocal(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    const svc = planSvc();
    if (!svc) {
      return sendJson(res, 503, {
        ok: false, v: 2, error: 'plan_unavailable',
        hint: '这个 DSH 版本没有 plan-mode 插件',
      });
    }
    let body = {};
    let sid = '';
    let caps = '';
    try {
      if (String(req.method || 'GET').toUpperCase() === 'GET') {
        const q = new URL(req.url ?? '/', 'http://dsh.invalid');
        sid = String(q.searchParams.get('session_id') || '');
        caps = q.searchParams.get('caps') ?? '';
      } else {
        body = JSON.parse((await readBody(req)) || '{}');
        sid = String(body.session_id || '');
        caps = body.caps ?? '';
      }
    } catch { /* 用默认值 */ }
    if (!capsAllowed(caps, 'plan.read')) {
      return sendJson(res, 403, { ok: false, error: 'caps_denied', need: 'plan.read' });
    }
    const agent = liveAgentOf(sid);
    if (!agent) {
      return sendJson(res, 409, {
        ok: false, v: 2, error: 'agent_not_live',
        hint: '这条会话当前不在电脑端运行（plan 状态只能通过 live agent 读写）',
      });
    }
    try {
      if (String(req.method || 'GET').toUpperCase() !== 'GET') {
        if (!capsAllowed(caps, 'plan.write')) {
          return sendJson(res, 403, { ok: false, error: 'caps_denied', need: 'plan.write' });
        }
        if (typeof body.active !== 'boolean') {
          return sendJson(res, 400, { ok: false, error: 'active_required' });
        }
        const how = svc.set(agent, body.active);
        const st = svc.get(agent) ?? {};
        return sendJson(res, 200, {
          ok: true, v: 2, how, active: Boolean(st.active), pending: st.pending, caps: summarize(caps),
        });
      }
      const st = svc.get(agent) ?? {};
      return sendJson(res, 200, {
        ok: true, v: 2, active: Boolean(st.active), pending: st.pending, caps: summarize(caps),
      });
    } catch (e) {
      return sendJson(res, 400, {
        ok: false, v: 2, error: 'plan_failed', detail: String(e?.message ?? e).slice(0, 300),
      });
    }
  });

  route('exact', '/v2/subagents', async (req, res) => {
    if (!authOk(req) && !isLocal(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    let sid = '';
    let caps = '';
    try {
      if (String(req.method || 'GET').toUpperCase() === 'GET') {
        const q = new URL(req.url ?? '/', 'http://dsh.invalid');
        sid = String(q.searchParams.get('session_id') || '');
        caps = q.searchParams.get('caps') ?? '';
      } else {
        const b = JSON.parse((await readBody(req)) || '{}');
        sid = String(b.session_id || '');
        caps = b.caps ?? '';
      }
    } catch { /* 用默认值 */ }
    if (!capsAllowed(caps, 'subagents.panel')) {
      return sendJson(res, 403, { ok: false, error: 'caps_denied', need: 'subagents.panel' });
    }
    if (!sid) return sendJson(res, 400, { ok: false, error: 'session_id required' });
    try {
      const children = await subagentsOf(sid);
      return sendJson(res, 200, { ok: true, v: 2, session_id: sid, children, caps: summarize(caps) });
    } catch (e) {
      return sendJson(res, 500, {
        ok: false, v: 2, error: 'subagents_failed', detail: String(e?.message ?? e).slice(0, 300),
      });
    }
  });

  route('exact', '/v2/answer', async (req, res) => {
    if (!authOk(req) && !isLocal(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    if (String(req.method || 'GET').toUpperCase() === 'GET') {
      return sendJson(res, 405, { ok: false, error: 'POST required' });
    }
    let body = {};
    try { body = JSON.parse((await readBody(req)) || '{}'); } catch {
      return sendJson(res, 400, { ok: false, error: 'bad json body' });
    }
    if (!capsAllowed(body.caps, 'questions.answer')) {
      return sendJson(res, 403, { ok: false, error: 'caps_denied', need: 'questions.answer' });
    }
    // 答案批次：每个问题恰好一条 {id, selected:[], custom?}
    const answers = Array.isArray(body.answers) ? body.answers : [];
    const normalized = answers
      .filter((a) => a && typeof a.id === 'string' && a.id)
      .map((a) => ({
        id: a.id,
        selected: Array.isArray(a.selected) ? a.selected.map(String) : [],
        ...(typeof a.custom === 'string' && a.custom.trim() ? { custom: a.custom.trim() } : {}),
      }));
    if (!normalized.length) return sendJson(res, 400, { ok: false, error: 'answers_required' });

    // ① 走审批/问答网关：先答先赢（与桌面端竞争）
    const gw = approvalGateway();
    if (gw && body.id) {
      try {
        for (const pending of gw.pendingRemoteEvents.values()) {
          if (String(pending.id) !== String(body.id)) continue;
          if (String(pending?.frame?.event) !== 'user-questions/request') continue;
          gw.settleRemoteEvent(pending, { kind: 'result', value: { answers: normalized } });
          return sendJson(res, 200, { ok: true, v: 2, via: 'gateway', id: String(body.id) });
        }
      } catch { /* 网关结构变化，退回 continued */ }
    }
    // ② continued（超时后仍可答）：需要 live agent + callId
    const sid = String(body.session_id || '');
    const callId = String(body.call_id || '');
    const svc = questionsSvc();
    const agent = liveAgentOf(sid);
    if (svc && typeof svc.answer === 'function' && agent && callId) {
      try {
        const accepted = await svc.answer(agent, callId, { answers: normalized });
        return sendJson(res, 200, {
          ok: Boolean(accepted), v: 2, via: 'continued',
          hint: accepted ? undefined : '这条问题已经不可回答（已过期或已结算）',
        });
      } catch (e) {
        return sendJson(res, 400, {
          ok: false, v: 2, error: 'answer_failed', detail: String(e?.message ?? e).slice(0, 300),
        });
      }
    }
    return sendJson(res, 404, {
      ok: false, v: 2, error: 'question_not_found',
      hint: '问题已结束，或这条会话不在电脑端运行（continued 需要 live agent）',
    });
  });

  // 磁盘会话数的短缓存：statusBody 被面板 5 秒轮询一次，别每次都全盘 stat
  let dshSessionsCache = { at: 0, n: 0 };
  const dshSessionsOnDisk = () => {
    const now = Date.now();
    if (now - dshSessionsCache.at > 3000) {
      try { dshSessionsCache = { at: now, n: sessionFileIndex().size }; }
      catch { dshSessionsCache = { at: now, n: dshSessionsCache.n || 0 }; }
    }
    return dshSessionsCache.n;
  };
  /**
   * 执行位置（会话属性，不是"模式"）。
   *
   * 统一的自动策略：
   *   · 会话在桌面端活着（live agent）→ host（宿主内，零冷启动，桌面实时可见）
   *   · 否则 → cli（子进程续写，桌面没开也能用）
   * 用户不再需要选"同步/直连"，只有"跟哪条对话"。
   */
  const executionInfo = (sid) => {
    const live = (() => { try { return Boolean(sid) && Boolean(ctx.agents?.get?.(sid)); } catch { return false; } })();
    const hostOnly = cfg.cliMode === false;
    const preferred = hostOnly || live ? 'host' : 'cli';
    return {
      mode: hostOnly ? 'host-only' : 'auto',
      kind: preferred,
      preferred,
      reason: hostOnly
        ? '插件配置为仅宿主内执行'
        : (live ? '会话正在桌面端打开，走宿主内（无冷启动）' : '会话未在桌面端打开，走 CLI 续写'),
      profile: preferred === 'host' ? null : String(cfg.cliProfile || 'headless'),
      cwd: String(cfg.workingDir || '') || null,
      policy: String(CLI_PERM.mode),
    };
  };
  const statusBody = () => ({
    ok: true,
    service: 'dsh-plugin-remote',
    version: 8,
    protocol: 2,
    bridge: 'dsh-plugin',
    channels: { session_acp: true, exec: allowExec === true },
    acp_agent: { provider: cfg.provider || '(none)', model: cfg.model || '(none)', preset: lastPreset, transport: 'in-process' },
    permission_policy: allowExec === true ? 'workspace-write' : 'read-only',
    session_streams: handles.size,
    // ★ 语义修正（v8.1）：sessions_count 曾经是 handles.size（插件内存里的流），
    //   手机端标签却写"电脑端 dsh 会话数"，于是明明有 110 条会话却显示 0。
    //   现在：sessions_count = 磁盘上的真实会话数；插件在管流单独给 plugin_sessions。
    sessions_count: dshSessionsOnDisk(),
    plugin_sessions: handles.size,
    active_jobs: activeJobs,
    // ★ 单一文案模板：手机 / 电脑 UI 直接渲染这组行，不再各自拼一套说法
    text_lines: (() => {
      const disk = dshSessionsOnDisk();
      return [
        `插件 v8 · ${allowExec === true ? '可执行' : '只读'}`,
        `电脑端会话 ${disk} 条（插件在管 ${handles.size} 条）`,
        `执行位置：${cfg.cliMode === false ? '宿主进程内' : 'CLI 子进程'}`,
        `在跑任务：${activeJobs}${activeJobs ? '' : '（空闲）'}`,
      ];
    })(),
    // 直连模式的 CLI 子进程池：running/queued 是面板与手机端判断"电脑忙不忙"的依据
    cli: {
      mode: cfg.cliMode === false ? 'in-process' : 'cli',
      running: CLI_SLOT.running,
      queued: CLI_SLOT.queued,
      max: CLI_SLOT.max,
      warm_reuse: cfg.warmReuse === true,
      max_queued: CLI_SLOT.maxQueued,
      priority: 'below-normal',
      timeout_ms: Number(cfg.cliTimeoutMs) || 300000,
    },
    devices: devices.devices.length,
    // 主令牌最近一次被使用的时间：手机端只有在"还没换成设备令牌"或"用设备令牌失败退回主令牌"时才走主令牌，
    // 有值就说明当前有连接在用主令牌（面板据此提示，避免把"设备数 0"误读成"没人连着"）
    main_token_last_seen: mainTokenLastSeen,
    pairing_codes_pending: pairingCodes.size,
    prompts: promptCount,
    // 隧道/公网域名：手机连接页的“隧道域名”就用它；没配置时是空串
    public_url: String(cfg.publicUrl ?? '').trim(),
    port: ctx.webServer.port,
    uptime_ms: Date.now() - startedAt,
    // ---- 新增：双端能力的可见状态（面板据此显示）----
    events: { since: true, log_replay: true, snapshot: true, heartbeat: 15 },
    approval_relay: (() => {
      const gw = (() => { try { return approvalGateway(); } catch { return null; } })();
      let pending = 0;
      try { pending = pendingRemoteOf(null, 'approval').length + pendingRemoteOf(null, 'question').length; } catch { /* ignore */ }
      return { gateway: Boolean(gw), pending };
    })(),
    cmd_queue: (() => {
      const list = [...cmdQueue.values()];
      return {
        allowed: [...CMD_ALLOW],
        queued: list.filter((c) => c.state !== 'done').length,
        done: list.filter((c) => c.state === 'done').length,
        recent: list.slice(-5).map((c) => ({
          id: c.id, tool: c.tool, state: c.state, ok: c.ok, at: c.createdAt,
          result: String(c.result || '').slice(0, 120),
        })),
      };
    })(),
  });

  // ==================== 自检 / 双端对账 ====================
  /** 目录在 OS 层是否真实存在（asar 内部虚拟路径在 OS 层不存在） */
  const realDir = (p) => {
    try { return statSync(String(p || '')).isDirectory(); } catch { return false; }
  };
  /** process.cwd() 在目录被删后会抛异常，必须包住 */
  const safeCwd = () => { try { return process.cwd(); } catch { return ''; } };

  /** 自检项的**单一来源**：/health、/v2/view、电脑面板、手机页面都渲染这一份，杜绝三处各写一套 */
  const buildHealthChecks = () => {
    const cwd = safeCwd();
    const cwdOk = Boolean(cwd) && realDir(cwd);
    const home = String(process.env.DSH_HOME || '');
    const homeOk = Boolean(home) && realDir(home);
    const checks = [
      {
        name: 'host_cwd', ok: cwdOk, severity: 'error',
        detail: cwdOk ? cwd : `工作目录不存在或不是目录：${cwd || '(空)'}`,
        hint: 'cwd 无效会让所有起子进程的工具报 ENOENT（看起来像 powershell/rg 丢了，其实是被 cwd 连累）',
      },
      {
        name: 'dsh_home', ok: homeOk, severity: 'error',
        detail: home ? (homeOk ? home : `DSH_HOME 不存在：${home}`) : '(未设置 DSH_HOME)',
      },
      {
        name: 'exec_channel', ok: allowExec === true, severity: 'info',
        detail: allowExec === true ? '已允许远程执行命令' : '远程执行已关闭（/exec 与 shell 动作会被拒）',
      },
      {
        name: 'agent_preset', ok: lastPreset !== '(unset)' && !/^error/.test(String(lastPreset)), severity: 'warn',
        detail: String(lastPreset),
        hint: '(unset) = 还没有通过插件建过会话；error: 开头 = 预设挂载失败，会话会"启动却无事可做"',
      },
      {
        name: 'session_mount', ok: handles.size > 0, severity: 'info',
        detail: `在管会话 ${handles.size} 条（会话级流 ${handles.size}）`,
      },
    ];
    return { cwd, cwdOk, home, homeOk, checks };
  };

  /**
   * GET /health —— 探活 + 自检。
   *
   * 未鉴权且非本机时只回最小信息：手机首次探测桥接时还没有令牌，收紧会直接打断配对。
   * 带令牌（或本机）才返回路径/能力明细。
   */
  route('exact', '/health', (req, res) => {
    const detailed = (() => { try { return isLocal(req) || authOk(req) === true; } catch { return false; } })();
    if (!detailed) {
      return sendJson(res, 200, { ok: true, service: 'dsh-plugin-remote', version: 8, detail: false });
    }
    const { cwd, cwdOk, home, homeOk, checks } = buildHealthChecks();
    const preserved = ['DSH_HOME', 'DSH_PROFILE', 'DSH_SESSION_ID'].filter((k) => Boolean(process.env[k]));
    sendJson(res, 200, {
      ok: checks.every((c) => c.ok || c.severity !== 'error'),
      service: 'dsh-plugin-remote', version: 8,
      pid: process.pid, node: process.version, platform: process.platform,
      uptime_ms: Date.now() - startedAt,
      cwd: { path: cwd, exists: cwdOk, warning: CWD_GUARD.lastWarning },
      dsh_home: { path: home, exists: homeOk },
      exec: { allowed: allowExec === true, permission_policy: allowExec === true ? 'workspace-write' : 'read-only' },
      agent: {
        preset: String(lastPreset), selection: String(lastSelection),
        plugin_sessions: handles.size, dsh_sessions_total: dshSessionsOnDisk(),
      },
      cli: { running: CLI_SLOT.running, queued: CLI_SLOT.queued, mode: cfg.cliMode === false ? 'in-process' : 'cli' },
      env: { preserved },
      checks,
      checked_at: Date.now(),
    });
  });

  /**
   * POST /reconcile —— 双端对账。
   *
   * 手机把"它认为的对话"发上来，电脑侧拿落盘日志做权威版本，
   * 逐条比对（role+text）并给出第一个分歧点 + 缺失/多余条目，手机据此补拉或截断。
   * 也可以只给 digest / last_seq 做轻量校验（不传正文时）。
   *
   * 请求：{ session_id, messages?: [{role,text}], digest?, last_seq? }
   * 返回：{ verdict: match|diverged|no_session|unknown, host:{...}, diff:{...} }
   */
  const reconcileHandler = async (req, res) => {
    if (!authOk(req) && !isLocal(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    let body = {};
    try {
      if (String(req.method || 'GET').toUpperCase() === 'GET') {
        const q = new URL(req.url ?? '/', 'http://dsh.invalid');
        body = {
          session_id: q.searchParams.get('session_id') || '',
          digest: q.searchParams.get('digest') || undefined,
          last_seq: q.searchParams.get('last_seq') || undefined,
          light: q.searchParams.get('light') === '1' ? '1' : undefined,
        };
      } else {
        body = JSON.parse((await readBody(req)) || '{}');
        if (!body || typeof body !== 'object') body = {};
      }
    } catch { return sendJson(res, 400, { ok: false, error: 'bad json body' }); }

    const sid = String(body.session_id || body.sessionId || '').trim();
    if (!sid) return sendJson(res, 400, { ok: false, error: 'session_id required' });

    const svc = persistence();
    if (!svc || typeof svc.open !== 'function') return sendJson(res, 503, { ok: false, error: 'sessionPersistence 不可用' });

    let events = [];
    let header = {};
    try {
      const handle = await svc.open(sid, 'read');
      try {
        const got = await handle.read();
        events = Array.isArray(got) ? got : (got?.events ?? []);
        header = handle?.header ?? {};
      } finally { try { await handle.close?.(); } catch { /* 已关 */ } }
    } catch (e) {
      return sendJson(res, 404, {
        ok: false, verdict: 'no_session', session_id: sid,
        error: String(e?.message ?? e).slice(0, 200),
        hint: '电脑上没有这条会话：可能已被删除/归档，或 session_id 属于手机本地',
      });
    }

    const rows = [];
    for (const ev of events) {
      const m = messageOf(ev);
      if (m) rows.push({ ...m, id: messageIdOf(ev), seq: Number(ev?.seq) || 0 });
    }
    const key = (m) => `${m.role}\u0000${String(m.text ?? '').replace(/\s+/g, ' ').trim()}`;
    const hostLines = rows.map(key);
    const digestOf = (lines) => createHash('sha256').update(lines.join('\n')).digest('hex');
    const hostDigest = digestOf(hostLines);
    const lastSeq = events.reduce((a, ev) => Math.max(a, Number(ev?.seq) || 0), 0);
    const lastTime = events.reduce((a, ev) => Math.max(a, Number(ev?.time) || 0), 0);

    // 手机可在同一次请求里顺带回执（批量 ack），省一次往返
    if (Array.isArray(body.acks) && body.acks.length) {
      ACK_STORE.set(sid, {
        acked_seq: body.acks.reduce((a, x) => Math.max(a, Number(x?.seq) || 0), 0),
        acked_ids: body.acks.map((x) => x?.id).filter((x) => typeof x === 'string' && x),
      });
    }
    const storedAck = ACK_STORE.get(sid) ?? { acked_seq: 0, acked_ids: [] };
    const ackedSeq = Number(storedAck.acked_seq) || 0;
    const ackSummary = {
      acked_seq: ackedSeq,
      acked_count: Array.isArray(storedAck.acked_ids) ? storedAck.acked_ids.length : 0,
      updated_at: storedAck.updated_at ?? null,
      unacked_count: rows.filter((r) => r.seq > ackedSeq).length,
      unacked: rows.filter((r) => r.seq > ackedSeq).slice(0, 20),
    };

    // light=1：只回摘要（手机定时对账用，不需要正文）
    const light = body.light === true || String(body.light ?? '') === '1';
    if (light) {
      // 对账的"权威口径"只有一个：seq 游标。
      // 老版只回 summary，手机永远判不出"对不对"；现在带 last_seq（手机已收到的最大序号）
      // 就回 match/behind + gap，客户端不需要再自己算摘要。
      const clientSeqLight = (() => {
        const raw = Number(body.last_seq ?? body.client_last_seq);
        return Number.isFinite(raw) && raw >= 0 ? raw : null;
      })();
      const gap = clientSeqLight === null ? null : Math.max(0, lastSeq - clientSeqLight);
      const verdictLight = gap === null ? 'summary' : (gap === 0 ? 'match' : 'behind');
      return sendJson(res, 200, {
        ok: true, session_id: sid, verdict: verdictLight, checked_at: Date.now(), light: true,
        host: {
          exists: true, event_count: events.length, last_seq: lastSeq, last_time: lastTime,
          message_count: rows.length, digest: hostDigest,
        },
        client: { last_seq: clientSeqLight },
        gap,
        ack: ackSummary,
      });
    }

    const clientLines = Array.isArray(body.messages)
      ? body.messages.map((m) => key({ role: m?.role === 'assistant' ? 'assistant' : 'user', text: m?.text ?? m?.content ?? '' }))
      : null;
    const clientDigest = typeof body.digest === 'string' ? body.digest
      : typeof body.client_digest === 'string' ? body.client_digest : null;
    const clientSeq = Number(body.last_seq ?? body.client_last_seq);

    let verdict = 'unknown';
    let diff = null;
    if (clientLines) {
      const n = Math.min(clientLines.length, hostLines.length);
      let first = -1;
      for (let i = 0; i < n; i++) { if (clientLines[i] !== hostLines[i]) { first = i; break; } }
      const same = first === -1 && clientLines.length === hostLines.length;
      verdict = same ? 'match' : 'diverged';
      const toPair = (s) => { const i = s.indexOf('\u0000'); return { role: s.slice(0, i), text: s.slice(i + 1) }; };
      diff = {
        first_divergence: first,
        host_count: hostLines.length,
        client_count: clientLines.length,
        // first===-1 且数量不同 → 手机少了尾部若干条，补拉即可
        missing_on_client: (first === -1 ? hostLines.slice(clientLines.length) : hostLines.slice(first)).slice(0, 20).map(toPair),
        extra_on_client: (first === -1 ? [] : clientLines.slice(first)).slice(0, 20).map(toPair),
        host_tail: rows.slice(-3),
      };
      if (clientLines.length > hostLines.length && first === -1) {
        diff.hint = '手机端比电脑端多出尾部消息：可能是本地未送达的草稿，或电脑端回滚过';
      }
    } else if (clientDigest) {
      verdict = clientDigest === hostDigest ? 'match' : 'diverged';
    }

    sendJson(res, 200, {
      ok: true, session_id: sid, verdict, checked_at: Date.now(),
      host: {
        exists: true,
        agent_preset: String(header.agentPreset ?? ''),
        event_count: events.length,
        last_seq: lastSeq,
        last_time: lastTime,
        message_count: hostLines.length,
        digest: hostDigest,
        tail: rows.slice(-3),
      },
      client: {
        digest: clientDigest,
        last_seq: Number.isFinite(clientSeq) ? clientSeq : null,
        message_count: clientLines ? clientLines.length : null,
      },
      ack: ackSummary,
      diff,
      note: 'host.digest = sha256(每行 "role\\0text"，按顺序拼接换行)；手机端用同样算法即可离线自校验',
    });
  };

  // 同一实现两个门牌：/reconcile（旧）与 /v2/reconcile（新客户端）
  route('exact', '/reconcile', reconcileHandler);
  route('exact', '/v2/reconcile', reconcileHandler);

  /**
   * POST /ack —— 回执（对账的另一半）。
   *
   * 请求：{ session_id, seq?, ids?: string[] }
   * 语义：手机确认"seq 及之前的消息我都完整收到了"，和/或"这些 id 收到了"。
   * 水位线单调只增，落盘到 .acks.json，重启不丢；断线重连后只补水位线之后的部分。
   */
  const ackHandler = async (req, res) => {
    if (!authOk(req) && !isLocal(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    let body = {};
    try { body = JSON.parse((await readBody(req)) || '{}'); } catch { return sendJson(res, 400, { ok: false, error: 'bad json body' }); }
    const sid = String(body.session_id || body.sessionId || '').trim();
    if (!sid) return sendJson(res, 400, { ok: false, error: 'session_id required' });
    const ids = (Array.isArray(body.ids) ? body.ids : (body.id ? [body.id] : []))
      .filter((x) => typeof x === 'string' && x);
    const seq = Number(body.seq) || 0;
    if (!seq && ids.length === 0) return sendJson(res, 400, { ok: false, error: 'seq 或 ids 至少给一个' });
    const next = ACK_STORE.set(sid, { acked_seq: seq, acked_ids: ids });
    sendJson(res, 200, {
      ok: true, session_id: sid,
      ack: { acked_seq: next.acked_seq, acked_count: next.acked_ids.length, updated_at: next.updated_at },
      note: '回执水位线只增不减；下次 /reconcile 会按它算 unacked',
    });
  };

  route('exact', '/ack', ackHandler);
  route('exact', '/v2/ack', ackHandler);

  // ==================== 协议 v2：握手 / 视图 / 消息 / 事件流 ====================

  /**
   * 连接登记表（内存）：谁在连着、最后心跳什么时候。
   * 不做持久化 —— 连接状态本来就有时效性，重启后重新登记即可。
   */
  const CONNECTIONS = new Map();
  const CONNECTION_TTL_MS = 30000;   // 30s 没有心跳视为离线（心跳 10s，留 3 个周期）
  const touchConnection = (sid, kind = 'sse') => {
    if (!sid) return;
    const prev = CONNECTIONS.get(sid) || {};
    CONNECTIONS.set(sid, { ...prev, kind, last_seen: Date.now() });
  };
  const dropConnection = (sid) => { CONNECTIONS.delete(sid); };
  const connectionSnapshot = () => [...CONNECTIONS.entries()].map(([sid, v]) => ({
    session_id: sid,
    kind: v.kind || 'sse',
    last_seen: v.last_seen || 0,
    age_ms: Date.now() - (v.last_seen || 0),
    online: Date.now() - (v.last_seen || 0) < CONNECTION_TTL_MS,
  }));

  /**
   * 字段字典：两端 UI 只准用这里定义的名字。
   * 之前 "sessions_count=0" 的事故就是同一个词在两处指不同的东西造成的。
   */
  const FIELD_DICT = {
    'sessions_count': { type: 'int', owner: 'server', meaning: '电脑磁盘上的真实会话数' },
    'plugin_sessions': { type: 'int', owner: 'server', meaning: '插件内存里托管的会话流数量' },
    'active_jobs': { type: 'int', owner: 'server', meaning: '插件此刻正在执行的任务数（0 = 空闲）' },
    'session_streams': { type: 'int', owner: 'server', meaning: '同 plugin_sessions，保留旧名' },
    'last_seq': { type: 'int', owner: 'server', meaning: '会话事件流的最大序号' },
    'acked_seq': { type: 'int', owner: 'server', meaning: '客户端已确认收到的最大序号（只增不减）' },
    'digest': { type: 'string', owner: 'server', meaning: 'sha256(每行 role\\0text 换行拼接)' },
    'execution': { type: 'string', owner: 'server', meaning: 'host = 宿主进程内；cli = 子进程（会话属性，不是模式）' },
    'verdict': { type: 'string', owner: 'server', meaning: 'match | diverged | no_session | summary | unknown' },
  };

  route('exact', '/v2/schema', (req, res) =>
    sendJson(res, 200, { ok: true, v: 2, fields: FIELD_DICT }));

  /**
   * POST /v2/hello —— 握手 + 能力协商。
   * 客户端先问"你能给我什么"，再决定调哪些接口；不再靠猜版本。
   */
  route('exact', '/v2/hello', async (req, res) => {
    if (!authOk(req) && !isLocal(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    let body = {};
    try {
      if (String(req.method || 'GET').toUpperCase() === 'GET') {
        const q = new URL(req.url ?? '/', 'http://dsh.invalid');
        body = { session_id: q.searchParams.get('session_id') || '' };
      } else {
        body = JSON.parse((await readBody(req)) || '{}');
      }
    } catch { body = {}; }
    const sid = String(body?.session_id || '').trim();
    // 能力协商（2026-11-12）：客户端带 caps 就按它裁剪下发内容；不带则是 legacy（一切照发）。
    const caps = summarize(body?.caps);
    const client = body?.client && typeof body.client === 'object' ? body.client : null;
    sendJson(res, 200, {
      ok: true, v: 2,
      server: { name: 'smartquiz-remote', version: 8, node: process.version, platform: process.platform },
      capabilities: [...SERVER_CAPS],
      caps,
      client: client ? {
        id: String(client.id ?? ''), kind: String(client.kind ?? ''), ver: String(client.ver ?? ''),
      } : null,
      // 兼容旧字段：features 仍是布尔表，但已按客户端能力裁剪（legacy 客户端全部为 true）
      features: {
        event_stream: true, since_replay: true, ack_watermark: true,
        view_model: true, tool_cards: true, approval_relay: Boolean(approvalGateway()),
        approval_timeline: !caps.missing.includes('parts.approval'),
        view_timeline: !caps.missing.includes('view.cards.timeline'),
      },
      session: sid ? { id: sid, exists: await sessionExists(sid) } : null,
      limits: { max_message_chars: 200000, stream_keepalive_ms: 15000, stream_max_ms: 600000 },
      server_time: Date.now(),
    });
  });

  /**
   * GET /v2/view —— 服务端派生 ViewModel。
   *
   * 这是"两端同一个界面"的关键：电脑面板与手机页面都只渲染 cards[]，
   * 文案/排序/状态色只在服务端定义一次，再也不会出现"同一个数字两种解释"。
   */
  const buildView = async (sid, opts = {}) => {
    const st = statusBody();
    const { cwd, checks } = buildHealthChecks();
    const cards = [
      {
        kind: 'status', title: '电脑端',
        rows: [
          { label: '插件', value: 'v' + st.version + (st.channels.exec ? ' · 可执行' : ' · 只读') },
          { label: '会话', value: `${st.sessions_count} 条（插件在管 ${st.plugin_sessions}）` },
          { label: '执行位置', value: (() => {
            const ex = executionInfo(sid);
            return (ex.preferred === 'host' ? '宿主内' : 'CLI 子进程') + '（自动 · ' + ex.reason + '）';
          })() },
          { label: '在跑任务', value: st.active_jobs ? String(st.active_jobs) : '0（空闲）' },
          { label: '工作目录', value: cwd || '(未知)' },
        ],
      },
      {
        kind: 'checks', title: '自检',
        items: checks.map((c) => ({
          state: c.ok ? 'ok' : (c.severity === 'error' ? 'error' : 'warn'),
          name: c.name, detail: c.detail, hint: c.hint || null,
        })),
      },
      {
        kind: 'connections', title: '连接（呼吸）',
        items: (() => {
          const list = connectionSnapshot();
          if (!list.length) {
            return [{ state: 'warn', name: '无活动连接', detail: '手机端当前没有订阅事件流（心跳 10s / 30s 判离线）' }];
          }
          return list.map((c) => ({
            state: c.online ? 'ok' : 'warn',
            name: String(c.session_id).slice(0, 24),
            detail: `${c.kind} · ${Math.round(c.age_ms / 1000)}s 前心跳`,
          }));
        })(),
      },
    ];

    let header = { session_id: sid || '', title: '', badge: st.channels.exec ? '可执行' : '只读' };
    if (sid) {
      try {
        const head = await readStoredHead(sid);
        const ack = ACK_STORE.get(sid) ?? { acked_seq: 0, acked_ids: [] };
        header = { session_id: sid, title: head.title || sid, badge: '已连接' };
        cards.push({
          kind: 'session', title: head.title || sid,
          rows: [
            { label: '会话', value: sid },
            { label: '预设', value: head.agentPreset || '(未知)' },
            { label: '回执', value: 'seq=' + (Number(ack.acked_seq) || 0) },
          ],
        });
        // 待审批/待回答：作为时间线上的一等卡片（以前只走旁路 relay，手机看不到上下文）
        const pendingItems = (() => {
          try {
            return [...pendingRemoteOf(sid, 'approval'), ...pendingRemoteOf(sid, 'question')];
          } catch { return []; }
        })();
        if (pendingItems.length) {
          cards.push({
            kind: 'approval', title: '待你处理',
            items: pendingItems.map((a) => ({
              state: 'warn',
              name: String(a.id).slice(0, 18),
              detail: (a.kind === 'question' ? '提问' : '审批')
                + (a.request?.title ? ' · ' + a.request.title : '')
                + (a.request?.message ? ' · ' + String(a.request.message).slice(0, 80) : ''),
              approval_id: a.id,
              approval_kind: a.kind,
            })),
          });
        }
        if (opts.withTimeline !== false) {
          const tail = await readStoredTail(sid, 12);
          // 每条消息标来源：手机发的（插件台账里有）vs 电脑键盘输入的
          const messages = tail.messages.map((m) => ({
            ...m,
            origin: m.role === 'user' ? ORIGIN.originOf(sid, m.text, m.time) : 'assistant',
          }));
          cards.push({ kind: 'timeline', title: '最近消息', messages });
        }
      } catch (e) {
        cards.push({
          kind: 'session', title: '会话', error: 'no_session',
          detail: String(e?.message ?? e).slice(0, 160),
        });
      }
    }
    // 能力协商可见化：两端各自声明了什么、缺什么，直接进时间线/自检页，而不是只写日志
    if (opts.caps) {
      const c = opts.caps;
      cards.push({
        kind: 'caps', title: '能力协商',
        rows: [
          {
            label: '客户端',
            value: c.legacy ? '未声明（兼容模式）' : (c.client.length + ' 项 · v' + c.version),
          },
          { label: '未识别', value: c.unknown.length ? c.unknown.join('、') : '无' },
          { label: '缺少', value: c.missing.length ? c.missing.join('、') : '无' },
        ],
      });
    }
    return { v: 2, ok: true, generated_at: Date.now(), header, cards };
  };

  route('exact', '/v2/view', async (req, res) => {
    if (!authOk(req) && !isLocal(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    let sid = '';
    let withTimeline = true;
    let caps = [];
    try {
      if (String(req.method || 'GET').toUpperCase() === 'GET') {
        const q = new URL(req.url ?? '/', 'http://dsh.invalid');
        sid = String(q.searchParams.get('session_id') || '').trim();
        withTimeline = q.searchParams.get('timeline') !== '0';
        caps = normalizeCaps(q.searchParams.get('caps') ?? '');
      } else {
        const b = JSON.parse((await readBody(req)) || '{}');
        sid = String(b?.session_id || '').trim();
        withTimeline = b?.timeline !== false;
        caps = normalizeCaps(b?.caps ?? '');
      }
    } catch { /* 用默认值 */ }
    try {
      const view = await buildView(sid, { withTimeline, caps: summarize(caps) });
      // 能力协商：客户端声明了就不下发它渲染不了的卡（legacy 客户端不裁）
      view.cards = filterViewCards(view.cards, caps);
      view.caps = summarize(caps);
      sendJson(res, 200, view);
    } catch (e) {
      sendJson(res, 500, { ok: false, v: 2, error: String(e?.message ?? e) });
    }
  });

  /**
   * POST /v2/pulse —— 双端心跳 + 连接状态校验（"呼吸"）。
   *
   * 手机每隔 N 秒带 { session_id, client_id, since } 打一次：
   *   · 电脑回 now / 事件总数 / 缺口 gap / 回执水位线 / 当前连接表
   *   · 客户端据此判断：在线？落后多少？要不要补拉（gap>0 → /v2/stream?since=）
   *   · 服务端同时刷新该会话的"最后心跳"，电脑面板据此显示手机是否在线
   */
  route('exact', '/v2/pulse', async (req, res) => {
    if (!authOk(req) && !isLocal(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    let body = {};
    try {
      if (String(req.method || 'GET').toUpperCase() === 'GET') {
        const q = new URL(req.url ?? '/', 'http://dsh.invalid');
        body = {
          session_id: q.searchParams.get('session_id') || '',
          client_id: q.searchParams.get('client_id') || '',
          since: q.searchParams.get('since') || undefined,
        };
      } else {
        body = JSON.parse((await readBody(req)) || '{}');
      }
    } catch { body = {}; }
    const sid = String(body?.session_id || '').trim();
    const since = Number(body?.since);
    let exists = false;
    let eventCount = 0;
    if (sid) {
      try {
        const st = await persistence()?.stat?.(sid);
        exists = Boolean(st);
        eventCount = Number(st?.eventCount ?? 0) || 0;
      } catch { /* 无会话或 persistence 不可用 */ }
      touchConnection(sid, 'pulse');
    }
    const ack = sid ? (ACK_STORE.get(sid) ?? { acked_seq: 0 }) : null;
    sendJson(res, 200, {
      ok: true, v: 2, now: Date.now(),
      echo: { client_id: body?.client_id || '', since: Number.isFinite(since) ? since : null },
      session: { id: sid, exists, event_count: eventCount },
      // gap 是"事件总数 - 客户端游标"的近似值：>0 就该补拉
      gap: Number.isFinite(since) ? Math.max(0, eventCount - since) : null,
      ack: ack ? { acked_seq: Number(ack.acked_seq) || 0 } : null,
      connections: connectionSnapshot(),
      server: { version: 8, protocol: 2, heartbeat_ms: 10000 },
    });
  });

  /**
   * POST /v2/session —— 会话身份的**唯一权威**。
   *
   * 核心约定：客户端只说"我要哪个对话码（code）"，session_id 永远是电脑给的那个。
   * 手机不再自己发明 id，也不再同时持有多份 id —— 换机器、重装、清缓存都能靠 code 接回来。
   *
   * 请求：{ action: 'state'|'ensure'|'attach'|'list', code?, session_id?, limit? }
   * 返回：{ v:2, session: { id, code, exists, bound, live, execution } }
   */
  route('exact', '/v2/session', async (req, res) => {
    if (!authOk(req) && !isLocal(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    let body = {};
    try {
      if (String(req.method || 'GET').toUpperCase() === 'GET') {
        const q = new URL(req.url ?? '/', 'http://dsh.invalid');
        body = {
          action: q.searchParams.get('action') || 'state',
          code: q.searchParams.get('code') || '',
          session_id: q.searchParams.get('session_id') || '',
          limit: q.searchParams.get('limit') || undefined,
        };
      } else {
        body = JSON.parse((await readBody(req)) || '{}');
      }
    } catch { return sendJson(res, 400, { ok: false, v: 2, error: 'bad json body' }); }

    const action = String(body?.action || 'state').toLowerCase();
    const code = String(body?.code ?? body?.session_code ?? '').trim();
    const execution = executionInfo(String(body?.session_id || "").trim());

    if (action === 'list') {
      const limit = Math.max(1, Math.min(100, Number(body?.limit) || 20));
      try {
        const rows = await listStoredSessions(limit);
        return sendJson(res, 200, {
          ok: true, v: 2, action, execution, count: rows.length,
          sessions: rows.map((r) => ({
            id: r.session_id, title: r.title || '', updated_at: r.updated_at || null,
            last: r.last || '', cwd: r.cwd || '',
          })),
        });
      } catch (e) {
        return sendJson(res, 500, { ok: false, v: 2, action, error: String(e?.message ?? e) });
      }
    }

    if (action === 'attach') {
      const id = String(body?.session_id || '').trim();
      if (!id) return sendJson(res, 400, { ok: false, v: 2, action, error: 'session_id required' });
      if (!(await sessionExists(id))) {
        return sendJson(res, 404, { ok: false, v: 2, action, error: 'no_session', session: { id, exists: false } });
      }
      // 只有显式 bind=true 才把 code 指向这条，避免"随手看一眼"就改了默认对话
      if (code && body?.bind === true) {
        sessionBinding.set(code, id);
      }
      return sendJson(res, 200, {
        ok: true, v: 2, action,
        session: {
          id, code, exists: true, bound: sessionBinding.get(code) === id,
          live: (() => { try { return Boolean(ctx.agents?.get?.(id)); } catch { return false; } })(),
          execution,
        },
      });
    }

    // state / ensure —— 同一个语义：查"这个 code 现在绑到哪条会话"
    let id = sessionBinding.get(code);
    let exists = false;
    let title = '';
    if (id) {
      exists = await sessionExists(id);
      // 绑定的会话没了（被删/归档/清过季）：把死绑定清掉，别让客户端拿着死 id 续写
      if (!exists) { sessionBinding.set(code, ''); id = ''; }
    }
    if (id) {
      try {
        const head = await readStoredHead(id);
        title = String(head?.title ?? '');
      } catch { /* 标题只是展示 */ }
    }
    sendJson(res, 200, {
      ok: true, v: 2, action, execution,
      session: {
        id, code, title, exists, bound: Boolean(id),
        // 首次对话由执行器创建并回绑；客户端拿到 needs_first_message 就发一条消息即可
        needs_first_message: !id,
        live: (() => { try { return Boolean(id) && Boolean(ctx.agents?.get?.(id)); } catch { return false; } })(),
      },
    });
  });

  /**
   * POST /v2/message —— v2 门牌的消息入口。
   *
   * ★ 执行路径完全复用 legacy 的 runPrompt（CLI / 宿主内两条路都在里面），
   *   所以行为和 /session action=prompt 一致，不存在"两套实现跑偏"。
   *   响应头 `x-dsh-protocol: 2` 标明协议版本；payload 仍是 legacy 结构（含 reply/session_id）。
   */
  route('exact', '/v2/message', async (req, res) => {
    if (!authOk(req) && !isLocal(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    if (String(req.method || 'GET').toUpperCase() !== 'POST') return sendJson(res, 405, { ok: false, error: 'use POST' });
    let body;
    try { body = JSON.parse((await readBody(req)) || '{}'); }
    catch (e) { return sendJson(res, 400, { ok: false, v: 2, error: `bad json: ${e?.message ?? e}` }); }
    const text = String(body?.text ?? body?.prompt ?? body?.task ?? '').trim();
    if (!text) return sendJson(res, 400, { ok: false, v: 2, error: 'text 不能为空' });
    const sid = String(body?.session_id || '').trim() || `smartquiz-${randomBytes(6).toString('hex')}`;
    let targetSid = String(body?.target_session_id ?? body?.session_id ?? '').trim();
    if (targetSid && !(await sessionExists(targetSid))) targetSid = '';
    const wantsStream = body?.stream === true || String(req.headers?.accept ?? '').includes('text/event-stream');
    const cmid = String(body?.client_msg_id ?? '').trim();
    if (cmid && clientMsgDuplicated(sid, cmid)) {
      return sendJson(res, 200, {
        ok: true, v: 2, duplicate: true, session_id: sid,
        hint: '这条消息已受理过（幂等键命中），回复请从事件流补拉',
      });
    }
    const timeoutSec = Number.isFinite(body?.timeout) ? Math.max(5, Math.min(600, Number(body.timeout))) : 120;
    const abort = requestAbort(res);
    try { res.setHeader?.('x-dsh-protocol', '2'); } catch { /* 已发头则忽略 */ }
    const runOpts = {
      signal: abort.signal,
      deadline: Date.now() + timeoutSec * 1000,
      targetSessionId: targetSid,
      sessionCode: typeof body?.session_code === 'string' ? body.session_code : '',
    };
    if (wantsStream) {
      return runPrompt(req, res, sid, text, true, runOpts);
    }
    /**
     * 非流式：把 legacy payload 升成 **v2 信封**。
     *
     * v2 契约：{ v, ok, session_id, reply, execution, error?, duplicate?, debug? }
     * 同时保留 `legacy` 原样字段，旧客户端读 reply/session_id 不受影响。
     */
    const chunks = [];
    const proxy = {
      _code: 200,
      _headers: {},
      setHeader(k, v) { this._headers[k] = v; },
      getHeader(k) { return this._headers[k]; },
      writeHead(code, headers) { this._code = code; if (headers) Object.assign(this._headers, headers); },
      write(s) { if (s) chunks.push(Buffer.from(s)); return true; },
      on() {}, once() {}, removeListener() {},
      get writableEnded() { return false; },
      end(s) {
        if (s) chunks.push(Buffer.from(s));
        let legacy = {};
        try { legacy = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'); }
        catch { legacy = { raw: Buffer.concat(chunks).toString('utf8').slice(0, 2000) }; }
        sendJson(res, this._code || 200, {
          v: 2,
          ok: legacy.ok !== false,
          session_id: legacy.session_id || sid,
          reply: typeof legacy.reply === 'string' ? legacy.reply : '',
          execution: legacy.execution || executionInfo(targetSid),
          error: legacy.error,
          duplicate: legacy.duplicate || undefined,
          hint: legacy.hint,
          debug: legacy.debug,
          legacy,
        });
      },
    };
    await runPrompt(req, proxy, sid, text, false, runOpts);
  });

  /**
   * GET /v2/stream?session_id=&since= —— SSE 事件流。
   *
   * 语义：先按 since 回放已有事件，然后每 1.5s 轮询落盘日志的增量；
   * 空闲时发注释行保活。客户端断开即停（不泄漏定时器）。
   * 事件信封：{ v, seq, id, ts, type, data }
   */
  route('exact', '/v2/stream', async (req, res) => {
    if (!authOk(req) && !isLocal(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    const q = new URL(req.url ?? '/', 'http://dsh.invalid');
    const sid = String(q.searchParams.get('session_id') || '').trim();
    const since = Number(q.searchParams.get('since') || 0) || 0;
    const caps = q.searchParams.get('caps') ?? '';
    if (!sid) return sendJson(res, 400, { ok: false, error: 'session_id required' });
    const svc = persistence();
    if (!svc || typeof svc.open !== 'function') return sendJson(res, 503, { ok: false, error: 'sessionPersistence 不可用' });

    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache, no-transform',
      connection: 'keep-alive',
      'x-dsh-protocol': '2',
    });

    let closed = false;
    let offset = 0;
    touchConnection(sid, 'stream');
    const write = (s) => { if (!closed) { try { res.write(s); } catch { closed = true; } } };
    const sendEv = (ev) => write(`data: ${JSON.stringify({
      v: 2, seq: Number(ev?.seq) || 0, id: ev?.id ?? null,
      ts: Number(ev?.time) || 0, type: String(ev?.type ?? ''), data: ev?.data ?? null,
    })}\n\n`);

    // 实时桥：与 /events 共用 STREAM_HUB（token 增量、子代理活动、日程变化）
    const sub = {
      sid,
      caps,
      send: (o) => {
        if (!o || !eventAllowed(o.type, caps)) return;
        write(`data: ${JSON.stringify({
          v: 2, seq: 0, id: null, ts: Date.now(),
          type: String(o.type ?? ''), data: o.data ?? null,
        })}\n\n`);
      },
    };
    STREAM_HUB.add(sub);

    // 1) 回放
    try {
      const h = await svc.open(sid, 'read');
      try {
        const got = await h.read();
        const events = Array.isArray(got) ? got : (got?.events ?? []);
        for (const ev of events) {
          if ((Number(ev?.seq) || 0) <= since) continue;
          if (!eventAllowed(ev?.type, caps)) continue; // 客户端不具备该能力
          sendEv(ev);
        }
        offset = events.length;
      } finally { try { await h.close?.(); } catch { /* 已关 */ } }
    } catch (e) {
      write(`event: error\ndata: ${JSON.stringify({ v: 2, error: 'no_session', detail: String(e?.message ?? e).slice(0, 160) })}\n\n`);
      try { res.end(); } catch { /* ignore */ }
      return;
    }

    // 2) 增量轮询
    const announcedApprovals = new Set();
    const pumpApprovals = () => {
      // 能力协商：客户端不支持 approval 块就不推审批事件（否则手机端只能静默丢弃）
      if (!eventAllowed('approval', caps)) return;
      try {
        const pend = [...pendingRemoteOf(sid, 'approval'), ...pendingRemoteOf(sid, 'question')];
        for (const a of pend) {
          if (announcedApprovals.has(a.id)) continue;
          announcedApprovals.add(a.id);
          write(`event: approval\ndata: ${JSON.stringify({
            v: 2, type: 'approval', id: a.id, kind: a.kind, request: a.request ?? null, ts: Date.now(),
          })}\n\n`);
        }
        for (const id of [...announcedApprovals]) {
          if (pend.some((a) => a.id === id)) continue;
          announcedApprovals.delete(id);
          write(`event: approval_gone\ndata: ${JSON.stringify({ v: 2, type: 'approval_gone', id, ts: Date.now() })}\n\n`);
        }
      } catch { /* 审批网关不可用时忽略 */ }
    };
    pumpApprovals();
    const timer = setInterval(async () => {
      if (closed) { clearInterval(timer); return; }
      try {
        pumpApprovals();
        const h = await svc.open(sid, 'read');
        let events = [];
        try {
          const got = await h.read(offset);
          events = Array.isArray(got) ? got : (got?.events ?? []);
        } finally { try { await h.close?.(); } catch { /* 已关 */ } }
        if (!events.length) {
          touchConnection(sid, 'stream');
          write(`event: pulse\ndata: ${JSON.stringify({ v: 2, ts: Date.now(), seq: since })}\n\n`);
          return;
        }
        for (const ev of events) {
          if (eventAllowed(ev?.type, caps)) sendEv(ev);
        }
        offset += events.length;
      } catch { /* 单次读取失败不终止流 */ }
    }, 1500);

    const stop = () => {
      if (closed) return;
      closed = true;
      clearInterval(timer);
      STREAM_HUB.delete(sub);
      dropConnection(sid);
      try { res.end(); } catch { /* ignore */ }
    };
    req.on?.('close', stop);
    res.on?.('close', stop);
    setTimeout(stop, 10 * 60 * 1000);
  });


  // ---------------- 电脑端会话：列出 / 只读查看 ----------------
  // 目的：手机不只知道"自己那条会话"，还能看到电脑上正在聊/聊过的会话（只读），
  // 需要时再 attach 过去接着聊（写入口唯一性见 /session action=attach 的说明）。
  /**
   * 已归档的会话 id（$DSH_HOME/storages/workspace.json → global.archivedSessionIds）。
   * 手机端选会话只该看到"还在用"的，归档的不用同步。
   */
  let archivedCache = { at: 0, ids: new Set() };
  const archivedSessionIds = () => {
    try {
      const home = process.env.DSH_HOME || '';
      if (!home) return archivedCache.ids;
      const p = join(home, 'storages', 'workspace.json');
      const st = statSync(p);
      if (st.mtimeMs === archivedCache.at) return archivedCache.ids;
      const j = JSON.parse(readFileSync(p, 'utf8'));
      const list = j?.global?.archivedSessionIds;
      archivedCache = { at: st.mtimeMs, ids: new Set(Array.isArray(list) ? list.map(String) : []) };
      return archivedCache.ids;
    } catch {
      return archivedCache.ids;
    }
  };

  const persistence = () => { try { return ctx.get('sessionPersistence'); } catch { return undefined; } };
  /** 电脑上是否真有这条会话（直连协商/恢复时先验一下，避免拿一个死 id 去续写） */
  const sessionExists = async (id) => {
    if (!id) return false;
    try {
      const svc = persistence();
      const st = svc?.stat ? await svc.stat(id) : null;
      return Boolean(st);
    } catch { return false; }
  };

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
    const text = (partsOf(body).answer || '').trim();
    if (!text) return null;
    return { role: t === 'user/message' ? 'user' : 'assistant', text, time: Number(ev?.time) || 0 };
  };

  /**
   * 读一条已落盘会话的尾部：最近 N 条消息 + 最后活动时间。
   *
   * 只读尾部（stat 拿 eventCount 后从 offset 读），别把整条日志读进内存。
   * `lastTime` 用来排序：snapshot 里没有可靠的 mtime（location 常常缺），
   * 之前按 created_at 排，导致"很久以前创建、但一直在聊"的会话沉底 ——
   * 手机端看到的就是一堆旧会话。
   */
  async function readStoredTail(id, max = 20) {
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
      let lastTime = 0;
      for (const ev of events) {
        if (typeof ev?.time === 'number' && ev.time > lastTime) lastTime = ev.time;
        const m = messageOf(ev);
        if (m) out.push(m);
      }
      return { messages: out.slice(-Math.max(1, Math.min(200, max))), lastTime };
    } finally { try { await handle.close?.(); } catch { /* 已关 */ } }
  }

  async function readStoredMessages(id, max = 20) {
    return (await readStoredTail(id, max)).messages;
  }

  /** 读会话标题与头部信息（`session/title` 与 header；只读开头几十条，别为一条标题扫全量日志） */
    async function readStoredHead(id) {
    const svc = persistence();
    if (!svc || typeof svc.open !== 'function') return { title: '', firstUser: '', agentPreset: '' };
    const handle = await svc.open(id, 'read');
    try {
      // 会话头不在事件流里，是 handle.header（形如 {..., "agentPreset":"standard"}）。
      // 有 agentPreset 就说明这条会话是桌面端预设建的，headless one-shot CLI 拒绝续写。
      let agentPreset = typeof handle?.header?.agentPreset === 'string' ? handle.header.agentPreset : '';
      const got = await handle.read(0, 80);
      const events = Array.isArray(got) ? got : (got?.events ?? []);
      let title = '';
      let firstUser = '';
      for (const ev of events) {
        if (!agentPreset && ev?.type === 'session' && typeof ev.agentPreset === 'string') agentPreset = ev.agentPreset;
        if (ev?.type === 'session/title' && ev?.data && typeof ev.data.title === 'string') title = ev.data.title;
        if (!firstUser && ev?.type === 'user/message') {
          const kind = ev?.data?.source?.kind;
          if (typeof kind !== 'string' || kind === 'user') {
            const t = (partsOf(ev?.data).answer || '').trim();
            if (t) firstUser = t;
          }
        }
      }
      return { title: title.trim(), firstUser, agentPreset };
    } finally { try { await handle.close?.(); } catch { /* 已关 */ } }
  }

  /** 兼容旧调用点 */
  async function readStoredTitle(id) {
    return (await readStoredHead(id)).title;
  }

  /** 列出已落盘会话，按**最后活动时间**排序（不是创建时间） */
  async function listStoredSessions(limit = 20, opts = {}) {
    const svc = persistence();
    const idx = sessionFileIndex();                 // id → 文件 mtime，只 stat，不解码日志
    let rows = [];
    if (svc && typeof svc.list === 'function') {
      const all = await svc.list();
      rows = (Array.isArray(all) ? all : []).map((snap) => {
        const h = snap?.header ?? snap ?? {};
        return { session_id: String(h.id ?? ''), cwd: String(h.cwd ?? ''), created_at: Number(h.createdAt ?? 0) || null };
      }).filter((r) => r.session_id !== '');
    } else {
      rows = [...idx.keys()].map((id) => ({ session_id: id, cwd: '', created_at: null }));
    }
    const archivedSet = opts.includeArchived ? new Set() : archivedSessionIds();
    const desktopRows = rows.filter((r) => !r.session_id.startsWith('smartquiz-') && !archivedSet.has(r.session_id));
    // 排序用文件 mtime（≈ 最后一次写入），不再为每条读一遍日志尾；
    // 只有真正要返回的那几条才补标题/最后一句，避免会话多时一次请求要等十几秒。
    for (const row of desktopRows) row.updated_at = idx.get(row.session_id) ?? null;
    desktopRows.sort((a, b) => (b.updated_at ?? b.created_at ?? 0) - (a.updated_at ?? a.created_at ?? 0));
    const lim = Math.max(1, Math.min(200, limit));
    const picked = desktopRows.slice(0, lim);
    for (const row of picked) {
      try {
        const head = await readStoredHead(row.session_id);
        // 早期 ACP/headless 会话没有 session/title 事件：用第一条用户消息兜底，
        // 否则手机列表里会出现没有标题的空白项（实测踩到）。
        const title = head.title || (head.firstUser ? head.firstUser.replace(/\s+/g, ' ').slice(0, 24) : '');
        if (title) row.title = title;
        const tail = await readStoredTail(row.session_id, 3);
        if (tail.lastTime) row.updated_at = Math.max(row.updated_at ?? 0, tail.lastTime);
        const lastUser = [...tail.messages].reverse().find((m) => m.role === 'user');
        const snippet = (lastUser ?? tail.messages[tail.messages.length - 1])?.text ?? '';
        if (snippet) row.last = snippet.slice(0, 120);
        // 头 80 条里既没有标题、也没有真人消息（ACP/长会话常见）：再用"最后一句"兜底，
        // 保证手机列表里不会出现无标题的空白项。
        if (!row.title && row.last) row.title = row.last.replace(/\s+/g, ' ').slice(0, 24);
      } catch { /* 单条读失败不影响列表 */ }
    }
    // 空会话（没有标题、也没有任何真人消息）不进入列表：
    // 这类多是早期 ACP/CLI 留下的壳，手机选择框里只会挡住真正在用的会话。
    const nonEmpty = opts.includeEmpty ? picked : picked.filter((r) => (r.title && r.title.length > 0) || (r.last && r.last.length > 0));
    return nonEmpty;
  }

  /**
   * 轻量索引：只 stat 目录拿"会话 id → 文件 mtime"，**不解码日志**。
   * 手机端开着选择框时用它每几秒问一次"有没有变化"（一次 readdir + stat，毫秒级），
   * 有变化再拉完整列表 —— 比每次都读 120 条日志便宜两个数量级。
   */
  function sessionFileIndex() {
    const root = join(process.env.DSH_HOME || join(homedir(), '.dsh'), 'sessions');
    const out = new Map();
    let buckets = [];
    try { buckets = readdirSync(root, { withFileTypes: true }); } catch { return out; }
    for (const bucket of buckets) {
      if (!bucket.isDirectory()) continue;
      const bp = join(root, bucket.name);
      let dirs = [];
      try { dirs = readdirSync(bp, { withFileTypes: true }); } catch { continue; }
      for (const d of dirs) {
        if (!d.isDirectory()) continue;
        const dir = join(bp, d.name);
        let mtime = 0;
        try { mtime = statSync(join(dir, 'session.v4.jsonl.zstd')).mtimeMs; } catch {
          try { mtime = statSync(join(dir, 'session.jsonl.zstd')).mtimeMs; } catch {
            try { mtime = statSync(dir).mtimeMs; } catch { /* 跳过 */ }
          }
        }
        if (mtime) out.set(d.name, mtime);
      }
    }
    return out;
  }

  route('exact', '/sessions.json', async (req, res) => {
    if (!authOk(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    try {
      const q = new URL(req.url ?? '/', 'http://dsh.invalid');
      const limit = Number(q.searchParams.get('limit') ?? 20);
      const lim = Math.max(1, Math.min(200, Number.isFinite(limit) ? limit : 20));
      if (q.searchParams.get('light') === '1') {
        const idx = sessionFileIndex();
        const rows = [...idx.entries()]
          .filter(([id]) => !id.startsWith('smartquiz-'))
          .map(([id, mtime]) => ({ session_id: id, updated_at: mtime }))
          .sort((a, b) => b.updated_at - a.updated_at)
          .slice(0, lim);
        return sendJson(res, 200, { ok: true, light: true, count: rows.length, sessions: rows });
      }
      const sessions = await listStoredSessions(lim);
      sendJson(res, 200, { ok: true, count: sessions.length, sessions });
    } catch (e) {
      sendJson(res, 500, { ok: false, error: String(e?.message ?? e) });
    }
  });

  /**
   * GET /events?session_id=…&since=<seq>&timeout=<秒>&follow=1
   *
   * **原样转发 DSH 的事件流**，不在插件里做任何 UI 适配：
   *   data: {"type":"ready","session_id":…,"seq":N}
   *   data: {"type":"event","event":{type,seq,time,data}}   ← 逐条原样
   *   data: {"type":"end"}
   *
   * 手机端拿到后自己把事件映射成 ChatMessage / parts（正文、思考、工具卡片）。
   * 这样 DSH 侧新增事件类型时，插件不需要跟着改。
   */
  route('exact', '/events', async (req, res) => {
    if (!authOk(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    const q = new URL(req.url ?? '/', 'http://dsh.invalid');
    const sid = String(q.searchParams.get('session_id') ?? '').trim();
    if (!sid) return sendJson(res, 400, { ok: false, error: '需要 session_id' });
    // 游标优先级：?since= → SSE 标准头 Last-Event-ID → -1（首次）
    const headerId = req.headers?.['last-event-id'];
    const sinceRaw = q.searchParams.get('since');
    const sinceParam = Number(sinceRaw ?? (headerId ?? '-1'));
    const timeoutSec = Math.max(5, Math.min(1800, Number(q.searchParams.get('timeout') ?? '600') || 600));
    const follow = q.searchParams.get('follow') === '1';
    const snapshotThreshold = Math.max(50, Number(q.searchParams.get('snapshot_threshold') ?? '500') || 500);
    // 可选事件类型过滤（导入历史时只要 4 类，能把 9000+ 条压到一千多条）
    const typesFilter = new Set(
      String(q.searchParams.get('types') ?? '')
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean),
    );
    const caps = q.searchParams.get('caps') ?? '';
    const passType = (ev) => {
      const t = String(ev?.type ?? '');
      if (typesFilter.size > 0 && !typesFilter.has(t)) return false;
      // 能力协商：客户端声明不支持 approval 块时，审批事件不下发（legacy 不裁）
      return eventAllowed(t, caps);
    };
    res.writeHead(200, {
      'content-type': 'text/event-stream; charset=utf-8',
      'cache-control': 'no-cache, no-transform',
      connection: 'keep-alive',
      'x-accel-buffering': 'no',
    });
    const sse = (o) => { try { res.write('data: ' + JSON.stringify(o) + '\n\n'); } catch { /* 断连 */ } };
    // 实时桥订阅：token 增量 / 子代理活动 / 日程变化都经这里推给手机。
    // 事件类型按 caps 过滤（例如没声明 parts.stream 就不收 delta）。
    const sub = {
      sid,
      caps,
      send: (o) => {
        if (!o || !eventAllowed(o.type, caps)) return;
        sse(o);
      },
    };
    STREAM_HUB.add(sub);
    // 心跳 = pulse 事件（不再只是注释行）：客户端能据此测 RTT、发现"假在线"
    const ping = () => {
      touchConnection(sid, 'events');
      const live = ctx.sessions.get(sid);
      sse({ type: 'pulse', ts: Date.now(), seq: Number(live?.seq ?? 0) || 0 });
    };
    let closed = false;
    let viewTimer = null;
    req.on('close', () => {
      closed = true;
      if (viewTimer) clearInterval(viewTimer);
      STREAM_HUB.delete(sub);
      dropConnection(sid);
    });
    touchConnection(sid, 'events');
    const finish = () => {
      closed = true;
      if (viewTimer) clearInterval(viewTimer);
      sse({ type: 'end' });
      try { res.end(); } catch { /* 已断 */ }
    };
    // ★ 2026-11-12：连接即下发「权威状态 + 视图」，之后由服务端持续推送。
    //   客户端不再每 3/5 秒轮询 /v2/view + /v2/pulse：一次连接、服务端说话。
    const pushState = async () => {
      try {
        const live = ctx.sessions.get(sid);
        let dshSeq = Number(live?.seq ?? 0) || 0;
        if (!live) {
          try {
            const st = await persistence()?.stat?.(sid);
            dshSeq = Number(st?.eventCount ?? 0) || 0;
          } catch { /* 无持久化 */ }
        }
        const head = await (async () => {
          try { return await readStoredHead(sid); } catch { return null; }
        })();
        let code = '';
        try {
          for (const [k, v] of Object.entries(sessionBinding.dump())) {
            if (v === sid) { code = k; break; }
          }
        } catch { /* ignore */ }
        sse({
          type: 'state', data: {
            session_id: sid,
            exists: Boolean(live) || Boolean(head),
            live: Boolean(live),
            title: String(head?.title ?? ''),
            agent_preset: String(head?.agentPreset ?? ''),
            code: code === '@direct' ? '' : code,
            execution: executionInfo(sid),
            last_seq: dshSeq,
            acked_seq: Number((ACK_STORE.get(sid) ?? {}).acked_seq ?? 0) || 0,
            server_time: Date.now(),
          },
        });
      } catch { /* 状态失败不影响事件 */ }
    };
    const pushView = async () => {
      try { sse({ type: 'view', data: await buildView(sid, { withTimeline: false }) }); } catch { /* ignore */ }
    };
    pushState();
    pushView();
    viewTimer = setInterval(() => { if (!closed) pushView(); }, 5000);
    // since = -2 means "start from now": no history replay (first connect / no cursor)
    const since = sinceParam === -2 ? -2
      : (Number.isFinite(sinceParam) && sinceParam >= 0 ? sinceParam : -1);
    const live0 = ctx.sessions.get(sid);

    // ★ 非 live 会话：从持久化会话日志补发（"补发会话文件"）
    if (!live0) {
      const replay = await replaySessionLog(sid, since === -2 ? 1_000_000_000 : since, snapshotThreshold);
      if (!replay) {
        sse({ type: 'gone', session_id: sid });
        try { res.end(); } catch { /* 已断 */ }
        return;
      }
      if (replay.snapshot) {
        sse({ type: 'snapshot', session_id: sid, seq: replay.seq, turns: replay.turns, source: 'log' });
      } else {
        sse({ type: 'ready', session_id: sid, seq: replay.seq, source: 'log', since });
        for (const ev of replay.events) {
          if (passType(ev)) sse({ type: 'event', event: ev });
        }
      }
      /**
       * ★ 2026-11-12 修复：以前这里直接 finish()，会话只要没在桌面端打开（非 live），
       *   手机订阅完历史就"断流"，之后电脑写的回复再也推不过去 —— 用户看到的就是
       *   「连接流不稳定 / 消息没同步 / 没有补发」。
       *   现在 follow=1 时改为轮询持久化日志，增量推 + 心跳保活，直到手机断开或超时。
       */
      if (!follow) return finish();
      const svc2 = persistence();
      if (!svc2 || typeof svc2.open !== 'function') return finish();
      let sentCount = 0;
      try {
        const st0 = await svc2.stat?.(sid);
        sentCount = Number(st0?.eventCount ?? 0) || 0;
      } catch { sentCount = 0; }
      const pollDeadline = Date.now() + timeoutSec * 1000;
      let lastPing2 = Date.now();
      while (!closed && Date.now() < pollDeadline) {
        try {
          let total = 0;
          try { total = Number((await svc2.stat?.(sid))?.eventCount ?? 0) || 0; } catch { total = 0; }
          if (total > sentCount) {
            const h = await svc2.open(sid, 'read');
            let fresh = [];
            try {
              const got = await h.read(sentCount);
              fresh = Array.isArray(got) ? got : (got?.events ?? []);
            } finally { try { await h.close?.(); } catch { /* 已关 */ } }
            for (const ev of fresh) { if (passType(ev)) sse({ type: 'event', event: ev }); }
            sentCount += fresh.length;
          }
          if (Date.now() - lastPing2 > 10000) { lastPing2 = Date.now(); ping(); }
        } catch { /* 单次读失败继续 */ }
        // eslint-disable-next-line no-await-in-loop -- 顺序轮询
        await new Promise((r) => setTimeout(r, 500));
      }
      return finish();
    }

    const seqNow = Number(live0.seq ?? 0);
    // ★ 首次连接或缺口过大：给快照（历史批），随后继续增量
    if (since === -2) {
      // explicit "from now": ready only, no replay (first connect must not dump history)
      sse({ type: 'ready', session_id: sid, seq: seqNow, source: 'live', since });
      if (!follow) return finish();
    } else if (since < 0 || seqNow - since > snapshotThreshold) {
      sse({ type: 'snapshot', session_id: sid, seq: seqNow, turns: snapshotTurns(live0), source: 'live' });
      if (!follow) return finish();
    } else {
      sse({ type: 'ready', session_id: sid, seq: seqNow, source: 'live', since });
    }
    let cursor = since >= 0 ? Math.min(since, seqNow) : seqNow;
    const announced = new Set();
    const deadline = Date.now() + timeoutSec * 1000;
    let lastPing = Date.now();
    while (!closed && Date.now() < deadline) {
      const live = ctx.sessions.get(sid);
      if (!live) { sse({ type: 'gone', session_id: sid }); break; }
      const seq = Number(live.seq ?? 0);
      for (; cursor < seq; cursor += 1) {
        const ev = live.eventAt?.(cursor);
        if (ev && passType(ev)) sse({ type: 'event', event: ev });
      }
      for (const a of [...pendingRemoteOf(sid, 'approval'), ...pendingRemoteOf(sid, 'question')]) {
        if (announced.has(a.id)) continue;
        announced.add(a.id);
        sse({ type: a.kind === 'question' ? 'question' : 'approval', id: a.id, request: a.request });
      }
      for (const id of [...announced]) {
        const still = [...pendingRemoteOf(sid, 'approval'), ...pendingRemoteOf(sid, 'question')]
          .some((a) => a.id === id);
        if (still) continue;
        announced.delete(id);
        sse({ type: 'approval_gone', id });
      }
      if (!follow) break;
      if (Date.now() - lastPing > 10000) { lastPing = Date.now(); ping(); }
      // eslint-disable-next-line no-await-in-loop -- 顺序轮询
      await new Promise((r) => setTimeout(r, 150));
    }
    return finish();
  });

  /** 会话的"历史批"：最近 20 条消息（角色 + 文本），首次连接 / 缺口过大时用 */
  const snapshotTurns = (session) => {
    const turns = [];
    try {
      const msgs = session?.deriveMessages?.() ?? [];
      for (const m of msgs.slice(-20)) {
        const role = m?.role === 'user' ? 'user' : 'assistant';
        const text = typeof m?.content === 'string'
          ? m.content
          : Array.isArray(m?.content)
            ? m.content.map((p) => (p && typeof p.text === 'string' ? p.text : '')).join('')
            : '';
        if (text) turns.push({ role, text: String(text).slice(0, 4000) });
      }
    } catch { /* 拿不到就空快照 */ }
    return turns;
  };

  /**
   * 从持久化会话日志读事件（"补发会话文件"）。
   *
   * DSH 把会话写在 $DSH_HOME/sessions/--<cwd>--/<id>/session.v4.jsonl.zstd：首帧是头部、
   * 之后是事件帧。按 zstd 魔数切帧解压、逐行 JSON、过滤 seq > since。
   * 返回 null 表示这个环境读不了（老 Node 没有 zstd），调用方回 gone。
   */
  const replaySessionLog = async (sessionId, since, threshold) => {
    let dec;
    try {
      const zlib = await import('node:zlib');
      dec = zlib.zstdDecompressSync ?? zlib.default?.zstdDecompressSync;
    } catch { /* 老 Node 没有 zstd */ }
    const home = process.env.DSH_HOME || '';
    if (!dec || !home) return null;
    const root = join(home, 'sessions');
    let file = '';
    try {
      for (const dir of readdirSync(root)) {
        const cand = join(root, dir, sessionId, 'session.v4.jsonl.zstd');
        if (existsSync(cand)) { file = cand; break; }
      }
    } catch { return null; }
    if (!file) return null;
    let text = '';
    try {
      const buf = readFileSync(file);
      const magic = [0x28, 0xb5, 0x2f, 0xfd];
      const starts = [];
      for (let i = 0; i + 3 < buf.length; i += 1) {
        if (buf[i] === magic[0] && buf[i + 1] === magic[1] && buf[i + 2] === magic[2] && buf[i + 3] === magic[3]) starts.push(i);
      }
      for (let k = 0; k < starts.length; k += 1) {
        const seg = buf.subarray(starts[k], k + 1 < starts.length ? starts[k + 1] : buf.length);
        try { text += dec(seg).toString('utf8'); } catch { /* 跳过坏帧 */ }
      }
    } catch { return null; }
    const events = [];
    let seq = 0;
    for (const line of text.split('\n')) {
      if (!line.trim()) continue;
      let ev;
      try { ev = JSON.parse(line); } catch { continue; }
      if (typeof ev?.seq === 'number') {
        seq = Math.max(seq, ev.seq);
        if (ev.seq > since) events.push(ev);
      }
    }
    if (since < 0 || events.length > threshold) {
      const turns = [];
      for (const ev of events.slice(-120)) {
        if (ev.type === 'user/message') {
          const t = Array.isArray(ev.data?.content) ? ev.data.content.map((p) => p?.text ?? '').join('') : '';
          if (t) turns.push({ role: 'user', text: t.slice(0, 2000) });
        } else if (ev.type === 'assistant/message') {
          const t = (ev.data?.message?.content ?? [])
            .filter((p) => p && p.type !== 'reasoning')
            .map((p) => p.text ?? '').join('');
          if (t) turns.push({ role: 'assistant', text: t.slice(0, 4000) });
        }
      }
      return { snapshot: true, seq, turns: turns.slice(-20) };
    }
    return { snapshot: false, seq, events };
  };

  /**
   * 审批中继用的"网关桥"。
   *
   * DSH 的审批是 typertGateway 把 approval/request 转发给所有 owner client、谁先应答谁
   * settle（先答先赢）。插件跑在宿主里，直接读它的 pendingRemoteEvents 并调用
   * settleRemoteEvent 就能"替手机点一下"，桌面端对应弹窗会因 settle 而收掉。
   *
   * 这些都是**私有字段**：用特性探测包住，DSH 升级后拿不到就自动退回"只提醒"，
   * 不影响任何既有功能。
   */
  const approvalGateway = () => {
    let gw;
    try { gw = ctx.typertGateway; } catch { gw = undefined; }
    if (!gw) {
      try { gw = ctx.get?.('typertGateway'); } catch { gw = undefined; }
    }
    try {
      if (!gw || typeof gw.settleRemoteEvent !== 'function' || !(gw.pendingRemoteEvents instanceof Map)) return null;
      return gw;
    } catch { return null; }
  };

  /**
   * 某条会话当前挂着的"远端待答事件"（审批 / agent 反问）。
   * 返回值里的 `kind` 决定手机端弹哪种框。
   */
  const pendingRemoteOf = (sessionId, kind) => {
    const gw = approvalGateway();
    if (!gw) return [];
    const eventName = kind === 'question' ? 'user-questions/request' : 'approval/request';
    const out = [];
    try {
      for (const pending of gw.pendingRemoteEvents.values()) {
        const f = pending?.frame;
        if (!f || f.event !== eventName) continue;
        if (sessionId && String(f.agentId) !== String(sessionId)) continue;
        out.push({ id: String(pending.id), kind, request: f.request ?? null, agent_id: String(f.agentId ?? '') });
      }
    } catch { /* 结构变了就当没有 */ }
    return out;
  };

  /** 兼容旧调用点：只要审批 */
  const pendingApprovalsOf = (sessionId) => pendingRemoteOf(sessionId, 'approval');

  /** 手机应答：先答先赢；失败返回错误说明 */
  const answerApproval = (approvalId, allow) => {
    const gw = approvalGateway();
    if (!gw) return { ok: false, error: 'gateway_unavailable', hint: '这个 DSH 版本的网关不暴露审批中继（插件会自动退回只提醒）' };
    try {
      for (const pending of gw.pendingRemoteEvents.values()) {
        if (String(pending.id) !== String(approvalId)) continue;
        gw.settleRemoteEvent(pending, { kind: 'result', value: allow ? 'allowed' : 'rejected' });
        return { ok: true, decision: allow ? 'allowed' : 'rejected' };
      }
      return { ok: false, error: 'approval_not_found', hint: '这条审批已经结束了（可能刚被电脑端点了，或已超时）' };
    } catch (e) {
      return { ok: false, error: String(e?.message ?? e) };
    }
  };

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
    // DSH Web UI 面板是同源 fetch，不带 Bearer —— 本机请求放行只读状态（不含令牌）；远程仍要令牌。
    if (!authOk(req) && !isLocal(req)) return sendJson(res, 401, { ok: false, error: 'unauthorized' });
    sendJson(res, 200, statusBody());
  });

  /**
   * 宿主进程内跑一轮（不启 CLI）。
   *
   * ★ 为什么需要：headless 的 one-shot CLI 续写会话有两个硬限制：
   *   ① 会话头部带 `agentPreset`（桌面端建的都是 "standard"）时直接拒绝：
   *      "runs under agent preset standard, which the one-shot runner does not compose"；
   *   ② 桌面端正打开着那条会话时，子进程拿不到写权（单写者）。
   * 而插件与桌面端在**同一个宿主进程**里：ctx.agents.get/resume 拿到的就是写者本身。
   * 所以"用户在电脑上正看这条对话"时改走这条路 —— 消息会直接出现在电脑那条对话里
   * （这正是用户想要的），手机照常拿到回复。
   */
  const getLiveAgent = (target) => {
    try { return ctx.agents?.get?.(target) ?? null; } catch { return null; }
  };

  const sessionAgentPreset = async (id) => {
    try { return (await readStoredHead(id)).agentPreset || ''; } catch { return ''; }
  };

  const runInProcessTurn = async (target, text, turnOpts) => {
    const deadline = Number.isFinite(turnOpts?.deadline) ? turnOpts.deadline : Date.now() + 120000;
    const signal = turnOpts?.signal;
    let agent = getLiveAgent(target);
    let resumed = false;
    let resumeError = '';
    if (!agent) {
      try {
        let h = resumedHandles.get(target);
        if (!h) { h = await ctx.agents.resume({ resumeSessionId: target }); if (h) rememberWarm(target, h); }
        agent = h?.agent ?? h ?? null;
        resumed = Boolean(agent);
      } catch (e) { resumeError = String(e?.message ?? e).slice(0, 200); }
    }
    if (!agent || typeof agent.followup !== 'function') return { ok: false, resumeError };
    if (signal?.aborted) return { ok: false, aborted: true };
    const live = ctx.sessions.get(target);
    if (!live) return { ok: false, resumeError: resumeError || '会话不在宿主内存里' };
    const seqBefore = Number(live.seq ?? 0);
    const baseReply = lastAssistant(live) || '';
    ORIGIN.record(target, text);   // 插件代手机注入
    try {
      agent.followup({ id: randomBytes(16).toString('hex'), role: 'user', content: [{ type: 'text', text }], source: { kind: 'user' } });
    } catch (e) {
      return { ok: false, resumeError: String(e?.message ?? e).slice(0, 200) };
    }
    let reply = '';
    let turnError = '';
    let timedOut = true;
    while (Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 200));
      if (signal?.aborted) return { ok: false, aborted: true, via: resumed ? 'in-process:resume' : 'in-process:live' };
      const cur = ctx.sessions.get(target);
      if (!cur) break;
      const seq = Number(cur.seq ?? 0);
      if (seq > seqBefore) {
        const t = assistantTextSince(cur, seqBefore);
        if (t && t !== baseReply) { reply = t; timedOut = false; break; }
        for (let i = seq - 1; i >= Math.max(0, seq - 40); i -= 1) {
          const ev = cur.eventAt?.(i);
          if (ev?.type === 'turn/end' && ev?.data?.reason?.kind === 'error') {
            turnError = String(ev.data.reason.error?.message ?? ev.data.reason.error ?? '').slice(0, 300);
            break;
          }
        }
        if (turnError) break;
      }
    }
    const cur = ctx.sessions.get(target);
    const parts = assistantPartsSince(cur, seqBefore);
    if (!reply) reply = (parts.answer || parts.thinking || '').trim();
    return {
      ok: Boolean(reply) && !turnError,
      reply, timedOut, turnError, resumeError,
      via: resumed ? 'in-process:resume' : 'in-process:live',
    };
  };

  /** 一轮对话（非流式 / SSE 流式）
   *  runOpts.signal   —— 手机连接断开/超时 → 取消 CLI 子进程
   *  runOpts.deadline —— 手机给的时间预算（ms 时间戳），CLI 不能跑过它 */
  const runPrompt = async (req, res, sid, text, wantsStream, runOpts) => {
    const opts = runOpts ?? {};
    // CLI 模式下不需要（也不该）在宿主里建 agent：任务由 `dsh --profile headless`
    // 子进程执行。之前无论哪种模式都先 create 一次，attach 到电脑端已有会话后
    // 会撞上 "session already exists"（那条 id 在宿主里已经存在）。
    const cliMode = cfg.cliMode !== false;
    let handle = handles.get(sid);
    if (!cliMode && handle === undefined) {
      if (!ctx.agents || !ctx.sessions) throw new Error('agents/sessions 服务不可用');
      handle = await ctx.agents.create(await createInput(sid));
      handles.set(sid, handle);
    }
    if (!wantsStream) {
      const target = String(opts.targetSessionId || '').trim();
      let r;
      if (cliMode && target) {
        /**
         * 什么时候不能走 CLI：
         *   · 会话头部带 agentPreset（桌面端建的都是 "standard"）→ CLI 直接拒绝续写；
         *   · 桌面端正打开着这条会话（宿主里有 live agent）→ CLI 拿不到写权。
         * 这两种情况都改由宿主进程内接管，用户正在看的那条对话会实时收到消息。
         */
        const liveAgent = getLiveAgent(target) || warmAgentFor(target);
        const preset = liveAgent ? '' : await sessionAgentPreset(target);
        if (liveAgent || preset) {
          const ir = await runInProcessTurn(target, text, { signal: opts.signal, deadline: opts.deadline });
          if (ir?.reply) {
            r = { reply: ir.reply, via: ir.via, dshSession: target, debug: { via: ir.via, in_process: true, preset: preset || '(live)' } };
          } else if (ir?.aborted) {
            r = { reply: '', via: 'cli-aborted', error: '手机端已取消/超时，电脑侧已停止等待', dshSession: target };
          } else {
            r = { reply: '', via: 'in-process-failed', dshSession: target,
              error: '电脑端这条对话正在使用中，进程内接管失败：' + (ir?.resumeError || ir?.turnError || '未知原因') };
          }
        }
      }
      if (!r) {
        r = await runTurn(ctx, handle, sid, text, cfg, { signal: opts.signal, deadline: opts.deadline,
          targetSessionId: opts.targetSessionId, sessionCode: opts.sessionCode });
        // CLI 续不上（预设不兼容 / 被别的进程占着）→ 再试一次宿主内接管
        if (!r.reply && cliMode && target && !r.aborted
            && /agent preset|one-shot runner|write owner|already owned|compose/i.test(String(r.error || ''))) {
          const ir = await runInProcessTurn(target, text, { signal: opts.signal, deadline: opts.deadline });
          if (ir?.reply) {
            r = { reply: ir.reply, via: ir.via, dshSession: target, debug: { via: ir.via, fallback: 'after-cli' } };
          } else if (ir?.turnError) {
            r = { ...r, error: '电脑端这一轮报错：' + ir.turnError };
          }
        }
      }
      // 热 agent 预热：本轮仍走 CLI，但把这条会话在宿主内 resume 好，
      // 下一轮就能直接宿主内执行（省掉 2~8s 冷启动）。失败不影响本轮。
      if (cfg.warmReuse === true && r?.dshSession && !(r.debug && r.debug.in_process) && !resumedHandles.has(r.dshSession)) {
        const warmSid = r.dshSession;
        Promise.resolve().then(async () => {
          try {
            const h = await ctx.agents.resume({ resumeSessionId: warmSid });
            if (h) rememberWarm(warmSid, h);
          } catch { /* 预热失败：下一轮继续走 CLI */ }
        });
      }
      // 手机已经断开（超时/取消）：不再往死连接上写，也不要记进本地历史
      if (opts.signal?.aborted) { try { res.end(); } catch { /* 已断开 */ } return; }
      const dbg = { via: r.via, model: modelInfo(), selection: lastSelection, selectionInstaller, preset: lastPreset, ...r.debug };
      // 失败要说出来：以前 CLI 起不来时回 ok:true + 空 reply，手机端只显示「电脑没回话」
      if (!r.reply && r.error) {
        return sendJson(res, 200, { ok: false, action: 'prompt', session_id: sid, dsh_session_id: r.dshSession || '', reply: '', error: r.error, debug: dbg });
      }
      promptCount += 1;
      const reply = r.reply;
      sendJson(res, 200, { ok: true, action: 'prompt', session_id: sid, dsh_session_id: r.dshSession || '', reply, duration_ms: dbg.cli_ms ?? null, turn: 1, execution: executionInfo(target), debug: dbg });
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
            const t = (partsOf(ev?.data).answer || '');
            if (t && t !== lastText) { lastText = t; sse({ type: 'text', text: t }); }
          }
        }
        lastSeq = seq;
      } catch (e) { sse({ type: 'warn', error: String(e?.message ?? e) }); }
    }, 150);
    try {
      const rr = await runTurn(ctx, handle, sid, text, { ...cfg, __onData: (s) => sse({ type: 'text', text: s }) },
        { signal: opts.signal, deadline: opts.deadline, targetSessionId: opts.targetSessionId, sessionCode: opts.sessionCode });

      promptCount += 1;
      const s = ctx.sessions.get(sid);
      let reply = rr.reply || lastText;
      if (s) for (const m of (s.deriveMessages?.() ?? [])) if (m?.role === 'assistant' && textOf(m)) reply = textOf(m);
      if (reply.length > cfg.maxReplyChars) reply = reply.slice(0, cfg.maxReplyChars);
      if (!reply && rr.error) sse({ type: 'error', action: 'prompt', session_id: sid, error: rr.error });
      else sse({ type: 'done', action: 'prompt', session_id: sid, dsh_session_id: rr.dshSession || '', reply, debug: { via: rr.via, ...rr.debug } });
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
      if (action === 'new_session') {
        // 手机点「新建对话」：在电脑端真正建一条会话，并绑定为跟随会话（@code:follow）。
        // 和 action=start 的区别：start 只发手机侧句柄、不建电脑会话；new_session 会建。
        if (!ctx.agents || !ctx.sessions) {
          return sendJson(res, 503, { ok: false, action, error: 'agents/sessions 服务不可用' });
        }
        const sid = `session-${randomBytes(16).toString('hex')}`;
        let handle;
        try {
          handle = await ctx.agents.create(await createInput(sid));
        } catch (e) {
          return sendJson(res, 500, { ok: false, action, error: 'create_failed: ' + String(e?.message ?? e) });
        }
        // 保留句柄：inject 走 ctx.agents.get 时能直接命中这条新会话（不再冷启动/resume）
        handles.set(sid, handle);
        rememberWarm(sid, handle);
        sessionBinding.set('follow', sid);
        let title = '';
        try { title = String(ctx.sessions.get(sid)?.header?.title ?? ''); } catch { /* 还没标题 */ }
        return sendJson(res, 200, { ok: true, action: 'new_session', session_id: sid, title });
      }
      if (action === 'start') {
        const sid = `smartquiz-${randomBytes(6).toString('hex')}`;
        // ★ CLI 模式下**不要**在宿主里建 agent：那会落一条空的 `smartquiz-*` 会话，
        //   正是电脑侧栏里"一堆会话"的来源。真正的电脑会话由 CLI 建，建好后通过
        //   `dsh_session_id` 回给手机（两端认同同一个 id）。
        if (cfg.cliMode === false) {
          if (!ctx.agents || !ctx.sessions) throw new Error('agents/sessions 服务不可用');
          const handle = await ctx.agents.create(await createInput(sid));
          handles.set(sid, handle);
        }

        return sendJson(res, 200, { ok: true, action: 'start', session_id: sid, dsh_session_id: '' });
      }
      if (action === 'get_status') return sendJson(res, 200, { ...statusBody(), action: 'get_status' });
      if (action === 'set_config') return sendJson(res, 200, { ok: true, action: 'set_config', note: '配置由手机侧持有' });

      const sid = typeof body.session_id === 'string' && body.session_id
        ? body.session_id : `smartquiz-${randomBytes(6).toString('hex')}`;

      // ---- 直连会话协商：手机问"我的直连对话是哪条电脑会话"，电脑说了算 ----
      if (action === 'direct_state' || action === 'direct_bind') {
        const code = String(body.session_code ?? '').trim();
        if (action === 'direct_bind') {
          const target = String(body.target_session_id ?? body.direct_session_id ?? '').trim();
          if (!target) {
            sessionBinding.set(code, '');
            return sendJson(res, 200, { ok: true, action: 'direct_bind', session_code: code, direct_session_id: '' });
          }
          if (!(await sessionExists(target))) {
            return sendJson(res, 404, { ok: false, action: 'direct_bind', session_code: code,
              error: '电脑上没有这条会话：' + target });
          }
          sessionBinding.set(code, target);
          let title = ''; try { title = await readStoredTitle(target); } catch { /* 忽略 */ }
          return sendJson(res, 200, { ok: true, action: 'direct_bind', session_code: code,
            direct_session_id: target, title, exists: true, agent_preset: await sessionAgentPreset(target) });
        }
        let id = sessionBinding.get(code);
        let exists = id ? await sessionExists(id) : false;
        // 手机记得 id 但电脑侧绑定丢了（清过 .sessions.json / 换过插件包）：先认回来，别新建
        const known = String(body.target_session_id ?? '').trim();
        if ((!id || !exists) && known && known !== id && await sessionExists(known)) {
          id = known;
          exists = true;
          sessionBinding.set(code, known);
        }
        let created = false;
        // ★ 不在 direct_state 里凭空建会话：DSH 的会话必须带工作目录才可被 CLI 续写，
        //   宿主 API 建出来的会 "recorded no working directory"（实测）。所以第一次真正
        //   发消息时由 CLI 建（带 cwd），插件把 dsh_session_id 回给手机 —— 电脑端仍然是
        //   唯一决定 id 的一方，手机只保存/回传。
        let title = '';
        if (exists && id) { try { title = await readStoredTitle(id); } catch { title = ''; } }
        return sendJson(res, 200, { ok: true, action: 'direct_state', session_code: code,
          direct_session_id: id, title, exists,
          created, agent_preset: id ? await sessionAgentPreset(id) : '' });
      }

      if (action === 'history') {
        const max = Number.isFinite(body.max) ? Math.max(1, Math.min(200, body.max)) : 10;
        const s = ctx.sessions.get(sid);
        let items = [];
        // 只认两个来源：宿主内存里的活会话，或落盘日志。不再维护第三份 local 缓存。
        if (s) {
          items = (s.deriveMessages?.() ?? [])
            .map((m) => ({ role: m?.role ?? 'assistant', text: textOf(m) }))
            // 只回真人对话：deriveMessages 里还有 tool 消息，混进 history 会让手机看到"命令输出"当聊天
            .filter((x) => (x.role === 'user' || x.role === 'assistant') && x.text);
        } else {
          try { items = await readStoredMessages(sid, max); } catch { items = []; }
        }
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
        handles.delete(sid);        // 旧句柄/旧历史作废
        return sendJson(res, 200, {
          ok: true, action: 'attach', session_id: sid, attached_to: target, count: history.length, history,
          mode: 'copy-on-write',
          note: '已接过来：下一条消息会带上这段上下文在新会话里继续（电脑端那条不会被改动）',
        });
      }
      if (action === 'sync') {
        // 真·双端同步：把手机这句话注入桌面端**正在运行**的那条会话。
        // 写者始终是宿主进程本身（ctx.agents.get 拿到的就是 GUI 在用的 agent），
        // 因此不违反单写者；桌面上会实时看到这句用户消息。
        const target = String(body.target_session_id ?? '').trim();
        const text = (typeof body.text === 'string' && body.text ? body.text : (typeof body.prompt === 'string' && body.prompt ? body.prompt : (typeof body.task === 'string' ? body.task : ''))).trim();
        if (!target || !text) return sendJson(res, 400, { ok: false, action, error: '需要 target_session_id 和 text' });
        ORIGIN.record(target, text);   // 同步模式：这条也是手机发的，别和电脑键盘输入混了
        // 桌面端空闲时，会话只是"打开着"但**没有 live agent**（agent 随 turn 结束被销毁），
        // 所以不能只 get：取不到就用 resume 按持久化日志重建一个，然后照常 followup。
        let agent = (() => { try { return ctx.agents?.get?.(target); } catch { return undefined; } })();
        let resumed = false;
        let resumeError = '';
        if (!agent) {
          try {
            let h = resumedHandles.get(target);
            if (!h) { h = await ctx.agents.resume({ resumeSessionId: target }); if (h) rememberWarm(target, h); }
            agent = h?.agent ?? h ?? null;
            resumed = Boolean(agent);
          } catch (e) {
            resumeError = String(e?.message ?? e).slice(0, 300);
          }
        }
        if (!agent) {
          // ★ 兼容兜底：既没有 live agent、又 resume 不起来（最常见是被别的进程占着写权，
          //   比如桌面端刚打开这条会话）。此时不直接报错，而是把最近记录当上下文、
          //   用 CLI 在新会话里跑一遍 —— 手机至少能拿到回复，行为退化成 attach。
          try {
            const hist = await readStoredMessages(target, 20);
            const preamble = carryPreamble({ target, history: hist }, text);
            const cands = resolveDshCli(cfg);
            let lastErr = '';
            for (const cli of cands) {
              // eslint-disable-next-line no-await-in-loop -- 串行尝试，失败才换下一个
              const r = await runCliTask(cli, cfg.cliProfile || 'headless', preamble, cfg.cliTimeoutMs || 300000, null, '', cfg.workingDir, {});
              if (r.ok && r.output) {
                promptCount += 1;
                return sendJson(res, 200, {
                  ok: true, action: 'sync', session_id: target, reply: r.output, execution: executionInfo(target),
                  fallback: 'carry', resume_error: resumeError || undefined,
                  hint: '这条会话被占用，已用"复制上下文另起一条"的方式执行（电脑上那条不会变）',
                  debug: { via: 'cli-carry', cli_ms: r.ms },
                });
              }
              lastErr = cli + ' → code=' + r.code + ' ' + String(r.output).slice(0, 120);
            }
            return sendJson(res, 502, {
              ok: false, action: 'sync', error: '兜底执行也失败：' + lastErr,
              resume_error: resumeError || undefined,
            });
          } catch (e) {
            return sendJson(res, 500, { ok: false, action: 'sync', error: String(e?.message ?? e) });
          }
        }
        const timeoutMs = Number.isFinite(body.timeout) ? Math.max(5, Math.min(600, body.timeout)) * 1000 : 120000;
        const startedAt = Date.now();
        try {
          const before = ctx.sessions.get(target);
          const seqBefore = Number(before?.seq ?? 0);
          const baseReply = lastAssistant(before) || '';
          // ★ 流式分支：手机带 stream:true 时，边跑边推，而不是等整轮结束再一次性回。
          //   事件：start / thinking(全文) / text(全文) / done / error。
          //   推“全文”而不是 delta —— 手机端直接替换内容即可，丢一两个事件也不会错位。
          if (body.stream === true) {
            res.writeHead(200, {
              'content-type': 'text/event-stream; charset=utf-8',
              'cache-control': 'no-cache, no-transform',
              connection: 'keep-alive',
              'x-accel-buffering': 'no',
            });
            const sse = (o) => { try { res.write('data: ' + JSON.stringify(o) + '\n\n'); } catch { /* 断连 */ } };
            sse({ type: 'start', session_id: target, resumed });
            let lastAnswer = '';
            let lastThinking = '';
            let finished = false;
            let turnErrorText = '';
            try {
              agent.followup({
                id: randomBytes(16).toString('hex'),
                role: 'user',
                content: [{ type: 'text', text }],
                source: { kind: 'user' },
              });
              while (Date.now() - startedAt < timeoutMs) {
                await new Promise((r) => setTimeout(r, 150));
                const live = ctx.sessions.get(target);
                if (!live) break;
                const parts = assistantPartsSince(live, seqBefore);
                if (parts.thinking && parts.thinking !== lastThinking) {
                  lastThinking = parts.thinking;
                  sse({ type: 'thinking', text: lastThinking });
                }
                if (parts.answer && parts.answer !== lastAnswer) {
                  lastAnswer = parts.answer;
                  sse({ type: 'text', text: lastAnswer });
                }
                const seq = Number(live?.seq ?? 0);
                for (let i = seq - 1; i >= Math.max(0, seq - 30); i -= 1) {
                  const ev = live.eventAt?.(i);
                  if (ev?.type !== 'turn/end') continue;
                  if (ev?.data?.reason?.kind === 'error') {
                    turnErrorText = String(ev.data.reason.error?.message ?? ev.data.reason.error ?? '').slice(0, 300);
                  }
                  finished = true;
                  break;
                }
                if (finished) break;
              }
              promptCount += 1;
              if (turnErrorText) sse({ type: 'error', error: turnErrorText });
              else sse({ type: 'done', reply: lastAnswer, thinking: lastThinking || undefined, timed_out: !finished });
            } catch (e) {
              sse({ type: 'error', error: String(e?.message ?? e) });
            }
            try { res.end(); } catch { /* 已断 */ }
            return;
          }
          agent.followup({
            id: randomBytes(16).toString('hex'),
            role: 'user',
            content: [{ type: 'text', text }],
            source: { kind: 'user' },
          });
          // ★ 不能只等 whenIdle：刚投递、还没起 turn 的那一瞬间 agent 也是 idle 的，
          //   于是会立刻返回**上一条** assistant 内容 —— 手机端看到的就是"答非所问/没回复"。
          //   改成盯会话 seq：seq 涨了、且最后一条 assistant 变了，才算这一轮真有回复。
          let reply = '';
          let timedOut = true;
          let turnError = '';
          while (Date.now() - startedAt < timeoutMs) {
            await new Promise((r) => setTimeout(r, 250));
            const live = ctx.sessions.get(target);
            if (!live) break;
            const seq = Number(live?.seq ?? 0);
            if (seq > seqBefore) {
              const t = assistantTextSince(live, seqBefore);
              if (t && t !== baseReply) { reply = t; timedOut = false; break; }
              for (let i = seq - 1; i >= Math.max(0, seq - 40); i -= 1) {
                const ev = live.eventAt?.(i);
                if (ev?.type === 'turn/end' && ev?.data?.reason?.kind === 'error') {
                  turnError = String(ev.data.reason.error?.message ?? ev.data.reason.error ?? '').slice(0, 300);
                  break;
                }
              }
              if (turnError) break;
            }
          }
          promptCount += 1;
          const live = ctx.sessions.get(target);
          // 正文为空但确实思考过时，至少把思考内容兜回来，别让手机显示空白
          const finalParts = assistantPartsSince(live, seqBefore);
          if (!reply) reply = (finalParts.answer || finalParts.thinking || '').trim();
          const thinking = finalParts.thinking ? finalParts.thinking.trim().slice(0, 4000) : '';
          return sendJson(res, 200, {
            ok: !turnError, action: 'sync', session_id: target, reply, thinking: thinking || undefined, execution: executionInfo(target),
            waited_ms: Date.now() - startedAt, timed_out: timedOut,
            error: turnError || undefined,
            hint: turnError ? '电脑端这一轮报错：' + turnError
              : timedOut ? '这一轮还没跑完（可能在等审批或模型很慢），已先返回现有内容' : undefined,
            debug: { via: 'in-process', resumed, seq: Number(live?.seq ?? 0) || null, seq_before: seqBefore || null },
          });
        } catch (e) {
          return sendJson(res, 500, { ok: false, action: 'sync', error: String(e?.message ?? e) });
        }
      }
      if (action === 'inject') {
        // 只把消息投进会话并立刻返回（不等回复）。手机随后用 /events 订阅事件流，
        // 自己渲染思考/正文/工具 —— 这是"原样转发 + 手机端转换"的主路径。
        const target = String(body.target_session_id ?? '').trim();
        const text = (typeof body.text === 'string' && body.text ? body.text : (typeof body.task === 'string' ? body.task : '')).trim();
        if (!target || !text) return sendJson(res, 400, { ok: false, action, error: '需要 target_session_id 和 text' });
        let agent = (() => { try { return ctx.agents?.get?.(target); } catch { return undefined; } })();
        let resumed = false;
        if (!agent) {
          try {
            let h = resumedHandles.get(target);
            if (!h) { h = await ctx.agents.resume({ resumeSessionId: target }); if (h) rememberWarm(target, h); }
            agent = h?.agent ?? h ?? null;
            resumed = Boolean(agent);
          } catch (e) {
            return sendJson(res, 409, { ok: false, action, error: 'cannot_resume', resume_error: String(e?.message ?? e) });
          }
        }
        if (!agent) return sendJson(res, 409, { ok: false, action, error: 'live_session_required' });
        const seqBefore = Number(ctx.sessions.get(target)?.seq ?? 0);
        ORIGIN.record(target, text);   // inject 也是手机发起
        agent.followup({
          id: randomBytes(16).toString('hex'),
          role: 'user',
          content: [{ type: 'text', text }],
          source: { kind: 'user' },
        });
        promptCount += 1;
        return sendJson(res, 200, { ok: true, action: 'inject', session_id: target, resumed, seq_before: seqBefore });
      }
      if (action === 'approval_state') {
        const target = String(body.target_session_id ?? '').trim();
        return sendJson(res, 200, {
          ok: true, action: 'approval_state',
          gateway: Boolean(approvalGateway()),
          pending: [...pendingRemoteOf(target || null, 'approval'), ...pendingRemoteOf(target || null, 'question')],
        });
      }
      if (action === 'approval') {
        const id = String(body.approval_id ?? '').trim();
        if (!id) return sendJson(res, 400, { ok: false, action, error: '需要 approval_id' });
        const decision = String(body.decision ?? '').trim().toLowerCase();
        if (decision !== 'allow' && decision !== 'deny') {
          return sendJson(res, 400, { ok: false, action, error: "decision 只能是 allow / deny" });
        }
        const r = answerApproval(id, decision === 'allow');
        return sendJson(res, r.ok ? 200 : 409, { ...r, action: 'approval', approval_id: id });
      }
      if (action === 'sync_state') {
        const target = String(body.target_session_id ?? '').trim();
        let live = false;
        try { live = Boolean(target) && Boolean(ctx.agents?.get?.(target)); } catch { live = false; }
        let resumable = false;
        try {
          const svc = persistence();
          const st = target && svc?.stat ? await svc.stat(target) : null;
          resumable = Boolean(st);
        } catch { resumable = false; }
        return sendJson(res, 200, { ok: true, action: 'sync_state', session_id: target, live, resumable });
      }
      if (action !== 'prompt' && action !== 'run' && action !== 'send') return sendJson(res, 400, { ok: false, error: `unknown action: ${action}` });

      const text = (typeof body.text === 'string' && body.text ? body.text : (typeof body.prompt === 'string' && body.prompt ? body.prompt : (typeof body.task === 'string' ? body.task : ''))).trim();
      if (!text) return sendJson(res, 400, { ok: false, action: 'prompt', error: 'text 不能为空' });
      // 幂等：手机重试同一条（client_msg_id 相同）不再执行第二次
      const cmid = String(body.client_msg_id ?? '').trim();
      if (cmid && clientMsgDuplicated(sid, cmid)) {
        return sendJson(res, 200, {
          ok: true, action: 'prompt', session_id: sid, duplicate: true,
          hint: '这条消息已受理过（幂等键命中），回复请从事件流补拉',
        });
      }
      const wantsStream = body.stream === true || String(req.headers['accept'] ?? '').includes('text/event-stream');
      // 手机给的 timeout（秒）在这里真正生效：到点杀 CLI 子进程，手机断开也杀。
      // 以前 CLI 固定 300s，手机 120s 就放弃 —— 中间 3 分钟纯属白烧 CPU/内存。
      const timeoutSec = Number.isFinite(body.timeout) ? Math.max(5, Math.min(600, Number(body.timeout))) : 120;
      const abort = requestAbort(res);
      // 直连协商：手机带回来的电脑会话 id 先验存在（否则 CLI 续写会报错），
      // 无效就当没带 —— 由 CLI 新建一条，并把新 id 回给手机。
      let targetSid = String(body.target_session_id ?? '').trim();
      if (targetSid && !(await sessionExists(targetSid))) targetSid = '';
      await runPrompt(req, res, sid, text, wantsStream, {
        signal: abort.signal,
        deadline: Date.now() + timeoutSec * 1000,
        targetSessionId: targetSid,
        sessionCode: typeof body.session_code === 'string' ? body.session_code : '',
      });
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
    const text = (typeof body.text === 'string' && body.text ? body.text : (typeof body.prompt === 'string' && body.prompt ? body.prompt : (typeof body.task === 'string' ? body.task : ''))).trim();
    if (!text) return sendJson(res, 400, { ok: false, error: 'text is required' });
    const wantsStream = body.stream === true || String(req.headers['accept'] ?? '').includes('text/event-stream');
    const timeoutSec = Number.isFinite(body.timeout) ? Math.max(5, Math.min(600, Number(body.timeout))) : 120;
    const abort = requestAbort(res);
    try {
      let targetSid = String(body.target_session_id ?? '').trim();
      if (targetSid && !(await sessionExists(targetSid))) targetSid = '';
      await runPrompt(req, res, sid, text, wantsStream, {
        signal: abort.signal,
        deadline: Date.now() + timeoutSec * 1000,
        targetSessionId: targetSid,
        sessionCode: typeof body.session_code === 'string' ? body.session_code : '',
      });
    }
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
      handles.delete(id); closed += 1;
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
    handles.clear();
  });

  ctx.logger?.info?.(`[smartquiz-remote] 挂载: /health /reconcile /pair /pair.json /status /config /session /run /exec /reset `
    + `(port=${ctx.webServer.port}, model=${cfg.provider}/${cfg.model}, exec=${allowExec === true})`);
}
