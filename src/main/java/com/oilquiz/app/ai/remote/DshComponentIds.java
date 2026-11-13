package com.oilquiz.app.ai.remote;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 电脑端（DSH）发出的组件 id 登记表（2026-11-13，B4 / M4）。
 *
 * <p>为什么需要它：卡片按钮最终只带一个组件 id 进 {@code ComponentActions}，
 * 而"这个 id 是电脑端发的还是本机发的"决定了动作往哪送（回传宿主 vs 本机结果槽）。
 * 旧实现靠 {@code id.startsWith("dsh-")} 嗅探，但真实 id 是派发器发的
 * {@code T5-C12}/{@code T5-T3}，于是**所有** DSH 卡片动作都被当成杂项丢掉。</p>
 *
 * <p>登记表是内存态（每次投影/恢复时重建），来源字段 {@code ComponentData.source}
 * 负责跨重启：{@link #markFromHistory(Iterable)} 在加载历史后按 source 重新登记。</p>
 */
public final class DshComponentIds {

    /** 投影产出的组件来源标记（与 {@code ComponentData.source} 同值） */
    public static final String SOURCE_DSH = "dsh";

    /** 旧版本前缀：磁盘上可能还留着这种 id，继续认 */
    private static final String LEGACY_PREFIX = "dsh-";

    private static final Set<String> IDS = ConcurrentHashMap.newKeySet();

    private DshComponentIds() {
    }

    /** 记录一个电脑端组件 id（幂等） */
    public static void mark(String id) {
        if (id != null && !id.isEmpty()) IDS.add(id);
    }

    /** 是否是电脑端组件 id：登记过的，或旧版 {@code dsh-} 前缀 */
    public static boolean isDshComponent(String id) {
        if (id == null || id.isEmpty()) return false;
        return IDS.contains(id) || id.startsWith(LEGACY_PREFIX);
    }

    /** 从历史/恢复的消息里按 {@code source} 重新登记（进程重启后路由仍然正确） */
    public static void markFromHistory(Iterable<ChatMessage> messages) {
        if (messages == null) return;
        for (ChatMessage m : messages) {
            if (m == null || m.components == null) continue;
            for (ComponentData c : m.components) {
                if (c == null) continue;
                if (SOURCE_DSH.equals(c.source)) mark(c.id);
            }
        }
    }

    /** 测试用：清空登记表 */
    public static void clearForTest() {
        IDS.clear();
    }
}
