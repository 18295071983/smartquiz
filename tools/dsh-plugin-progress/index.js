/**
 * Progress Bar · 宿主侧（DSH 插件）
 *
 * 作用：给「我」一个在对话界面里展示进度的通道。
 *
 * 传输：注册到 DSH 自带的 webServer（同源，浏览器 fetch 无 CORS 问题）
 *   GET  /dsh-progress/state            读全部进度快照（客户端轮询用）
 *   POST /dsh-progress/set              写一条进度（我通过它驱动）
 *   POST /dsh-progress/clear            清空（可指定 id）
 *
 * 设计要点：
 *   · 多任务并存：每条进度有独立 id，界面按 id 渲染多个条
 *   · 自动过期：超过 ttlMs 没更新就视为失效（防止遗留进度条卡死界面）
 *   · 心跳计时：客户端据 updatedAt 显示「N 秒未更新」→ 我自己被卡住时你能看出来
 *
 * 驱动方式（我在 pwsh 里调用）：
 *   python tools\dsh-progress.py set "编译原生库" 40 100 "正在编译 ggml-hexagon"
 *   python tools\dsh-progress.py done "编译原生库" "编译完成"
 *   python tools\dsh-progress.py clear
 */

const tasks = new Map();          // id -> { id, label, value, total, detail, state, updatedAt, startedAt }

const DEFAULT_TTL_MS = 5 * 60 * 1000;   // 5 分钟没更新即失效
const MAX_TASKS = 8;

function snapshot() {
  const now = Date.now();
  const list = [];
  for (const t of tasks.values()) {
    const age = now - t.updatedAt;
    if (age > (t.ttlMs || DEFAULT_TTL_MS)) {
      tasks.delete(t.id);          // 过期即回收，避免界面卡住
      continue;
    }
    list.push({ ...t, ageMs: age });
  }
  list.sort((a, b) => a.startedAt - b.startedAt);
  return { ok: true, now, tasks: list };
}

function readBody(req, limit = 256 * 1024) {
  return new Promise((resolve) => {
    let buf = '';
    req.on('data', (c) => {
      buf += c;
      if (buf.length > limit) { req.destroy(); resolve(null); }
    });
    req.on('end', () => {
      if (!buf) return resolve({});
      try { resolve(JSON.parse(buf)); } catch { resolve(null); }
    });
    req.on('error', () => resolve(null));
  });
}

const sendJson = (res, status, obj) => {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
  });
  res.end(body);
};

/** 把外部输入规整成一条进度记录 */
function normalize(input, id) {
  const now = Date.now();
  const prev = tasks.get(id);
  let value = Number(input.value);
  let total = Number(input.total);
  if (!Number.isFinite(value)) value = prev ? prev.value : 0;
  if (!Number.isFinite(total) || total <= 0) total = prev ? prev.total : 100;
  value = Math.max(0, Math.min(total, value));

  const state = ['running', 'done', 'error'].includes(input.state)
    ? input.state
    : (prev?.state ?? 'running');

  return {
    id,
    label: String(input.label ?? prev?.label ?? id).slice(0, 120),
    value,
    total,
    detail: String(input.detail ?? '').slice(0, 300),
    state,
    startedAt: prev?.startedAt ?? now,
    updatedAt: now,
    ttlMs: Number.isFinite(Number(input.ttlMs)) ? Number(input.ttlMs) : (prev?.ttlMs ?? DEFAULT_TTL_MS),
  };
}

export const name = 'dsh-progress';
export const inject = ['webServer'];   // cordis 不允许访问未 inject 的服务

export function apply(ctx) {
  ctx.logger?.info?.('[dsh-progress] 进度条插件已加载');

  ctx.effect(() => ctx.webServer.register({
    kind: 'exact',
    path: '/dsh-progress/state',
    handler: (_req, res) => sendJson(res, 200, snapshot()),
  }));

  ctx.effect(() => ctx.webServer.register({
    kind: 'exact',
    path: '/dsh-progress/set',
    handler: async (req, res) => {
      const body = await readBody(req);
      if (!body) return sendJson(res, 400, { ok: false, error: 'bad_json' });
      const id = String(body.id ?? 'default').slice(0, 64);
      if (tasks.size >= MAX_TASKS && !tasks.has(id)) {
        // 超上限时淘汰最旧的，避免无限增长
        const oldest = [...tasks.values()].sort((a, b) => a.startedAt - b.startedAt)[0];
        if (oldest) tasks.delete(oldest.id);
      }
      const rec = normalize(body, id);
      tasks.set(id, rec);
      return sendJson(res, 200, { ok: true, task: rec });
    },
  }));

  ctx.effect(() => ctx.webServer.register({
    kind: 'exact',
    path: '/dsh-progress/clear',
    handler: async (req, res) => {
      const body = await readBody(req);
      const id = body && body.id != null ? String(body.id) : null;
      if (id) tasks.delete(id); else tasks.clear();
      return sendJson(res, 200, { ok: true, cleared: id ?? '*', remaining: tasks.size });
    },
  }));

  ctx.effect(() => () => tasks.clear());
}
