package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.ChatMessage;

/**
 * 渲染环境（parts 架构升级 P2，2026-10-21）。
 *
 * <p>{@link PartRenderer} 不直接持有 {@code ChatAdapter}，只依赖本接口取它需要的最小环境。
 * 真正的宿主由 {@code ChatAdapter}（P3 接线时）实现，把宽度、markdown 应用、主题、流式态等
 * 现有能力透出来——这样 renderer 复用既有渲染通路，而不是造平行轮子。</p>
 *
 * <p>为什么 markdown 要经 {@link #renderMarkdown}：原生 Markwon 路径的正确做法是后台线程渲染 +
 * {@code LinkMovementMethod} + Glide 异步图片 span + 主题取色（见 ChatAdapter 的 renderInto）。
 * 若 renderer 自己 {@code MarkdownRenderer.render(...).setText(...)}，图片不显示、链接不可点，
 * 还把解析压到主线程。故正文渲染统一交给宿主这条既有原语，renderer 不重复实现。</p>
 */
public interface PartHost {

    /** 用于 inflate/构造 View 的上下文。 */
    Context context();

    /** 可用宽度（px），MarkdownRenderer 排版与表格测量都依赖它。 */
    int availableWidth();

    /** 当前 part 所属的消息；renderer 需要消息级状态（如展开位、工具归属）时用。 */
    ChatMessage message();

    /**
     * 是否用 WebView 渲染 markdown（沿用既有 {@code web_markdown_render} 开关口径）。
     * true → {@link com.oilquiz.app.ai.chat.ui.MarkdownWebView}；false → TextView +
     * {@link #renderMarkdown}。
     */
    boolean webMarkdownEnabled();

    /** 当前是否夜间模式（喂 {@code MarkdownWebView.setNight}）。 */
    boolean dark();

    /**
     * 主题 CSS 变量覆盖串（喂 {@code MarkdownWebView.setThemeColors}），如
     * {@code "--fg:#111;--link:#2563eb;--muted:#666"}；取不到返回 ""（用内置默认配色）。
     */
    String markdownThemeVars();

    /**
     * 用宿主既有的 markdown 原语把文本渲染进 {@code target}（后台线程 + LinkMovementMethod +
     * Glide 异步图片 + 主题）。原生路径专用，renderer 不得自行 setText 绕过它。
     */
    void renderMarkdown(TextView target, String markdown);

    /** 内容高度变化后请求宿主重排（WebView 高度回调、RecyclerView item 重测量都走这里）。 */
    void invalidateSize();
}
