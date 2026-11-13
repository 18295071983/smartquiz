package com.oilquiz.app.ai.chat.component.action;

import android.content.Context;

import com.oilquiz.app.ai.remote.DshComponentIds;
import com.oilquiz.app.ai.tool.RemoteDshTool;

/**
 * 电脑端（DSH）动作落点（2026-11-13，M4）。
 *
 * <p>判定不再靠 id 前缀，而是 {@link DshComponentIds} 的来源登记表（B4 修好的那套）：
 * 投影时登记、恢复历史时按 {@code ComponentData.source} 重建。</p>
 *
 * <p>投递失败返回 false，让路由器把机会交给后面的 sink（例如本地兜底）——
 * 以前这里是直接 return，失败即静默丢失。</p>
 */
public final class DshActionSink implements ActionSink {

    public static final String ID = "dsh";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean canHandle(ActionEvent event) {
        return event != null && DshComponentIds.isDshComponent(event.componentId);
    }

    @Override
    public boolean send(Context context, ActionEvent event) {
        if (context == null || event == null) return false;
        try {
            return RemoteDshTool.sendComponentAction(context, event.componentId, event.value);
        } catch (Throwable t) {
            android.util.Log.w("DshActionSink", "回传电脑端失败: " + t);
            return false;
        }
    }
}
