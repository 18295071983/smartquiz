package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.component.ComponentLifecycle;
import com.oilquiz.app.ai.chat.component.ComponentRegistry;
import com.oilquiz.app.ai.chat.turn.AssistantTurn;
import com.oilquiz.app.ai.chat.turn.AssistantTurn.Part;

import java.util.List;

/**
 * 工具组折叠卡（parts §11.1）：把连续多工具渲成一行"已运行 N 个工具"，点击展开列每个工具卡。
 *
 * <p>成员卡复用 {@link ComponentRegistry}（{@code tool_call} 组件 → ToolCallCardView），
 * 与单工具卡同一渲染源；展开态走 {@link PartHost#toolGroupExpanded}/{@code toggleToolGroup}
 * （宿主存于 {@code ChatMessage}）。数据来自 {@link Part#group}（每个成员已并入 result）。</p>
 */
public final class ToolGroupPartRenderer implements PartRenderer {

    /** 运行结束留一拍再收起（与思考折叠同一口径）。 */
    private static final long AUTO_COLLAPSE_MS = 700L;
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** 产物卡渲染工厂（成员行已改走 {@link ToolActivityRowView}，不再经组件工厂出工具卡）。 */
    private final ToolCallPartRenderer.ComponentFactory productFactory;

    public ToolGroupPartRenderer() {
        this((ctx, data) -> ComponentRegistry.getInstance().render(ctx, data));
    }

    ToolGroupPartRenderer(ToolCallPartRenderer.ComponentFactory productFactory) {
        this.productFactory = productFactory;
    }

    @Override
    public boolean canRender(Part part) {
        return part != null && Part.TYPE_TOOL_GROUP.equals(part.type);
    }

    @Override
    public View createView(PartHost host, ViewGroup parent) {
        Context ctx = host.context();
        int pad = dp(ctx, 6);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        // 与上一条 part 留 4dp：按类型分折后思考/工具会交替出现，贴在一起会看成同一块
        rootLp.topMargin = dp(ctx, 4);
        root.setLayoutParams(rootLp);

        // 折叠头：整行可点，左侧是"还在动"的三颗呼吸点（只在运行中出现）。
        // 为什么把 padding/clickable 从 TextView 挪到这一行：点阵是独立自绘 View（LiveDotsView），
        // 塞不进 TextView；点击目标也应该是"点阵 + 文字"整行，而不是只剩文字那一截。
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.setPadding(pad, pad, pad, pad);
        head.setClickable(true);
        head.setFocusable(true);

        com.oilquiz.app.ui.widget.LiveDotsView dots =
                new com.oilquiz.app.ui.widget.LiveDotsView(ctx);
        // 非运行态 GONE（而不是 INVISIBLE）：静态时保持和改造前一模一样的左侧留白，
        // 不能因为"加动效"让「已运行 N 个工具」这行永久右移 27dp（回归面最小化）。
        dots.setVisibility(View.GONE);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsLp.setMarginEnd(dp(ctx, 6));
        head.addView(dots, dotsLp);

        TextView header = new TextView(ctx);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        // 工具侧文案可能追加"正在干什么"（文件名/查询词）：单行省略，避免长目标把卡片撑高
        header.setSingleLine(true);
        header.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(header, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        // 产物区与折叠体分离：对齐 legacy 的 chat_v2_tool_card_products（挂在 expanded 判断之外）。
        // 若把产物塞进 body，折叠组卡时会连产物一起藏掉，用户必须点开才看得到图/表——那是回归。
        LinearLayout products = new LinearLayout(ctx);
        products.setOrientation(LinearLayout.VERTICAL);

        root.addView(head);
        root.addView(body);
        root.addView(products);
        root.setTag(new Views(head, header, body, products, dots));
        return root;
    }

    /** 工具名：优先 PC 侧发来的原始名，过一遍展示名表，空则"工具" */
    private static String nameOf(Part p) {
        String raw = p == null || p.toolName == null ? "" : p.toolName.trim();
        if (raw.isEmpty()) return "工具";
        try {
            String display = com.oilquiz.app.ai.chat.ToolMetaRegistry.displayName(raw);
            return display == null || display.isEmpty() ? raw : display;
        } catch (Throwable t) {
            return raw;
        }
    }

    /**
     * 多个工具的头部文案：按出现顺序取**去重后的显示名**，最多 {@code maxNames} 个；
     * 超出时追加"等 N 个工具"（N 为不同工具名个数）。
     *
     * <p>单工具一直显示"读取文件"这类名字，多工具却只显示"已运行 2 个工具"，
     * 用户无法从折叠头知道跑了什么（2026-11-18 反馈）。显示名统一走
     * {@link com.oilquiz.app.ai.chat.ToolMetaRegistry#displayName(String)}，和单工具同一来源。</p>
     */
    private static String namesSummary(java.util.List<Part> members, int maxNames) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (Part m : members) {
            if (m != null) {
                names.add(nameOf(m));
            }
        }
        if (names.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String name : names) {
            if (shown > 0) sb.append('、');
            sb.append(name);
            shown++;
            if (shown >= Math.max(1, maxNames)) break;
        }
        if (names.size() > shown) {
            sb.append(" 等 ").append(names.size()).append(" 个工具");
        }
        return sb.toString();
    }

