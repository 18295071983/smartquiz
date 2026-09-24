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
    // 大历史防护（2026-09-25）：分段加载 + 保存保留未加载头部，历史完整保留；
    // 字节上限仅作极端超大文件兜底（正常不触发）
    private static final int MIN_SESSION_MESSAGES = 30;       // 裁剪时至少保留的消息数
    private static final long MAX_SESSION_FILE_BYTES = 64L * 1024 * 1024; // 单文件 JSON 兜底上限(64MB)
    public static final int HISTORY_PAGE_SIZE = 100;          // 历史分段加载每页条数

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
            // 组件 id 持久化（2026-09-14）：COMPONENT 子 id（T1-C1…）随组件保存，
            // 会话恢复后适配器仍可按组件 id 定位/更新/移除
            if (src.id != null && !src.id.isEmpty()) {
                obj.addProperty("id", src.id);
            }
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
                ComponentData cd = new ComponentData(type, props);
                // 恢复组件 id（2026-09-14；旧数据无 id 字段时保持 null，惰性补发）
                JsonElement idEl = obj.get("id");
                if (idEl != null && !idEl.isJsonNull()) {
                    cd.id = idEl.getAsString();
                }
                return cd;
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
    /**
     * 保存前深拷贝消息列表：主线程可能在后台保存线程序列化期间继续修改
     * 消息的 components/attachments/thinkingSteps/thinkingRounds 等可变集合
     * （如 appendAgentToolCall 向 msg.components 追加组件），浅拷贝共享引用会导致
     * Gson 序列化时 ConcurrentModificationException（Crash）。
     * 返回的列表与调用方完全解耦，可安全序列化。
     */
        public static List<ChatMessage> deepCopyForSave(List<ChatMessage> chatHistory) {
        if (chatHistory == null) return new ArrayList<>();
        List<ChatMessage> copy = new ArrayList<>(chatHistory.size());
        for (ChatMessage msg : chatHistory) {
            if (msg == null) { copy.add(null); continue; }
            // 基于 clone()（Builder 深拷贝 thinkingSteps/attachments），
            // 再补上 clone() 未覆盖的可变集合与运行时字段（防并发修改 CME）
            ChatMessage m = msg.clone();
            // 消息对 id 持久化（2026-09-14）：clone() 的 Builder 不含 turnId/subId，
            // 若不补拷，保存后所有消息的回合/子 id 丢失，适配器按 id 管理失效
            m.turnId = msg.turnId;
            m.subId = msg.subId;
            m.components = msg.components != null ? new ArrayList<>(msg.components) : null;
            m.thinkingRounds = msg.thinkingRounds != null ? new ArrayList<>(msg.thinkingRounds) : null;
            // 思考轮 id 随轮次内容一起拷贝（2026-09-14）：恢复后 id 与轮次一一对应不丢
            m.thinkingRoundIds = msg.thinkingRoundIds != null ? new ArrayList<>(msg.thinkingRoundIds) : null;
            // Agent 运行时字段（clone() 的 Builder 未覆盖，直接赋值）
            m.agentToolsExpanded = msg.agentToolsExpanded;
            m.agentMode = msg.agentMode;
            m.agentGroupId = msg.agentGroupId;
            m.isAgentGroupHeader = msg.isAgentGroupHeader;
            m.agentGroupCollapsed = msg.agentGroupCollapsed;
            m.agentGroupStepCount = msg.agentGroupStepCount;
            m.agentGroupToolCount = msg.agentGroupToolCount;
            m.turnMode = msg.turnMode;   // 轮次模式标记（clone() 的 Builder 未覆盖，会话保存后隔离不失效）
            m.agentStepStatus = msg.agentStepStatus;
            m.agentSummary = msg.agentSummary;
            m.hasUserToggledExpand = msg.hasUserToggledExpand;
            m.agentExecutionState = msg.agentExecutionState;
            copy.add(m);
        }
        return copy;
    }
