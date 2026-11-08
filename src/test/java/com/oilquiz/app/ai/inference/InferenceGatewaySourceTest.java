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
 * 不允许再直接调 NpuEngineRouter.chatJson；owner 必须显式传入，否则超时 forceEnd
 * 找不到对应 generation，会把闸门永久锁死。</p>
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
        assertFalse("AgentLoopEngine 不得再直接调 NpuEngineRouter.chatJson",
                src.contains("NpuEngineRouter.chatJson("));
    }

    @Test
    public void modelBridgeUsesGatewayWithOwner() throws Exception {
        String src = read(BRIDGE_PATH);
        assertTrue("ModelExecutionBridge 必须走 InferenceGateway",
                src.contains("InferenceGateway.get().chatJson"));
        assertTrue("ModelExecutionBridge 必须显式传 owner",
                src.contains("\"ModelExecutionBridge\""));
        assertFalse("ModelExecutionBridge 不得再持有 NpuEngineRouter.chatJson 调用",
                src.contains("NpuEngineRouter.chatJson("));
        assertTrue("早退/取消路径必须 forceEnd，避免下一次 busy",
                src.contains("InferenceGateway.get().forceEnd(\"ModelExecutionBridge\")"));
    }

    @Test
    public void npuSignalRequiresLoadedModel() throws Exception {
        String src = read("src/main/java/com/oilquiz/app/ai/engine/contract/NpuSignalAdapter.java");
        assertTrue("NPU 状态适配器必须同时检查 engineEnabled 与 isLoaded，"
                        + "否则会拿 NPU 的 32768/52 覆盖本地 llama 的 16384/4473",
                src.contains("NpuLlmChat.isEngineEnabled() && NpuLlmChat.isLoaded()"));
    }
}
