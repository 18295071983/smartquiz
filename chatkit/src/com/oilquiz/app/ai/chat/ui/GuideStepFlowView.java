package com.oilquiz.app.ai.chat.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 步骤引导流视图（全局可复用 View 组件，新建通用设计）。
 *
 * 覆盖对话页工具引导/复合引导中"分步执行 + 步骤状态"的通用形态：
 * 垂直步骤列表，每步带序号徽标与状态（等待/运行中/成功/失败），
 * 运行中步骤自动滚动到可见。
 *
 * <pre>
 * GuideStepFlowView flow = new GuideStepFlowView(context);
 * flow.setSteps(new String[]{"获取位置", "查询天气", "生成回复"});
 * flow.setStepState(0, StepState.RUNNING);
 * flow.setStepState(1, StepState.DONE);
 * flow.setStepState(2, StepState.ERROR, "超时");
 * </pre>
 */
public class GuideStepFlowView extends ScrollView {

    /** 步骤状态 */
    public enum StepState { PENDING, RUNNING, DONE, ERROR }

    public static class Step {
        public final String title;
        public StepState state;
        public String detail;
        public Step(String title) { this.title = title; this.state = StepState.PENDING; }
    }

    private final LinearLayout container;
    private final List<Step> steps = new ArrayList<>();

    public GuideStepFlowView(Context context) {
        this(context, null);
    }

    /** XML 布局 inflate 构造（委托单参构造） */
    public GuideStepFlowView(Context context, @androidx.annotation.Nullable android.util.AttributeSet attrs) {
        super(context, attrs);
        setVerticalScrollBarEnabled(false);
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(4), dp(8), dp(4), dp(8));
        addView(container, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
    }

    public void setSteps(List<String> titles) {
        steps.clear();
        if (titles != null) {
            for (String t : titles) steps.add(new Step(t));
        }
        rebuild();
    }

    public void setStepState(int index, StepState state) {
        setStepState(index, state, null);
    }

    public void setStepState(int index, StepState state, String detail) {
        if (index < 0 || index >= steps.size()) return;
        steps.get(index).state = state;
        steps.get(index).detail = detail;
        rebuild();
        if (state == StepState.RUNNING) {
            final int i = index;
            post(() -> {
                if (i * dp(48) > getScrollY() + getHeight() - dp(60)) {
                    smoothScrollTo(0, Math.max(0, i * dp(48) - dp(20)));
                }
            });
        }
    }

    public StepState getStepState(int index) {
        if (index < 0 || index >= steps.size()) return StepState.PENDING;
        return steps.get(index).state;
    }

    public int getStepCount() { return steps.size(); }

    private void rebuild() {
        container.removeAllViews();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(5), 0, dp(5));

            // 序号/状态徽标
            TextView badge = new TextView(getContext());
            badge.setText(badgeText(s));
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            badge.setGravity(Gravity.CENTER);
            GradientDrawable b = new GradientDrawable();
            b.setCornerRadius(dp(12));
            b.setColor(badgeColor(s));
            badge.setBackground(b);
            badge.setTextColor(0xFFFFFFFF);
            row.addView(badge, new LinearLayout.LayoutParams(dp(24), dp(24)));

            // 标题 + 可选详情（RUNNING/DONE/ERROR 均显示状态机阶段说明，PENDING 无详情）
            LinearLayout textWrap = new LinearLayout(getContext());
            textWrap.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(getContext());
            title.setText(s.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setTextColor(0xFF374151);
            textWrap.addView(title);
            if (s.detail != null && !s.detail.isEmpty() && s.state != StepState.PENDING) {
                TextView detail = new TextView(getContext());
                detail.setText(s.detail);
                detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                int c = s.state == StepState.ERROR ? 0xFFEF4444
                        : s.state == StepState.RUNNING ? 0xFF2563EB : 0xFF16A34A;
                detail.setTextColor(c);
                textWrap.addView(detail);
            }
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            textLp.setMargins(dp(10), 0, 0, 0);
            row.addView(textWrap, textLp);
            container.addView(row);
        }
    }

    private String badgeText(Step s) {
        switch (s.state) {
            case RUNNING: return "▶";
            case DONE: return "✓";
            case ERROR: return "✕";
            default: return String.valueOf(steps.indexOf(s) + 1);
        }
    }

    private int badgeColor(Step s) {
        switch (s.state) {
            case RUNNING: return 0xFF2563EB;
            case DONE: return 0xFF16A34A;
            case ERROR: return 0xFFEF4444;
            default: return 0xFF9CA3AF;
        }
    }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }
}
