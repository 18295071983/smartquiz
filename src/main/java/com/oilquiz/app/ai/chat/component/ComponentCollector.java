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

    private static final List<ComponentData> pending = new CopyOnWriteArrayList<>();

    private ComponentCollector() {
    }

    /** 清空暂存（对话开始前调用，隔离上一轮残留） */
    public static void clear() {
        pending.clear();
    }

    /**
     * 收集一个组件数据（工具执行成功且携带组件时由桥接层调用）。
     */
    public static void collect(ComponentData data) {
        if (data != null && data.type != null) {
            pending.add(data);
        }
    }

    /**
     * 取走全部暂存组件并清空。
     *
     * @return 暂存的组件列表；无则返回 null
     */
    public static List<ComponentData> drain() {
        if (pending.isEmpty()) return null;
        List<ComponentData> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    /** 当前是否暂存了组件 */
    public static boolean hasPending() {
        return !pending.isEmpty();
    }
}
