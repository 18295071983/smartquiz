package com.oilquiz.app.ai.tool;

import android.content.Context;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * 单位换算工具：纯 Java 本地实现，零依赖。
 * 支持：长度、重量、温度、面积、体积、速度。
 *
 * 参数：
 * - value: 数值
 * - from: 源单位（如 km / kg / celsius / m2 / l / kmh）
 * - to: 目标单位（同上）
 * 温度用线性换算，其余用基准系数（BigDecimal 保留 6 位小数）。
 */
public class UnitConverterTool implements AITool {

    private static final String TAG = "UnitConverterTool";

    public UnitConverterTool() {
    }

    public UnitConverterTool(Context context) {
    }

    @Override
    public String getName() {
        return "unit_converter";
    }

    @Override
    public String getDescription() {
        return "单位换算工具（纯本地）：支持长度（m/km/cm/mm/mile/yd/ft/inch）、"
                + "重量（kg/g/mg/t/lb/oz）、温度（celsius/fahrenheit/kelvin）、"
                + "面积（m2/km2/cm2/hectare/acre）、体积（l/ml/m3/gallon）、"
                + "速度（mps/kmh/mph）。"
                + "参数：value 数值、from 源单位、to 目标单位。"
                + "适合日常单位换算、食谱/距离/温度等场景。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("value", "数值（如 100）");
        params.put("from", "源单位（如 km）");
        params.put("to", "目标单位（如 mile）");
        return params;
    }

    /** 长度 → 米 */
    private static final Map<String, BigDecimal> LENGTH = map(
            "m", "1", "km", "1000", "cm", "0.01", "mm", "0.001",
            "mile", "1609.344", "yd", "0.9144", "ft", "0.3048", "inch", "0.0254");
    /** 重量 → 千克 */
    private static final Map<String, BigDecimal> WEIGHT = map(
            "kg", "1", "g", "0.001", "mg", "0.000001", "t", "1000",
            "lb", "0.45359237", "oz", "0.028349523125");
    /** 面积 → 平方米 */
    private static final Map<String, BigDecimal> AREA = map(
            "m2", "1", "km2", "1000000", "cm2", "0.0001",
            "hectare", "10000", "acre", "4046.8564224");
    /** 体积 → 升 */
    private static final Map<String, BigDecimal> VOLUME = map(
            "l", "1", "ml", "0.001", "m3", "1000", "gallon", "3.785411784");
    /** 速度 → 米/秒 */
    private static final Map<String, BigDecimal> SPEED = map(
            "mps", "1", "kmh", "0.2777777777777778", "mph", "0.44704");

    private static Map<String, BigDecimal> map(String... kv) {
        Map<String, BigDecimal> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], new BigDecimal(kv[i + 1]));
        }
        return m;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String valueStr = parameters.get("value") != null ? String.valueOf(parameters.get("value")).trim() : "";
            String from = parameters.get("from") != null ? String.valueOf(parameters.get("from")).trim().toLowerCase() : "";
            String to = parameters.get("to") != null ? String.valueOf(parameters.get("to")).trim().toLowerCase() : "";
            if (valueStr.isEmpty() || from.isEmpty() || to.isEmpty()) {
                return AIToolResult.fail("缺少参数: 需要 value、from、to");
            }
            BigDecimal value = new BigDecimal(valueStr);
            String result = convert(value, from, to);

            Map<String, Object> info = new HashMap<>();
            info.put("value", value.toPlainString());
            info.put("from", from);
            info.put("to", to);
            info.put("result", result);
            return AIToolResult.success(value.toPlainString() + " " + from + " = " + result + " " + to, info);
        } catch (NumberFormatException e) {
            return AIToolResult.fail("value 不是有效数字: " + e.getMessage());
        } catch (Exception e) {
            return AIToolResult.fail("换算失败: " + e.getMessage());
        }
    }

    private String convert(BigDecimal value, String from, String to) throws Exception {
        // 温度：线性换算
        if (isTemp(from) || isTemp(to)) {
            return convertTemp(value, from, to);
        }
        Map<String, BigDecimal> table = findTable(from, to);
        BigDecimal base = value.multiply(table.get(from));
        BigDecimal result = base.divide(table.get(to), 6, RoundingMode.HALF_UP);
        return result.stripTrailingZeros().toPlainString();
    }

    private Map<String, BigDecimal> findTable(String from, String to) throws Exception {
        if (LENGTH.containsKey(from) && LENGTH.containsKey(to)) return LENGTH;
        if (WEIGHT.containsKey(from) && WEIGHT.containsKey(to)) return WEIGHT;
        if (AREA.containsKey(from) && AREA.containsKey(to)) return AREA;
        if (VOLUME.containsKey(from) && VOLUME.containsKey(to)) return VOLUME;
        if (SPEED.containsKey(from) && SPEED.containsKey(to)) return SPEED;
        throw new Exception("不支持的换算: " + from + " → " + to
                + "（或两个单位不属于同一量纲）");
    }

    private boolean isTemp(String unit) {
        return "celsius".equals(unit) || "fahrenheit".equals(unit) || "kelvin".equals(unit);
    }

    /** 温度换算（摄氏度 C、华氏度 F、开尔文 K） */
    private String convertTemp(BigDecimal v, String from, String to) throws Exception {
        double val = v.doubleValue();
        double celsius;
        switch (from) {
            case "celsius": celsius = val; break;
            case "fahrenheit": celsius = (val - 32) * 5.0 / 9.0; break;
            case "kelvin": celsius = val - 273.15; break;
            default: throw new Exception("未知温度单位: " + from);
        }
        double out;
        switch (to) {
            case "celsius": out = celsius; break;
            case "fahrenheit": out = celsius * 9.0 / 5.0 + 32; break;
            case "kelvin": out = celsius + 273.15; break;
            default: throw new Exception("未知温度单位: " + to);
        }
        BigDecimal bd = new BigDecimal(Double.toString(out)).setScale(6, RoundingMode.HALF_UP);
        return bd.stripTrailingZeros().toPlainString();
    }
}
