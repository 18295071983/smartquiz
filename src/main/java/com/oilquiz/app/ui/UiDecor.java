package com.oilquiz.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;

/**
 * 列表与加载态的基础设施（2026-11-16）。
 *
 * <p><b>为什么补这个</b>：审计发现项目里 RecyclerView 用了 446 处，但
 * <b>ItemDecoration 0 处</b> —— 所有列表的分隔线、分组间距、网格间隙都是靠每行 margin
 * 硬凑出来的，导致同一列表的首行/末行多出空档、分割线宽度对不齐。
 * 另外空状态/加载态也是各页面各写。</p>
 *
 * <p>这里提供三件事，全部走 token 与主题色：</p>
 * <ul>
 *   <li>{@link #divider} 列表分隔线（可配粗细/颜色/首末行是否绘制）；</li>
 *   <li>{@link #gridSpacing} 网格间隙（等距分布，不再手写 margin）；</li>
 *   <li>{@link #skeleton} 骨架屏（占位块 + 淡入淡出呼吸动画，无第三方依赖）。</li>
 * </ul>
 */
public final class UiDecor {

    private UiDecor() {
    }

    /**
     * 列表分隔线：在每个 item 底部画一条 1px 分隔线，最后一行不画。
     *
     * @param insetStart 左侧缩进（与文字对齐用，0 = 通栏）
     */
    public static RecyclerView.ItemDecoration divider(final Context context, final int insetStart) {
        return new RecyclerView.ItemDecoration() {
            @Override
            public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
                int count = parent.getChildCount();
                for (int i = 0; i < count; i++) {
                    View child = parent.getChildAt(i);
                    int pos = parent.getChildAdapterPosition(child);
                    if (pos == RecyclerView.NO_POSITION
                            || pos == parent.getAdapter().getItemCount() - 1) {
                        continue;
                    }
                    int y = child.getBottom();
                    c.drawRect(child.getLeft() + insetStart, y,
                            child.getRight(), y + 1, dividerPaint(context));
                }
            }
        };
    }

    /** 网格等距间隙：不依赖 item 自身 margin，最后一行/列不额外留白。 */
    public static RecyclerView.ItemDecoration gridSpacing(final Context context, final int spanCount) {
        final int gap = Tokens.space(context, Tokens.SPACE_MD);
        return new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                int position = parent.getChildAdapterPosition(view);
                if (position == RecyclerView.NO_POSITION) {
                    return;
                }
                RecyclerView.LayoutManager lm = parent.getLayoutManager();
                int span = spanCount;
                if (lm instanceof GridLayoutManager) {
                    span = ((GridLayoutManager) lm).getSpanCount();
                }
                int column = position % span;
                outRect.left = column == 0 ? 0 : gap / 2;
                outRect.right = column == span - 1 ? 0 : gap / 2;
                outRect.top = position < span ? 0 : gap;
                outRect.bottom = 0;
            }
        };
    }

    private static android.graphics.Paint dividerPaint(Context context) {
        android.graphics.Paint p = new android.graphics.Paint();
        p.setColor(ThemeColors.get(context, R.color.divider));
        p.setStyle(android.graphics.Paint.Style.FILL);
        return p;
    }

    /** 新建一个骨架屏占位块（呼吸动画由 {@link #startSkeleton} 启动）。 */
    public static View skeleton(Context context, int heightPx) {
        View v = new View(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Tokens.radius(context, Tokens.RADIUS_SM));
        bg.setColor(ThemeColors.get(context, R.color.surface_variant));
        v.setBackground(bg);
        v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx));
        return v;
    }

    /** 骨架屏呼吸动画：alpha 0.4 ↔ 1.0 来回，模拟加载占位。 */
    public static void startSkeleton(View view) {
        if (view == null) {
            return;
        }
        android.animation.ObjectAnimator anim = android.animation.ObjectAnimator.ofFloat(
                view, View.ALPHA, 0.45f, 1f);
        anim.setDuration(900);
        anim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        anim.start();
        view.setTag(R.id.switchSystemDynamic, anim);
    }

    /** 停止骨架屏动画（数据加载完成后调用，移除占位）。 */
    public static void stopSkeleton(View view) {
        if (view == null) {
            return;
        }
        Object tag = view.getTag(R.id.switchSystemDynamic);
        if (tag instanceof android.animation.Animator) {
            ((android.animation.Animator) tag).cancel();
        }
    }

    /**
     * 延迟加载布局：ViewStub 的封装 —— 首次用到时才 inflate，之后复用。
     * 用于列表项里的重型区块（图表/媒体/复杂卡片）。
     */
    public static View inflateOnce(ViewStub stub) {
        if (stub == null) {
            return null;
        }
        // ViewStub 只能 inflate 一次，第二次调用会抛 IllegalStateException；
        // 已 inflate 后 ViewStub 自身从视图树移除（getParent() == null），此时返回 null 让调用方跳过。
        if (stub.getParent() == null) {
            return null;
        }
        return stub.inflate();
    }
}
