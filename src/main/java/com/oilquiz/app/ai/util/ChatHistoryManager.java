package com.oilquiz.app.ai.util;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.model.AgentTask;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonSyntaxException;

/**
 * ChatHistoryManager - 聊天历史记录管理器
 * 
 * 功能：
 * - 管理AI聊天历史记录的持久化存储
 * - 管理Agent聊天历史记录
 * - 管理Agent任务列表
 * 
 * 存储文件：
 * - ai_chat_history.json: AI聊天历史
 * - agent_chat_history.json: Agent聊天历史
 * - agent_task_list.json: Agent任务列表
 * 
 * 特性：
 * - Gson序列化和反序列化
 * - 自动处理损坏的JSON文件（删除并重建）
 * - 支持清空历史记录
 * 
 * 使用方式：
 * ChatHistoryManager manager = new ChatHistoryManager(context);
 * List<ChatMessage> history = manager.loadAIChatHistory();
 * manager.saveAIChatHistory(newHistory);
 * 
 * @author AI Team
 * @since 2024
 */
public class ChatHistoryManager {

    private static final String TAG = "ChatHistoryManager";
    private static final String AI_CHAT_HISTORY_FILE = "ai_chat_history.json";
    private static final String AGENT_CHAT_HISTORY_FILE = "agent_chat_history.json";
    private static final String AGENT_TASK_LIST_FILE = "agent_task_list.json";

    private final Context context;
    private final Gson gson;

    public ChatHistoryManager(Context context) {
        this.context = context;
        this.gson = new Gson();
    }

    // AI Chat History
    public void saveAIChatHistory(List<ChatMessage> chatHistory) {
        try {
            File file = new File(context.getFilesDir(), AI_CHAT_HISTORY_FILE);
            FileWriter writer = new FileWriter(file);
            gson.toJson(chatHistory, writer);
            writer.close();
        } catch (IOException e) {
            Log.e(TAG, "Error saving AI chat history", e);
        }
    }

    public List<ChatMessage> loadAIChatHistory() {
        try {
            File file = new File(context.getFilesDir(), AI_CHAT_HISTORY_FILE);
            if (!file.exists()) {
                return new ArrayList<>();
            }

            FileReader reader = new FileReader(file);
            Type type = new TypeToken<List<ChatMessage>>() {}.getType();
            List<ChatMessage> chatHistory = gson.fromJson(reader, type);
            reader.close();
            return chatHistory != null ? chatHistory : new ArrayList<>();
        } catch (IOException e) {
            Log.e(TAG, "Error loading AI chat history", e);
            return new ArrayList<>();
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "Error parsing AI chat history JSON", e);
            // Delete the corrupted file
            File file = new File(context.getFilesDir(), AI_CHAT_HISTORY_FILE);
            if (file.exists()) {
                file.delete();
                Log.i(TAG, "Corrupted AI chat history file deleted");
            }
            return new ArrayList<>();
        }
    }

    public void clearAIChatHistory() {
        File file = new File(context.getFilesDir(), AI_CHAT_HISTORY_FILE);
        if (file.exists()) {
            file.delete();
        }
    }

    // Agent Chat History
    public void saveAgentChatHistory(List<ChatMessage> chatHistory) {
        try {
            File file = new File(context.getFilesDir(), AGENT_CHAT_HISTORY_FILE);
            FileWriter writer = new FileWriter(file);
            gson.toJson(chatHistory, writer);
            writer.close();
        } catch (IOException e) {
            Log.e(TAG, "Error saving Agent chat history", e);
        }
    }

    public List<ChatMessage> loadAgentChatHistory() {
        try {
            File file = new File(context.getFilesDir(), AGENT_CHAT_HISTORY_FILE);
            if (!file.exists()) {
                return new ArrayList<>();
            }

            FileReader reader = new FileReader(file);
            Type type = new TypeToken<List<ChatMessage>>() {}.getType();
            List<ChatMessage> chatHistory = gson.fromJson(reader, type);
            reader.close();
            return chatHistory != null ? chatHistory : new ArrayList<>();
        } catch (IOException e) {
            Log.e(TAG, "Error loading Agent chat history", e);
            return new ArrayList<>();
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "Error parsing Agent chat history JSON", e);
            // Delete the corrupted file
            File file = new File(context.getFilesDir(), AGENT_CHAT_HISTORY_FILE);
            if (file.exists()) {
                file.delete();
                Log.i(TAG, "Corrupted Agent chat history file deleted");
            }
            return new ArrayList<>();
        }
    }

    public void clearAgentChatHistory() {
        File file = new File(context.getFilesDir(), AGENT_CHAT_HISTORY_FILE);
        if (file.exists()) {
            file.delete();
        }
    }

    // Agent Task List
    public void saveAgentTaskList(List<AgentTask> taskList) {
        try {
            File file = new File(context.getFilesDir(), AGENT_TASK_LIST_FILE);
            FileWriter writer = new FileWriter(file);
            gson.toJson(taskList, writer);
            writer.close();
        } catch (IOException e) {
            Log.e(TAG, "Error saving Agent task list", e);
        }
    }

    public List<AgentTask> loadAgentTaskList() {
        try {
            File file = new File(context.getFilesDir(), AGENT_TASK_LIST_FILE);
            if (!file.exists()) {
                return new ArrayList<>();
            }

            FileReader reader = new FileReader(file);
            Type type = new TypeToken<List<AgentTask>>() {}.getType();
            List<AgentTask> taskList = gson.fromJson(reader, type);
            reader.close();
            return taskList != null ? taskList : new ArrayList<>();
        } catch (IOException e) {
            Log.e(TAG, "Error loading Agent task list", e);
            return new ArrayList<>();
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "Error parsing Agent task list JSON", e);
            // Delete the corrupted file
            File file = new File(context.getFilesDir(), AGENT_TASK_LIST_FILE);
            if (file.exists()) {
                file.delete();
                Log.i(TAG, "Corrupted Agent task list file deleted");
            }
            return new ArrayList<>();
        }
    }

    public void clearAgentTaskList() {
        File file = new File(context.getFilesDir(), AGENT_TASK_LIST_FILE);
        if (file.exists()) {
            file.delete();
        }
    }

    // Clear all chat data
    public void clearAllChatData() {
        clearAIChatHistory();
        clearAgentChatHistory();
        clearAgentTaskList();
    }
}
