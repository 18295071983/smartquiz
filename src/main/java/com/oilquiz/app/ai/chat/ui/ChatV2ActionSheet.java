package com.oilquiz.app.ai.chat.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.oilquiz.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * v2 的"动作面板"（demo {@code #sheet-message}：标题 + 若干"图标 + 文字"行）。
 *
 * <p>为什么做成通用类而不是在 {@code ChatAdapter} 里内联一段：
 * 顶栏「⋯」的信息面板、长按菜单、消息操作面板都是同一个形状（一行一个动作），
 * 抽出来后可复用同一套密度/配色，也避免"每加一个面板就手搓一遍 LinearLayout"。</p>
 *
 * <p>刻意不接管动作语义：每个 {@link Item} 只带一个 {@link View.OnClickListener}，
 * 调用方传进来的通常是"旧版那颗同名按钮的 performClick()"
 * （与 {@link ChatUiV2Chrome} 里工具抽屉/清空对话的复用方式一致），
 * 这样面板只改呈现，不改行为。</p>
 */
public final class ChatV2ActionSheet {

    /** 面板里的一行：图标 + 文案 + 点击动作 */
    public static final class Item {
        @DrawableRes
        public final int iconRes;
        @StringRes
        public final int titleRes;
        /** 可选的动态文案（例如"朗读/停止"随状态变）；为 null 时用 titleRes */
        public final CharSequence dynamicTitle;
        public final View.OnClickListener onClick;

        public Item(@DrawableRes int iconRes, @StringRes int titleRes, View.OnClickListener onClick) {
            this(iconRes, titleRes, null, onClick);
        }

        public Item(@DrawableRes int iconRes, @StringRes int titleRes,
                    CharSequence dynamicTitle, View.OnClickListener onClick) {
            this.iconRes = iconRes;
            this.titleRes = titleRes;
            this.dynamicTitle = dynamicTitle;
            this.onClick = onClick;
        }
    }

    private ChatV2ActionSheet() {
    }

    /** 打开动作面板；上下文缺失或没有可用动作时静默返回（不弹空面板）。 */
    public static void show(Context ctx, @StringRes int titleRes, List<Item> items) {
        if (ctx == null || items == null || items.isEmpty()) return;
        try {
            BottomSheetDialog sheet = new BottomSheetDialog(ctx);
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            int padH = dp(ctx, 18);
            box.setPadding(padH, dp(ctx, 10), padH, dp(ctx, 14));

            TextView title = new TextView(ctx);
            title.setText(titleRes);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            title.setTextColor(ContextCompat.getColor(ctx, R.color.chat_v2_text));
            title.setPadding(0, 0, 0, dp(ctx, 8));
            box.addView(title);

            List<Item> usable = new ArrayList<>();
            for (Item item : items) {
                if (item != null && item.onClick != null) usable.add(item);
            }
            for (Item item : usable) {
                box.addView(createRow(ctx, item, sheet));
            }
            sheet.setContentView(box);
            sheet.show();
        } catch (Throwable t) {
            // 面板是入口不是功能本体：起不来就退回提示，不能把消息渲染带崩
            Toast.makeText(ctx, R.string.chat_v2_no_data, Toast.LENGTH_SHORT).show();
        }
    }

    private static View createRow(Context ctx, Item item, BottomSheetDialog sheet) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 46)));
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0);

        ImageView icon = new ImageView(ctx);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20));
        iconLp.setMarginEnd(dp(ctx, 12));
        icon.setLayoutParams(iconLp);
        icon.setImageResource(item.iconRes);
        icon.setColorFilter(ContextCompat.getColor(ctx, R.color.chat_v2_text_2));
        row.addView(icon);

        TextView label = new TextView(ctx);
        CharSequence text = !TextUtils.isEmpty(item.dynamicTitle)
                ? item.dynamicTitle
                : (item.titleRes != 0 ? ctx.getString(item.titleRes) : null);
        if (!TextUtils.isEmpty(text)) label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        label.setTextColor(ContextCompat.getColor(ctx, R.color.chat_v2_text));
        row.addView(label);

        row.setOnClickListener(v -> {
            sheet.dismiss();
            item.onClick.onClick(v);
        });
        return row;
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
