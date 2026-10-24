package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.ai.chat.component.ComponentDisplay;
import com.oilquiz.app.ai.chat.component.ComponentLifecycle;

import java.util.List;

/**
 * 产物卡挂载的公共实现（parts §11.3）。
 *
 * <p>{@link DataPartRenderer}（顶层产物）与 {@link ToolCallPartRenderer}/{@link ToolGroupPartRenderer}
 * （挂在所属工具卡下的产物）共用这里，保证档位（HIDDEN 不挂 / PEEK 摘要行可展开 / INLINE 整卡）
 * 与 legacy {@code mountRoundComponent}/{@code wrapProductPeek} 同口径，避免两套渲染逻辑漂移。</p>
 *
 * <p>调用方负责宿主视图的独占写入（单写者）：{@link #mount} 只 addView，不 removeAllViews；
 * {@link #releaseAll} 供重绑前深度释放旧子树（嵌套 WebView 等）。</p>
 */
final class ProductCards {

    private ProductCards() {
    }

    /** 把单个产物按档位挂进 {@code parent}；HIDDEN 不挂；PEEK 摘要行可就地展开。 */
    static void mount(ToolCallPartRenderer.ComponentFactory factory, ViewGroup parent,
                      ComponentData comp, String expandKey, PartHost host) {
        if (factory == null || parent == null || comp == null || host == null) {
            return;
        }
        Context ctx = host.context();
        ComponentDisplay.Mode mode = ComponentDisplay.resolve(ctx, comp);
        if (!ComponentDisplay.mounts(mode)) {
            return; // HIDDEN：不挂载
        }
        View body = factory.create(ctx, comp);
        if (body == null) {
            return;
        }
        if (mode == ComponentDisplay.Mode.PEEK) {
            parent.addView(buildPeek(ctx, host, comp, expandKey, body));
        } else {
            parent.addView(body); // INLINE
        }
    }

    /** 依次挂一组产物（顺序即列表顺序）。expandKey 用 {@code keyPrefix + i} 保证稳定唯一。 */
    static void mountAll(ToolCallPartRenderer.ComponentFactory factory, ViewGroup parent,
                         List<ComponentData> products, String keyPrefix, PartHost host) {
        if (products == null) {
            return;
        }
        for (int i = 0; i < products.size(); i++) {
            ComponentData comp = products.get(i);
            if (comp == null) {
                continue;
            }
            String key = (comp.id != null && !comp.id.isEmpty()) ? comp.id : (keyPrefix + i);
            mount(factory, parent, comp, key, host);
        }
    }

    /** 释放并清空容器子树（深度释放嵌套 WebView 等）。 */
    static void releaseAll(ViewGroup group) {
        if (group == null) {
            return;
        }
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            ComponentLifecycle.release(group.getChildAt(i));
        }
        group.removeAllViews();
    }

    /**
     * 产物集合摘要（顺序敏感：id + type + props 内容）。
     *
     * <p>用途是<b>产物没变就别重建子树</b>。此前工具卡每次重绑都 {@code releaseAll} 再重新
     * {@code mountAll}，而重绑在流式期是常态（任一工具 running→success、完成态那一次全量绑定都会
     * 改指纹）——html/图表/播放器这类带 WebView 的产物于是被打回起点：滚动位置、页面内输入焦点、
     * 播放进度全丢（真机表现为"在网页卡里打字，焦点同一秒被弹掉"）。摘要相等就原样保留产物子树，
     * 变了才整体重建（顺序变化也走重建，代价可控，不做 diff 以免重蹈"产物被抹掉"那类双写 bug）。</p>
     */
    static String digestOf(List<ComponentData> products) {
        StringBuilder sb = new StringBuilder();
        if (products != null) {
            for (ComponentData c : products) {
                if (c == null) {
                    sb.append("|-");
                    continue;
                }
                String key = c.type + "|" + (c.props != null ? c.props.toString() : "");
                sb.append('|').append(c.id == null ? "" : c.id)
                        .append(':').append(Integer.toHexString(key.hashCode()));
            }
        }
        return sb.toString();
    }

    /** 产物容器当前挂的是不是这份摘要（{@code R.id.product_mount_digest} 记在容器 tag 上）。 */
    static boolean unchanged(ViewGroup products, String digest) {
        if (products == null || digest == null || products.getChildCount() == 0) {
            return false;
        }
        return digest.equals(products.getTag(R.id.product_mount_digest));
    }

    /** 记下落在这个容器里的产物摘要（只在真正重建过子树后调用）。 */
    static void markMounted(ViewGroup products, String digest) {
        if (products != null) {
            products.setTag(R.id.product_mount_digest, digest);
        }
    }

    /** PEEK 摘要行 + 可展开卡体（展开态记在 {@link ChatMessage#isComponentBodyExpanded}，key 唯一）。 */
    private static View buildPeek(Context ctx, PartHost host, ComponentData comp,
                                  String key, View body) {
        ChatMessage msg = host.message();
        boolean startExpanded = msg != null && msg.isComponentBodyExpanded(key);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);

        final TextView row = new TextView(ctx);
        row.setTextSize(12f);
        row.setClickable(true);
        row.setFocusable(true);
        row.setText(label(comp, startExpanded));
        body.setVisibility(startExpanded ? View.VISIBLE : View.GONE);

        row.setOnClickListener(v -> {
            boolean expanded = msg != null
                    ? msg.toggleComponentBodyExpanded(key)
                    : body.getVisibility() != View.VISIBLE;
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            row.setText(label(comp, expanded));
            host.invalidateSize();
        });

        box.addView(row);
        box.addView(body);
        return box;
    }

    private static String label(ComponentData comp, boolean expanded) {
        return (expanded ? "▾ " : "▸ ") + ComponentDisplay.peekLabel(comp);
    }
}
