package com.oilquiz.app.ai.chat;

/**
 * 流式滚动"贴底"状态机（纯逻辑，无 Android 依赖，可 JVM 单测）。
 *
 * <p>2026-10-25：把"有新内容就 scrollToPosition(last)"换成业界通行的 stick-to-bottom 语义：</p>
 * <ul>
 *   <li><b>pinned</b>：只有用户还停在底部附近、且没在拖拽时，新内容才跟随滚动；</li>
 *   <li><b>用户接管</b>：一拖拽就 unpin，之后新 token/高度变化一律不动视口；</li>
 *   <li><b>重新 pin</b>：只有滚回距底 ≤ {@code thresholdPx}、或显式 {@link #pin()}
 *       （用户发消息 / 点回底按钮）才恢复跟随；</li>
 *   <li><b>不做"过几秒自动把人拽回去"</b>：那是业界明确的反模式，正读旧内容会被打断。</li>
 * </ul>
 *
 * <p>阈值用<b>距底部像素距离</b>而不是"最后一项是否完全可见"——流式中最后一条气泡往往比屏幕
 * 还高，永远"不完全可见"，拿 item 可见性判会误判。32dp 与 Flutter 的
 * {@code StickToBottomController} 默认 threshold 同量级。</p>
 */
public final class StickToBottomState {

    private final int thresholdPx;
    private boolean pinned = true;
    private boolean dragging = false;
    private boolean smoothScrolling = false;
    /**
     * 用户是否主动接管过视口（上滑离开底部）。
     *
     * <p>一旦接管，只有"用户自己拖回底部"或显式 {@link #pin()}（发消息/回底按钮）才恢复跟随；
     * 流式内容收起/工具卡插入这类<b>程序化高度收缩</b>会把"距底距离"瞬间变小，不能据此
     * 把正在阅读的用户重新钉回底部（2026-10-25 真机踩到：思考收起后视口被拽到新轮底部）。</p>
     */
    private boolean userOptedOut = false;
    /** 是否处于"用户松手后的惯性滚动"（区别于按钮触发的程序化 smooth）。 */
    private boolean userSettling = false;

    public StickToBottomState(int thresholdPx) {
        this.thresholdPx = Math.max(1, thresholdPx);
    }

    public int thresholdPx() {
        return thresholdPx;
    }

    public boolean isPinned() {
        return pinned;
    }

    public boolean isDragging() {
        return dragging;
    }

    /** 用户按下并开始拖动：立刻 unpin，并终止"正在平滑回底"的排队。 */
    public void onDragStart() {
        dragging = true;
        pinned = false;
        smoothScrolling = false;
        userOptedOut = true;
    }

    /** 用户松手进入惯性滚动（SETTLING）：保留"这是用户在滚"的语义，用于回到底部时重新 pin。 */
    public void onSettling() {
        userSettling = true;
    }

    /** 滚动停止（松手/惯性结束）：解除 dragging；平滑回底也至此结束。 */
    public void onScrollIdle() {
        dragging = false;
        smoothScrolling = false;
        userSettling = false;
    }

    /**
     * 用当前"距底部像素距离"更新 pin 状态（滚动/布局后调用）。
     *
     * <p>回到阈值内：<b>用户自己拖回来</b>才重新跟随；程序化高度收缩（思考收起、工具卡插入）
     * 导致的"变近底部"不解除"用户接管"状态。否则只有拖拽中才 unpin——程序化的跟随滚动本身
     * 会让 distance 变小，不能因为"距底小于阈值前的一瞬间"把 pin 状态抖掉。</p>
     */
    public void onDistance(int distancePx) {
        if (distancePx <= thresholdPx) {
            if (dragging || userSettling) {
                pinned = true;
                userOptedOut = false;
            } else if (!userOptedOut) {
                pinned = true;
            }
        } else if (dragging) {
            pinned = false;
            userOptedOut = true;
        }
    }

    /** 当前是否应该跟随滚动（只有 pinned、非拖拽、非平滑回底动画中才跟）。 */
    public boolean shouldFollow() {
        return pinned && !dragging && !smoothScrolling;
    }

    /** 显式重新 pin（用户发消息 / 初始化 / 切会话 / 点回底按钮）：解除"用户接管"。 */
    public void pin() {
        pinned = true;
        dragging = false;
        smoothScrolling = false;
        userOptedOut = false;
    }

    /** 开始平滑回底：先 pin，平滑动画期间暂停逐帧跟随，结束（idle）后恢复。 */
    public void pinForSmoothScroll() {
        pinned = true;
        dragging = false;
        smoothScrolling = true;
    }

    public boolean isSmoothScrolling() {
        return smoothScrolling;
    }
}
