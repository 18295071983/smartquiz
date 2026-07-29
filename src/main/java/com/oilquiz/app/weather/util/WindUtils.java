package com.oilquiz.app.weather.util;

import java.util.HashMap;
import java.util.Map;

public class WindUtils {

    public static final int WIND_DIRECTION_COUNT_16 = 16;
    public static final int WIND_DIRECTION_COUNT_8 = 8;

    private static final Map<String, String> DIRECTION_CODE_TO_NAME_16 = new HashMap<>();
    private static final Map<String, String> DIRECTION_CODE_TO_NAME_8 = new HashMap<>();
    private static final Map<Integer, String> DIRECTION_ANGLE_TO_NAME = new HashMap<>();

    static {
        DIRECTION_CODE_TO_NAME_16.put("N", "北风");
        DIRECTION_CODE_TO_NAME_16.put("NNE", "东北偏北风");
        DIRECTION_CODE_TO_NAME_16.put("NE", "东北风");
        DIRECTION_CODE_TO_NAME_16.put("ENE", "东北偏东风");
        DIRECTION_CODE_TO_NAME_16.put("E", "东风");
        DIRECTION_CODE_TO_NAME_16.put("ESE", "东南偏东风");
        DIRECTION_CODE_TO_NAME_16.put("SE", "东南风");
        DIRECTION_CODE_TO_NAME_16.put("SSE", "东南偏南风");
        DIRECTION_CODE_TO_NAME_16.put("S", "南风");
        DIRECTION_CODE_TO_NAME_16.put("SSW", "西南偏南风");
        DIRECTION_CODE_TO_NAME_16.put("SW", "西南风");
        DIRECTION_CODE_TO_NAME_16.put("WSW", "西南偏西风");
        DIRECTION_CODE_TO_NAME_16.put("W", "西风");
        DIRECTION_CODE_TO_NAME_16.put("WNW", "西北偏西风");
        DIRECTION_CODE_TO_NAME_16.put("NW", "西北风");
        DIRECTION_CODE_TO_NAME_16.put("NNW", "西北偏北风");

        DIRECTION_CODE_TO_NAME_8.put("N", "北风");
        DIRECTION_CODE_TO_NAME_8.put("NE", "东北风");
        DIRECTION_CODE_TO_NAME_8.put("E", "东风");
        DIRECTION_CODE_TO_NAME_8.put("SE", "东南风");
        DIRECTION_CODE_TO_NAME_8.put("S", "南风");
        DIRECTION_CODE_TO_NAME_8.put("SW", "西南风");
        DIRECTION_CODE_TO_NAME_8.put("W", "西风");
        DIRECTION_CODE_TO_NAME_8.put("NW", "西北风");
        DIRECTION_CODE_TO_NAME_8.put("Rotational", "旋转风");
        DIRECTION_CODE_TO_NAME_8.put("None", "无持续风向");

        DIRECTION_ANGLE_TO_NAME.put(0, "北风");
        DIRECTION_ANGLE_TO_NAME.put(22, "东北偏北风");
        DIRECTION_ANGLE_TO_NAME.put(45, "东北风");
        DIRECTION_ANGLE_TO_NAME.put(67, "东北偏东风");
        DIRECTION_ANGLE_TO_NAME.put(90, "东风");
        DIRECTION_ANGLE_TO_NAME.put(112, "东南偏东风");
        DIRECTION_ANGLE_TO_NAME.put(135, "东南风");
        DIRECTION_ANGLE_TO_NAME.put(157, "东南偏南风");
        DIRECTION_ANGLE_TO_NAME.put(180, "南风");
        DIRECTION_ANGLE_TO_NAME.put(202, "西南偏南风");
        DIRECTION_ANGLE_TO_NAME.put(225, "西南风");
        DIRECTION_ANGLE_TO_NAME.put(247, "西南偏西风");
        DIRECTION_ANGLE_TO_NAME.put(270, "西风");
        DIRECTION_ANGLE_TO_NAME.put(292, "西北偏西风");
        DIRECTION_ANGLE_TO_NAME.put(315, "西北风");
        DIRECTION_ANGLE_TO_NAME.put(337, "西北偏北风");
    }

