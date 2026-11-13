package com.oilquiz.app.ai.chat.component.action;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 组件动作路由器（2026-11-13，M4）。
 *
 * <p>以前"往哪送"的判断散在 {@code ComponentActions.notifyResult} 里：先看 id 前缀、
 * 再查组件回调表、最后兜全局回调。新增一种去处（动态卡跳转、MCP 工具）就要改那个大方法。
 * 现在收敛成一张有序 sink 表：</p>
 *
 * <ul>
 *   <li>顺序即优先级（DSH 在前，本机回调在后兜底）；</li>
 *   <li>单个 sink 抛异常只记一行日志，**不影响后面的 sink**（故障隔离）；</li>
 *   <li>返回 false = 没人认领，调用方可以据此提示/上报。</li>
 * </ul>
 */
public final class ActionRouter {

    private static final String TAG = "ActionRouter";

    private final List<ActionSink> sinks = new CopyOnWriteArrayList<>();

    public ActionRouter addSink(ActionSink sink) {
        if (sink != null) sinks.add(sink);
        return this;
    }

    /** 插到指定位置（测试/覆盖场景） */
    public ActionRouter addSink(int index, ActionSink sink) {
        if (sink == null) return this;
        int at = Math.max(0, Math.min(index, sinks.size()));
        sinks.add(at, sink);
        return this;
    }

    public List<ActionSink> sinks() {
        return Collections.unmodifiableList(new ArrayList<>(sinks));
    }

    public void clearSinks() {
        sinks.clear();
    }

    public int size() {
        return sinks.size();
    }

    /**
     * 分发一次动作。
     *
     * @return true = 有 sink 消费了；false = 无人认领（已记日志）
     */
    public boolean dispatch(Context context, ActionEvent event) {
        if (event == null || event.componentId.isEmpty()) return false;
        for (ActionSink sink : sinks) {
            boolean eligible;
            try {
                eligible = sink.canHandle(event);
            } catch (Throwable t) {
                Log.w(TAG, "sink " + sink.id() + " canHandle 抛异常（跳过）: " + t);
                continue;
            }
            if (!eligible) continue;
            try {
                if (sink.send(context, event)) {
                    Log.i(TAG, "动作已投递 sink=" + sink.id() + " " + event);
                    return true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "sink " + sink.id() + " send 抛异常（继续下一个）: " + t);
            }
        }
        Log.w(TAG, "动作无人认领 " + event);
        return false;
    }
}
