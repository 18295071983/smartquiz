package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 信息卡片组件：标题 + 键值对列表（Track B0-3 已接 {@link CardShell}）。
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

    /** 一行键值：左标签弱色、右值主色，两端对齐 */
    private static View buildRow(Context context, JSONObject item) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, ComponentTheme.dp(context, 3), 0, ComponentTheme.dp(context, 3));

        TextView labelTv = new TextView(context);
        labelTv.setText(item.optString("label", ""));
        labelTv.setTextSize(ComponentTheme.bodyTextSize());
        labelTv.setTextColor(ComponentTheme.textSecondary(context));
        labelTv.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        row.addView(labelTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.38f));

        TextView valueTv = new TextView(context);
        valueTv.setText(item.optString("value", ""));
        valueTv.setTextSize(ComponentTheme.bodyTextSize());
        valueTv.setTextColor(ComponentTheme.textPrimary(context));
        valueTv.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
        row.addView(valueTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.62f));
        return row;
    }
}
