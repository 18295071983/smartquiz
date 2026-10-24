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

/**
 * 推理/思考 part 渲染器（parts 架构升级 P2，2026-10-21）。
 *
 * <p><b>2026-10-25 定稿：折叠头只显示状态；思考正文实时整块刷新（不做动画、不做打字机）。</b></p>
 * <ul>
 *   <li><b>折叠头</b>：只挂状态——思考中是状态机的阶段（"正在思考..."），思考结束换成
 *       "已深度思考 X 秒 / 已深度思考"。文字直接切换，不做滑入动画。</li>
 *   <li><b>正文区（思考正文，不是回答正文）</b>：思考中展开，把 {@code msg.thinkingContent}
 *       直接铺出来（每次到达新内容就整块刷新，纯 {@code setText}）；思考结束自动收起，
 *       点折叠头展开全文（那时才交宿主 markdown 渲染）。</li>
 * </ul>
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

        // 思考正文：思考中整块实时刷新，定稿收起，点开看全文
        final TextView body = new TextView(ctx);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        body.setPadding(pad, 0, pad, pad);
        body.setTextIsSelectable(true);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        body.setVisibility(View.GONE);

        final Row row = new Row(header, body);
        header.setOnClickListener(v -> {
            boolean expanded = isExpanded(row, row.part != null && row.part.isStreaming());
            row.userExpandedOverride = !expanded;
            applyBodyVisibility(row, !expanded);
            applyHeader(row, !expanded);
            if (!expanded) {
                // 自愈：折叠期间异步渲染结果可能因代次过期被丢弃（视图当时未 attach 等），
                // 展开这一刻若正文仍为空就重发一次，避免"点开是空的"。
                PartHost h = row.host;
                AssistantTurn.Part p = row.part;
                if (h != null && p != null && body.getText().length() == 0
                        && p.text != null && !p.text.isEmpty()) {
                    h.renderMarkdown(body, p.text);
                }
            }
        });

        root.addView(header);
        root.addView(body);
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
            // 视图复用到另一条 part：展开选择属于上一条，不能带过来
            row.partId = part.id;
            row.userExpandedOverride = null;
        }
        row.part = part;
        row.host = host;
        final boolean streaming = part.isStreaming();

        if (streaming) {
            // 思考中：直接铺思考正文（整块刷新），不排队 markdown、不做动画
            row.body.setText(text);
            row.baseLabel = stateLabel(row);
        } else {
            // 定稿：全文交给宿主的 markdown 原语（后台渲染 + 链接 + 图片 + 主题）
            host.renderMarkdown(row.body, text);
            row.baseLabel = doneLabel(row, part);
        }

        boolean expanded = isExpanded(row, streaming);
        applyBodyVisibility(row, expanded);
        applyHeader(row, expanded);
    }

    // ==================== 文案 / 可见性 ====================

    /** 状态机状态文案（消息当前的推理阶段，取不到退回"正在思考…"）。 */
    private static String stateLabel(Row row) {
        ChatMessage msg = row.host != null ? row.host.message() : null;
        if (msg != null && msg.inferenceProgress != null
                && msg.inferenceProgress.phase != null) {
            String text = msg.inferenceProgress.phase.getDisplayText();
            if (text != null && !text.isEmpty()) return text;
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

    private static void applyBodyVisibility(Row row, boolean expanded) {
        row.body.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    private static void applyHeader(Row row, boolean expanded) {
        row.header.setText((expanded ? "▾ " : "▸ ") + row.baseLabel);
    }

    @Override
    public void releaseView(View view) {
        // 无重资源；视图随容器移除即可。
    }

    /** 折叠行的两个子视图句柄，挂在 root 的 tag 上，bindView 复用同实例时取回。 */
    private static final class Row {
        final TextView header;
        final TextView body;
        String baseLabel = HEADER_PLAIN;
        /** 当前绑定的 part id：换了一条 part 就重置展开选择。 */
        String partId;
        /** 用户点折叠头的显式选择；null = 跟随状态（思考中展开、定稿收起）。 */
        Boolean userExpandedOverride;
        AssistantTurn.Part part;
        PartHost host;
        Row(TextView header, TextView body) {
            this.header = header;
            this.body = body;
        }
    }

    private static final String HEADER_PLAIN = "深度思考";
    /** 还没有状态可读时的占位（与 {@code chat_v2_thinking_now} 资源同形）。 */
    private static final String HEADER_STREAMING = "正在思考…";
}
