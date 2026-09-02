package com.oilquiz.app.ui.activity;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.config.UserModelParamsManager;
import com.oilquiz.app.ai.service.AIServiceInitializer;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.ui.widget.GradientRingProgress;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 服务一键初始化 — 全屏精美引导界面
 *
 * 视觉：极光渐变背景 + 粒子 + 发光徽标 + 渐变进度环 + 步骤指示 + 呼吸动画
 * 流程：自动调用 {@link AIServiceInitializer} 完成「下载 → 配置 → 加载」，
 *       全程展示进度与阶段状态，成功/失败均有明确反馈。
 */
public class AIServiceInitActivity extends BaseActivity {

    private static final String[] STEP_LABELS = {"检测配置", "下载模型", "配置模型", "加载服务"};
    private static final String[] STEP_EMOJIS = {"🔍", "📥", "🔧", "🚀"};

    private GradientRingProgress ringProgress;
    private TextView tvTitle;
    private TextView tvSubtitle;
    private TextView tvStatus;
    private TextView tvHint;
    private MaterialButton btnAction;
    private MaterialButton btnClose;
    private MaterialButton btnParams;
    private TextView tvParamsSummary;
    private View logoContainer;
    private LinearLayout stepsContainer;
    private View mmprojContainer;
    private ProgressBar mmprojProgress;
    private TextView tvMmprojStatus;
    private ProgressBar modelProgress;
    private TextView tvModelStatus;

    private final List<StepItem> steps = new ArrayList<>();
    private boolean running = false;
    private boolean completed = false;
    private volatile boolean destroyed = false;
    private ObjectAnimator logoPulse;

