package com.oilquiz.app.ai.chat.ui;

import android.content.Context;

import com.oilquiz.app.R;

/**
 * 工具活动行的状态文案与状态色（2026-10-21 抽出）。
 *
 * <p>原先是 {@code ChatAdapter} 的两个私有方法。抽出来的原因是 parts 渲染器要把工具渲成
 * <b>与 legacy 同一个紧凑行</b>（{@code R.layout.item_chat_v2_tool_row}），状态措辞必须同源——
 * 否则"已完成/失败/执行中"两套叫法会各自漂移（本项目已在组件目录字段上踩过同类坑）。
 * {@code ChatAdapter} 现在委托到这里，legacy 路径行为不变。</p>
 *
 * <p>注意：这里的入参是<b>字符串</b>状态（来自 {@code tool_call} 卡 props / parts 的
 * {@code Part.status}），与枚举版 {@code toolStatusColor(ToolCallStatus)} 不是同一条通道。</p>
 */
public final class ToolRowStyle {

    private ToolRowStyle() {
    }

    /** 状态 → 行尾文案；未知状态一律按"执行中"显示（与旧私有实现同口径）。 */
    public static String statusText(Context ctx, String status) {
        if ("completed".equals(status) || "success".equals(status)) {
            return ctx.getString(R.string.chat_v2_tool_done);
        }
        if ("failed".equals(status) || "error".equals(status)) {
            return ctx.getString(R.string.chat_v2_tool_failed);
        }
        return ctx.getString(R.string.chat_v2_tool_running);
    }

    /** 状态 → 行尾文字色。 */
    public static int statusColor(Context ctx, String status) {
        if ("completed".equals(status) || "success".equals(status)) {
            return androidx.core.content.ContextCompat.getColor(ctx, R.color.chat_v2_success);
        }
        if ("failed".equals(status) || "error".equals(status)) {
            return androidx.core.content.ContextCompat.getColor(ctx, R.color.chat_v2_danger);
        }
        return androidx.core.content.ContextCompat.getColor(ctx, R.color.chat_v2_text_3);
    }
}
