package com.oilquiz.app.ai.tool;

import com.oilquiz.app.ai.tool.openai.ParamDefinition;
import com.oilquiz.app.ai.tool.openai.ToolDefinition;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 工具 schema 的<b>形状</b>契约（与 {@link ToolSchemaVisibilityTest} 关注的"提示词里教了什么"互补）：
 * 这里断言的是模型经由 {@code tools} 数组实际收到的类型、枚举与 strict 值。
 *
 * <p>覆盖两类静默故障：</p>
 * <ul>
 *   <li><b>类型塌陷</b>：没有 switch 版 ToolDefinition 的工具只能走
 *   {@code createToolDefinitionFromAITool} 的 Map 兜底，兜底把每个参数一律写成
 *   {@code type:"string" / required:false}——类上 {@code @Param(type="integer"/"boolean")}
 *   的声明从来没进过线上 schema。time_date 的 timestamp、workspace 的 confirm 曾是这种状态。</li>
 *   <li><b>strict 口径</b>：chat/completions 与 Responses 两条链路都显式写 false。
 *   本项目的工具 schema 刻意宽松（ui_component 26 参数仅 action 必填 + 5 个自由 object +
 *   3 个无 items 数组），开严格模式一条都过不了；显式 false 只为不赌各网关的默认值。</li>
 * </ul>
 */
public class ToolSchemaShapeTest {

    private static Map<String, ParamDefinition> byName(List<ParamDefinition> defs) {
        Map<String, ParamDefinition> map = new HashMap<>();
        for (ParamDefinition def : defs) {
            map.put(def.getName(), def);
        }
        return map;
    }

    @Test
    public void timeDate_declaresIntegerTimestampAndActionEnum() {
        List<ParamDefinition> defs = new TimeDateTool().getParameterDefinitions();
        Map<String, ParamDefinition> byName = byName(defs);

        // 核心：timestamp 必须是 integer，不能塌成 string（execute 用 parseLong 取值）
        assertEquals("integer", byName.get("timestamp").getType());
        assertEquals("string", byName.get("date").getType());
        assertEquals("string", byName.get("action").getType());

        // 枚举必须与 TimeDateTool.execute 的 switch 逐字一致；改了 execute 就要改这里
        assertEquals(Arrays.asList("now", "date", "time", "weekday",
                        "timestamp_to_date", "date_to_timestamp", "timezone"),
                byName.get("action").getEnumValues());

        // 三个参数都不是"任何调用都必须给"：action 缺省 now，timestamp/date 只在各自 action 下必填
        for (ParamDefinition def : defs) {
            assertFalse(def.getName() + " 不该被标成必填（扁平 schema 分不出 action）", def.isRequired());
        }
    }

    @Test
    public void workspace_declaresBooleanConfirmAndActionEnum() {
        List<ParamDefinition> defs = new WorkspaceTool().getParameterDefinitions();
        Map<String, ParamDefinition> byName = byName(defs);

        // 核心：confirm 是 boolean（execute 的 boolParam 按布尔语义判），不能显示成 string
        assertEquals("boolean", byName.get("confirm").getType());
        assertEquals("string", byName.get("fileName").getType());
        assertEquals(Arrays.asList("list", "path", "read", "delete", "clear"),
                byName.get("action").getEnumValues());

        for (ParamDefinition def : defs) {
            assertFalse(def.getName() + " 不该被标成必填（action 缺省 list）", def.isRequired());
        }
    }

    @Test
    public void chatToolJson_carriesExplicitStrictFalseInsideFunction() throws Exception {
        JSONObject tool = ToolDefinition.builder("demo", "演示工具")
                .addParameter("q", "string", "查询词", true)
                .build()
                .toOpenAIFormat();
        assertEquals("function", tool.getString("type"));
        // chat/completions 的 strict 在 function 里面（Responses 侧才与 name 同级）
        JSONObject function = tool.getJSONObject("function");
        assertTrue("chat 侧每条工具都要带 strict 字段", function.has("strict"));
        assertFalse(function.getBoolean("strict"));
        assertFalse("strict 不能错放到 tool 顶层", tool.has("strict"));
    }
}
