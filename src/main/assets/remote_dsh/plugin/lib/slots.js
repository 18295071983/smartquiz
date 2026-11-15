/**
 * 甲板插槽优先级档位（从 index.js 抽出，2026-11-16）。
 *
 * <p>这张表是**双端契约**：手机端 `SlotKind` / 插件 `normalizeSlotFields` 都按 p0..p5
 * 解释优先级。放独立模块是为了让"改档位"这件事有一个唯一入口，而不是在 8000 行里搜三处。</p>
 */

export const SLOT_PRIORITY = Object.freeze({ p0: 0, p1: 5, p2: 10, p3: 20, p4: 30, p5: 50 });

/** 档位名 → 数值；未知/空返回 null（调用方自己决定回退，不在这里吞掉旧 priority）。 */
export const slotPriorityOfLevel = (level) => {
  const key = String(level ?? '').trim().toLowerCase();
  return Object.prototype.hasOwnProperty.call(SLOT_PRIORITY, key) ? SLOT_PRIORITY[key] : null;
};

/** 数值 → 档位名；不在表里返回 ''（历史中间值不硬凑"最近档位"，免得日志误导）。 */
export const slotLevelOfPriority = (priority) => {
  const p = Number(priority);
  for (const [k, v] of Object.entries(SLOT_PRIORITY)) {
    if (v === p) return k;
  }
  return '';
};

/** 一帧插槽的优先级：已知 level → 表；否则旧 priority；都没有 → p4（与手机端默认一致）。 */
export const slotPriorityValue = (data) => {
  const byLevel = slotPriorityOfLevel(data?.level);
  if (byLevel !== null) return byLevel;
  const n = Number(data?.priority);
  return Number.isFinite(n) ? n : SLOT_PRIORITY.p4;
};
