package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.component.ComponentLifecycle;
import com.oilquiz.app.ai.chat.component.ComponentRegistry;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;
import com.oilquiz.app.ai.chat.turn.AssistantTurn.Part;
import com.oilquiz.app.ai.chat.ui.ReasoningShellView;
import com.oilquiz.app.theme.ThemeColors;

import java.util.ArrayList;
import java.util.List;

/**
 * 思考手风琴（2026-11-18，对标 assistant-ui 的 GroupedParts / Telerik 的 Chain of Thought）。
 *
 * <p><b>为什么需要</b>：以前一轮里"思考 → 工具 → 思考 → 工具"会变成 4 个各自独立的折叠头，
 * 消息流被折叠头切得支离破碎，用户原话"part 折叠头太死板"。业界做法是**一轮只给一个折叠头**，
 * 里面按时间顺序列思考段与工具行。本渲染器就是那个头 + 身体。</p>
 *
 * <p><b>数据从哪来</b>：{@link AssistantTurn#coalesceThinking(List)} 在渲染前把相邻的
 * reasoning / tool_group 合并成一个 {@link Part#TYPE_THINKING}（成员在 {@code part.group}），
 * 所以引擎的流式 id 寻址完全不用改。</p>
 *
 * <p><b>视觉层级</b>：头（{@link ReasoningShellView}，32dp 胶囊）+ 折叠体（左侧 2dp 引导线）
 * + 常显产物区（图片/图表/文件卡不跟着折叠藏掉）。生成态：主色文字 + 呼吸点 + 流光；
 * 结束态：回次级色，留一拍后自动收起。</p>
 */
public final class ThinkingPartRenderer implements PartRenderer {

    /** 定稿后留一拍再收起（与 ReasoningPartRenderer 同一口径）。 */
    private static final long AUTO_COLLAPSE_MS = 700L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final ToolCallPartRenderer.ComponentFactory productFactory;

    public ThinkingPartRenderer() {
        this((ctx, data) -> ComponentRegistry.getInstance().render(ctx, data));
    }

    ThinkingPartRenderer(ToolCallPartRenderer.ComponentFactory productFactory) {
        this.productFactory = productFactory;
    }

    @Override
    public boolean canRender(Part part) {
        return part != null && Part.TYPE_THINKING.equals(part.type);
    }

    @Override
    public View createView(PartHost host, ViewGroup parent) {
        if (AgentPanelWebFlag.enabled(host.context()) && AgentPanelBudget.acquire()) {
            // PoC：执行活动区改用内嵌 WebView 组件渲染（同一份 JSON 载荷，页面自带交互与动效）。
            // 额度由 AgentPanelBudget 兜底：同屏 WebView 超过 MAX_LIVE 就退回原生，避免长会话
            // 每条消息一个 Chromium 实例把内存/帧率拖死。
            com.oilquiz.app.ai.chat.ui.AgentPanelView panel =
                    new com.oilquiz.app.ai.chat.ui.AgentPanelView(host.context(), true);
            panel.setOnHeightChanged(heightPx -> host.invalidateSize());
            return panel;
        }
        ReasoningShellView shell = new ReasoningShellView(host.context());
        shell.bodyColumn();
        shell.trailingColumn();
        shell.setTag(new Holder());
        return shell;
    }

    @Override
    public void bindView(View view, Part part, PartHost host) {
        if (view instanceof com.oilquiz.app.ai.chat.ui.AgentPanelView) {
            List<Part> members = part.group != null ? part.group : new ArrayList<>();
            ((com.oilquiz.app.ai.chat.ui.AgentPanelView) view).update(
                    AgentPanelPayload.of(host.context(), members, host.dark()));
            return;
        }
        if (!(view instanceof ReasoningShellView)) {
            return;
        }
        ReasoningShellView shell = (ReasoningShellView) view;
        Holder h = view.getTag() instanceof Holder ? (Holder) view.getTag() : new Holder();
        view.setTag(h);
        if (!TextUtils.equals(part.id, h.partId)) {
            cancelAutoCollapse(h);
            h.partId = part.id;
            h.override = null;
            h.lastActive = false;
        }

        List<Part> members = part.group != null ? part.group : new ArrayList<>();
        Context ctx = host.context();

        ThinkingSummary st = ThinkingSummary.of(members);
        shell.label().setText(st.label(ctx));
        shell.setStreaming(st.active);
        shell.setCollapsible(true);
        shell.setSummary(st.summary());
        com.oilquiz.app.ui.UiFx.shimmer(shell.label(), st.active);

        bindBody(ctx, shell, members, host, h);
        bindProducts(shell, members, part, host);
        bindExpanded(shell, h, st.active);
        shell.setOnHeaderClickListener(v -> {
            boolean open = shell.isExpanded();
            h.override = !open;
            cancelAutoCollapse(h);
            shell.setExpanded(!open, true);
        });
    }

    // ---------------- 正文 ----------------

    private void bindBody(Context ctx, ReasoningShellView shell, List<Part> members, PartHost host,
                          Holder h) {
        LinearLayout body = shell.bodyColumn();
        // 工具行里可能是原生组件（shell 命令卡等）：重排前先深度 release，别让资源挂在旧子树上
        releaseChildren(body);
        h.seenReasoningIds.clear();
        int toolIndex = 0;
        for (Part m : members) {
            if (m == null) {
                continue;
            }
            if (Part.TYPE_REASONING.equals(m.type)) {
                body.addView(reasoningRow(ctx, m, host, h));
                h.seenReasoningIds.add(m.id);
            } else if (Part.TYPE_TOOL_GROUP.equals(m.type) && m.group != null) {
                for (Part t : m.group) {
                    if (t == null) {
                        continue;
                    }
                    body.addView(ToolActivityRowView.build(ctx, body, t, toolIndex++, host));
                }
            }
        }
        // 清掉已经不在成员里的思考视图缓存（视图复用给别的 part 时不串台）
        h.reasoningViews.keySet().removeIf(id -> !h.seenReasoningIds.contains(id));
    }

