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
     * <p>支持两类 part：拥有独立视图的（默认），和通过
     * {@link PartRenderer#anchorIdFor} 声明"挂进 anchor 视图"的（§4.6 工具卡归属，如
     * tool_result/data 写进所属 tool_call 卡，不建第二张卡）。attached part 不占独立子视图，
     * 其 anchor 视图重建时会被强制重绑。</p>
     *
     * @param parts 权威顺序；null/空表示清空（释放并移除全部）。
     */
    public void bind(List<AssistantTurn.Part> parts) {
        // 预解析：每个 part 的 renderer + 它要挂进的 anchor（若有）。
        Map<String, PartRenderer> rendererBy = new HashMap<>();
        Map<String, String> anchorBy = new HashMap<>();
        if (parts != null) {
            for (AssistantTurn.Part part : parts) {
                if (part == null || part.id == null || part.id.isEmpty()) {
                    continue;
                }
                PartRenderer r = registry.resolve(part);
                if (r == null) {
                    // 未注册类型（如 P3 之前的 tool_call）：跳过，保留既有视图不受影响。
                    continue;
                }
                rendererBy.put(part.id, r);
                String anchor = r.anchorIdFor(part, parts);
                if (anchor != null && !anchor.equals(part.id)) {
                    anchorBy.put(part.id, anchor);
                }
            }
        }

        Set<String> ownSeen = new HashSet<>();
        Set<String> attachedSeen = new HashSet<>();
        Set<String> recreated = new HashSet<>();

        if (parts != null) {
            // phase1：拥有独立视图的 part。
            for (AssistantTurn.Part part : parts) {
                if (part == null || !rendererBy.containsKey(part.id)
                        || anchorBy.containsKey(part.id)) {
                    continue;
                }
                ownSeen.add(part.id);
                if (bindOwn(part, rendererBy.get(part.id))) {
                    recreated.add(part.id);
                }
            }
            // phase2：attached part → 写进 anchor 视图，不建自己的视图。
            for (AssistantTurn.Part part : parts) {
                String anchor = part == null ? null : anchorBy.get(part.id);
                if (anchor == null) {
                    continue;
                }
                View anchorView = viewById.get(anchor);
                if (anchorView == null) {
                    continue; // anchor 未渲染
                }
                attachedSeen.add(part.id);
                PartRenderer r = rendererBy.get(part.id);
                String fp = part.fingerprint();
                // anchor 刚重建（新视图内容是空的）→ 强制把 attached 数据写进去。
                if (recreated.contains(anchor) || !Objects.equals(fp, fingerprintById.get(part.id))) {
                    r.bindView(anchorView, part, host);
                    bindCount++;
                    fingerprintById.put(part.id, fp);
                }
            }
        }

        removeUnseenOwnViews(ownSeen);
        pruneStaleFingerprints(ownSeen, attachedSeen);
        if (parts != null) {
            reconcileOrder(parts); // 只有 own 视图参与顺序
        }
    }

    /** 处理拥有独立视图的 part；返回本次是否新建了视图（供 attached 强制重绑判断）。 */
    private boolean bindOwn(AssistantTurn.Part part, PartRenderer renderer) {
        String id = part.id;
        View existing = viewById.get(id);
        PartRenderer prev = rendererById.get(id);
        boolean created = false;

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
            created = true;
        }

        // 只在内容指纹变化时重绑；指纹相同（含流式无变化）跳过。
        String fp = part.fingerprint();
        if (!Objects.equals(fp, fingerprintById.get(id))) {
            renderer.bindView(view, part, host);
            bindCount++;
            fingerprintById.put(id, fp);
        }
        return created;
    }

    private void removeUnseenOwnViews(Set<String> ownSeen) {
        List<String> toRemove = new ArrayList<>();
        for (String id : viewById.keySet()) {
            if (!ownSeen.contains(id)) {
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

    /** 清掉已消失 part（尤其 attached，无独立视图故不走 removeUnseenOwnViews）的指纹，防泄漏。 */
    private void pruneStaleFingerprints(Set<String> ownSeen, Set<String> attachedSeen) {
        List<String> drop = new ArrayList<>();
        for (String id : fingerprintById.keySet()) {
            if (!ownSeen.contains(id) && !attachedSeen.contains(id)) {
                drop.add(id);
            }
        }
        for (String id : drop) {
            fingerprintById.remove(id);
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
