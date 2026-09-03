package com.oilquiz.app.ai.performance;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.gpu.GpuCapabilityDetector;
import com.oilquiz.app.ai.gpu.MemoryUsageInfo;
import com.oilquiz.app.ai.jni.LlamaHelper;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ui.activity.ModelSelectorActivity;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.List;

/**
 * PerformanceDashboardFragment - 性能仪表板（真实数据）。
 *
 * 实时采样本地推理运行数据（TPS/GPU/内存/温度/上下文），
 * 由 {@link PerformanceRuleEngine} 自动分析并输出可执行建议：
 * - 提高/降低 GPU 层数并重载模型
 * - 清理对话上下文
 * - 切换模型
 */
public class PerformanceDashboardFragment extends Fragment {

    private static final String TAG = "PerformanceDashboard";

    private TextView tpsValue;
    private TextView latencyValue;
    private TextView gpuUsageValue;
    private TextView memoryUsageValue;
    private TextView temperatureValue;
    private TextView performanceScoreValue;
    private TextView kvHitRateValue;
    private TextView kvCtxUsageValue;
    private WebView tpsChart;
    private RecyclerView optimizationSuggestions;
    private MaterialButton oneClickOptimizeBtn;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Float> tpsHistory = new ArrayList<>();
    private static final int MONITOR_INTERVAL_MS = 3000; // 3 秒采样一次
    private static final float LATENCY_MS_PER_TOKEN = 60f; // 延迟估算：TPS 换算

    private GpuCapabilityDetector gpuDetector;
    private AIService aiService;

