package com.oilquiz.app.ai.importing;

import com.oilquiz.app.ai.agent.AgentCallback;
import com.oilquiz.app.ai.agent.online.OnlineToolResult;
import com.oilquiz.app.ai.chat.ui.GuideStepFlowView;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 智能体导入状态机（独立于智能体专用 UI 的导入业务状态机）。
 *
 * <p>职责：把 AgentSession（LLM 工具决策）的异步回调翻译成<b>导入业务状态</b>，
 * 再通过 {@link Ui} 接口驱动导入页自有 UI（GuideStepFlowView 四步骤 + 监控区），
 * 不渲染思考过程 / 工具调用流水（不使用智能体专用视图）。</p>
 *
 * <p>状态：{@link Phase}（发现→预处理→启动→导入→完成/失败/取消），
 * 全部迁移经 {@link #VALID_TRANSITIONS} 合法转移表校验，非法迁移直接拒绝并告警，
 * 与 AIChatViewModel.AIState 的转移表约束一致。</p>
 *
 * <p>扩展方式：{@link Ui} 为全部 UI 输出点（阶段消息 / 步骤状态 / 监控数字 / 完成 / 错误）。
 * 需要换皮肤、换宿主、换呈现载体时，实现或替换 Ui 即可，本状态机零改动；
 * 需要接入新的工具阶段时，扩展 {@link #stageForTool(String)} 的映射表。</p>
 */
public class AgentImportStateMachine implements AgentCallback {

    /** 导入业务阶段 */
    public enum Phase {
        IDLE,           // 空闲
        DISCOVERING,    // 发现文件（import_list_files）
        PREPROCESSING,  // 预处理评估（file_reader/excel_tool/python 等）
        STARTING,       // 启动导入（import_start）
        IMPORTING,      // 轮询入库（import_status）
        COMPLETED,      // 完成
        FAILED,         // 失败
        CANCELLED;      // 已取消

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    /** 合法迁移表：仅允许以下状态流转 */
    private static final Set<String> VALID_TRANSITIONS = new HashSet<>();
    static {
        VALID_TRANSITIONS.add("IDLE->DISCOVERING");
        VALID_TRANSITIONS.add("IDLE->PREPROCESSING");
        VALID_TRANSITIONS.add("IDLE->STARTING");
        VALID_TRANSITIONS.add("IDLE->FAILED");
        VALID_TRANSITIONS.add("DISCOVERING->PREPROCESSING");
        VALID_TRANSITIONS.add("DISCOVERING->STARTING");
        VALID_TRANSITIONS.add("DISCOVERING->FAILED");
        VALID_TRANSITIONS.add("PREPROCESSING->STARTING");
        VALID_TRANSITIONS.add("PREPROCESSING->FAILED");
        VALID_TRANSITIONS.add("STARTING->IMPORTING");
        VALID_TRANSITIONS.add("STARTING->FAILED");
        VALID_TRANSITIONS.add("IMPORTING->IMPORTING");   // 轮询保持
        VALID_TRANSITIONS.add("IMPORTING->COMPLETED");
        VALID_TRANSITIONS.add("IMPORTING->FAILED");
        VALID_TRANSITIONS.add("IMPORTING->CANCELLED");
        VALID_TRANSITIONS.add("IDLE->CANCELLED");
        VALID_TRANSITIONS.add("DISCOVERING->CANCELLED");
        VALID_TRANSITIONS.add("PREPROCESSING->CANCELLED");
        VALID_TRANSITIONS.add("STARTING->CANCELLED");
        VALID_TRANSITIONS.add("FAILED->IDLE");           // 重试回空闲
        VALID_TRANSITIONS.add("CANCELLED->IDLE");
    }

    /** UI 输出接口：宿主实现即可换载体（Activity / 全局组件 / 测试桩），状态机零改动 */
    public interface Ui {
        /** 阶段切换 + 状态消息（"正在发现文件…" / LLM 汇总文本） */
        void onPhase(Phase phase, String message);
        /** 步骤状态（0=检测 1=映射 2=解析 3=入库） */
        void onStep(int stepIndex, GuideStepFlowView.StepState state);
        /** 监控数字（耗时秒 / 阶段 / 速度 tokens/s / token 数 / 进度 current/total，<=0 表示未知） */
        void onMonitor(long elapsedSec, String stage, float speed, int tokens, long current, long total);
        /** 完成（fullText 为智能体最终汇报文本；statistics 为解析出的 新增/重复/失败/总数，-1=未知） */
        void onComplete(String fullText, int imported, int duplicated, int failed, int totalRows);
        /** 失败 */
        void onError(String error);
    }

    private final Ui ui;
    private volatile Phase phase = Phase.IDLE;
    private final long startAt;
    private long lastActivityAt;
    private volatile boolean thinkingActive = false;
    private int stepIndex = -1;   // 当前步骤（0~3）
    private final Set<String> touchedTools = new HashSet<>();
    // 统计解析结果（从智能体汇总文本中尽力提取，-1=未知）
    private int statImported = -1, statDuplicated = -1, statFailed = -1, statTotal = -1;

    public AgentImportStateMachine(Ui ui) {
        if (ui == null) throw new IllegalArgumentException("Ui 不能为 null");
        this.ui = ui;
        this.startAt = System.currentTimeMillis();
        this.lastActivityAt = startAt;
    }

    public Phase getPhase() { return phase; }
    public long getElapsedSec() { return (System.currentTimeMillis() - startAt) / 1000; }
    public long getIdleSec() { return (System.currentTimeMillis() - lastActivityAt) / 1000; }

    /** 重置到空闲（可复用实例重试） */
    public synchronized void reset() {
        transition(Phase.IDLE, "已重置");
        stepIndex = -1;
        touchedTools.clear();
        thinkingActive = false;
        statImported = statDuplicated = statFailed = statTotal = -1;
    }

    // ==================== 状态机核心 ====================

    private synchronized void transition(Phase next, String message) {
        if (phase == next && next != Phase.IMPORTING) return;
        String key = phase.name() + "->" + next.name();
        if (!VALID_TRANSITIONS.contains(key)) {
            android.util.Log.w("AgentImportStateMachine",
                    "非法状态迁移被拒绝: " + key);
            return;
        }
        Phase prev = phase;
        phase = next;
        lastActivityAt = System.currentTimeMillis();
        // 步骤状态联动（GuideStepFlowView 四步骤：检测/映射/解析/入库）
        if (next == Phase.DISCOVERING) setStep(0, GuideStepFlowView.StepState.RUNNING);
        else if (next == Phase.PREPROCESSING) { setStep(0, GuideStepFlowView.StepState.DONE); setStep(1, GuideStepFlowView.StepState.RUNNING); }
        else if (next == Phase.STARTING) { setStep(0, GuideStepFlowView.StepState.DONE); setStep(1, GuideStepFlowView.StepState.DONE); setStep(2, GuideStepFlowView.StepState.RUNNING); }
        else if (next == Phase.IMPORTING) { setStep(0, GuideStepFlowView.StepState.DONE); setStep(1, GuideStepFlowView.StepState.DONE); setStep(2, GuideStepFlowView.StepState.DONE); setStep(3, GuideStepFlowView.StepState.RUNNING); }
        else if (next == Phase.COMPLETED) { for (int i = 0; i < 4; i++) setStep(i, GuideStepFlowView.StepState.DONE); }
        else if (next == Phase.FAILED || next == Phase.CANCELLED) {
            for (int i = 0; i < 4; i++) {
                GuideStepFlowView.StepState st = i < stepIndex
                        ? GuideStepFlowView.StepState.DONE
                        : i == stepIndex ? GuideStepFlowView.StepState.ERROR
                        : GuideStepFlowView.StepState.PENDING;
                setStep(i, st);
            }
        }
        ui.onPhase(next, message);
        if (next.isTerminal()) {
            ui.onMonitor(getElapsedSec(), message, 0, 0, 0, 0);
        }
    }

    private void setStep(int index, GuideStepFlowView.StepState state) {
        stepIndex = Math.max(stepIndex, index);
        try {
            ui.onStep(index, state);
        } catch (Throwable t) {
            android.util.Log.w("AgentImportStateMachine", "UI 步骤更新失败: " + t.getMessage());
        }
    }

    /** 工具名 → 导入阶段映射（新工具接入时在此扩展） */
    private Phase stageForTool(String toolName) {
        if (toolName == null) return null;
        String t = toolName.toLowerCase(Locale.ROOT);
        if (t.contains("import_list") || t.contains("list_files")) return Phase.DISCOVERING;
        if (t.contains("import_start")) return Phase.STARTING;
        if (t.contains("import_status") || t.contains("import_cancel")) return Phase.IMPORTING;
        // 预处理工具族
        if (t.contains("file_reader") || t.contains("file_analyzer")
                || t.contains("excel_tool") || t.contains("file_generator")
                || t.contains("python_")) return Phase.PREPROCESSING;
        return null;
    }

    // ==================== AgentCallback 翻译层 ====================

    @Override public void onToken(String token) { /* 正文不做流式展示（不使用智能体专用 UI） */ }

    @Override public void onThinkingToken(String token) {
        if (!thinkingActive) {
            thinkingActive = true;
            transition(Phase.PREPROCESSING, "AI 正在分析题库结构，确定导入参数…");
        }
    }

    @Override public void onThinkingEnd() { thinkingActive = false; }

    @Override public void onThinkingStage(String stage) {
        if (stage != null && !stage.isEmpty()) {
            ui.onMonitor(getElapsedSec(), stage, 0, 0, 0, 0);
        }
    }

    @Override public void onToolCallStart(String toolCallId, String toolName, String args) {
        if (phase.isTerminal()) return;
        Phase p = stageForTool(toolName);
        if (p != null) {
            touchedTools.add(toolName);
            transition(p, toolName + " 执行中");
            ui.onMonitor(getElapsedSec(), toolName, 0, 0, 0, 0);
        }
    }

    @Override public void onToolCallComplete(String toolCallId, String toolName, OnlineToolResult result) {
        if (phase.isTerminal()) return;
        String note = toolName + " 完成";
        if (result != null) {
            String err = result.error;
            if (err != null && !err.isEmpty()) {
                note = toolName + " 失败: " + err;
                transition(Phase.FAILED, note);
                return;
            }
        }
        ui.onMonitor(getElapsedSec(), note, 0, 0, 0, 0);
    }

    @Override public void onStepUpdate(String step, String detail) {
        if (step != null && !step.isEmpty()) {
            ui.onMonitor(getElapsedSec(), step, 0, 0, 0, 0);
        }
    }

    @Override public void onComplete(String fullText) {
        parseStatistics(fullText);
        transition(Phase.COMPLETED, fullText != null ? fullText : "导入完成");
        ui.onComplete(fullText != null ? fullText : "导入完成",
                statImported, statDuplicated, statFailed, statTotal);
    }

    @Override public void onError(String error) {
        transition(Phase.FAILED, error != null ? error : "导入失败");
        ui.onError(error != null ? error : "导入失败");
    }

    /** 取消（由宿主按钮触发，转发给状态机与 AgentSession） */
    public synchronized void cancel() {
        transition(Phase.CANCELLED, "已取消");
    }

    // ==================== 汇总文本统计解析（尽力而为） ====================

    private void parseStatistics(String text) {
        if (text == null) return;
        String t = text.toLowerCase(Locale.ROOT);
        statImported = extractNumber(t, new String[]{"新增", "导入", "成功", "imported", "新增 "});
        statDuplicated = extractNumber(t, new String[]{"重复", "duplicated", "重复 "});
        statFailed = extractNumber(t, new String[]{"失败", "failed", "失败 "});
        statTotal = extractNumber(t, new String[]{"共", "total", "总计", "全部", "totalRows"});
    }

    private int extractNumber(String lower, String[] keys) {
        for (String k : keys) {
            int idx = lower.indexOf(k.toLowerCase(Locale.ROOT));
            if (idx < 0) continue;
            int start = idx + k.length();
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if (Character.isDigit(c)) sb.append(c);
                else if (sb.length() > 0 && (c == ' ' || c == '\t')) continue;
                else if (sb.length() > 0) break;
                else if (c == '，' || c == ',' || c == '：' || c == ':' || c == '、') continue;
                else if (c == '0' && sb.length() == 0) { sb.append(c); }
                else break;
            }
            if (sb.length() > 0) {
                try { return Integer.parseInt(sb.toString()); } catch (Exception ignored) {}
            }
        }
        return -1;
    }
}
