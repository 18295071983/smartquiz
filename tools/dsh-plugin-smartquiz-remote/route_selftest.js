// route_selftest.js —— 用假 ctx 起插件，验证 2026-11-12 新增的 v2 路由（node route_selftest.js）
//
// 为什么需要它：这些路由依赖 DSH 宿主服务（schedule/planMode/userQuestions/typertGateway），
// 真机验证要连电脑、要有人点；而"参数/能力门禁/返回形状"是纯逻辑，假 ctx 就能钉死。
// 跑之前不需要 DSH 在运行。
import assert from 'node:assert/strict';
import { apply } from './index.js';

const TOKEN = 'route-selftest';
const routes = new Map();
const handlers = new Map();

let planState = { active: false, pending: false };
let settled = null;
let questionAnswer = null;

const sessions = new Map();
const rootEvents = [
  { seq: 1, type: 'subagent/catalog', data: { childId: 'child-1', label: '调研', mode: 'continuable', childCreatedAt: '2026-11-12T00:00:00Z' } },
];
const childEvents = [
  { seq: 1, time: 100, type: 'assistant/message', data: { message: { content: [{ type: 'text', text: '子代理正在整理资料' }] } } },
];
sessions.set('root-1', {
  seq: rootEvents.length,
  eventAt: (i) => rootEvents[i - 1],
});
sessions.set('child-1', {
  seq: childEvents.length,
  eventAt: (i) => childEvents[i - 1],
});

const ctx = {
  effect(fn) {
    const dispose = fn();
    return () => {
      try { dispose?.(); } catch { /* ignore */ }
    };
  },
  get(name) {
    // 与 Cordis 一致：ctx.get(name) 不经 inject 也能读服务
    if (name === 'schedule') return ctx.schedule;
    if (name === 'planMode') return ctx.planMode;
    if (name === 'userQuestions') return ctx.userQuestions;
    if (name !== 'sessionPersistence') return undefined;
    return {
      async open(id) {
        const evs = id === 'root-1' ? rootEvents : (id === 'child-1' ? childEvents : []);
        return { async read() { return evs; }, async close() {} };
      },
      async stat(id) {
        const evs = id === 'root-1' ? rootEvents : (id === 'child-1' ? childEvents : []);
        return { eventCount: evs.length };
      },
    };
  },
  webServer: {
    port: 19387,
    register({ kind, path, handler }) {
      routes.set(path, handler);
      return () => routes.delete(path);
    },
  },
  on(name, fn) {
    handlers.set(name, fn);
    return () => handlers.delete(name);
  },
  agents: {
    get(id) {
      return id === 'root-1' ? { id, session: { id } } : undefined;
    },
  },
  sessions: {
    get(id) {
      return sessions.get(id);
    },
  },
  schedule: {
    async catalog() {
      return [{
        id: 'sch-1', kind: 'daily', title: '每日早报', prompt: '总结昨天',
        scheduledAt: '2026-11-13T01:00:00.000Z', status: 'active',
        target: { kind: 'session', sessionId: 'root-1' }, involvedSessionIds: ['root-1'],
      }];
    },
    async list() {
      return { items: [], shown: 0, total: 0, limit: 100, offset: 0 };
    },
    async view({ id }) {
      return { id, found: true };
    },
    async history() {
      return { id: 'sch-1', items: [], earlierRecordsUnavailable: false };
    },
    async run({ id }) {
      return { id, status: 'active', ran: true };
    },
    async edit(request) {
      return { id: request.id, action: request.action };
    },
  },
  planMode: {
    get() {
      return { ...planState };
    },
    set(_agent, active) {
      const prev = planState.active;
      planState = { active, pending: false };
      return prev === active ? 'noop' : 'committed';
    },
  },
  userQuestions: {
    async answer(_agent, callId, answer) {
      questionAnswer = { callId, answer };
      return true;
    },
  },
  typertGateway: {
    pendingRemoteEvents: new Map([[
      'pending-1',
      { id: 'pending-1', frame: { event: 'user-questions/request', agentId: 'root-1', request: { questions: [{ id: 'q1', question: '选哪个？' }] } } },
    ]]),
    settleRemoteEvent(pending, result) {
      settled = { pending, result };
      this.pendingRemoteEvents.delete(pending.id);
    },
  },
  logger: { info() {}, warn() {}, debug() {} },
};

await apply(ctx, { token: TOKEN });

