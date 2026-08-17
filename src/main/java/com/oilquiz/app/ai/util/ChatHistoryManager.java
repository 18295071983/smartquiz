package com.oilquiz.app.ai.util;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.ai.chat.component.ComponentData;
import com.oilquiz.app.model.AgentTask;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

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
    private static final String CONVERSATIONS_DIR = "conversations";

    private final Context context;
    private final Gson gson;

    public ChatHistoryManager(Context context) {
        this.context = context;
        // ComponentData.props 是 org.json.JSONObject，Gson 无法直接序列化；
        // 注册适配器：组件数据整体以 JSON 字符串形式持久化（含 tool_call 工具卡片，支持 Agent 过程随会话保存）
        this.gson = new GsonBuilder()
                .disableHtmlEscaping()
                .registerTypeAdapter(ComponentData.class, new ComponentDataAdapter())
                .create();
    }

    /** ComponentData Gson 适配器：以 Gson JsonObject 结构持久化（type + props 树），
     *  避免 org.json.JSONObject 字符串中转的转义/解析问题 */
    private static class ComponentDataAdapter
            implements JsonSerializer<ComponentData>, JsonDeserializer<ComponentData> {
        @Override
        public JsonElement serialize(ComponentData src, java.lang.reflect.Type typeOfSrc,
                                     JsonSerializationContext context) {
            if (src == null) return JsonNull.INSTANCE;
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            obj.addProperty("type", src.type);
            if (src.props != null) {
                obj.add("props", ChatHistoryManager.orgJsonPropsToGson(src.props));
            } else {
                obj.add("props", new com.google.gson.JsonObject());
            }
            return obj;
        }

        @Override
        public ComponentData deserialize(JsonElement json, java.lang.reflect.Type typeOfT,
                                         JsonDeserializationContext context) {
            if (json == null || json.isJsonNull()) return null;
            // 兼容旧格式：字符串形式（{"type":...} 转义的 JSON 字符串）
            if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
                return ComponentData.fromPersistableJson(json.getAsString());
            }
            if (json.isJsonObject()) {
                com.google.gson.JsonObject obj = json.getAsJsonObject();
                JsonElement typeEl = obj.get("type");
                String type = typeEl != null && !typeEl.isJsonNull() ? typeEl.getAsString() : null;
                if (type == null || type.isEmpty()) return null;
                JsonElement propsEl = obj.get("props");
                org.json.JSONObject props;
                if (propsEl != null && propsEl.isJsonObject()) {
                    Object converted = gsonToOrgJson(propsEl.getAsJsonObject());
                    if (converted instanceof org.json.JSONObject) {
                        props = (org.json.JSONObject) converted;
                    } else {
                        props = new org.json.JSONObject();
                    }
                } else {
                    props = new org.json.JSONObject();
                }
                return new ComponentData(type, props);
            }
            return null;
        }
    }

    /** org.json.JSONObject → Gson JsonElement（递归，兼容 JSONArray） */
    private static JsonElement orgJsonToGson(Object value) {
        if (value instanceof org.json.JSONObject) {
            org.json.JSONObject obj = (org.json.JSONObject) value;
            com.google.gson.JsonObject out = new com.google.gson.JsonObject();
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                out.add(key, orgJsonToGson(obj.opt(key)));
            }
            return out;
        }
        if (value instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) value;
            com.google.gson.JsonArray out = new com.google.gson.JsonArray();
            for (int i = 0; i < arr.length(); i++) {
                out.add(orgJsonToGson(arr.opt(i)));
            }
            return out;
        }
        if (value == null || value == org.json.JSONObject.NULL) {
            return JsonNull.INSTANCE;
        }
        if (value instanceof Boolean) return new JsonPrimitive((Boolean) value);
        if (value instanceof Integer) return new JsonPrimitive((Integer) value);
        if (value instanceof Long) return new JsonPrimitive((Long) value);
        if (value instanceof Double) return new JsonPrimitive((Double) value);
        if (value instanceof Float) return new JsonPrimitive((Float) value);
        return new JsonPrimitive(String.valueOf(value));
    }

    /** Gson JsonElement → org.json 值（递归） */
    private static Object gsonToOrgJson(JsonElement el) {
        if (el == null || el.isJsonNull()) return org.json.JSONObject.NULL;
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble();
            return p.getAsString();
        }
        if (el.isJsonArray()) {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (JsonElement child : el.getAsJsonArray()) {
                arr.put(gsonToOrgJson(child));
            }
            return arr;
        }
        if (el.isJsonObject()) {
            org.json.JSONObject obj = new org.json.JSONObject();
            for (java.util.Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                try {
                    obj.put(e.getKey(), gsonToOrgJson(e.getValue()));
                } catch (org.json.JSONException ignored) {}
            }
            return obj;
        }
        return null;
    }

    /** org.json.JSONObject → Gson JsonObject（props 持久化入口） */
    private static JsonObject orgJsonPropsToGson(org.json.JSONObject obj) {
        JsonElement el = orgJsonToGson(obj);
        return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
    }

    /**
     * 检查是否有之前的会话记录
     */
    public boolean hasPreviousSession() {
        File file = new File(context.getFilesDir(), AI_CHAT_HISTORY_FILE);
        return file.exists() && file.length() > 0;
    }

    // AI Chat History
    public void saveAIChatHistory(List<ChatMessage> chatHistory) {
        try {
            File file = new File(context.getFilesDir(), AI_CHAT_HISTORY_FILE);
            File tempFile = new File(context.getFilesDir(), AI_CHAT_HISTORY_FILE + ".tmp");
            FileWriter writer = new FileWriter(tempFile);
            gson.toJson(chatHistory, writer);
            writer.close();
            // 原子替换：先写临时文件，再重命名
            if (file.exists()) file.delete();
            tempFile.renameTo(file);
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
            File tempFile = new File(context.getFilesDir(), AGENT_CHAT_HISTORY_FILE + ".tmp");
            FileWriter writer = new FileWriter(tempFile);
            gson.toJson(chatHistory, writer);
            writer.close();
            if (file.exists()) file.delete();
            tempFile.renameTo(file);
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
            File tempFile = new File(context.getFilesDir(), AGENT_TASK_LIST_FILE + ".tmp");
            FileWriter writer = new FileWriter(tempFile);
            gson.toJson(taskList, writer);
            writer.close();
            if (file.exists()) file.delete();
            tempFile.renameTo(file);
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

    // ==================== 多会话管理 ====================

    /**
     * 保存一个会话到持久化存储。
     * 每个会话存储为独立 JSON 文件，放在 conversations/ 目录下。
     *
     * @param session 要保存的会话（如果 id 为空会自动生成）
     * @return 保存后的会话（含 id 和时间戳）
     */
    public ConversationSession saveConversationSession(ConversationSession session) {
        try {
            if (session.id == null || session.id.isEmpty()) {
                session.id = UUID.randomUUID().toString();
            }
            if (session.title == null || session.title.isEmpty()) {
                session.title = ConversationSession.generateTitle(session.messages);
            }
            if (session.createdAt <= 0) {
                session.createdAt = System.currentTimeMillis();
            }
            session.updatedAt = System.currentTimeMillis();

            File dir = getConversationsDir();
            File file = new File(dir, session.id + ".json");
            FileWriter writer = new FileWriter(file);
            gson.toJson(session, writer);
            writer.close();
            return session;
        } catch (IOException e) {
            Log.e(TAG, "Error saving conversation session", e);
            return session;
        }
    }

    /**
     * 加载指定 ID 的会话。
     */
    public ConversationSession loadConversationSession(String sessionId) {
        try {
            File file = new File(getConversationsDir(), sessionId + ".json");
            if (!file.exists()) return null;
            FileReader reader = new FileReader(file);
            ConversationSession session = gson.fromJson(reader, ConversationSession.class);
            reader.close();
            return session;
        } catch (IOException | JsonSyntaxException e) {
            Log.e(TAG, "Error loading conversation session: " + sessionId, e);
            return null;
        }
    }

    /**
     * 删除指定 ID 的会话。
     */
    public boolean deleteConversationSession(String sessionId) {
        File file = new File(getConversationsDir(), sessionId + ".json");
        if (file.exists()) {
            return file.delete();
        }
        return false;
    }

    /**
     * 列出所有已保存的会话，按最后更新时间降序排列。
     * 只加载元数据（id/title/createdAt/updatedAt/messageCount），不加载完整消息列表，
     * 以保证列表加载速度。
     */
    public List<ConversationSession> listConversationSessions() {
        List<ConversationSession> sessions = new ArrayList<>();
        File dir = getConversationsDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return sessions;

        for (File file : files) {
            try {
                FileReader reader = new FileReader(file);
                ConversationSession session = gson.fromJson(reader, ConversationSession.class);
                reader.close();
                if (session != null && session.id != null) {
                    // 只保留摘要信息，不保留完整消息列表以节省内存
                    session.messages = null;
                    sessions.add(session);
                }
            } catch (Exception e) {
                Log.w(TAG, "Skipping corrupted session file: " + file.getName());
            }
        }

        // 按更新时间降序排列
        Collections.sort(sessions, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return sessions;
    }

    /**
     * 清空所有会话记录。
     */
    public void clearAllConversationSessions() {
        File dir = getConversationsDir();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }

    /**
     * 将当前聊天历史保存为一个会话（用于自动保存）。
     * 如果 existingSessionId 不为空，则更新该会话；否则创建新会话。
     *
     * @param chatHistory 当前聊天消息列表
     * @param existingSessionId 已有的会话 ID（为空则创建新会话）
     * @return 保存的会话，如果 chatHistory 为空则返回 null
     */
    public ConversationSession saveCurrentChatAsSession(List<ChatMessage> chatHistory, String existingSessionId) {
        if (chatHistory == null || chatHistory.isEmpty()) return null;
    
        // 过滤掉系统消息，只保留用户和AI消息
        List<ChatMessage> filtered = new ArrayList<>();
        for (ChatMessage msg : chatHistory) {
            if (msg.isUserMessage() || msg.isAIMessage()) {
                filtered.add(msg);
            }
        }
        if (filtered.isEmpty()) return null;
    
        String title = ConversationSession.generateTitle(filtered);
        ConversationSession session;
        if (existingSessionId != null && !existingSessionId.isEmpty()) {
            // 更新已有会话
            session = loadConversationSession(existingSessionId);
            if (session != null) {
                session.messages = filtered;
                session.title = title;
                session.updatedAt = System.currentTimeMillis();
            } else {
                // 已有会话不存在，创建新的
                session = new ConversationSession(null, title, filtered);
            }
        } else {
            session = new ConversationSession(null, title, filtered);
        }
        return saveConversationSession(session);
    }
    
    /**
     * 将当前聊天历史保存为一个新会话。
     */
    public ConversationSession saveCurrentChatAsSession(List<ChatMessage> chatHistory) {
        return saveCurrentChatAsSession(chatHistory, null);
    }

    private File getConversationsDir() {
        File dir = new File(context.getFilesDir(), CONVERSATIONS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
}
