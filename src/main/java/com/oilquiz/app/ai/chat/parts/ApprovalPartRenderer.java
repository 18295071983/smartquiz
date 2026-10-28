package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.chat.turn.AssistantTurn.Part;
import com.oilquiz.app.theme.ThemeColors;

/**
 * 审批 / 人机确认 part 渲染器（对齐 cdesktop / clawpanel / AI SDK 的 approval 块，2026-10-21）。
 *
 * <p>这是"块级动作"的第一个 part：pending 时给"允许 / 拒绝"按钮，点击经
 * {@link PartHost#onApprovalDecision} 回调宿主；已决（approved/denied）时收起按钮、显示结果文案并上色。</p>
 *
 * <p>数据口径：{@code Part.type=approval}，{@code toolName}=权限名、{@code text}=说明文案、
 * {@code status}=决策态（pending/approved/denied）、{@code toolCallId}=关联工具调用。</p>
 *
 * <p>无 emoji（沿用项目"状态去 emoji"约定）；颜色走 {@link ThemeColors} 语义资源，不写死十六进制。
 * 本轮不接 ChatAdapter、flag 默认 false——生产者（把现有 Agent 权限请求落成 approval part）在 P3。</p>
 */
public final class ApprovalPartRenderer implements PartRenderer {

    @Override
    public boolean canRender(Part part) {
        return part != null && Part.TYPE_APPROVAL.equals(part.type);
    }

    @Override
    public View createView(PartHost host, ViewGroup parent) {
        Context ctx = host.context();
        int pad = dp(ctx, 10);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView prompt = new TextView(ctx);
        prompt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        root.addView(prompt);

        LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button deny = new Button(ctx);
        deny.setText("拒绝");
        Button allow = new Button(ctx);
        allow.setText("允许");
        actions.addView(deny);
        actions.addView(allow);
        root.addView(actions);

        root.setTag(new Views(prompt, actions, allow, deny));
        return root;
    }

    @Override
    public void bindView(View view, Part part, PartHost host) {
        Views v = view instanceof LinearLayout ? (Views) ((LinearLayout) view).getTag() : null;
        if (v == null) {
            return;
        }
        Context ctx = host.context();
        String permission = part.toolName != null ? part.toolName : "";
        String promptText = part.text != null ? part.text : "";
        boolean pending = part.isApprovalPending();

        v.allow.setOnClickListener(x ->
                host.onApprovalDecision(part.id, part.toolCallId, true));
        v.deny.setOnClickListener(x ->
                host.onApprovalDecision(part.id, part.toolCallId, false));

        v.actions.setVisibility(pending ? View.VISIBLE : View.GONE);
        v.prompt.setText(pending
                ? compose(permission, promptText)
                : decidedLabel(part, permission));
        try {
            int color = pending
                    ? ThemeColors.get(ctx, R.color.text_secondary)
                    : Part.APPROVAL_APPROVED.equals(part.status)
                            ? ThemeColors.get(ctx, R.color.success)
                            : ThemeColors.get(ctx, R.color.error);
            v.prompt.setTextColor(color);
        } catch (Throwable ignore) {
            // 取色失败保持默认前景色
        }
    }

    @Override
    public void releaseView(View view) {
        // 纯 View，无重资源。
    }

    private static String compose(String permission, String prompt) {
        if (permission.isEmpty()) {
            return prompt;
        }
        return prompt.isEmpty() ? ("请求权限：" + permission)
                : ("请求权限：" + permission + "\n" + prompt);
    }

    private static String decidedLabel(Part part, String permission) {
        boolean approved = Part.APPROVAL_APPROVED.equals(part.status);
        String verb = approved ? "已允许" : "已拒绝";
        return permission.isEmpty() ? verb : (verb + "：" + permission);
    }

    private static int dp(Context ctx, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }

    private static final class Views {
        final TextView prompt;
        final LinearLayout actions;
        final Button allow;
        final Button deny;
        Views(TextView prompt, LinearLayout actions, Button allow, Button deny) {
            this.prompt = prompt;
            this.actions = actions;
            this.allow = allow;
            this.deny = deny;
        }
    }
}
