package com.oilquiz.app.ai.tool;

import com.oilquiz.app.ai.tool.openai.ParamDefinition;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 动态工具参数 schema 测试（2026-11-16，重构 R2 的验收）。
 *
 * <p>重构目标 R2 是"参数 schema 只有一份真值"。实际检查后发现
 * {@code DynamicToolParams} 内部<b>已经</b>用 {@link ParamDefinition} 承载类型，
 * {@code DynamicAITool.getParameterDefinitions()} 也优先返回它、仅旧数据回退成
 * 全 string —— 也就是说 R2 基本已完成，缺的是<b>把这件事钉住的测试</b>。
 * 这个类补上验收：类型 / 必填 / 枚举 / 默认值 都要原样穿过 parse → ParamDefinition。</p>
 */
public class DynamicToolParamsTest {

    private static final String TYPED_SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "  \"city\":{\"type\":\"string\",\"description\":\"城市名\"},"
            + "  \"days\":{\"type\":\"integer\",\"description\":\"天数\"},"
            + "  \"unit\":{\"type\":\"string\",\"description\":\"单位\",\"enum\":[\"c\",\"f\"]},"
            + "  \"verbose\":{\"type\":\"boolean\",\"description\":\"详细输出\"}"
            + "},"
            + "\"required\":[\"city\",\"days\"]"
            + "}";

    @Test
    public void typedSchemaKeepsTypesAndRequired() {
        DynamicToolParams params = DynamicToolParams.parse(TYPED_SCHEMA);
        assertFalse("不应为空", params.isEmpty());
        List<ParamDefinition> defs = params.getDefinitions();
        assertEquals("参数数量应与 schema 一致", 4, defs.size());

        ParamDefinition city = find(defs, "city");
        assertEquals("city 应为 string", "string", city.getType());
        assertTrue("city 应必填", city.isRequired());

        ParamDefinition days = find(defs, "days");
        assertEquals("days 应为 integer（不能被压成 string）", "integer", days.getType());
        assertTrue("days 应必填", days.isRequired());

        ParamDefinition verbose = find(defs, "verbose");
        assertEquals("verbose 应为 boolean", "boolean", verbose.getType());
        assertFalse("verbose 非必填", verbose.isRequired());
    }

    @Test
    public void enumIsPreserved() {
        DynamicToolParams params = DynamicToolParams.parse(TYPED_SCHEMA);
        ParamDefinition unit = find(params.getDefinitions(), "unit");
        assertTrue("unit 应保留枚举", unit.getEnumValues() != null
                && unit.getEnumValues().contains("c") && unit.getEnumValues().contains("f"));
    }

    @Test
    public void jsonRoundTripKeepsTypes() {
        DynamicToolParams params = DynamicToolParams.parse(TYPED_SCHEMA);
        String json = params.toJson();
        assertTrue("回写 JSON 应含 integer 类型: " + json, json.contains("\"integer\""));
        DynamicToolParams again = DynamicToolParams.fromJson(json);
        assertEquals("回读后参数数量一致", params.getDefinitions().size(),
                again.getDefinitions().size());
        assertEquals("回读后 days 仍是 integer", "integer",
                find(again.getDefinitions(), "days").getType());
    }

    @Test
    public void legacyNameDescMapFallsBackToStrings() throws Exception {
        // 旧数据：只有 name→desc 的 map，没有类型信息
        DynamicToolParams params = DynamicToolParams.parse(
                new JSONObject("{\"a\":\"说明A\",\"b\":\"说明B\"}"));
        List<ParamDefinition> defs = params.getDefinitions();
        assertEquals(2, defs.size());
        for (ParamDefinition d : defs) {
            assertEquals("旧数据回退为 string", "string", d.getType());
            assertFalse("旧数据一律非必填", d.isRequired());
        }
    }

    private static ParamDefinition find(List<ParamDefinition> defs, String name) {
        for (ParamDefinition d : defs) {
            if (name.equals(d.getName())) {
                return d;
            }
        }
        throw new AssertionError("参数不存在: " + name);
    }
}
