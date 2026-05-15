package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.service.AIService;
import com.oilquiz.app.ai.service.AgentService;
import com.oilquiz.app.ai.chat.AgentChatHandler;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.util.ChatHistoryManager;
import com.oilquiz.app.model.AgentTask;
import com.oilquiz.app.ui.base.BaseActivity;

import java.util.ArrayList;
import java.util.List;

public class AgentChatActivity extends BaseActivity implements AgentChatHandler.AgentChatCallback {

    private static final String TAG = "AgentChatActivity";

    private MaterialButton btnBack;
    private MaterialButton btnModelSelect;
    private MaterialButton btnClearHistory;
    private MaterialButton btnTaskList;
    private TextView modelNameText;
    private View thinkingIndicator;
    private RecyclerView messageList;
    private EditText inputMessage;
    private MaterialButton btnSend;
    private MaterialButton btnAttach;
    private MaterialButton btnVoice;
    private Chip chipCreateTask;
    private Chip chipLearnPlan;
    private Chip chipProblemSolve;
    private Chip chipOptimizeTask;

    private AIService aiService;
    private AgentService agentService;
    private AgentChatHandler agentChatHandler;
    private List<ChatMessage> chatHistory;
    private List<AgentTask> taskList;
    private ChatHistoryManager chatHistoryManager;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_agent_chat;
    }

    @Override
    protected void initView() {
        btnBack = findViewById(R.id.btn_back);
        btnModelSelect = findViewById(R.id.btn_model_select);
        btnClearHistory = findViewById(R.id.btn_clear_history);
        btnTaskList = findViewById(R.id.btn_task_list);
        modelNameText = findViewById(R.id.model_name);
        thinkingIndicator = findViewById(R.id.thinking_indicator);
        messageList = findViewById(R.id.message_list);
        inputMessage = findViewById(R.id.input_message);
        btnSend = findViewById(R.id.btn_send);
        btnAttach = findViewById(R.id.btn_attach);
        btnVoice = findViewById(R.id.btn_voice);
        chipCreateTask = findViewById(R.id.chip_create_task);
        chipLearnPlan = findViewById(R.id.chip_learn_plan);
        chipProblemSolve = findViewById(R.id.chip_problem_solve);
        chipOptimizeTask = findViewById(R.id.chip_optimize_task);

        messageList.setLayoutManager(new LinearLayoutManager(this));
        messageList.setAdapter(new ChatMessageAdapter());
    }

    @Override
    protected void initData() {
        aiService = AIService.getInstance(this);
        agentService = new AgentService(this);
        agentChatHandler = new AgentChatHandler(this, aiService, agentService, this);

        chatHistoryManager = new ChatHistoryManager(this);
        chatHistory = chatHistoryManager.loadAgentChatHistory();
        taskList = chatHistoryManager.loadAgentTaskList();

        updateModelNameDisplay();

        List<ChatMessage> historyCopy = new ArrayList<>(chatHistory);
        if (historyCopy.isEmpty()) {
            addSystemMessage("欢迎使用AI Agent功能！我可以帮助您完成各种任务，如制定学习计划、搜索信息、查询天气等。");
        } else {
            for (ChatMessage message : historyCopy) {
                if (message.isUserMessage()) {
                    addUserMessage(message.content);
                } else if (message.isAIMessage()) {
                    addAIMessage(message.content);
                } else if (message.isSystemMessage()) {
                    addSystemMessage(message.content);
                }
            }
            addSystemMessage("欢迎回来！继续我们的任务吧。");
        }

        if (!taskList.isEmpty()) {
            addSystemMessage("您有 " + taskList.size() + " 个待处理任务。");
        }
    }

    private void updateModelNameDisplay() {
        try {
            if (aiService != null) {
                String modelName = aiService.getCurrentModelName();
                if (modelName != null && !modelName.isEmpty()) {
                    modelNameText.setText(modelName);
                } else {
                    modelNameText.setText("未选择模型");
                }
            } else {
                modelNameText.setText("AI服务未初始化");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating model name display", e);
            modelNameText.setText("未知模型");
        }
    }

    @Override
    protected void initListener() {
        setupClickListeners();
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());
        btnModelSelect.setOnClickListener(v ->
            startActivity(new Intent(AgentChatActivity.this, ModelSelectorActivity.class)));
        btnSend.setOnClickListener(v -> sendMessage());
        btnTaskList.setOnClickListener(v -> showTaskList());
        btnClearHistory.setOnClickListener(v -> clearHistory());
        btnAttach.setOnClickListener(v -> showToast("附件功能开发中"));
        btnVoice.setOnClickListener(v -> showToast("语音输入功能开发中"));

        if (chipCreateTask != null) chipCreateTask.setOnClickListener(v -> handleQuickAction("创建任务"));
        if (chipLearnPlan != null) chipLearnPlan.setOnClickListener(v -> handleQuickAction("学习计划"));
        if (chipProblemSolve != null) chipProblemSolve.setOnClickListener(v -> handleQuickAction("问题解决"));
        if (chipOptimizeTask != null) chipOptimizeTask.setOnClickListener(v -> handleQuickAction("优化任务"));

        inputMessage.setOnEditorActionListener((v, actionId, event) -> {
            sendMessage();
            return true;
        });
    }

    private void handleQuickAction(String action) {
        String prompt;
        switch (action) {
            case "创建任务": prompt = "请帮我创建一个任务管理计划。"; break;
            case "学习计划": prompt = "请帮我制定一个学习计划。"; break;
            case "问题解决": prompt = "请帮我分析并解决一个问题。"; break;
            case "优化任务": prompt = "请帮我优化现有任务流程。"; break;
            default: prompt = action; break;
        }
        addUserMessage(action);
        sendAgentMessage(prompt);
    }

    private void sendAgentMessage(String message) {
        if (!aiService.isInitialized()) {
            addSystemMessage("正在初始化AI服务...");
            aiService.initializeAsync(success -> runOnUiThread(() -> {
                if (success) {
                    sendAgentMessageInternal(message);
                } else {
                    addSystemMessage("AI服务初始化失败，请先选择模型");
                }
            }));
            return;
        }
        sendAgentMessageInternal(message);
    }

    private void sendAgentMessageInternal(String message) {

        thinkingIndicator.setVisibility(View.VISIBLE);

        if (agentChatHandler.shouldUseAgent(message)) {
            agentChatHandler.startAgentLoop(message, 8192, true);
        } else {
            agentChatHandler.startAgentLoop(message, 8192, false);
        }
    }

    private void sendMessage() {
        String message = inputMessage.getText().toString().trim();
        if (message.isEmpty()) {
            showToast("请输入消息");
            return;
        }
        addUserMessage(message);
        inputMessage.setText("");
        sendAgentMessage(message);
    }

    @Override
    public void onToolCallStart(String toolName, String args) {
        runOnUiThread(() -> addSystemMessage("🔧 调用工具: " + toolName));
    }

    @Override
    public void onToolCallComplete(String toolName, AgentService.ToolResult result) {
        runOnUiThread(() -> {
            String display = result.result;
            if (display != null && display.length() > 200) {
                display = display.substring(0, 200) + "...";
            }
            addSystemMessage("📋 工具结果: " + display);
        });
    }

    @Override
    public void onToken(String token) {
        runOnUiThread(() -> updateLastAIMessage(token));
    }

    @Override
    public void onThinkingToken(String token) {
        runOnUiThread(() -> updateLastAIMessage(token));
    }

    @Override
    public void onThinkingEnd() {
        runOnUiThread(() -> {
            ChatMessageAdapter adapter = (ChatMessageAdapter) messageList.getAdapter();
            if (adapter != null) adapter.refresh();
        });
    }

    @Override
    public void onComplete(String fullResponse) {
        runOnUiThread(() -> {
            thinkingIndicator.setVisibility(View.GONE);
            if (fullResponse != null && !fullResponse.isEmpty()) {
                analyzeAndAddTasks(fullResponse, "");
            }
        });
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> {
            thinkingIndicator.setVisibility(View.GONE);
            addSystemMessage("Agent执行出错: " + error);
        });
    }

    @Override
    public void onModeSwitched(String mode) {
        runOnUiThread(() -> addSystemMessage("模式切换: " + mode));
    }

    @Override
    public void onAgentStep(ChatMessage.AgentStepInfo stepInfo) {
        runOnUiThread(() -> {
            String stepMsg = "步骤 " + stepInfo.stepType;
            String detail = stepInfo.thought != null ? stepInfo.thought :
                           stepInfo.action != null ? stepInfo.action :
                           stepInfo.observation != null ? stepInfo.observation : "";
            if (!detail.isEmpty()) {
                stepMsg += ": " + (detail.length() > 100 ? detail.substring(0, 100) + "..." : detail);
            }
            addSystemMessage(stepMsg);
        });
    }

    @Override
    public void onToolCallUI(String toolName, String args, int position) {
        runOnUiThread(() -> addSystemMessage("🔧 调用工具: " + toolName));
    }

    @Override
    public void onToolCallResultUI(int position, boolean success, String result) {
        runOnUiThread(() -> {
            String display = result;
            if (display != null && display.length() > 200) {
                display = display.substring(0, 200) + "...";
            }
            addSystemMessage((success ? "✅" : "❌") + " " + display);
        });
    }

    @Override
    public void onAgentStepUpdateUI(int position, String thought, String action, String observation, boolean isCompleted) {
        runOnUiThread(() -> {
            if (thought != null && !thought.isEmpty()) {
                addSystemMessage("💭 " + (thought.length() > 100 ? thought.substring(0, 100) + "..." : thought));
            }
            if (action != null && !action.isEmpty()) {
                addSystemMessage("🎯 " + action);
            }
        });
    }

    private void updateLastAIMessage(String token) {
        if (chatHistory.isEmpty()) return;
        ChatMessage lastMsg = chatHistory.get(chatHistory.size() - 1);
        if (lastMsg.isAIMessage()) {
            lastMsg.content += token;
        } else {
            String messageId = "ai_" + System.currentTimeMillis();
            long timestamp = System.currentTimeMillis();
            chatHistory.add(ChatMessage.createAIMessage(messageId, token, timestamp));
        }
        ChatMessageAdapter adapter = (ChatMessageAdapter) messageList.getAdapter();
        if (adapter != null) adapter.refresh();
    }

    private void analyzeAndAddTasks(String response, String originalRequest) {
        if (response.contains("步骤") || response.contains("Step") || response.contains("任务")) {
            AgentTask task = new AgentTask(originalRequest, response, AgentTask.STATUS_PENDING, System.currentTimeMillis());
            taskList.add(task);
            chatHistoryManager.saveAgentTaskList(taskList);
            addSystemMessage("已创建任务: " + originalRequest);
        }
    }

    private void showTaskList() {
        if (taskList.isEmpty()) { addSystemMessage("暂无任务"); return; }
        StringBuilder sb = new StringBuilder("任务列表:\n\n");
        for (int i = 0; i < taskList.size(); i++) {
            AgentTask task = taskList.get(i);
            sb.append((i + 1)).append(". ").append(task.getTitle()).append("\n状态: ").append(task.getStatusText()).append("\n\n");
        }
        addSystemMessage(sb.toString());
    }

    private void clearHistory() {
        int oldSize = chatHistory.size();
        chatHistory.clear();
        taskList.clear();
        if (messageList.getAdapter() != null && oldSize > 0) {
            ((ChatMessageAdapter) messageList.getAdapter()).notifyItemRangeRemoved(0, oldSize);
        }
        new Thread(() -> {
            chatHistoryManager.clearAgentChatHistory();
            chatHistoryManager.clearAgentTaskList();
        }).start();
        addSystemMessage("聊天历史已清空");
    }

    private void scrollToBottom() {
        messageList.post(() -> {
            if (messageList.getAdapter() != null && messageList.getAdapter().getItemCount() > 0) {
                messageList.scrollToPosition(messageList.getAdapter().getItemCount() - 1);
            }
        });
    }

    private class ChatMessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int VIEW_TYPE_USER = 0;
        private static final int VIEW_TYPE_AI = 1;
        private static final int VIEW_TYPE_SYSTEM = 2;

        @Override public int getItemViewType(int position) {
            ChatMessage msg = chatHistory.get(position);
            if (msg.isUserMessage()) return VIEW_TYPE_USER;
            if (msg.isAIMessage()) return VIEW_TYPE_AI;
            return VIEW_TYPE_SYSTEM;
        }

        @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            int layout = viewType == VIEW_TYPE_USER ? R.layout.item_user_message :
                         viewType == VIEW_TYPE_AI ? R.layout.item_ai_message : R.layout.item_system_message;
            return new SimpleViewHolder(getLayoutInflater().inflate(layout, parent, false));
        }

        @Override public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((SimpleViewHolder) holder).messageText.setText(chatHistory.get(position).content);
        }

        @Override public int getItemCount() { return chatHistory.size(); }

        public void refresh() { notifyDataSetChanged(); scrollToBottom(); }

        private class SimpleViewHolder extends RecyclerView.ViewHolder {
            TextView messageText;
            SimpleViewHolder(View itemView) {
                super(itemView);
                messageText = itemView.findViewById(R.id.message_text);
            }
        }
    }

    private void addUserMessage(String message) {
        chatHistory.add(ChatMessage.createUserMessage("user_" + System.currentTimeMillis(), message, System.currentTimeMillis()));
        if (messageList.getAdapter() != null) ((ChatMessageAdapter) messageList.getAdapter()).refresh();
        final List<ChatMessage> hCopy = new ArrayList<>(chatHistory);
        new Thread(() -> chatHistoryManager.saveAgentChatHistory(hCopy)).start();
    }

    private void addAIMessage(String message) {
        chatHistory.add(ChatMessage.createAIMessage("ai_" + System.currentTimeMillis(), message, System.currentTimeMillis()));
        if (messageList.getAdapter() != null) ((ChatMessageAdapter) messageList.getAdapter()).refresh();
        final List<ChatMessage> hCopy = new ArrayList<>(chatHistory);
        new Thread(() -> chatHistoryManager.saveAgentChatHistory(hCopy)).start();
    }

    private void addSystemMessage(String message) {
        chatHistory.add(ChatMessage.createSystemMessage("sys_" + System.currentTimeMillis(), message, ChatMessage.SystemMessageType.INFO, System.currentTimeMillis()));
        if (messageList.getAdapter() != null) ((ChatMessageAdapter) messageList.getAdapter()).refresh();
        final List<ChatMessage> hCopy = new ArrayList<>(chatHistory);
        new Thread(() -> chatHistoryManager.saveAgentChatHistory(hCopy)).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (agentChatHandler != null) agentChatHandler.cancel();
    }
}
