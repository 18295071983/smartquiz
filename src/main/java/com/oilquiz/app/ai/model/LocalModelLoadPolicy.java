package com.oilquiz.app.ai.model;

import android.content.Context;

import com.oilquiz.app.ai.refactor.AIConfig;

/**
 * 「要不要主动把本地 GGUF 模型捞进内存」的唯一判定（2026-10-25）。
 *
 * <p>起因：同一个问题原先在两处各写了一半条件。</p>
 * <ul>
 *   <li>启动预加载（{@code SmartQuizApplication.preloadAIServiceInternal}）：
 *       {@code hasActiveOnlineModel() || !localAgentEnabled} → 跳过；</li>
 *   <li>回前台热启动（{@code AIService.canHotStart()}）：<b>只</b>排除 NPU 引擎激活，
 *       不看在线模式，也不看本地 Agent 开关。</li>
 * </ul>
 * <p>结果就是"预加载说跳过、热启动 8 秒后照样把权重加载回来"。真机实测（2026-10-25 21:09，
 * 停在首页没进聊天页）：{@code Native Heap Heap Alloc ≈ 984MB}、swap 492MB、
 * {@code EGL mtrack 26MB}＋{@code Gfx dev 30MB}，全是白占——用户当时用的是在线模型。</p>
 *
 * <p>口径：<b>主动</b>加载（启动预加载、回前台热启动）只在"没有激活的在线模型
 * <b>且</b> 本地 Agent 开着"时允许。用户显式点"加载模型/开始本地对话"不受这里限制——
 * 那条路走 {@code loadModel}，不经过本类。</p>
 */
public final class LocalModelLoadPolicy {

    private LocalModelLoadPolicy() {
    }

    /**
     * 纯判定（不碰 Context，便于单测穷举）。
     *
     * @param hasActiveOnlineModel 当前是否有"激活"的在线模型
     * @param localAgentEnabled    本地 Agent 开关
     * @return true = 允许主动加载/热启动本地模型
     */
    public static boolean shouldAutoLoadLocalModel(boolean hasActiveOnlineModel,
                                                   boolean localAgentEnabled) {
        return !hasActiveOnlineModel && localAgentEnabled;
    }

    /**
     * 读现场状态并给出判定。
     *
     * @return {@code null} = 允许主动加载；否则是一条可直接写进日志的原因
     */
    public static String skipReason(Context ctx) {
        if (ctx == null) {
            return "无 Context，不主动加载本地模型";
        }
        boolean hasActiveOnline;
        boolean localAgent;
        try {
            hasActiveOnline = OnlineModelManager.getInstance(ctx).hasActiveOnlineModel();
        } catch (Throwable t) {
            // 读不到就按"有在线模型"处理：宁可不动本地权重，也不要"不用也加载"
            return "读取在线模型状态失败，跳过本地模型主动加载: " + t.getMessage();
        }
        try {
            localAgent = new AIConfig(ctx).isLocalAgentEnabled();
        } catch (Throwable t) {
            return "读取本地 Agent 开关失败，跳过本地模型主动加载: " + t.getMessage();
        }
        if (!shouldAutoLoadLocalModel(hasActiveOnline, localAgent)) {
            if (hasActiveOnline) {
                return "已激活在线模型，本地模型按需加载（不主动预加载/热启动）";
            }
            return "本地 Agent 已关闭，本地模型按需加载（不主动预加载/热启动）";
        }
        return null;
    }

    /** {@link #skipReason(Context)} 的布尔形式。 */
    public static boolean shouldAutoLoadLocalModel(Context ctx) {
        return skipReason(ctx) == null;
    }
}
