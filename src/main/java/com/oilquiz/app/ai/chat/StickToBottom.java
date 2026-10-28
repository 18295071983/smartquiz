package com.oilquiz.app.ai.chat;

import android.view.View;
import android.view.ViewTreeObserver;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView 版"流式贴底跟随"（2026-10-25）。
 *
 * <p>解决的问题：旧实现流式中每拍调 {@code scrollToPosition(last)}。最后一条消息比屏幕高时，
 * RecyclerView 会把<b>该 item 的顶部</b>对齐视口顶（文档行为），于是最新流式内容一直长在屏幕下方，
 * 用户看不到"正在写的那一行"；同时散落的强制滚动会在用户上滑阅读时把人反复拽回底部。</p>
 *
 * <p>现在的模型（与业界 stick-to-bottom 一致）：</p>
 * <ol>
 *   <li>内容增长（token 写回 / WebView 高度回调 / 思考行刷新）后调 {@link #follow()}；</li>
 *   <li>只有 {@link StickToBottomState} 判定 pinned 才真正滚，且滚的是<b>距底距离</b>
 *       （{@code scrollBy(0, gap)}），不是对 item 定位 → 超高气泡也永远贴底；</li>
 *   <li>在下一帧的 {@code OnPreDraw} 里执行：此时本轮 measure/layout 已完成，gap 是新值，
 *       不会出现"先滚→再长高→再滚"的逐段跳；同一帧多次请求自动合并成一次；</li>
 *   <li>gap 优先按"最后一条 item 的 bottom"精确算（RecyclerView 的
 *       {@code computeVerticalScrollRange} 是估算值，超高 WebView 列表会偏小）；
 *       一次没到底（末项刚被 layout 出来/高度还在长）就在下一帧继续，连续两次滚不动才放弃，
 *       放弃时强制定位到末项让下一帧拿到精确 gap；</li>
 *   <li>用户拖拽 → 立刻取消排队滚动并 unpin；只有用户自己滚回底部、发消息或点回底按钮才重新 pin。
 *       程序化的高度收缩（思考收起）不会解除"用户接管"，不会把正在阅读的人拽回底部。</li>
 * </ol>
 *
 * <p>线程：全部方法须在主线程调用（Activity 侧本来就在主线程）。</p>
 */
public final class StickToBottom {

    /** 距底部多少 dp 以内还算"贴底"。32dp 与 Flutter StickToBottomController 默认值同量级。 */
    private static final int THRESHOLD_DP = 32;

    /** 同一轮跟随最多连续纠正的帧数（防布局异常时死循环）。 */
    private static final int MAX_FOLLOW_FRAMES = 6;

    /** 末项不在已 layout 范围内时返回的"远"值（px）：表示离底部很远但避免 int 溢出。 */
    private static final int FAR_FROM_BOTTOM_PX = 1_000_000;

    /** 平滑回底的距离上限（超过 2 屏就改成瞬时，避免 OverScroller 动画跑好几秒）。 */
    private static final int SMOOTH_MAX_SCREENS = 2;

    private final RecyclerView rv;
    private final StickToBottomState state;
    private final int thresholdPx;

    /** 下一帧要跟随（内容增长/流式 token 触发）。 */
    private boolean pendingFollow;
    /** 下一帧要平滑回底（用户点回底按钮触发）。 */
    private boolean pendingSmooth;
    /** 本轮跟随连续纠正次数（见 {@link #MAX_FOLLOW_FRAMES}）。 */
    private int followFrames;
    private boolean released;
    private final ViewTreeObserver.OnPreDrawListener preDrawListener;

    private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                // 手势优先：任何排队中的跟随/回底都作废（用户正在接管视口）
                pendingFollow = false;
                pendingSmooth = false;
                followFrames = 0;
                state.onDragStart();
            } else if (newState == RecyclerView.SCROLL_STATE_SETTLING) {
                // 用户松手后的惯性（区别于按钮触发的 smooth）：用于"用户自己甩回底部"时重新 pin
                state.onSettling();
            } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                state.onScrollIdle();
            }
        }

        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            state.onDistance(gapToBottom());
        }
    };

    public StickToBottom(RecyclerView rv) {
        this.rv = rv;
        this.thresholdPx = Math.round(THRESHOLD_DP * rv.getResources().getDisplayMetrics().density);
        this.state = new StickToBottomState(thresholdPx);
        this.preDrawListener = () -> {
            if (released) {
                return true;
            }
            if (!pendingFollow && !pendingSmooth) {
                followFrames = 0;
                return true;
            }
            int gap = gapToBottom();
            state.onDistance(gap);
            if (gap <= 0 || !state.isPinned() || state.isDragging()) {
                pendingFollow = false;
                pendingSmooth = false;
                followFrames = 0;
                return true;
            }
            if (pendingSmooth) {
                pendingFollow = false;
                pendingSmooth = false;
                if (gap > rv.getHeight() * SMOOTH_MAX_SCREENS) {
                    // 距离太远：直接贴底（平滑动画会拖好几秒）
                    rv.scrollBy(0, gap);
                } else {
                    state.pinForSmoothScroll();
                    rv.smoothScrollBy(0, gap);
                }
                // 无论是瞬时还是平滑，之后都继续逐帧核对（平滑期间由状态机拦住）
                pendingFollow = true;
                return true;
            }
            if (state.isSmoothScrolling()) {
                // 平滑回底动画还在跑：保留请求，动画结束（idle）后的下一帧再逐帧跟随
                pendingFollow = true;
                return true;
            }
            int before = rv.computeVerticalScrollOffset();
            rv.scrollBy(0, gap);
            int after = rv.computeVerticalScrollOffset();
            if (after == before) {
                // 估算范围/布局没跟上（末项还没 layout 出来）：强制定位到末项，下一帧按精确 gap 补齐。
                followFrames++;
                if (followFrames <= 2 && forcePositionToLastItem()) {
                    pendingFollow = true;
                    return true;
                }
                pendingFollow = false;
                followFrames = 0;
                return true;
            }
            followFrames++;
            if (followFrames >= MAX_FOLLOW_FRAMES) {
                pendingFollow = false;
                followFrames = 0;
            } else {
                // 内容可能还在长/末项高度还没稳定：下一帧再核对一次
                pendingFollow = true;
            }
            return true;
        };
        rv.addOnScrollListener(scrollListener);
        rv.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
    }

    /**
     * 视口尺寸变化（键盘/IME insets 动画、旋转、输入栏高度变化）后调用。
     *
     * <p>键盘弹起/收起时列表高度在动画中逐帧变化。旧实现靠 {@code setupKeyboardListener} 的
     * 15% 阈值 + 100ms 延迟补滚，等于"最后一拍才跟"。现在由 insets 回调直接驱动：</p>
     * <ul>
     *   <li>变化前是 pinned → 下一帧立刻贴底（不依赖是否有新 token）；</li>
     *   <li>变化前是 unpinned（用户在上方阅读）→ 什么都不做，位置由
     *       {@link ScrollAnchorKeeper} 保持。</li>
     * </ul>
     *
     * @param pinnedBefore 本次视口变化发生前是否处于贴底状态（调用方在改 padding 前取）
     */
    public void onViewportChanged(boolean pinnedBefore) {
        if (released || !pinnedBefore) {
            return;
        }
        pinAndJump();
    }

    /** 当前是否处于"贴底"（回底按钮显隐 / 历史归档等判断都用它，别再数可见 item）。 */
    public boolean isPinned() {
        return state.isPinned();
    }


    /**
     * 内容增长后请求跟随。pinned 时在下一帧滚到新底部；unpinned/拖拽中直接丢弃。
     *
     * <p>可在同一帧被多次调用（token、WebView 高度、思考行刷新），只合并成一次滚动。</p>
     */
    public void follow() {
        if (released) {
            return;
        }
        if (!state.isPinned() || state.isDragging()) {
            return;
        }
        pendingFollow = true;
    }

    /**
     * 强制贴底并重新 pin：用户自己发消息、进入页面、切换会话时用。
     *
     * <p>与跟随走同一条执行路径：下一帧按距底距离直接 {@code scrollBy}，不为超高 item 做定位。</p>
     */
    public void pinAndJump() {
        if (released) {
            return;
        }
        state.pin();
        pendingSmooth = false;
        pendingFollow = true;
        followFrames = 0;
    }

    /**
     * 回底按钮：平滑滚到底部并重新 pin。
     *
     * <p>立即 pin（按钮马上隐藏、后续内容重新跟随）；平滑动画本身在下一帧 OnPreDraw 里启动
     * （此时布局已完成、距底距离是新值）。动画期间暂停逐帧跟随，结束后的新内容继续跟随。</p>
     */
    public void pinAndSmooth() {
        if (released) {
            return;
        }
        state.pin();
        pendingFollow = false;
        pendingSmooth = true;
        followFrames = 0;
    }

    /**
     * 距内容底部的像素距离（≥0）。
     *
     * <p>优先用末项目前的 layout 位置精确计算（末项可能比屏幕高，{@code getBottom()} 仍然有效）。
     * 末项还没 layout 出来时<b>不能</b>退回 {@code computeVerticalScrollRange()}：RecyclerView
     * 对可变高列表返回的是估算值，超高 WebView 气泡会严重偏小——一旦把它当"已贴底"，正在阅读的
     * 用户会在惯性滚动中被重新 pin、直接拽回底部（2026-10-25 真机踩到）。此时用一个大值表示
     * "离底部很远"，让调用方继续滚/继续找末项，下一帧拿到精确值。</p>
     */
    private int gapToBottom() {
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        RecyclerView.Adapter<?> adapter = rv.getAdapter();
        int count = adapter == null ? 0 : adapter.getItemCount();
        if (count > 0 && lm instanceof LinearLayoutManager) {
            View last = ((LinearLayoutManager) lm).findViewByPosition(count - 1);
            if (last != null) {
                int viewportBottom = rv.getHeight() - rv.getPaddingBottom();
                return Math.max(0, last.getBottom() - viewportBottom);
            }
            // 末项不在已 layout 范围内：离底部很远（具体多远不知道，别用估算冒充）
            return FAR_FROM_BOTTOM_PX;
        }
        return Math.max(0, rv.computeVerticalScrollRange()
                - rv.computeVerticalScrollOffset()
                - rv.computeVerticalScrollExtent());
    }

    /** 把末项定位到视口内（不保证贴底），让下一帧能拿到精确的 {@link #gapToBottom()}。 */
    private boolean forcePositionToLastItem() {
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        RecyclerView.Adapter<?> adapter = rv.getAdapter();
        if (!(lm instanceof LinearLayoutManager) || adapter == null || adapter.getItemCount() <= 0) {
            return false;
        }
        ((LinearLayoutManager) lm).scrollToPosition(adapter.getItemCount() - 1);
        return true;
    }

    /** Activity 销毁时解绑（preDraw 监听器强引用 Activity，必须移除）。 */
    public void release() {
        released = true;
        pendingFollow = false;
        pendingSmooth = false;
        try {
            rv.getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
        } catch (Throwable ignored) {
        }
        try {
            rv.removeOnScrollListener(scrollListener);
        } catch (Throwable ignored) {
        }
    }
}
