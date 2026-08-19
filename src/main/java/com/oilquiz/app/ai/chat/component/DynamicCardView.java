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

import java.util.Iterator;

/**
 * 通用兜底组件（动态组件渲染）。
 *
 * Agent/工具动态创建的自定义组件类型（未注册专用渲染器）也必须在对话界面可用：
 * 以通用卡片展示标题 + 数据（键值行/数组/嵌套 JSON），保证任何动态类型都不静默消失。
 * 内置 20 种组件走专用渲染，本组件仅作为 ComponentRegistry 未命中时的兜底。
 */
public class DynamicCardView implements ChatComponent {

    /** 深度缩进（嵌套对象每层两个空格） */
    private static final int DEPTH_INDENT_DP = 6;

    @Override
    public String getType() {
        return "*dynamic*"; // 通配兜底，不参与注册表匹配
    }

    @Override
    public boolean canRender(ComponentData data) {
        // 任何组件数据都可兜底展示
        return data != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context));

        // 标题：优先 props.title，否则显示组件类型
        String title = p.optString("title", "");
        if (TextUtils.isEmpty(title)) {
            title = "📦 " + data.type;
        }
        TextView titleTv = new TextView(context);
        titleTv.setText(title);
        titleTv.setTextSize(14);
        titleTv.setTextColor(ComponentColors.textPrimary(context));
        titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleTv.setPadding(0, 0, 0, dp(context, 6));
        titleTv.setTextIsSelectable(true);
        card.addView(titleTv);

        // 内容：优先常见结构（content/message/text 字符串、items 键值数组、list 数组），
        // 其余把 props 逐层展开为键值行
        String text = firstNonEmpty(p, "content", "message", "text", "description", "summary");
        if (text != null) {
            addText(card, context, text, 0);
        }
        JSONArray items = p.optJSONArray("items");
        if (items != null) {
            appendItems(card, context, items);
        }
        appendProps(card, context, p, 0, new java.util.HashSet<String>(java.util.Arrays.asList(
                "title", "content", "message", "text", "description", "summary", "items")));

        // 动作行：自定义组件带 actions 时渲染真实可点击按钮（打开链接/复制等）
        JSONArray actions = p.optJSONArray("actions");
        if (actions == null || actions.length() == 0) {
            // 兼容对话框类自定义组件：buttons/options 数组（字符串或 {label,...}）
            JSONArray buttons = p.optJSONArray("buttons");
            if (buttons == null || buttons.length() == 0) {
                buttons = p.optJSONArray("options");
            }
            if (buttons != null && buttons.length() > 0) {
                org.json.JSONArray normalized = new org.json.JSONArray();
                for (int i = 0; i < buttons.length(); i++) {
                    Object b = buttons.opt(i);
                    if (b instanceof JSONObject) {
                        normalized.put(b);
                    } else if (b != null) {
                        try {
                            JSONObject jo = new JSONObject();
                            jo.put("label", String.valueOf(b));
                            normalized.put(jo);
                        } catch (org.json.JSONException ignored) {
                        }
                    }
                }
                actions = normalized;
            }
        }
        ComponentActions.renderActions(card, context, actions);

        // 空数据也给出可读占位
        if (card.getChildCount() <= 1) {
            addText(card, context, "（空内容）", 0);
        }
        return card;
    }

    /** 键值行列表渲染（items: [{"label":..,"value":..}]） */
    private void appendItems(LinearLayout card, Context context, JSONArray items) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String label = item.optString("label", "");
            Object value = item.has("value") ? item.opt("value") : null;
            if (value instanceof JSONObject || value instanceof JSONArray) {
                if (!TextUtils.isEmpty(label)) {
                    addText(card, context, "· " + label, 0);
                }
                appendValue(card, context, value, 1);
            } else {
                addKeyValue(card, context, label, value == null ? "" : String.valueOf(value), 0);
            }
        }
    }

    /** 把 props 逐层展开为键值行（跳过已单独展示的顶层键） */
    private void appendProps(LinearLayout card, Context context, JSONObject obj, int depth,
                             java.util.Set<String> skippedTopKeys) {
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String key = it.next();
            if (depth == 0 && skippedTopKeys.contains(key)) continue;
            Object v = obj.opt(key);
            if (v instanceof JSONObject) {
                addText(card, context, "· " + key, depth);
                appendProps(card, context, (JSONObject) v, depth + 1, null);
            } else if (v instanceof JSONArray) {
                addText(card, context, "· " + key, depth);
                appendValue(card, context, v, depth + 1);
            } else {
                addKeyValue(card, context, key, v == null ? "" : String.valueOf(v), depth);
            }
        }
    }

    /** 数组/嵌套对象值：简单数组 join，复杂值缩进 JSON 文本 */
    private void appendValue(LinearLayout card, Context context, Object value, int depth) {
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            if (arr.length() == 0) {
                addText(card, context, "（空）", depth);
                return;
            }
            boolean simple = true;
            for (int i = 0; i < arr.length(); i++) {
                Object v = arr.opt(i);
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    simple = false;
                    break;
                }
            }
            if (simple) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arr.length(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(String.valueOf(arr.opt(i)));
                }
                addText(card, context, sb.toString(), depth);
            } else {
                addText(card, context, prettyJson(arr), depth);
            }
        } else if (value instanceof JSONObject) {
            addText(card, context, prettyJson((JSONObject) value), depth);
        } else {
            addText(card, context, String.valueOf(value), depth);
        }
    }

    /** 缩进 JSON 文本（Android org.json 的 toString(int) 抛异常，此处容错降级为无缩进） */
    private static String prettyJson(Object o) {
        try {
            if (o instanceof JSONArray) return ((JSONArray) o).toString(1);
            if (o instanceof JSONObject) return ((JSONObject) o).toString(1);
            return String.valueOf(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    private void addKeyValue(LinearLayout card, Context context, String label, String value, int depth) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int padLeft = dp(context, DEPTH_INDENT_DP * depth);
        row.setPadding(padLeft, dp(context, 3), 0, dp(context, 3));

        TextView labelTv = new TextView(context);
        labelTv.setText(TextUtils.isEmpty(label) ? "" : label + "：");
        labelTv.setTextSize(13);
        labelTv.setTextColor(ComponentColors.textSecondary(context));
        row.addView(labelTv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView valueTv = new TextView(context);
        valueTv.setText(value);
        valueTv.setTextSize(13);
        valueTv.setTextColor(ComponentColors.textPrimary(context));
        valueTv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        valueTv.setTextIsSelectable(true);
        row.addView(valueTv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row);
    }

    private void addText(LinearLayout card, Context context, String text, int depth) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(ComponentColors.textPrimary(context));
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(context, DEPTH_INDENT_DP * depth), dp(context, 2), 0, dp(context, 2));
        card.addView(tv);
    }

    private static String firstNonEmpty(JSONObject p, String... keys) {
        for (String k : keys) {
            String v = p.optString(k, "");
            if (!v.trim().isEmpty()) return v;
        }
        return null;
    }

    private static android.graphics.drawable.Drawable cardBackground(Context context) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(ComponentColors.background(context));
        gd.setCornerRadius(dp(context, 10));
        gd.setStroke(dp(context, 1), ComponentColors.border(context));
        return gd;
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
