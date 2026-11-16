package com.oilquiz.app.ai.chat.component;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.widget.ScrollView;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.Tokens;
import com.oilquiz.app.ui.modal.AppSheet;

/**
 * 工具参数/结果"独立弹层"（2026-11-16）。
 *
 * <p>为什么有它：工具卡内联只展示 2 行摘要（点击展开也只是 2↔10 行），长参数/长日志会被截断，
 * 用户想看完整内容只能复制出去。这里用标准弹层 {@link AppSheet} 承载完整文本：
 * 可滚动、等宽字体、一键复制，和工具卡共用同一套主题与弹层标准。</p>
 */
public final class ToolDetailSheet {

    private ToolDetailSheet() {
    }

    /**
     * @param title 弹层标题（如「终端命令」「shell」）
     * @param body  完整内容（参数 + 结果/输出），可空
     */
    public static void show(Context context, CharSequence title, CharSequence body) {
        if (context == null) {
            return;
        }
        String text = body == null ? "" : body.toString();
        if (TextUtils.isEmpty(text)) {
            text = context.getString(R.string.chat_v2_no_data);
        }
        ScrollView scroll = new ScrollView(context);
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextIsSelectable(true);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(Tokens.textSizeSp(tv.getContext(), Tokens.TEXT_CAPTION));
        tv.setTextColor(ComponentColors.textSecondary(context));
        int pad = Tokens.spaceSm(context);
        tv.setPadding(pad, pad, pad, pad);
        scroll.addView(tv, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        final String copyText = text;
        AppSheet.builder(context)
                .title(TextUtils.isEmpty(title) ? "工具详情" : title)
                .content(scroll)
                .action("复制", false, v -> copy(context, copyText))
                .action("关闭", true, null)
                .show();
    }

    private static void copy(Context context, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("tool_detail", text));
            }
        } catch (Throwable ignored) {
            // 复制失败不影响查看
        }
    }

    /** 把工具数据拼成"完整详情"文本（工具卡长内容统一用它）。 */
    public static String buildBody(CharSequence args, CharSequence result, CharSequence statusNote) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(args)) {
            sb.append("参数\n").append(args).append("\n\n");
        }
        if (!TextUtils.isEmpty(statusNote)) {
            sb.append("状态\n").append(statusNote).append("\n\n");
        }
        if (!TextUtils.isEmpty(result)) {
            sb.append("结果\n").append(result);
        }
        return sb.toString().trim();
    }
}
