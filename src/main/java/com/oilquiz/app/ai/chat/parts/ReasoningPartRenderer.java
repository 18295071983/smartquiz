package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;
import com.oilquiz.app.ai.chat.ui.MarkdownWebView;

/**
 * 推理/思考 part 渲染器（parts 架构升级 P2，2026-10-21）。
 *
 * <p><b>2026-10-25 定稿（第二版）：折叠头只显示状态；思考正文与回答正文同一条流式渲染通路。</b></p>
 * <ul>
 *   <li><b>折叠头</b>：只挂状态——思考中是状态机的阶段（"正在思考..."），思考结束换成
 *       "已深度思考 X 秒 / 已深度思考"。文字直接切换，不做滑入动画。</li>
 *   <li><b>正文区（思考正文，不是回答正文）</b>：
 *       <ul>
 *         <li>{@code web_markdown_render} + {@link ReasoningWebRenderFlag} 都开（出厂档）→
 *             {@link MarkdownWebView}：流式期 {@code setContent(text, true)}，与主气泡同一套
 *             {@code MarkdownStreamSplitter} 块级尾巴（{@code markdown_stream_tail}）；定稿
 *             {@code setContent(text, false)} 整篇重载。思考中默认展开，正文随 token 逐块长出来。</li>
 *         <li><b>排版必须与回答正文区分</b>：同一字形同字号时用户分不清哪段是思考（2026-10-25
 *             真机反馈"一个字体，我无法区分"）。WebView 走 {@code qz-reasoning} 次级排版
 *             （13px / {@code --muted} / 左侧竖线缩进），原生回退路同样 13sp + 次级文字色。</li>
 *         <li>任一开关关掉 → 退回 {@code TextView}：流式期纯 {@code setText} 整块刷新，
 *             定稿才交宿主 markdown（2026-10-25 第一版口径，作为回退路保留）。</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p><b>收起时不跑 WebView</b>：思考定稿后默认收起、历史消息里的思考也是收起态。WebView 正文只在
 * "展开"时 {@code setContent}（{@code Row#webLoadedText} 记录已加载内容，同内容不重复加载），
 * 收起期间只更新 {@code part.text}，点折叠头展开才加载——否则历史列表滚动会为每条收起态思考
 * 白建一次页面。展开自愈逻辑对原生正文保持不变。</p>
 *
 * <p><b>状态机集成</b>：思考段到底"还在思考"还是"已经进入回答"，以引擎状态机为准——
 * {@code AIChatActivity} 按思考 / 正文两类 token 把阶段写进 {@code msg.inferenceProgress.phase}，
 * {@code AssistantTurn} 据此决定 reasoning part 是否还打 {@code streaming}。所以正文一开始，
 * 折叠头立刻换成"已深度思考"，不用等整条消息结束。</p>
 */
public final class ReasoningPartRenderer implements PartRenderer {

    @Override
    public boolean canRender(AssistantTurn.Part part) {
        return part != null && AssistantTurn.Part.TYPE_REASONING.equals(part.type);
    }

    @Override
    public View createView(PartHost host, ViewGroup parent) {
        Context ctx = host.context();
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f,
                ctx.getResources().getDisplayMetrics());

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 折叠头：只显示状态（正在思考... / 已深度思考 X 秒），不承载内容、不做动画
        final TextView header = new TextView(ctx);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        header.setPadding(pad, pad, pad, pad);
        header.setText(HEADER_PLAIN);
        header.setSingleLine(true);
        header.setEllipsize(TextUtils.TruncateAt.END);

        // 正文区：默认与回答正文同一条 WebView 流式通路；开关关掉时退回原生 TextView。
        final boolean webBody = host.webMarkdownEnabled() && ReasoningWebRenderFlag.enabled(ctx);
        final TextView bodyText;
        final MarkdownWebView bodyWeb;
        if (webBody) {
            MarkdownWebView web = new MarkdownWebView(ctx);
            // 思考正文用次级排版（13px / --muted / 左侧竖线缩进），与回答正文 15px 主排版区分开：
            // 两个 WebView 若同字号同色，真机上用户分不清哪段是思考（2026-10-25 反馈）。
            web.setReasoningStyle(true);
            // 与 TextPartRenderer 同口径：WebView 内部按内容高回调，宿主据此重排 item。
            web.setOnContentHeightChanged(heightPx -> host.invalidateSize());
            web.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            bodyText = null;
            bodyWeb = web;
        } else {
            TextView tv = new TextView(ctx);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            tv.setPadding(pad, 0, pad, pad);
            tv.setTextIsSelectable(true);
            tv.setTextColor(secondaryTextColor(ctx));
            tv.setMovementMethod(LinkMovementMethod.getInstance());
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            bodyText = tv;
            bodyWeb = null;
        }
        View bodyView = bodyWeb != null ? bodyWeb : bodyText;
        // 默认收起（思考中由绑定态展开、定稿后收起；与第一版一致）
        bodyView.setVisibility(View.GONE);

