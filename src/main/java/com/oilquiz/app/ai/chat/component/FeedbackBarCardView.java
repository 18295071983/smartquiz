package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.oilquiz.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 消息操作栏组件（feedback_bar）：横向按钮条，声明式配置。
 *
 * <p>Track B0-4：去掉写死的白色系颜色（浅色底上几乎不可读）与 emoji 图标，
 * 颜色一律走 {@link ComponentTheme}，图标走 res/drawable 矢量资源；
 * props 里的 icon 字段不再当图标用（模型给的就是 emoji），改为按按钮 id 映射。
 *
 * <p>取代 ChatAdapter 里硬编码的 btnCopy/btnSpeak/btnShare/btnRegenerate/btnLike/
 * btnDislike/btnStop 等 TextView。props 描述按钮列表 + 反馈状态，点击通过
 * ComponentActions.dispatchResult 分发到 chat_ui_actions 通道，Agent 端可查询。
 *
 * <p>props 示例：
 * <pre>
 * {
 *   "messageId": "T1-2",
 *   "feedbackState": 0,          // 1=已赞 -1=已踩 0=未反馈
 *   "speaking": false,           // 是否正在朗读
 *   "streaming": false,          // 是否正在流式生成
 *   "actions": [
 *     {"id":"copy","label":"复制"},
 *     {"id":"speak","label":"朗读","dynamic":{"speaking":{"label":"停止"}}}
 *   ]
 * }
 * </pre>
 * 未提供 actions 时使用默认全套。
 */
public class FeedbackBarCardView implements ChatComponent {

    @Override
    public String getType() {
        return "feedback_bar";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null;
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props;
        String msgId = p.optString("messageId", null);
        int feedback = p.optInt("feedbackState", 0);
        boolean speaking = p.optBoolean("speaking", false);
        boolean streaming = p.optBoolean("streaming", false);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        JSONArray actions = p.optJSONArray("actions");
        if (actions == null || actions.length() == 0) {
            actions = defaultActions(context);
        }
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.optJSONObject(i);
            if (a == null) continue;
            String id = a.optString("id", "");
            // visibleWhen=streaming：非流式跳过（stop 按钮）
            String when = a.optString("visibleWhen", null);
            if ("streaming".equals(when) && !streaming) continue;
            if ("completed".equals(when) && streaming) continue;

            String label = a.optString("label", defaultLabel(context, id));
            // 动态状态覆盖（如 speak → stop）
            JSONObject dynamic = a.optJSONObject("dynamic");
            if (dynamic != null && speaking && "speak".equals(id)) {
                JSONObject speakingState = dynamic.optJSONObject("speaking");
                if (speakingState != null) {
                    label = speakingState.optString("label", context.getString(R.string.chat_stop));
                }
            }
            // 反馈按钮激活态
            boolean active = ("like".equals(id) && feedback == 1)
                    || ("dislike".equals(id) && feedback == -1);

            TextView btn = makeChip(context, id, label, active);
            final String actionId = id;
            btn.setOnClickListener(v -> {
                String value = actionId + (msgId != null ? ":" + msgId : "");
                ComponentActions.dispatchResult(v.getContext(),
                        ComponentActions.CHAT_UI_CHANNEL, value);
            });
            row.addView(btn);
        }
        return row;
    }

    /** 默认全套：只声明 id，图标与文案都由本地资源决定（不再往数据里塞 emoji） */
    private JSONArray defaultActions(Context context) {
        JSONArray arr = new JSONArray();
        try {
            arr.put(new JSONObject().put("id", "copy"));
            arr.put(new JSONObject().put("id", "speak")
                    .put("dynamic", new JSONObject().put("speaking",
                            new JSONObject().put("label",
                                    context.getString(R.string.chat_stop)))));
            arr.put(new JSONObject().put("id", "share"));
            arr.put(new JSONObject().put("id", "regen"));
            arr.put(new JSONObject().put("id", "like"));
            arr.put(new JSONObject().put("id", "dislike"));
            arr.put(new JSONObject().put("id", "stop").put("visibleWhen", "streaming"));
        } catch (org.json.JSONException ignored) {
        }
        return arr;
    }

    /** 按钮图标：按 id 映射矢量资源，未知 id 不显示图标 */
    private static int iconFor(String id) {
        switch (id) {
            case "copy":
                return R.drawable.ic_copy;
            case "speak":
                return R.drawable.ic_speaker;
            case "share":
                return R.drawable.ic_share;
            case "regen":
                return R.drawable.ic_refresh;
            case "like":
                return R.drawable.ic_thumb_up;
            case "dislike":
                return R.drawable.ic_thumb_down;
            case "stop":
                return R.drawable.ic_stop;
            default:
                return 0;
        }
    }

    private static String defaultLabel(Context context, String id) {
        switch (id) {
            case "copy":
                return context.getString(R.string.chat_copy);
            case "speak":
                return context.getString(R.string.chat_speak);
            case "share":
                return context.getString(R.string.chat_share);
            case "regen":
                return context.getString(R.string.chat_regenerate);
            case "like":
                return context.getString(R.string.chat_like);
            case "dislike":
                return context.getString(R.string.chat_dislike);
            case "stop":
                return context.getString(R.string.chat_stop_generating);
            default:
                return "";
        }
    }

    private TextView makeChip(Context ctx, String id, String label, boolean active) {
        TextView tv = new TextView(ctx);
        boolean hasLabel = !TextUtils.isEmpty(label);
        tv.setText(hasLabel ? label : "");
        tv.setContentDescription(hasLabel ? label : id);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                hasLabel ? ComponentTheme.metaTextSize() : ComponentTheme.bodyTextSize());
        int fg = active ? ComponentTheme.brand(ctx) : ComponentTheme.textSecondary(ctx);
        tv.setTextColor(fg);
        tv.setBackground(ComponentTheme.chipBackground(ctx, active));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setMinHeight(ComponentTheme.dp(ctx, 32));
        tv.setPadding(ComponentTheme.dp(ctx, 8), ComponentTheme.dp(ctx, 4),
                ComponentTheme.dp(ctx, 8), ComponentTheme.dp(ctx, 4));
        tv.setCompoundDrawablePadding(ComponentTheme.dp(ctx, 4));

        int iconRes = iconFor(id);
        if (iconRes != 0) {
            Drawable d = ContextCompat.getDrawable(ctx, iconRes);
            if (d != null) {
                d = DrawableCompat.wrap(d.mutate());
                DrawableCompat.setTintList(d, ColorStateList.valueOf(fg));
                d.setBounds(0, 0, ComponentTheme.dp(ctx, 14), ComponentTheme.dp(ctx, 14));
                tv.setCompoundDrawablesRelative(d, null, null, null);
            }
        }
        tv.setClickable(true);
        tv.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(ComponentTheme.dp(ctx, 6));
        tv.setLayoutParams(lp);
        return tv;
    }
}
