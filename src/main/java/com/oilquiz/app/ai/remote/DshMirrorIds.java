package com.oilquiz.app.ai.remote;

import com.oilquiz.app.ai.chat.ChatIdDispatcher;

/**
 * DSH 镜像气泡的 id 分配（2026-11-13，B6）。
 *
 * <p>以前气泡用 {@code UUID} 当 id，又把 {@code subId} 指回自己 —— id 与 turnId 脱节，
 * 按 id 排序/聚合时归错组。现在统一由派发器发 {@code T{n}-A{k}}：id == subId，
 * 且与 {@code turnId} 同源。抽出来是为了能在单测里钉住这条约定（Activity 里没法直接测）。</p>
 */
public final class DshMirrorIds {

    private DshMirrorIds() {
    }

    public static final class Ids {
        public final String turnId;
        public final String subId;

        Ids(String turnId, String subId) {
            this.turnId = turnId == null ? "" : turnId;
            this.subId = subId == null ? "" : subId;
        }
    }

    /** 生产路径：用进程内单例派发器 */
    public static Ids allocate() {
        return allocate(ChatIdDispatcher.getInstance());
    }

    /** 测试路径：可传入独立派发器，避免污染全局计数器 */
    public static Ids allocate(ChatIdDispatcher dispatcher) {
        String turnId = dispatcher.applyMasterId();
        String subId = dispatcher.applySubId(ChatIdDispatcher.IdType.AI, turnId);
        if (subId == null || subId.isEmpty()) {
            // 派发器异常时的兜底：不能留空 id（组件/消息都靠 id 定位）
            subId = turnId + "-A0";
        }
        return new Ids(turnId, subId);
    }
}