        final Row row = new Row(header, bodyText, bodyWeb);
        header.setOnClickListener(v -> {
            boolean expanded = isExpanded(row, row.part != null && row.part.isStreaming());
            row.userExpandedOverride = !expanded;
            applyBodyVisibility(row, !expanded);
            applyHeader(row, !expanded);
            if (!expanded) {
                // 展开这一刻才真正渲染正文：
                // - WebView：收起期间没 load（见 Row#webLoadedText），这里补一次；
                // - 原生：沿用"折叠期间异步渲染结果可能因代次过期被丢弃"的自愈重发。
                AssistantTurn.Part p = row.part;
                if (row.bodyWeb != null) {
                    if (p != null) {
                        loadWebBody(row, p.text != null ? p.text : "", p.isStreaming());
                    }
                } else if (row.bodyText != null) {
                    PartHost h = row.host;
                    if (h != null && p != null && row.bodyText.getText().length() == 0
                            && p.text != null && !p.text.isEmpty()) {
                        h.renderMarkdown(row.bodyText, p.text);
                    }
                }
            }
        });

        root.addView(header);
        root.addView(bodyView);
        root.setTag(row);
        return root;
    }

    @Override
    public void bindView(View view, AssistantTurn.Part part, PartHost host) {
        Row row = view instanceof LinearLayout ? (Row) ((LinearLayout) view).getTag() : null;
        if (row == null) {
            return;
        }
        String text = part.text != null ? part.text : "";
        if (part.id != null && !part.id.equals(row.partId)) {
            // 视图复用到另一条 part：展开选择/WebView 正文加载状态都属于上一条，不能带过来
            row.partId = part.id;
            row.userExpandedOverride = null;
            row.webLoadedText = null;
        }
        row.part = part;
        row.host = host;
        final boolean streaming = part.isStreaming();

        row.baseLabel = streaming ? stateLabel(row) : doneLabel(row, part);
        boolean expanded = isExpanded(row, streaming);
        applyBodyVisibility(row, expanded);
        applyHeader(row, expanded);

        if (row.bodyWeb != null) {
            // WebView 正文：展开才上屏（收起期间不跑 WebView，历史列表滚动不为收起态思考白建页面）；
            // 流式期每 tick 走块级尾巴，定稿整篇重载一次。
            if (expanded) {
                loadWebBody(row, text, streaming);
            }
        } else if (row.bodyText != null) {
            // 原生回退路：流式期整块 setText，定稿走宿主 markdown（与第一版逐字一致）。
            if (streaming) {
                row.bodyText.setText(text);
            } else {
                host.renderMarkdown(row.bodyText, text);
            }
        }
    }

    /**
     * WebView 正文加载：内容或流式态变化才 {@code setContent}，重复绑定（同内容同态）直接早退，
     * 避免 120ms 思考刷新里做无效整篇重载；收起期间未加载过的内容在点开时由这里补上。
     */
    private static void loadWebBody(Row row, String text, boolean streaming) {
        MarkdownWebView web = row.bodyWeb;
        if (web == null) {
            return;
        }
        String t = text != null ? text : "";
        if (t.equals(row.webLoadedText) && streaming == row.webLoadedStreaming) {
            return;
        }
        PartHost h = row.host;
        web.setNight(h != null && h.dark());
        web.setThemeColors(h != null ? h.markdownThemeVars() : "");
        web.setContent(t, streaming);
        row.webLoadedText = t;
        row.webLoadedStreaming = streaming;
    }

    // ==================== 文案 / 可见性 ====================

    /** 原生回退路的次级文字色（与 Web 态 {@code --muted} 同一个主题 token：chat_v2_text_2）。 */
    private static int secondaryTextColor(Context ctx) {
        try {
            return androidx.core.content.ContextCompat.getColor(ctx, R.color.chat_v2_text_2);
        } catch (Throwable t) {
            return 0xFF8A8F98;
        }
    }

    /**
     * 折叠头的"活动态"文案：与底部生成状态行共用同一套多段文案
     * （{@link com.oilquiz.app.ai.chat.InferenceStatusPhrases}），按"本阶段已耗时"推进：
     * 理解问题 → 梳理思路 → 推敲关键点 → 验证推理 → 收敛结论（超长还有"仍在继续"）。
     *
     * <p>只有当前活跃的思考块（{@code part.isStreaming()}）才会走到这里；其它思考块走
     * {@link #doneLabel} 显示"已深度思考"，所以多个思考块时只有正在跑的那一块切换状态文字。</p>
     */
    private static String stateLabel(Row row) {
        ChatMessage msg = row.host != null ? row.host.message() : null;
        if (msg != null && msg.inferenceProgress != null) {
            ChatMessage.InferencePhase phase = msg.inferenceProgress.phase;
            long start = msg.inferenceProgress.phaseStartTime;
            if (start <= 0 && msg.timestamp > 0) start = msg.timestamp;
            if (start > 0) {
                // 多步深度思考不报轮号：轮次/耗时由各轮折叠块自身的"已深度思考 X 秒"承载，
                // 这里只给当前活跃块的阶段文案（业界 Progress Steps 口径）
                String phrase = com.oilquiz.app.ai.chat.InferenceStatusPhrases
                        .phrase(phase, System.currentTimeMillis() - start);
                if (phrase != null) return phrase;
            }
            String label = com.oilquiz.app.ai.chat.InferenceStatusLabel.shortLabel(phase);
            if (label != null) return label;
        }
        return thinkingNow(row.header.getContext());
    }

    private static String doneLabel(Row row, AssistantTurn.Part part) {
        if (part.durationMs > 0L) {
            return "已深度思考 " + Math.round(part.durationMs / 1000.0) + " 秒";
        }
        Context ctx = row.header.getContext();
        if (ctx != null) {
            try {
                return ctx.getString(R.string.chat_v2_thought_done);   // "已深度思考"
            } catch (Throwable ignored) {
            }
        }
        return HEADER_PLAIN;
    }

    private static String thinkingNow(Context ctx) {
        if (ctx == null) return HEADER_STREAMING;
        try {
            return ctx.getString(R.string.chat_v2_thinking_now);
        } catch (Throwable t) {
            return HEADER_STREAMING;
        }
    }

    private static boolean isExpanded(Row row, boolean streaming) {
        return row.userExpandedOverride != null ? row.userExpandedOverride : streaming;
    }

    private static View bodyView(Row row) {
        return row.bodyWeb != null ? row.bodyWeb : row.bodyText;
    }

    private static void applyBodyVisibility(Row row, boolean expanded) {
        View body = bodyView(row);
        if (body != null) {
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
    }

    private static void applyHeader(Row row, boolean expanded) {
        row.header.setText((expanded ? "▾ " : "▸ ") + row.baseLabel);
    }

    @Override
    public void releaseView(View view) {
        // 无重资源；WebView 走 markdown 缓存/文档身份重置，避免复用串台。
        Row row = view instanceof LinearLayout ? (Row) view.getTag() : null;
        if (row != null && row.bodyWeb != null) {
            row.bodyWeb.resetForReuse();
        }
    }

    /** 折叠行的子视图句柄，挂在 root 的 tag 上，bindView 复用同实例时取回。 */
    private static final class Row {
        final TextView header;
        /** 原生正文；web 正文时 null。 */
        final TextView bodyText;
        /** WebView 正文；原生正文时 null。 */
        final MarkdownWebView bodyWeb;
        String baseLabel = HEADER_PLAIN;
        /** 当前绑定的 part id：换了一条 part 就重置展开选择与正文加载状态。 */
        String partId;
        /** 用户点折叠头的显式选择；null = 跟随状态（思考中展开、定稿收起）。 */
        Boolean userExpandedOverride;
        AssistantTurn.Part part;
        PartHost host;
        /** 最近一次真正喂给 WebView 的文本与流式态，用于同内容复用早退。 */
        String webLoadedText;
        boolean webLoadedStreaming;

        Row(TextView header, TextView bodyText, MarkdownWebView bodyWeb) {
            this.header = header;
            this.bodyText = bodyText;
            this.bodyWeb = bodyWeb;
        }
    }

    private static final String HEADER_PLAIN = "深度思考";
    /** 还没有状态可读时的占位（与 {@code chat_v2_thinking_now} 资源同形）。 */
    private static final String HEADER_STREAMING = "正在思考…";
}