    @Override
    public void bindView(View view, Part part, PartHost host) {
        Views v = view instanceof LinearLayout ? (Views) ((LinearLayout) view).getTag() : null;
        List<Part> members = part.group;
        if (v == null || members == null || members.isEmpty()) {
            return;
        }
        Context ctx = host.context();
        final String groupId = part.id;

        boolean anyRunning = false;
        for (Part m : members) {
            if (m != null && "running".equals(ToolCallPartRenderer.ToolCardProps
                    .normalizeStatus(m.status))) {
                anyRunning = true;
                break;
            }
        }
        int n = members.size();
        if (anyRunning) {
            // 工具侧「思考」文案：AssistantTurn 按"本轮第一个工具开始到现在"的耗时算好并放进
            // part.statusText（参与指纹）；这里只负责显示。旧数据没有该字段时退回"名字摘要"。
            // 运行时不再带 ⏳ 前缀：左侧的呼吸点就是"进行中"的信号（项目"状态去 emoji"口径），
            // 两者叠在一起既重复又显得吵。
            String phrase = part.statusText;
            if (phrase != null && !phrase.isEmpty()) {
                v.header.setText(phrase);
            } else if (n == 1) {
                v.header.setText("正在运行 " + nameOf(members.get(0)));
            } else {
                v.header.setText("正在运行 " + namesSummary(members, 2));
            }
            // 活着信号：三颗点（形状）+ 文字流光（同一行自带走光）。两个都幂等，
            // 流式每 120ms 重绑一次不会重启动画、也不会叠出多个 Animator。
            v.dots.setDotColor(com.oilquiz.app.ai.chat.component.ComponentColors.accent(ctx));
            v.dots.setVisibility(View.VISIBLE);
            v.dots.start();
            com.oilquiz.app.ui.UiFx.shimmer(v.header, true);
        } else {
            // 单工具时直接显示工具名：否则组卡头部只有"已运行 1 个工具"，
            // 用户看到的就是"这个工具没名字"（成员行沿用 legacy 紧凑行文案）。
            // 多工具同样给名字摘要（"读取文件、查看图片 …"）——单工具能显示名字，
            // 多个工具就不该只剩"已运行 N 个工具"（2026-11-18，用户反馈）。
            // 2026-11-18：不再带 🔧 前缀——按类型分折后这里和思考折叠并排，emoji 只在这个头上有，
            // 视觉上比"呼吸点/图标"更吵；状态已经由底色和右侧行状态表达。
            v.header.setText(n == 1 ? nameOf(members.get(0)) : namesSummary(members, 3));
            v.dots.stop();
            v.dots.setVisibility(View.GONE);
            com.oilquiz.app.ui.UiFx.stopShimmer(v.header);
        }

        // 成员行（对齐 legacy 紧凑行 item_chat_v2_tool_row，不是整张 ToolCallCardView），
        // 折叠也预建，展开即时；产物进常显区。
        releaseChildren(v.body);
        // 产物区：只在产物真的变了才重建（成员行每绑都重排，但 WebView 类产物重建会丢
        // 输入焦点/滚动/播放进度，见 ProductCards.digestOf）。
        java.util.List<ComponentData> allProducts = new java.util.ArrayList<>();
        for (Part m : members) {
            if (m != null && m.products != null) allProducts.addAll(m.products);
        }
        final String digest = ProductCards.digestOf(allProducts);
        final boolean keepProducts = ProductCards.unchanged(v.products, digest);
        if (!keepProducts) {
            releaseChildren(v.products);
        }
        int idx = 0;
        for (Part m : members) {
            v.body.addView(ToolActivityRowView.build(ctx, v.body, m, idx++, host));
            // 所属产物（§11.3）：进<b>产物区</b>而不是折叠体——折叠时仍可见（同 legacy）。
            if (!keepProducts) {
                ProductCards.mountAll(productFactory, v.products, m.products, m.id + ":prod:", host);
            }
        }
        if (!keepProducts) {
            ProductCards.markMounted(v.products, digest);
        }

        final PartHost h = host;
        // 按类型分折后的工具组：运行中自动展开、结束留一拍再收起；用户点过的选择优先。
        final boolean running = anyRunning;
        boolean wasRunning = v.lastRunning;
        v.lastRunning = running;
        if (v.override != null) {
            cancelAutoCollapse(v);
            applyExpanded(v, v.override, ctx);
        } else if (running) {
            cancelAutoCollapse(v);
            applyExpanded(v, true, ctx);
        } else if (wasRunning) {
            applyExpanded(v, true, ctx);
            scheduleAutoCollapse(v, ctx);
        } else {
            cancelAutoCollapse(v);
            applyExpanded(v, h.toolGroupExpanded(groupId), ctx);
        }
        v.head.setOnClickListener(x -> {
            boolean now = v.body.getVisibility() == View.VISIBLE;
            boolean want = !now;
            v.override = want;
            cancelAutoCollapse(v);
            if (h.toolGroupExpanded(groupId) != want) {
                h.toggleToolGroup(groupId);
            }
            applyExpanded(v, want, ctx);
            h.invalidateSize();
        });
    }

