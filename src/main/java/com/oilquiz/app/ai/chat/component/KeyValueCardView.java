package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 键值卡（2026-11-15）：一行「名称 / 值」的紧凑信息卡。
 *
 * <p>type：{@code key_value}；别名：{@code keyValue}、{@code dsh_keyvalue}。</p>
 *
 * <pre>
 * {"type":"key_value","props":{
 *   "title":"账户信息",                       // 可选
 *   "items":[["余额","128.50"],["状态","正常"]]  // 三种形态任选
 *   // 或 [{"label":"余额","value":"128.50","tone":"good"}, ...]
 *   // 或 {"余额":"128.50","状态":"正常"}
 * }}
 * </pre>
 *
 * <p>左侧名称次要色、右侧值主色/语义色右对齐；数字样值用等宽字体。
 * 最多 24 项，超出显示「还有 N 项」；点击整卡复制整段文本。空/坏数据不崩，
 * 能解析出 0 条时显示空态；{@code canRender} 只要求 items 是<b>非空</b>数组/对象。</p>
 */
public class KeyValueCardView implements ChatComponent {

    @Override
    public String getType() {
        return "key_value";
    }

    @Override
    public boolean canRender(ComponentData data) {
        if (data == null || data.props == null) return false;
        Object raw = data.props.opt("items");
        if (raw instanceof JSONArray) return ((JSONArray) raw).length() > 0;
        if (raw instanceof JSONObject) return ((JSONObject) raw).length() > 0;
        return false;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject props = data != null && data.props != null ? data.props : new JSONObject();
        String title = props.optString("title", "");
        CardKeyValues.Parsed parsed = CardKeyValues.parse(props);

        CardShell shell = CardShell.create(context);
        if (!TextUtils.isEmpty(title)) shell.setTitle(title);
        if (parsed.items.isEmpty()) {
            shell.setState(CardShell.State.EMPTY, "暂无可显示的键值");
            return shell;
        }

        for (CardKeyValues.Item item : parsed.items) {
            shell.addBody(buildRow(context, item, ComponentTheme.bodyTextSize(),
                    ComponentTheme.bodyTextSize()));
        }
        if (parsed.overflow() > 0) {
            shell.setState(CardShell.State.TRUNCATED, "还有 " + parsed.overflow() + " 项");
        }
        final String copyText = CardKeyValues.toCopyText(parsed.items);
        if (!copyText.isEmpty()) {
            shell.setCardClickable(() ->
                    ComponentActions.execute(context, "", copyText, "copy"));
        }
        return shell;
    }

    /**
     * 一行键值（compare 小卡也复用）。
     *
     * @param labelSizeSp 名称字号
     * @param valueSizeSp 值字号
     */
    static LinearLayout buildRow(Context context, CardKeyValues.Item item,
                                 float labelSizeSp, float valueSizeSp) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, ComponentTheme.dp(context, 3), 0, ComponentTheme.dp(context, 3));

        TextView labelView = new TextView(context);
        labelView.setText(item != null ? item.label : "");
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSizeSp);
        labelView.setTextColor(ComponentTheme.textSecondary(context));
        labelView.setMaxLines(2);
        labelView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(labelView, labelLp);

        TextView valueView = new TextView(context);
        String value = item != null ? item.value : "";
        valueView.setText(value);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSizeSp);
        Integer toneColor = item != null
                ? ComponentColors.toneColor(context, item.tone) : null;
        valueView.setTextColor(toneColor != null ? toneColor : ComponentTheme.textPrimary(context));
        valueView.setGravity(Gravity.END);
        valueView.setMaxLines(3);
        if (CardKeyValues.looksNumeric(value)) {
            valueView.setTypeface(android.graphics.Typeface.MONOSPACE);
        }
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        valueLp.setMarginStart(ComponentTheme.dp(context, 8));
        row.addView(valueView, valueLp);
        return row;
    }
}
