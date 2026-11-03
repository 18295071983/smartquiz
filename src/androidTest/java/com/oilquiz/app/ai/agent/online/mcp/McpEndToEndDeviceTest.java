package com.oilquiz.app.ai.agent.online.mcp;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.oilquiz.app.ai.tool.AIToolResult;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 真机端到端测试（需要外部测试 server + adb reverse）。
 *
 * <p>默认跳过；只有显式传入端点时才跑，避免 CI/普通 androidTest 因为“没有 server”假红：</p>
 * <pre>
 *   adb reverse tcp:8765 tcp:8765
 *   adb shell am instrument -w \
 *     -e class com.oilquiz.app.ai.agent.online.mcp.McpEndToEndDeviceTest \
 *     -e mcpEndpoint http://127.0.0.1:8765/mcp \
 *     com.oilquiz.app.test/androidx.test.runner.AndroidJUnitRunner
 * </pre>
 *
 * <p>覆盖：Bearer 鉴权、initialize/session、tools/list 分页、目录安装、
 * 按需注册、tools/call 文本结果、structuredContent 回退、删除清理。</p>
 */
@RunWith(AndroidJUnit4.class)
public class McpEndToEndDeviceTest {

    private static final String TOKEN = "test-token";

    @Test
    public void listRegisterAndCallRealServer() throws Exception {
        String endpoint = InstrumentationRegistry.getArguments().getString("mcpEndpoint");
        Assume.assumeTrue("未传 -e mcpEndpoint，跳过真机 MCP 测试", endpoint != null && !endpoint.isEmpty());

        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        McpManager mgr = McpManager.getInstance(ctx);
        mgr.init();

        String id = "e2e-" + System.currentTimeMillis();
        McpServerConfig cfg = new McpServerConfig(id, "E2E Test", endpoint,
                McpServerConfig.Transport.STREAMABLE_HTTP, TOKEN, true, false);

        try {
            mgr.upsertServer(cfg);

            // 等后台同步完成（真实网络 + initialize + 两页 tools/list）
            String status = waitForTerminalStatus(mgr, id, 20_000L);
            assertTrue("同步应成功，实际: " + status, status != null && status.startsWith("ok:"));
            assertTrue("分页后应有 3 个工具，实际: " + status,
                    status.startsWith("ok:3"));

            List<McpClient.McpToolInfo> catalog = mgr.getCatalogForServer(id);
            assertTrue("目录缺失 echo", hasTool(catalog, "echo"));
            assertTrue("目录缺失第二页的 structured_only", hasTool(catalog, "structured_only"));

            List<McpManager.McpToolEntry> hits = mgr.searchTools("echo", 5);
            McpManager.McpToolEntry echo = null;
            for (McpManager.McpToolEntry e : hits) {
                if ("echo".equals(e.remoteName) && id.equals(e.serverId)) echo = e;
            }
            assertNotNull("search 应命中 echo", echo);

            String localName = mgr.ensureRegistered(echo);
            assertNotNull("get/ensureRegistered 应成功注册 echo", localName);
            assertTrue("本地名应以 mcp__ 开头", localName.startsWith("mcp__"));

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("text", "hello");
            AIToolResult echoResult = mgr.executeRemote(id, "echo", args);
            assertTrue("echo 调用失败: " + echoResult.getErrorMessage(), echoResult.isSuccess());
            assertTrue("echo 结果应回显文本，实际: " + echoResult.getResult(),
                    String.valueOf(echoResult.getResult()).contains("echo:hello"));

            Map<String, Object> addArgs = new LinkedHashMap<>();
            addArgs.put("a", 2);
            addArgs.put("b", 3);
            AIToolResult addResult = mgr.executeRemote(id, "add", addArgs);
            assertTrue("add 调用失败: " + addResult.getErrorMessage(), addResult.isSuccess());
            assertTrue("add 结果应为 5.0，实际: " + addResult.getResult(),
                    String.valueOf(addResult.getResult()).contains("5.0"));

            // 服务端只回 structuredContent（无 content）时，客户端要能回退
            AIToolResult structured = mgr.executeRemote(id, "structured_only", new LinkedHashMap<>());
            assertTrue("structured_only 调用失败: " + structured.getErrorMessage(), structured.isSuccess());
            assertTrue("应回退到 structuredContent，实际: " + structured.getResult(),
                    String.valueOf(structured.getResult()).contains("42"));

            // 逐工具审批：显式 deny 必须直接阻断，且不依赖 UI
            mgr.setToolApproval(id, "structured_only", McpConstants.APPROVAL_DENY);
            AIToolResult denied = mgr.executeRemote(id, "structured_only", new LinkedHashMap<>());
            assertTrue("deny 策略应阻断执行", !denied.isSuccess());
            assertTrue("失败原因应说明被禁止，实际: " + denied.getErrorMessage(),
                    String.valueOf(denied.getErrorMessage()).contains("禁止"));
        } finally {
            mgr.removeServer(id);
        }
    }

    private static boolean hasTool(List<McpClient.McpToolInfo> list, String name) {
        for (McpClient.McpToolInfo t : list) {
            if (name.equals(t.name)) return true;
        }
        return false;
    }

    private static String waitForTerminalStatus(McpManager mgr, String id, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = null;
        while (System.currentTimeMillis() < deadline) {
            last = mgr.getStatus().get(id);
            if (last != null && (last.startsWith("ok:") || last.startsWith("error:"))) {
                return last;
            }
            Thread.sleep(200L);
        }
        fail("等待 MCP 同步超时，最后状态: " + last);
        return last;
    }
}
