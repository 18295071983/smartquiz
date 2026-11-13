package com.oilquiz.app.ai.chat.status;

/**
 * "待你处理"插槽（2026-11-13）。
 *
 * <p>审批/反问卡**不放进插槽**：插槽是一行 11sp 的状态条，而审批要有按钮、反问要有多选/自由输入，
 * 且必须跟消息里的 part 保持同一份真值（否则两处状态会互相打架）。所以卡留在消息流里，
 * 插槽只放一个**指路牌**：几条待处理、是什么、点一下滚到那张卡。</p>
 *
 * <p>这里是纯函数，便于单测（标题拼接/优先级/条数/同优先级排序）。</p>
 */
public final class PendingAttention {

    /** 插槽 id（全局唯一；同一时间只该有一个指路牌） */
    public static final String SLOT_ID = "attention";

    /** 优先级：比推理进度（10）还靠前，确保待处理最先被看到 */
    public static final int PRIORITY = 5;

    private PendingAttention() {
    }

    /**
     * 生成指路牌插槽；没有任何待处理时返回 null（调用方据此移除）。
     *
     * @param approvals   待审批条数
     * @param questions   待回答条数
     * @param firstLabel  第一条的摘要（工具名/问题首句），可空
     */
    public static DockSlotBus.Slot slotOf(int approvals, int questions, String firstLabel) {
        int total = Math.max(0, approvals) + Math.max(0, questions);
        if (total <= 0) return null;
        String kind;
        if (approvals > 0 && questions > 0) {
            kind = "审批与提问";
        } else if (approvals > 0) {
            kind = "审批";
        } else {
            kind = "提问";
        }
        String label = firstLabel == null ? "" : firstLabel.trim();
        if (label.length() > 24) label = label.substring(0, 24) + "…";
        String title = "待你处理：" + kind + (label.isEmpty() ? "" : " · " + label);
        String detail = total + " 条 · 点这里定位";
        return new DockSlotBus.Slot(SLOT_ID, PRIORITY, title, detail, "attention", true);
    }
}
