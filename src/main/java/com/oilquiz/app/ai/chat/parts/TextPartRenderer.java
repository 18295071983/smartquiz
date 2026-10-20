package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.turn.AssistantTurn;
import com.oilquiz.app.ai.chat.ui.MarkdownWebView;

/**
 * 文本 part 渲染器（parts 架构升级 P2，2026-10-21）。
 *
 * <p>完全复用既有 markdown 渲染通路，不新写渲染逻辑：</p>
 * <ul>
 *   <li>web（{@link PartHost#webMarkdownEnabled()}）→ {@link MarkdownWebView}：
 *       {@code setNight(dark)} + {@code setThemeColors(themeVars)} 对齐卡片配色，
 *       {@code setContent(text, streaming)} 沿用其高度回调、内部上限与回收；</li>
 *   <li>原生 → {@link PartHost#renderMarkdown}：走宿主后台渲染 + LinkMovementMethod +
 *       Glide 异步图片 + 主题，避免在本类 {@code setText(Spanned)} 丢图/丢链接、压主线程。</li>
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
            // 链接可点（图片/异步 span 由 renderMarkdown 在绑定时挂上）。
            tv.setMovementMethod(LinkMovementMethod.getInstance());
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
            MarkdownWebView web = (MarkdownWebView) view;
            web.setNight(host.dark());
            web.setThemeColors(host.markdownThemeVars());
            web.setContent(text, host.streaming());
        } else if (view instanceof TextView) {
            host.renderMarkdown((TextView) view, text);
        }
    }

    @Override
    public void releaseView(View view) {
        if (view instanceof MarkdownWebView) {
            ((MarkdownWebView) view).resetForReuse();
        }
    }
}
