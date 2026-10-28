package com.oilquiz.app.ai.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引导显示名翻译器的口径（2026-10-28 第二步）。
 *
 * <p>要钉住的是"只翻显示、不动下发"这条线：动态兜底
 * （{@code ToolGuideFlow.buildDynamicFlow}）把 schema 的枚举原文当 label 塞进选项卡，
 * 用户于是看到 {@code list}/{@code get}/{@code air_quality}。翻译层补中文，
 * 但一旦碰了 {@code value}，工具侧就会收到一个它认不出来的操作名——
 * 所以本类最关键的一条断言不是"翻出来了"，而是"翻完 value 一个字节都没变"。</p>
 *
 * <p>纯 JVM 用例，不需要 Context：{@link ToolGuideTerms} 是值对象，
 * {@link ToolGuideLabels} 是纯函数。接进 {@code buildDynamicFlow} 那一段要真机验
 * （Robolectric 下 {@code SmartQuizApplication.getAppContext()} 是 null，
 * 见 {@code src/test/resources/robolectric.properties} 第 3 条）。</p>
 */
public class ToolGuideLabelsTest {

    // ==================== 核心安全线 ====================

    @Test
    public void translatesRawEnumLabelsButNeverTouchesValues() {
        ToolGuideFlow flow = dynamicLikeFlow("knowledge_base", "action",
                "list", "get", "search", "add", "add_batch", "update",
                "import_json", "import_file", "import_document", "delete", "clear", "stats");
        List<String> valuesBefore = valuesOf(flow, 0);

        ToolGuideLabels.Result result = ToolGuideLabels.applyTo(flow, termsWithKbActions());

        assertEquals("12 个动作都该翻出来", 12, result.translatedOptions);
        assertEquals(0, result.untranslatedOptions);
        for (int i = 0; i < 12; i++) {
            assertNotEqualsAt(flow, i, valuesBefore.get(i));
        }
        // 最要紧的一条：下发值必须与 schema 原文逐字相同，否则工具侧报"未知操作"
        assertEquals(valuesBefore, valuesOf(flow, 0));
        // 抽查几个中文标签，确认取的是知识库自己描述里的说法而不是随手起的名字
        assertEquals("列索引", flow.steps.get(0).options.get(0).label);
        assertEquals("取单条", flow.steps.get(0).options.get(1).label);
        assertEquals("导入文档", flow.steps.get(0).options.get(8).label);
    }

    @Test
    public void neverOverwritesAnOptionThatAlreadyHasItsOwnLabel() {
        // 手写 builder 的形状：label 是中文、value 是枚举原文。即使全局词条里有同一个值，也不能盖
        ToolGuideFlow flow = new ToolGuideFlow("network_search", "联网搜索", "",
                Collections.singletonList(optionStep("action",
                        new ToolGuideFlow.GuideStep.Option("搜一下", "search"))));
        ToolGuideFlowConfig.Terms raw = new ToolGuideFlowConfig.Terms();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("search", "搜索"); // 全局档
        raw.values = values;

        ToolGuideLabels.Result result = ToolGuideLabels.applyTo(flow, ToolGuideTerms.fromConfig(raw));

        assertEquals("已有中文 label 的选项不该被动", 0, result.translatedOptions);
        assertEquals("搜一下", flow.steps.get(0).options.get(0).label);
        assertEquals("search", flow.steps.get(0).options.get(0).value);
    }

    @Test
    public void curatedBuildersAreLeftCompletelyUntouchedByRealSeededTerms() {
        // 用随包那份真词典（含 search/ask/read_url 全局词条）跑三个手写流程，
        // 证明"接了翻译层"对已覆盖的 13 个工具是零改动
        ToolGuideTerms terms = ToolGuideTerms.fromConfig(seedLikeShippedTerms());
        for (String toolName : new String[]{"network_search", "database", "ai_weather"}) {
            ToolGuideFlow built = ToolGuideFlow.getFlow(toolName);
            assertNotNull("builder 侧应有 " + toolName, built);
            List<String> labelsBefore = allLabels(built);
            ToolGuideLabels.Result result = ToolGuideLabels.applyTo(built, terms);
            assertEquals(toolName + " 的手写 label 不该被改", 0, result.translatedOptions);
            assertEquals(toolName + " 的 label 序列不该变", labelsBefore, allLabels(built));
        }
    }

