package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Layout 树卡片：把 render.layout 控件树渲染为聊天流卡片。
 *
 * props 支持：
 * - layout: 控件树 JSON（字符串或对象）
 * - title: 可选标题
 *
 * 用于 ui_component(action=create, component_type=自定义类型, layout={...})
 * 现场渲染布局树进聊天流——修复此前自定义 layout 卡片被 DynamicCardView
 * 兜底为"键值源码展示"或整卡失败的问题。
 */
public class LayoutCardView implements ChatComponent {

    @Override
    public String getType() {
        return "layout";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null
                && (data.props.has("layout") || data.props.has("render"));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        Object layoutObj = p.opt("layout");
        if (layoutObj == null) layoutObj = p.opt("render");
        if (layoutObj instanceof JSONObject) {
            // layout 是对象：可能直接是 {"root":...} 或 {"type":"column",...}（自动包 root）
            JSONObject lo = (JSONObject) layoutObj;
            try {
                if (!lo.has("root") && lo.has("type")) {
                    JSONObject wrapped = new JSONObject();
                    wrapped.put("root", lo);
                    layoutObj = wrapped;
                }
            } catch (Exception ignored) {
            }
        }
        try {
            java.util.Map<String, Object> viewRefs = new java.util.HashMap<>();
            View v = com.oilquiz.app.ai.python.NativeLayoutRenderer.render(
                    context, String.valueOf(layoutObj), p, viewRefs);
            if (v != null) {
                // 注册输入收集器：props.actions[].component_id 是按钮回调的目标组件，
                // 提交时 collectValues(viewRefs) 把卡片内 input/select/switch 等输入值
                // 一并回传（修复 layout 卡片输入值无法回传：按钮只回传自身 value）。
                try {
                    org.json.JSONArray actions = p.optJSONArray("actions");
                    if (actions != null) {
                        for (int i = 0; i < actions.length(); i++) {
                            org.json.JSONObject a = actions.optJSONObject(i);
                            if (a != null) {
                                String cid = a.optString("component_id", "");
                                if (!cid.isEmpty()) {
                                    final java.util.Map<String, Object> refs = viewRefs;
                                    ComponentActions.registerInputCollector(cid,
                                            () -> com.oilquiz.app.ai.python.NativeLayoutRenderer
                                                    .collectValues(refs));
                                    break;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                return v;
            }
        } catch (Throwable t) {
            android.util.Log.w("LayoutCardView", "layout 渲染失败: " + t.getMessage());
        }
        // 降级占位
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        TextView tv = new TextView(context);
        tv.setText("⚠ layout 渲染失败");
        tv.setTextSize(12);
        tv.setTextColor(ComponentColors.textTertiary(context));
        card.addView(tv);
        return card;
    }

    private static int dp(Context context, float value) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
