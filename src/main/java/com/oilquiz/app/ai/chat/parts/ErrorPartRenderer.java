package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;
import com.oilquiz.app.theme.ThemeColors;

/**
 * 错误 part 渲染器（parts 架构升级 P2.5，2026-10-21）。
 *
 * <p>独立视图；正文复用宿主 markdown 原语（{@link PartHost#renderMarkdown}），并给一个语义
 * 错误色（{@code R.color.error}，走 ThemeColors 不写死十六进制），链接可点。</p>
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
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        try {
            tv.setTextColor(ThemeColors.get(ctx, R.color.error));
        } catch (Throwable ignore) {
            // 取色失败用默认前景色
        }
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
        host.renderMarkdown((TextView) view, text);
    }

    @Override
    public void releaseView(View view) {
        // TextView，无重资源。
    }
}
