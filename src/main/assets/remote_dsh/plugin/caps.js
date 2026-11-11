// caps.js —— 双端能力协商（纯函数，不依赖 DSH / 网络，可直接单测）
//
// 为什么单独一个文件：能力协商是"协议契约"，不属于任何一条路由。放在 index.js 里
// 只能靠读代码猜，也没法测；抽出来后：
//   · index.js 只负责"从请求里取 caps → 调这里 → 按结果裁剪下发内容"；
//   · src/main/java/.../RemoteCaps.java 是同名契约的客户端实现；
//   · caps_selftest.js 用 node 直接验证（含"旧客户端不声明 = 全部允许"的兼容口径）。
//
// 语义：
//   - 客户端不声明 caps（旧 APK / 手写 curl）→ 视为 legacy：一切照发，不裁剪；
//   - 客户端声明了 caps → 只下发它声明支持的卡片/事件/命令；
//   - 服务端多出来的能力不回传（避免客户端以为能用）。

export const CAP_VERSION = 1;

/** 服务端能提供的能力（顺序即面板展示顺序） */
export const SERVER_CAPS = Object.freeze([
  'view.cards.status',
  'view.cards.checks',
  'view.cards.connections',
  'view.cards.session',
  'view.cards.approval',
  'view.cards.timeline',
  'view.cards.schedule',
  'view.cards.subagents',
  'view.cards.plan',
  'parts.approval',
  'parts.tool-cards',
  'parts.data-cards',
  'parts.stream',
  'parts.questions',
  'events.stream',
  'events.replay',
  'events.ack',
  'schedule.read',
  'schedule.write',
  'plan.read',
  'plan.write',
  'questions.answer',
  'subagents.panel',
  'cmd.ui',
  'cmd.shell',
]);

/** /v2/view 的 card.kind → 需要的客户端能力 */
export const VIEW_CARD_CAP = Object.freeze({
  status: 'view.cards.status',
  checks: 'view.cards.checks',
  connections: 'view.cards.connections',
  session: 'view.cards.session',
  approval: 'view.cards.approval',
  timeline: 'view.cards.timeline',
  schedule: 'view.cards.schedule',
  subagents: 'view.cards.subagents',
  plan: 'view.cards.plan',
  caps: 'view.cards.status',
});

/** 这些事件只有在客户端能渲染 approval 块时才有意义 */
const APPROVAL_EVENTS = new Set(['approval', 'approval_gone', 'question', 'question_gone']);

/** 合成事件（非持久）所需能力；未列出的类型不需要额外能力 */
const EVENT_CAP = Object.freeze({
  'stream/delta': 'parts.stream',
  'stream/end': 'parts.stream',
  'subagent/activity': 'subagents.panel',
  'plan/mode': 'plan.read',
  'schedule/changed': 'schedule.read',
});

/** 数组 / 逗号分隔字符串 / null → 去重去空的能力列表 */
export function normalizeCaps(raw) {
  let list = [];
  if (Array.isArray(raw)) list = raw;
  else if (typeof raw === 'string') list = raw.split(',');
  else if (raw && typeof raw === 'object') list = []; // 不认识的结构按"未声明"处理
  const out = [];
  for (const item of list) {
    const cap = String(item ?? '').trim();
    if (!cap || out.includes(cap)) continue;
    out.push(cap);
  }
  return out;
}

/** 客户端声明了但服务端不认识的能力（客户端版本比服务端新时会出现） */
export function unknownCaps(raw) {
  return normalizeCaps(raw).filter((cap) => !SERVER_CAPS.includes(cap));
}

/** 客户端缺的服务端能力；未声明（legacy）返回空数组——兼容旧客户端 */
export function missingCaps(raw) {
  const caps = normalizeCaps(raw);
  if (caps.length === 0) return [];
  return SERVER_CAPS.filter((cap) => !caps.includes(cap));
}

/** 客户端是否支持某能力；未声明 caps 的 legacy 客户端按"全支持" */
export function allows(raw, cap) {
  const caps = normalizeCaps(raw);
  if (caps.length === 0) return true;
  return caps.includes(cap);
}

/** 按能力裁剪 /v2/view 的 cards（未知 kind 不裁，保证前向兼容） */
export function filterViewCards(cards, raw) {
  const caps = normalizeCaps(raw);
  if (caps.length === 0) return cards;
  const list = Array.isArray(cards) ? cards : [];
  return list.filter((card) => {
    const need = VIEW_CARD_CAP[String(card?.kind ?? '')];
    if (!need) return true;
    return caps.includes(need);
  });
}

/** 事件是否该下发给这个客户端 */
export function eventAllowed(type, raw) {
  const t = String(type ?? '');
  if (APPROVAL_EVENTS.has(t)) return allows(raw, 'parts.approval');
  const need = EVENT_CAP[t];
  if (need) return allows(raw, need);
  return true;
}

/** 握手响应里的 caps 块 */
export function summarize(raw) {
  const client = normalizeCaps(raw);
  return {
    version: CAP_VERSION,
    declared: client.length > 0,
    legacy: client.length === 0,
    client,
    server: [...SERVER_CAPS],
    missing: missingCaps(client),
    unknown: unknownCaps(client),
  };
}
