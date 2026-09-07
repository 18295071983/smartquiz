package com.oilquiz.app.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
/**
 * AI 文生图 / 文生视频页面（百炼 DashScope 通义万相）。
 *
 * 后端：DashscopeMediaTool（action=image/video/query，Key 默认取当前在线模型配置）。
 * 入口：AI 中心"AI生图/AI生视频"按钮；app_operation navigate page=media_gen。
 *
 * 交互：输入描述 → 生成（文生图短轮询，完成即显示；文生视频异步提交返回 task_id，
 * 界面提供"查询进度"按钮轮询下载）。结果可保存到工作区并展示/播放/分享。
 */
public class MediaGenActivity extends Activity {

    private static final String[] IMAGE_MODELS = {"wan2.2-t2i-flash", "wan2.2-t2i-plus",
            "qwen-image-max", "qwen-image-plus", "qwen-image-3.0"};
    private static final String[] IMAGE_SIZES = {"1024*1024", "768*1024", "1024*768", "512*512", "1440*1440"};
    private static final String[] VIDEO_MODELS = {"wan2.1-t2v-turbo", "wan2.2-t2v-plus"};
    private static final String[] VIDEO_SIZES = {"832*480", "480*832", "624*624", "1440*1440",
            "1632*1248", "1248*1632", "1920*1080", "1080*1920"};
    /** scnet 等 OpenAI 兼容提供商的模型/尺寸清单（scnet 实测：生图 Qwen-Image-2.0、尺寸用 *） */
    private static final String[] SC_IMAGE_MODELS = {"Qwen-Image-2.0", "qwen-image-max", "qwen-image-plus"};
    private static final String[] SC_VIDEO_MODELS = {"Seedance2.0"};
    private static final String[] SC_IMAGE_SIZES = {"1024*1024", "2048*2048", "768*1024", "1024*768"};
    private static final String[] SC_VIDEO_SIZES = {"1280*720", "720*1280", "960*960", "1024*1024"};
    private static final String[] DURATIONS = {"5", "10"};

    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private String mode = "image";
    private String currentTaskId = null;
    private String currentTaskType = "video";

