package com.oilquiz.app.ai.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PoC 档的转换层口径（2026-10-28 第三步）。
 *
 * <p>PoC 的唯一承诺是：<b>开关打开后，配置产出的引导流程与硬编码 builder 逐字段等价，
 * 并且条件过滤行为也等价</b>。所以本类不是"测转换器能跑"，而是拿真文件对 builder 做 A/B。
 * 比数据形状更进一步的地方是 {@link #activeStepsFilteringMatchesBuilderForEveryActionValue()}：
 * 条件分支被从结构化 {@code values[]} 回填成 "|" 串，只要回填形状差一点，
 * {@code getActiveSteps} 就会漏掉或多展示步骤，而那在界面上表现为"某一步莫名其妙不见了"。</p>
 *
 * <p>另外钉住两个容易写错的地方：转换器<b>每次新建对象</b>（缓存流程会让两次引导共享
 * {@code paramValue} 运行时槽位），以及各类型只填 {@code GuideStep} 工厂方法本来会填的字段
 * （{@code imagePickerStep} 强制 {@code mimeTypes={"image/*"}}、{@code optionStep} 根本不填
 * {@code hint}）。这些不是"多余字段"，是现行行为的一部分。</p>
 *
 * <p>{@code ToolGuideFlow.getFlow} 里"开关 + 回退"那段接线只能靠源码文本钉住 + 真机点一次：
 * Robolectric 下 {@code SmartQuizApplication.getAppContext()} 是 null，flag 走默认档（关）。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ToolGuideFlowConverterTest {

    /** 随包档里被列出来的三个工具（= PoC 覆盖面）。 */
    private static final List<String> POC_TOOLS =
            Arrays.asList("ai_weather", "network_search", "database");

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private ToolGuideFlowConfig shippedConfig() {
        ToolGuideConfigLoader.invalidate();
        ToolGuideFlowConfig config = ToolGuideConfigLoader.config(ctx());
        assertNotNull("读不到随包档就无法做 A/B（文件缺失/解析失败/有 ERROR 被拒）", config);
        return config;
    }

    // ==================== A/B：配置产物 vs builder 产物 ====================

    @Test
    public void convertedFlowIsFieldIdenticalToBuilderForEveryPocTool() {
        ToolGuideFlowConfig config = shippedConfig();
        for (String toolName : POC_TOOLS) {
            ToolGuideFlow fromBuilder = ToolGuideFlow.getFlow(toolName);
            ToolGuideFlow fromConfig = ToolGuideFlowConverter.findToolFlow(config, toolName);
            assertNotNull(toolName + " builder 侧应有流程", fromBuilder);
            assertNotNull(toolName + " 配置侧应有流程", fromConfig);
            List<String> diffs = GuideFlowDiff.diff(fromBuilder, fromConfig);
            assertEquals(toolName + " 配置产物与 builder 不等价：\n  " + GuideFlowDiff.render(diffs),
                    0, diffs.size());
        }
    }

    @Test
    public void activeStepsFilteringMatchesBuilderForEveryActionValue() {
        // 逐工具把每个 action 取值都喂一遍，比较 getActiveSteps 出来的步骤序列
        ToolGuideFlowConfig config = shippedConfig();
        for (String toolName : POC_TOOLS) {
            ToolGuideFlow builder = ToolGuideFlow.getFlow(toolName);
            ToolGuideFlow converted = ToolGuideFlowConverter.findToolFlow(config, toolName);
            assertNotNull(converted);
            for (Map<String, String> selection : selectionsOf(builder)) {
                List<String> expectedKeys = paramKeysOf(builder.getActiveSteps(selection));
                List<String> actualKeys = paramKeysOf(converted.getActiveSteps(selection));
                assertEquals(toolName + " 在已选参数 " + selection + " 下的步骤序列不同",
                        expectedKeys, actualKeys);
            }
        }
    }

    // ==================== 回退与边界 ====================

    @Test
    public void toolsNotListedInConfigAreLeftToTheBuilder() {
        ToolGuideFlowConfig config = shippedConfig();
        // location / file_reader / app_operation 有 chip 入口但 PoC 没列进配置
        for (String toolName : new String[]{"location", "file_reader", "app_operation"}) {
            assertNull(toolName + " 没在配置里，转换器必须返回 null 让调用方回退 builder",
                    ToolGuideFlowConverter.findToolFlow(config, toolName));
        }
    }

    @Test
    public void nullConfigAndNullToolNameAreSafe() {
        assertNull(ToolGuideFlowConverter.findToolFlow(null, "ai_weather"));
        assertNull(ToolGuideFlowConverter.findToolFlow(shippedConfig(), null));
        ToolGuideFlowConfig empty = new ToolGuideFlowConfig();
        assertNull(ToolGuideFlowConverter.findToolFlow(empty, "ai_weather"));
    }

    @Test
    public void everyCallBuildsFreshStepsSoRuntimeSlotsAreNotShared() {
        ToolGuideFlowConfig config = shippedConfig();
        ToolGuideFlow first = ToolGuideFlowConverter.findToolFlow(config, "ai_weather");
        ToolGuideFlow second = ToolGuideFlowConverter.findToolFlow(config, "ai_weather");

        List<String> diffs = GuideFlowDiff.diff(first, second);
        assertEquals("两次调用结果应等价：\n  " + GuideFlowDiff.render(diffs), 0, diffs.size());
        assertFalse("两次调用绝不能共享同一个步骤对象",
                first.steps.get(0) == second.steps.get(0));

        // paramValue 是 AIChatActivity 往当前步骤上写的运行时槽位
        first.steps.get(0).paramValue = "current";
        assertNull("前一次引导的选择不能漏进后一次", second.steps.get(0).paramValue);
    }

    // ==================== 工厂方法字段子集（不是"多填更好"）====================

    @Test
    public void imagePickerIgnoresConfiguredMimeTypesBecauseTheFactoryForcesImageStar() {
        ToolGuideFlowConfig.Step src = step("IMAGE_PICKER", "image_path");
        src.mimeTypes = Arrays.asList("application/pdf"); // 配置里这么写也不会生效
        ToolGuideFlow.GuideStep converted = ToolGuideFlowConverter.toStep(src);
        assertNotNull(converted);
        assertTrue("IMAGE_PICKER 的 mimeTypes 必须与现行工厂一致地固定为 image/*",
                Arrays.equals(new String[]{"image/*"}, converted.mimeTypes));
    }

    @Test
    public void optionStepDoesNotGrowHintOrRequiredThatTheBuilderNeverSets() {
        ToolGuideFlowConfig.Step src = step("OPTION", "action");
        src.hint = "配置里多写的提示";
        src.required = true;
        src.options = Collections.singletonList(option("现在天气", "current"));
        ToolGuideFlow.GuideStep converted = ToolGuideFlowConverter.toStep(src);
        assertNotNull(converted);
        assertNull("optionStep 不设 hint，转换器也不该多填", converted.hint);
        assertFalse("optionStep 不设 required", converted.required);
    }

    @Test
    public void omittedBooleansMeanFalseExactlyLikeBuilderDefaults() {
        ToolGuideFlowConfig.Step src = step("INPUT", "keyword");
        // required/multiline 都不写
        ToolGuideFlow.GuideStep converted = ToolGuideFlowConverter.toStep(src);
        assertNotNull(converted);
        assertFalse(converted.required);
        assertFalse(converted.multiline);
    }

    @Test
    public void structuredConditionIsJoinedBackToThePipeFormGetActiveStepsParses() {
        ToolGuideFlowConfig.Step src = step("INPUT", "content");
        ToolGuideFlowConfig.Condition condition = new ToolGuideFlowConfig.Condition();
        condition.key = "action";
        condition.values = Arrays.asList("create", "append", "markdown");
        src.condition = condition;

        ToolGuideFlow.GuideStep converted = ToolGuideFlowConverter.toStep(src);

        assertNotNull(converted);
        assertEquals("action", converted.conditionKey);
        assertEquals("create|append|markdown", converted.conditionValue);
    }

    @Test
    public void unknownStepTypeIsSkippedInsteadOfThrowingAwayTheWholeFlow() {
        ToolGuideFlowConfig.ToolFlow src = new ToolGuideFlowConfig.ToolFlow();
        src.toolName = "some_tool";
        src.steps = Arrays.asList(step("CONFIRM", null), step("MYSTERY", "oops"), step("CONFIRM", null));
        ToolGuideFlow converted = ToolGuideFlowConverter.toFlow(src);
        assertNotNull(converted);
        assertEquals("无法识别的步骤被跳过，正常步骤保留", 2, converted.steps.size());
    }

    // ==================== 接线不回退（源码文本钉）====================

    @Test
    public void getFlowWiringKeepsFlagGateAndBuilderFallback() throws Exception {
        // 风格同 LocalAgentCancelPathTest：结构不能回退。真机点一次才算行为验完。
        String source = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/oilquiz/app/ai/agent/ToolGuideFlow.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue("getFlow 必须先过配置档再进 builder switch",
                source.contains("tryFlowFromConfig(toolName)"));
        assertTrue("配置档要走开关，不能无条件读配置",
                source.contains("ToolGuideFlowFlag.enabled(context)"));
        assertTrue("开关关/配置缺时必须回退 builder",
                source.contains("ToolGuideFlowConverter.findToolFlow("));
        assertTrue("builder 的 13 个 case 不能被删（PoC 阶段两档并存）",
                countOccurrences(source, "case \"") >= 13);
        assertTrue("catch 兜住一切，失败模式必须是维持现状而不是抛",
                source.contains("回退硬编码 builder"));
    }

    @Test
    public void flagDefaultsToOffSoPoCShipsInert() {
        assertFalse("出厂档＝关：不显式写 prefs 就完全维持现状",
                ToolGuideFlowFlag.enabled(null));
    }

    // ==================== 小工具 ====================

    /** 用 builder 的 action 选项造一批"已选参数"样本，含空选择。 */
    private static List<Map<String, String>> selectionsOf(ToolGuideFlow flow) {
        List<Map<String, String>> out = new ArrayList<>();
        out.add(new HashMap<String, String>());
        ToolGuideFlow.GuideStep actionStep = null;
        for (ToolGuideFlow.GuideStep step : flow.steps) {
            if ("action".equals(step.paramKey) && step.options != null) {
                actionStep = step;
                break;
            }
        }
        if (actionStep != null) {
            for (ToolGuideFlow.GuideStep.Option option : actionStep.options) {
                Map<String, String> selection = new HashMap<>();
                selection.put("action", option.value);
                out.add(selection);
            }
        }
        return out;
    }

    private static List<String> paramKeysOf(List<ToolGuideFlow.GuideStep> steps) {
        List<String> out = new ArrayList<>();
        for (ToolGuideFlow.GuideStep step : steps) {
            out.add(step.paramKey + "#" + step.type);
        }
        return out;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
    }

    private static ToolGuideFlowConfig.Step step(String type, String paramKey) {
        ToolGuideFlowConfig.Step step = new ToolGuideFlowConfig.Step();
        step.type = type;
        step.paramKey = paramKey;
        step.title = "标题";
        step.description = "描述";
        return step;
    }

    private static ToolGuideFlowConfig.Option option(String label, String value) {
        ToolGuideFlowConfig.Option option = new ToolGuideFlowConfig.Option();
        option.label = label;
        option.value = value;
        return option;
    }
}
