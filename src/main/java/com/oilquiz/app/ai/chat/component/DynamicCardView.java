package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * 通用兜底组件（动态/未注册类型）。
 *
 * <p>Agent 或工具现场注册的自定义类型没有专用渲染器时，也必须能在对话里看得见：
 * 这里以 {@link CardShell} 展示 标题 + 已知结构（content/items/动作），
 * 其余字段不再像以前那样整棵 JSON 摊平进正文，而是收进「查看原始 JSON」折叠区
 * （B1-3）：默认收起、展开后限高滚动，长 props 不会把消息流顶成一屏键值对。
 *
 * <p>状态位：无内容 → {@link CardShell.State#EMPTY}；字段过多 → 折叠而不是截断。
 */
public class DynamicCardView implements ChatComponent {

    /** 顶层已单独展示的键，不再进"原始数据" */
    private static final Set<String> KNOWN_KEYS = new HashSet<>(java.util.Arrays.asList(
            "title", "content", "message", "text", "description", "summary", "items",
            "actions", "buttons", "options", "style"));

    /** 折叠区里最多展示的 JSON 字符数（超出截断并提示） */
    private static final int MAX_RAW_CHARS = 4000;

    @Override
    public String getType() {
        return "*dynamic*"; // 通配兜底，不参与注册表匹配
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();

        String title = p.optString("title", "");
        CardShell shell = CardShell.create(context)
                .setHeaderIcon(R.drawable.ic_ai_generate)
                .setTitle(TextUtils.isEmpty(title) ? data.type : title);

        boolean hasBody = false;
        String text = firstNonEmpty(p, "content", "message", "text", "description", "summary");
        if (text != null) {
            shell.addBodyText(text, ComponentTheme.bodyTextSize(),
                    ComponentTheme.textPrimary(context), false);
            hasBody = true;
        }
        JSONArray items = p.optJSONArray("items");
        if (items != null && items.length() > 0) {
            appendItems(shell, context, items);
            hasBody = true;
        }

        // 未知字段：收进「查看原始 JSON」，默认折叠
        JSONObject extras = subtract(p, KNOWN_KEYS);
        if (extras.length() > 0) {
            appendRawSection(shell, context, extras);
            hasBody = true;
        }

        // 动作：footer（与 props.actions/buttons/options 统一口径）
        shell.addActions(normalizeActions(p));

        if (!hasBody) {
            shell.setState(CardShell.State.EMPTY);
        }
        return shell;
    }

    /** items: [{"label":..,"value":..}] → 键值行；嵌套值走 JSON 文本 */
    private void appendItems(CardShell shell, Context context, JSONArray items) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                shell.addBodyText(String.valueOf(items.opt(i)), ComponentTheme.bodyTextSize(),
                        ComponentTheme.textPrimary(context), false);
                continue;
            }
            String label = item.optString("label", "");
            Object value = item.has("value") ? item.opt("value") : null;
            String valueText = (value instanceof JSONObject || value instanceof JSONArray)
                    ? prettyJson(value)
                    : (value == null ? "" : String.valueOf(value));
            shell.addBody(keyValueRow(context, label, valueText));
        }
    }

    /** 折叠的原始数据区：默认收起，展开后限高内部滚动 */
    private void appendRawSection(CardShell shell, final Context context, final JSONObject extras) {
        TextView toggle = new TextView(context);
        toggle.setText(context.getString(R.string.component_raw_json_show, extras.length()));
        toggle.setTextSize(ComponentTheme.metaTextSize());
        toggle.setTextColor(ComponentTheme.textSecondary(context));
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setMinHeight(ComponentTheme.dp(context, 32));
        toggle.setClickable(true);
        toggle.setFocusable(true);

        final LinearLayout rawBox = new LinearLayout(context);
        rawBox.setOrientation(LinearLayout.VERTICAL);
        rawBox.setVisibility(View.GONE);
        String raw = prettyJson(extras);
        boolean clipped = raw.length() > MAX_RAW_CHARS;
        if (clipped) {
            raw = raw.substring(0, MAX_RAW_CHARS);
        }
        CardShell.MaxHeightScrollView scroller = new CardShell.MaxHeightScrollView(context,
                ComponentTheme.dp(context, 240));
        TextView rawTv = new TextView(context);
        rawTv.setText(clipped
                ? raw + context.getString(R.string.component_raw_json_clipped)
                : raw);
        rawTv.setTextSize(ComponentTheme.metaTextSize());
        rawTv.setTextColor(ComponentTheme.textSecondary(context));
        rawTv.setTextIsSelectable(true);
        rawTv.setTypeface(android.graphics.Typeface.MONOSPACE);
        rawTv.setLineSpacing(0, 1.25f);
        scroller.addView(rawTv, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        rawBox.addView(scroller);

        toggle.setOnClickListener(v -> {
            boolean expand = rawBox.getVisibility() != View.VISIBLE;
            rawBox.setVisibility(expand ? View.VISIBLE : View.GONE);
            toggle.setText(context.getString(expand
                    ? R.string.component_raw_json_hide : R.string.component_raw_json_show,
                    extras.length()));
        });

        shell.addBody(toggle);
        shell.addBody(rawBox);
    }

    /** actions/buttons/options 三种写法归一成 [{label,...}] */
    private static JSONArray normalizeActions(JSONObject p) {
        JSONArray actions = p.optJSONArray("actions");
        if (actions != null && actions.length() > 0) return actions;
        JSONArray buttons = p.optJSONArray("buttons");
        if (buttons == null || buttons.length() == 0) buttons = p.optJSONArray("options");
        JSONArray normalized = new JSONArray();
        if (buttons == null) return normalized;
        for (int i = 0; i < buttons.length(); i++) {
            Object b = buttons.opt(i);
            if (b instanceof JSONObject) {
                normalized.put(b);
            } else if (b != null) {
                try {
                    normalized.put(new JSONObject().put("label", String.valueOf(b)));
                } catch (org.json.JSONException ignored) {
                }
            }
        }
        return normalized;
    }

    private static View keyValueRow(Context context, String label, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, ComponentTheme.dp(context, 3), 0, ComponentTheme.dp(context, 3));

        TextView labelTv = new TextView(context);
        labelTv.setText(TextUtils.isEmpty(label) ? "" : label + "：");
        labelTv.setTextSize(ComponentTheme.bodyTextSize());
        labelTv.setTextColor(ComponentTheme.textSecondary(context));
        row.addView(labelTv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView valueTv = new TextView(context);
        valueTv.setText(value);
        valueTv.setTextSize(ComponentTheme.bodyTextSize());
        valueTv.setTextColor(ComponentTheme.textPrimary(context));
        valueTv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        valueTv.setTextIsSelectable(true);
        row.addView(valueTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static JSONObject subtract(JSONObject p, Set<String> skip) {
        JSONObject out = new JSONObject();
        Iterator<String> it = p.keys();
        while (it.hasNext()) {
            String key = it.next();
            if (skip.contains(key)) continue;
            try {
                out.put(key, p.get(key));
            } catch (org.json.JSONException ignored) {
            }
        }
        return out;
    }

    /** 缩进 JSON 文本（Android org.json 的 toString(int) 会抛异常，容错降级为无缩进） */
    private static String prettyJson(Object o) {
        try {
            if (o instanceof JSONArray) return ((JSONArray) o).toString(2);
            if (o instanceof JSONObject) return ((JSONObject) o).toString(2);
            return String.valueOf(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private static String firstNonEmpty(JSONObject p, String... keys) {
        for (String k : keys) {
            String v = p.optString(k, "");
            if (!v.trim().isEmpty()) return v;
        }
        return null;
    }
}
