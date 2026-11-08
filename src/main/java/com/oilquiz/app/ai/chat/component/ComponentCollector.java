package com.oilquiz.app.ai.chat.component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 组件收集桥：工具执行产生的结构化 UI 组件在此暂存，
 * 当一轮对话生成完成（AI 消息创建时）由 UI 层 drain 取走并附加到消息。
 *
 * 设计为进程级暂存（非会话级），一轮对话内工具产生的组件都会被收集；
 * 若多轮对话并发，由 UI 层在合适的时机调用 {@link #clear()} 隔离。
 */
public class ComponentCollector {

    private static final List<Entry> pending = new CopyOnWriteArrayList<>();

    private ComponentCollector() {
    }

    private static final class Entry {
        final String producer;
        final ComponentData data;

        Entry(String producer, ComponentData data) {
            this.producer = producer == null ? "" : producer;
            this.data = data;
        }
    }

    /** 清空暂存（对话开始前调用，隔离上一轮残留） */
    public static void clear() {
        pending.clear();
    }

    /** 收集组件（旧路径：不带 producer，兼容无法提供工具名的调用点）。 */
    public static void collect(ComponentData data) {
        collectForTool("", data);
    }

    /**
     * 收集组件并记录产出工具。并行工具调用时，不能让先完成的工具
     * drain 到另一个工具产生的组件（真机 T145：location 卡片被标成 ai_weather）。
     */
    public static void collectForTool(String producer, ComponentData data) {
        if (data != null && data.type != null) {
            pending.add(new Entry(producer, data));
        }
    }

    /**
     * 取走暂存组件并清空。
     *
     * @return 暂存组件列表；无则返回 null
     */
    public static List<ComponentData> drain() {
        if (pending.isEmpty()) return null;
        List<ComponentData> out = new ArrayList<>();
        for (Entry e : pending) out.add(e.data);
        pending.clear();
        return out;
    }

    /**
     * 只取走指定工具（或无 producer 的旧数据）产生的组件；其他工具的组件留在队列里，
     * 等各自的 tool-complete 回调再取。这样并行工具的产物不会串归属。
     */
    public static List<ComponentData> drainForTool(String toolName) {
        if (pending.isEmpty()) return null;
        String wanted = toolName == null ? "" : toolName;
        List<ComponentData> out = new ArrayList<>();
        for (Entry e : pending) {
            if (e.producer.isEmpty() || e.producer.equals(wanted)) {
                out.add(e.data);
                pending.remove(e);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** 当前暂存数量（供调用方做"一轮内上限"之类的节流判断） */
    public static int pendingCount() {
        return pending.size();
    }
}
