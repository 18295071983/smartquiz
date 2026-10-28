package com.oilquiz.app.ai.tool;

import android.content.Context;

import com.oilquiz.app.ai.tool.annotation.Tool;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * 单位换算工具：纯 Java 本地实现，零依赖。
 * 支持：长度、重量、温度、面积、体积、速度、压力、质量流量。
 *
 * 参数：
 * - value: 数值
 * - from: 源单位（如 km / kg / celsius / m2 / l / kmh）
 * - to: 目标单位（同上）
 * 温度用线性换算，其余用基准系数（BigDecimal 保留 6 位小数）。
 */
@Tool(value = "unit_converter", category = "utility")
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
                + "速度（mps/kmh/mph）、**压力**（pa/kpa/mpa/gpa/bar/mbar/psi/atm/torr=mmhg）、"
                + "**质量流量**（kg/h/t/h/g/s/lb/h）。"
                + "参数：value 数值、from 源单位、to 目标单位。"
                + "适合日常单位换算、食谱/距离/温度，以及工况里的压力（MPa↔kPa↔bar）、"
                + "流量（t/h↔kg/h）换算。液位高度属于长度，用 mm/m 那组。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> params = new HashMap<>();
        params.put("value", "数值（如 100）");
        params.put("from", "源单位（如 km / mpa / t/h）");
        params.put("to", "目标单位（如 mile / kpa / kg/h）");
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
    /**
     * 压力 → 帕斯卡（2026-10-29 补：化工工况最常问的一组，此前完全没有，
     * "mpa → kpa"会报"不属于同一量纲"，只能退回 python_calculate 手算）。
     * 数值取定义值：1 bar=1e5 Pa、1 atm=101325 Pa、1 psi=6894.757293168 Pa、
     * 1 torr=101325/760 Pa（mmhg 与 torr 同值）。
     */
    private static final Map<String, BigDecimal> PRESSURE = map(
            "pa", "1", "kpa", "1000", "mpa", "1000000", "gpa", "1000000000",
            "bar", "100000", "mbar", "100", "hpa", "100",
            "psi", "6894.757293168", "atm", "101325", "torr", "133.322368421", "mmhg", "133.322368421");
    /** 质量流量 → 千克/小时（t/h=2.777... 无歧义：1 t/h = 1000 kg/h） */
    private static final Map<String, BigDecimal> MASS_FLOW = map(
            "kg/h", "1", "t/h", "1000", "g/s", "3.6", "g/h", "0.001", "lb/h", "0.45359237");

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
        if (PRESSURE.containsKey(from) && PRESSURE.containsKey(to)) return PRESSURE;
        if (MASS_FLOW.containsKey(from) && MASS_FLOW.containsKey(to)) return MASS_FLOW;
        // 报错要说清"每个单位各自属于哪一族"——只说"不属于同一量纲"时，
        // 调用方无从知道是自己拼错了单位还是这一族压根不支持（2026-10-29 实测就撞在这）
        throw new Exception("不支持的换算: " + from + " → " + to
                + "（" + familyOf(from) + "，" + familyOf(to) + "）。"
                + "已支持的量纲：长度/重量/温度/面积/体积/速度/压力/质量流量");
    }

    /** 单位属于哪一族（unknown 时明确说没收录，别和"跨量纲"混成一句话） */
    private static String familyOf(String unit) {
        if (unit == null || unit.isEmpty()) return "空单位";
        if (isTemp(unit)) return unit + " 属于温度";
        if (LENGTH.containsKey(unit)) return unit + " 属于长度";
        if (WEIGHT.containsKey(unit)) return unit + " 属于重量";
        if (AREA.containsKey(unit)) return unit + " 属于面积";
        if (VOLUME.containsKey(unit)) return unit + " 属于体积";
        if (SPEED.containsKey(unit)) return unit + " 属于速度";
        if (PRESSURE.containsKey(unit)) return unit + " 属于压力";
        if (MASS_FLOW.containsKey(unit)) return unit + " 属于质量流量";
        return "单位 " + unit + " 未收录";
    }

    private static boolean isTemp(String unit) {
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
