package com.oilquiz.app.ai.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oilquiz.app.ai.util.AtomicFileText;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一轮 Agent 执行的<b>检查点</b>：进程被杀后，靠它把"这一轮做到哪了"还原出来。
 *
 * <p><b>恢复语义（刻意保守）</b>：本类只负责"记录与读回"，<b>不重放任何工具</b>。
 * 崩溃时正在执行的工具，其结果<b>未知</b>——它可能没跑，也可能已经跑完（删了文件/发了消息）。
 * 恢复时把这些工具一律补成"结果未知"的 tool 消息：既满足 API 的配对要求（否则孤儿 tool_call
 * 直接 400），也确保模型不会基于"我没见过结果"去重跑一个有副作用的工具。要不要重试，交给人/模型
 * 在下一轮显式决定。</p>
 *
 * <p>落点选择（两个都写，后写覆盖前写）：</p>
 * <ol>
 *   <li>assistant 消息（含 tool_calls）进历史之后、工具真正执行之前 —— 崩溃时这一批工具就是"未知"；</li>
 *   <li>这一轮全部工具结果收齐之后 —— 最坏只丢"最后一个工具之后的进展"。</li>
 * </ol>
 *
 * <p>状态只有 running / interrupted 两种留存值：正常收尾会把文件删掉，所以"文件存在"就等于
 * "上次没有正常结束"。用 {@link AtomicFileText} 原子写，不会留下半截 JSON。</p>
 */
public final class AgentRoundCheckpoint {

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_INTERRUPTED = "interrupted";

    /** 恢复时补的那条 tool 消息正文：说清"未知"，并明确禁止直接重跑有副作用的工具 */
    public static final String UNKNOWN_RESULT_TEXT =
            "上一次执行在此工具调用期间被中断，本次结果未知（可能已执行、也可能没执行）。"
                    + "不要直接重跑有副作用或不可逆的工具；如需重试，先向用户确认。";

    private AgentRoundCheckpoint() {
    }

    /** 检查点内容 */
    public static final class Snapshot {
        public String sessionId;
        public String modelId;
        public String runId;
        public long startedAt;
        public int iteration;
        public String status;
        public JsonArray messages;
    }

    /** 检查点文件：与历史文件同目录，名字由"历史文件名 + .round"派生，天然按 会话×模型 隔离 */
    public static File fileFor(File historyFile) {
        return historyFile == null ? null : new File(historyFile.getPath() + ".round");
    }

    /** 原子写入（同时把旧的一份留作 .bak，由 AtomicFileText 负责） */
    public static boolean save(File checkpointFile, Snapshot snapshot) {
        if (checkpointFile == null || snapshot == null) return false;
        JsonObject obj = new JsonObject();
        obj.addProperty("sessionId", snapshot.sessionId);
        obj.addProperty("modelId", snapshot.modelId);
        obj.addProperty("runId", snapshot.runId);
        obj.addProperty("startedAt", snapshot.startedAt);
        obj.addProperty("iteration", snapshot.iteration);
        obj.addProperty("status", snapshot.status);
        obj.add("messages", snapshot.messages != null ? snapshot.messages : new JsonArray());
        return AtomicFileText.write(checkpointFile, obj.toString());
    }

    /** 读回；文件不存在/解析失败返回 null（主文件坏了回落 .bak） */
    public static Snapshot load(File checkpointFile) {
        if (checkpointFile == null || !checkpointFile.exists()) return null;
        Snapshot s = parse(AtomicFileText.readText(checkpointFile));
        if (s == null) s = parse(AtomicFileText.readBackup(checkpointFile));
        return s;
    }

    /** 删除检查点（正常收尾/已消费后调用）；.bak 一并删，避免下次被"复活" */
    public static void clear(File checkpointFile) {
        if (checkpointFile == null) return;
        File bak = AtomicFileText.backupOf(checkpointFile);
        if (checkpointFile.exists()) checkpointFile.delete();
        if (bak != null && bak.exists()) bak.delete();
    }

    private static Snapshot parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JsonObject obj = JsonParser.parseString(raw).getAsJsonObject();
            Snapshot s = new Snapshot();
            s.sessionId = optString(obj, "sessionId");
            s.modelId = optString(obj, "modelId");
            s.runId = optString(obj, "runId");
            s.startedAt = obj.has("startedAt") ? obj.get("startedAt").getAsLong() : 0L;
            s.iteration = obj.has("iteration") ? obj.get("iteration").getAsInt() : 0;
            s.status = optString(obj, "status");
            s.messages = obj.has("messages") && obj.get("messages").isJsonArray()
                    ? obj.getAsJsonArray("messages") : new JsonArray();
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static String optString(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try {
            return obj.get(key).getAsString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 找出"发了但没结果"的 tool_calls：assistant 消息里声明了 tool_calls，但历史里没有对应
     * {@code role=tool} 且 {@code tool_call_id} 匹配的消息。
     *
     * @return 每项 {@code {callId, toolName}}，顺序与历史一致
     */
    public static List<String[]> unresolvedToolCalls(JsonArray messages) {
        List<String[]> unresolved = new ArrayList<>();
        if (messages == null) return unresolved;
        Set<String> answered = new HashSet<>();
        for (JsonElement el : messages) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject msg = el.getAsJsonObject();
            String role = optString(msg, "role");
            if (!"tool".equals(role)) continue;
            String id = optString(msg, "tool_call_id");
            if (id != null) answered.add(id);
        }
        for (JsonElement el : messages) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject msg = el.getAsJsonObject();
            if (!"assistant".equals(optString(msg, "role"))) continue;
            if (!msg.has("tool_calls") || !msg.get("tool_calls").isJsonArray()) continue;
            for (JsonElement tcEl : msg.getAsJsonArray("tool_calls")) {
                if (tcEl == null || !tcEl.isJsonObject()) continue;
                JsonObject tc = tcEl.getAsJsonObject();
                String id = optString(tc, "id");
                if (id == null || answered.contains(id)) continue;
                String name = null;
                if (tc.has("function") && tc.get("function").isJsonObject()) {
                    name = optString(tc.getAsJsonObject("function"), "name");
                }
                unresolved.add(new String[]{id, name});
            }
        }
        return unresolved;
    }
}