public void saveAIChatHistory(List<ChatMessage> chatHistory) {
        saveAIHistoryWithHead(chatHistory, 0);
    }

    /**
     * 保存单文件历史，并保留文件前端未加载的旧消息（分段加载时防止截断丢数据）。
     *
     * @param chatHistory 当前内存中的消息窗口（含新消息）
     * @param unloadedFrontCount 该文件前端尚未加载的旧消息条数（0=全部已加载）
     */
    public void saveAIHistoryWithHead(List<ChatMessage> chatHistory, int unloadedFrontCount) {
        try {
            // 深拷贝防并发修改崩溃（主线程可能仍在改 components 等集合）
            List<ChatMessage> snapshot = deepCopyForSave(chatHistory);
            List<ChatMessage> toSave = trimBySize(snapshot, AI_CHAT_HISTORY_FILE);
            File file = new File(context.getFilesDir(), AI_CHAT_HISTORY_FILE);
            int headCount = Math.max(0, Math.min(unloadedFrontCount, countMessagesInFile(file)));
            File tempFile = new File(context.getFilesDir(), AI_CHAT_HISTORY_FILE + ".tmp");
            FileWriter writer = new FileWriter(tempFile);
            writer.write("[");
            writeMessagesPreservingHead(writer, file, false, headCount, toSave);
            writer.write("]");
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
            warnIfLarge(file, AI_CHAT_HISTORY_FILE);

            FileReader reader = new FileReader(file);
            Type type = new TypeToken<List<ChatMessage>>() {}.getType();
            List<ChatMessage> chatHistory = gson.fromJson(reader, type);
            reader.close();
            return chatHistory != null ? chatHistory : new ArrayList<>();
        } catch (OutOfMemoryError e) {
            // 兜底：大文件解析内存不足时保命，不删用户文件（后续保存已按上限裁剪）
            Log.e(TAG, "OutOfMemory loading AI chat history (file too large), keep file intact", e);
            return new ArrayList<>();
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
            warnIfLarge(file, AGENT_CHAT_HISTORY_FILE);

            FileReader reader = new FileReader(file);
            Type type = new TypeToken<List<ChatMessage>>() {}.getType();
            List<ChatMessage> chatHistory = gson.fromJson(reader, type);
            reader.close();
            return chatHistory != null ? chatHistory : new ArrayList<>();
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "OutOfMemory loading Agent chat history, keep file intact", e);
            return new ArrayList<>();
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
            // 持久化消息数：列表加载时为省内存会置空 messages，靠此字段展示条数
            session.messageCount = session.messages != null ? session.messages.size() : session.messageCount;

            File dir = getConversationsDir();
            File file = new File(dir, session.id + ".json");
            // 大会话防护：序列化后按字节上限裁剪最早消息，防止单会话文件无限增长
            String json = gson.toJson(session);
            int guard = 0;
            while (json.length() > MAX_SESSION_FILE_BYTES
                    && session.messages != null && session.messages.size() > MIN_SESSION_MESSAGES
                    && guard++ < 4) {
                int drop = Math.max(1, session.messages.size() / 4);
                session.messages = new ArrayList<>(session.messages.subList(drop, session.messages.size()));
                session.messageCount = session.messages.size();
                Log.i(TAG, "会话[" + session.id + "]过大(" + json.length() + "B)，裁剪最早 " + drop + " 条消息");
                json = gson.toJson(session);
            }
            // 原子写：先写临时文件再 rename，避免退出保存线程与新 Activity 加载线程
            // 并发读写时读到半写文件（Gson 解析失败 → 会话被跳过 → 历史丢失）
            File tempFile = new File(dir, session.id + ".json.tmp");
            FileWriter writer = new FileWriter(tempFile);
            writer.write(json);
            writer.close();
            if (file.exists()) file.delete();
            tempFile.renameTo(file);
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
            warnIfLarge(file, sessionId + ".json");
            FileReader reader = new FileReader(file);
            ConversationSession session = gson.fromJson(reader, ConversationSession.class);
            reader.close();
            return session;
        } catch (OutOfMemoryError e) {
            // 兜底：超大会话解析内存不足时保命（不删文件，后续保存已按上限裁剪）
            Log.e(TAG, "OutOfMemory loading conversation session: " + sessionId + ", keep file intact", e);
            return null;
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
                // 流式只读元数据：跳过 messages 数组（只数条数、不构建消息对象），
                // 避免多会话/大文件时全量 Gson 解析造成卡顿与 OOM 崩溃
                ConversationSession session = readSessionMetadataOnly(file);
                if (session != null && session.id != null) {
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
     * 流式读取会话文件元数据（id/title/createdAt/updatedAt/消息条数）。
     * 不解析 messages 数组内容，仅数顶层数组元素个数，内存占用与文件大小无关。
     */
    private ConversationSession readSessionMetadataOnly(File file) throws IOException {
        ConversationSession session = new ConversationSession();
        try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case "id":
                        session.id = reader.nextString();
                        break;
                    case "title":
                        session.title = reader.nextString();
                        break;
                    case "createdAt":
                        session.createdAt = reader.nextLong();
                        break;
                    case "updatedAt":
                        session.updatedAt = reader.nextLong();
                        break;
                    case "messageCount":
                        try {
                            session.messageCount = reader.nextInt();
                        } catch (Exception ex) {
                            reader.skipValue();
                        }
                        break;
                    case "messages":
                        // 只数元素个数，不构建消息对象
                        reader.beginArray();
                        int count = 0;
                        while (reader.hasNext()) {
                            reader.skipValue();
                            count++;
                        }
                        reader.endArray();
                        session.messageCount = count;
                        break;
                    default:
                        reader.skipValue();
                        break;
                }
            }
            reader.endObject();
        }
        if (session.id == null || session.id.isEmpty()) return null;
        // 与旧行为一致：列表只保留摘要信息，不保留消息列表
        session.messages = null;
        return session;
    }

    /** 大文件告警（加载前调用，日志可见便于排查） */
    private void warnIfLarge(File file, String name) {
        if (file.length() > MAX_SESSION_FILE_BYTES) {
            Log.w(TAG, "文件偏大(" + file.length() + "B)，加载可能变慢: " + name);
        }
    }

    /** 按文件字节上限裁剪最早消息（保留最新窗口）；返回裁剪后的列表 */
    private List<ChatMessage> trimBySize(List<ChatMessage> list, String fileTag) {
        if (list == null || list.isEmpty()) return list;
        List<ChatMessage> trimmed = list;
        int guard = 0;
        String json = gson.toJson(trimmed);
        while (json.length() > MAX_SESSION_FILE_BYTES && trimmed.size() > MIN_SESSION_MESSAGES && guard++ < 4) {
            int drop = Math.max(1, trimmed.size() / 4);
            trimmed = new ArrayList<>(trimmed.subList(drop, trimmed.size()));
            Log.i(TAG, "历史文件[" + fileTag + "]过大(" + json.length() + "B)，裁剪最早 " + drop + " 条消息");
            json = gson.toJson(trimmed);
        }
        return trimmed;
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
    
        // 深拷贝防并发修改崩溃（主线程可能仍在改 components 等集合）
        List<ChatMessage> safeHistory = deepCopyForSave(chatHistory);
        // 过滤掉系统消息，只保留用户和AI消息
        List<ChatMessage> filtered = new ArrayList<>();
        for (ChatMessage msg : safeHistory) {
            if (msg.isUserMessage() || msg.isAIMessage()) {
                filtered.add(msg);
            }
        }
        if (filtered.isEmpty()) return null;
    
        String title = ConversationSession.generateTitle(filtered);
        return saveCurrentChatAsSession(chatHistory, existingSessionId, 0, false);
    }

    /**
     * 将当前聊天历史保存为会话；分段加载场景下保留文件前端未加载的旧消息，不丢数据。
     *
     * @param chatHistory 当前内存中的消息窗口（含新消息）
     * @param existingSessionId 已有会话 ID（为空则创建新会话）
     * @param unloadedFrontCount 该会话前端尚未加载的旧消息条数（0=全部已加载）
     * @param mergeSingleFileHead 创建新会话时，若历史源自单文件（ai_chat_history.json）且前端未加载，
     *                            从单文件合并头部，避免新会话被截断
     */
    public ConversationSession saveCurrentChatAsSession(List<ChatMessage> chatHistory, String existingSessionId,
                                                        int unloadedFrontCount, boolean mergeSingleFileHead) {
        if (chatHistory == null || chatHistory.isEmpty()) return null;
        // 深拷贝防并发修改崩溃（主线程可能仍在改 components 等集合）
        List<ChatMessage> safeHistory = deepCopyForSave(chatHistory);
        List<ChatMessage> filtered = new ArrayList<>();
        for (ChatMessage msg : safeHistory) {
            if (msg.isUserMessage() || msg.isAIMessage()) filtered.add(msg);
        }
        if (filtered.isEmpty()) return null;
        String title = ConversationSession.generateTitle(filtered);
        try {
            File sessionFile = null;
            File headSource = null;
            boolean headSessionObject = true;
            int headCount = 0;
            long createdAt = System.currentTimeMillis();
            if (existingSessionId != null && !existingSessionId.isEmpty()) {
                sessionFile = new File(getConversationsDir(), existingSessionId + ".json");
                if (sessionFile.exists()) {
                    ConversationSession meta = readSessionMetadataOnly(sessionFile);
                    if (meta != null && meta.createdAt > 0) createdAt = meta.createdAt;
                    headCount = Math.max(0, Math.min(unloadedFrontCount, countMessagesInFile(sessionFile)));
                    headSource = sessionFile;
                }
            } else if (mergeSingleFileHead) {
                File aiFile = new File(context.getFilesDir(), AI_CHAT_HISTORY_FILE);
                if (aiFile.exists()) {
                    headCount = Math.max(0, Math.min(unloadedFrontCount, countMessagesInFile(aiFile)));
                    headSource = aiFile;
                    headSessionObject = false;
                }
            }
            ConversationSession session = new ConversationSession(existingSessionId, title, filtered);
            session.createdAt = createdAt;
            session.updatedAt = System.currentTimeMillis();
            if (sessionFile != null || headSource != null) {
                // 保留未加载头部 + 新尾部（流式合并，内存有界）
                File target = sessionFile != null
                        ? sessionFile : new File(getConversationsDir(), session.id + ".json");
                writeSessionWithHead(target, session, headCount, headSource, headSessionObject);
            } else {
                // 全新会话，无头部可保留
                return saveConversationSession(session);
            }
            return session;
        } catch (Exception e) {
            Log.e(TAG, "Error saving conversation session", e);
            return null;
        }
    }
    
    /** 流式统计文件中的消息总数（数组或会话对象两种形态都支持；不构建对象） */
    private int countMessagesInFile(File file) {
        if (file == null || !file.exists()) return 0;
        int count = 0;
        try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            if (reader.peek() == com.google.gson.stream.JsonToken.BEGIN_ARRAY) {
                reader.beginArray();
                while (reader.hasNext()) { reader.skipValue(); count++; }
                reader.endArray();
            } else {
                reader.beginObject();
                while (reader.hasNext()) {
                    if ("messages".equals(reader.nextName())) {
                        reader.beginArray();
                        while (reader.hasNext()) { reader.skipValue(); count++; }
                        reader.endArray();
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error counting messages", e);
            return 0;
        }
        return count;
    }

    /** 统计单文件历史消息总数（流式，内存与文件大小无关） */
    public int countAIHistoryMessages() {
        return countMessagesInFile(new File(context.getFilesDir(), AI_CHAT_HISTORY_FILE));
    }

    /** 统计指定会话消息总数（流式） */
    public int countConversationMessages(String sessionId) {
        return countMessagesInFile(new File(getConversationsDir(), sessionId + ".json"));
    }

    /** 分段加载单文件历史：跳过前 skipCount 条，最多取 limit 条（流式解析，内存只含返回条数） */
    public List<ChatMessage> loadAIHistoryMessages(int skipCount, int limit) {
        return loadMessagesFromFile(new File(context.getFilesDir(), AI_CHAT_HISTORY_FILE), false, skipCount, limit);
    }

    /** 分段加载会话消息：跳过前 skipCount 条，最多取 limit 条 */
    public List<ChatMessage> loadConversationSessionMessages(String sessionId, int skipCount, int limit) {
        return loadMessagesFromFile(new File(getConversationsDir(), sessionId + ".json"), true, skipCount, limit);
    }

    /** 流式分段读取消息数组：跳过的元素用 skipValue（不构建对象），取到的逐条解析 */
    private List<ChatMessage> loadMessagesFromFile(File file, boolean sessionObject, int skipCount, int limit) {
        List<ChatMessage> out = new ArrayList<>();
        if (limit <= 0 || file == null || !file.exists()) return out;
        try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            if (sessionObject) {
                reader.beginObject();
                while (reader.hasNext()) {
                    if ("messages".equals(reader.nextName())) {
                        readArrayInto(reader, out, skipCount, limit);
                        break;
                    }
                    reader.skipValue();
                }
                reader.endObject();
            } else {
                readArrayInto(reader, out, skipCount, limit);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading history page", e);
        }
        return out;
    }

    private void readArrayInto(com.google.gson.stream.JsonReader reader, List<ChatMessage> out,
                               int skipCount, int limit) throws java.io.IOException {
        reader.beginArray();
        int idx = 0;
        while (reader.hasNext()) {
            if (idx < skipCount) {
                reader.skipValue();
            } else if (out.size() < limit) {
                ChatMessage m = gson.fromJson(reader, ChatMessage.class);
                if (m != null) out.add(m);
            } else {
                reader.skipValue();
            }
            idx++;
        }
        reader.endArray();
    }

    /**
     * 流式写会话文件：头部从旧文件逐条复制（内存有界），尾部写当前内存窗口；
     * 用于分段加载场景下保存时不丢未加载的旧消息。
     */
    private void writeSessionWithHead(File file, ConversationSession session, int headCount,
                                      File headSource, boolean headSessionObject) throws IOException {
        File dir = getConversationsDir();
        File tempFile = new File(dir, session.id + ".json.tmp");
        // 尾部同样受字节上限兜底（仅极端大文件触发，正常不裁剪）
        List<ChatMessage> tail = trimBySize(session.messages, session.id + ".json");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write("{\"id\":");
            writer.write(gson.toJson(session.id));
            writer.write(",\"title\":");
            writer.write(gson.toJson(session.title != null ? session.title : ""));
            writer.write(",\"messages\":[");
            writeMessagesPreservingHead(writer, headSource, headSessionObject, headCount, tail);
            writer.write("],\"createdAt\":");
            writer.write(Long.toString(session.createdAt > 0 ? session.createdAt : System.currentTimeMillis()));
            writer.write(",\"updatedAt\":");
            writer.write(Long.toString(System.currentTimeMillis()));
            writer.write(",\"messageCount\":");
            writer.write(Integer.toString(headCount + tail.size()));
            writer.write("}");
        }
        if (file.exists()) file.delete();
        tempFile.renameTo(file);
    }

    /**
     * 流式写消息数组内容：先复制 sourceFile 头部 headCount 条（逐条解析再序列化，内存有界），
     * 再写 tail（当前内存窗口）。调用方负责写方括号。
     */
    private void writeMessagesPreservingHead(FileWriter writer, File sourceFile, boolean sourceSessionObject,
                                             int headCount, List<ChatMessage> tail) throws IOException {
        boolean first = true;
        if (headCount > 0 && sourceFile != null && sourceFile.exists()) {
            try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(sourceFile), java.nio.charset.StandardCharsets.UTF_8))) {
                boolean arrayFound = false;
                if (sourceSessionObject) {
                    reader.beginObject();
                    while (reader.hasNext()) {
                        if ("messages".equals(reader.nextName())) { arrayFound = true; break; }
                        reader.skipValue();
                    }
                } else {
                    arrayFound = true;
                }
                if (arrayFound) {
                    reader.beginArray();
                    int idx = 0;
                    while (reader.hasNext() && idx < headCount) {
                        ChatMessage m = gson.fromJson(reader, ChatMessage.class);
                        if (m != null) {
                            if (!first) writer.write(",");
                            writer.write(gson.toJson(m));
                            first = false;
                        }
                        idx++;
                    }
                    while (reader.hasNext()) reader.skipValue();
                    reader.endArray();
                }
            }
        }
        for (ChatMessage m : tail) {
            if (m == null) continue;
            if (!first) writer.write(",");
            writer.write(gson.toJson(m));
            first = false;
        }
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
