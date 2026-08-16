package com.oilquiz.app.weather;

/**
 * 天气详情总介绍生成器（横幅与详情页共用，保证两处文案完全一致）。
 * 各字段缺省/为 "--" 时自动跳过对应段落；全部为空返回 null（调用方隐藏区域）。
 */
public final class WeatherSummaryUtil {

    private WeatherSummaryUtil() {
    }

    /**
     * 生成人性化天气总结（详情页 tvWeatherSummary / 横幅总介绍同款）。
     *
     * @param todayDay      今日白天天气（如"多云"）
     * @param todayNight    今日夜间天气（如"晴"）
     * @param highTemp      今日最高温
     * @param lowTemp       今日最低温
     * @param temp          当前温度
     * @param feelsLike     体感温度
     * @param weatherText   当前天气描述（用于天气状况提醒）
     * @param humidity      湿度
     * @param uv            紫外线指数
     * @param windScale     风力（级数）
     * @param visibility    能见度(km)
     * @param minutelySummary 分钟级降水摘要（可为空）
     * @param alertSummary  天气预警摘要（可为空）
     * @return 总结文本；无任何内容时返回 null
     */
    public static String generate(String todayDay, String todayNight, String highTemp, String lowTemp,
                                  String temp, String feelsLike, String weatherText,
                                  String humidity, String uv, String windScale,
                                  String visibility, String minutelySummary, String alertSummary) {
        StringBuilder sb = new StringBuilder();

        // ① 今日天气概述 + 温差
        boolean hasDay = !isEmpty(todayDay);
        boolean hasNight = !isEmpty(todayNight);
        if (hasDay || hasNight) {
            if (hasDay && hasNight) {
                if (todayDay.equals(todayNight)) {
                    sb.append("今天全天").append(todayDay);
                } else {
                    sb.append("今天白天").append(todayDay).append("，夜间").append(todayNight);
                }
            } else if (hasDay) {
                sb.append("今天白天").append(todayDay);
            } else {
                sb.append("今天夜间").append(todayNight);
            }
            if (!isEmpty(highTemp) && !isEmpty(lowTemp) && !"--".equals(highTemp) && !"--".equals(lowTemp)) {
                try {
                    int high = Integer.parseInt(highTemp);
                    int low = Integer.parseInt(lowTemp);
                    int diff = high - low;
                    sb.append("，").append(low).append("~").append(high).append("°");
                    if (diff >= 10) sb.append("，温差").append(diff).append("°注意添减衣物");
                } catch (NumberFormatException ignored) {
                }
            }
            sb.append("。");
        }

        // ② 当前体感与能见度
        boolean hasTemp = !isEmpty(temp) && !"--".equals(temp);
        boolean hasFeels = !isEmpty(feelsLike) && !"--".equals(feelsLike);
        boolean hasVis = !isEmpty(visibility) && !"--".equals(visibility);
        if (hasTemp || hasFeels || hasVis) {
            sb.append(" 当前");
            if (hasTemp) sb.append(temp).append("°");
            if (hasFeels && hasTemp) {
                try {
                    int diff = Integer.parseInt(feelsLike) - Integer.parseInt(temp);
                    if (diff >= 3) sb.append("，体感更热约").append(feelsLike).append("°");
                    else if (diff <= -3) sb.append("，体感更冷约").append(feelsLike).append("°");
                } catch (NumberFormatException ignored) {
                }
            } else if (hasFeels) {
                sb.append("，体感").append(feelsLike).append("°");
            }
            if (hasVis) {
                try {
                    double vis = Double.parseDouble(visibility);
                    if (vis < 1) sb.append("，能见度极差(<1km)");
                    else if (vis < 5) sb.append("，能见度差(").append(visibility).append("km)");
                    else if (vis < 10) sb.append("，能见度一般(").append(visibility).append("km)");
                    else sb.append("，能见度良好(").append(visibility).append("km)");
                } catch (NumberFormatException ignored) {
                }
            }
            sb.append("。");
        }

        // ③ 天气状况提醒（雨/雪/雾/沙尘/温度）
        if (!isEmpty(weatherText) && !"--".equals(weatherText)) {
            boolean added = false;
            if (weatherText.contains("暴雨") || weatherText.contains("大暴雨")) {
                sb.append(" 暴雨天气，避免外出。");
                added = true;
            } else if (weatherText.contains("大雨")) {
                sb.append(" 雨势较大，出门带伞。");
                added = true;
            } else if (weatherText.contains("雨")) {
                sb.append(" 有降雨，建议携带雨具。");
                added = true;
            } else if (weatherText.contains("暴") && weatherText.contains("雪")) {
                sb.append(" 暴雪天气，注意保暖防滑。");
                added = true;
            } else if (weatherText.contains("雪")) {
                sb.append(" 有降雪，注意路滑。");
                added = true;
            } else if (weatherText.contains("雾") || weatherText.contains("霾")) {
                sb.append(" 能见度低，出行注意安全。");
                added = true;
            } else if (weatherText.contains("沙尘")) {
                sb.append(" 沙尘天气，佩戴口罩。");
                added = true;
            }
            if (!added && hasTemp) {
                try {
                    int t = Integer.parseInt(temp);
                    if (t <= 0) sb.append(" 天气严寒，注意防冻。");
                    else if (t <= 5) sb.append(" 天气寒冷，注意保暖。");
                    else if (t >= 35) sb.append(" 酷热天气，注意防暑。");
                    else if (t >= 30) sb.append(" 天气炎热，多补充水分。");
                    else if (t > 15 && t < 30 && weatherText.contains("晴")) {
                        sb.append(" 天气不错，适合外出活动。");
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // ④ 湿度/紫外线/风力提示
        java.util.List<String> envs = new java.util.ArrayList<>();
        if (!isEmpty(humidity) && !"--".equals(humidity)) {
            try {
                int h = Integer.parseInt(humidity);
                if (h <= 30) envs.add("空气干燥注意补水");
                else if (h >= 80) envs.add("湿度高体感闷热");
            } catch (NumberFormatException ignored) {
            }
        }
        if (!isEmpty(uv) && !"--".equals(uv)) {
            try {
                int uvVal = Integer.parseInt(uv);
                if (uvVal >= 8) envs.add("紫外线强务必防晒");
                else if (uvVal >= 5) envs.add("紫外线较强建议防晒");
            } catch (NumberFormatException ignored) {
            }
        }
        if (!isEmpty(windScale) && !"--".equals(windScale)) {
            try {
                int wind = Integer.parseInt(windScale);
                if (wind >= 8) envs.add("风力极大减少外出");
                else if (wind >= 6) envs.add("风力较大注意安全");
            } catch (NumberFormatException ignored) {
            }
        }
        if (!envs.isEmpty()) {
            sb.append(" ").append(String.join("，", envs)).append("。");
        }

        // ⑤ 降水预报
        if (!isEmpty(minutelySummary)) {
            sb.append(" ").append(minutelySummary).append("。");
        }

        // ⑥ 天气预警
        if (!isEmpty(alertSummary)) {
            sb.append(" ⚠").append(alertSummary).append("。");
        }

        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
