package com.oilquiz.app.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 初始化步骤指示器控件
 *
 * 以横向步骤环展示多步流程：未到（半透明）/ 进行中（高亮放大）/ 完成（对勾环）。
 * 复用项目已设计好的步骤环资源 {@code ai_init_step_ring / _active / _done}。
 */
public class InitStepIndicatorView extends LinearLayout {

    private final List<StepItem> steps = new ArrayList<>();

    public InitStepIndicatorView(Context context) {
        this(context, null);
    }

    public InitStepIndicatorView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
    }

    /** 设置步骤（标签 + 图标），会重建指示器 */
    public void setSteps(String[] labels, String[] emojis) {
        removeAllViews();
        steps.clear();
        if (labels == null) return;
        int count = labels.length;
        for (int i = 0; i < count; i++) {
            StepItem item = new StepItem(labels[i], emojis != null && i < emojis.length ? emojis[i] : "•");
            LinearLayout itemLayout = new LinearLayout(getContext());
            itemLayout.setOrientation(VERTICAL);
            itemLayout.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

            FrameLayout dotWrap = new FrameLayout(getContext());
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(44), dp(44));
            itemLayout.addView(dotWrap, dotLp);

            TextView dot = new TextView(getContext());
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dp(44), dp(44));
            dotParams.gravity = android.view.Gravity.CENTER;
            dot.setGravity(android.view.Gravity.CENTER);
            dot.setTextSize(18);
            dot.setText(item.emoji);
            dot.setTextColor(Color.WHITE);
            dotWrap.addView(dot, dotParams);
            item.dot = dot;

            TextView ring = new TextView(getContext());
            FrameLayout.LayoutParams ringParams = new FrameLayout.LayoutParams(dp(44), dp(44));
            ringParams.gravity = android.view.Gravity.CENTER;
            ring.setBackgroundResource(R.drawable.ai_init_step_ring);
            dotWrap.addView(ring, ringParams);
            item.ring = ring;

            TextView label = new TextView(getContext());
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            labelLp.topMargin = dp(4);
            label.setText(item.label);
            label.setTextSize(11);
            label.setTextColor(Color.argb(150, 255, 255, 255));
            itemLayout.addView(label, labelLp);
            item.labelView = label;

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i < count - 1) {
                lp.setMarginEnd(dp(4));
            }
            addView(itemLayout, lp);
            steps.add(item);
        }
    }

    /** 设置某一步状态：0=未到 1=进行中 2=完成 */
    public void setStepState(int index, int state) {
        if (index < 0 || index >= steps.size()) return;
        StepItem item = steps.get(index);
        if (item.dot == null || item.ring == null) return;
        if (state == 0) {
            item.ring.setBackgroundResource(R.drawable.ai_init_step_ring);
            item.dot.setAlpha(0.35f);
            if (item.labelView != null) item.labelView.setTextColor(Color.argb(120, 255, 255, 255));
        } else if (state == 1) {
            item.ring.setBackgroundResource(R.drawable.ai_init_step_ring_active);
            item.dot.setAlpha(1f);
            if (item.labelView != null) item.labelView.setTextColor(Color.WHITE);
            item.dot.animate().scaleX(1.25f).scaleY(1.25f).setDuration(300).start();
        } else if (state == 2) {
            item.ring.setBackgroundResource(R.drawable.ai_init_step_ring_done);
            item.dot.setAlpha(1f);
            if (item.labelView != null) item.labelView.setTextColor(Color.WHITE);
            item.dot.animate().scaleX(1f).scaleY(1f).setDuration(250).start();
        }
    }

    /** 快捷：第 activeIndex 步为 state，之前全部完成，之后全部未到 */
    public void setActiveStep(int activeIndex, int state) {
        for (int i = 0; i < steps.size(); i++) {
            setStepState(i, i < activeIndex ? 2 : (i == activeIndex ? state : 0));
        }
    }

    private static class StepItem {
        final String label;
        final String emoji;
        TextView dot;
        TextView ring;
        TextView labelView;

        StepItem(String label, String emoji) {
            this.label = label;
            this.emoji = emoji;
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
