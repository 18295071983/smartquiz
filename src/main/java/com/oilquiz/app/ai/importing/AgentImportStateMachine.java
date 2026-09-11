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
        /** 步骤状态（0=检测 1=映射 2=解析 3=入库；detail 为状态机阶段说明文字） */
        void onStep(int stepIndex, GuideStepFlowView.StepState state, String detail);
        /** agent 执行过程：向指定步骤追加/更新一个工具执行项（text 去重，状态覆盖） */
        void onStepProcess(int stepIndex, GuideStepFlowView.ProcessItem item);
        /** 监控数字（耗时秒 / 阶段 / 速度 tokens/s / token 数 / 进度 current/total，<=0 表示未知） */
        void onMonitor(long elapsedSec, String stage, float speed, long tokens, long current, long total);
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
    // 真实监控数据：token 累计 / 进度（来自 import_status 结果）/ 最近阶段消息
    private volatile long bodyTokens = 0, thinkTokens = 0;
    private volatile long progressCurrent = 0, progressTotal = 0;
    private volatile String lastStageMessage = "等待";
    // agent 执行过程：每步的工具执行项（text → 状态），用于步骤区子步骤展示
    private final java.util.Map<String, GuideStepFlowView.StepState>[] stepProcesses =
            new java.util.HashMap[4];
    {
        for (int i = 0; i < stepProcesses.length; i++) stepProcesses[i] = new java.util.HashMap<>();
    }
    // import_status 轮询去重：同一轮询 key 更新进度文字
    private static final String POLL_KEY = "import_status 轮询";

    public AgentImportStateMachine(Ui ui) {
        if (ui == null) throw new IllegalArgumentException("Ui 不能为 null");
        this.ui = ui;
        this.startAt = System.currentTimeMillis();
        this.lastActivityAt = startAt;
    }

    public Phase getPhase() { return phase; }
    public long getElapsedSec() { return (System.currentTimeMillis() - startAt) / 1000; }
    public long getIdleSec() { return (System.currentTimeMillis() - lastActivityAt) / 1000; }

    // ==================== 监控数据查询（UI 每秒 tick 读取） ====================

    /** 累计 Token（思考 + 正文） */
    public long getTotalTokens() { return bodyTokens + thinkTokens; }
    /** 推理速度 tokens/s（有耗时且有 token 时） */
    public float getSpeed() {
        long sec = getElapsedSec();
        long tokens = getTotalTokens();
        return sec > 0 && tokens > 0 ? (float) tokens / sec : 0f;
    }
    public long getProgressCurrent() { return progressCurrent; }
    public long getProgressTotal() { return progressTotal; }
    /** 最近阶段消息（工具名/阶段说明/导入进度） */
    public String getStageMessage() { return lastStageMessage; }

    /** 重置到空闲（可复用实例重试） */
    public synchronized void reset() {
        transition(Phase.IDLE, "已重置");
        stepIndex = -1;
        touchedTools.clear();
        thinkingActive = false;
        statImported = statDuplicated = statFailed = statTotal = -1;
        bodyTokens = 0; thinkTokens = 0;
        progressCurrent = 0; progressTotal = 0;
        lastStageMessage = "等待";
        for (int i = 0; i < stepProcesses.length; i++) stepProcesses[i].clear();
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
        // 步骤状态联动（GuideStepFlowView 四步骤：检测/映射/解析/入库），detail 展示状态机阶段说明
        if (next == Phase.DISCOVERING) setStep(0, GuideStepFlowView.StepState.RUNNING, "发现文件…");
        else if (next == Phase.PREPROCESSING) {
            setStep(0, GuideStepFlowView.StepState.DONE, "发现完成");
            setStep(1, GuideStepFlowView.StepState.RUNNING, "分析结构/定参数…");
        }
        else if (next == Phase.STARTING) {
            setStep(0, GuideStepFlowView.StepState.DONE, "发现完成");
            setStep(1, GuideStepFlowView.StepState.DONE, "预处理完成");
            setStep(2, GuideStepFlowView.StepState.RUNNING, "启动导入…");
        }
        else if (next == Phase.IMPORTING) {
            setStep(0, GuideStepFlowView.StepState.DONE, "发现完成");
            setStep(1, GuideStepFlowView.StepState.DONE, "预处理完成");
            setStep(2, GuideStepFlowView.StepState.DONE, "已启动");
            setStep(3, GuideStepFlowView.StepState.RUNNING, "入库中" + (progressTotal > 0 ? " " + progressCurrent + "/" + progressTotal : "…"));
        }
        else if (next == Phase.COMPLETED) {
            for (int i = 0; i < 4; i++) {
                setStep(i, GuideStepFlowView.StepState.DONE, i == 3 ? "入库完成" : "完成");
            }
        }
        else if (next == Phase.FAILED || next == Phase.CANCELLED) {
            for (int i = 0; i < 4; i++) {
                GuideStepFlowView.StepState st = i < stepIndex
                        ? GuideStepFlowView.StepState.DONE
                        : i == stepIndex ? GuideStepFlowView.StepState.ERROR
                        : GuideStepFlowView.StepState.PENDING;
                setStep(i, st, i == stepIndex ? message : null);
            }
        }
        ui.onPhase(next, message);
        if (message != null) lastStageMessage = message;
        if (next.isTerminal()) {
            ui.onMonitor(getElapsedSec(), message, getSpeed(), getTotalTokens(),
                    progressCurrent, progressTotal);
        }
    }

    private void setStep(int index, GuideStepFlowView.StepState state, String detail) {
        stepIndex = Math.max(stepIndex, index);
        try {
            ui.onStep(index, state, detail);
        } catch (Throwable t) {
            android.util.Log.w("AgentImportStateMachine", "UI 步骤更新失败: " + t.getMessage());
        }
    }

    /** 向步骤区追加/更新 agent 执行过程项（工具调用历史） */
    private void addProcess(int stepIndex, String key, GuideStepFlowView.StepState state, String display) {
        if (stepIndex < 0 || stepIndex >= stepProcesses.length) return;
        String text = display != null ? display : key;
        stepProcesses[stepIndex].put(key, state);
        try {
            ui.onStepProcess(stepIndex, new GuideStepFlowView.ProcessItem(text, state));
        } catch (Throwable t) {
            android.util.Log.w("AgentImportStateMachine", "UI 过程项更新失败: " + t.getMessage());
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

    /** 阶段 → 步骤索引（0=检测 1=映射 2=解析 3=入库） */
    private int stepForPhase(Phase p) {
        switch (p) {
            case DISCOVERING: return 0;
            case PREPROCESSING: return 1;
            case STARTING: return 2;
            case IMPORTING: return 3;
            default: return Math.max(0, stepIndex);
        }
    }

    // ==================== AgentCallback 翻译层 ====================

    @Override public void onToken(String token) {
        if (token != null && !token.isEmpty()) bodyTokens += token.length();
    }

    @Override public void onThinkingToken(String token) {
        if (token != null && !token.isEmpty()) thinkTokens += token.length();
        if (!thinkingActive) {
            thinkingActive = true;
            transition(Phase.PREPROCESSING, "AI 正在分析题库结构，确定导入参数…");
        }
    }

    @Override public void onThinkingEnd() { thinkingActive = false; }

    @Override public void onThinkingStage(String stage) {
        if (stage != null && !stage.isEmpty()) {
            lastStageMessage = stage;
            ui.onMonitor(getElapsedSec(), stage, getSpeed(), getTotalTokens(),
                    progressCurrent, progressTotal);
        }
    }

    @Override public void onToolCallStart(String toolCallId, String toolName, String args) {
        if (phase.isTerminal()) return;
        Phase p = stageForTool(toolName);
        if (p != null) {
            touchedTools.add(toolName);
            transition(p, toolName + " 执行中");
            lastStageMessage = toolName + " 执行中";
            // agent 执行过程：当前工具挂到当前步骤的子步骤（RUNNING）
            int si = stepForPhase(p);
            addProcess(si, toolName, GuideStepFlowView.StepState.RUNNING, toolName + " 执行中");
            ui.onMonitor(getElapsedSec(), toolName + " 执行中", getSpeed(), getTotalTokens(),
                    progressCurrent, progressTotal);
        }
    }

    @Override public void onToolCallComplete(String toolCallId, String toolName, OnlineToolResult result) {
        if (phase.isTerminal()) return;
        String note = toolName + " 完成";
        int si = stepForPhase(phase);
        if (result != null) {
            String err = result.error;
            if (err != null && !err.isEmpty()) {
                note = toolName + " 失败: " + err;
                addProcess(si, toolName, GuideStepFlowView.StepState.ERROR, note);
                transition(Phase.FAILED, note);
                return;
            }
            // import_status 轮询结果：解析进度（current/total）、阶段消息与最终统计（imported/duplicated/failed/totalRows），接入监控区与结果卡
            if (toolName != null && toolName.toLowerCase(Locale.ROOT).contains("import_status")
                    && result.result != null) {
                try {
                    String json = result.result;
                    // 兼容可能带 ```json 包裹
                    int b = json.indexOf('{');
                    int e = json.lastIndexOf('}');
                    if (b >= 0 && e > b) {
                        org.json.JSONObject o = new org.json.JSONObject(json.substring(b, e + 1));
                        if (o.has("current")) progressCurrent = o.optLong("current");
                        if (o.has("total")) progressTotal = o.optLong("total");
                        // 真实统计（QuestionImportTaskManager.getStatus 返回字段）：
                        // 优先于 LLM 汇总文本解析，结果卡显示可靠数字
                        if (o.has("imported")) statImported = o.optInt("imported");
                        if (o.has("duplicated")) statDuplicated = o.optInt("duplicated");
                        if (o.has("failed")) statFailed = o.optInt("failed");
                        if (o.has("totalRows")) statTotal = o.optInt("totalRows");
                        // 进度文字挂到入库步骤 detail + 轮询过程项（去重，更新进度）
                        if (phase == Phase.IMPORTING && stepIndex >= 0) {
                            String pollText = progressTotal > 0
                                    ? "import_status 轮询中 " + progressCurrent + "/" + progressTotal
                                    : "import_status 轮询中…";
                            setStep(3, GuideStepFlowView.StepState.RUNNING, pollText);
                            addProcess(3, POLL_KEY, GuideStepFlowView.StepState.RUNNING, pollText);
                        }
                        String msg = o.optString("message", "");
                        if (!msg.isEmpty()) note = msg;
                        if (o.has("stage") && !o.optString("stage").isEmpty()) {
                            note = o.optString("stage") + " · " + (msg.isEmpty() ? note : msg);
                        }
                    }
                } catch (Throwable ignored) {
                    // 结果非 JSON（工具异常文本）则保留原始 note
                }
            }
        }
        // 普通工具完成 → 过程项更新为 DONE（import_status 已单独处理轮询项）
        if (!(toolName != null && toolName.toLowerCase(Locale.ROOT).contains("import_status"))) {
            addProcess(si, toolName, GuideStepFlowView.StepState.DONE, toolName + " 完成");
        }
        lastStageMessage = note;
        ui.onMonitor(getElapsedSec(), note, getSpeed(), getTotalTokens(),
                progressCurrent, progressTotal);
    }

    @Override public void onStepUpdate(String step, String detail) {
        if (step != null && !step.isEmpty()) {
            lastStageMessage = step;
            ui.onMonitor(getElapsedSec(), step, getSpeed(), getTotalTokens(),
                    progressCurrent, progressTotal);
        }
    }

    @Override public void onComplete(String fullText) {
        parseStatistics(fullText);
        String summary = fullText != null ? fullText : "导入完成";
        lastStageMessage = summary;
        transition(Phase.COMPLETED, summary);
        ui.onComplete(summary, statImported, statDuplicated, statFailed, statTotal);
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
