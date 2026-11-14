package com.oilquiz.app.ai.remote;

import com.oilquiz.app.ai.chat.ChatMessage;

import java.util.List;

/**
 * 审批/反问与"所属段"的映射（2026-11-15，§19C 审批精确落段）。
 *
 * <p>背景：审批/反问是 <b>消息级</b>字段（{@code ChatMessage.approvals/questions}），
 * 不进 {@link DshProjection}。以前 {@code AIChatActivity} 一律写"当前活动尾段"——
 * 于是 step 2 发生的审批，在第 7 步之后到达时就挂在了 G1 上，滑回 G0 看不到；
 * {@code approval_gone} 也只清尾段，前面的审批永远停在 pending。</p>
 *
 * <p>本类把"该挂哪段/该清哪段"抽成纯 JVM 函数（可单测，不碰 Android）：</p>
 * <ul>
 *   <li>{@link #segmentOfRound}：事件所属全局步序号（1 基）→ 段号；</li>
 *   <li>{@link #segmentOfApproval}：按审批 id 找它落在哪一段（gone 用）；</li>
 *   <li>{@link #segmentOfQuestionBatch}：按反问批次 id 找段（question_gone 用）。</li>
 * </ul>
 *
 * <p>返回 -1 表示"定位不到"：事件缺 step/callId（旧数据、中继帧）或段坐标越界。
 * 调用方此时回退当前尾段，并打一行日志说明 —— 不能静默丢掉。</p>
 */
public final class DshSegmentLocator {

    private DshSegmentLocator() {
    }

    /**
     * 全局步序号（1 基）→ 段号。
     *
     * @param round        事件所属步的全局序号（1 基；0 = 未知）
     * @param segmentCount 当前时间线已知的段数
     * @return 段号；无法定位返回 -1
     */
    public static int segmentOfRound(int round, int segmentCount) {
        if (round <= 0 || segmentCount <= 0) return -1;
        int index = SegmentSealPolicy.segmentIndex(round - 1);
        return index >= 0 && index < segmentCount ? index : -1;
    }

    /** 审批 id 落在哪一段；找不到返回 -1（调用方回退尾段/全量兜底）。 */
    public static int segmentOfApproval(List<ChatMessage> segments, String id) {
        if (segments == null || id == null || id.isEmpty()) return -1;
        for (int i = 0; i < segments.size(); i += 1) {
            ChatMessage m = segments.get(i);
            if (m == null || m.approvals == null) continue;
            for (ChatMessage.ApprovalRequest a : m.approvals) {
                if (a != null && id.equals(a.id)) return i;
            }
        }
        return -1;
    }

    /** 反问批次 id 落在哪一段；找不到返回 -1。 */
    public static int segmentOfQuestionBatch(List<ChatMessage> segments, String batchId) {
        if (segments == null || batchId == null || batchId.isEmpty()) return -1;
        for (int i = 0; i < segments.size(); i += 1) {
            ChatMessage m = segments.get(i);
            if (m == null || m.questions == null) continue;
            for (ChatMessage.QuestionRequest q : m.questions) {
                if (q != null && batchId.equals(q.batchId)) return i;
            }
        }
        return -1;
    }
}
