package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.render.MarkdownRenderer;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;

/**
 * 错误 part 渲染器（parts 架构升级 P2 补齐，2026-10-21）。
 *
 * <p>错误行是独立视图，正文复用 markdown 原语 {@link MarkdownRenderer#render}（与 text 同源，
 * 不新写渲染器）。P2 能安全落地是因为它不依赖 ChatAdapter 宿主、也不涉及工具卡归属；
 * tool_call/tool_result/data 要等 P3（需宿主 + ToolCallCardView 复用 + 真机"不串卡"验证）。</p>
 */
public final class ErrorPartRenderer implements PartRenderer {

    @Override
    public boolean canRender(AssistantTurn.Part part) {
        return part != null && AssistantTurn.Part.TYPE_ERROR.equals(part.type);
    }

    @Override
    public View createView(PartHost host, ViewGroup parent) {
        Context ctx = host.context();
        TextView tv = new TextView(ctx);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f,
                ctx.getResources().getDisplayMetrics());
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);
        tv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    @Override
    public void bindView(View view, AssistantTurn.Part part, PartHost host) {
        if (!(view instanceof TextView)) {
            return;
        }
        String text = part.text != null ? part.text : (part.result != null ? part.result : "");
        Spanned spanned = MarkdownRenderer.render(text, host.context(), host.availableWidth());
        ((TextView) view).setText(spanned);
    }

    @Override
    public void releaseView(View view) {
        // TextView，无重资源。
    }
}
