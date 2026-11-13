package com.oilquiz.app.ai.chat.status;

/**
 * 甲板一行的展示模型（2026-11-13）。
 *
 * <p>把"哪段文字当主标题、哪段当副标题、要不要画箭头"从 Activity 里抽出来，
 * 这样文案规则可单测，渲染只负责摆 View。</p>
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

    private DeckRowModel(String title, String detail, boolean clickable, boolean warn) {
        this.title = title == null ? "" : title;
        this.detail = detail == null ? "" : detail;
        this.clickable = clickable;
        this.warn = warn;
    }

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
        return new DeckRowModel(title, detail, !slot.action.isEmpty(), slot.warn);
    }
}
