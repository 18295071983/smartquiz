package com.oilquiz.app.ai.chat.parts;

import android.view.View;
import android.view.ViewGroup;

import com.oilquiz.app.ai.chat.turn.AssistantTurn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * parts → 视图的增量复用引擎（parts 架构升级 P2，2026-10-21）。
 *
 * <p>这是本次架构升级的核心：把"裸 {@code for(Part) removeAllViews+addView}"换成
 * 按稳定 id 复用、按内容指纹决定是否重绑、只删除消失 part 的增量协调。文档 §3 列出的
 * 五类问题（WebView/播放器状态丢失、滚动/选中丢失、双渲染、测量抖动、无障碍顺序）都源于
 * 全量重建，本类正是从算法上杜绝它：</p>
 *
 * <ul>
 *   <li>不 {@code removeAllViews}——视图按 {@code part.id} 存活于 {@link #viewById}；</li>
 *   <li>内容指纹未变的 part 不重绑（见 {@link AssistantTurn.Part#fingerprint()}）；</li>
 *   <li>删除 part 时先 {@link PartRenderer#releaseView} 再从容器移除；</li>
 *   <li>每次 bind 末尾按 parts 顺序对齐子视图顺序，支持重排。</li>
 * </ul>
 *
 * <p>本类只依赖 {@link ViewGroup} 与 {@link PartRenderer} 抽象，不依赖 ChatAdapter，
 * 因此可在真实 {@code LinearLayout} 上做纯逻辑单测。</p>
 */
public final class TurnPartsHost {

    private final ViewGroup container;
    private final PartRendererRegistry registry;
    private final PartHost host;

    private final Map<String, View> viewById = new LinkedHashMap<>();
    private final Map<String, String> fingerprintById = new HashMap<>();
    private final Map<String, PartRenderer> rendererById = new HashMap<>();

    /** 统计用：累计 createView 次数（单测据此断言"复用而非重建"）。 */
    int createCount;
    /** 统计用：累计 bindView 次数。 */
    int bindCount;
    /** 统计用：累计 releaseView 次数。 */
    int releaseCount;

    public TurnPartsHost(ViewGroup container, PartRendererRegistry registry, PartHost host) {
        this.container = Objects.requireNonNull(container, "container");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.host = Objects.requireNonNull(host, "host");
    }

    /** 当前已挂载的 part 视图数（等于 {@code container} 子视图数）。 */
    public int renderedCount() {
        return viewById.size();
    }

    /** 只读快照：当前 id→视图映射（供宿主/测试观察，勿外部修改）。 */
    public Map<String, View> views() {
        return Collections.unmodifiableMap(viewById);
    }

    /**
     * 把一组有序 parts 协调到容器：复用同 id、只绑变化项、删除消失项、按序对齐。
     *
     * @param parts 权威顺序；null/空表示清空（释放并移除全部）。
     */
    public void bind(List<AssistantTurn.Part> parts) {
        Set<String> seen = new HashSet<>();
        if (parts != null) {
            for (AssistantTurn.Part part : parts) {
                if (part == null || part.id == null || part.id.isEmpty()) {
                    continue;
                }
                PartRenderer renderer = registry.resolve(part);
                if (renderer == null) {
                    // 未注册类型（如 P3 之前的 tool_call）：跳过，保留既有视图不受影响。
                    continue;
                }
                seen.add(part.id);
                bindOne(part, renderer);
            }
        }

        removeUnseen(seen);
        if (parts != null) {
            reconcileOrder(parts);
        }
    }

    private void bindOne(AssistantTurn.Part part, PartRenderer renderer) {
        String id = part.id;
        View existing = viewById.get(id);
        PartRenderer prev = rendererById.get(id);

        // id 相同但类型变了：先释放旧视图，再按新类型重建。
        if (existing != null && prev != null && !prev.canRender(part)) {
            prev.releaseView(existing);
            container.removeView(existing);
            releaseCount++;
            viewById.remove(id);
            fingerprintById.remove(id);
            rendererById.remove(id);
            existing = null;
        }

        View view = existing;
        if (view == null) {
            view = renderer.createView(host, container);
            createCount++;
            viewById.put(id, view);
            rendererById.put(id, renderer);
            fingerprintById.remove(id); // 新视图强制首绑
        }

        // 只在内容指纹变化时重绑；指纹相同（含流式无变化）跳过。
        String fp = part.fingerprint();
        if (!Objects.equals(fp, fingerprintById.get(id))) {
            renderer.bindView(view, part, host);
            bindCount++;
            fingerprintById.put(id, fp);
        }
    }

    private void removeUnseen(Set<String> seen) {
        List<String> toRemove = new ArrayList<>();
        for (String id : viewById.keySet()) {
            if (!seen.contains(id)) {
                toRemove.add(id);
            }
        }
        for (String id : toRemove) {
            View v = viewById.get(id);
            PartRenderer r = rendererById.get(id);
            if (r != null && v != null) {
                r.releaseView(v);
                releaseCount++;
            }
            if (v != null) {
                container.removeView(v);
            }
            viewById.remove(id);
            fingerprintById.remove(id);
            rendererById.remove(id);
        }
    }

    /**
     * 让 {@code container} 子视图顺序与 parts 的已渲染顺序一致。
     * 只在确需移动时 removeView+addView(index)，绝不全量重建。
     */
    private void reconcileOrder(List<AssistantTurn.Part> parts) {
        List<View> wanted = new ArrayList<>();
        for (AssistantTurn.Part part : parts) {
            if (part == null || part.id == null) {
                continue;
            }
            View v = viewById.get(part.id);
            if (v != null && !wanted.contains(v)) {
                wanted.add(v);
            }
        }
        for (int i = 0; i < wanted.size(); i++) {
            View want = wanted.get(i);
            if (container.getChildAt(i) != want) {
                int cur = container.indexOfChild(want);
                if (cur >= 0) {
                    container.removeViewAt(cur);
                }
                container.addView(want, Math.min(i, container.getChildCount()));
            }
        }
    }
}