    // ==================== 查找优先级与边界 ====================

    @Test
    public void toolLevelTermWinsOverGlobalAndGlobalWinsOverNothing() {
        ToolGuideTerms terms = ToolGuideTerms.fromConfig(seedLikeShippedTerms());

        assertEquals("列索引", terms.valueLabel("knowledge_base", "action", "list"));
        assertEquals("列出", terms.valueLabel("memory", "action", "list"));
        // 没进工具级的工具 → 落全局档
        assertEquals("读网页", terms.valueLabel("webpage_reader", "action", "read_url"));
        // 两处都没有 → null，由调用方保持原文
        assertNull(terms.valueLabel("some_tool", "action", "totally_new_value"));
    }

    @Test
    public void unknownValueStaysRawAndIsCountedSoTheGapIsVisible() {
        ToolGuideFlow flow = dynamicLikeFlow("knowledge_base", "action", "list", "export_pdf");

        ToolGuideLabels.Result result = ToolGuideLabels.applyTo(flow, termsWithKbActions());

        assertEquals(1, result.translatedOptions);
        assertEquals("没词条的个数要能被统计出来，才知道还差多少", 1, result.untranslatedOptions);
        assertEquals("export_pdf", flow.steps.get(0).options.get(1).label);
        assertEquals("export_pdf", flow.steps.get(0).options.get(1).value);
    }

    @Test
    public void emptyValueIsNeverTreatedAsATranslatableToken() {
        // 空串是"不传该参数"的有意语义（天气"📍 自动定位"），不能被当成一个词去查
        ToolGuideFlow flow = new ToolGuideFlow("ai_weather", "天气查询", "",
                Collections.singletonList(optionStep("city",
                        new ToolGuideFlow.GuideStep.Option("📍 自动定位", ""))));
        ToolGuideFlowConfig.Terms raw = new ToolGuideFlowConfig.Terms();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("", "空"); // 就算有人这么写，也不该生效
        raw.values = values;

        ToolGuideLabels.Result result = ToolGuideLabels.applyTo(flow, ToolGuideTerms.fromConfig(raw));

        assertEquals(0, result.translatedOptions);
        assertEquals("📍 自动定位", flow.steps.get(0).options.get(0).label);
    }

    @Test
    public void emptyTermsLeaveEverythingAsIs() {
        ToolGuideFlow flow = dynamicLikeFlow("knowledge_base", "action", "list", "get");
        List<String> labelsBefore = allLabels(flow);

        ToolGuideLabels.Result result = ToolGuideLabels.applyTo(flow, ToolGuideTerms.EMPTY);

        assertEquals(0, result.translatedOptions);
        assertEquals(labelsBefore, allLabels(flow));
        // 装载失败时走的就是这个档：界面维持今天的行为，不会因为一份坏 JSON 变得更差
    }

    @Test
    public void nullFlowAndNullTermsAreSafe() {
        assertEquals(0, ToolGuideLabels.applyTo(null, ToolGuideTerms.EMPTY).translatedOptions);
        assertEquals(0, ToolGuideLabels.applyTo(
                dynamicLikeFlow("knowledge_base", "action", "list"), null).translatedOptions);
    }

    // ==================== 参数标题 ====================

    @Test
    public void rewritesRawParamTitleButOnlyTheGeneratedOne() {
        ToolGuideFlow flow = new ToolGuideFlow("file_reader", "文件阅读", "", Arrays.asList(
                inputStep("填写 file_path", "file_path"),
                inputStep("按什么分类浏览?", "category"),
                inputStep("填写 no_term_here", "no_term_here")));

        ToolGuideFlowConfig.Terms raw = new ToolGuideFlowConfig.Terms();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("file_path", "文件路径");
        params.put("category", "分类"); // 有词条，但标题不是生成的那种 → 不该动
        raw.params = params;

        ToolGuideLabels.Result result = ToolGuideLabels.applyTo(flow, ToolGuideTerms.fromConfig(raw));

        assertEquals(1, result.translatedTitles);
        assertEquals("文件路径", flow.steps.get(0).title);
        assertEquals("按什么分类浏览?", flow.steps.get(1).title);
        assertEquals("填写 no_term_here", flow.steps.get(2).title);
        // paramKey 是下发依据，标题变了它也不能变
        assertEquals("file_path", flow.steps.get(0).paramKey);
    }

