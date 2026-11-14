package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 对比卡（2026-11-15）：2~4 项并排/竖排的对比小卡。
 *
 * <pre>
 * {"type":"compare","props":{
 *   "title":"套餐对比",                    // 可选
 *   "items":[
 *     {"title":"基础版","badge":"¥19",
 *      "rows":[["存储","10 GB"],["成员","3 人"]]},
 *     {"title":"专业版","badge":"¥49",
 *      "rows":{"存储":"100 GB","成员":"20 人"}}
 *   ]
 * }}
 * </pre>
 *
 * <p>每项一张小卡：标题 + 可选 badge + 键值行（rows 支持二元数组或对象映射，复用
 * {@link CardKeyValues} 的解析）。超过 4 项截断并提示「还有 N 项未显示」；点击整卡复制全文。
 * 空/坏数据不崩：0 项时显示空态；不足 2 项也照常渲染（模型先给 1 项时不至于掉原始 JSON 兜底）。</p>
 */
public class CompareCardView implements ChatComponent {

    /** 最多展示 4 项；超出的截断并提示。 */
    static final int MAX_CARDS = 4;

    @Override
    public String getType() {
        return "compare";
    }

    @Override
    public boolean canRender(ComponentData data) {
        if (data == null || data.props == null) return false;
        JSONArray items = data.props.optJSONArray("items");
        return items != null && items.length() > 0;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject props = data != null && data.props != null ? data.props : new JSONObject();
        Parsed parsed = parse(props);

        CardShell shell = CardShell.create(context);
        String title = props.optString("title", "");
        if (!TextUtils.isEmpty(title)) shell.setTitle(title);
        if (parsed.cards.isEmpty()) {
            shell.setState(CardShell.State.EMPTY, "暂无对比项");
            return shell;
        }
        for (int i = 0; i < parsed.cards.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = ComponentTheme.dp(context, i == parsed.cards.size() - 1 ? 0 : 8);
            shell.addBody(buildCard(context, parsed.cards.get(i)), lp);
        }
        if (parsed.dropped() > 0) {
            shell.setState(CardShell.State.TRUNCATED,
                    "还有 " + parsed.dropped() + " 项未显示");
        }
        final String copyText = toCopyText(parsed);
        if (!copyText.isEmpty()) {
            shell.setCardClickable(() ->
                    ComponentActions.execute(context, "", copyText, "copy"));
        }
        return shell;
    }

    /** compare 的一项。 */
    static final class Comparison {
        final String title;
        final String badge;
        final List<CardKeyValues.Item> rows;
        final int rowOverflow;

        Comparison(String title, String badge, List<CardKeyValues.Item> rows, int rowOverflow) {
            this.title = title;
            this.badge = badge;
            this.rows = rows;
            this.rowOverflow = rowOverflow;
        }
    }

    /** 解析结果：前 4 项 + 有效总项数。 */
    static final class Parsed {
        final List<Comparison> cards;
        final int total;

        Parsed(List<Comparison> cards, int total) {
            this.cards = cards;
            this.total = total;
        }

        int dropped() {
            return Math.max(0, total - cards.size());
        }
    }

    static Parsed parse(JSONObject props) {
        List<Comparison> cards = new ArrayList<>();
        int total = 0;
        JSONArray items = props != null ? props.optJSONArray("items") : null;
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String title = firstNonEmpty(item, "title", "label", "name", "key");
                String badge = firstNonEmpty(item, "badge", "tag", "meta");
                Object rowsRaw = item.opt("rows");
                if (rowsRaw == null) rowsRaw = item.opt("items");
                if (rowsRaw == null) rowsRaw = item.opt("kv");
                CardKeyValues.Parsed rows = CardKeyValues.parse(rowsRaw, CardKeyValues.MAX_ITEMS);
                if (title.isEmpty() && badge.isEmpty() && rows.items.isEmpty()) continue;
                total++;
                if (cards.size() < MAX_CARDS) {
                    cards.add(new Comparison(title, badge, rows.items, rows.overflow()));
                }
            }
        }
        return new Parsed(cards, total);
    }

    private View buildCard(Context context, Comparison comparison) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ComponentTheme.dp(context, 10), ComponentTheme.dp(context, 8),
                ComponentTheme.dp(context, 10), ComponentTheme.dp(context, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ComponentTheme.surfaceVariant(context));
        bg.setCornerRadius(ComponentTheme.dp(context, 10));
        bg.setStroke(ComponentTheme.borderWidth(context), ComponentTheme.outline(context));
        card.setBackground(bg);

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = new TextView(context);
        titleView.setText(TextUtils.isEmpty(comparison.title) ? "对比项" : comparison.title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleView.setTextColor(ComponentTheme.textPrimary(context));
        titleView.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(titleView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (!TextUtils.isEmpty(comparison.badge)) {
            TextView badgeView = new TextView(context);
            badgeView.setText(comparison.badge);
            badgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            badgeView.setTextColor(ComponentTheme.brand(context));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(ComponentTheme.brandOverlay(context));
            badgeBg.setCornerRadius(ComponentTheme.dp(context, 8));
            badgeView.setBackground(badgeBg);
            badgeView.setPadding(ComponentTheme.dp(context, 6), ComponentTheme.dp(context, 2),
                    ComponentTheme.dp(context, 6), ComponentTheme.dp(context, 2));
            LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            badgeLp.setMarginStart(ComponentTheme.dp(context, 6));
            head.addView(badgeView, badgeLp);
        }
        card.addView(head);

        if (!comparison.rows.isEmpty()) {
            View spacer = new View(context);
            LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ComponentTheme.dp(context, 2));
            card.addView(spacer, spacerLp);
        }
        for (CardKeyValues.Item row : comparison.rows) {
            card.addView(KeyValueCardView.buildRow(context, row, 12f, 12f));
        }
        if (comparison.rowOverflow > 0) {
            TextView more = new TextView(context);
            more.setText("还有 " + comparison.rowOverflow + " 行");
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, ComponentTheme.metaTextSize());
            more.setTextColor(ComponentTheme.textTertiary(context));
            more.setPadding(0, ComponentTheme.dp(context, 2), 0, 0);
            card.addView(more);
        }
        return card;
    }

    private static String toCopyText(Parsed parsed) {
        StringBuilder sb = new StringBuilder();
        for (Comparison card : parsed.cards) {
            if (sb.length() > 0) sb.append("\n\n");
            if (!TextUtils.isEmpty(card.title)) sb.append(card.title);
            if (!TextUtils.isEmpty(card.badge)) sb.append("（").append(card.badge).append("）");
            String rows = CardKeyValues.toCopyText(card.rows);
            if (!rows.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(rows);
            }
        }
        return sb.toString();
    }

    private static String firstNonEmpty(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }
}
