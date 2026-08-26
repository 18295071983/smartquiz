package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Layout 原语桥接卡片：把 {@code NativeLayoutRenderer} 的单控件渲染能力暴露为聊天流卡片类型。
 *
 * 注册类型 = layout 控件名（table/steps/timeline/alert/stat/qrcode/barcode/calendar/
 * toggle/stepper/slider_range/tag_input/line_chart/bar_chart/pie_chart/sparkline/
 * countdown/breadcrumb/avatar_group/progress_ring/notice/empty 等）。
 * props 直接作为单控件节点渲染——Agent 用 ui_component(component_type=line_chart,
 * props={categories,series}) 即可在聊天流生成图表/二维码/日历等，无需写 layout 树。
 *
 * 值收集：单控件 key 值由 NativeLayoutRenderer.render 的 viewRefs 收集
 * （本卡片主要用于展示类，交互控件请走 ui_component 弹窗/插件链路）。
 */
public class LayoutPrimitiveCardView implements ChatComponent {

    /** 由 NativeLayoutRenderer 支持的、可作聊天流卡片的控件类型（注册用） */
    public static final String[] BRIDGED_TYPES = {
            // 数据展示
            "table", "steps", "timeline", "alert", "stat", "empty", "notice", "progress_ring",
            // 图表
            "line_chart", "bar_chart", "pie_chart", "sparkline",
            // 工具
            "qrcode", "barcode", "countdown", "calendar", "breadcrumb",
            // 输入/选择（展示态）
            "toggle", "stepper", "slider_range", "tag_input",
            "checkbox_group", "radio_group", "search_bar",
            // 展示增强
            "avatar_group", "badge", "quote", "icon", "marquee",
            // 容器（内容为 props 描述时）
            "accordion", "carousel", "stack"
    };

    private final String type;

    public LayoutPrimitiveCardView(String type) {
        this.type = type;
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        // 构造单控件节点：{"type": <type>, ...props}
        JSONObject node = new JSONObject();
        try {
            node.put("type", type);
            java.util.Iterator<String> keys = p.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                node.put(k, p.get(k));
            }
        } catch (Exception ignored) {
        }
        try {
            View v = com.oilquiz.app.ai.python.NativeLayoutRenderer.render(
                    context, new org.json.JSONObject().put("root", node), null, new java.util.HashMap<>());
            if (v != null) {
                return v;
            }
        } catch (Throwable t) {
            android.util.Log.w("LayoutPrimitiveCardView", type + " 渲染失败: " + t.getMessage());
        }
        // 降级占位
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        TextView tv = new TextView(context);
        tv.setText("⚠ " + type + " 渲染失败");
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
