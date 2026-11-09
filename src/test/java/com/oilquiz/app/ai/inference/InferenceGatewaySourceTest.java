package com.oilquiz.app.ai.inference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * INFERENCE-GATEWAY(2026-11-08)：基础设施迁移约束。
 *
 * <p>AgentLoopEngine / ModelExecutionBridge 必须走 InferenceGateway.chatJson，
 * 不允许绕过闸门直接调 {@code LlamaHelper.chatJson}；owner 必须显式传入，否则超时
 * forceEnd 找不到对应 generation，会把闸门永久锁死。</p>
 */
public class InferenceGatewaySourceTest {

    private static final String ENGINE_PATH =
            "src/main/java/com/oilquiz/app/ai/agent/software/engine/AgentLoopEngine.java";
    private static final String BRIDGE_PATH =
            "src/main/java/com/oilquiz/app/ai/bridge/ModelExecutionBridge.java";

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    @Test
    public void agentUsesGatewayWithOwner() throws Exception {
        String src = read(ENGINE_PATH);
        assertTrue("AgentLoopEngine 必须走 InferenceGateway",
                src.contains("InferenceGateway.get().chatJson(requestJson"));
        assertTrue("AgentLoopEngine 必须显式传 owner，供超时 forceEnd 精确释放",
                src.contains("\"AgentLoopEngine\""));
        assertFalse("AgentLoopEngine 不得绕过闸门直接调 LlamaHelper.chatJson",
                src.contains("LlamaHelper.chatJson("));
    }

    @Test
    public void modelBridgeUsesGatewayWithOwner() throws Exception {
        String src = read(BRIDGE_PATH);
        assertTrue("ModelExecutionBridge 必须走 InferenceGateway",
                src.contains("InferenceGateway.get().chatJson"));
        assertTrue("ModelExecutionBridge 必须显式传 owner",
                src.contains("\"ModelExecutionBridge\""));
        assertFalse("ModelExecutionBridge 不得绕过闸门直接调 LlamaHelper.chatJson",
                src.contains("LlamaHelper.chatJson("));
        assertTrue("早退/取消路径必须 forceEnd，避免下一次 busy",
                src.contains("InferenceGateway.get().forceEnd(\"ModelExecutionBridge\")"));
    }

    @Test
    public void gatewayWaitsForChatContextBeforeBegin() throws Exception {
        String src = read("src/main/java/com/oilquiz/app/ai/inference/InferenceGateway.java");
        assertTrue("Gateway must wait for chat context before begin",
                src.contains("awaitBackendReady(30_000L)"));
        assertTrue("Readiness must use ContextLedger.isChatReady (n_ctx>0)",
                src.contains("ContextLedger.isChatReady()"));
    }
}
