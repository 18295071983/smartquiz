package com.oilquiz.app.ai.chat;

import android.view.View;
import android.view.ViewTreeObserver;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/**
 * RecyclerView 滚动锚点保持（2026-10-25）—— 等价 Web 的 {@code overflow-anchor}。
 *
 * <p><b>为什么需要</b>：RecyclerView 布局时按"滚动偏移像素"保位。视口<b>上方</b>的消息高度一变
 * （思考折叠/展开、Markdown WebView 异步测高、工具卡展开、parts 高度回调），同一条消息的
 * 屏幕位置就会上下跳；用户在读旧消息时尤其明显。</p>
 *
 * <p><b>做法</b>：</p>
 * <ol>
 *   <li>滚动时记"第一可见项 position + top + item 总数 + 稳定键"（{@link ScrollAnchorState}）；</li>
 *   <li>下一帧 {@code OnPreDraw}（此时本轮 layout 已完成）：如果用户没有贴底、不在拖拽/惯性中，
 *       找到同一项，比较 top 差值 → {@code scrollBy(0, delta)} 把阅读位置拉回原处；</li>
 *   <li>item 数变化（插入/删除/加载更早历史）或同 position 换了消息（消息 id 不同）→ 重捕，不乱补；</li>
 *   <li>贴底（{@link StickToBottom#isPinned()}）时完全让路：滚动权归贴底跟随。</li>
 * </ol>
 *
 * <p>两个 preDraw 监听器（本类与 StickToBottom）互斥：pinned 时本类跳过、unpinned 时贴底跟随
 * 本身也不会滚，所以不会互相打架。</p>
 */
public final class ScrollAnchorKeeper {

    /** top 变化小于该像素认为没动（过滤测量抖动）。 */
    private static final int TOLERANCE_PX = 1;

    /** 连续补偿却滚不动时，最多容忍几帧再放弃本次锚定。 */
    private static final int MAX_STALL_FRAMES = 2;

    private final RecyclerView rv;
    private final BooleanSupplier pinnedSupplier;
    /** position → 稳定键（消息 id）；用于"同 position 是否同一条消息"校验，可返回 null。 */
    private final IntFunction<String> stableKeyProvider;
    private final ScrollAnchorState state = new ScrollAnchorState(TOLERANCE_PX);

    private boolean released;
    private boolean restoring;
    private int stallFrames;
    private final ViewTreeObserver.OnPreDrawListener preDrawListener;

    private final RecyclerView.OnScrollListener scrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                captureQuietly();
            }
        }

        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            if (!restoring) {
                captureQuietly();
            }
        }
    };

    public ScrollAnchorKeeper(RecyclerView rv, BooleanSupplier pinnedSupplier,
                              IntFunction<String> stableKeyProvider) {
        this.rv = rv;
        this.pinnedSupplier = pinnedSupplier;
        this.stableKeyProvider = stableKeyProvider;
        this.preDrawListener = () -> {
            if (released) {
                return true;
            }
            // 贴底 / 拖拽 / 惯性中：让路（用户在操作视口，补偿只会打架）
            if (pinnedSupplier != null && pinnedSupplier.getAsBoolean()) {
                return true;
            }
            if (rv.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
                return true;
            }
            androidx.recyclerview.widget.RecyclerView.LayoutManager lm = rv.getLayoutManager();
            RecyclerView.Adapter<?> adapter = rv.getAdapter();
            if (!(lm instanceof LinearLayoutManager) || adapter == null || adapter.getItemCount() == 0) {
                return true;
            }
            int position = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
            View anchor = lm.findViewByPosition(position);
            if (anchor == null) {
                captureQuietly();
                return true;
            }
            int count = adapter.getItemCount();
            String key = stableKey(position);
            int delta = state.computeDelta(anchor.getTop(), count, key);
            if (delta == ScrollAnchorState.RECAPTURE) {
                state.capture(position, anchor.getTop(), count, key);
                return true;
            }
            if (delta == 0) {
                stallFrames = 0;
                return true;
            }
            int before = rv.computeVerticalScrollOffset();
            restoring = true;
            try {
                rv.scrollBy(0, delta);
            } finally {
                restoring = false;
            }
            int after = rv.computeVerticalScrollOffset();
            if (after == before) {
                // 滚不动（已到边界/布局未就绪）：重捕一次，连续失败就接受当前位置，避免每帧空转
                if (++stallFrames >= MAX_STALL_FRAMES) {
                    stallFrames = 0;
                    captureQuietly();
                } else {
                    state.invalidate();
                }
            } else {
                stallFrames = 0;
                state.applyRestore(delta);
                captureQuietly();
            }
            return true;
        };
        rv.addOnScrollListener(scrollListener);
        rv.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
    }

    /** 立即重捕锚点（例如宿主刚做完一次整表绑定后想让当前位置成为新基准）。 */
    public void resetAnchor() {
        captureQuietly();
    }

    private void captureQuietly() {
        if (released) {
            return;
        }
        androidx.recyclerview.widget.RecyclerView.LayoutManager lm = rv.getLayoutManager();
        RecyclerView.Adapter<?> adapter = rv.getAdapter();
        if (!(lm instanceof LinearLayoutManager) || adapter == null || adapter.getItemCount() == 0) {
            state.invalidate();
            return;
        }
        int position = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
        View view = lm.findViewByPosition(position);
        if (view == null) {
            state.invalidate();
            return;
        }
        state.capture(position, view.getTop(), adapter.getItemCount(), stableKey(position));
    }

    private String stableKey(int position) {
        if (stableKeyProvider == null) {
            return null;
        }
        try {
            return stableKeyProvider.apply(position);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Activity 销毁时解绑。 */
    public void release() {
        released = true;
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