    // ===== 模型选择 =====
    private View modelPickerSection;
    private LinearLayout modelPickerContainer;
    private TextView tvPickerHint;
    private String selectedModelId;
    private boolean currentMultimodal = false; // 当前所选模型是否多模态（决定 mmproj 区显示）
    private final java.util.List<com.oilquiz.app.ai.model.ModelDownloadManager.ModelPresetInfo> presetList = new ArrayList<>();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_ai_service_init;
    }

    @Override
    protected void initView() {
        ringProgress = findViewById(R.id.ring_progress);
        tvTitle = findViewById(R.id.tv_title);
        tvSubtitle = findViewById(R.id.tv_subtitle);
        tvStatus = findViewById(R.id.tv_status);
        tvHint = findViewById(R.id.tv_hint);
        btnAction = findViewById(R.id.btn_action);
        btnClose = findViewById(R.id.btn_close);
        logoContainer = findViewById(R.id.logo_container);
        stepsContainer = findViewById(R.id.steps_container);
        mmprojContainer = findViewById(R.id.mmproj_container);
        mmprojProgress = findViewById(R.id.mmproj_progress);
        tvMmprojStatus = findViewById(R.id.tv_mmproj_status);
        modelProgress = findViewById(R.id.model_progress);
        tvModelStatus = findViewById(R.id.tv_model_status);
        modelPickerSection = findViewById(R.id.model_picker_section);
        modelPickerContainer = findViewById(R.id.model_picker_container);
        tvPickerHint = findViewById(R.id.tv_picker_hint);
        btnParams = findViewById(R.id.btn_params);
        tvParamsSummary = findViewById(R.id.tv_params_summary);
        if (btnParams != null) {
            btnParams.setOnClickListener(v -> showParamsDialog());
        }
        refreshParamsSummary();

        buildSteps();
        startLogoPulse();
        renderModelPicker();
    }

    @Override
    protected void initData() {
        // 进入页面先让用户选择模型（默认已选推荐模型），点击「开始初始化」后才执行
        // 不再自动开始，避免用户在未确认模型时就直接下载
    }

    @Override
    protected void initListener() {
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> finish());
        }
        if (btnAction != null) {
            btnAction.setOnClickListener(v -> {
                if (completed) {
                    finish();
                } else if (!running) {
                    startInitialization(selectedModelId != null ? selectedModelId : com.oilquiz.app.ai.service.AIServiceInitializer.DEFAULT_MODEL_ID);
                }
            });
        }
    }

    // ==================== 模型选择 ====================

    /** 推理参数面板（LM Studio 式：上下文 / 温度 / GPU 层数） */
    private void showParamsDialog() {
        UserModelParamsManager um = UserModelParamsManager.getInstance(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        ScrollView scroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad / 2, pad, 0);
        scroll.addView(container);

        CheckBox autoCb = new CheckBox(this);
        autoCb.setText("自动参数（按设备性能推荐）");
        autoCb.setChecked(um.isAuto());
        container.addView(autoCb);

        container.addView(paramsLabel("上下文长度（512-32768，留空=自动）"));
        EditText ctxEt = new EditText(this);
        ctxEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        ctxEt.setHint("例如 8192");
        if (um.getContextSize() > 0) ctxEt.setText(String.valueOf(um.getContextSize()));
        container.addView(ctxEt);

        container.addView(paramsLabel("温度（0.1-1.5，0=模型默认 0.7）"));
        EditText tempEt = new EditText(this);
        tempEt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        tempEt.setHint("例如 0.8");
        if (um.getTemperature() > 0) tempEt.setText(String.valueOf(um.getTemperature()));
        container.addView(tempEt);

        container.addView(paramsLabel("GPU 卸载层数（0-64，留空=自动）"));
        EditText gpuEt = new EditText(this);
        gpuEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        gpuEt.setHint("例如 24");
        if (um.getGpuLayers() >= 0) gpuEt.setText(String.valueOf(um.getGpuLayers()));
        container.addView(gpuEt);

        new MaterialAlertDialogBuilder(this)
                .setTitle("推理参数")
                .setMessage("参数在下次加载模型时生效")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    if (autoCb.isChecked()) {
                        um.setAuto(true);
                    } else {
                        String ctxS = ctxEt.getText().toString().trim();
                        if (ctxS.isEmpty()) {
                            um.clearContextOverride();
                        } else {
                            try { um.setContextSize(Math.max(512, Math.min(32768, Integer.parseInt(ctxS)))); } catch (Exception ignored) {}
                        }
                        String tS = tempEt.getText().toString().trim();
                        if (tS.isEmpty()) {
                            um.setTemperature(0f);
                        } else {
                            try { um.setTemperature(Math.max(0.1f, Math.min(1.5f, Float.parseFloat(tS)))); } catch (Exception ignored) {}
                        }
                        String gS = gpuEt.getText().toString().trim();
                        if (gS.isEmpty()) {
                            um.clearGpuOverride();
                        } else {
                            try { um.setGpuLayers(Math.max(0, Math.min(64, Integer.parseInt(gS)))); } catch (Exception ignored) {}
                        }
                    }
                    refreshParamsSummary();
                })
                .setNegativeButton("恢复默认", (d, w) -> {
                    um.resetAll();
                    refreshParamsSummary();
                })
                .setNeutralButton("取消", null)
                .show();
    }

    private TextView paramsLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12f);
        t.setTextColor(0xFF6B7280);
        t.setPadding(0, 10, 0, 2);
        return t;
    }

    private void refreshParamsSummary() {
        if (tvParamsSummary == null) return;
        UserModelParamsManager um = UserModelParamsManager.getInstance(this);
        if (um.isAuto()) {
            tvParamsSummary.setText("自动参数（推荐）");
        } else {
            StringBuilder sb = new StringBuilder("手动：");
            boolean first = true;
            if (um.getContextSize() > 0) { sb.append("上下文 ").append(um.getContextSize()); first = false; }
            if (um.getTemperature() > 0) { if (!first) sb.append(" · "); sb.append("温度 ").append(um.getTemperature()); first = false; }
            if (um.getGpuLayers() >= 0) { if (!first) sb.append(" · "); sb.append("GPU ").append(um.getGpuLayers()); }
            tvParamsSummary.setText(sb.length() > 4 ? sb.toString() : "手动参数");
        }
    }

    private void renderModelPicker() {
        if (modelPickerContainer == null) return;
        modelPickerContainer.removeAllViews();
        presetList.clear();
        try {
            com.oilquiz.app.ai.model.ModelDownloadManager manager =
                    com.oilquiz.app.ai.model.ModelDownloadManager.getInstance(this);
            java.util.List<com.oilquiz.app.ai.model.ModelDownloadManager.ModelPresetInfo> list =
                    manager.getPresetDomesticModels();
            if (list != null) {
                for (com.oilquiz.app.ai.model.ModelDownloadManager.ModelPresetInfo p : list) {
                    if (p != null) presetList.add(p);
                }
            }
        } catch (Exception e) {
            AILogger.e(TAG, "加载模型列表失败", e);
        }
        if (presetList.isEmpty()) {
            // 列表加载失败时回退默认模型，仍可初始化
            addFallbackCard();
        } else {
            // 默认选中推荐的多模态 Agent 模型
            String def = com.oilquiz.app.ai.service.AIServiceInitializer.DEFAULT_MODEL_ID;
            boolean found = false;
            for (com.oilquiz.app.ai.model.ModelDownloadManager.ModelPresetInfo p : presetList) {
                if (def.equals(p.id)) { found = true; break; }
            }
            selectedModelId = found ? def : presetList.get(0).id;
            for (com.oilquiz.app.ai.model.ModelDownloadManager.ModelPresetInfo p : presetList) {
                addModelCard(p);
            }
        }
        updatePickerHint();
    }

    private void addFallbackCard() {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText("⚠️ 模型列表加载失败，将使用默认模型");
        tv.setTextSize(13);
        tv.setTextColor(Color.argb(200, 255, 255, 255));
        tv.setPadding(dp(4), dp(8), dp(4), dp(8));
        modelPickerContainer.addView(tv);
        selectedModelId = com.oilquiz.app.ai.service.AIServiceInitializer.DEFAULT_MODEL_ID;
    }

    /** 创建一张模型选择卡片（选中态高亮） */
    private void addModelCard(final com.oilquiz.app.ai.model.ModelDownloadManager.ModelPresetInfo preset) {
        // 容器：水平布局，圆角卡片
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setClickable(true);
        card.setFocusable(true);

        // 左侧：名称 + 描述
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        leftLp.gravity = android.view.Gravity.CENTER_VERTICAL;

        TextView name = new TextView(this);
        name.setTextSize(15);
        name.setTextColor(Color.WHITE);
        name.setText(preset.name + (preset.multimodal ? "  🌈" : ""));
        left.addView(name);

        TextView desc = new TextView(this);
        desc.setTextSize(11);
        desc.setTextColor(Color.argb(160, 255, 255, 255));
        desc.setText(preset.description);
        desc.setMaxLines(2);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(3);
        left.addView(desc, descLp);

        card.addView(left, leftLp);

        // 右侧：大小 + 选中
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        TextView size = new TextView(this);
        size.setTextSize(12);
        size.setTextColor(Color.argb(200, 255, 255, 255));
        long mb = preset.sizeMB > 0 ? preset.sizeMB : 0;
        size.setText(mb >= 1024 ? String.format("%.1f GB", mb / 1024f) : mb + " MB");
        right.addView(size);

        // 内存要求提示（minRamMB 较大时标注，避免用户误选爆内存模型）
        if (preset.minRamMB > 8192) {
            TextView ram = new TextView(this);
            ram.setTextSize(10);
            ram.setTextColor(0xFFF5A623);
            ram.setText("建议内存 ≥ " + (preset.minRamMB >= 1024 ? String.format("%.0f GB", preset.minRamMB / 1024f) : preset.minRamMB + " MB"));
            LinearLayout.LayoutParams ramLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            ramLp.topMargin = dp(2);
            right.addView(ram, ramLp);
        }

        TextView check = new TextView(this);
        check.setTextSize(20);
        check.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(dp(28), dp(28));
        checkLp.topMargin = dp(4);
        right.addView(check, checkLp);

        card.addView(right, rightLp);

        // 初始渲染
        refreshCard(card, check, preset.id.equals(selectedModelId));

        card.setOnClickListener(v -> {
            if (running) return; // 初始化中不可切换
            selectedModelId = preset.id;
            // 遍历所有卡片刷新选中态：用每个卡片自己的 id 定位其圆点
            for (int i = 0; i < modelPickerContainer.getChildCount(); i++) {
                View child = modelPickerContainer.getChildAt(i);
                Object tag = child.getTag();
                if (!(tag instanceof String)) continue;
                String childId = (String) tag;
                boolean sel = childId.equals(selectedModelId);
                View ck = child.findViewWithTag("check_" + childId);
                refreshCard(child, ck, sel);
            }
            updatePickerHint();
        });
        card.setTag(preset.id);
        // 给 check 设置 tag 便于定位（setTag 在 addView 前设置仍有效）
        check.setTag("check_" + preset.id);
        modelPickerContainer.addView(card, cardLp);
    }

    /** 刷新卡片选中态：选中 → 渐变高亮 + 圆点填充；未选中 → 半透明 + 空心 */
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

        if (checkView instanceof android.widget.TextView) {
            android.widget.TextView ck = (android.widget.TextView) checkView;
            if (selected) {
                ck.setText("●");
                ck.setTextColor(0xFF8E6CFF);
            } else {
                ck.setText("○");
                ck.setTextColor(0x66FFFFFF);
            }
        }
    }

    private void updatePickerHint() {
        if (tvPickerHint == null) return;
        if (selectedModelId == null) {
            tvPickerHint.setText("已选：1 个");
            return;
        }
        String nm = selectedModelId;
        for (com.oilquiz.app.ai.model.ModelDownloadManager.ModelPresetInfo p : presetList) {
            if (selectedModelId.equals(p.id)) { nm = p.name; break; }
        }
        tvPickerHint.setText("已选：" + nm);
    }

    private static final String TAG = "AIServiceInitActivity";

    // ==================== 步骤指示器 ====================

    private void buildSteps() {
        if (stepsContainer == null) return;
        stepsContainer.removeAllViews();
        steps.clear();

        for (int i = 0; i < STEP_LABELS.length; i++) {
            StepItem item = new StepItem(STEP_LABELS[i], STEP_EMOJIS[i]);
            LinearLayout itemLayout = new LinearLayout(this);
            itemLayout.setOrientation(LinearLayout.VERTICAL);
            itemLayout.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

            // 圆点
            FrameLayout dotWrap = new FrameLayout(this);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                    dp(52), dp(52));
            itemLayout.addView(dotWrap, dotLp);

            TextView dot = new TextView(this);
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(
                    dp(52), dp(52));
            dotParams.gravity = android.view.Gravity.CENTER;
            dot.setGravity(android.view.Gravity.CENTER);
            dot.setTextSize(20);
            dot.setText(item.emoji);
            dot.setTextColor(Color.WHITE);
            dotWrap.addView(dot, dotParams);
            item.dot = dot;

            // 底部圆环（状态标记）
            TextView ring = new TextView(this);
            FrameLayout.LayoutParams ringParams = new FrameLayout.LayoutParams(
                    dp(52), dp(52));
            ringParams.gravity = android.view.Gravity.CENTER;
            ring.setBackgroundResource(R.drawable.ai_init_step_ring);
            dotWrap.addView(ring, ringParams);
            item.ring = ring;

            // 文字
            TextView label = new TextView(this);
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
            if (i < STEP_LABELS.length - 1) {
                lp.setMarginEnd(dp(4));
            }
            stepsContainer.addView(itemLayout, lp);
            steps.add(item);
        }
    }

    /** 设置某一步的状态：0=未到 1=进行中 2=完成 */
    private void setStepState(int index, int state) {
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

    // ==================== 初始化流程 ====================

    private void startInitialization(String modelId) {
        if (running) return;
        running = true;
        completed = false;

        // 隐藏模型选择区，进入初始化流程
        if (modelPickerSection != null) modelPickerSection.setVisibility(View.GONE);

        // 记录所选模型是否为多模态（决定完成后是否显示视觉模块进度）
        currentMultimodal = false;
        if (modelId != null) {
            for (com.oilquiz.app.ai.model.ModelDownloadManager.ModelPresetInfo p : presetList) {
                if (modelId.equals(p.id)) { currentMultimodal = p.multimodal; break; }
            }
        }

        if (btnAction != null) {
            btnAction.setEnabled(false);
            btnAction.setText("初始化中…");
        }
        if (tvHint != null) tvHint.setText("请保持网络畅通 · 正在下载所选模型");

        // 重置
        ringProgress.setProgress(0);
        for (int i = 0; i < steps.size(); i++) setStepState(i, 0);
        setStepState(0, 1);
        tvStatus.setText("正在检测本地与在线模型配置…");

        AIServiceInitializer.start(this, modelId != null && !modelId.isEmpty()
                        ? modelId : com.oilquiz.app.ai.service.AIServiceInitializer.DEFAULT_MODEL_ID,
                new AIServiceInitializer.InitCallback() {
            @Override
            public void onProgress(String message, int percent) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    tvStatus.setText(message);
                    int p = (int) Math.max(0f, Math.min(100f, percent < 0 ? ringProgress.getCurrentProgress() : (float) percent));
                    ringProgress.setProgress(p);
                    // 主模型条形进度 + 百分比同步（与进度环一致）
                    if (modelProgress != null) modelProgress.setProgress(p);
                    if (tvModelStatus != null) tvModelStatus.setText(p + "%");
                    updateStepsByProgress(p, message);
                });
            }

            @Override
            public void onDownloadReady(String modelName, String modelFileName) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    // 两步分离：下载完成 → 显示「加载模型」，用户点击后再加载（避免大模型加载卡 UI）
                    tvStatus.setText("✅ 下载完成，点击「加载模型」开始使用");
                    tvTitle.setText("下载完成");
                    ringProgress.setProgress(100);
                    setStepState(0, 2);
                    setStepState(1, 2);
                    setStepState(2, 2);
                    setStepState(3, 0);
                    if (btnAction != null) {
                        btnAction.setEnabled(true);
                        btnAction.setText("加载模型");
                        btnAction.setOnClickListener(v -> {
                            if (btnAction != null) { btnAction.setEnabled(false); btnAction.setText("加载中…"); }
                            tvStatus.setText("正在加载模型，首次加载较慢…");
                            AIServiceInitializer.loadDownloadedModel(AIServiceInitActivity.this, modelId, this);
                        });
                    }
                    if (tvHint != null) tvHint.setText("点击「加载模型」后即可离线对话、识图与智能问答");
                });
            }

            public void onComplete(String modelName, boolean downloaded) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    finishSuccess(modelName);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    finishError(error);
                });
            }

            @Override
            public void onSecondaryProgress(String label, int percent, long downloadedMB, long totalMB) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    if (mmprojContainer != null && mmprojContainer.getVisibility() != View.VISIBLE) {
                        mmprojContainer.setVisibility(View.VISIBLE);
                    }
                    if (percent >= 0 && mmprojProgress != null) {
                        mmprojProgress.setProgress(Math.max(0, Math.min(100, percent)));
                    }
                    // 只在有效数据时更新文本（percent<0 为心跳，不覆盖真实下载量/总量，避免 0/0 闪回）
                    if (tvMmprojStatus != null && totalMB > 0) {
                        tvMmprojStatus.setText(downloadedMB + " / " + totalMB + " MB");
                    }
                });
            }
        });
    }

    private void updateStepsByProgress(int progress, String message) {
        // 依据阶段文案细分步骤状态，避免"检测配置/下载模型"来回横跳
        String msg = message == null ? "" : message;
        boolean downloading = msg.contains("下载") || msg.contains("下载中");
        boolean configuring = msg.contains("配置") || msg.contains("加载模型")
                || msg.contains("就绪") || msg.contains("加载中") || msg.contains("加载服务");
        boolean detecting = msg.contains("检测");

        if (progress <= 0 && detecting) {
            // 检测配置中
            setStepState(0, 1);
            setStepState(1, 0);
            setStepState(2, 0);
            setStepState(3, 0);
        } else if (progress < 100 || downloading) {
            // 下载中（含 mmproj 补充下载）
            setStepState(0, 2);
            setStepState(1, 1);
            setStepState(2, 0);
            setStepState(3, 0);
        } else if (configuring && progress >= 100) {
            // 配置模型 / 加载服务阶段
            setStepState(0, 2);
            setStepState(1, 2);
            setStepState(2, 2);
            setStepState(3, 1);
        } else {
            setStepState(0, 2);
            setStepState(1, 1);
            setStepState(2, 0);
            setStepState(3, 0);
        }
    }

    private void finishSuccess(String modelName) {
        running = false;
        completed = true;
        ringProgress.setProgress(100);
        for (int i = 0; i < steps.size(); i++) setStepState(i, 2);
        tvTitle.setText("AI 服务已就绪");
        tvSubtitle.setText(modelName);
        tvStatus.setText("✅ 初始化完成，本地模型已加载");
        if (currentMultimodal) {
            // 仅多模态模型展示视觉模块（mmproj）完成态
            if (mmprojContainer != null) {
                mmprojContainer.setVisibility(View.VISIBLE);
                if (mmprojProgress != null) mmprojProgress.setProgress(100);
            }
        } else {
            // 非多模态模型：隐藏视觉模块区，避免显示假进度条
            if (mmprojContainer != null) mmprojContainer.setVisibility(View.GONE);
        }
        if (btnAction != null) {
            btnAction.setEnabled(true);
            btnAction.setText("开始使用");
        }
        if (tvHint != null) tvHint.setText("恭喜！现在可以离线对话、识图与智能问答了");
        playCompleteBounce();
    }

    private void finishError(String error) {
        running = false;
        completed = false;
        // 重新显示模型选择区，允许用户换模型重试
        if (modelPickerSection != null) modelPickerSection.setVisibility(View.VISIBLE);
        tvStatus.setText("❌ " + friendlyError(error));
        tvTitle.setText("初始化未完成");
        if (btnAction != null) {
            btnAction.setEnabled(true);
            btnAction.setText("重试");
        }
        if (tvHint != null) tvHint.setText("可前往「模型下载」页面手动下载，或检查网络后重试");
    }

    /** 把底层技术化错误映射成用户可读的友好提示（下载失败/校验失败/存储不足等分类） */
    private String friendlyError(String error) {
        if (error == null || error.trim().isEmpty()) return "未知错误，请重试";
        String e = error.toLowerCase();
        if (e.contains("finalize") || e.contains("rename") || e.contains("校验") || e.contains("sha-256")) {
            return "文件校验/保存失败，已自动重新下载，请稍后重试";
        }
        if (e.contains("connect") || e.contains("timeout") || e.contains("reset")
                || e.contains("refused") || e.contains("unreachable") || e.contains("network")
                || e.contains("网络") || e.contains("socket") || e.contains("dns")) {
            return "网络连接失败，已自动切换备用下载源，请检查网络后重试";
        }
        if (e.contains("空间") || e.contains("no space") || e.contains("enospc")) {
            return "存储空间不足，请清理后重试";
        }
        if (e.contains("cancel") || e.contains("取消")) {
            return "已取消初始化";
        }
        return error;
    }

    // ==================== 动效 ====================

    private void startLogoPulse() {
        if (logoContainer == null) return;
        logoPulse = ObjectAnimator.ofPropertyValuesHolder(
                logoContainer,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.06f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.06f));
        logoPulse.setDuration(1400);
        logoPulse.setRepeatCount(ObjectAnimator.INFINITE);
        logoPulse.setRepeatMode(ObjectAnimator.REVERSE);
        logoPulse.start();
    }

    private void playCompleteBounce() {
        if (logoContainer == null) return;
        if (logoPulse != null) logoPulse.cancel();
        logoContainer.animate()
                .scaleX(1.15f).scaleY(1.15f)
                .setDuration(250)
                .withEndAction(() -> logoContainer.animate()
                        .scaleX(1f).scaleY(1f)
                        .setInterpolator(new OvershootInterpolator())
                        .setDuration(450)
                        .start())
                .start();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (logoPulse != null) logoPulse.cancel();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        destroyed = true;
        finish();
    }

    // ==================== 内部类 ====================

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