// ---- 测试工具 ----
let passed = 0;
const test = async (name, fn) => {
  try {
    await fn();
    passed += 1;
    console.log('  ok  ' + name);
  } catch (e) {
    console.error('FAIL  ' + name + '\n      ' + (e?.stack ?? e));
    process.exitCode = 1;
  }
};

function fakeReq({ method = 'GET', url = '/', body = '', headers = {} } = {}) {
  return {
    method,
    url,
    headers: { authorization: 'Bearer ' + TOKEN, ...headers },
    socket: { remoteAddress: '127.0.0.1' },
    on(ev, fn) {
      if (ev === 'data' && body) fn(Buffer.from(body));
      if (ev === 'end') fn();
      return this;
    },
  };
}

function fakeRes() {
  const chunks = [];
  return {
    statusCode: 200,
    headers: {},
    ended: false,
    writeHead(code, h) {
      this.statusCode = code;
      Object.assign(this.headers, h || {});
    },
    write(s) {
      chunks.push(String(s));
    },
    end(s) {
      if (s) chunks.push(String(s));
      this.ended = true;
    },
    text() {
      return chunks.join('');
    },
  };
}

async function call(path, opts) {
  const pathOnly = String(path).split('?')[0];
  const handler = routes.get(pathOnly);
  assert.ok(handler, '路由未注册：' + pathOnly);
  const res = fakeRes();
  await handler(fakeReq({ ...(opts || {}), url: (opts && opts.url) || path }), res);
  let json = null;
  try { json = JSON.parse(res.text()); } catch { /* 非 JSON（SSE） */ }
  return { res, json };
}

// ---- 路由注册 ----
await test('新 v2 路由都已注册', () => {
  for (const p of ['/v2/schedule', '/v2/plan', '/v2/subagents', '/v2/answer']) {
    assert.ok(routes.has(p), '缺路由 ' + p);
  }
});

// ---- 日程 ----
await test('GET /v2/schedule?action=catalog 返回任务', async () => {
  const { res, json } = await call('/v2/schedule?action=catalog&caps=schedule.read');
  assert.equal(res.statusCode, 200);
  assert.equal(json.ok, true);
  assert.equal(json.tasks.length, 1);
  assert.equal(json.tasks[0].title, '每日早报');
});

await test('未声明 schedule.read → 403', async () => {
  const { res, json } = await call('/v2/schedule?action=catalog&caps=view.cards.status');
  assert.equal(res.statusCode, 403);
  assert.equal(json.need, 'schedule.read');
});

await test('POST /v2/schedule 执行一次', async () => {
  const body = JSON.stringify({ action: 'run', id: 'sch-1', caps: 'schedule.read,schedule.write' });
  const { res, json } = await call('/v2/schedule', { method: 'POST', body });
  assert.equal(res.statusCode, 200);
  assert.equal(json.result.ran, true);
});

// ---- 计划 ----
await test('GET /v2/plan 读状态', async () => {
  const { res, json } = await call('/v2/plan?session_id=root-1&caps=plan.read');
  assert.equal(res.statusCode, 200);
  assert.equal(json.active, false);
});

await test('POST /v2/plan 切换；再读为新状态', async () => {
  const body = JSON.stringify({ session_id: 'root-1', active: true, caps: 'plan.read,plan.write' });
  const { res, json } = await call('/v2/plan', { method: 'POST', body });
  assert.equal(res.statusCode, 200);
  assert.equal(json.active, true);
  const read = await call('/v2/plan?session_id=root-1&caps=plan.read');
  assert.equal(read.json.active, true);
});

await test('会话不 live → 409（而不是静默失败）', async () => {
  const { res, json } = await call('/v2/plan?session_id=dead&caps=plan.read');
  assert.equal(res.statusCode, 409);
  assert.equal(json.error, 'agent_not_live');
});

// ---- 子代理 ----
await test('GET /v2/subagents 列目录 + 最近活动', async () => {
  const { res, json } = await call('/v2/subagents?session_id=root-1&caps=subagents.panel');
  assert.equal(res.statusCode, 200);
  assert.equal(json.children.length, 1);
  const c = json.children[0];
  assert.equal(c.child_id, 'child-1');
  assert.equal(c.label, '调研');
  assert.equal(c.last_text, '子代理正在整理资料');
  assert.equal(c.live, false);
});

