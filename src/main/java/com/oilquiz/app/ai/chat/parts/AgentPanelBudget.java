package com.oilquiz.app.ai.chat.parts;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebView 执行面板的存活上限（PoC，2026-11-18）。
 *
 * <p>为什么需要上限：一个 {@code WebView} 不是普通 View——它背后是一个 Chromium 渲染侧实例
 * （约几十 MB 原生内存 + 自己的合成器）。{@code MarkdownWebView} 已经是"每条文气泡一个"，
 * 执行面板再叠一层，长会话里同屏 6–8 条消息就可能到 15+ 个 WebView，卡顿和内存都会先于
 * 功能暴露出来。所以 PoC 阶段给一个硬上限：**超出就退回原生手风琴**，宁可视效不一致，
 * 也不让 WebView 数量失控。</p>
 *
 * <p>上限值 3 的依据：屏幕上同时"正在执行"的 turn 通常 ≤ 2（用户只会看最新的一轮），
 * 3 留一格余量；超出的历史 turn 用原生渲染——它们本来就已结束，不需要流式面板。</p>
 *
 * <p>注意：上限是**进程级**的，调试页单独创建的 panel 也会占额度（PoC 页按需释放）。</p>
 */
public final class AgentPanelBudget {

    /** 同时存活的 WebView 执行面板上限。 */
    static final int MAX_LIVE = 3;

    private static final AtomicInteger LIVE = new AtomicInteger(0);

    private AgentPanelBudget() {
    }

    /** 申请一个额度；成功返回 true（调用方必须在释放视图时还回来）。 */
    public static boolean acquire() {
        while (true) {
            int cur = LIVE.get();
            if (cur >= MAX_LIVE) {
                return false;
            }
            if (LIVE.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    /** 归还额度（幂等调用只应配对一次；由持有方保证）。 */
    public static void release() {
        LIVE.updateAndGet(v -> Math.max(0, v - 1));
    }

    public static int live() {
        return LIVE.get();
    }

    /** 仅测试用：清空计数（避免用例间串账）。 */
    static void resetForTest() {
        LIVE.set(0);
    }
}
