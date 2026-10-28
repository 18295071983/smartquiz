package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 信息卡片组件：标题 + 键值对列表（Track B0-3 已接 {@link CardShell}）。
 *
 * <p>2026-10-28 版式修正（真机反馈"信息卡错位"）：值超过一行时不再靠右对齐——
 * 右对齐的多行文本每行起跳位置都不同，配上左侧被压窄、垂直居中的标签，整块看起来
 * 就是散的。现在：短值保持"左标签 / 右值"的两端对齐；长值（含换行或超过
 * {@link #LONG_VALUE_UNITS} 显示宽）改成左上对齐、值紧贴标签列，标签与值同顶。</p>
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "任务信息",
 *   "items": [{"label":"状态","value":"已完成"}, {"label":"耗时","value":"3.2s"}]
 * }
 * </pre>
 */
public class InfoCardView implements ChatComponent {

    /** 值超过这个显示宽度（CJK 记 2）就按"长文本"排版 */
    private static final int LONG_VALUE_UNITS = 24;

    @Override
    public String getType() {
        return "info_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null && data.props.optJSONArray("items") != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        JSONArray items = p.optJSONArray("items");

        CardShell shell = CardShell.create(context)
                .setHeader(p.optString("title", ""),
                        items != null && items.length() > 0
                                ? context.getString(R.string.card_shell_item_count, items.length())
                                : null);

        if (items == null || items.length() == 0) {
            shell.setState(CardShell.State.EMPTY);
            return shell;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            shell.addBody(buildRow(context, item));
        }
        return shell;
    }

    /** 一行键值：短值左标签/右值；长值左上对齐、值左对齐（多行才好读） */
    private static View buildRow(Context context, JSONObject item) {
        String labelText = item.optString("label", "");
        String valueText = item.optString("value", "");
        boolean longValue = valueText.indexOf('\n') >= 0 || visualWidth(valueText) > LONG_VALUE_UNITS;
        int vertical = longValue ? Gravity.TOP : Gravity.CENTER_VERTICAL;

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(vertical);
        row.setPadding(0, ComponentTheme.dp(context, 3), 0, ComponentTheme.dp(context, 3));

        TextView labelTv = new TextView(context);
        labelTv.setText(labelText);
        labelTv.setTextSize(ComponentTheme.bodyTextSize());
        labelTv.setTextColor(ComponentTheme.textSecondary(context));
        labelTv.setGravity(Gravity.START | vertical);
        // 长值行把标签列收窄一点，正文能多一行字；短值维持原 0.38/0.62 的两端对齐观感。
        row.addView(labelTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, longValue ? 0.32f : 0.38f));

        TextView valueTv = new TextView(context);
        valueTv.setText(valueText);
        valueTv.setTextSize(ComponentTheme.bodyTextSize());
        valueTv.setTextColor(ComponentTheme.textPrimary(context));
        valueTv.setGravity((longValue ? Gravity.START : Gravity.END) | vertical);
        if (longValue) {
            valueTv.setPadding(ComponentTheme.dp(context, 8), 0, 0, 0);
        }
        row.addView(valueTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, longValue ? 0.68f : 0.62f));
        return row;
    }

    /** 显示宽度估算：CJK/全角记 2、其余记 1（与表格卡同口径） */
    private static int visualWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        int units = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            units += (ch >= 0x2E80 && ch <= 0xA4CF) || (ch >= 0xAC00 && ch <= 0xD7A3)
                    || (ch >= 0xFF00 && ch <= 0xFF60) ? 2 : 1;
        }
        return units;
    }
}
