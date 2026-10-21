package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.ImageViewCompat;

import com.oilquiz.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 步骤流程卡片组件：多步骤状态展示（计划/流程/检查清单，Track B0-3 已接 {@link CardShell}）。
 *
 * <p>状态指示原来是 ✅/⏳/❌/○ 四个字符——在不同字体下大小不一、深色模式对比度不可控，
 * 换成矢量图标 + 主题状态色。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "title": "执行流程",
 *   "steps": [
 *     {"title":"解析参数","description":"...","status":"done"},   // done/current/todo/failed
 *     ...
 *   ]
 * }
 * </pre>
 */
public class StepsCardView implements ChatComponent {

    @Override
    public String getType() {
        return "steps_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && stepsOf(normalize(data.props)) != null;
    }

    /**
     * 别名形状归一：{@code timeline} 已被 {@link ComponentTypes} 归一到本卡（"原生实现赢名字"），
     * 但工具说明教它的参数仍是原语那套 {@code items}。名字换了、形状没换 → 模型照说明传就
     * {@code canRender=false} → 静默落 {@code DynamicCardView} 贴原始 JSON。这里认 {@code items} 作 {@code steps}。
     *
     * <p>返回副本，绝不原地写回 {@code data.props}（它会被持久化）。</p>
     */
    static JSONObject normalize(JSONObject p) {
        if (p == null) {
            return null;
        }
        if (p.optJSONArray("steps") != null) {
            return p; // 已是本卡形状，省一次拷贝
        }
        JSONArray items = p.optJSONArray("items");
        if (items == null) {
            return p;
        }
        try {
            JSONObject out = new JSONObject(p.toString());
            out.put("steps", items);
            return out;
        } catch (Exception e) {
            return p;
        }
    }

    private static JSONArray stepsOf(JSONObject p) {
        return p == null ? null : p.optJSONArray("steps");
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = normalize(data.props != null ? data.props : new JSONObject());
        JSONArray steps = p.optJSONArray("steps");

        CardShell shell = CardShell.create(context)
                .setHeaderIcon(R.drawable.ic_home_plan)
                .setHeader(p.optString("title", ""),
                        steps != null && steps.length() > 0
                                ? context.getString(R.string.card_shell_step_count, steps.length())
                                : null);

        if (steps == null || steps.length() == 0) {
            shell.setState(CardShell.State.EMPTY);
            return shell;
        }
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            shell.addBody(buildRow(context, step, i));
        }
        return shell;
    }

    /** 单步：状态图标 + 标题 + 描述 */
    private View buildRow(Context context, JSONObject step, int index) {
        String status = step.optString("status", "todo");

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, ComponentTheme.dp(context, 3), 0, ComponentTheme.dp(context, 3));

        ImageView indicator = new ImageView(context);
        int size = ComponentTheme.dp(context, 16);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(size, size);
        iconLp.topMargin = ComponentTheme.dp(context, 2);
        iconLp.setMarginEnd(ComponentTheme.dp(context, 8));
        indicator.setLayoutParams(iconLp);
        indicator.setImageResource(iconFor(status));
        ImageViewCompat.setImageTintList(indicator,
                ColorStateList.valueOf(colorFor(context, status)));
        indicator.setContentDescription(statusLabel(context, status));
        row.addView(indicator);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        String stepTitle = step.optString("title",
                context.getString(R.string.card_shell_step_default_title, index + 1));
        TextView stepTitleTv = new TextView(context);
        stepTitleTv.setText(stepTitle);
        stepTitleTv.setTextSize(ComponentTheme.bodyTextSize());
        stepTitleTv.setTextColor("todo".equals(status)
                ? ComponentTheme.textSecondary(context) : ComponentTheme.textPrimary(context));
        textCol.addView(stepTitleTv);

        String description = step.optString("description", "");
        if (!TextUtils.isEmpty(description)) {
            TextView descTv = new TextView(context);
            descTv.setText(description);
            descTv.setTextSize(ComponentTheme.metaTextSize());
            descTv.setTextColor(ComponentTheme.textTertiary(context));
            textCol.addView(descTv);
        }
        row.addView(textCol);
        return row;
    }

    private static int iconFor(String status) {
        switch (status) {
            case "done":
                return R.drawable.ic_check_circle;
            case "current":
                return R.drawable.ic_schedule;
            case "failed":
                return R.drawable.ic_close;
            default:
                return R.drawable.ic_circle_outline;
        }
    }

    private static int colorFor(Context context, String status) {
        switch (status) {
            case "done":
                return ComponentTheme.success(context);
            case "current":
                return ComponentTheme.brand(context);
            case "failed":
                return ComponentTheme.danger(context);
            default:
                return ComponentTheme.textTertiary(context);
        }
    }

    private static String statusLabel(Context context, String status) {
        switch (status) {
            case "done":
                return context.getString(R.string.card_shell_step_done);
            case "current":
                return context.getString(R.string.card_shell_step_current);
            case "failed":
                return context.getString(R.string.card_shell_step_failed);
            default:
                return context.getString(R.string.card_shell_step_todo);
        }
    }
}
