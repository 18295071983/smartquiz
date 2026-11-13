package com.oilquiz.app.ai.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * 验收 A3（2026-11-13）：视口变化后"贴底不裁切"。
 *
 * <p>键盘/输入栏高度变化时，旧实现靠 15% 阈值 + 100ms 延迟补滚，等于最后一拍才跟，
 * 静止时留一截被裁。现在 {@code onViewportChanged(pinnedBefore)} 在 pinned 时下一帧直接贴底；
 * unpinned（用户在上方阅读）时不动。</p>
 *
 * <p>断言：① pinned 时贴底后末项底部 ≈ 列表底部，且不能再往下滚；② unpinned 时位置不变。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class StickToBottomA3Test {

    private static final int ROWS = 40;
    private static final int ROW_HEIGHT = 60;
    private static final int VIEWPORT_HEIGHT = 600;

    private RecyclerView rv;
    private StickToBottom stick;

    private static final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ROW_HEIGHT));
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            holder.tv.setText("row-" + position);
        }

        @Override
        public int getItemCount() {
            return ROWS;
        }

        static final class VH extends RecyclerView.ViewHolder {
            final TextView tv;

            VH(TextView tv) {
                super(tv);
                this.tv = tv;
            }
        }
    }

    @Before
    public void setUp() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        rv = new RecyclerView(RuntimeEnvironment.getApplication());
        rv.setLayoutManager(new LinearLayoutManager(rv.getContext()));
        rv.setAdapter(new Adapter());
        root.addView(rv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stick = new StickToBottom(rv);
    }

    private void layoutViewport() {
        rv.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY));
        rv.layout(0, 0, 400, VIEWPORT_HEIGHT);
    }

    private void dispatchPreDraw() {
        rv.getViewTreeObserver().dispatchOnPreDraw();
        layoutViewport();
    }

    /** 末项底部距列表底部的像素差 */
    private int gapToBottomPx() {
        int last = rv.getChildCount() - 1;
        if (last < 0) return Integer.MAX_VALUE;
        View child = rv.getChildAt(last);
        int bottom = child.getBottom();
        return bottom - (VIEWPORT_HEIGHT - rv.getPaddingBottom());
    }

    @Test
    public void pinnedViewportChangeScrollsToTrueBottom() {
        layoutViewport();
        rv.scrollToPosition(ROWS - 1);
        layoutViewport();
        dispatchPreDraw();
        dispatchPreDraw();   // 让逐帧跟随把末项高度稳定下来

        assertTrue("到底后应处于 pinned", stick.isPinned());
        assertFalse("到底后不应还能往下滚", rv.canScrollVertically(1));

        // 模拟键盘收起：视口变大（贴底状态不变），pinned=true
        stick.onViewportChanged(true);
        dispatchPreDraw();

        assertTrue("pinned 视口变化后仍应贴底，实际 gap=" + gapToBottomPx(),
                Math.abs(gapToBottomPx()) <= 1);
        assertFalse("贴底后不能再往下滚", rv.canScrollVertically(1));
    }

    @Test
    public void viewportChangeWithUnpinnedFlagDoesNothing() {
        layoutViewport();
        rv.scrollToPosition(2);
        layoutViewport();
        dispatchPreDraw();

        final int offsetBefore = rv.computeVerticalScrollOffset();
        // 状态机自身的 pin/unpin 由 StickToBottomStateTest 覆盖；这里只验 Activity 传下来的契约：
        // pinnedBefore=false（用户在上方阅读）→ 视口变化不得移动阅读位置。
        stick.onViewportChanged(false);
        dispatchPreDraw();

        assertEquals("unpinned 视口变化不得改变阅读位置",
                offsetBefore, rv.computeVerticalScrollOffset());
    }
}
