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
 *                               pinned?, count?, remove? } }
 * </pre>
 */
public final class RemoteSlotBridge {

    private RemoteSlotBridge() {
    }

    /** 应用一条插槽帧；返回 true 表示已处理 */
    public static boolean apply(JSONObject data) {
        if (data == null) return false;
        String id = data.optString("id", "").trim();
        if (id.isEmpty()) return false;
        if (data.optBoolean("remove", false)) {
            DockSlotBus.remove(id);
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
            DockSlotBus.remove(id);
            return true;
        }
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
                data.optInt("count", 0)));
        return true;
    }
}
