package com.oilquiz.app.ai.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.openai.ParamDefinition;
import com.oilquiz.app.ai.tool.openai.ToolDefinition;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具引导配置 JSON schema 草稿与校验器的验证（2026-10-28 设计阶段）。
 *
 * <p>三件事，缺一不可：</p>
 * <ol>
 *   <li><b>随包那份真文件过校验器无错</b>：读的是 {@code src/main/assets/tool_guide_flow.json}
 *       本身（经 {@link ToolGuideConfigLoader}），不是测试目录里的副本——两份文件会漂，
 *       验副本等于没验。</li>
 *   <li><b>草稿与现有 builder 逐字段相等</b>：这是"搬数据不改行为"的唯一硬证据。
 *       只比字面量不够——漏一个 option、错一个 condition 都会让引导界面静默变形。</li>
 *   <li><b>校验器真会响</b>：每条规则都用坏配置证一遍。仪器不响，前面两条的"绿"没意义。</li>
 * </ol>
 *
 * <p>另外 {@link #actionParamEnumsAreNotDeclaredOnTheHardcodedSwitchPath()} 是一条
 * 特征化断言（characterization test），记录当前"枚举只在 description 文案里、第 3 档枚举校验
 * 只能降级为 WARN"这个事实。将来给 AIToolManager 那几个 switch 分支补上 enumValues 时，
 * 这条会红——那时应把它改成硬校验断言，并同步把校验器里的 WARN 分支删掉。</p>
 *
 * <p>跑 Robolectric 是为了能读到 assets 里的真文件；{@code robolectric.properties} 已把
 * Application 换成裸 {@code android.app.Application}，所以本类不依赖 SmartQuizApplication
 * 的启动（Chaquopy 的 .so 在桌面 JVM 不存在）。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ToolGuideFlowValidatorTest {

    /** 随包档文件名，与装载器、assets 里的实际文件同一个名字 */
    private static final String CONFIG_ASSET = "tool_guide_flow.json";

    @Before
    public void dropLoaderCache() {
        // 每个用例都重新从磁盘/装载器读一遍，避免上一个用例的缓存把本次改动盖掉
        ToolGuideConfigLoader.invalidate();
    }

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    // ==================== 1. 草稿无错 ====================

    @Test
    public void draftPassesStructuralAndReferenceRulesWithZeroErrors() {
        ToolGuideFlowConfig config = loadDraft();
        List<ToolGuideFlowValidator.Issue> issues = ToolGuideFlowValidator.validate(config);
        List<ToolGuideFlowValidator.Issue> errors = ToolGuideFlowValidator.errors(issues);
        assertEquals("草稿存在结构性错误：\n" + ToolGuideFlowValidator.render(errors), 0, errors.size());
        // 三份工具流程 + 三个聚合方案，全在
        assertEquals(3, config.tools.size());
        assertEquals(3, config.composites.size());
    }

    @Test
    public void draftWarnsOnlyAboutUnverifiedEnumsNotAboutAnythingBroken() {
        ToolGuideFlowConfig config = loadDraft();
        List<ToolGuideFlowValidator.Issue> issues = ToolGuideFlowValidator.validate(config);
        for (ToolGuideFlowValidator.Issue issue : ToolGuideFlowValidator.warnings(issues)) {
            // 本轮草稿刻意不留任何 WARN：三个工具的字段都齐、都以 CONFIRM 收尾。
            // 若出现 WARN，说明草稿字段退化了，直接把它当失败报出来。
            assertTrue("草稿不该有 WARN: " + issue, false);
        }
    }

    // ==================== 2. 草稿 == 现有 builder ====================

    @Test
    public void draftAiWeatherIsFieldIdenticalToHardcodedBuilder() {
        assertToolMatchesBuilder(loadDraft(), "ai_weather");
    }

    @Test
    public void draftNetworkSearchIsFieldIdenticalToHardcodedBuilder() {
        assertToolMatchesBuilder(loadDraft(), "network_search");
    }

    @Test
    public void draftDatabaseIsFieldIdenticalToHardcodedBuilder() {
        assertToolMatchesBuilder(loadDraft(), "database");
    }

    @Test
    public void draftCompositeGoOutMatchesBuilder() {
        assertCompositeMatchesBuilder(loadDraft(), "go_out");
    }

    @Test
    public void draftCompositeStudyMatchesBuilder() {
        assertCompositeMatchesBuilder(loadDraft(), "study");
    }

    @Test
    public void draftCompositeResearchMatchesBuilder() {
        assertCompositeMatchesBuilder(loadDraft(), "research");
    }

    // ==================== 3. 校验器真会响 ====================

    @Test
    public void rejectsStepTypeThatIsNotAnEnumName() {
        ToolGuideFlowConfig config = configWithTool("ai_weather", step("OPTION", "action"));
        ToolGuideFlowConfig.Step bad = config.tools.get(0).steps.get(0);
        bad.type = "option"; // 小写别名，本项目刻意不接受
        assertErrorContaining(ToolGuideFlowValidator.validate(config), "不是合法步骤类型");
    }

    @Test
    public void rejectsNonConfirmStepWithoutParamKey() {
        ToolGuideFlowConfig.Step step = step("INPUT", null);
        step.hint = "随便";
        assertErrorContaining(ToolGuideFlowValidator.validate(configWithTool("database", step)),
                "缺少 paramKey");
    }

    @Test
    public void rejectsMissingVersionAndEmptyContent() {
        ToolGuideFlowConfig config = new ToolGuideFlowConfig();
        List<ToolGuideFlowValidator.Issue> issues = ToolGuideFlowValidator.validate(config);
        assertHasIssue(issues, ToolGuideFlowValidator.Severity.ERROR, "缺少 version");
        assertHasIssue(issues, ToolGuideFlowValidator.Severity.ERROR, "都是空的");
    }

    @Test
    public void rejectsNullConfig() {
        List<ToolGuideFlowValidator.Issue> issues = ToolGuideFlowValidator.validate(null);
        assertEquals(1, issues.size());
        assertEquals(ToolGuideFlowValidator.Severity.ERROR, issues.get(0).severity);
    }

    @Test
    public void rejectsOptionValueThatIsNotDeclaredInSchemaEnum() {
        // schema 侧明确 action 只能取 current/forecast；草稿式配置写了个不存在的值
        ToolGuideFlowConfig.Step action = step("OPTION", "action");
        action.options = Arrays.asList(option("现在天气", "current"), option("昨天天气", "yesterday"));
        Map<String, ToolGuideFlowValidator.ParamSpec> params = new LinkedHashMap<>();
        params.put("action", ToolGuideFlowValidator.paramSpec(false, "current", "forecast"));

        List<ToolGuideFlowValidator.Issue> issues = ToolGuideFlowValidator.validate(
                configWithTool("ai_weather", action, confirm()), enumProbe("ai_weather", params));

        assertErrorContaining(issues, "value=\"yesterday\" 不在参数 \"action\" 声明的枚举里");
    }

    @Test
    public void degradesToWarnWhenSchemaDeclaresNoEnumForTheParam() {
        ToolGuideFlowConfig.Step action = step("OPTION", "action");
        action.options = Collections.singletonList(option("现在天气", "current"));
        Map<String, ToolGuideFlowValidator.ParamSpec> params = new LinkedHashMap<>();
        params.put("action", ToolGuideFlowValidator.paramSpec(false)); // enumValues=null

        List<ToolGuideFlowValidator.Issue> issues = ToolGuideFlowValidator.validate(
                configWithTool("ai_weather", action, confirm()), enumProbe("ai_weather", params));

        assertEquals("该场景只该出一条 WARN，不该有 ERROR",
                0, ToolGuideFlowValidator.errors(issues).size());
        assertEquals(1, ToolGuideFlowValidator.warnings(issues).size());
        assertTrue(ToolGuideFlowValidator.warnings(issues).get(0).message.contains("没声明枚举值"));
    }

    @Test
    public void rejectsParamKeyAbsentFromModelVisibleSchema() {
        ToolGuideFlowConfig.Step city = step("OPTION", "cit y"); // 手滑打错的典型形态
        city.options = Collections.singletonList(option("北京", "北京"));
        Map<String, ToolGuideFlowValidator.ParamSpec> params = new LinkedHashMap<>();
        params.put("action", ToolGuideFlowValidator.paramSpec(false, "current"));

        List<ToolGuideFlowValidator.Issue> issues = ToolGuideFlowValidator.validate(
                configWithTool("ai_weather", city, confirm()), enumProbe("ai_weather", params));

        assertErrorContaining(issues, "cit y\" 不在该工具的模型可见 schema 参数里");
    }

    @Test
    public void warnsWhenToolIsUnknownToSchemaInsteadOfSilentlyPassing() {
        ToolGuideFlowConfig.Step action = step("OPTION", "action");
        action.options = Collections.singletonList(option("现在天气", "current"));
        List<ToolGuideFlowValidator.Issue> issues = ToolGuideFlowValidator.validate(
                configWithTool("no_such_tool", action, confirm()),
                ToolGuideFlowValidator.probeFromParamNames(
                        Collections.<String, Set<String>>emptyMap()));
        assertHasIssue(issues, ToolGuideFlowValidator.Severity.WARN, "schema 里查不到工具");
    }

    @Test
    public void rejectsConditionKeyThatNoEarlierOptionProduces() {
        ToolGuideFlowConfig.Step orphan = step("INPUT", "query");
        orphan.hint = "搜什么";
        orphan.condition = condition("action", "search"); // 前面根本没有 action 选项步
        assertErrorContaining(
                ToolGuideFlowValidator.validate(configWithTool("network_search", orphan, confirm())),
                "在此之前没有对应的 OPTION 步骤");
    }

    @Test
    public void rejectsConditionDependingOnItsOwnParamKey() {
        ToolGuideFlowConfig.Step step = step("OPTION", "action");
        step.options = Collections.singletonList(option("搜一下", "search"));
        step.condition = condition("action", "search");
        assertErrorContaining(ToolGuideFlowValidator.validate(configWithTool("network_search", step)),
                "不能是本步骤自己的 paramKey");
    }

    @Test
    public void rejectsEmptyConditionValuesWhichCouldNeverMatch() {
        ToolGuideFlowConfig.Step action = step("OPTION", "action");
        action.options = Collections.singletonList(option("搜一下", "search"));
        ToolGuideFlowConfig.Step query = step("INPUT", "query");
        query.hint = "搜什么";
        query.condition = condition("action"); // values 为空
        assertErrorContaining(
                ToolGuideFlowValidator.validate(configWithTool("network_search", action, query, confirm())),
                "没有值能命中");
    }

    @Test
    public void rejectsTwoStepsFightingForTheSameParamKey() {
        ToolGuideFlowConfig.Step a = step("INPUT", "file_name");
        a.hint = "文件名";
        a.condition = condition("action", "create", "append");
        ToolGuideFlowConfig.Step b = step("INPUT", "file_name");
        b.hint = "目标文件名";
        b.condition = condition("action", "append"); // 与 a 的值集相交
        ToolGuideFlowConfig.Step action = step("OPTION", "action");
        action.options = Arrays.asList(option("创建", "create"), option("追加", "append"));
        List<ToolGuideFlowValidator.Issue> issues = ToolGuideFlowValidator.validate(
                configWithTool("file_generator", action, a, b, confirm()));
        assertErrorContaining(issues, "两个条件重叠的步骤产出");
    }

    @Test
    public void acceptsTwoStepsSharingParamKeyWhenConditionsAreDisjoint() {
        // file_generator 的真实形状：file_name 在 create 系与 copy 系各出现一次，条件值集不相交
        ToolGuideFlowConfig.Step action = step("OPTION", "action");
        action.options = Arrays.asList(option("创建", "create"), option("复制", "copy"));
        ToolGuideFlowConfig.Step a = step("INPUT", "file_name");
        a.hint = "文件名";
        a.condition = condition("action", "create");
        ToolGuideFlowConfig.Step b = step("INPUT", "file_name");
        b.hint = "目标文件名";
        b.condition = condition("action", "copy");
        List<ToolGuideFlowValidator.Issue> issues = ToolGuideFlowValidator.validate(
                configWithTool("file_generator", action, a, b, confirm()));
        assertEquals("条件不相交的同名步骤不该被拦：\n" + ToolGuideFlowValidator.render(issues),
                0, ToolGuideFlowValidator.errors(issues).size());
    }

    @Test
    public void rejectsDuplicateOptionValueWithinOneStepIncludingTwoBlanks() {
        ToolGuideFlowConfig.Step step = step("OPTION", "city");
        step.options = Arrays.asList(option("自动定位", ""), option("也自动", ""));
        assertErrorContaining(ToolGuideFlowValidator.validate(configWithTool("ai_weather", step)),
                "两个空串也算重复");
    }

    @Test
    public void rejectsNullOptionValueButAcceptsEmptyStringAsDoNotSendParam() {
        ToolGuideFlowConfig.Step step = step("OPTION", "city");
        step.options = Collections.singletonList(option("自动定位", null));
        assertErrorContaining(ToolGuideFlowValidator.validate(configWithTool("ai_weather", step)),
                "null 与空串语义不同");

        ToolGuideFlowConfig.Step ok = step("OPTION", "city");
        ok.options = Collections.singletonList(option("自动定位", ""));
        assertEquals(0, ToolGuideFlowValidator.errors(
                ToolGuideFlowValidator.validate(configWithTool("ai_weather", ok, confirm()))).size());
    }

    @Test
    public void rejectsDynamicOptionsWithoutSourceTriple() {
        ToolGuideFlowConfig.Step step = step("INPUT", "category");
        step.hint = "分类";
        ToolGuideFlowConfig.DynamicOptions dynamic = new ToolGuideFlowConfig.DynamicOptions();
        dynamic.toolName = "database";
        dynamic.action = "";  // 没写 action
        dynamic.listKey = "categories";
        step.dynamicOptions = dynamic;
        assertErrorContaining(ToolGuideFlowValidator.validate(configWithTool("database", step)),
                "dynamicOptions.action 为空");
    }

    @Test
    public void rejectsMalformedPrevRefAndCarriesOnWithGoodOnes() {
        ToolGuideFlowConfig.CompositeFlow flow = new ToolGuideFlowConfig.CompositeFlow();
        flow.flowId = "go_out";
        flow.displayName = "出行准备";
        flow.icon = "🚗";
        ToolGuideFlowConfig.CompositeStep step = new ToolGuideFlowConfig.CompositeStep();
        step.toolName = "ai_weather";
        step.actionDescription = "查当前天气";
        step.fixedParams = Collections.singletonMap("action", "current");
        Map<String, String> refs = new LinkedHashMap<>();
        refs.put("lat", "prev.lat"); // 少了 $
        refs.put("lon", "$prev.lon");
        step.paramRefs = refs;
        flow.steps = Collections.singletonList(step);
        ToolGuideFlowConfig config = new ToolGuideFlowConfig();
        config.version = "test";
        config.composites = Collections.singletonList(flow);
        assertErrorContaining(ToolGuideFlowValidator.validate(config), "只支持 $prev.<字段> 形式");
    }

    @Test
    public void warnsOnAutoExecuteStepThatStillCarriesGuideSteps() {
        ToolGuideFlowConfig.CompositeFlow flow = new ToolGuideFlowConfig.CompositeFlow();
        flow.flowId = "go_out";
        flow.displayName = "出行准备";
        flow.icon = "🚗";
        ToolGuideFlowConfig.CompositeStep step = new ToolGuideFlowConfig.CompositeStep();
        step.toolName = "location";
        step.actionDescription = "获取当前位置";
        step.autoExecute = true;
        step.guideSteps = Collections.singletonList(step("INPUT", "keyword"));
        flow.steps = Collections.singletonList(step);
        ToolGuideFlowConfig config = new ToolGuideFlowConfig();
        config.version = "test";
        config.composites = Collections.singletonList(flow);
        assertHasIssue(ToolGuideFlowValidator.validate(config),
                ToolGuideFlowValidator.Severity.WARN, "这些步骤是死配置");
    }

    @Test
    public void rejectsDuplicateFlowIdAndMissingDisplayNameWhichChipEntrypointNeeds() {
        ToolGuideFlowConfig config = new ToolGuideFlowConfig();
        config.version = "test";
        ToolGuideFlowConfig.CompositeFlow a = new ToolGuideFlowConfig.CompositeFlow();
        a.flowId = "go_out";
        a.displayName = "出行准备";
        a.icon = "🚗";
        a.steps = Collections.singletonList(compositeStep("location"));
        ToolGuideFlowConfig.CompositeFlow b = new ToolGuideFlowConfig.CompositeFlow();
        b.flowId = "go_out";
        b.steps = Collections.singletonList(compositeStep("location"));
        config.composites = Arrays.asList(a, b);
        List<ToolGuideFlowValidator.Issue> issues = ToolGuideFlowValidator.validate(config);
        assertErrorContaining(issues, "flowId 重复");
        assertErrorContaining(issues, "displayName 为空");
    }

    @Test
    public void warnsWhenToolFlowDoesNotEndWithConfirmStep() {
        ToolGuideFlowConfig.Step action = step("OPTION", "action");
        action.options = Collections.singletonList(option("现在天气", "current"));
        assertHasIssue(ToolGuideFlowValidator.validate(configWithTool("ai_weather", action)),
                ToolGuideFlowValidator.Severity.WARN, "最后一个步骤不是 CONFIRM");
    }

    // ==================== 4. 现状特征化：switch 分支没带枚举 ====================

    @Test
    public void actionParamEnumsAreNotDeclaredOnTheHardcodedSwitchPath() {
        // 这四个工具都命中 getToolDefinition 的 switch 分支，那些分支用四参 addParameter，
        // 所以 ParamDefinition.enumValues 是 null：枚举只活在 description 文案里。
        // PARAM_ENUM_OVERRIDES 只在注解路径（createToolDefinitionFromAITool）生效，
        // 覆盖不到 switch 分支——AIToolManager:2089 那段"location 无 switch 定义"的注释已过期
        // （:1417 就有 case "location":）。
        for (String toolName : new String[]{"ai_weather", "network_search", "database", "location"}) {
            ParamDefinition action = paramOf(toolName, "action");
            assertNotNull(toolName + " 应该有 action 参数", action);
            assertTrue(toolName + " 的 action 枚举目前未被结构化声明（校验器第 3 档只能 WARN）",
                    action.getEnumValues() == null || action.getEnumValues().isEmpty());
        }
    }

    @Test
    public void draftAgainstRealModelVisibleSchemaPassesParamKeyCheck() {
        // 用真 schema 当探针跑一遍草稿：paramKey 全部对得上（枚举侧目前只能 WARN）
        ToolGuideFlowConfig config = loadDraft();
        List<ToolGuideFlowValidator.Issue> issues =
                ToolGuideFlowValidator.validate(config, realSchemaProbe());
        List<ToolGuideFlowValidator.Issue> errors = ToolGuideFlowValidator.errors(issues);
        assertEquals("草稿的 paramKey 与模型可见 schema 不一致：\n"
                + ToolGuideFlowValidator.render(errors), 0, errors.size());
    }

    // ==================== 术语表（翻译器）====================

    @Test
    public void shippedTermsCoverEveryDeclaredActionValueOfKnowledgeBase() {
        // knowledge_base 是唯一在 switch 分支里声明了 action 枚举、又没有手写精细引导的工具，
        // 所以它的 12 个动作今天就会以原始值出现在选项卡上。随包术语表必须全覆盖，
        // 否则以后有人加一个 action 却没加词条，这条会红。
        ToolGuideTerms terms = ToolGuideConfigLoader.terms(ctx());
        ParamDefinition action = paramOf("knowledge_base", "action");
        assertNotNull("knowledge_base 的 action 应带声明的枚举值（否则本断言的前提不成立）", action);
        assertNotNull(action.getEnumValues());
        assertTrue("该工具的 action 枚举本应已结构化声明", action.getEnumValues().size() > 0);
        for (String value : action.getEnumValues()) {
            assertNotNull("知识库里动作 \"" + value + "\" 没有中文词条，界面会继续露原始值",
                    terms.valueLabel("knowledge_base", "action", value));
        }
    }

    @Test
    public void loaderRejectsConfigWithErrorsAndKeepsCurrentLabels() throws Exception {
        // 写坏的覆盖档必须整份拒绝（而不是半套用），界面维持今天的行为
        java.io.File override = new java.io.File(ctx().getFilesDir(), "tool_guide_flow.json");
        java.io.Writer writer = new java.io.FileWriter(override);
        try {
            writer.write("{\"version\":\"broken\",\"tools\":[{\"toolName\":\"broken_tool\","
                    + "\"toolDisplayName\":\"坏配置\",\"steps\":[{\"type\":\"INPUT\",\"title\":\"没有paramKey\"}]}]}");
        } finally {
            writer.close();
        }
        try {
            ToolGuideConfigLoader.invalidate();
            assertNull("有 ERROR 的配置应被整份拒绝", ToolGuideConfigLoader.config(ctx()));
            assertTrue("拒绝后术语表必须退回空词典（保持原始字段名）",
                    ToolGuideConfigLoader.terms(ctx()).isEmpty());
        } finally {
            assertTrue("覆盖档没删掉，会污染后续用例", override.delete());
            ToolGuideConfigLoader.invalidate();
        }
    }

    @Test
    public void loaderServesTheShippedAssetFile() {
        ToolGuideFlowConfig config = ToolGuideConfigLoader.config(ctx());
        assertNotNull("随包档应能被装载器读到并通过校验", config);
        assertEquals("2026-10-28-v2", config.version);
        assertNotNull("随包档应带术语表段", config.terms);
        assertTrue("术语表不该是空的", !ToolGuideConfigLoader.terms(ctx()).isEmpty());
    }

    @Test
    public void rejectsTermEntryWithBlankChineseBecauseBlankIsWorseThanRaw() {
        ToolGuideFlowConfig config = configWithTool("database", confirm());
        ToolGuideFlowConfig.Terms terms = new ToolGuideFlowConfig.Terms();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("get_questions", "   ");
        terms.values = values;
        config.terms = terms;
        assertErrorContaining(ToolGuideFlowValidator.validate(config), "中文名为空");
    }

    @Test
    public void rejectsTermKeyedOnEmptyStringWhichMeansDoNotSendParam() {
        ToolGuideFlowConfig config = configWithTool("database", confirm());
        ToolGuideFlowConfig.Terms terms = new ToolGuideFlowConfig.Terms();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("", "全部");
        terms.values = values;
        config.terms = terms;
        assertErrorContaining(ToolGuideFlowValidator.validate(config), "空串表示\"不传该参数\"");
    }

    @Test
    public void warnsWhenATermTranslatesAValueToItself() {
        ToolGuideFlowConfig config = configWithTool("database", confirm());
        ToolGuideFlowConfig.Terms terms = new ToolGuideFlowConfig.Terms();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("list_tables", "list_tables");
        terms.values = values;
        config.terms = terms;
        assertHasIssue(ToolGuideFlowValidator.validate(config),
                ToolGuideFlowValidator.Severity.WARN, "等于没翻");
    }

    @Test
    public void absentTermsSectionIsALegalConfig() {
        ToolGuideFlowConfig config = configWithTool("database", confirm());
        config.terms = null;
        assertEquals("没有术语表是合法档（动态兜底维持原文）",
                0, ToolGuideFlowValidator.errors(ToolGuideFlowValidator.validate(config)).size());
    }

    // ==================== 比对：草稿 vs builder ====================

    private void assertToolMatchesBuilder(ToolGuideFlowConfig config, String toolName) {
        ToolGuideFlow built = ToolGuideFlow.getFlow(toolName);
        assertNotNull("builder 侧没有该工具流程: " + toolName, built);
        ToolGuideFlowConfig.ToolFlow draft = findTool(config, toolName);
        assertNotNull("草稿里没有该工具流程: " + toolName, draft);

        List<String> diffs = new ArrayList<>();
        compare(diffs, "toolDisplayName", built.toolDisplayName, draft.toolDisplayName);
        compare(diffs, "toolDescription", built.toolDescription, draft.toolDescription);
        compare(diffs, "contextDependencies", built.contextDependencies, draft.contextDependencies);
        if (built.steps == null || draft.steps == null
                || built.steps.size() != draft.steps.size()) {
            diffs.add("steps.size builder=" + (built.steps == null ? 0 : built.steps.size())
                    + " draft=" + (draft.steps == null ? 0 : draft.steps.size()));
        } else {
            for (int i = 0; i < built.steps.size(); i++) {
                compareStep(diffs, "steps[" + i + "]", built.steps.get(i), draft.steps.get(i));
            }
        }
        assertEquals(toolName + " 的草稿与现有 builder 不一致：\n  " + join(diffs), 0, diffs.size());
    }

    private void assertCompositeMatchesBuilder(ToolGuideFlowConfig config, String flowId) {
        CompositeGuideFlow built = CompositeGuideFlow.getFlow(flowId);
        assertNotNull("builder 侧没有该聚合方案: " + flowId, built);
        ToolGuideFlowConfig.CompositeFlow draft = findComposite(config, flowId);
        assertNotNull("草稿里没有该聚合方案: " + flowId, draft);

        List<String> diffs = new ArrayList<>();
        compare(diffs, "displayName", built.displayName, draft.displayName);
        compare(diffs, "description", built.description, draft.description);
        compare(diffs, "icon", built.icon, draft.icon);
        if (built.steps == null || draft.steps == null || built.steps.size() != draft.steps.size()) {
            diffs.add("steps.size builder=" + (built.steps == null ? 0 : built.steps.size())
                    + " draft=" + (draft.steps == null ? 0 : draft.steps.size()));
        } else {
            for (int i = 0; i < built.steps.size(); i++) {
                String loc = flowId + ".steps[" + i + "]";
                CompositeGuideFlow.CompositeStep b = built.steps.get(i);
                ToolGuideFlowConfig.CompositeStep d = draft.steps.get(i);
                compare(diffs, loc + ".toolName", b.toolName, d.toolName);
                compare(diffs, loc + ".actionDescription", b.actionDescription, d.actionDescription);
                compare(diffs, loc + ".icon", b.icon, d.icon);
                compare(diffs, loc + ".autoExecute", b.autoExecute, Boolean.TRUE.equals(d.autoExecute));
                compareMap(diffs, loc + ".fixedParams", b.fixedParams, d.fixedParams);
                compareMap(diffs, loc + ".paramRefs", b.paramRefs, d.paramRefs);
                int bGuide = b.guideSteps == null ? 0 : b.guideSteps.size();
                int dGuide = d.guideSteps == null ? 0 : d.guideSteps.size();
                if (bGuide != dGuide) {
                    diffs.add(loc + ".guideSteps.size builder=" + bGuide + " draft=" + dGuide);
                } else {
                    for (int g = 0; g < bGuide; g++) {
                        compareStep(diffs, loc + ".guideSteps[" + g + "]", b.guideSteps.get(g), d.guideSteps.get(g));
                    }
                }
            }
        }
        assertEquals(flowId + " 的草稿与现有 builder 不一致：\n  " + join(diffs), 0, diffs.size());
    }

    /** 逐字段比对单个步骤；builder 的 conditionKey/conditionValue("|") 与草稿的结构化 condition 归一到值集。 */
    private void compareStep(List<String> diffs, String loc,
                             ToolGuideFlow.GuideStep b, ToolGuideFlowConfig.Step d) {
        if (b == null || d == null) {
            diffs.add(loc + " 一侧为 null");
            return;
        }
        compare(diffs, loc + ".type", b.type == null ? null : b.type.name(), d.type);
        compare(diffs, loc + ".title", b.title, d.title);
        compare(diffs, loc + ".description", b.description, d.description);
        compare(diffs, loc + ".paramKey", b.paramKey, d.paramKey);
        compare(diffs, loc + ".hint", b.hint, d.hint);
        compare(diffs, loc + ".required", b.required, Boolean.TRUE.equals(d.required));
        compare(diffs, loc + ".multiline", b.multiline, Boolean.TRUE.equals(d.multiline));
        compare(diffs, loc + ".allowMultiple", b.allowMultiple, Boolean.TRUE.equals(d.allowMultiple));
        compare(diffs, loc + ".mimeTypes",
                b.mimeTypes == null ? null : Arrays.asList(b.mimeTypes), d.mimeTypes);

        // null 与空列表等价：CONFIRM 步骤两侧都不该有 options，不能因为"某一侧是 null"就报差异
        int bOptionCount = b.options == null ? 0 : b.options.size();
        int dOptionCount = d.options == null ? 0 : d.options.size();
        if (bOptionCount != dOptionCount) {
            diffs.add(loc + ".options.size builder=" + bOptionCount + " draft=" + dOptionCount);
        } else {
            for (int i = 0; i < bOptionCount; i++) {
                compare(diffs, loc + ".options[" + i + "].label", b.options.get(i).label, d.options.get(i).label);
                compare(diffs, loc + ".options[" + i + "].value", b.options.get(i).value, d.options.get(i).value);
            }
        }

        compare(diffs, loc + ".condition.key", emptyToNull(b.conditionKey),
                d.condition == null ? null : d.condition.key);
        compareSet(diffs, loc + ".condition.values", splitConditionValues(b.conditionValue),
                d.condition == null ? null : new LinkedHashSet<>(d.condition.values));

        if (b.dynamicOptions == null || d.dynamicOptions == null) {
            if (b.dynamicOptions != null || d.dynamicOptions != null) {
                diffs.add(loc + ".dynamicOptions 一侧缺失 builder="
                        + (b.dynamicOptions != null) + " draft=" + (d.dynamicOptions != null));
            }
        } else {
            compare(diffs, loc + ".dynamicOptions.toolName", b.dynamicOptions.toolName, d.dynamicOptions.toolName);
            compare(diffs, loc + ".dynamicOptions.action", b.dynamicOptions.action, d.dynamicOptions.action);
            compare(diffs, loc + ".dynamicOptions.listKey", b.dynamicOptions.listKey, d.dynamicOptions.listKey);
            compare(diffs, loc + ".dynamicOptions.itemField", b.dynamicOptions.itemField, d.dynamicOptions.itemField);
            compare(diffs, loc + ".dynamicOptions.allOptionLabel",
                    b.dynamicOptions.allOptionLabel, d.dynamicOptions.allOptionLabel);
        }
    }

    // ==================== 探针 ====================

    /** 用真 AIToolManager 的模型可见定义当探针（ToolSchemaVisibilityTest 同款 @Mock Context 姿势）。 */
    private ToolGuideFlowValidator.SchemaProbe realSchemaProbe() {
        return new ToolGuideFlowValidator.SchemaProbe() {
            @Override
            public Map<String, ToolGuideFlowValidator.ParamSpec> paramsOf(String toolName) {
                ToolDefinition definition = AIToolManager.getInstance(ctx()).getToolDefinition(toolName);
                if (definition == null || definition.getParameters() == null) {
                    return null;
                }
                Map<String, ToolGuideFlowValidator.ParamSpec> out = new LinkedHashMap<>();
                for (ParamDefinition param : definition.getParameters()) {
                    Set<String> enums = param.getEnumValues() == null || param.getEnumValues().isEmpty()
                            ? null
                            : new LinkedHashSet<>(param.getEnumValues());
                    out.put(param.getName(), new ToolGuideFlowValidator.ParamSpec(param.isRequired(), enums));
                }
                return out;
            }
        };
    }

    private ToolGuideFlowValidator.SchemaProbe enumProbe(final String toolName,
                                                         final Map<String, ToolGuideFlowValidator.ParamSpec> params) {
        return new ToolGuideFlowValidator.SchemaProbe() {
            @Override
            public Map<String, ToolGuideFlowValidator.ParamSpec> paramsOf(String name) {
                return toolName.equals(name) ? params : null;
            }
        };
    }

    private ParamDefinition paramOf(String toolName, String paramName) {
        ToolDefinition definition = AIToolManager.getInstance(ctx()).getToolDefinition(toolName);
        assertNotNull("schema 里没有工具 " + toolName, definition);
        for (ParamDefinition param : definition.getParameters()) {
            if (paramName.equals(param.getName())) {
                return param;
            }
        }
        return null;
    }

    // ==================== 构造与断言小工具 ====================

    private ToolGuideFlowConfig loadDraft() {
        ToolGuideFlowConfig config = ToolGuideConfigLoader.config(ctx());
        assertNotNull("装载器读不到随包档 " + CONFIG_ASSET
                + "（三种可能：文件缺失、Gson 解析失败、或校验报了 ERROR 被整份拒绝）", config);
        return config;
    }

    private static ToolGuideFlowConfig configWithTool(String toolName, ToolGuideFlowConfig.Step... steps) {
        ToolGuideFlowConfig.ToolFlow tool = new ToolGuideFlowConfig.ToolFlow();
        tool.toolName = toolName;
        tool.toolDisplayName = "测试工具";
        tool.toolDescription = "测试用";
        tool.steps = Arrays.asList(steps);
        ToolGuideFlowConfig config = new ToolGuideFlowConfig();
        config.version = "test";
        config.tools = Collections.singletonList(tool);
        return config;
    }

    private static ToolGuideFlowConfig.Step step(String type, String paramKey) {
        ToolGuideFlowConfig.Step step = new ToolGuideFlowConfig.Step();
        step.type = type;
        step.paramKey = paramKey;
        step.title = "测试步骤";
        step.description = "测试描述";
        return step;
    }

    private static ToolGuideFlowConfig.Step confirm() {
        ToolGuideFlowConfig.Step step = new ToolGuideFlowConfig.Step();
        step.type = "CONFIRM";
        step.title = "确认执行";
        step.description = "信息无误就点执行";
        return step;
    }

    private static ToolGuideFlowConfig.Option option(String label, String value) {
        ToolGuideFlowConfig.Option option = new ToolGuideFlowConfig.Option();
        option.label = label;
        option.value = value;
        return option;
    }

    private static ToolGuideFlowConfig.Condition condition(String key, String... values) {
        ToolGuideFlowConfig.Condition condition = new ToolGuideFlowConfig.Condition();
        condition.key = key;
        condition.values = values.length == 0
                ? new ArrayList<String>()
                : new ArrayList<>(Arrays.asList(values));
        return condition;
    }

    private static ToolGuideFlowConfig.CompositeStep compositeStep(String toolName) {
        ToolGuideFlowConfig.CompositeStep step = new ToolGuideFlowConfig.CompositeStep();
        step.toolName = toolName;
        step.actionDescription = "执行";
        step.icon = "🛠️";
        return step;
    }

    private static ToolGuideFlowConfig.ToolFlow findTool(ToolGuideFlowConfig config, String toolName) {
        for (ToolGuideFlowConfig.ToolFlow tool : config.tools) {
            if (toolName.equals(tool.toolName)) {
                return tool;
            }
        }
        return null;
    }

    private static ToolGuideFlowConfig.CompositeFlow findComposite(ToolGuideFlowConfig config, String flowId) {
        for (ToolGuideFlowConfig.CompositeFlow flow : config.composites) {
            if (flowId.equals(flow.flowId)) {
                return flow;
            }
        }
        return null;
    }

    private static Set<String> splitConditionValues(String conditionValue) {
        if (conditionValue == null || conditionValue.isEmpty()) {
            return null;
        }
        return new LinkedHashSet<>(Arrays.asList(conditionValue.split("\\|")));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static void compare(List<String> diffs, String field, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            diffs.add(field + " builder=" + expected + " draft=" + actual);
        }
    }

    private static void compareSet(List<String> diffs, String field, Set<String> a, Set<String> b) {
        Set<String> left = a == null ? Collections.<String>emptySet() : a;
        Set<String> right = b == null ? Collections.<String>emptySet() : b;
        if (!left.equals(right)) {
            diffs.add(field + " builder=" + left + " draft=" + right);
        }
    }

    private static void compareMap(List<String> diffs, String field, Map<String, String> a, Map<String, String> b) {
        Map<String, String> left = a == null ? Collections.<String, String>emptyMap() : a;
        Map<String, String> right = b == null ? Collections.<String, String>emptyMap() : b;
        if (!left.equals(right)) {
            diffs.add(field + " builder=" + left + " draft=" + right);
        }
    }

    private static String join(List<String> diffs) {
        StringBuilder sb = new StringBuilder();
        for (String diff : diffs) {
            if (sb.length() > 0) {
                sb.append("\n  ");
            }
            sb.append(diff);
        }
        return sb.toString();
    }

    private static void assertErrorContaining(List<ToolGuideFlowValidator.Issue> issues, String fragment) {
        assertHasIssue(issues, ToolGuideFlowValidator.Severity.ERROR, fragment);
    }

    private static void assertHasIssue(List<ToolGuideFlowValidator.Issue> issues,
                                       ToolGuideFlowValidator.Severity severity, String fragment) {
        for (ToolGuideFlowValidator.Issue issue : issues) {
            if (issue.severity == severity && issue.message.contains(fragment)) {
                return;
            }
        }
        throw new AssertionError("没找到 " + severity + " 且含 \"" + fragment + "\" 的问题；实际：\n  "
                + ToolGuideFlowValidator.render(issues));
    }
}
