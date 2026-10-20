package com.oilquiz.app.ai.chat.parts;

import android.content.Context;

import com.oilquiz.app.ai.chat.ChatMessage;

/**
 * 渲染环境（parts 架构升级 P2，2026-10-21）。
 *
 * <p>{@link PartRenderer} 不直接持有 {@code ChatAdapter}，只依赖本接口取它需要的最小环境。
 * 真正的宿主由 {@code ChatAdapter}（P3 接线时）实现，把宽度、是否走 WebView、请求重排等
 * 现有能力透出来——这样 renderer 复用既有渲染通路，而不是造平行轮子。</p>
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
     * {@link com.oilquiz.app.ai.chat.render.MarkdownRenderer}。
     */
    boolean webMarkdownEnabled();

    /** 内容高度变化后请求宿主重排（WebView 高度回调、RecyclerView item 重测量都走这里）。 */
    void invalidateSize();
}
