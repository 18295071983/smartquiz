package com.oilquiz.app.ai.chat.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 甲板（状态区）的渲染策略（2026-11-13，M1）。
 *
 * <p>把"哪些行该显示"从 {@code AIChatActivity} 里抽出来：上限来自
 * {@link DshUiCatalogLoader}，取舍规则（优先级升序、同优先级按 id 稳定排序、截断）是纯函数，
 * 于是可以用 FakeHost 直接测（验收 A2），不必起 Activity。</p>
 *
 * <p>"甲板两容器合并"里也靠它统一调度：进度条与插槽共用同一份行选择逻辑，
 * 进度条可见时插槽只留 1 条，否则 2 条（默认值，可由配置表覆盖）。</p>
 */
public final class StatusDeckController {

    /** 渲染宿主：Activity 侧负责真正 inflate，控制器只决定"显示哪几行" */
    public interface Host {
        void renderSlots(List<DockSlotBus.Slot> rows);
    }

    private final Host host;
    private int maxWithProgress = DshUiCatalogLoader.DEFAULT_MAX_WITH_PROGRESS;
    private int maxWithoutProgress = DshUiCatalogLoader.DEFAULT_MAX_WITHOUT_PROGRESS;

    public StatusDeckController(Host host) {
        this.host = host;
    }

    /** 应用配置表（越界自动收敛到 0..HARD_MAX） */
    public void applyCatalog(DshUiCatalogLoader catalog) {
        if (catalog == null) return;
        maxWithProgress = Math.max(0, Math.min(catalog.maxRowsWithProgress(), DshUiCatalogLoader.HARD_MAX));
        maxWithoutProgress = Math.max(0, Math.min(catalog.maxRowsWithoutProgress(), DshUiCatalogLoader.HARD_MAX));
    }

    public int maxWithProgress() {
        return maxWithProgress;
    }

    public int maxWithoutProgress() {
        return maxWithoutProgress;
    }

    /** 插槽集合变化 / 进度条显隐变化时调用：算出该显示的行，交给宿主 */
    public void onSlotsChanged(List<DockSlotBus.Slot> slots, boolean progressVisible) {
        if (host == null) return;
        host.renderSlots(select(slots, progressVisible, maxWithProgress, maxWithoutProgress));
    }

    /** 断连/切会话：清空显示（不改 DockSlotBus 本身，调用方决定要不要 clear） */
    public void clear() {
        if (host != null) host.renderSlots(Collections.<DockSlotBus.Slot>emptyList());
    }

    /**
     * 行选择纯函数（验收 A2 的主体）。
     *
     * <ul>
     *   <li>过滤 null / 空 id；</li>
     *   <li>priority 升序（越小越靠上），同优先级按 id 字典序，保证顺序稳定不抖；</li>
     *   <li>按进度条可见性取上限，超出的截断。</li>
     * </ul>
     */
    public static List<DockSlotBus.Slot> select(List<DockSlotBus.Slot> slots, boolean progressVisible,
                                                int maxWith, int maxWithout) {
        if (slots == null || slots.isEmpty()) return Collections.emptyList();
        List<DockSlotBus.Slot> sorted = new ArrayList<>(slots.size());
        for (DockSlotBus.Slot s : slots) {
            if (s == null) continue;
            if (s.id == null || s.id.trim().isEmpty()) continue;
            sorted.add(s);
        }
        Collections.sort(sorted, new Comparator<DockSlotBus.Slot>() {
            @Override
            public int compare(DockSlotBus.Slot a, DockSlotBus.Slot b) {
                int byPriority = Integer.compare(a.priority, b.priority);
                if (byPriority != 0) return byPriority;
                return a.id.compareTo(b.id);
            }
        });
        int max = Math.max(0, progressVisible ? maxWith : maxWithout);
        if (sorted.size() > max) return new ArrayList<>(sorted.subList(0, max));
        return sorted;
    }
}
