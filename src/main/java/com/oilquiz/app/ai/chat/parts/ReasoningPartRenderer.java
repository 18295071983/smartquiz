package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;
import com.oilquiz.app.ai.chat.ui.MarkdownWebView;
import com.oilquiz.app.ai.chat.ui.ReasoningShellView;

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

        // 思考块自己的容器（2026-11-18）：折叠头（图标 + 呼吸点 + 文字 + 箭头，32dp 胶囊）
        // + 正文容器（左侧 2dp 引导线）。原先这里是"裸 TextView 当折叠头 + 裸正文"，
        // 展开动画/悬停底色/展开指示器都没有可挂的地方（用户原话："没有自己的容器"）。
        final ReasoningShellView shell = new ReasoningShellView(ctx);

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
            bodyText = null;
            bodyWeb = web;
        } else {
            TextView tv = new TextView(ctx);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            // 横向留白/底部留白统一由容器 bodyHost 给，避免和旧的 12dp 双重缩进
            tv.setPadding(0, 0, 0, 0);
            tv.setTextIsSelectable(true);
            tv.setTextColor(secondaryTextColor(ctx));
            tv.setMovementMethod(LinkMovementMethod.getInstance());
            bodyText = tv;
            bodyWeb = null;
        }
        View bodyView = bodyWeb != null ? bodyWeb : bodyText;
        shell.setBodyView(bodyView); // 默认收起（思考中由绑定态展开、定稿后收起；与第一版一致）

        final Row row = new Row(shell, bodyText, bodyWeb);
        shell.setOnHeaderClickListener(v -> {
            boolean expanded = isExpanded(row, row.part != null && row.part.isStreaming());
            row.userExpandedOverride = !expanded;
            shell.setExpanded(!expanded, true);
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

        shell.setTag(row);
        return shell;
    }

    @Override
    public void bindView(View view, AssistantTurn.Part part, PartHost host) {
        Row row = view instanceof ReasoningShellView ? (Row) view.getTag() : null;
        if (row == null) {
            return;
        }
        String text = part.text != null ? part.text : "";
        if (part.id != null && !part.id.equals(row.partId)) {
            // 视图复用到另一条 part：展开选择/WebView 正文加载状态都属于上一条，不能带过来
            cancelAutoCollapse(row);
            row.partId = part.id;
            row.userExpandedOverride = null;
            row.webLoadedText = null;
            row.previewSource = null;
            row.previewText = null;
            row.lastStreaming = false;
        }
        row.part = part;
        row.host = host;
        final boolean streaming = part.isStreaming();

        row.baseLabel = streaming ? activeLabel(row, part) : doneLabel(row, part);
        boolean expanded = isExpanded(row, streaming);
        boolean justFinished = row.lastStreaming && !streaming;
        row.lastStreaming = streaming;
        applyLabel(row);
        // 收起态的"思考过程"摘要（2026-11-18）：复用 2026-10-25 落下的 ThinkingPreview 口径
        // （最新已完成段落首行，流式时退到上一段），折叠头上不只剩"深度思考"四个字。
        // 文本没变就不重算：ThinkingPreview 会过 JNI 取模板标签，流式每 120ms 重绑没必要跑。
        if (row.previewSource == null || !row.previewSource.equals(text)
                || row.previewStreaming != streaming) {
            row.previewSource = text;
            row.previewStreaming = streaming;
            row.previewText = com.oilquiz.app.ai.chat.ui.ThinkingPreview.of(text, streaming);
            row.shell.setSummary(row.previewText);
        }
        row.shell.setStreaming(streaming);
        row.shell.setCollapsible(!text.isEmpty());
        if (justFinished && row.userExpandedOverride == null) {
            // 对标业界（AI Elements Reasoning：streaming 自动展开、finished 自动收起）：
            // 结束这一帧先保持展开，留一拍让人看完最后一句，再播收起动画。
            row.shell.setExpanded(true, false);
            scheduleAutoCollapse(row);
        } else {
            cancelAutoCollapse(row);
            row.shell.setExpanded(expanded, false);
        }
        // 思考中让折叠头自带走光：模型还在产 token，这一行却是个静态句子，
        // 长思考时看着像卡死（2026-11-18）。动效实现统一在 UiFx（对话页禁止手写 Animator，
        // 见 UiHardcodeGuardTest），且幂等——流式每 120ms 重绑不会重启扫光。
        com.oilquiz.app.ui.UiFx.shimmer(row.shell.label(), streaming);

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
        // 2026-11-16：不再留写死的灰蓝兜底 —— ThemeColors.get 自己就会在缺 attr 时退回资源值。
        return ThemeColors.get(ctx, R.color.chat_v2_text_2);
    }

    /**
     * 活跃思考块的文案：优先用 {@code AssistantTurn} 算好的 {@code part.statusText}
     * （它参与 part 指纹，卡顿没有新 token 时文案推进也会触发重绑）；
     * 老数据/无该字段时退回按消息状态机现场算 {@link #stateLabel}。
     */
    private static String activeLabel(Row row, AssistantTurn.Part part) {
        if (part != null && part.statusText != null && !part.statusText.isEmpty()) {
            return part.statusText;
        }
        return stateLabel(row);
    }

    /**
     * 折叠头的"活动态"文案（回退路）：与底部生成状态行共用同一套多段文案
     * （{@link com.oilquiz.app.ai.chat.InferenceStatusPhrases}），按"本阶段已耗时"推进：
     * 理解问题 → 梳理思路 → 推敲关键点 → 验证推理 → 收敛结论（超长还有"仍在继续"）。
     *
     * <p>只有当前活跃的思考块（{@code part.isStreaming()}）才会走到这里；其它思考块走
     * {@link #doneLabel} 显示"已深度思考"，所以多个思考块时只有正在跑的那一块切换状态文字。</p>
     */
    private static String stateLabel(Row row) {
        ChatMessage msg = row.host != null ? row.host.message() : null;
        if (msg != null && msg.inferenceProgress != null) {
            if (msg.inferenceProgress.phaseText != null
                    && !msg.inferenceProgress.phaseText.isEmpty()) {
                return msg.inferenceProgress.phaseText;
            }
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
        return thinkingNow(row.shell.getContext());
    }

    private static String doneLabel(Row row, AssistantTurn.Part part) {
        String stopReason = row.host != null && row.host.message() != null
                ? row.host.message().stopReasonText : null;
        String suffix = (stopReason == null || stopReason.isEmpty()) ? "" : " · " + stopReason;
        if (part.durationMs > 0L) {
            return "深度思考 " + Math.round(part.durationMs / 1000.0) + " 秒" + suffix;
        }
        Context ctx = row.shell.getContext();
        if (ctx != null) {
            try {
                return ctx.getString(R.string.chat_v2_thought_done) + suffix;   // "已深度思考"
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

    /** 折叠头文字：展开态由容器里的箭头表达，不在文案前拼 ▾/▸（那是没有容器时的替代品）。 */
    private static void applyLabel(Row row) {
        row.shell.label().setText(row.baseLabel);
    }

    /** 定稿后留一拍再收起：让人看完最后一句（对标 AI Elements 的"结束自动收起"）。 */
    private static final long AUTO_COLLAPSE_MS = 700L;

    /**
     * 延迟收起用的 Handler：不用 {@code view.postDelayed}——part 视图在绑定那一刻可能还没 attach，
     * 未 attach 的 {@code post} 会进 View 的待执行队列、不保证按点跑（真机上偶尔"该收没收到"）。
     */
    private static final android.os.Handler MAIN = new android.os.Handler(
            android.os.Looper.getMainLooper());

    private static void scheduleAutoCollapse(Row row) {
        cancelAutoCollapse(row);
        final String partId = row.partId;
        row.collapseRunnable = () -> {
            row.collapseRunnable = null;
            if (row.userExpandedOverride != null) {
                return; // 用户点过了：以用户选择为准
            }
            if (partId == null || !partId.equals(row.partId)) {
                return; // 视图已被复用给另一条 part
            }
            if (row.part != null && row.part.isStreaming()) {
                return; // 又开始流式了
            }
            row.shell.setExpanded(false, true);
        };
        MAIN.postDelayed(row.collapseRunnable, AUTO_COLLAPSE_MS);
    }

    private static void cancelAutoCollapse(Row row) {
        if (row.collapseRunnable != null) {
            MAIN.removeCallbacks(row.collapseRunnable);
            row.collapseRunnable = null;
        }
    }

    @Override
    public void releaseView(View view) {
        // 无重资源；WebView 走 markdown 缓存/文档身份重置，避免复用串台。
        Row row = view instanceof ReasoningShellView ? (Row) view.getTag() : null;
        if (row != null) {
            cancelAutoCollapse(row);
            // 视图离树即停扫光，别把 Animator 带进列表复用池
            com.oilquiz.app.ui.UiFx.stopShimmer(row.shell.label());
            if (row.bodyWeb != null) {
                row.bodyWeb.resetForReuse();
            }
        }
    }

    /** 思考块的子视图句柄，挂在容器 tag 上，bindView 复用同实例时取回。 */
    private static final class Row {
        final ReasoningShellView shell;
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
        /** 上一绑的流式态：用于识别"流式→定稿"这一帧并播收起动画。 */
        boolean lastStreaming;
        /** 定稿后的延迟收起任务（用户点击/视图复用/重新流式时取消）。 */
        Runnable collapseRunnable;
        /** 最近一次真正喂给 WebView 的文本与流式态，用于同内容复用早退。 */
        String webLoadedText;
        boolean webLoadedStreaming;
        /** 收起态摘要的缓存（文本没变不重算 ThinkingPreview）。 */
        String previewSource;
        boolean previewStreaming;
        String previewText;

        Row(ReasoningShellView shell, TextView bodyText, MarkdownWebView bodyWeb) {
            this.shell = shell;
            this.bodyText = bodyText;
            this.bodyWeb = bodyWeb;
        }
    }

    private static final String HEADER_PLAIN = "深度思考";
    /** 还没有状态可读时的占位（与 {@code chat_v2_thinking_now} 资源同形）。 */
    private static final String HEADER_STREAMING = "正在思考…";
}
