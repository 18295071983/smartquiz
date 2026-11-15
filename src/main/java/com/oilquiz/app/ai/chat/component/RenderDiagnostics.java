package com.oilquiz.app.ai.chat.component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡片渲染诊断收集器（2026-11-16）。
 *
 * <p><b>为什么必须有它</b>：卡片渲染失败原来是"哑失败" —— {@code ComponentRegistry.render}
 * 里落兜底或抛异常只写 logcat，<b>模型完全收不到</b>。于是 Agent 创建完组件拿到
 * {@code success=true}，就认为用户看到了一张正常卡片，实际上用户看的是一张
 * "查看原始 JSON"的兜底卡（甚至是空白）。模型没有反馈路径 = 会反复犯同一个错。</p>
 *
 * <p><b>怎么把反馈送回去</b>：渲染发生在 UI 线程、通常在工具返回之后，所以做不到
 * "同一次工具结果里带上"。这里用<b>未送达队列</b>：</p>
 * <ol>
 *   <li>渲染失败时 {@link #record} 记一条（类型、原因、字段建议）；</li>
 *   <li>下一次工具调用结束时，{@code AIToolManager.executeTool} 调 {@link #drain}
 *       取走未送达条目，塞进那次结果的 {@code additionalInfo.render_warnings}；</li>
 *   <li>模型下一轮就能看到"你上次渲染 xxx 卡片失败了：需要 headers/rows"。</li>
 * </ol>
 *
 * <p>容量上限 20 条：渲染失败不会无限堆积，超出丢最旧的（并计数，避免静默丢失）。</p>
 */
public final class RenderDiagnostics {

    /** 一条渲染诊断 */
    public static final class Entry {
        public final String type;
        public final String reason;
        public final String hint;
        public final long at;

        Entry(String type, String reason, String hint) {
            this.type = type;
            this.reason = reason;
            this.hint = hint;
            this.at = System.currentTimeMillis();
        }

        /** 给模型看的一行文字 */
        public String text() {
            StringBuilder sb = new StringBuilder("卡片 ").append(type).append(" 渲染失败：").append(reason);
            if (hint != null && !hint.isEmpty()) {
                sb.append("（").append(hint).append("）");
            }
            return sb.toString();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("reason", reason);
            m.put("hint", hint == null ? "" : hint);
            m.put("at", at);
            return m;
        }
    }

    private static final int MAX = 20;
    private static final List<Entry> PENDING = new ArrayList<>();
    private static int dropped = 0;

    private RenderDiagnostics() {
    }

    /** 记一条渲染失败（线程安全）。 */
    public static synchronized void record(String type, String reason, String hint) {
        if (PENDING.size() >= MAX) {
            PENDING.remove(0);
            dropped++;
        }
        PENDING.add(new Entry(type, reason == null ? "未知原因" : reason, hint));
    }

    /** 取走全部未送达条目（送完即清空，保证模型不会重复收到同一条）。 */
    public static synchronized List<Entry> drain() {
        List<Entry> out = new ArrayList<>(PENDING);
        PENDING.clear();
        return out;
    }

    /** 不取走，仅查看（测试/调试用）。 */
    public static synchronized List<Entry> peek() {
        return new ArrayList<>(PENDING);
    }

    /** 因容量上限被丢弃的条数（可观测性）。 */
    public static synchronized int droppedCount() {
        return dropped;
    }

    /** 清空（测试用）。 */
    public static synchronized void clear() {
        PENDING.clear();
        dropped = 0;
    }
}
