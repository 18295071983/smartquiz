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
 * 本地「薄工具」适配层不变量（2026-10-25）。
 *
 * <p>薄工具（read_file/write_file…）只在 schema 里出现、执行时翻译成真实工具。两个必守的口径：
 * ① 每个薄工具名都必须在 {@code DEFAULT_CORE_TOOLS} 里（否则永远不会被注入、模型点不到）；
 * ② 本地 FC 系统提示词的工具数量必须与注入集一致——历史上"说 4 实际"漂移过，别再犯。
 */
public class LocalThinToolsTest {

    private static Object readStatic(String field) throws Exception {
        Field f = AgentLoopEngine.class.getDeclaredField(field);
        f.setAccessible(true);
        return f.get(null);
    }

    @Test
    public void everyThinToolIsInDefaultCoreTools() throws Exception {
        String[] core = (String[]) readStatic("DEFAULT_CORE_TOOLS");
        @SuppressWarnings("unchecked")
        Map<String, ?> thin = (Map<String, ?>) readStatic("LOCAL_THIN_TOOLS");
        Set<String> coreSet = new HashSet<>(Arrays.asList(core));
        assertTrue("薄工具表不应为空", !thin.isEmpty());
        for (String name : thin.keySet()) {
            assertTrue("薄工具 " + name + " 未进 DEFAULT_CORE_TOOLS，模型永远看不到",
                    coreSet.contains(name));
        }
    }

    @Test
    public void fcPromptToolCountMatchesTheInjectedSet() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(
                "src/main/java/com/oilquiz/app/ai/agent/software/engine/AgentLoopEngine.java")),
                StandardCharsets.UTF_8);
        // 只盯实际注入模型的 prompt 句子（不匹配历史注释里的"四个"字样）：
        // 提示词必须说"六个工具"，且旧的"下列四个工具"这句已不存在（防"说4实际6"漂移）。
        assertTrue("FC 提示词应声明『只能调用下列九个工具』", src.contains("只能调用下列九个工具"));
        assertFalse("FC 提示词不应再写『只能调用下列四个工具』", src.contains("只能调用下列四个工具"));
        assertTrue("薄工具 read_file 应写进选用说明", src.contains("read_file 读取"));
        assertTrue("薄工具 write_file 应写进选用说明", src.contains("write_file 把文本写入"));
        assertTrue("薄工具 calculate 应写进选用说明", src.contains("calculate 做算术求值"));
        assertTrue("薄工具 search_questions 应写进选用说明", src.contains("search_questions 按关键词查本地题库"));
        assertTrue("薄工具 recall_history 应写进选用说明", src.contains("recall_history 搜索跨会话聊天历史"));
    }
}
