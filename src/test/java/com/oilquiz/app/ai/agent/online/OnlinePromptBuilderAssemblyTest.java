package com.oilquiz.app.ai.agent.online;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * OnlinePromptBuilder 组装回归测试。
 *
 * <p>两层保护，目的不同：
 * <ol>
 *   <li><b>golden 逐字比对</b>（{@code src/test/resources/golden/}）：提示词是"改一个字就可能改变
 *       模型行为"的东西，任何改动都必须是一次<b>有意识的</b>动作。golden 不是不能变，
 *       而是变之前要看过 diff、确认是想要的措辞，再重新生成（见下）。</li>
 *   <li><b>段标题与顺序</b>：结构契约，比逐字比对更稳，golden 重新生成时也靠它兜住顺序不回退。</li>
 * </ol>
 *
 * <p>重新生成 golden（改完提示词、确认过 diff 之后执行）：临时写一个用例把
 * {@code new OnlinePromptBuilder(null).buildSystemPrompt()} 与
 * {@code buildSystemPromptTakeover()} 分别写到
 * {@code src/test/resources/golden/assist.txt} / {@code takeover.txt}，
 * 跑完删掉临时用例，再 diff 一遍确认改动范围。<b>不要</b>为了让测试变绿直接覆盖 golden
 * 而不看 diff——那等于关掉这层保护。
 *
 * <p>2026-10-19 重新生成过一次：当时 golden 还是上下文瘦身前的 20.8KB/19.3KB，
 * 现行装配结果是 9.9KB/8.1KB，UI 组件那段也从长规则换成精简指引
 * （长规则挪进 ui_component 工具定义，见 {@code OnlinePromptBuilder} 的"上下文瘦身"注释）。
 * 瘦身本身是有意为之，所以这条用 {@link #uiComponentGuidanceStaysSlim()} 钉住：
 * 长规则段一旦被塞回系统提示词，就该红。
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
        assertSectionOrder(b.buildSystemPrompt(),
                "【角色】", "【工具使用规范】", "【工具发现】", "【工具策略】",
                "【知识库】", "【UI 组件】", "【长期记忆管理】", "【任务状态跟踪】",
                "【多模态边界】", "【执行规范】", "【图片生成】", "【输出要求】", "【推理能力】");
    }

    @Test
    public void takeoverSectionsInRegisteredOrder() {
        OnlinePromptBuilder b = new OnlinePromptBuilder(null);
        assertSectionOrder(b.buildSystemPromptTakeover(),
                "【角色】", "【工具发现】", "【调用规则】", "【工具策略】",
                "【知识库】", "【自主决策权限】", "【输出要求】", "【UI 组件】",
                "【长期记忆管理】", "【执行规范】");
        // guide=null 时不应有【可用工具】段
        assertFalse(b.buildSystemPromptTakeover().contains("【可用工具】"));
    }

    /**
     * 上下文瘦身契约：UI 组件在系统提示词里只留一段精简指引，
     * 完整的"按需选型，禁止乱猜"长规则属于 ui_component 工具定义，不该回到系统提示词里
     * （它同时出现在 assist 与 takeover 两条链路上，是每次请求都要付的 token）。
     */
    @Test
    public void uiComponentGuidanceStaysSlim() {
        OnlinePromptBuilder b = new OnlinePromptBuilder(null);
        String longRules = "【UI 组件使用规则 —— 按需选型，禁止乱猜】";
        assertFalse("UI 组件长规则不应再进系统提示词（已挪进工具定义）",
                b.buildSystemPrompt().contains(longRules));
        assertFalse("UI 组件长规则不应再进接管提示词",
                b.buildSystemPromptTakeover().contains(longRules));
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

    /** 段标题都要存在，且相对顺序与注册顺序一致 */
    private static void assertSectionOrder(String prompt, String... titles) {
        int last = -1;
        for (String t : titles) {
            int i = prompt.indexOf(t);
            assertTrue("缺少段标题 " + t, i >= 0);
            assertTrue("段顺序错误：" + t + " 出现在 " + i + "，但上一段在 " + last, i > last);
            last = i;
        }
    }
}
