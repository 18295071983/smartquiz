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
        // version 是 integer（execute 用 intParam 取版本号），不能塌成 string
        assertEquals("integer", byName.get("version").getType());
        assertEquals(Arrays.asList("list", "path", "read", "versions", "restore", "delete", "clear"),
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

    /**
     * 装配器是<b>唯一</b>的，且永不输出 default。
     *
     * <p>此前 chat 链路有第二份装配（{@code OnlineToolRegistry}）：两处漂开成
     * "一边输出 default、一边刻意不输出"。现在 {@code OnlineToolRegistry} 改为复用
     * {@link ToolDefinition#schemaFor}，这条用例同时钉住三件事：default 不出现、枚举不丢、
     * 实例方法与静态装配器同结果（前者只是后者的薄壳）。</p>
     */
    @Test
    public void schemaAssemblerIsSharedAndNeverAdvertisesDefaults() throws Exception {
        List<ParamDefinition> params = Arrays.asList(
                new ParamDefinition("action", "string", "操作", false, "now", Arrays.asList("now", "date")),
                new ParamDefinition("q", "string", "查询词", true));

        JSONObject fromStatic = ToolDefinition.schemaFor("demo", "演示工具", params);
        JSONObject properties = fromStatic.getJSONObject("function")
                .getJSONObject("parameters").getJSONObject("properties");
        JSONObject action = properties.getJSONObject("action");
        assertFalse("default 会诱导模型只传默认值，不该进 schema", action.has("default"));
        assertTrue("枚举是形状约束，必须保留", action.has("enum"));
        assertEquals("string", action.getString("type"));

        // 实例方法必须与静态装配器产出同一结构
        JSONObject fromInstance = ToolDefinition.builder("demo", "演示工具")
                .addParameter("action", "string", "操作", false, "now", Arrays.asList("now", "date"))
                .addParameter("q", "string", "查询词", true)
                .build()
                .toOpenAIFormat();
        JSONObject fnStatic = fromStatic.getJSONObject("function");
        JSONObject fnInstance = fromInstance.getJSONObject("function");
        assertEquals(fnStatic.getString("name"), fnInstance.getString("name"));
        assertEquals(fnStatic.getString("description"), fnInstance.getString("description"));
        assertEquals(fnStatic.getBoolean("strict"), fnInstance.getBoolean("strict"));
        assertEquals(fnStatic.getJSONObject("parameters").getJSONArray("required").toString(),
                fnInstance.getJSONObject("parameters").getJSONArray("required").toString());
        assertEquals(fnStatic.getJSONObject("parameters").getJSONObject("properties")
                        .getJSONObject("action").getJSONArray("enum").toString(),
                fnInstance.getJSONObject("parameters").getJSONObject("properties")
                        .getJSONObject("action").getJSONArray("enum").toString());
    }

    @Test
    public void schemaAssemblerKeepsTypeStringWhenTypeIsMissing() throws Exception {
        // 旧实例方法会把 null 直接 put 进去（键消失），模型看到的参数没有 type；
        // 唯一装配器统一按 string 补，与旧 Registry 的行为一致
        JSONObject tool = ToolDefinition.schemaFor("demo", null,
                Arrays.asList(new ParamDefinition("x", null, null, false)));
        JSONObject prop = tool.getJSONObject("function").getJSONObject("parameters")
                .getJSONObject("properties").getJSONObject("x");
        assertEquals("string", prop.getString("type"));
        assertEquals("", prop.getString("description"));
        // description 为 null 时 function 上也要有一个字符串，而不是缺键
        assertEquals("", tool.getJSONObject("function").getString("description"));
    }
}
