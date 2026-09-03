package com.oilquiz.app.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.oilquiz.app.ai.model.ModelDownloadManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型选择控件（用于选择要下载的预置模型）
 *
 * 封装预置模型列表（数据来自 {@link ModelDownloadManager}）的卡片渲染与选中态管理。
 * 提供 {@link #setModels(List)} 渲染、{@link #getSelectedId()} 取值、选中回调。
 */
public class ModelPickerView extends LinearLayout {

    public interface OnModelSelectedListener {
        void onModelSelected(String modelId);
    }

    private final List<ModelDownloadManager.ModelPresetInfo> presets = new ArrayList<>();
    private String selectedId;
    private OnModelSelectedListener listener;

    public ModelPickerView(Context context) {
        this(context, null);
    }

    public ModelPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
    }

    /** 设置预置模型列表并渲染卡片（默认选中第一个） */
    public void setModels(List<ModelDownloadManager.ModelPresetInfo> list, String defaultId) {
        removeAllViews();
        presets.clear();
        if (list != null) {
            for (ModelDownloadManager.ModelPresetInfo p : list) {
                if (p != null) presets.add(p);
            }
        }
        selectedId = null;
        if (defaultId != null) {
            for (ModelDownloadManager.ModelPresetInfo p : presets) {
                if (defaultId.equals(p.id)) { selectedId = defaultId; break; }
            }
        }
        if (selectedId == null && !presets.isEmpty()) {
            selectedId = presets.get(0).id;
        }
        for (ModelDownloadManager.ModelPresetInfo p : presets) {
            addCard(p);
        }
    }

    /** 当前选中的模型 id（无选中返回 null） */
    public String getSelectedId() {
        return selectedId;
    }

    public void setOnModelSelectedListener(OnModelSelectedListener l) {
        this.listener = l;
    }

    public boolean isEmpty() {
        return presets.isEmpty();
    }

    /** 已选模型的显示名 */
    public String getSelectedName() {
        for (ModelDownloadManager.ModelPresetInfo p : presets) {
            if (selectedId != null && selectedId.equals(p.id)) return p.name;
        }
        return selectedId;
    }

    private void addCard(final ModelDownloadManager.ModelPresetInfo preset) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setClickable(true);
        card.setFocusable(true);

        // 左侧：名称 + 描述
        LinearLayout left = new LinearLayout(getContext());
        left.setOrientation(VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        leftLp.gravity = android.view.Gravity.CENTER_VERTICAL;

        TextView name = new TextView(getContext());
        name.setTextSize(15);
        name.setTextColor(Color.WHITE);
        name.setText(preset.name + (preset.multimodal ? "  🌈" : ""));
        left.addView(name);

        TextView desc = new TextView(getContext());
        desc.setTextSize(11);
        desc.setTextColor(Color.argb(160, 255, 255, 255));
        desc.setText(preset.description);
        desc.setMaxLines(2);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(3);
        left.addView(desc, descLp);
        card.addView(left, leftLp);

        // 右侧：大小 + 选中圆点
        LinearLayout right = new LinearLayout(getContext());
        right.setOrientation(VERTICAL);
        right.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        TextView size = new TextView(getContext());
        size.setTextSize(12);
        size.setTextColor(Color.argb(200, 255, 255, 255));
        long mb = preset.sizeMB > 0 ? preset.sizeMB : 0;
        size.setText(mb >= 1024 ? String.format("%.1f GB", mb / 1024f) : mb + " MB");
        right.addView(size);

        TextView check = new TextView(getContext());
        check.setTextSize(20);
        check.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        checkLp.topMargin = dp(4);
        right.addView(check, checkLp);
        card.addView(right, rightLp);

        refreshCard(card, check, preset.id.equals(selectedId));
        card.setTag(preset.id);
        check.setTag("check_" + preset.id);
        card.setOnClickListener(v -> {
            selectedId = preset.id;
            refreshAllCards();
            if (listener != null) listener.onModelSelected(selectedId);
        });
        addView(card, cardLp);
    }

    private void refreshAllCards() {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!(child.getTag() instanceof String)) continue;
            String cid = (String) child.getTag();
            View ck = child.findViewWithTag("check_" + cid);
            refreshCard(child, ck, cid.equals(selectedId));
        }
    }

    private void refreshCard(View card, View checkView, boolean selected) {
        if (card == null) return;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (selected) {
            bg.setColor(0x338E6CFF);
            bg.setStroke(dp(2), 0xFF8E6CFF);
        } else {
            bg.setColor(0x14FFFFFF);
            bg.setStroke(dp(1), 0x2EFFFFFF);
        }
        card.setBackground(bg);
        if (checkView instanceof TextView) {
            TextView ck = (TextView) checkView;
            if (selected) {
                ck.setText("●");
                ck.setTextColor(0xFF8E6CFF);
            } else {
                ck.setText("○");
                ck.setTextColor(0x66FFFFFF);
            }
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