    private List<OptimizationSuggestion> suggestionList = new ArrayList<>();
    private OptimizationSuggestionAdapter suggestionAdapter;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_performance_dashboard, container, false);
        initViews(view);
        if (getContext() != null) {
            gpuDetector = new GpuCapabilityDetector(getContext());
            aiService = AIService.getInstance(getContext());
        }
        setupTpsChart();
        startPerformanceMonitoring();
        return view;
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }

    private void initViews(View view) {
        tpsValue = view.findViewById(R.id.tps_value);
        latencyValue = view.findViewById(R.id.latency_value);
        gpuUsageValue = view.findViewById(R.id.gpu_usage_value);
        memoryUsageValue = view.findViewById(R.id.memory_usage_value);
        temperatureValue = view.findViewById(R.id.temperature_value);
        performanceScoreValue = view.findViewById(R.id.performance_score_value);
        kvHitRateValue = view.findViewById(R.id.kv_hit_rate_value);
        kvCtxUsageValue = view.findViewById(R.id.kv_ctx_usage_value);
        tpsChart = view.findViewById(R.id.tps_chart);
        optimizationSuggestions = view.findViewById(R.id.optimization_suggestions);
        oneClickOptimizeBtn = view.findViewById(R.id.one_click_optimize_btn);

        suggestionAdapter = new OptimizationSuggestionAdapter(suggestionList, this::handleOptimizationAction);
        if (optimizationSuggestions != null) {
            optimizationSuggestions.setAdapter(suggestionAdapter);
        }
        if (oneClickOptimizeBtn != null) {
            oneClickOptimizeBtn.setOnClickListener(v -> performOneClickOptimization());
        }
    }

    // ========== 实时数据采样（真实） ==========

    private PerformanceRuleEngine.RuntimeSnapshot sampleRuntimeData() {
        PerformanceRuleEngine.RuntimeSnapshot s = new PerformanceRuleEngine.RuntimeSnapshot();
        try {
            s.tps = LlamaHelper.getInferenceSpeed();
        } catch (Exception ignored) {}
        try {
            s.gpuLayers = LlamaHelper.getGPULayers();
        } catch (Exception ignored) {}
        // 用户手动 GPU 层数（独立 key，-1=自动模式）
        try {
            if (getContext() != null) {
                s.manualGpuLayers = getContext().getSharedPreferences(
                        "model_state_cache", android.content.Context.MODE_PRIVATE)
                        .getInt("gpu_layers_manual", -1);
            }
        } catch (Exception ignored) {}
        try {
            s.gpuWorking = LlamaHelper.isGPUWorking();
        } catch (Exception ignored) {}
        try {
            s.modelLoaded = LlamaHelper.isModelInitialized();
        } catch (Exception ignored) {}
        if (gpuDetector != null) {
            try {
                s.temperature = gpuDetector.getTemperature();
            } catch (Exception ignored) {}
            try {
                MemoryUsageInfo mem = gpuDetector.getMemoryUsageInfo();
                if (mem != null) {
                    s.totalMemoryMB = mem.totalMemoryMB;
                    s.availableMemoryMB = mem.availableMemoryMB;
                }
            } catch (Exception ignored) {}
        }
        if (aiService != null) {
            try {
                s.modelName = aiService.getCurrentModelName();
                if (s.modelName != null && getContext() != null) {
                    java.io.File modelFile = new java.io.File(
                            new java.io.File(getContext().getFilesDir(), "ai_models"), s.modelName);
                    if (modelFile.exists()) {
                        s.modelSizeMB = modelFile.length() / (1024 * 1024);
                    }
                }
            } catch (Exception ignored) {}
            try {
                s.contextUsedPercent = aiService.getContextUsagePercent();
            } catch (Exception ignored) {}
        }
        return s;
    }

    private void startPerformanceMonitoring() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updatePerformanceData();
                refreshSuggestions();
                handler.postDelayed(this, MONITOR_INTERVAL_MS);
            }
        }, MONITOR_INTERVAL_MS);
    }

    private void updatePerformanceData() {
        PerformanceRuleEngine.RuntimeSnapshot s = sampleRuntimeData();

        if (tpsValue != null) {
            tpsValue.setText(String.format("%.1f", s.tps));
        }
        if (latencyValue != null) {
            float latency = s.tps > 0 ? LATENCY_MS_PER_TOKEN / s.tps * 1000f : 0;
            latencyValue.setText(String.format("%.0fms", latency));
        }
        if (gpuUsageValue != null) {
            if (s.gpuWorking && s.gpuLayers > 0) {
                gpuUsageValue.setText(s.gpuLayers + "层");
            } else if (s.modelLoaded) {
                gpuUsageValue.setText("未启用");
            } else {
                gpuUsageValue.setText("--");
            }
        }
        if (memoryUsageValue != null) {
            if (s.totalMemoryMB > 0) {
                float usedPercent = (s.totalMemoryMB - s.availableMemoryMB) * 100f / s.totalMemoryMB;
                memoryUsageValue.setText(String.format("%.0f%%", usedPercent));
            } else {
                memoryUsageValue.setText("--");
            }
        }
        if (temperatureValue != null) {
            temperatureValue.setText(s.temperature > 0 ? s.temperature + "°C" : "--");
        }
        if (performanceScoreValue != null) {
            performanceScoreValue.setText(String.valueOf(PerformanceRuleEngine.calculateScore(s)));
        }

        refreshKvCacheStats();

        // TPS 历史
        tpsHistory.add(s.tps);
        if (tpsHistory.size() > 60) {
            tpsHistory.remove(0);
        }
        updateTpsChart();
    }

    /**
     * 刷新 KV 增量缓存状态：命中率 + 上下文占用（native AgentKvCache 统计）。
     * 数据来源：LlamaHelper.getKvCacheStats()（JNI nativeGetKvCacheStats）。
     * 用于诊断"为什么没吃到 KV 增量缓存"与监控长对话上下文逼近 n_ctx。
     */
    private void refreshKvCacheStats() {
        if (kvHitRateValue == null && kvCtxUsageValue == null) return;
        String json = LlamaHelper.getKvCacheStats();
        if (json == null || json.isEmpty()) {
            if (kvHitRateValue != null) kvHitRateValue.setText("--");
            if (kvCtxUsageValue != null) kvCtxUsageValue.setText("--");
            return;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            double hit = o.optDouble("hit_rate_pct", -1);
            double usage = o.optDouble("ctx_usage_pct", -1);
            String strat = o.optString("strategy", "");
            int plans = o.optInt("plans", 0);
            if (kvHitRateValue != null) {
                if (hit >= 0 && plans > 0) {
                    kvHitRateValue.setText(String.format("%.0f%%", hit));
                } else {
                    kvHitRateValue.setText("--");
                }
            }
            if (kvCtxUsageValue != null) {
                if (usage >= 0) {
                    kvCtxUsageValue.setText(String.format("%.0f%% · %s", usage, strat));
                } else {
                    kvCtxUsageValue.setText("--");
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "refreshKvCacheStats parse failed: " + e.getMessage());
            if (kvHitRateValue != null) kvHitRateValue.setText("--");
            if (kvCtxUsageValue != null) kvCtxUsageValue.setText("--");
        }
    }

    // ========== 规则分析 & 建议 ==========

    private void refreshSuggestions() {
        PerformanceRuleEngine.RuntimeSnapshot s = sampleRuntimeData();
        List<PerformanceRuleEngine.RuleResult> rules = PerformanceRuleEngine.analyze(s);

        List<OptimizationSuggestion> newList = new ArrayList<>();
        for (PerformanceRuleEngine.RuleResult r : rules) {
            // id 承载动作类型（ACTION_*），供 handleOptimizationAction 匹配执行
            newList.add(new OptimizationSuggestion(r.action, r.title, r.description, r.priority, r.estimate));
        }
        suggestionList.clear();
        suggestionList.addAll(newList);
        if (suggestionAdapter != null) {
            suggestionAdapter.notifyDataSetChanged();
        }
    }

    /**
     * 执行建议动作
     */
    private void handleOptimizationAction(OptimizationSuggestion suggestion) {
        String action = suggestion.id;
        if (action == null) return;
        switch (action) {
            case PerformanceRuleEngine.ACTION_INCREASE_GPU:
                applyGpuLayersChange(true);
                break;
            case PerformanceRuleEngine.ACTION_REDUCE_GPU:
                applyGpuLayersChange(false);
                break;
            case PerformanceRuleEngine.ACTION_CLEAR_CONTEXT:
                if (aiService != null) {
                    aiService.clearChatContext();
                    Toast.makeText(getContext(), "对话上下文已清理", Toast.LENGTH_SHORT).show();
                    refreshSuggestions();
                }
                break;
            case PerformanceRuleEngine.ACTION_SWITCH_MODEL:
                if (getContext() != null) {
                    startActivity(new Intent(getContext(), ModelSelectorActivity.class));
                }
                break;
            case PerformanceRuleEngine.ACTION_RELOAD:
                reloadModel();
                break;
            case PerformanceRuleEngine.ACTION_RESTORE_AUTO:
                restoreAutoGpuLayers();
                break;
            default:
                Toast.makeText(getContext(), suggestion.title, Toast.LENGTH_SHORT).show();
                break;
        }
    }

    /**
     * 恢复自动 GPU 层数：删除手动值并重载（自动计算重新接管）
     */
    private void restoreAutoGpuLayers() {
        if (getContext() != null) {
            getContext().getSharedPreferences("model_state_cache", android.content.Context.MODE_PRIVATE)
                    .edit().remove("gpu_layers_manual").apply();
        }
        Toast.makeText(getContext(), "已恢复自动 GPU 层数，正在重载模型...", Toast.LENGTH_SHORT).show();
        reloadModel();
    }

    /**
     * 一键优化：依次执行所有高/中优先级可执行建议
     */
    private void performOneClickOptimization() {
        PerformanceRuleEngine.RuntimeSnapshot s = sampleRuntimeData();
        List<PerformanceRuleEngine.RuleResult> rules = PerformanceRuleEngine.analyze(s);
        int applied = 0;
        for (PerformanceRuleEngine.RuleResult r : rules) {
            if (r.priority == OptimizationSuggestion.Priority.LOW) continue;
            switch (r.action) {
                case PerformanceRuleEngine.ACTION_INCREASE_GPU:
                    applyGpuLayersChange(true);
                    applied++;
                    break;
                case PerformanceRuleEngine.ACTION_REDUCE_GPU:
                    applyGpuLayersChange(false);
                    applied++;
                    break;
                case PerformanceRuleEngine.ACTION_CLEAR_CONTEXT:
                    if (aiService != null) {
                        aiService.clearChatContext();
                        applied++;
                    }
                    break;
                default:
                    break;
            }
        }
        Toast.makeText(getContext(), applied > 0 ? "已应用 " + applied + " 项优化" : "无需优化，一切正常", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::refreshSuggestions, 2000);
    }

    /**
     * 调整 GPU 层数并重载模型（GPU 层数仅 initModel 时生效）
     */
    private void applyGpuLayersChange(boolean increase) {
        int current = 0;
        try { current = LlamaHelper.getGPULayers(); } catch (Exception ignored) {}
        int target = increase ? Math.min(30, current + 10) : Math.max(0, current - 10);
        if (target == current) {
            Toast.makeText(getContext(), "GPU 层数已到" + (increase ? "上限 30" : "下限 0"), Toast.LENGTH_SHORT).show();
            return;
        }
        // 持久化 + 设置 + 重载（独立 key，加载时优先于自动计算）
        if (getContext() != null) {
            getContext().getSharedPreferences("model_state_cache", android.content.Context.MODE_PRIVATE)
                    .edit().putInt("gpu_layers_manual", target).apply();
        }
        LlamaHelper.setGPULayers(target);
        Toast.makeText(getContext(), "GPU 层数 " + current + " → " + target + "，正在重载模型...", Toast.LENGTH_SHORT).show();
        reloadModel();
    }

    /**
     * 重载模型（原子操作：释放旧模型 + 重新加载，让 GPU 层数/参数生效）
     */
    private void reloadModel() {
        if (aiService == null) return;
        aiService.reloadModelAsync(new AIService.InitializeCallback() {
            @Override
            public void onResult(boolean success) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        Toast.makeText(getContext(),
                                success ? "模型重载完成" : "模型重载失败",
                                Toast.LENGTH_SHORT).show();
                        refreshSuggestions();
                    });
                }
            }
        });
    }

    // ========== TPS 图表 ==========

    private void updateTpsChart() {
        String tpsData = "";
        for (int i = 0; i < tpsHistory.size(); i++) {
            tpsData += tpsHistory.get(i);
            if (i < tpsHistory.size() - 1) tpsData += ",";
        }

        String html = """
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    html, body { margin: 0; padding: 0; height: 100%; background: transparent; }
                </style>
            </head>
            <body>
                <canvas id="c"></canvas>
                <script>
                    var data = [__TPS_DATA__];
                    var c = document.getElementById('c');
                    c.width = c.offsetWidth || 300;
                    c.height = c.offsetHeight || 160;
                    var ctx = c.getContext('2d');
                    ctx.clearRect(0, 0, c.width, c.height);
                    if (data.length < 2) {
                        ctx.fillStyle = '#999';
                        ctx.font = '12px sans-serif';
                        ctx.fillText('等待推理数据...', 20, 40);
                    } else {
                        var max = Math.max(1, Math.max.apply(null, data));
                        var w = c.width, h = c.height;
                        ctx.strokeStyle = '#6366f1';
                        ctx.lineWidth = 2;
                        ctx.beginPath();
                        for (var i = 0; i < data.length; i++) {
                            var x = (i / (data.length - 1)) * (w - 10) + 5;
                            var y = h - 10 - (data[i] / max) * (h - 30);
                            if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
                        }
                        ctx.stroke();
                        ctx.fillStyle = '#999';
                        ctx.font = '10px sans-serif';
                        ctx.fillText('TPS 趋势', 5, 12);
                    }
                </script>
            </body>
            </html>
            """.replace("__TPS_DATA__", tpsData);

        if (tpsChart != null) {
            tpsChart.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        }
    }

    private void setupTpsChart() {
        // 初始绘制等待状态
        updateTpsChart();
    }
}