// ---- 问答答案 ----
await test('POST /v2/answer 走网关 settle（阻塞问题）', async () => {
  const body = JSON.stringify({
    id: 'pending-1',
    answers: [{ id: 'q1', selected: ['A'] }],
    caps: 'questions.answer',
  });
  const { res, json } = await call('/v2/answer', { method: 'POST', body });
  assert.equal(res.statusCode, 200);
  assert.equal(json.via, 'gateway');
  assert.ok(settled, '应 settle 网关事件');
  assert.equal(settled.result.kind, 'result');
  assert.equal(settled.result.value.answers[0].selected[0], 'A');
});

await test('POST /v2/answer 走 continued（超时后的问题）', async () => {
  const body = JSON.stringify({
    session_id: 'root-1', call_id: 'call-9',
    answers: [{ id: 'q1', selected: [], custom: '自定义' }],
    caps: 'questions.answer',
  });
  const { res, json } = await call('/v2/answer', { method: 'POST', body });
  assert.equal(res.statusCode, 200);
  assert.equal(json.via, 'continued');
  assert.equal(questionAnswer.callId, 'call-9');
  assert.equal(questionAnswer.answer.answers[0].custom, '自定义');
});

await test('POST /v2/answer 缺 answers → 400', async () => {
  const body = JSON.stringify({ id: 'pending-1', answers: [], caps: 'questions.answer' });
  const { res, json } = await call('/v2/answer', { method: 'POST', body });
  assert.equal(res.statusCode, 400);
  assert.equal(json.error, 'answers_required');
});

// ---- 实时桥：token 增量经 /events 下发 ----
await test('/events 订阅后能收到 agent/assistant-stream 的 text-delta', async () => {
  const handler = routes.get('/events');
  assert.ok(handler);
  const res = fakeRes();
  const req = fakeReq({ url: '/events?session_id=root-1&follow=1&caps=parts.stream' });
  // /events 是长连接：不 await，只让它注册订阅并写 ready
  const pending = handler(req, res);
  await new Promise((r) => setTimeout(r, 40));
  const onStream = handlers.get('agent/assistant-stream');
  assert.ok(onStream, '应注册 agent/assistant-stream 监听');
  onStream({
    agent: { id: 'root-1' },
    frame: { type: 'chunk', turn: 1, step: 1, attemptId: 'a1', chunk: { type: 'text-delta', text: '你' } },
  });
  await new Promise((r) => setTimeout(r, 20));
  const body = res.text();
  assert.ok(body.includes('stream/delta'), 'SSE 应包含 stream/delta：' + body.slice(0, 200));
  assert.ok(body.includes('"text":"你"'), 'SSE 应带增量文本');
  // 清理：模拟断开
  try { req.__close?.(); } catch { /* ignore */ }
  void pending;
});

await test('/events 连接即推 state/view（服务端主动下发）', async () => {
  const handler = routes.get('/events');
  const res = fakeRes();
  const req = fakeReq({ url: '/events?session_id=root-1&follow=1&caps=parts.stream' });
  const pending = handler(req, res);
  await new Promise((r) => setTimeout(r, 60));
  const body = res.text();
  assert.ok(body.includes('"type":"state"'), '应立刻下发 state：' + body.slice(0, 300));
  assert.ok(body.includes('"session_id":"root-1"'), 'state 应带会话 id');
  void pending;
});

await test('对账用 last_seq 给 verdict/gap（不再永远 summary）', async () => {
  const { res, json } = await call('/reconcile?light=1&session_id=root-1&last_seq=0');
  assert.equal(res.statusCode, 200);
  assert.equal(json.verdict, 'behind');
  assert.ok(json.gap >= 1, 'gap 应大于 0：' + JSON.stringify(json));
  const same = await call('/reconcile?light=1&session_id=root-1&last_seq=1');
  assert.equal(same.json.verdict, 'match');
});

await test('/events?since=-2 不回放历史（首次连接只 ready）', async () => {
  const handler = routes.get('/events');
  const res = fakeRes();
  const req = fakeReq({ url: '/events?session_id=root-1&follow=1&since=-2&caps=parts.stream' });
  const pending = handler(req, res);
  await new Promise((r) => setTimeout(r, 60));
  const body = res.text();
  assert.ok(body.includes('"type":"ready"'), '应下发 ready：' + body.slice(0, 200));
  assert.ok(!body.includes('"type":"event"'), '不应回放历史事件：' + body.slice(0, 200));
  void pending;
});

console.log(`\n${passed} passed${process.exitCode ? '（有失败）' : ''}`);
// /events 的定时器会让 node 不退出；自检结束按结果直接退出
process.exit(process.exitCode ?? 0);