    private EditText etPrompt;
    private Spinner spProvider;
    private EditText etApiUrl;
    private EditText etApiKey;
    private android.widget.AutoCompleteTextView spModel;
    private android.widget.AutoCompleteTextView spSize;
    private Spinner spDuration;
    private TextView tvStatus;
    private TextView tvCostHint;
    private LinearLayout resultArea;
    private ImageView imgResult;
    private TextView tvVideoInfo;
    private LinearLayout videoActions;
    private LinearLayout taskArea;
    private TextView tvTask;
    private Button btnQuery;
    private Button btnImageMode, btnVideoMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String m = getIntent() != null ? getIntent().getStringExtra("mode") : null;
        if ("video".equals(m)) mode = "video";
        buildUi();
        // 动态参数注入（Agent 经 app_operation navigate page=media_gen params={...} 预填）
        applyInjectedParams();
    }

    /** 读取 intent extras（mode/prompt/model/size/duration/api_url/api_key）预填表单 */
    private void applyInjectedParams() {
        Intent in = getIntent();
        if (in == null) return;
        String prompt = in.getStringExtra("prompt");
        if (prompt != null && !prompt.isEmpty()) etPrompt.setText(prompt);
        String model = in.getStringExtra("model");
        if (model != null && !model.isEmpty()) {
            spModel.setText(model);
            spModel.selectAll();
        }
        String size = in.getStringExtra("size");
        if (size != null && !size.isEmpty()) {
            spSize.setText(size);
            spSize.selectAll();
        }
        String duration = in.getStringExtra("duration");
        if (duration != null && !duration.isEmpty()) selectSpinner(spDuration, duration);
        // 端点/Key 注入（Agent 可指定提供商；Key 留空则用配置解析）
        String apiUrl = in.getStringExtra("api_url");
        if (apiUrl != null && !apiUrl.isEmpty()) etApiUrl.setText(apiUrl);
        String apiKey = in.getStringExtra("api_key");
        if (apiKey != null && !apiKey.isEmpty()) etApiKey.setText(apiKey);
    }

    private void selectSpinner(Spinner sp, String value) {
        try {
            for (int i = 0; i < sp.getCount(); i++) {
                Object item = sp.getItemAtPosition(i);
                if (item != null && item.toString().equalsIgnoreCase(value.trim())) {
                    sp.setSelection(i);
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(ThemeColors.get(R.color.hc_fff8fafc));

        TextView title = new TextView(this);
        title.setText("🎨 AI 文生图 / 视频（百炼通义万相）");
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(ThemeColors.get(R.color.hc_ff111827));
        root.addView(title);

        // 模式切换
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, dp(10), 0, dp(4));
        btnImageMode = modeButton("🖼️ 文生图");
        btnVideoMode = modeButton("🎬 文生视频");
        modeRow.addView(btnImageMode, rowWeight());
        modeRow.addView(btnVideoMode, rowWeight());
        root.addView(modeRow);
        btnImageMode.setOnClickListener(v -> setMode("image"));
        btnVideoMode.setOnClickListener(v -> setMode("video"));

        // 提供商/端点选择：从模型管理配置表动态获取（跟随配置 + 各已配置提供商）
        spProvider = new Spinner(this);
        loadProvidersFromConfig();
        root.addView(fieldRow("提供商", spProvider));
        etApiUrl = new EditText(this);
        etApiUrl.setHint("端点 api_url（留空=跟随模型管理配置的百炼端点）");
        etApiUrl.setSingleLine(true);
        etApiUrl.setTextSize(13);
        root.addView(fieldRow("端点", etApiUrl));
        etApiKey = new EditText(this);
        etApiKey.setHint("API Key（已从配置自动填入，可留空；如需覆盖请自行填写）");
        etApiKey.setSingleLine(true);
        etApiKey.setTextSize(13);
        root.addView(fieldRow("API Key", etApiKey));
        spProvider.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                applyProvider();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        // 提示词 + AI 优化按钮
        TextView lblPrompt = label("画面 / 视频描述");
        LinearLayout promptHead = new LinearLayout(this);
        promptHead.setOrientation(LinearLayout.HORIZONTAL);
        promptHead.setGravity(Gravity.CENTER_VERTICAL);
        promptHead.addView(lblPrompt);
        Button btnOptimize = new Button(this);
        btnOptimize.setText("✨ AI优化");
        btnOptimize.setTextSize(11);
        btnOptimize.setBackgroundColor(ThemeColors.attr(this, R.attr.colorPrimary));
        btnOptimize.setTextColor(Color.WHITE);
        btnOptimize.setPadding(dp(8), 0, dp(8), 0);
        btnOptimize.setOnClickListener(v -> optimizePrompt());
        promptHead.addView(btnOptimize);
        root.addView(promptHead);
        etPrompt = new EditText(this);
        etPrompt.setHint(mode.equals("video")
                ? "描述视频内容与运镜，如：一只橘猫在草地上打滚，镜头缓缓推进…"
                : "描述画面，如：一只可爱的橘猫在草地上晒太阳");
        etPrompt.setMinLines(3);
        etPrompt.setMaxLines(6);
        etPrompt.setGravity(Gravity.TOP | Gravity.START);
        etPrompt.setTextSize(14);
        etPrompt.setBackgroundColor(ThemeColors.get(R.color.hc_ffffffff));
        root.addView(etPrompt);

        // 模型 + 尺寸 + 时长
        spModel = new android.widget.AutoCompleteTextView(this);
        spModel.setThreshold(0);
        spModel.setSingleLine(true);
        spModel.setTextSize(14);
        spModel.setHint("模型：点开选或直接输入（如 wan2.2-t2i-flash / Seedream）");
        spModel.setAdapter(fullListAdapter(IMAGE_MODELS));
        // 点击/聚焦即弹出完整下拉列表（阈值0 + 手动弹）
        spModel.setOnClickListener(v -> spModel.showDropDown());
        spModel.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) spModel.showDropDown();
        });
        Button btnRefreshModels = new Button(this);
        btnRefreshModels.setText("🔄 刷新模型");
        btnRefreshModels.setTextSize(11);
        btnRefreshModels.setBackgroundColor(ThemeColors.get(R.color.hc_ff64748b));
        btnRefreshModels.setTextColor(Color.WHITE);
        btnRefreshModels.setPadding(dp(6), 0, dp(6), 0);
        btnRefreshModels.setOnClickListener(v -> refreshModels());
        LinearLayout modelRow = fieldRow("模型", spModel);
        modelRow.addView(btnRefreshModels, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(modelRow);
        spSize = new android.widget.AutoCompleteTextView(this);
        spSize.setThreshold(0);
        spSize.setSingleLine(true);
        spSize.setTextSize(14);
        spSize.setHint("尺寸：点开选或直接输入（如 1024*1024 / 832*480）");
        spSize.setAdapter(fullListAdapter(IMAGE_SIZES));
        spSize.setOnClickListener(v -> spSize.showDropDown());
        spSize.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) spSize.showDropDown();
        });
        root.addView(fieldRow("尺寸", spSize));
        spDuration = new Spinner(this);
        spDuration.setAdapter(spinnerAdapter(DURATIONS));
        root.addView(fieldRow("视频时长(秒)", spDuration));
        spDuration.setVisibility(mode.equals("video") ? View.VISIBLE : View.GONE);

        // 生成按钮
        Button btnGenerate = new Button(this);
        btnGenerate.setText("🚀 生成" + (mode.equals("video") ? "视频" : "图片"));
        btnGenerate.setTextSize(15);
        btnGenerate.setTextColor(Color.WHITE);
        btnGenerate.setBackgroundColor(ThemeColors.get(R.color.hc_ff2563eb));
        btnGenerate.setPadding(0, dp(12), 0, dp(12));
        btnGenerate.setOnClickListener(v -> onGenerate());
        root.addView(btnGenerate);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(ThemeColors.get(R.color.hc_ff374151));
        tvStatus.setPadding(0, dp(8), 0, dp(4));
        root.addView(tvStatus);

        // 费用提示（文生视频按秒计费，尺寸越大越贵）
        tvCostHint = new TextView(this);
        tvCostHint.setTextSize(11);
        tvCostHint.setTextColor(ThemeColors.get(R.color.hc_ffb45309));
        tvCostHint.setPadding(0, dp(2), 0, dp(6));
        root.addView(tvCostHint);

        // 结果区
        resultArea = new LinearLayout(this);
        resultArea.setOrientation(LinearLayout.VERTICAL);
        imgResult = new ImageView(this);
        imgResult.setAdjustViewBounds(true);
        imgResult.setMaxHeight(dp(420));
        imgResult.setVisibility(View.GONE);
        resultArea.addView(imgResult);
        tvVideoInfo = new TextView(this);
        tvVideoInfo.setTextSize(13);
        tvVideoInfo.setTextColor(ThemeColors.get(R.color.hc_ff374151));
        tvVideoInfo.setVisibility(View.GONE);
        resultArea.addView(tvVideoInfo);
        videoActions = new LinearLayout(this);
        videoActions.setOrientation(LinearLayout.HORIZONTAL);
        videoActions.setVisibility(View.GONE);
        Button btnPlay = new Button(this);
        btnPlay.setText("▶ 播放");
        btnPlay.setBackgroundColor(ThemeColors.get(R.color.hc_ff059669));
        btnPlay.setTextColor(Color.WHITE);
        btnPlay.setOnClickListener(v -> playVideo());
        Button btnShare = new Button(this);
        btnShare.setText("分享");
        btnShare.setBackgroundColor(ThemeColors.attr(this, R.attr.colorPrimary));
        btnShare.setTextColor(Color.WHITE);
        btnShare.setOnClickListener(v -> shareVideo());
        videoActions.addView(btnPlay, rowWeight());
        videoActions.addView(btnShare, rowWeight());
        resultArea.addView(videoActions);
        root.addView(resultArea);

        // 任务查询区
        taskArea = new LinearLayout(this);
        taskArea.setOrientation(LinearLayout.VERTICAL);
        taskArea.setPadding(0, dp(8), 0, 0);
        tvTask = new TextView(this);
        tvTask.setTextSize(12);
        tvTask.setTextColor(ThemeColors.get(R.color.hc_ff6b7280));
        taskArea.addView(tvTask);
        btnQuery = new Button(this);
        btnQuery.setText("🔄 查询进度");
        btnQuery.setBackgroundColor(ThemeColors.get(R.color.hc_fff59e0b));
        btnQuery.setTextColor(Color.WHITE);
        btnQuery.setVisibility(View.GONE);
        btnQuery.setOnClickListener(v -> onQuery());
        taskArea.addView(btnQuery);
        root.addView(taskArea);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        setMode(mode);
    }

    // ==================== 交互 ====================

    private void setMode(String m) {
        mode = m;
        btnImageMode.setBackgroundColor(mode.equals("image") ? ThemeColors.get(R.color.hc_ff2563eb) : ThemeColors.get(R.color.hc_ffe5e7eb));
        btnImageMode.setTextColor(mode.equals("image") ? Color.WHITE : ThemeColors.get(R.color.hc_ff374151));
        btnVideoMode.setBackgroundColor(mode.equals("video") ? ThemeColors.get(R.color.hc_ff2563eb) : ThemeColors.get(R.color.hc_ffe5e7eb));
        btnVideoMode.setTextColor(mode.equals("video") ? Color.WHITE : ThemeColors.get(R.color.hc_ff374151));
        spDuration.setVisibility(mode.equals("video") ? View.VISIBLE : View.GONE);
        etPrompt.setHint(mode.equals("video")
                ? "描述视频内容与运镜，如：一只橘猫在草地上打滚，镜头缓缓推进…"
                : "描述画面，如：一只可爱的橘猫在草地上晒太阳");
        applyProvider();
        resetResult();
    }

    /** 提供商下拉数据（与 spProvider 位置对应）：0=跟随模型管理配置，其余=各已配置提供商 */
    private java.util.List<String> providerUrls = new java.util.ArrayList<>();
    private java.util.List<String> providerKeys = new java.util.ArrayList<>();
    /** 从配置解析的真实 Key（不显示明文，仅内部使用） */
    private String resolvedApiKey = "";

    /** Key 掩码显示：sk-a01a****cd0f（不暴露明文） */
    private String maskKey(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 8) return "****";
        return key.substring(0, 6) + "****" + key.substring(key.length() - 4);
    }

    /** 从模型管理配置表加载提供商下拉（动态，配了什么显示什么） */
    private void loadProvidersFromConfig() {
        java.util.List<String> labels = new java.util.ArrayList<>();
        providerUrls = new java.util.ArrayList<>();
        providerKeys = new java.util.ArrayList<>();
        labels.add("跟随模型管理配置（百炼系）");
        providerUrls.add("");
        providerKeys.add("");
        try {
            com.oilquiz.app.ai.model.OnlineModelManager m =
                    com.oilquiz.app.ai.model.OnlineModelManager.getInstance(this);
            for (com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig c : m.getModelList()) {
                if (c.apiUrl == null || c.apiUrl.trim().isEmpty()) continue;
                String host = c.apiUrl.trim();
                try {
                    java.net.URI uri = new java.net.URI(host);
                    host = uri.getHost() != null ? uri.getHost() : host;
                } catch (Exception ignored) {
                }
                String name = c.name != null && !c.name.isEmpty() ? c.name : host;
                labels.add(name + "（" + host + "）");
                providerUrls.add(c.apiUrl.trim());
                providerKeys.add(c.apiKey != null ? c.apiKey.trim() : "");
            }
        } catch (Throwable t) {
            android.util.Log.w("MediaGenActivity", "loadProvidersFromConfig failed: " + t.getMessage());
        }
        spProvider.setAdapter(spinnerAdapter(labels.toArray(new String[0])));
        spProvider.setSelection(0);
    }

    /** 按「模式 × 提供商」刷新模型/尺寸下拉与费用提示 */
    private void applyProvider() {
        int pos = spProvider.getSelectedItemPosition();
        if (pos < 0 || pos >= providerUrls.size()) pos = 0;
        String apiUrl = pos > 0 ? providerUrls.get(pos) : "";
        resolvedApiKey = pos > 0 ? providerKeys.get(pos) : "";
        etApiUrl.setText(apiUrl);
        // Key 不明文显示：输入框留空，掩码提示已自动填入
        etApiKey.setText("");
        etApiKey.setHint(resolvedApiKey != null && !resolvedApiKey.isEmpty()
                ? "API Key 已自动填入 " + maskKey(resolvedApiKey) + "（留空即可；覆盖请填写）"
                : "API Key（留空=自动解析）");
        // 协议判断：百炼系（dashscope/maas）走原生；其余（scnet 等）走 OpenAI 兼容
        boolean isBailian = apiUrl.isEmpty()
                || apiUrl.toLowerCase().contains("dashscope")
                || apiUrl.toLowerCase().contains("maas.aliyuncs.com");
        String[] modelList;
        String[] sizeList;
        if (isBailian) {
            modelList = mode.equals("video") ? VIDEO_MODELS : IMAGE_MODELS;
            sizeList = mode.equals("video") ? VIDEO_SIZES : IMAGE_SIZES;
        } else {
            // scnet 等 OpenAI 兼容：Seedream 生图 / Seedance2.0 生视频，尺寸用 x 分隔
            modelList = mode.equals("video") ? SC_VIDEO_MODELS : SC_IMAGE_MODELS;
            sizeList = mode.equals("video") ? SC_VIDEO_SIZES : SC_IMAGE_SIZES;
        }
        spModel.setAdapter(fullListAdapter(modelList));
        spModel.setText(modelList[0], false);
        spModel.selectAll();
        spSize.setAdapter(fullListAdapter(sizeList));
        spSize.setText(sizeList[0], false);
        spSize.selectAll();
        // 能力提示：检查其他提供商是否支持文生图/文生视频
        String capHint = providerCapabilityHint(apiUrl);
        if (!isBailian) {
            tvCostHint.setText(capHint + "\n💰 费用按平台计费，生成前请自行确认");
        } else if (mode.equals("video")) {
            tvCostHint.setText("💰 视频按秒计费：turbo≈0.3元/5s，plus 更贵（约¥0.5+/秒），"
                    + "1080*1920 等大尺寸单价最高；建议 turbo + 小尺寸");
        } else {
            tvCostHint.setText("💰 文生图：wan2.2-t2i-flash 按张计费（约 ¥0.15/张），plus 更贵，尺寸越大越贵");
        }
    }

    /** 动态获取账号可用模型（dashscope_media action=models），更新模型下拉 */
    private void refreshModels() {
        tvStatus.setText("⏳ 获取可用模型…");
        executor.execute(() -> {
            try {
                com.oilquiz.app.ai.tool.DashscopeMediaTool tool =
                        new com.oilquiz.app.ai.tool.DashscopeMediaTool(MediaGenActivity.this);
                Map<String, Object> p = new HashMap<>();
                p.put("action", "models");
                String apiUrl = etApiUrl.getText() != null ? etApiUrl.getText().toString().trim() : "";
                if (!apiUrl.isEmpty()) p.put("api_url", apiUrl);
                String apiKey = etApiKey.getText() != null ? etApiKey.getText().toString().trim() : "";
                if (apiKey.isEmpty()) apiKey = resolvedApiKey;
                if (!apiKey.isEmpty()) p.put("api_key", apiKey);
                com.oilquiz.app.ai.tool.AIToolResult r = tool.execute(p);
                runOnUiThread(() -> {
                    if (r != null && r.isSuccess() && r.getResult() instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> res = (Map<String, Object>) r.getResult();
                        String modelsJson = mode.equals("video")
                                ? String.valueOf(res.get("video_models"))
                                : String.valueOf(res.get("image_models"));
                        String[] arr = parseJsonArray(modelsJson);
                        if (arr.length > 0) {
                            spModel.setAdapter(fullListAdapter(arr));
                            spModel.setText(arr.length > 0 ? arr[0] : "", false);
                            spModel.selectAll();
                            tvStatus.setText("✅ 已动态加载 " + arr.length + " 个模型，请选择");
                            return;
                        }
                    }
                    String err = (r != null && r.getErrorMessage() != null)
                            ? r.getErrorMessage() : "无可用模型";
                    tvStatus.setText("❌ 获取模型失败: " + err);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> tvStatus.setText("❌ 获取模型失败: " + t.getMessage()));
            }
        });
    }

    private String[] parseJsonArray(String s) {
        try {
            if (s == null || !s.trim().startsWith("[")) return new String[0];
            org.json.JSONArray arr = new org.json.JSONArray(s);
            String[] out = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) out[i] = arr.optString(i);
            return out;
        } catch (Exception e) {
            return new String[0];
        }
    }

    private void onGenerate() {
        String prompt = etPrompt.getText() != null ? etPrompt.getText().toString().trim() : "";
        if (prompt.isEmpty()) {
            Toast.makeText(this, "请先输入描述", Toast.LENGTH_SHORT).show();
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("action", mode);
        params.put("prompt", prompt);
        String model = spModel.getText() != null ? spModel.getText().toString().trim() : "";
        params.put("model", !model.isEmpty() ? model : (mode.equals("video") ? "wan2.1-t2v-turbo" : "wan2.2-t2i-flash"));
        String sizeText = spSize.getText() != null ? spSize.getText().toString().trim() : "";
        params.put("size", !sizeText.isEmpty() ? sizeText : (mode.equals("video") ? "832*480" : "1024*1024"));
        if (mode.equals("video")) {
            Object dur = spDuration.getSelectedItem();
            try {
                params.put("duration", dur != null ? Integer.parseInt(dur.toString()) : 5);
            } catch (Exception ignored) {
                params.put("duration", 5);
            }
            // UI 页已有费用提示，不再重复弹确认框
            params.put("confirm_cost", "false");
        }
        // 提供商/端点：随下拉联动（配置驱动），端点/Key 已由 applyProvider 填入；用户可改
        String apiUrl = etApiUrl.getText() != null ? etApiUrl.getText().toString().trim() : "";
        String apiKey = etApiKey.getText() != null ? etApiKey.getText().toString().trim() : "";
        if (apiKey.isEmpty()) apiKey = resolvedApiKey; // 用户未输入则用配置解析的 Key（不明文显示）
        if (!apiUrl.isEmpty()) params.put("api_url", apiUrl);
        if (!apiKey.isEmpty()) params.put("api_key", apiKey);
        currentTaskId = null;
        currentTaskType = mode;
        tvStatus.setText("⏳ 提交" + (mode.equals("video") ? "视频" : "图片") + "生成任务…");
        tvTask.setText("");
        btnQuery.setVisibility(View.GONE);
        resetResult();
        executor.execute(() -> {
            try {
                com.oilquiz.app.ai.tool.DashscopeMediaTool tool =
                        new com.oilquiz.app.ai.tool.DashscopeMediaTool(MediaGenActivity.this);
                com.oilquiz.app.ai.tool.AIToolResult r = tool.execute(params);
                runOnUiThread(() -> handleToolResult(r));
            } catch (Throwable t) {
                runOnUiThread(() -> tvStatus.setText("生成失败: " + t.getMessage()));
            }
        });
    }

    private void onQuery() {
        if (currentTaskId == null) {
            Toast.makeText(this, "没有待查询的任务", Toast.LENGTH_SHORT).show();
            return;
        }
        tvStatus.setText("⏳ 查询任务进度…");
        btnQuery.setEnabled(false);
        Map<String, Object> params = new HashMap<>();
        params.put("action", "query");
        params.put("task_id", currentTaskId);
        params.put("type", currentTaskType);
        // 查询必须带当前提供商端点（scnet 等 OpenAI 兼容端点任务查询要用同一端点）
        String apiUrl = etApiUrl.getText() != null ? etApiUrl.getText().toString().trim() : "";
        if (!apiUrl.isEmpty()) params.put("api_url", apiUrl);
        String apiKey = etApiKey.getText() != null ? etApiKey.getText().toString().trim() : "";
        if (apiKey.isEmpty()) apiKey = resolvedApiKey;
        if (!apiKey.isEmpty()) params.put("api_key", apiKey);
        executor.execute(() -> {
            try {
                com.oilquiz.app.ai.tool.DashscopeMediaTool tool =
                        new com.oilquiz.app.ai.tool.DashscopeMediaTool(MediaGenActivity.this);
                com.oilquiz.app.ai.tool.AIToolResult r = tool.execute(params);
                runOnUiThread(() -> {
                    btnQuery.setEnabled(true);
                    handleToolResult(r);
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    btnQuery.setEnabled(true);
                    tvStatus.setText("查询失败: " + t.getMessage());
                });
            }
        });
    }

    /** 解析工具结果：成功文件 / 任务处理中 / 失败 */
    @SuppressWarnings("unchecked")
    private void handleToolResult(com.oilquiz.app.ai.tool.AIToolResult r) {
        if (r == null) {
            tvStatus.setText("无返回结果");
            return;
        }
        Map<String, Object> result = null;
        if (r.getResult() instanceof Map) {
            result = (Map<String, Object>) r.getResult();
        }
        if (r.isSuccess() && result != null) {
            Object taskId = result.get("task_id");
            Object filePath = result.get("filePath");
            if (taskId != null && filePath == null) {
                // 任务处理中（image 未完成 / video 已提交）
                currentTaskId = String.valueOf(taskId);
                tvTask.setText("任务ID: " + taskId + "\n生成需数分钟，点下方按钮查询进度");
                btnQuery.setVisibility(View.VISIBLE);
                tvStatus.setText(result.get("message") != null ? String.valueOf(result.get("message")) : "任务处理中");
                return;
            }
            if (filePath != null) {
                showResultFile(String.valueOf(filePath),
                        result.get("contentUri") != null ? String.valueOf(result.get("contentUri")) : null);
                tvStatus.setText("✅ " + (mode.equals("video") ? "视频" : "图片") + "已生成并保存到工作区");
                return;
            }
            tvStatus.setText(result.get("message") != null ? String.valueOf(result.get("message")) : "成功");
            return;
        }
        // 失败
        String err = r.getErrorMessage() != null ? r.getErrorMessage() : "未知错误";
        tvStatus.setText("❌ " + err);
        Toast.makeText(this, "生成失败: " + err, Toast.LENGTH_LONG).show();
    }

    private void showResultFile(String path, String contentUri) {
        File f = new File(path);
        if (!f.exists()) {
            tvStatus.setText("结果文件不存在: " + path);
            return;
        }
        lastResultPath = path;
        String ext = path.toLowerCase();
        if (ext.endsWith(".png") || ext.endsWith(".jpg") || ext.endsWith(".jpeg")
                || ext.endsWith(".webp") || ext.endsWith(".gif")) {
            imgResult.setVisibility(View.VISIBLE);
            tvVideoInfo.setVisibility(View.GONE);
            videoActions.setVisibility(View.GONE);
            com.bumptech.glide.Glide.with(this).load(f).into(imgResult);
        } else {
            // 视频
            imgResult.setVisibility(View.GONE);
            tvVideoInfo.setVisibility(View.VISIBLE);
            tvVideoInfo.setText("🎬 " + f.getName() + "\n" + formatSize(f.length()) + "\n" + path);
            videoActions.setVisibility(View.VISIBLE);
        }
    }

    private void playVideo() {
        String path = lastResultPath();
        if (path == null) return;
        try {
            File f = new File(path);
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this, "com.oilquiz.app.fileprovider", f);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "播放失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareVideo() {
        String path = lastResultPath();
        if (path == null) return;
        try {
            File f = new File(path);
            Uri shareUri;
            try {
                com.oilquiz.app.ai.agent.online.AgentWorkspace ws =
                        com.oilquiz.app.ai.agent.online.AgentWorkspace.getInstance(this);
                boolean inWorkspace = f.getCanonicalPath().startsWith(ws.getWorkspaceDir().getCanonicalPath());
                if (inWorkspace && ws.isPublicWorkspace()) {
                    shareUri = androidx.core.content.FileProvider.getUriForFile(
                            this, "com.oilquiz.app.fileprovider", f);
                } else {
                    shareUri = copyToDownloads(f);
                }
            } catch (Throwable t) {
                shareUri = copyToDownloads(f);
            }
            if (shareUri == null) {
                Toast.makeText(this, "分享准备失败", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("video/*");
            share.putExtra(Intent.EXTRA_STREAM, shareUri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(share, "分享视频"));
        } catch (Exception e) {
            Toast.makeText(this, "分享失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String lastResultPath = null;

    private String lastResultPath() {
        return lastResultPath;
    }

    /** 复制到公共 Download/OilQuiz（私有工作区分享用），返回 content URI */
    private Uri copyToDownloads(File source) {
        try {
            String fileName = source.getName();
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "video/mp4");
            values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/OilQuiz");
            android.net.Uri collection = android.os.Build.VERSION.SDK_INT >= 29
                    ? android.provider.MediaStore.Downloads
                            .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            android.net.Uri item = getContentResolver().insert(collection, values);
            if (item == null) return null;
            try (java.io.OutputStream os = getContentResolver().openOutputStream(item)) {
                if (os == null) return null;
                try (java.io.InputStream is = new java.io.FileInputStream(source)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
                }
            }
            return item;
        } catch (Exception e) {
            android.util.Log.w("MediaGenActivity", "copy to downloads failed: " + e.getMessage());
            return null;
        }
    }

    private void resetResult() {
        imgResult.setVisibility(View.GONE);
        tvVideoInfo.setVisibility(View.GONE);
        videoActions.setVisibility(View.GONE);
        lastResultPath = null;
    }

    // ==================== UI 工具 ====================

    private Button modeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setPadding(0, dp(10), 0, dp(10));
        return b;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(ThemeColors.get(R.color.hc_ff374151));
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(10), 0, dp(2));
        return tv;
    }

    private LinearLayout fieldRow(String name, View field) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView nameTv = new TextView(this);
        nameTv.setText(name);
        nameTv.setTextSize(13);
        nameTv.setTextColor(ThemeColors.get(R.color.hc_ff374151));
        nameTv.setPadding(0, 0, dp(10), 0);
        row.addView(nameTv);
        row.addView(field, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.setPadding(0, dp(4), 0, dp(4));
        return row;
    }

    private android.widget.ArrayAdapter<String> spinnerAdapter(String[] items) {
        android.widget.ArrayAdapter<String> ad =
                new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return ad;
    }

    /** AutoCompleteTextView 专用：下拉始终显示完整列表，不被当前输入文本过滤（点击=展开全部） */
    private android.widget.ArrayAdapter<String> fullListAdapter(final String[] items) {
        final java.util.List<String> list = java.util.Arrays.asList(items);
        android.widget.ArrayAdapter<String> ad =
                new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
                    @Override public android.widget.Filter getFilter() {
                        return new android.widget.Filter() {
                            @Override protected android.widget.Filter.FilterResults performFiltering(CharSequence constraint) {
                                android.widget.Filter.FilterResults r = new android.widget.Filter.FilterResults();
                                r.values = list;
                                r.count = list.size();
                                return r;
                            }
                            @Override protected void publishResults(CharSequence constraint, android.widget.Filter.FilterResults results) {
                                notifyDataSetChanged();
                            }
                        };
                    }
                };
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return ad;
    }

    private LinearLayout.LayoutParams rowWeight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(java.util.Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }

    /** 检查其他提供商是否支持文生图/文生视频（DeepSeek 等对话模型要提示） */
    private String providerCapabilityHint(String apiUrl) {
        if (apiUrl == null || apiUrl.isEmpty()) {
            return "跟随配置（百炼系）：支持文生图/文生视频";
        }
        String low = apiUrl.toLowerCase();
        if (low.contains("deepseek.com")) {
            return "⚠️ DeepSeek 为对话模型，不支持文生图/文生视频，请改用百炼/MaaS 或 scnet 等支持媒体生成的提供商";
        }
        if (low.contains("dashscope") || low.contains("maas.aliyuncs.com")) {
            return "✅ 百炼系：支持文生图/文生视频";
        }
        if (low.contains("scnet")) {
            return "✅ 超算互联网：生图 Qwen-Image-2.0 / 生视频 Seedance2.0（OpenAI 兼容，已实测）";
        }
        if (low.contains("openai") || low.contains("api.anthropic") || low.contains("claude")
                || low.contains("gemini") || low.contains("googleapis")) {
            return "⚠️ " + providerUrlsLabel(apiUrl) + " 未验证是否提供图像/视频生成接口，如失败请检查端点";
        }
        return "⚠️ " + providerUrlsLabel(apiUrl) + "：未验证媒体生成能力，生成失败时请检查端点/模型是否支持";
    }

    private String providerUrlsLabel(String apiUrl) {
        try {
            java.net.URL u = new java.net.URL(apiUrl);
            return u.getHost();
        } catch (Throwable t) {
            return apiUrl;
        }
    }

    /** ✨ AI 优化描述：调用当前在线模型 chat/completions，把描述改写成适合文生图/文生视频的提示词 */
    private void optimizePrompt() {
        String p = etPrompt.getText() != null ? etPrompt.getText().toString().trim() : "";
        if (p.isEmpty()) {
            Toast.makeText(this, "请先输入描述再优化", Toast.LENGTH_SHORT).show();
            return;
        }
        tvStatus.setText("⏳ AI 优化描述中…");
        executor.execute(() -> {
            try {
                com.oilquiz.app.ai.model.OnlineModelManager mm =
                        com.oilquiz.app.ai.model.OnlineModelManager.getInstance(this);
                com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig cfg = mm.getActiveModel();
                if (cfg == null || cfg.apiKey == null || cfg.apiKey.isEmpty()) {
                    runOnUiThread(() -> tvStatus.setText("❌ 优化失败：请先在模型管理中配置在线模型 API Key"));
                    return;
                }
                String model = cfg.selectedModel != null && !cfg.selectedModel.isEmpty()
                        ? cfg.selectedModel : cfg.modelName;
                if (model == null || model.isEmpty()) {
                    runOnUiThread(() -> tvStatus.setText("❌ 优化失败：在线模型未设置模型名"));
                    return;
                }
                String base = cfg.apiUrl == null ? "" : cfg.apiUrl.trim().replaceAll("/+$", "");
                if (base.isEmpty()) {
                    runOnUiThread(() -> tvStatus.setText("❌ 优化失败：在线模型未设置接口地址"));
                    return;
                }
                org.json.JSONArray msgs = new org.json.JSONArray();
                msgs.put(new org.json.JSONObject()
                        .put("role", "system")
                        .put("content", "你是专业的 AI 绘画/视频提示词优化师。把用户的中文描述改写成" +
                                "适合文生图/文生视频模型的提示词：补充场景、光线、构图、风格、镜头运动等细节，" +
                                "保留原意，只输出优化后的提示词本身，不要解释、不要引号、不要前缀。"));
                msgs.put(new org.json.JSONObject().put("role", "user").put("content", p));
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("model", model);
                body.put("messages", msgs);
                body.put("temperature", 0.8);
                body.put("max_tokens", 600);
                String resp = httpPostJson(base + "/chat/completions", cfg.apiKey, body);
                org.json.JSONObject j = new org.json.JSONObject(resp);
                String optimized = "";
                if (j.optJSONArray("choices") != null && j.optJSONArray("choices").length() > 0) {
                    org.json.JSONObject msg = j.optJSONArray("choices").optJSONObject(0)
                            .optJSONObject("message");
                    if (msg != null) optimized = msg.optString("content", "");
                }
                if (optimized.isEmpty()) {
                    runOnUiThread(() -> tvStatus.setText("❌ 优化失败：模型返回为空，请检查在线模型配置"));
                    return;
                }
                final String o = optimized.trim();
                runOnUiThread(() -> {
                    etPrompt.setText(o);
                    tvStatus.setText("✅ 描述已由 AI 优化（可继续手动调整）");
                });
            } catch (Throwable t) {
                runOnUiThread(() -> tvStatus.setText("❌ 优化失败：" + t.getMessage()));
            }
        });
    }

    /** 通用 OpenAI 兼容 POST JSON，返回响应文本；失败抛异常（带状态码与响应前 200 字符） */
    private String httpPostJson(String url, String apiKey, org.json.JSONObject body) throws Exception {
        okhttp3.MediaType JSON = okhttp3.MediaType.parse("application/json; charset=utf-8");
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(okhttp3.RequestBody.create(JSON, body.toString()));
        try (okhttp3.Response resp = client.newCall(rb.build()).execute()) {
            String text = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new Exception("HTTP " + resp.code() + (text.length() > 200 ? text.substring(0, 200) : text));
            }
            return text;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
