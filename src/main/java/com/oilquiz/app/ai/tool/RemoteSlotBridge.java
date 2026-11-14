package com.oilquiz.app.ai.tool;

import com.oilquiz.app.ai.chat.status.DockSlotBus;

import org.json.JSONObject;

/**
 * 插件推来的插槽 → 甲板（C4，2026-11-14）。
 *
 * <p>以前甲板的内容全部由手机代码构造（`RemoteStatusSlots` 等），插件只能推
 * `ui_component` 命令。现在插件可以直接推 {@code ui/slot} 帧，字段与
 * {@link DockSlotBus.Slot} 一一对应；手机只做校验和渲染，**不再需要为每种新槽位发版**。</p>
 *
 * <p>帧格式（见 27 号《远程插件更新指南》）：</p>
 * <pre>
 * { "type":"ui/slot", "data": { id, title, detail, action?, priority?, warn?,
 *                               kind?: toggle|action|display|list, board?: alert|task|asset|state,
 *                               pinned?, count?, remove?, rev?, epoch?, ttl_ms? } }
 * </pre>
 *
 * <p>§20（2026-11-15）：{@code rev} 是同一 id 单调递增的版本号，乱序旧帧由
 * {@link DockSlotBus} 丢弃；{@code epoch} 是插件实例世代（毫秒时间戳），插件重载后
 * rev 从 1 重来，App 靠它清水位/清旧插件槽；{@code ttl_ms} 把相对存活时间换算成绝对
 * {@code expireAt}，生产者失联后槽位会自己消失。</p>
 */
public final class RemoteSlotBridge {

    /** ttl 上限（24h）：防止异常帧把过期时间算到溢出/永久。 */
    private static final long MAX_TTL_MS = 24L * 3600L * 1000L;

    private RemoteSlotBridge() {
    }

    /** 应用一条插槽帧；返回 true 表示已处理 */
    public static boolean apply(JSONObject data) {
        if (data == null) return false;
        String id = data.optString("id", "").trim();
        if (id.isEmpty()) return false;
        long rev = Math.max(0L, data.optLong("rev", 0L));
        // §20 修订（2026-11-15）：插件重载后 rev 会从 1 重来，epoch 变大就清水位、清旧插件槽；
        // epoch 更小的帧是上一世代迟到的，整帧丢弃（否则重载后移除/更新会永久失效）。
        if (DockSlotBus.notePluginEpoch(Math.max(0L, data.optLong("epoch", 0L))) < 0) {
            return true;
        }
        if (data.optBoolean("remove", false)) {
            DockSlotBus.remove(id, rev);
            return true;
        }
        /**
         * 2026-11-14：插槽"完成后消失"的统一约定。
         *
         * <p>插件历史上用"发一条空帧"（只有 id、没有 title/detail）表示移除，
         * 但这里以前只有 {@code remove:true} 才真的删 —— 于是"压缩中/子代理/任务"
         * 结束后槽位只是变成空壳留在总线里，甲板会挂一条空白。现在两种写法等价。</p>
         */
        String title = data.optString("title", "").trim();
        String detail = data.optString("detail", "").trim();
        int count = data.optInt("count", 0);
        if (title.isEmpty() && detail.isEmpty() && count <= 0) {
            DockSlotBus.remove(id, rev);
            return true;
        }
        long ttlMs = Math.min(Math.max(0L, data.optLong("ttl_ms", 0L)), MAX_TTL_MS);
        long expireAt = ttlMs > 0 ? System.currentTimeMillis() + ttlMs : 0L;
        DockSlotBus.update(new DockSlotBus.Slot(
                id,
                data.optInt("priority", 60),
                data.optString("title", ""),
                data.optString("detail", ""),
                data.optString("action", ""),
                data.optBoolean("warn", false),
                data.optString("kind", "display"),
                data.optString("board", "state"),
                data.optBoolean("pinned", false),
                data.optInt("count", 0),
                rev,
                expireAt,
                "plugin"));
        return true;
    }
}
