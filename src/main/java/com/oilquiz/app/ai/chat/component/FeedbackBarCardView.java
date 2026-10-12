package com.oilquiz.app.ai.chat.component;

import android.content.ClipData;
import android.content.ClipboardManager;
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

/**
 * 消息操作栏组件（feedback_bar）：横向按钮条，声明式配置。
 *
 * 取代 ChatAdapter 里硬编码的 btnCopy/btnSpeak/btnShare/btnRegenerate/btnLike/
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
 *     {"id":"copy","icon":"📋","label":"复制"},
 *     {"id":"speak","icon":"🔊","label":"朗读","dynamic":{"speaking":{"icon":"⏹","label":"停止"}}},
 *     {"id":"share","icon":"↗️","label":"分享"},
 *     {"id":"regen","icon":"🔄","label":"重新生成"},
 *     {"id":"like","icon":"👍"},
 *     {"id":"dislike","icon":"👎"},
 *     {"id":"stop","icon":"⏹","label":"停止生成","visibleWhen":"streaming"}
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
            actions = defaultActions();
        }
        for (int i = 0; i < actions.length(); i++) {
            JSONObject a = actions.optJSONObject(i);
            if (a == null) continue;
            String id = a.optString("id", "");
            // visibleWhen=streaming：非流式跳过（stop 按钮）
            String when = a.optString("visibleWhen", null);
            if ("streaming".equals(when) && !streaming) continue;
            if ("completed".equals(when) && streaming) continue;

            String icon = a.optString("icon", "");
            String label = a.optString("label", "");
            // 动态状态覆盖（如 speak → stop）
            JSONObject dynamic = a.optJSONObject("dynamic");
            if (dynamic != null && speaking && id.equals("speak")) {
                JSONObject speakingState = dynamic.optJSONObject("speaking");
                if (speakingState != null) {
                    icon = speakingState.optString("icon", icon);
                    label = speakingState.optString("label", label);
                }
            }
            // 反馈按钮激活态
            boolean active = ("like".equals(id) && feedback == 1)
                    || ("dislike".equals(id) && feedback == -1);

            TextView btn = makeChip(context, icon, label, active);
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

    private JSONArray defaultActions() {
        JSONArray arr = new JSONArray();
        try {
            arr.put(new JSONObject().put("id", "copy").put("icon", "📋").put("label", "复制"));
            arr.put(new JSONObject().put("id", "speak").put("icon", "🔊").put("label", "朗读")
                    .put("dynamic", new JSONObject().put("speaking",
                            new JSONObject().put("icon", "⏹").put("label", "停止"))));
            arr.put(new JSONObject().put("id", "share").put("icon", "↗️").put("label", "分享"));
            arr.put(new JSONObject().put("id", "regen").put("icon", "🔄").put("label", "重新生成"));
            arr.put(new JSONObject().put("id", "like").put("icon", "👍"));
            arr.put(new JSONObject().put("id", "dislike").put("icon", "👎"));
            arr.put(new JSONObject().put("id", "stop").put("icon", "⏹").put("label", "停止生成")
                    .put("visibleWhen", "streaming"));
        } catch (org.json.JSONException ignored) {}
        return arr;
    }

    private TextView makeChip(Context ctx, String icon, String label, boolean active) {
        TextView tv = new TextView(ctx);
        String text = (TextUtils.isEmpty(icon) ? "" : icon)
                + (TextUtils.isEmpty(label) ? "" : (TextUtils.isEmpty(icon) ? label : " " + label));
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTextColor(active ? 0xFF1565C0 : 0xE8FFFFFF);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(ctx, 12));
        gd.setStroke(dp(ctx, 1), active ? 0xFF1565C0 : 0x59FFFFFF);
        gd.setColor(active ? 0x331565C0 : 0x1AFFFFFF);
        tv.setBackground(gd);
        tv.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        tv.setClickable(true);
        tv.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(ctx, 8));
        tv.setLayoutParams(lp);
        return tv;
    }

    private int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
