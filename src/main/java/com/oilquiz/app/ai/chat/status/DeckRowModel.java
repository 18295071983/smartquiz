package com.oilquiz.app.ai.chat.status;

import java.util.Collections;
import java.util.List;

/**
 * 甲板一行的展示模型（2026-11-13）。
 *
 * <p>把"哪段文字当主标题、哪段当副标题、要不要画箭头"从 Activity 里抽出来，
 * 这样文案规则可单测，渲染只负责摆 View。</p>
 *
 * <p>④（2026-11-15）：增加 {@link #kind} / {@link #progress} / {@link #actions}，
 * 让甲板行与全屏列表共用同一份「这条该画成什么」的判断（降级规则见 {@link SlotKind}）。</p>
 */
public final class DeckRowModel {

    /** 主文案（一行，加粗感） */
    public final String title;
    /** 副文案（一行，次要色）；空串表示不显示 */
    public final String detail;
    /** 是否可点（有动作才画箭头 + 涟漪） */
    public final boolean clickable;
    /** 是否警示色（危险权限/失败） */
    public final boolean warn;
    /** ④：生效 kind（已按 SlotKind.effectiveKind 兜底） */
    public final String kind;
    /** ④：progress kind 的百分比；其它 kind 为 0 */
    public final int progress;
    /** ④：action kind 的行内按钮；其它 kind 为空 */
    public final List<SlotAction> actions;

    private DeckRowModel(String title, String detail, boolean clickable, boolean warn) {
        this(title, detail, clickable, warn, title, 0, SlotKind.STATE, 0,
                Collections.<SlotAction>emptyList());
    }

    /**
     * U1 (2026-11-14): chip form for the multi-board deck.
     *
     * @param chipText compact label for a chip (<= 12 chars)
     * @param count    item count for list-kind slots
     */
    private DeckRowModel(String title, String detail, boolean clickable, boolean warn,
                         String chipText, int count, String kind, int progress,
                         List<SlotAction> actions) {
        this.title = title == null ? "" : title;
        this.detail = detail == null ? "" : detail;
        this.clickable = clickable;
        this.warn = warn;
        this.chipText = chipText == null ? "" : chipText;
        this.count = Math.max(0, count);
        this.kind = kind == null ? SlotKind.STATE : kind;
        this.progress = Math.max(0, Math.min(100, progress));
        this.actions = actions == null
                ? Collections.<SlotAction>emptyList() : actions;
    }

    public final String chipText;
    public final int count;

    public static DeckRowModel of(DockSlotBus.Slot slot) {
        if (slot == null) return new DeckRowModel("", "", false, false);
        String title = slot.title == null ? "" : slot.title.trim();
        String detail = slot.detail == null ? "" : slot.detail.trim();
        // 兼容老帧：只有 detail 没有 title 时，把 detail 当主文案
        if (title.isEmpty() && !detail.isEmpty()) {
            title = detail;
            detail = "";
        }
        if (detail.length() > 28) detail = detail.substring(0, 28) + "…";
        String kind = SlotKind.effectiveKind(slot.kind, slot.count, slot.actions);
        // U1 (2026-11-14): chip label = title (+ count for list-kind), capped at 12 chars
        String chip = title;
        int count = SlotKind.LIST.equals(kind) ? slot.count : 0;
        if (count > 0) chip = chip + " " + count;
        if (chip.length() > 12) chip = chip.substring(0, 12) + "\u2026";
        return new DeckRowModel(title, detail, !slot.action.isEmpty(), slot.warn,
                chip, count, kind, slot.progress, slot.actions);
    }
}
