package com.oilquiz.app.ai.agent;

import android.content.Context;
import com.oilquiz.app.ai.intent.IntelligentIntentRecognizer;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.tool.AIToolsManager;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class AIAgentEngine {
    private static final String TAG = "AIAgentEngine";
    private static final int MAX_TASK_ITERATIONS = 10;
    private static final int MAX_AUTO_DECOMPOSE_TASKS = 5;
    private static final int LONG_CONTENT_THRESHOLD = 500;
    private static final int MAX_CONTEXT_TOKENS = 4096;
    private static final int SAFE_TOKENS_MARGIN = 256;

    public enum AgentState {
        IDLE, THINKING, PLANNING, EXECUTING, VALIDATING, RESPONDING, COMPLETED, ERROR
    }

    public interface OnAgentStepListener {
        void onStepStart(String stepType, String description);
        void onStepComplete(String stepType, String result);
        void onThinking(String thought);
        void onAction(String action);
        void onObservation(String observation);
    }

    private final Context context;
    private final AIToolsManager toolsManager;
    private final AIToolManager toolManager;
    private final AIService aiService;
    private final TaskDecompositionManager taskDecompositionManager;
    private final ServiceRouter serviceRouter;
    private final CreativeWritingEngine creativeWritingEngine;
    private final AgentToolsManager agentToolsManager;
    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService agentExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "AgentEngine-worker");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private String currentSessionId;
    private AgentState currentState = AgentState.IDLE;
    private OnAgentStepListener stepListener;

    public AIAgentEngine(Context context) {
        this.context = context.getApplicationContext();
        this.toolsManager = new AIToolsManager(context);
        this.toolManager = AIToolManager.getInstance(context);
        this.aiService = AIService.getInstance(context);
        this.agentToolsManager = new AgentToolsManager(context);
        this.taskDecompositionManager = new TaskDecompositionManager(this);
        this.creativeWritingEngine = new CreativeWritingEngine(this);
        this.serviceRouter = new ServiceRouter(this);
        this.currentSessionId = createSession();
    }

    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new AgentSession(sessionId));
        return sessionId;
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }

    private String buildSessionHistoryContext(AgentSession session) {
        if (session == null || session.history.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("[对话历史记录]\n");
        int start = Math.max(0, session.history.size() - 10);
        for (int i = start; i < session.history.size(); i++) {
            ChatMessage msg = session.history.get(i);
            String role = msg.role.equals("user") ? "用户" : "助手";
            String content = msg.content.length() > 300 ? msg.content.substring(0, 300) + "..." : msg.content;
            sb.append(role).append(": ").append(content).append("\n");
        }
        sb.append("\n请根据以上对话历史回答用户问题。\n\n");
        return sb.toString();
    }

    public CompletableFuture<AgentResult> processInput(String message) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            try {
                AgentSession session = sessions.get(currentSessionId);
                if (session != null) {
                    session.history.add(new ChatMessage("user", message));
                }

                IntelligentIntentRecognizer.IntentResult intent =
                    IntelligentIntentRecognizer.recognize(message, null);

                AILogger.i(TAG, "Intent: " + intent.primaryIntent + " confidence: " + intent.confidence);

                String historyContext = buildSessionHistoryContext(session);
                String fullMessage = historyContext + message;

                AgentResult result;

                result = tryToolInvocation(intent, fullMessage);

                if (result == null) {
                    if (intent.confidence > 0.6) {
                        result = routeByIntent(intent.primaryIntent, message);
                    } else {
                        result = executeAIDrivenProcess(message);
                    }
                }

                if (session != null && result != null && result.success) {
                    session.history.add(new ChatMessage("assistant", result.content));
                }

                result.processingTimeMs = System.currentTimeMillis() - startTime;
                return result;
            } catch (Exception e) {
                AILogger.e(TAG, "Error processing input: " + e.getMessage(), e);
                return new AgentResult("处理出错: " + e.getMessage(), false);
            }
        }, agentExecutor);
    }
    
    /**
     * 尝试根据意图调用工具
     * @param intent 识别到的意图
     * @param message 用户消息
     * @return 工具执行结果，如果不适合调用工具则返回null
     */
    private AgentResult tryToolInvocation(IntelligentIntentRecognizer.IntentResult intent, String message) {
        // 获取推荐的工具
        String toolName = IntelligentIntentRecognizer.getRecommendedTool(intent.primaryIntent);
        
        if (toolName == null) {
            AILogger.i(TAG, "No recommended tool for intent: " + intent.primaryIntent);
            return null;
        }
        
        // 检查工具是否可用
        if (!toolManager.hasTool(toolName)) {
            AILogger.i(TAG, "Tool not available: " + toolName);
            return null;
        }
        
        // 构建工具调用参数
        Map<String, Object> params = IntelligentIntentRecognizer.buildToolParameters(intent.primaryIntent, message);
        
        // 检查是否有必要的参数
        if (isParamsValid(intent.primaryIntent, params)) {
            AILogger.i(TAG, "Invoking tool: " + toolName + " with params: " + params);
            return executeToolCommand(toolName, params);
        } else {
            AILogger.i(TAG, "Insufficient parameters for tool: " + toolName + ", falling back to AI");
            return null;
        }
    }
    
    /**
     * 检查参数是否有效
     */
    private boolean isParamsValid(IntelligentIntentRecognizer.PrimaryIntent intent, Map<String, Object> params) {
        switch (intent) {
            case OCR:
                return params.containsKey("image_path") && params.get("image_path") != null;
            case IMAGE:
                // 生成图片不需要输入路径
                String action = (String) params.get("action");
                if (action != null && (action.startsWith("image_generate"))) {
                    return true;
                }
                return params.containsKey("image_path") && params.get("image_path") != null;
            case FILE:
                return params.containsKey("file_path") && params.get("file_path") != null;
            case WEB:
                return params.containsKey("source") && params.get("source") != null;
            case CALCULATOR:
                return params.containsKey("expression") && params.get("expression") != null;
            default:
                return true;
        }
    }

    private AgentResult routeByIntent(IntelligentIntentRecognizer.PrimaryIntent intent, String message) {
        switch (intent) {
            case WEATHER:
                return serviceRouter.executeWeatherTask(message);
            case SEARCH:
                return serviceRouter.executeSearchTask(message);
            case DATABASE:
                return serviceRouter.executeDatabaseTask(message);
            case CREATIVE:
                return creativeWritingEngine.executeCreativeTask(message);
            case TRANSLATE:
                return executeTranslationTask(message);
            default:
                return executeAIDrivenProcess(message);
        }
    }

    private AgentResult executeAIDrivenProcess(String message) {
        currentState = AgentState.THINKING;
        notifyStepStart("thinking", "分析任务...");

        boolean isComplex = taskDecompositionManager.isLongContentTask(message);
        if (isComplex) {
            currentState = AgentState.PLANNING;
            notifyStepStart("planning", "分解复杂任务...");
            List<String> subTasks = taskDecompositionManager.decomposeTask(message);
            if (subTasks != null && !subTasks.isEmpty() && subTasks.size() <= MAX_AUTO_DECOMPOSE_TASKS) {
                return executeSubTasks(subTasks, message);
            }
        }

        currentState = AgentState.EXECUTING;
        notifyStepStart("executing", "执行任务...");

        try {
            String response = aiService.generateSync(message, 512);
            currentState = AgentState.COMPLETED;
            notifyStepComplete("executing", response);
            return new AgentResult(response, true);
        } catch (Exception e) {
            currentState = AgentState.ERROR;
            return new AgentResult("执行失败: " + e.getMessage(), false);
        }
    }

    private AgentResult executeSubTasks(List<String> subTasks, String originalMessage) {
        StringBuilder combinedResult = new StringBuilder();
        for (int i = 0; i < subTasks.size() && i < MAX_TASK_ITERATIONS; i++) {
            String subTask = subTasks.get(i);
            notifyStepStart("subtask_" + i, "执行子任务 " + (i + 1) + "/" + subTasks.size());
            try {
                String result = aiService.generateSync(subTask, 300);
                combinedResult.append(result).append("\n\n");
                notifyStepComplete("subtask_" + i, result);
            } catch (Exception e) {
                AILogger.e(TAG, "Sub-task " + i + " failed: " + e.getMessage());
                combinedResult.append("[子任务").append(i + 1).append("失败]\n\n");
            }
        }

        String finalResult = taskDecompositionManager.combineChunks(combinedResult.toString());
        currentState = AgentState.COMPLETED;
        return new AgentResult(finalResult, true);
    }

    private AgentResult executeTranslationTask(String message) {
        try {
            String prompt = "请翻译以下内容（自动检测语言并翻译为中文，如果已经是中文则翻译为英文）:\n\n" + message;
            String result = aiService.generateSync(prompt, 1000);
            return new AgentResult(result, true);
        } catch (Exception e) {
            return new AgentResult("翻译失败: " + e.getMessage(), false);
        }
    }

    public AgentResult executeToolCommand(String toolName, Map<String, Object> params) {
        currentState = AgentState.EXECUTING;
        notifyStepStart("tool_call", "调用工具: " + toolName);

        try {
            AIToolResult toolResult = toolManager.executeTool(toolName, params);
            if (toolResult.isSuccess()) {
                currentState = AgentState.COMPLETED;
                notifyStepComplete("tool_call", String.valueOf(toolResult.getResult()));
                return new AgentResult(String.valueOf(toolResult.getResult()), true);
            } else {
                currentState = AgentState.ERROR;
                return new AgentResult(toolResult.getErrorMessage(), false);
            }
        } catch (Exception e) {
            currentState = AgentState.ERROR;
            return new AgentResult("工具执行失败: " + e.getMessage(), false);
        }
    }

    public String generateSmartFallbackAnswer(String message) {
        if (message.contains("你好") || message.contains("hi") || message.contains("hello")) {
            return "你好！我是AI助手，有什么可以帮你的吗？";
        }
        if (message.contains("谢谢") || message.contains("感谢")) {
            return "不客气！如果还有其他问题，随时问我。";
        }
        return "抱歉，我暂时无法处理这个请求。请尝试换一种方式描述。";
    }

    private void notifyStepStart(String stepType, String description) {
        if (stepListener != null) stepListener.onStepStart(stepType, description);
    }

    private void notifyStepComplete(String stepType, String result) {
        if (stepListener != null) stepListener.onStepComplete(stepType, result);
    }

    public void setStepListener(OnAgentStepListener listener) { this.stepListener = listener; }
    public AgentState getCurrentState() { return currentState; }
    public AIToolsManager getToolsManager() { return toolsManager; }
    public AIToolManager getToolManager() { return toolManager; }
    public AIService getAIService() { return aiService; }
    public AgentToolsManager getAgentToolsManager() { return agentToolsManager; }
    public TaskDecompositionManager getTaskDecompositionManager() { return taskDecompositionManager; }
    public CreativeWritingEngine getCreativeWritingEngine() { return creativeWritingEngine; }
    public ServiceRouter getServiceRouter() { return serviceRouter; }

    public static class ChatMessage {
        public final String role;
        public final String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public static class AgentSession {
        public final String sessionId;
        public final long createdAt;
        public final List<ChatMessage> history;
        public final Map<String, Object> context;

        public AgentSession(String sessionId) {
            this.sessionId = sessionId;
            this.createdAt = System.currentTimeMillis();
            this.history = new ArrayList<>();
            this.context = new HashMap<>();
        }
    }

    public static class AgentResult {
        public String content;
        public boolean success;
        public long processingTimeMs;
        public String usedStrategy;

        public AgentResult(String content, boolean success) {
            this.content = content;
            this.success = success;
        }
    }
}
