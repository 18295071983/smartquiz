package com.oilquiz.app.ai.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 轮次检查点：写入/读回、原子性（通过 AtomicFileText）、以及"哪些工具结果未知"的判定。
 *
 * <p>这里钉住的是恢复语义的安全边界：<b>只认 tool_call_id 配对，不认工具名</b>（同名工具可能被
 * 调用多次，按名配会把另一次的结果错当这次）；<b>未知工具绝不出现在"已完成"集合里</b>，
 * 否则恢复后模型会以为副作用已经发生/没发生，从而做出错误决定。</p>
 */
public class AgentRoundCheckpointTest {

    private File dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("agent-cp").toFile();
    }

    @After
    public void tearDown() {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        dir.delete();
    }

    private static JsonObject msg(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }

    private static JsonObject assistantWithCalls(String... idNamePairs) {
        JsonObject o = msg("assistant", "");
        JsonArray calls = new JsonArray();
        for (int i = 0; i + 1 < idNamePairs.length; i += 2) {
            JsonObject tc = new JsonObject();
            tc.addProperty("id", idNamePairs[i]);
            JsonObject fn = new JsonObject();
            fn.addProperty("name", idNamePairs[i + 1]);
            fn.addProperty("arguments", "{}");
            tc.add("function", fn);
            calls.add(tc);
        }
        o.add("tool_calls", calls);
        return o;
    }

    private static JsonObject toolResult(String callId, String name) {
        JsonObject o = msg("tool", "ok");
        o.addProperty("tool_call_id", callId);
        o.addProperty("name", name);
        return o;
    }

    @Test
    public void saveLoadRoundTripKeepsMessagesAndMeta() {
        File cp = AgentRoundCheckpoint.fileFor(new File(dir, "history_s1_m1.json"));
        AgentRoundCheckpoint.Snapshot snap = new AgentRoundCheckpoint.Snapshot();
        snap.sessionId = "s1";
        snap.modelId = "m1";
        snap.runId = "run12345";
        snap.startedAt = 123456789L;
        snap.iteration = 3;
        snap.status = AgentRoundCheckpoint.STATUS_RUNNING;
        JsonArray arr = new JsonArray();
        arr.add(msg("user", "帮我整理文件"));
        arr.add(assistantWithCalls("call_1", "workspace"));
        snap.messages = arr;

        assertTrue(AgentRoundCheckpoint.save(cp, snap));
        AgentRoundCheckpoint.Snapshot back = AgentRoundCheckpoint.load(cp);
        assertNotNull(back);
        assertEquals("s1", back.sessionId);
        assertEquals("run12345", back.runId);
        assertEquals(3, back.iteration);
        assertEquals(AgentRoundCheckpoint.STATUS_RUNNING, back.status);
        assertEquals(2, back.messages.size());
        assertEquals("call_1", JsonParser.parseString(back.messages.get(1).toString())
                .getAsJsonObject().getAsJsonArray("tool_calls")
                .get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    public void unresolvedIsMatchedByCallIdNotByName() {
        JsonArray history = new JsonArray();
        history.add(msg("user", "删掉 a.txt 和 b.txt"));
        // 发了两个调用，但只有第一个有结果——第二个是崩溃时正在跑的那个
        history.add(assistantWithCalls("call_A", "workspace", "call_B", "workspace"));
        history.add(toolResult("call_A", "workspace"));

        List<String[]> unresolved = AgentRoundCheckpoint.unresolvedToolCalls(history);
        assertEquals(1, unresolved.size());
        assertEquals("call_B", unresolved.get(0)[0]);
        assertEquals("workspace", unresolved.get(0)[1]);
    }

    @Test
    public void noUnresolvedWhenAllCallsAnsweredOrNoCalls() {
        JsonArray allAnswered = new JsonArray();
        allAnswered.add(assistantWithCalls("c1", "time_date", "c2", "time_date"));
        allAnswered.add(toolResult("c1", "time_date"));
        allAnswered.add(toolResult("c2", "time_date"));
        assertTrue(AgentRoundCheckpoint.unresolvedToolCalls(allAnswered).isEmpty());

        JsonArray plain = new JsonArray();
        plain.add(msg("assistant", "直接回答"));
        assertTrue(AgentRoundCheckpoint.unresolvedToolCalls(plain).isEmpty());
        assertTrue(AgentRoundCheckpoint.unresolvedToolCalls(null).isEmpty());
    }

    @Test
    public void clearRemovesMainAndBackupAndLoadIsNullAfterwards() {
        File cp = AgentRoundCheckpoint.fileFor(new File(dir, "h.json"));
        AgentRoundCheckpoint.Snapshot s1 = new AgentRoundCheckpoint.Snapshot();
        s1.runId = "r1";
        s1.messages = new JsonArray();
        assertTrue(AgentRoundCheckpoint.save(cp, s1));
        s1.runId = "r2";
        assertTrue(AgentRoundCheckpoint.save(cp, s1));   // 产生 .bak
        assertTrue(cp.exists());
        assertTrue(com.oilquiz.app.ai.util.AtomicFileText.backupOf(cp).exists());

        AgentRoundCheckpoint.clear(cp);
        assertFalse(cp.exists());
        assertFalse(com.oilquiz.app.ai.util.AtomicFileText.backupOf(cp).exists());
        assertNull(AgentRoundCheckpoint.load(cp));
    }

    @Test
    public void unknownResultTextWarnsAgainstReplay() {
        // 文案是恢复语义的一部分：必须同时说"未知"和"不要直接重跑有副作用的工具"
        assertTrue(AgentRoundCheckpoint.UNKNOWN_RESULT_TEXT.contains("未知"));
        assertTrue(AgentRoundCheckpoint.UNKNOWN_RESULT_TEXT.contains("不要直接重跑"));
    }
}
