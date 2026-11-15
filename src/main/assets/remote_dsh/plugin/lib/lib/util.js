/**
 * 零依赖工具（从 index.js 抽出的第一批模块，2026-11-16）。
 *
 * <p>抽取原则：只放**不碰 ctx、不碰 HTTP、不碰全局业务状态**的小工具，
 * 这样 index.js 的 `apply()` 闭包一行都不用改，仅把定义换成 import —— 风险最低，
 * 同时结束"改一行要在 8000 行里翻"的局面。</p>
 *
 * <p>没抽的两个：{@code killTree} / {@code lowerPriority} 依赖 spawn 与平台 API，
 * 与 CLI 调度强相关，留在主文件里跟调用点一起读更清楚。</p>
 */
import { createHash } from 'node:crypto';

/** 每个 key 只告警一次（重启后重新计）。 */
const WARNED_ONCE = new Set();
export function warnOnce(key, message) {
  if (!key || WARNED_ONCE.has(key)) return;
  WARNED_ONCE.add(key);
  try {
    console.warn(message);
  } catch {
    // console 不可用时忽略
  }
}

/**
 * 发送幂等：客户端带 client_msg_id，同一 (会话, id) 10 分钟内只受理一次。
 * 覆盖"网络抖了、手机重试，但电脑其实已经执行完"这种最恼人的重复执行。
 */
const CLIENT_MSG_SEEN = new Map();
export function clientMsgDuplicated(sid, id) {
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

/** 中断错误（带 code，便于上层区分"用户取消"与"真失败"）。 */
export const abortError = () => {
  const e = new Error('aborted');
  e.code = 'aborted';
  return e;
};

/** 内容指纹（来源白名单用）：sha1 前 16 位。 */
export function originHash(text) {
  return createHash('sha1').update(String(text ?? '').trim()).digest('hex').slice(0, 16);
}