    /** 展开/收起 + 收起态给折叠头一块"卡片感"的底（与思考折叠同一套表面层级）。 */
    private static void applyExpanded(Views v, boolean expanded, Context ctx) {
        v.body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (expanded) {
            v.head.setBackground(null);
        } else {
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(com.oilquiz.app.theme.ThemeColors.attr(ctx,
                    com.oilquiz.app.R.attr.colorControlBackground));
            bg.setCornerRadius(dp(ctx, 12));
            v.head.setBackground(bg);
        }
    }

    private static void scheduleAutoCollapse(Views v, Context ctx) {
        cancelAutoCollapse(v);
        v.collapseRunnable = () -> {
            v.collapseRunnable = null;
            if (v.override != null || v.lastRunning) {
                return;
            }
            applyExpanded(v, false, ctx);
        };
        MAIN.postDelayed(v.collapseRunnable, AUTO_COLLAPSE_MS);
    }

    private static void cancelAutoCollapse(Views v) {
        if (v.collapseRunnable != null) {
            MAIN.removeCallbacks(v.collapseRunnable);
            v.collapseRunnable = null;
        }
    }

    @Override
    public void releaseView(View view) {
        if (view instanceof LinearLayout) {
            Views v = (Views) ((LinearLayout) view).getTag();
            if (v != null) {
                cancelAutoCollapse(v);
                // 视图被移除/回收时停掉"活着"信号，别让 Animator 跟着泄漏到列表池里
                v.dots.stop();
                com.oilquiz.app.ui.UiFx.stopShimmer(v.header);
                releaseChildren(v.body);
                releaseChildren(v.products);
            }
        }
    }

    private static void releaseChildren(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            ComponentLifecycle.release(group.getChildAt(i));
        }
        group.removeAllViews();
    }

    private static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    private static final class Views {
        /** 折叠头整行（点击目标，含左侧呼吸点）。 */
        final LinearLayout head;
        final TextView header;
        /** "还在动"的呼吸点：仅运行中出现，结束即隐藏并停表。 */
        final com.oilquiz.app.ui.widget.LiveDotsView dots;
        final LinearLayout body;
        /** 产物区：在折叠体之外，折叠时仍可见（对齐 legacy chat_v2_tool_card_products）。 */
        final LinearLayout products;
        /** 用户点过的展开选择（null=跟随运行状态）。 */
        Boolean override;
        /** 上一帧是否运行：用于识别"运行→结束"这一帧并留一拍再收起。 */
        boolean lastRunning;
        /** 结束后的延迟收起任务（用户点击/重绑/回收时取消）。 */
        Runnable collapseRunnable;
        Views(LinearLayout head, TextView header, LinearLayout body, LinearLayout products,
              com.oilquiz.app.ui.widget.LiveDotsView dots) {
            this.head = head;
            this.header = header;
            this.body = body;
            this.products = products;
            this.dots = dots;
        }
    }
}
