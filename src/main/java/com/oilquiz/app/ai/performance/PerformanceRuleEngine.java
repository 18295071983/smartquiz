package com.oilquiz.app.ai.performance;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * PerformanceRuleEngine - 本地推理运行数据自动分析规则引擎。
 *
 * 输入 {@link RuntimeSnapshot}（实时采样：速度/GPU/内存/温度/上下文/模型），
 * 按规则集自动判定异常并输出可执行建议（{@link RuleResult}）。
 *
 * 规则覆盖：内存高压、推理过慢、GPU 未启用/异常、设备过热、模型偏大、
 * 上下文将满、GPU 层数可提升等。建议可一键执行（见 action）。
 */
public class PerformanceRuleEngine {

    private static final String TAG = "PerfRuleEngine";

    /** 建议动作类型 */
    public static final String ACTION_NONE = "none";              // 仅提示，无动作
    public static final String ACTION_INCREASE_GPU = "increase_gpu"; // 提高 GPU 层数并重载
    public static final String ACTION_REDUCE_GPU = "reduce_gpu";     // 降低 GPU 层数并重载
    public static final String ACTION_CLEAR_CONTEXT = "clear_context"; // 清理对话上下文
    public static final String ACTION_SWITCH_MODEL = "switch_model";  // 切换到模型选择页
    public static final String ACTION_RELOAD = "reload";          // 重载模型
    public static final String ACTION_RESTORE_AUTO = "restore_auto"; // 恢复自动 GPU 层数并重载

    /** 实时运行数据快照 */
    public static class RuntimeSnapshot {
        public float tps = 0;                 // 推理速度 tokens/s（0=无数据/未推理）
        public int gpuLayers = 0;             // 当前 GPU 层数
        public int manualGpuLayers = -1;      // 用户手动 GPU 层数（-1=未设置/自动模式，0-30=手动值）
        public boolean gpuWorking = false;    // GPU 推理是否工作
        public boolean modelLoaded = false;   // 模型是否已加载
        public int temperature = 0;           // 设备温度 °C
        public long totalMemoryMB = 0;        // 系统总内存
        public long availableMemoryMB = 0;    // 系统可用内存
        public long modelSizeMB = 0;          // 模型文件大小
        public int contextSize = 0;           // 上下文大小
        public float contextUsedPercent = 0;  // 上下文占用百分比
        public String modelName = "";         // 模型名
    }

    /** 规则判定结果 */
    public static class RuleResult {
        public String id;                     // 规则 id（用于去重）
        public String title;
        public String description;
        public OptimizationSuggestion.Priority priority;
        public String action;                 // ACTION_*
        public String estimate;               // 预期收益