    // ==================== 构造 ====================

    /** 造一个"长得像动态兜底产物"的流程：label 直接等于枚举原文。 */
    private static ToolGuideFlow dynamicLikeFlow(String toolName, String paramKey, String... values) {
        List<ToolGuideFlow.GuideStep.Option> options = new ArrayList<>();
        for (String value : values) {
            options.add(new ToolGuideFlow.GuideStep.Option(value, value));
        }
        return new ToolGuideFlow(toolName, toolName, "",
                Collections.singletonList(optionStep(paramKey, options.toArray(
                        new ToolGuideFlow.GuideStep.Option[0]))));
    }

    private static ToolGuideFlow.GuideStep optionStep(
            String paramKey, ToolGuideFlow.GuideStep.Option... options) {
        ToolGuideFlow.GuideStep step = new ToolGuideFlow.GuideStep();
        step.type = ToolGuideFlow.GuideStep.StepType.OPTION;
        step.title = "操作类型";
        step.paramKey = paramKey;
        step.options = new ArrayList<>(Arrays.asList(options));
        return step;
    }

    private static ToolGuideFlow.GuideStep inputStep(String title, String paramKey) {
        ToolGuideFlow.GuideStep step = new ToolGuideFlow.GuideStep();
        step.type = ToolGuideFlow.GuideStep.StepType.INPUT;
        step.title = title;
        step.paramKey = paramKey;
        step.hint = "提示";
        return step;
    }

    /** 与随包档 knowledge_base 段同形的词典（12 个动作，取自该工具自己的描述文案）。 */
    private static ToolGuideTerms termsWithKbActions() {
        return ToolGuideTerms.fromConfig(seedLikeShippedTerms());
    }

    private static ToolGuideFlowConfig.Terms seedLikeShippedTerms() {
        ToolGuideFlowConfig.Terms terms = new ToolGuideFlowConfig.Terms();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("search", "搜索");
        values.put("ask", "问答");
        values.put("read_url", "读网页");
        terms.values = values;
        Map<String, Map<String, Map<String, String>>> byTool = new LinkedHashMap<>();
        Map<String, Map<String, String>> kb = new LinkedHashMap<>();
        Map<String, String> kbAction = new LinkedHashMap<>();
        kbAction.put("list", "列索引");
        kbAction.put("get", "取单条");
        kbAction.put("search", "检索");
        kbAction.put("add", "添加");
        kbAction.put("add_batch", "批量添加");
        kbAction.put("update", "修改");
        kbAction.put("import_json", "导入JSON");
        kbAction.put("import_file", "导入JSON文件");
        kbAction.put("import_document", "导入文档");
        kbAction.put("delete", "删除");
        kbAction.put("clear", "清空");
        kbAction.put("stats", "统计");
        kb.put("action", kbAction);
        Map<String, Map<String, String>> memory = new LinkedHashMap<>();
        Map<String, String> memoryAction = new LinkedHashMap<>();
        memoryAction.put("list", "列出");
        memory.put("action", memoryAction);
        byTool.put("knowledge_base", kb);
        byTool.put("memory", memory);
        terms.byTool = byTool;
        return terms;
    }

    private static List<String> valuesOf(ToolGuideFlow flow, int stepIndex) {
        List<String> out = new ArrayList<>();
        for (ToolGuideFlow.GuideStep.Option option : flow.steps.get(stepIndex).options) {
            out.add(option.value);
        }
        return out;
    }

    private static List<String> allLabels(ToolGuideFlow flow) {
        List<String> out = new ArrayList<>();
        for (ToolGuideFlow.GuideStep step : flow.steps) {
            out.add(step.title);
            if (step.options != null) {
                for (ToolGuideFlow.GuideStep.Option option : step.options) {
                    out.add(option.label);
                }
            }
        }
        return out;
    }

    private static void assertNotEqualsAt(ToolGuideFlow flow, int optionIndex, String originalLabel) {
        String now = flow.steps.get(0).options.get(optionIndex).label;
        assertTrue("第 " + optionIndex + " 个选项的 label 该被翻掉，却还是原文 " + now,
                !originalLabel.equals(now));
    }
}
