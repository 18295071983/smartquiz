package com.oilquiz.app.ui;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
import com.oilquiz.app.theme.Tokens;

/**
 * 列表交互补充能力（2026-11-16 第二批）。
 *
 * <p>补齐审计 C 类里还剩的几项：长按菜单、滑动删除、列表项动画、IME 适配、无障碍描述、轻提示。</p>
 * <ul>
 *   <li>{@link #longPressMenu} 长按菜单：统一用 BottomSheet 呈现（不再各页写 ContextMenu）；</li>
 *   <li>{@link #swipeToDelete} 滑动删除：ItemTouchHelper 封装，带背景与回弹；</li>
 *   <li>{@link #defaultItemAnimator} 列表动画：统一默认增删动画（此前 4 处各写）；</li>
 *   <li>{@link #bindImePadding} 键盘适配：IME insets 自动转 padding（各页不再手写）；</li>
 *   <li>{@link #describe} 无障碍描述：一行补齐 contentDescription；</li>
 *   <li>{@link #haptic} 触感反馈：统一调用点。</li>
 * </ul>
 */
public final class UiActions {

    private UiActions() {
    }

    // ------------------------------------------------------------------
    // 长按菜单（BottomSheet 统一呈现）
    // ------------------------------------------------------------------

    /** 长按菜单回调 */
    public interface MenuCallback {
        void onMenuSelected(int index);
    }

    /**
     * 绑定长按 → 弹出底部菜单。
     *
     * <p>项目里 BottomSheet 用了 28 处、PopupMenu 8 处、ContextMenu 4 处 —— 三套并存。
     * 这里统一成 BottomSheet（触摸友好、深色/OLED 下表现稳定），新页面直接用。</p>
     */
    public static void longPressMenu(View view, String title, String[] actions, MenuCallback callback) {
        if (view == null || actions == null || actions.length == 0) {
            return;
        }
        UiFx.longPress(view, () -> {
            haptic(view);
            showSheet(view.getContext(), title, actions, callback);
        });
    }

    private static void showSheet(Context context, String title, String[] actions, MenuCallback callback) {
        final com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(context);
        android.widget.LinearLayout box = new android.widget.LinearLayout(context);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Tokens.space(context, Tokens.SPACE_XL);
        box.setPadding(pad, Tokens.space(context, Tokens.SPACE_LG), pad, pad);
        box.setBackgroundColor(ThemeColors.get(context, R.color.card_background));

        if (title != null && !title.isEmpty()) {
            android.widget.TextView t = new android.widget.TextView(context);
            t.setText(title);
            Tokens.applyText(t, Tokens.TEXT_SUBTITLE, Tokens.Weight.SEMIBOLD,
                    ThemeColors.get(context, R.color.text_primary));
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, Tokens.space(context, Tokens.SPACE_MD));
            t.setLayoutParams(lp);
            box.addView(t);
        }
        for (int i = 0; i < actions.length; i++) {
            final int index = i;
            android.widget.TextView item = new android.widget.TextView(context);
            item.setText(actions[i]);
            Tokens.applyText(item, Tokens.TEXT_BODY_LG, Tokens.Weight.REGULAR,
                    ThemeColors.get(context, R.color.text_primary));
            int p = Tokens.space(context, Tokens.SPACE_LG);
            item.setPadding(p, p, p, p);
            UiFx.click(item, () -> {
                dialog.dismiss();
                if (callback != null) {
                    callback.onMenuSelected(index);
                }
            });
            box.addView(item);
        }
        dialog.setContentView(box);
        dialog.show();
    }

    // ------------------------------------------------------------------
    // 滑动删除
    // ------------------------------------------------------------------

    /** 滑动删除回调 */
    public interface OnSwiped {
        void onSwiped(int position);
    }

    /**
     * 绑定滑动删除：左滑（或右滑）删除，带主题色背景与红色提示。
     * 返回 ItemTouchHelper 方便调用方后续 attach 到别的 RecyclerView。
     */
    public static ItemTouchHelper swipeToDelete(final RecyclerView recyclerView, final OnSwiped onSwiped) {
        ItemTouchHelper.SimpleCallback callback = new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                int pos = vh.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && onSwiped != null) {
                    onSwiped.onSwiped(pos);
                }
            }

            @Override
            public void onChildDraw(@NonNull android.graphics.Canvas c,
                                    @NonNull RecyclerView rv,
                                    @NonNull RecyclerView.ViewHolder vh,
                                    float dX, float dY, int actionState, boolean isCurrentlyActive) {
                View item = vh.itemView;
                // 背景：危险色底，提示"删除"
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setColor(ThemeColors.get(rv.getContext(), R.color.error));
                c.drawRect(item.getRight() + Math.min(dX, 0), item.getTop(), item.getRight(), item.getBottom(), paint);
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive);
            }
        };
        ItemTouchHelper helper = new ItemTouchHelper(callback);
        helper.attachToRecyclerView(recyclerView);
        return helper;
    }

    /** 统一列表项动画（增删淡入淡出），避免各页各写。 */
    public static void defaultItemAnimator(RecyclerView recyclerView) {
        if (recyclerView == null) {
            return;
        }
        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setAddDuration(200);
        animator.setRemoveDuration(200);
        animator.setMoveDuration(200);
        animator.setChangeDuration(150);
        recyclerView.setItemAnimator(animator);
    }

    // ------------------------------------------------------------------
    // 键盘 / 无障碍 / 触感
    // ------------------------------------------------------------------

    /**
     * 把 IME（键盘）高度转成 View 的底部 padding，键盘弹出时内容自动上移。
     *
     * <p>项目里各页手写 insets 监听，这里统一一份：view 的初始 padding 会保留。</p>
     */
    public static void bindImePadding(final View view) {
        if (view == null) {
            return;
        }
        final int initialBottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int system = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    initialBottom + Math.max(ime, system));
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /** 无障碍描述：一行补齐 contentDescription（读屏用）。 */
    public static void describe(View view, String description) {
        if (view == null) {
            return;
        }
        view.setContentDescription(description);
        ViewCompat.setAccessibilityDelegate(view, null);
    }

    /** 触感反馈（长按/删除等破坏性操作前调用）。 */
    public static void haptic(View view) {
        if (view == null) {
            return;
        }
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    /** 统一的轻提示（避免各页直接 new Toast）。 */
    public static void toast(View anchor, String text) {
        if (anchor == null || text == null) {
            return;
        }
        Toast.makeText(anchor.getContext(), text, Toast.LENGTH_SHORT).show();
    }
}