        RuleResult(String id, String title, String description,
                   OptimizationSuggestion.Priority priority, String action, String estimate) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.priority = priority;
            this.action = action;
            this.estimate = estimate;
        }
    }

    /**
     * 分析运行快照，返回按优先级排序的建议列表（空列表=一切正常）
     */
    public static List<RuleResult> analyze(RuntimeSnapshot s) {
        List<RuleResult> results = new ArrayList<>();

        // ---------- 内存规则 ----------
        if (s.modelLoaded && s.availableMemoryMB > 0 && s.availableMemoryMB < 800) {
            results.add(new RuleResult("mem_critical",
                    "内存告急（可用 " + s.availableMemoryMB + "MB）",
                    "系统可用内存极低，推理可能卡顿或被系统回收。建议切换到更小的模型释放内存。",
                    OptimizationSuggestion.Priority.HIGH, ACTION_SWITCH_MODEL,
                    "释放约 " + (s.modelSizeMB > 0 ? (s.modelSizeMB / 1024) : 2) + "GB 内存"));
        } else if (s.modelLoaded && s.availableMemoryMB > 0 && s.availableMemoryMB < 1500) {
            results.add(new RuleResult("mem_high",
                    "内存紧张（可用 " + s.availableMemoryMB + "MB）",
                    "当前模型常驻内存接近设备上限，建议换 4B 级模型或关闭后台应用。",
                    OptimizationSuggestion.Priority.HIGH, ACTION_SWITCH_MODEL,
                    "减少 GC 卡顿"));
        }

        // ---------- 速度规则 ----------
        if (s.modelLoaded && s.tps > 0 && s.tps < 3) {
            String tip = s.gpuLayers <= 0
                    ? "当前为纯 CPU 推理，建议启用 GPU 加速（提高层数并重载模型）。"
                    : "推理速度偏慢，建议提高 GPU 层数（不超过 30）并重载模型。";
            results.add(new RuleResult("speed_slow",
                    "推理过慢（" + String.format("%.1f", s.tps) + " tok/s）",
                    tip,
                    OptimizationSuggestion.Priority.HIGH, ACTION_INCREASE_GPU,
                    "速度可提升 2-4 倍"));
        } else if (s.modelLoaded && s.tps > 0 && s.tps < 6) {
            results.add(new RuleResult("speed_mid",
                    "推理偏慢（" + String.format("%.1f", s.tps) + " tok/s）",
                    "当前速度可接受但可优化，提高 GPU 层数或关闭深度思考模式可提速。",
                    OptimizationSuggestion.Priority.MEDIUM, ACTION_INCREASE_GPU,
                    "速度提升约 50%"));
        }

        // ---------- GPU 规则 ----------
        if (s.modelLoaded && !s.gpuWorking && s.gpuLayers > 0) {
            results.add(new RuleResult("gpu_broken",
                    "GPU 工作异常（已设 " + s.gpuLayers + " 层）",
                    "GPU 层数已设置但 GPU 未实际工作，可能初始化失败。建议重载模型或降低层数。",
                    OptimizationSuggestion.Priority.HIGH, ACTION_RELOAD,
                    "恢复 GPU 加速"));
        } else if (s.modelLoaded && s.gpuLayers <= 0) {
            results.add(new RuleResult("gpu_off",
                    "纯 CPU 推理（GPU 层数=0）",
                    "未使用 GPU 加速，推理偏慢且耗电。建议启用 GPU（设 10-30 层并重载）。",
                    OptimizationSuggestion.Priority.MEDIUM, ACTION_INCREASE_GPU,
                    "速度提升 2-4 倍"));
        }

        // ---------- 温度规则 ----------
        if (s.temperature >= 55) {
            results.add(new RuleResult("temp_hot",
                    "设备过热（" + s.temperature + "°C）",
                    "温度过高可能触发降频。建议降低 GPU 层数并暂停推理让设备散热。",
                    OptimizationSuggestion.Priority.HIGH, ACTION_REDUCE_GPU,
                    "降温约 5-10°C"));
        } else if (s.temperature >= 45) {
            results.add(new RuleResult("temp_warm",
                    "温度偏高（" + s.temperature + "°C）",
                    "长时间推理温度上升，建议适当降低 GPU 层数或休息。",
                    OptimizationSuggestion.Priority.MEDIUM, ACTION_REDUCE_GPU,
                    "降低过热降频风险"));
        }

        // ---------- 模型尺寸规则 ----------
        if (s.modelLoaded && s.modelSizeMB >= 4500 && s.totalMemoryMB > 0 && s.totalMemoryMB < 12288) {
            results.add(new RuleResult("model_big",
                    "模型偏大（" + (s.modelSizeMB / 1024) + "GB）",
                    "8B 级模型在 " + (s.totalMemoryMB / 1024) + "GB 设备上内存紧张，建议换 Qwen3-4B（思考链+工具调用双全）。",
                    OptimizationSuggestion.Priority.MEDIUM, ACTION_SWITCH_MODEL,
                    "常驻内存降低约 2.5GB"));
        }

        // ---------- 上下文规则 ----------
        if (s.modelLoaded && s.contextUsedPercent >= 80) {
            results.add(new RuleResult("ctx_high",
                    "上下文即将占满（" + (int) s.contextUsedPercent + "%）",
                    "上下文占用过高，长对话将触发裁剪遗忘。建议清理当前对话上下文。",
                    OptimizationSuggestion.Priority.MEDIUM, ACTION_CLEAR_CONTEXT,
                    "恢复完整上下文"));
        }

        // ---------- 可提升性规则 ----------
        if (s.modelLoaded && s.gpuWorking && s.gpuLayers > 0 && s.gpuLayers < 30 && s.temperature < 45) {
            results.add(new RuleResult("gpu_up",
                    "可提升 GPU 层数（当前 " + s.gpuLayers + "/30）",
                    "GPU 温度正常，提高层数可进一步加速（注意显存压力）。",
                    OptimizationSuggestion.Priority.LOW, ACTION_INCREASE_GPU,
                    "速度提升约 10-20%"));
        }

        // ---------- 手动 GPU 层数评估（手动模式下同样给建议） ----------
        if (s.modelLoaded && s.manualGpuLayers >= 0 && s.gpuLayers == s.manualGpuLayers) {
            // 按模型尺寸估算推荐区间（与自动计算逻辑对齐的简化版）
            int recLow, recHigh;
            if (s.modelSizeMB >= 4500) {
                // 8B 级：内存压力大，上限受可用内存约束
                recLow = 10;
                recHigh = s.availableMemoryMB > 0 && s.availableMemoryMB < 2500 ? 20 : 30;
            } else if (s.modelSizeMB >= 1500) {
                // 3-7B 级
                recLow = 15;
                recHigh = 30;
            } else {
                // 小模型：GPU 带宽瓶颈，不需要太多层
                recLow = 10;
                recHigh = 25;
            }
            int manual = s.manualGpuLayers;

            if (manual > recHigh) {
                String tip = "你手动设了 " + manual + " 层，但按当前模型/内存推荐 " + recHigh + " 层以内"
                        + (s.temperature >= 45 ? "（且设备温度 " + s.temperature + "°C 偏高）" : "")
                        + "，过高可能引起内存压力或过热降频。";
                results.add(new RuleResult("manual_high",
                        "手动 GPU 层数可能过高（" + manual + "/推荐≤" + recHigh + "）",
                        tip,
                        s.temperature >= 45 ? OptimizationSuggestion.Priority.HIGH : OptimizationSuggestion.Priority.MEDIUM,
                        ACTION_REDUCE_GPU,
                        "降低内存/温度压力"));
            } else if (manual < recLow && s.gpuWorking) {
                String tip = "你手动设了 " + manual + " 层，低于推荐 " + recLow + "-" + recHigh + " 层，"
                        + "当前 GPU 空闲，提高层数可显著加速（或点「恢复自动」用推荐值）。";
                results.add(new RuleResult("manual_low",
                        "手动 GPU 层数偏低（" + manual + "/推荐≥" + recLow + "）",
                        tip,
                        OptimizationSuggestion.Priority.MEDIUM,
                        ACTION_RESTORE_AUTO,
                        "速度提升 20-50%"));
            } else if (manual == 0 && !s.gpuWorking) {
                // 手动设 0 且 GPU 未工作：已是纯 CPU，gpu_off 规则会处理
            } else if (s.temperature >= 50 && manual > 15) {
                results.add(new RuleResult("manual_hot",
                        "手动层数偏高且设备过热（" + s.temperature + "°C）",
                        "建议降低 GPU 层数或暂停推理，避免过热降频。",
                        OptimizationSuggestion.Priority.HIGH, ACTION_REDUCE_GPU,
                        "降温约 5-10°C"));
            } else {
                results.add(new RuleResult("manual_ok",
                        "手动 GPU 层数合理（" + manual + " 层）",
                        "当前手动设置符合设备/模型能力，无需调整。",
                        OptimizationSuggestion.Priority.LOW, ACTION_NONE,
                        "当前配置正常"));
            }
        }

        // ---------- 模型未加载 ----------
        if (!s.modelLoaded) {
            results.add(new RuleResult("no_model",
                    "模型未加载",
                    "当前没有可用的本地模型，先在模型列表选择并加载模型后，本面板才有实际数据。",
                    OptimizationSuggestion.Priority.LOW, ACTION_SWITCH_MODEL,
                    "启用本地推理"));
        }

        // 按优先级排序：HIGH > MEDIUM > LOW
        results.sort((a, b) -> {
            int pa = priorityRank(a.priority), pb = priorityRank(b.priority);
            return Integer.compare(pb, pa);
        });
        Log.i(TAG, "analyze: " + results.size() + " suggestion(s)");
        return results;
    }

    private static int priorityRank(OptimizationSuggestion.Priority p) {
        switch (p) {
            case HIGH: return 3;
            case MEDIUM: return 2;
            default: return 1;
        }
    }

    /**
     * 计算综合性能评分（0-100），基于真实指标：
     * TPS 30 + GPU 20 + 内存 25 + 温度 25
     */
    public static int calculateScore(RuntimeSnapshot s) {
        int score = 0;
        // TPS：0→0 分，20+→30 分
        float tpsScore = Math.min(30, s.tps / 20f * 30);
        score += (int) tpsScore;
        // GPU：启用+层数 20 分
        if (s.gpuWorking && s.gpuLayers > 0) {
            score += Math.min(20, 10 + s.gpuLayers);
        }
        // 内存：可用比例 25 分
        if (s.totalMemoryMB > 0) {
            float availRatio = (float) s.availableMemoryMB / s.totalMemoryMB;
            score += (int) (availRatio * 25);
        }
        // 温度：<=40 满分，每 +1°C 扣 2 分
        score += Math.max(0, 25 - Math.max(0, s.temperature - 40) * 2);
        return Math.min(100, Math.max(0, score));
    }
}
