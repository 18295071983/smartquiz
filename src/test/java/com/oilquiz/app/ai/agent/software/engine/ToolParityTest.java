package com.oilquiz.app.ai.agent.software.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * TOOL-PARITY(2026-11-08)：在线核心工具必须能在本地通过「核心 / 薄工具 / tool_registry」到达。
 *
 * <p>本地不复制在线 8 个核心（那会把 location/ai_weather/read_file/... 从本地 schema 里拿掉），
 * 而是保持 13 个本地高频薄工具 + tool_registry 发现在线独有能力。这里锁定能力并集：
 * 任何在线核心工具，本地必须有真实注册（tool_registry 可 call），不能出现“在线有、本地没有”。</p>
 */
public class ToolParityTest {

    private static final String TOOL_MANAGER_PATH =
            "src/main/java/com/oilquiz/app/ai/tool/AIToolManager.java";

    private static Object readStatic(String field) throws Exception {
        Field f = AgentLoopEngine.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(null);
    }

    @Test
    public void everyOnlineCoreToolIsReachableFromLocal() throws Exception {
        String[] localCore = (String[]) readStatic("DEFAULT_CORE_TOOLS");
        @SuppressWarnings("unchecked")
        Map<String, ?> localThin = (Map<String, ?>) readStatic("LOCAL_THIN_TOOLS");
        Set<String> local = new HashSet<>(Arrays.asList(localCore));
        local.addAll(localThin.keySet());

        String registry = new String(Files.readAllBytes(Paths.get(TOOL_MANAGER_PATH)),
                StandardCharsets.UTF_8);

        Set<String> online = com.oilquiz.app.ai.agent.online.OnlineAgentEngine.coreTools();
        assertFalse("在线核心工具集为空", online.isEmpty());
        assertTrue("本地核心必须有 tool_registry 作为发现入口", local.contains("tool_registry"));

        for (String tool : online) {
            boolean inLocalCore = local.contains(tool);
            boolean registered = registry.contains("registerToolFactory(\"" + tool + "\"");
            assertTrue("在线核心工具 " + tool + " 在本地既不在核心/薄工具，也没有注册到 AIToolManager"
                            + "（tool_registry 无法 call）",
                    inLocalCore || registered);
        }
    }

    @Test
    public void localCoreKeepsHighFrequencyLocalTools() throws Exception {
        String[] core = (String[]) readStatic("DEFAULT_CORE_TOOLS");
        Set<String> set = new HashSet<>(Arrays.asList(core));
        for (String required : new String[]{
                "time_date", "location", "ai_weather", "network_search",
                "read_file", "write_file", "calculate", "search_questions",
                "database", "tool_registry", "memory", "task", "workspace"}) {
            assertTrue("本地 13 核心不应丢掉 " + required, set.contains(required));
        }
    }
}
