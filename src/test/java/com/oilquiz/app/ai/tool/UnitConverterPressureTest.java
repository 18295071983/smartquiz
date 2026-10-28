package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * unit_converter 的压力与质量流量换算（2026-10-29，真机对话历史实测）。
 *
 * <p>此前只有长度/重量/温度/面积/体积/速度六族——化工日常最常用的 MPa↔kPa 直接报
 * "不属于同一量纲"，模型只能退回 python_calculate 手算；报错文案也不说各自属于哪一族，
 * 调用方分不清是单位拼错还是这一族根本没支持。</p>
 */
public class UnitConverterPressureTest {

    private final UnitConverterTool tool = new UnitConverterTool();

    private AIToolResult convert(String value, String from, String to) {
        Map<String, Object> p = new HashMap<>();
        p.put("value", value);
        p.put("from", from);
        p.put("to", to);
        return tool.execute(p);
    }

    private String resultOf(AIToolResult r) {
        Object v = r.getAdditionalInfo() == null ? null : r.getAdditionalInfo().get("result");
        assertNotNull("成功结果应带 result 字段: " + r.getErrorMessage(), v);
        return String.valueOf(v);
    }

    @Test
    public void megapascalToKilopascal() {
        AIToolResult r = convert("1", "MPa", "kPa");
        assertTrue("MPa→kPa 必须支持: " + r.getErrorMessage(), r.isSuccess());
        assertEquals("1000", resultOf(r));
    }

    @Test
    public void barAndPsiAndAtmAndTorr() {
        assertEquals("100", resultOf(convert("1", "bar", "kpa")));
        assertEquals("101.325", resultOf(convert("1", "atm", "kpa")));
        // 1 psi = 6894.757293168 Pa → kPa
        assertEquals("6.894757", resultOf(convert("1", "psi", "kpa")));
        // 760 torr = 1 atm
        assertEquals("1", resultOf(convert("760", "torr", "atm")));
        assertEquals("101.325", resultOf(convert("760", "mmhg", "kpa")));
    }

    @Test
    public void massFlowFamilies() {
        assertEquals("1000", resultOf(convert("1", "t/h", "kg/h")));
        // 1 kg/h = 1000 g / 3600 s = 0.277778 g/s
        assertEquals("0.277778", resultOf(convert("1", "kg/h", "g/s")));
    }

    @Test
    public void crossFamilyErrorNamesBothSides() {
        AIToolResult r = convert("1", "mpa", "m3");
        assertFalse(r.isSuccess());
        assertTrue("报错要说清 mpa 属于压力族: " + r.getErrorMessage(),
                r.getErrorMessage().contains("属于压力"));
        assertTrue("报错要说清 m3 属于体积族: " + r.getErrorMessage(),
                r.getErrorMessage().contains("属于体积"));
    }

    @Test
    public void unknownUnitIsSaidAsUnlisted() {
        AIToolResult r = convert("1", "furlong", "m");
        assertFalse(r.isSuccess());
        assertTrue("未收录的单位要说「未收录」，不要混成跨量纲: " + r.getErrorMessage(),
                r.getErrorMessage().contains("未收录"));
    }
}