    /**
     * 一段思考的正文：原生 TextView + 宿主 markdown 渲染（流式期间 setText 快路径，定稿才渲染一次）。
     *
     * <p>这里刻意不再套 {@code MarkdownWebView}：手风琴里可能一次并列 3–4 段思考，每段一个
     * WebView 的回收/高度/内存成本不划算；用宿主的 Markwon 通路既保留代码/公式格式，又不引
     * WebView。TextView 按成员 id 缓存复用，流式每 tick 只 setText，定稿只 renderMarkdown 一次。</p>
     */
    private TextView reasoningRow(Context ctx, Part m, PartHost host, Holder h) {
        ReasoningCell cell = h.reasoningViews.get(m.id);
        if (cell == null) {
            TextView tv = new TextView(ctx);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            tv.setLineSpacing(0f, 1.6f);
            tv.setTextColor(ThemeColors.get(ctx, R.color.chat_v2_text_2));
            tv.setTextIsSelectable(true);
            tv.setPadding(0, dimen(ctx, R.dimen.space_4), 0, dimen(ctx, R.dimen.space_8));
            cell = new ReasoningCell(tv);
            h.reasoningViews.put(m.id, cell);
        }
        String text = m.text == null ? "" : m.text;
        if (m.isStreaming()) {
            cell.view.setText(text); // 流式快路径：不走 markdown（与原生回退路同口径）
            cell.rendered = null;
        } else if (!text.equals(cell.rendered)) {
            host.renderMarkdown(cell.view, text);
            cell.rendered = text;
        }
        return cell.view;
    }

    // ---------------- 产物（常显） ----------------

    private void bindProducts(ReasoningShellView shell, List<Part> members, Part part, PartHost host) {
        List<ComponentData> products = new ArrayList<>();
        for (Part m : members) {
            if (m == null || !Part.TYPE_TOOL_GROUP.equals(m.type) || m.group == null) {
                continue;
            }
            for (Part t : m.group) {
                if (t != null && t.products != null) {
                    products.addAll(t.products);
                }
            }
        }
        LinearLayout trailing = shell.trailingColumn();
        String digest = ProductCards.digestOf(products);
        if (ProductCards.unchanged(trailing, digest)) {
            return;
        }
        releaseChildren(trailing);
        ProductCards.mountAll(productFactory, trailing, products, part.id + ":prod:", host);
        ProductCards.markMounted(trailing, digest);
    }

    // ---------------- 展开 / 收起 ----------------

    private void bindExpanded(ReasoningShellView shell, Holder h, boolean active) {
        boolean justFinished = h.lastActive && !active;
        h.lastActive = active;
        // 用户点过折叠头就以用户为准，**包括流式期间**：否则事件一批批进来，每批都把用户
        // 刚收起的块又自动撑开——这就是"不好折叠"的直接原因。
        if (h.override != null) {
            cancelAutoCollapse(h);
            shell.setExpanded(h.override, false);
            return;
        }
        if (active) {
            cancelAutoCollapse(h);
            shell.setExpanded(true, false);
            return;
        }
        if (justFinished) {
            // 先保持展开让人看完最后一行，再播收起（同 ReasoningPartRenderer 的口径）
            shell.setExpanded(true, false);
            cancelAutoCollapse(h);
            final String partId = h.partId;
            h.collapseRunnable = () -> {
                h.collapseRunnable = null;
                if (h.override != null || !TextUtils.equals(partId, h.partId) || h.lastActive) {
                    return;
                }
                shell.setExpanded(false, true);
            };
            MAIN.postDelayed(h.collapseRunnable, AUTO_COLLAPSE_MS);
            return;
        }
        cancelAutoCollapse(h);
        shell.setExpanded(false, false);
    }

    private static void cancelAutoCollapse(Holder h) {
        if (h.collapseRunnable != null) {
            MAIN.removeCallbacks(h.collapseRunnable);
            h.collapseRunnable = null;
        }
    }

    @Override
    public void releaseView(View view) {
        if (view instanceof com.oilquiz.app.ai.chat.ui.AgentPanelView) {
            ((com.oilquiz.app.ai.chat.ui.AgentPanelView) view).release();
            return;
        }
        if (view instanceof ReasoningShellView) {
            Holder h = view.getTag() instanceof Holder ? (Holder) view.getTag() : null;
            if (h != null) {
                cancelAutoCollapse(h);
            }
            com.oilquiz.app.ui.UiFx.stopShimmer(((ReasoningShellView) view).label());
            releaseChildren(((ReasoningShellView) view).trailingColumn());
        }
    }

    private static void releaseChildren(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            ComponentLifecycle.release(group.getChildAt(i));
        }
        group.removeAllViews();
    }

    private static int dimen(Context ctx, int resId) {
        return ctx.getResources().getDimensionPixelSize(resId);
    }

    /** 视图级的可变状态（用户展开选择 / 上一帧是否活跃 / 待执行的自动收起）。 */
    private static final class Holder {
        String partId;
        Boolean override;
        boolean lastActive;
        Runnable collapseRunnable;
        /** 思考段正文视图缓存：member id → 视图 + 已渲染内容（流式不改 markdown）。 */
        final java.util.Map<String, ReasoningCell> reasoningViews = new java.util.HashMap<>();
        final java.util.Set<String> seenReasoningIds = new java.util.HashSet<>();
    }

    private static final class ReasoningCell {
        final TextView view;
        String rendered;

        ReasoningCell(TextView view) {
            this.view = view;
        }
    }
}
