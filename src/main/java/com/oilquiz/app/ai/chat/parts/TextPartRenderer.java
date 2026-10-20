package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.text.Spanned;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.render.MarkdownRenderer;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;
import com.oilquiz.app.ai.chat.ui.MarkdownWebView;

/**
 * 文本 part 渲染器（parts 架构升级 P2，2026-10-21）。
 *
 * <p>完全复用既有 markdown 渲染通路，不新写渲染器：</p>
 * <ul>
 *   <li>{@link PartHost#webMarkdownEnabled()} 为真 → {@link MarkdownWebView#setContent}
 *       （沿用其高度回调、内部高度上限与回收策略）；</li>
 *   <li>否则 → {@link MarkdownRenderer#render(String, Context, int)} 出 Spanned 塞进
 *       一个 {@link TextView}（沿用完成态重载：确保 Markwon 初始化、处理未闭合代码围栏）。</li>
 * </ul>
 *
 * <p>一个 text part 对应一个渲染实例（不拼接成巨型 WebView），符合文档 §4.5 的边界。</p>
 */
public final class TextPartRenderer implements PartRenderer {

    @Override
    public boolean canRender(AssistantTurn.Part part) {
        return part != null && AssistantTurn.Part.TYPE_TEXT.equals(part.type);
    }

    @Override
    public View createView(PartHost host, ViewGroup parent) {
        Context ctx = host.context();
        View view;
        if (host.webMarkdownEnabled()) {
            MarkdownWebView web = new MarkdownWebView(ctx);
            web.setOnContentHeightChanged(heightPx -> host.invalidateSize());
            view = web;
        } else {
            TextView tv = new TextView(ctx);
            tv.setTextIsSelectable(true);
            view = tv;
        }
        view.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    @Override
    public void bindView(View view, AssistantTurn.Part part, PartHost host) {
        String text = part.text != null ? part.text : "";
        if (view instanceof MarkdownWebView) {
            // 完成态：streaming=false，沿用 WebView 内部一次性加载。
            ((MarkdownWebView) view).setContent(text, false);
        } else if (view instanceof TextView) {
            Spanned spanned = MarkdownRenderer.render(text, host.context(), host.availableWidth());
            ((TextView) view).setText(spanned);
        }
    }

    @Override
    public void releaseView(View view) {
        if (view instanceof MarkdownWebView) {
            ((MarkdownWebView) view).resetForReuse();
        }
    }
}
