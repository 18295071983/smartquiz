package com.oilquiz.app.ai.chat.component;

import com.oilquiz.app.theme.Tokens;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 提醒卡片组件：成功/警告/错误/信息 四种类型，带左侧色条与图标。
 *
 * 数据格式（ComponentData.props）：
 * <pre>
 * {
 *   "type": "success" | "warning" | "error" | "info",
 *   "title": "操作成功",
 *   "content": "文件已保存到 /sdcard/xxx",
 *   "actions": [{"label":"打开","link":"file:///..."}]   // 可选
 * }
 * </pre>
 */
public class AlertCardView implements ChatComponent {

    @Override
    public String getType() {
        return "alert_card";
    }

    @Override
    public boolean canRender(ComponentData data) {
        return data != null && data.props != null
                && (data.props.has("title") || data.props.has("content") || data.props.has("text"));
    }

    @Override
    public View createView(Context context, ComponentData data) {
        JSONObject p = data.props != null ? data.props : new JSONObject();
        // 兼容两种字段：原生组件约定用 type，DSH 事件映射早先写的是 level。
        // 之前只认 type → DSH 过来的 alert 全部退化成 info 紫色，四档级别形同虚设。
        String type = p.optString("type", p.optString("level", "info"));
        String title = p.optString("title", "");
        // 兼容两种字段：DSH 事件映射早先用 text，原生组件约定用 content。
        // 只认 content 时，goal/team 这些卡会只剩标题、正文消失（2026-11-14 审计发现）。
        String content = p.optString("content", p.optString("text", ""));

        // 按类型取色：2026-11-18 起用"语义容器 + on 色"（容器底 + 对比文字），
        // 不再统一中性底 + 彩色条——四档级别在深浅/换肤下要能一眼区分。
        int accentColor;
        int containerColor;
        int contentColor;
        String icon;
        switch (type) {
            case "success":
                accentColor = ComponentTheme.success(context);
                containerColor = ComponentTheme.successContainer(context);
                contentColor = ComponentTheme.onSuccessContainer(context);
                icon = "✅";
                break;
            case "warning":
                accentColor = ComponentTheme.warning(context);
                containerColor = ComponentTheme.warningContainer(context);
                contentColor = ComponentTheme.onWarningContainer(context);
                icon = "⚠️";
                break;
            case "error":
                accentColor = ComponentTheme.danger(context);
                containerColor = ComponentTheme.errorContainer(context);
                contentColor = ComponentTheme.onErrorContainer(context);
                icon = "❌";
                break;
            case "info":
            default:
                accentColor = ComponentTheme.brand(context);
                containerColor = ComponentTheme.infoContainer(context);
                contentColor = ComponentTheme.onInfoContainer(context);
                icon = "ℹ️";
                break;
        }

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        card.setBackground(cardBackground(context, containerColor, accentColor));

        // 左侧色条
        View colorBar = new View(context);
        colorBar.setBackgroundColor(accentColor);
        card.addView(colorBar, new LinearLayout.LayoutParams(dp(context, 4),
                LinearLayout.LayoutParams.MATCH_PARENT));

        // 图标
        TextView iconTv = new TextView(context);
        iconTv.setText(icon);
        iconTv.setTextSize(Tokens.textSizeSp(iconTv.getContext(), Tokens.TEXT_SUBTITLE));
        iconTv.setPadding(dp(context, 10), 0, dp(context, 8), 0);
        iconTv.setGravity(Gravity.TOP);
        card.addView(iconTv);

        // 文本列
        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (!TextUtils.isEmpty(title)) {
            TextView titleTv = new TextView(context);
            titleTv.setText(title);
            titleTv.setTextSize(Tokens.textSizeSp(titleTv.getContext(), Tokens.TEXT_LABEL));
            titleTv.setTextColor(contentColor);
            titleTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textCol.addView(titleTv);
        }

        if (!TextUtils.isEmpty(content)) {
            TextView contentTv = new TextView(context);
            contentTv.setText(content);
            contentTv.setTextSize(Tokens.textSizeSp(contentTv.getContext(), Tokens.TEXT_FOOTNOTE));
            // on 色略降透明度：保持与主文字同色系，但不抢标题
            contentTv.setTextColor(ComponentTheme.withAlpha(contentColor, 0xD0));
            contentTv.setPadding(0, dp(context, 2), 0, 0);
            textCol.addView(contentTv);
        }

        // 动作行：actions 按钮（打开链接/复制等），点击真实响应
        ComponentActions.renderActions(textCol, context, p.optJSONArray("actions"));
        card.addView(textCol);
        /**
         * 2026-11-14：承接原来 dsh_notice 的两种交互，避免为同一件事维护两套卡。
         *
         * <ul>
         *   <li>{@code jump=last} → 滚到末尾那条（"正在执行/排队中"的气泡）；</li>
         *   <li>{@code jump=subagent} + {@code childId} → 滚到该子代理的轨迹卡；</li>
         *   <li>其它情况点开 → 全文弹窗（正文最长 3 行，长文本会被截断）。</li>
         * </ul>
         *
         * <p>只有带 {@code jump} 或正文较长的卡才可点，普通 alert 行为不变。</p>
         */
        final String jump = p.optString("jump", "");
        final String childId = p.optString("childId", "");
        final String fullTitle = title;
        final String fullContent = content;
        if (!jump.isEmpty() || content.length() > 60) {
            card.setClickable(true);
            card.setOnClickListener(v -> {
                ComponentHosts.Host host = ComponentHosts.get();
                if ("last".equals(jump)) {
                    host.scrollToLast();
                    return;
                }
                if ("subagent".equals(jump)) {
                    host.openSubagent(childId);
                    return;
                }
                if (!TextUtils.isEmpty(fullContent)) {
                    DshCardViews.showNoticeDialog(context, fullTitle, fullContent);
                }
            });
            if (!jump.isEmpty()) {
                card.setOnLongClickListener(v -> {
                    if (!TextUtils.isEmpty(fullContent)) {
                        DshCardViews.showNoticeDialog(context, fullTitle, fullContent);
                    }
                    return true;
                });
            }
        }
        return card;
    }

    /** 卡片底：语义容器色 + 该语义基色的低透明度描边（四档级别一眼可分）。 */
    private static android.graphics.drawable.Drawable cardBackground(Context context,
                                                                    int containerColor,
                                                                    int accentColor) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(containerColor != 0 ? containerColor : ComponentColors.background(context));
        gd.setCornerRadius(dp(context, 10));
        gd.setStroke(dp(context, 1), ComponentTheme.withAlpha(accentColor, 0x33));
        return gd;
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
