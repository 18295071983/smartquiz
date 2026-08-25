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

    // ============================================================
    // 分钟级降水摘要 / 预警摘要解析（与天气详情页同款逻辑，供横幅共用）
    // ============================================================

    /**
     * 解析分钟级降水文本 → 摘要（与详情页 parseAndUpdateMinutely 的 summary 同款）。
     * 无降水时返回空串（总介绍不显示该段，与详情页 minutelySummary = 无雨时 "" 一致）。
     * 输入示例：
     * <pre>
     * 分钟级降水:
     * 摘要: 未来2小时无降水
     * 13:00:0.0mm
     * 13:05:0.2mm
     * ...
     * </pre>
     */
    public static String parseMinutelySummary(String minutelyText) {
        if (minutelyText == null || minutelyText.isEmpty()) return "";
        String[] lines = minutelyText.split("\n");
        boolean inSection = false;
        String apiSummary = "";
        double totalPrecip = 0;
        String precipType = "";
        java.util.List<boolean[]> intervals = new java.util.ArrayList<>(); // {isRaining, hasData}
        java.util.List<String> timeLabels = new java.util.ArrayList<>();

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("分钟级降水:")) { inSection = true; continue; }
            if (inSection && line.startsWith("链接:")) continue;
            if (line.startsWith("摘要:")) { apiSummary = line.substring(3).trim(); continue; }
            if (inSection && line.startsWith("未来")) continue;
            if (!inSection || line.isEmpty()) continue;
            int colonIdx = line.lastIndexOf(':');
            if (colonIdx > 0) {
                String timeStr = line.substring(0, colonIdx).trim();
                String precipPart = line.substring(colonIdx + 1).trim();
                if (precipPart.contains("雪")) precipType = "雪";
                else if (precipPart.contains("雨")) precipType = "雨";
                try {
                    String numStr = precipPart.replaceAll("[^0-9.]", "").trim();
                    if (!numStr.isEmpty()) {
                        double precip = Double.parseDouble(numStr);
                        intervals.add(new boolean[]{precip > 0, true});
                        timeLabels.add(timeStr);
                        if (precip > 0) totalPrecip += precip;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // 分析降水段/间歇段
        int rainMinutes = 0, gapMinutes = 0, rainSegments = 0;
        int currentSegMinutes = 0, currentGapMinutes = 0;
        String rainStopTime = "";
        boolean inRain = false, hasRained = false;
        for (int i = 0; i < intervals.size(); i++) {
            boolean isRaining = intervals.get(i)[0];
            if (isRaining) {
                if (!inRain) {
                    rainSegments++;
                    inRain = true;
                    if (hasRained) gapMinutes += currentGapMinutes;
                    currentGapMinutes = 0;
                }
                currentSegMinutes += 5;
                rainMinutes += 5;
                hasRained = true;
            } else {
                if (inRain) {
                    inRain = false;
                    currentSegMinutes = 0;
                    currentGapMinutes = 5;
                    if (i < timeLabels.size()) rainStopTime = timeLabels.get(i);
                } else if (hasRained) {
                    currentGapMinutes += 5;
                }
            }
        }
        if (inRain) rainStopTime = "";

        if (rainMinutes == 0) return ""; // 无降水：总介绍不显示（与详情页一致）

        String levelText = precipLevelText(totalPrecip, precipType);
        String typeText = precipType.isEmpty() ? "降水" : precipType;
        boolean intermittent = rainSegments > 1;
        StringBuilder sb = new StringBuilder();
        if (intermittent) {
            sb.append(String.format("间歇性%s，降水持续%d分钟，间歇%d分钟", typeText, rainMinutes, gapMinutes));
        } else {
            sb.append(String.format("持续%s%d分钟", typeText, rainMinutes));
        }
        sb.append(String.format("，累计%.1fmm（%s）", totalPrecip, levelText));
        if (!rainStopTime.isEmpty()) {
            sb.append("，").append(rainStopTime).append("停");
        } else {
            sb.append("，2小时内不会停");
        }
        String summary = sb.toString();
        if (!apiSummary.isEmpty()) {
            summary = apiSummary + "，" + summary;
        }
        return summary;
    }

    /** 2小时降水量等级文字（与详情页同款） */
    private static String precipLevelText(double mm, String type) {
        if ("雪".equals(type)) {
            if (mm < 1) return "小雪";
            if (mm < 3) return "中雪";
            if (mm < 5) return "大雪";
            if (mm < 10) return "暴雪";
            return "大暴雪";
        }
        if (mm < 0.1) return "微量";
        if (mm < 4) return "小雨";
        if (mm < 12) return "中雨";
        if (mm < 25) return "大雨";
        if (mm < 50) return "暴雨";
        if (mm < 100) return "大暴雨";
        return "特大暴雨";
    }

    /** 预警信息结构（与详情页 AlertInfo 同款字段） */
    private static class AlertInfo {
        String title = "天气预警";
        String level = "预警";
        String summary = "";
        String description = "";
        String defense = "";
        String publishTime = "--";
    }

    /**
     * 解析天气预警文本 → 摘要（与详情页 parseAndUpdateAlerts + buildAlertSummary 同款）。
     * 无预警时返回空串（总介绍不显示该段）。
     */
    public static String parseAlertSummary(String alertsText) {
        if (alertsText == null || alertsText.isEmpty()) return "";

        // 拆分多条预警（空行分隔；"暂无预警信息"直接结束）
        boolean inAlertSection = false;
        java.util.List<String> alertTexts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : alertsText.split("\n")) {
            line = line.trim();
            if (line.startsWith("天气预警:") || line.startsWith("预警:")) { inAlertSection = true; continue; }
            if (inAlertSection && line.startsWith("链接:")) continue;
            if (!inAlertSection) continue;
            if (line.isEmpty()) {
                if (current.length() > 0) { alertTexts.add(current.toString()); current.setLength(0); }
            } else if (line.equals("暂无预警信息") || line.equals("当前无天气预警")) {
                break;
            } else {
                if (current.length() > 0) current.append("\n");
                current.append(line);
            }
        }
        if (current.length() > 0) alertTexts.add(current.toString());

        if (alertTexts.isEmpty()) return "";

        java.util.List<AlertInfo> infos = new java.util.ArrayList<>();
        for (String text : alertTexts) infos.add(parseAlertInfo(text));
        return buildAlertSummary(infos);
    }

    /** 解析单条预警文本（与详情页 parseAlertInfo 同款，仅保留摘要所需字段） */
    private static AlertInfo parseAlertInfo(String text) {
        AlertInfo info = new AlertInfo();
        StringBuilder descBuilder = new StringBuilder();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("【") && info.title.equals("天气预警")) {
                info.title = line;
                if (line.contains("红")) info.level = "红色";
                else if (line.contains("橙")) info.level = "橙色";
                else if (line.contains("黄")) info.level = "黄色";
                else if (line.contains("蓝")) info.level = "蓝色";
                continue;
            }
            if (line.startsWith("标题:")) {
                info.title = line.substring(3).trim();
            } else if (line.startsWith("预警等级:")) {
                info.level = line.substring(5).trim();
            } else if (line.startsWith("发布时间:")) {
                info.publishTime = line.substring(5).trim();
            } else if (line.startsWith("内容:")) {
                info.description = line.substring(3).trim();
            } else if (line.startsWith("防御指南:")) {
                info.defense = line.substring(5).trim();
            } else if (line.length() > 20) {
                if (descBuilder.length() > 0) descBuilder.append("\n");
                descBuilder.append(line);
            } else if (info.summary.isEmpty() && line.length() > 5) {
                info.summary = line;
            }
        }
        if (info.description.isEmpty() && descBuilder.length() > 0) {
            info.description = descBuilder.toString();
        }
        if (info.defense.isEmpty()) {
            int idx = info.description.indexOf("防御");
            if (idx >= 0) {
                info.defense = info.description.substring(idx).trim();
                info.description = info.description.substring(0, idx).trim();
            }
        }
        return info;
    }

    /** 构建预警摘要（与详情页 buildAlertSummary 同款：最新一条 + 其他数量） */
    private static String buildAlertSummary(java.util.List<AlertInfo> alertInfos) {
        if (alertInfos == null || alertInfos.isEmpty()) return "";
        java.util.List<AlertInfo> sorted = new java.util.ArrayList<>(alertInfos);
        sorted.sort((a, b) -> {
            String ta = a.publishTime == null ? "" : a.publishTime;
            String tb = b.publishTime == null ? "" : b.publishTime;
            return tb.compareTo(ta); // 降序，最新在前
        });
        AlertInfo latest = sorted.get(0);
        StringBuilder sb = new StringBuilder();

        String type = extractAlertType(latest.title);
        String level = latest.level;
        if (level.isEmpty() || level.equals("预警")) {
            level = extractAlertColor(latest.title);
        }
        if (!level.isEmpty() && !level.equals("预警")) {
            sb.append("[").append(level).append(type).append("]");
        } else {
            sb.append("[").append(type).append("预警]");
        }
        if (!latest.description.isEmpty()) {
            sb.append("：").append(latest.description.trim());
        }
        if (!latest.defense.isEmpty()) {
            if (!endsWithPeriod(sb)) sb.append("。");
            sb.append(latest.defense.trim());
        }
        if (sorted.size() > 1) {
            sb.append("（另有");
            for (int i = 1; i < sorted.size(); i++) {
                AlertInfo other = sorted.get(i);
                String otherType = extractAlertType(other.title);
                String otherLevel = extractAlertColor(other.title);
                if (i > 1) sb.append("、");
                if (!otherLevel.isEmpty()) sb.append(otherLevel).append(otherType);
                else sb.append(otherType).append("预警");
            }
            sb.append("）");
        }
        return sb.toString();
    }

    private static String extractAlertType(String title) {
        if (title == null || title.isEmpty()) return "天气";
        String s = title;
        int bracketEnd = s.indexOf('】');
        if (bracketEnd >= 0) s = s.substring(bracketEnd + 1);
        int parenIdx = s.indexOf('(');
        if (parenIdx > 0) s = s.substring(0, parenIdx);
        s = s.trim();
        return s.isEmpty() ? "天气" : s;
    }

    private static String extractAlertColor(String title) {
        if (title == null) return "";
        if (title.contains("红")) return "红色";
        if (title.contains("橙")) return "橙色";
        if (title.contains("黄")) return "黄色";
        if (title.contains("蓝")) return "蓝色";
        return "";
    }

    private static boolean endsWithPeriod(StringBuilder sb) {
        if (sb.length() == 0) return true;
        char last = sb.charAt(sb.length() - 1);
        return last == '。' || last == '.' || last == '；' || last == ';';
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }
}