    public static String getDirectionNameByCode16(String code) {
        if (code == null || code.isEmpty()) return "未知";
        String name = DIRECTION_CODE_TO_NAME_16.get(code.toUpperCase());
        return name != null ? name : code;
    }

    public static String getDirectionNameByCode8(String code) {
        if (code == null || code.isEmpty()) return "未知";
        String name = DIRECTION_CODE_TO_NAME_8.get(code);
        if (name != null) return name;
        name = DIRECTION_CODE_TO_NAME_8.get(code.toUpperCase());
        return name != null ? name : code;
    }

    public static String getDirectionNameByAngle(double angle) {
        if (angle < 0) return "无持续风向";
        int roundedAngle = (int) Math.round(angle / 22.5) * 22;
        if (roundedAngle >= 360) roundedAngle = 0;
        String name = DIRECTION_ANGLE_TO_NAME.get(roundedAngle);
        return name != null ? name : "未知";
    }

    public static String getDirectionCodeByAngle16(double angle) {
        if (angle < 0) return "N";
        int index = (int) Math.round(angle / 22.5) % 16;
        String[] codes = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                          "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return codes[index];
    }

    public static int getBeaufortScale(double speedKmh) {
        if (speedKmh < 1) return 0;
        if (speedKmh < 5) return 1;
        if (speedKmh < 11) return 2;
        if (speedKmh < 19) return 3;
        if (speedKmh < 28) return 4;
        if (speedKmh < 38) return 5;
        if (speedKmh < 49) return 6;
        if (speedKmh < 61) return 7;
        if (speedKmh < 74) return 8;
        if (speedKmh < 88) return 9;
        if (speedKmh < 102) return 10;
        if (speedKmh < 117) return 11;
        if (speedKmh < 134) return 12;
        if (speedKmh < 150) return 13;
        if (speedKmh < 167) return 14;
        if (speedKmh < 184) return 15;
        if (speedKmh < 202) return 16;
        return 17;
    }

    public static String getBeaufortScaleName(int scale) {
        switch (scale) {
            case 0: return "无风";
            case 1: return "软风";
            case 2: return "轻风";
            case 3: return "微风";
            case 4: return "和风";
            case 5: return "清风";
            case 6: return "强风";
            case 7: return "疾风";
            case 8: return "大风";
            case 9: return "烈风";
            case 10: return "狂风";
            case 11: return "暴风";
            case 12: return "飓风";
            case 13: return "台风";
            case 14: return "强台风";
            case 15: return "强台风";
            case 16: return "超强台风";
            case 17: return "超强台风";
            default: return "未知";
        }
    }

    public static String getBeaufortScaleDescription(int scale) {
        switch (scale) {
            case 0: return "静,烟直上";
            case 1: return "烟示风向";
            case 2: return "感觉有风";
            case 3: return "旌旗展开";
            case 4: return "吹起尘土";
            case 5: return "小树摇摆";
            case 6: return "电线有声";
            case 7: return "步行困难";
            case 8: return "折毁树枝";
            case 9: return "小损房屋";
            case 10: return "拔起树木";
            case 11: return "损毁重大";
            case 12: return "摧毁极大";
            default: return "";
        }
    }

    public static double speedKmhToMs(double kmh) {
        return kmh / 3.6;
    }

    public static double speedMsToKmh(double ms) {
        return ms * 3.6;
    }

    public static String getWindDisplay(String windDir, String windScale, String windSpeed) {
        StringBuilder sb = new StringBuilder();
        if (windDir != null && !windDir.isEmpty()) {
            sb.append(windDir);
        }
        if (windScale != null && !windScale.isEmpty()) {
            try {
                int scale = Integer.parseInt(windScale);
                sb.append(getBeaufortScaleName(scale));
            } catch (NumberFormatException e) {
                sb.append(windScale).append("级");
            }
        }
        if (windSpeed != null && !windSpeed.isEmpty()) {
            sb.append(" ").append(windSpeed).append("km/h");
        }
        return sb.toString();
    }
}
