package com.oilquiz.app.ai.agent.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * OnlinePromptBuilder 重构回归测试。
 * 重构目标：手写 StringBuilder += 拼接 → PromptAssembler 命名分段组装，段文本逐字不变。
 * 验证：
 * 1) buildSystemPrompt() / buildSystemPromptTakeover() 与重构前 golden 输出"内容逐字节一致
 *    （仅允许空行数量差异）"——normalize 折叠连续换行后必须完全相等；
 * 2) 段标题出现且顺序与注册 order 一致；
 * 3) buildSystemPromptWithRuntime() 正确注入思考/工作区动态段。
 */
public class OnlinePromptBuilderAssemblyTest {

    /** 折叠连续换行为单个换行并 trim，容忍段间空行数量差异 */
    private static String normalize(String s) {
        return s.replaceAll("\\n+", "\n").trim();
    }

    private static String golden(String name) throws Exception {
        return new String(Files.readAllBytes(
                Paths.get("src/test/resources/golden/" + name)), StandardCharsets.UTF_8);
    }

    @Test
    public void assistMatchesGoldenContent() throws Exception {
        OnlinePromptBuilder b = new OnlinePromptBuilder(null);
        assertEquals(normalize(golden("assist.txt")), normalize(b.buildSystemPrompt()));
    }

    @Test
    public void takeoverMatchesGoldenContent() throws Exception {
        OnlinePromptBuilder b = new OnlinePromptBuilder(null);
        assertEquals(normalize(golden("takeover.txt")), normalize(b.buildSystemPromptTakeover()));
    }

    @Test
    public void assistSectionsInRegisteredOrder() {
        OnlinePromptBuilder b = new OnlinePromptBuilder(null);
        String p = b.buildSystemPrompt();
        String[] titles = {"【角色】", "【工具使用规范】", "【工具发现】", "【工具策略】",
                "【知识库】", "【UI 组件使用规则", "【长期记忆管理】", "【任务状态跟踪】",
                "【多模态能力边界】", "【执行规范】", "【图片生成】", "【输出要求】", "【推理能力】"};
        int last = -1;
        for (String t : titles) {
            int i = p.indexOf(t);
            assertTrue("assist 缺少段标题 " + t, i >= 0);
            assertTrue("assist 段顺序错误：" + t + " 出现在 " + i + " 但上次在 " + last,
                    i > last);
            last = i;
        }
    }

    @Test
    public void takeoverSectionsInRegisteredOrder() {
        OnlinePromptBuilder b = new OnlinePromptBuilder(null);
        String p = b.buildSystemPromptTakeover();
        String[] titles = {"【角色】", "【工具发现】", "【调用规则】", "【工具策略】",
                "【知识库】", "【自主决策权限】", "【输出要求】", "【UI 组件使用规则",
                "【长期记忆管理】", "【执行规范】"};
        int last = -1;
        for (String t : titles) {
            int i = p.indexOf(t);
            assertTrue("takeover 缺少段标题 " + t, i >= 0);
            assertTrue("takeover 段顺序错误：" + t, i > last);
            last = i;
        }
        // guide=null 时不应有【可用工具】段
        assertFalse(p.contains("【可用工具】"));
    }

    @Test
    public void withRuntimeInjectsThinkingThenWorkspace() {
        OnlinePromptBuilder b = new OnlinePromptBuilder(null);
        String p = b.buildSystemPromptWithRuntime(true, "请先思考再回答",
                "【文件与工作区】工作区路径 /files");
        assertTrue(p.contains("【深度思考】"));
        assertTrue(p.contains("请先思考再回答"));
        assertTrue(p.contains("【文件与工作区】"));
        assertTrue(p.indexOf("【深度思考】") > p.indexOf("【推理能力】"));
        assertTrue(p.indexOf("【文件与工作区】") > p.indexOf("【深度思考】"));
    }

    @Test
    public void withRuntimeSkipsEmptyDynamics() {
        OnlinePromptBuilder b = new OnlinePromptBuilder(null);
        String p = b.buildSystemPromptWithRuntime(false, null, "   ");
        assertFalse(p.contains("【深度思考】"));
        assertFalse(p.contains("【文件与工作区】"));
        // 与基础版内容一致
        assertEquals(normalize(b.buildSystemPrompt()), normalize(p));
    }
}
