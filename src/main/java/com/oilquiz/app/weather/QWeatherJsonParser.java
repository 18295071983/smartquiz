package com.oilquiz.app.weather;

import android.util.Log;

import com.oilquiz.app.weather.model.AirQualityData;
import com.oilquiz.app.weather.model.WeatherIndicesData;
import com.oilquiz.app.weather.model.WeatherNowData;
import com.oilquiz.app.weather.model.WeatherWarningData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class QWeatherJsonParser {

    private static final String TAG = "QWeatherJsonParser";

    public static WeatherNowData parseWeatherNow(String jsonStr) {
        WeatherNowData data = new WeatherNowData();
        try {
            JSONObject json = new JSONObject(jsonStr);
            String code = json.optString("code", "");
            if (!"200".equals(code)) {
                Log.w(TAG, "Weather now response code: " + code);
                return data;
            }

            data.updateTime = json.optString("updateTime", "");
            data.fxLink = json.optString("fxLink", "");

            JSONObject now = json.optJSONObject("now");
            if (now != null) {
                data.obsTime = now.optString("obsTime", "");
                data.temp = now.optString("temp", "");
                data.feelsLike = now.optString("feelsLike", "");
                data.iconCode = now.optString("icon", "");
                data.text = now.optString("text", "");
                data.wind360 = now.optString("wind360", "");
                data.windDir = now.optString("windDir", "");
                data.windScale = now.optString("windScale", "");
                data.windSpeed = now.optString("windSpeed", "");
                data.humidity = now.optString("humidity", "");
                data.precip = now.optString("precip", "");
                data.pressure = now.optString("pressure", "");
                data.visibility = now.optString("vis", "");
                data.cloud = now.optString("cloud", "");
                data.dew = now.optString("dew", "");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing weather now JSON", e);
        }
        return data;
    }

    public static AirQualityData parseAirQuality(String jsonStr) {
        AirQualityData data = new AirQualityData();
        try {
            JSONObject json = new JSONObject(jsonStr);

            JSONObject metadata = json.optJSONObject("metadata");

            JSONArray indexes = json.optJSONArray("indexes");
            if (indexes != null && indexes.length() > 0) {
                JSONObject mainIndex = indexes.getJSONObject(0);
                data.aqi = mainIndex.optString("aqi", "");
                data.aqiDisplay = mainIndex.optString("aqiDisplay", "");
                data.category = mainIndex.optString("category", "");
                data.level = mainIndex.optString("level", "");
                data.aqiCode = mainIndex.optString("code", "");
                data.aqiName = mainIndex.optString("name", "");
                data.isQAQI = "qaqi".equalsIgnoreCase(data.aqiCode);

                JSONObject color = mainIndex.optJSONObject("color");
                if (color != null) {
                    data.colorRed = String.valueOf(color.optInt("red", 0));
                    data.colorGreen = String.valueOf(color.optInt("green", 0));
                    data.colorBlue = String.valueOf(color.optInt("blue", 0));
                    data.colorAlpha = String.valueOf(color.optDouble("alpha", 1.0));
                }

                JSONObject primaryPollutant = mainIndex.optJSONObject("primaryPollutant");
                if (primaryPollutant != null) {
                    data.primaryPollutantCode = primaryPollutant.optString("code", "");
                    data.primaryPollutantName = primaryPollutant.optString("name", "");
                }

                JSONObject health = mainIndex.optJSONObject("health");
                if (health != null) {
                    data.healthEffect = health.optString("effect", "");
                    JSONObject advice = health.optJSONObject("advice");
                    if (advice != null) {
                        data.healthGeneralAdvice = advice.optString("generalPopulation", "");
                        data.healthSensitiveAdvice = advice.optString("sensitivePopulation", "");
                    }
                }

                for (int i = 1; i < indexes.length(); i++) {
                    JSONObject idx = indexes.getJSONObject(i);
                    AirQualityData.AirQualityIndex aqIndex = new AirQualityData.AirQualityIndex();
                    aqIndex.code = idx.optString("code", "");
                    aqIndex.name = idx.optString("name", "");
                    aqIndex.aqi = idx.optDouble("aqi", 0);
                    aqIndex.aqiDisplay = idx.optString("aqiDisplay", "");
                    aqIndex.level = idx.optString("level", "");
                    aqIndex.category = idx.optString("category", "");
                    data.additionalIndexes.add(aqIndex);
                }
            }

            JSONArray pollutants = json.optJSONArray("pollutants");
            if (pollutants != null) {
                for (int i = 0; i < pollutants.length(); i++) {
                    JSONObject p = pollutants.getJSONObject(i);
                    AirQualityData.PollutantData pollutant = new AirQualityData.PollutantData();
                    pollutant.code = p.optString("code", "");
                    pollutant.name = p.optString("name", "");
                    pollutant.fullName = p.optString("fullName", "");

                    JSONObject concentration = p.optJSONObject("concentration");
                    if (concentration != null) {
                        pollutant.concentrationValue = concentration.optDouble("value", 0);
                        pollutant.concentrationUnit = concentration.optString("unit", "");
                    }

                    JSONArray subIndexes = p.optJSONArray("subIndexes");
                    if (subIndexes != null) {
                        for (int j = 0; j < subIndexes.length(); j++) {
                            JSONObject si = subIndexes.getJSONObject(j);
                            AirQualityData.SubIndexData subIndex = new AirQualityData.SubIndexData();
                            subIndex.code = si.optString("code", "");
                            subIndex.aqi = si.optDouble("aqi", 0);
                            subIndex.aqiDisplay = si.optString("aqiDisplay", "");
                            pollutant.subIndexes.add(subIndex);
                        }
                    }

                    data.pollutants.add(pollutant);

                    switch (pollutant.code) {
                        case "pm2p5":
                            data.pm2p5 = pollutant.getDisplayConcentration();
                            break;
                        case "pm10":
                            data.pm10 = pollutant.getDisplayConcentration();
                            break;
                        case "no2":
                            data.no2 = pollutant.getDisplayConcentration();
                            break;
                        case "so2":
                            data.so2 = pollutant.getDisplayConcentration();
                            break;
                        case "co":
                            data.co = pollutant.getDisplayConcentration();
                            break;
                        case "o3":
                            data.o3 = pollutant.getDisplayConcentration();
                            break;
                    }
                }
            }

            JSONArray stations = json.optJSONArray("stations");
            if (stations != null) {
                for (int i = 0; i < stations.length(); i++) {
                    JSONObject s = stations.getJSONObject(i);
                    AirQualityData.StationData station = new AirQualityData.StationData();
                    station.id = s.optString("id", "");
                    station.name = s.optString("name", "");
                    data.stations.add(station);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing air quality JSON", e);
        }
        return data;
    }

    public static WeatherIndicesData parseIndices(String jsonStr) {
        WeatherIndicesData data = new WeatherIndicesData();
        try {
            JSONObject json = new JSONObject(jsonStr);
            String code = json.optString("code", "");
            if (!"200".equals(code)) {
                Log.w(TAG, "Indices response code: " + code);
                return data;
            }

            data.updateTime = json.optString("updateTime", "");
            data.fxLink = json.optString("fxLink", "");

            JSONArray daily = json.optJSONArray("daily");
            if (daily != null) {
                for (int i = 0; i < daily.length(); i++) {
                    JSONObject item = daily.getJSONObject(i);
                    WeatherIndicesData.IndexItem indexItem = new WeatherIndicesData.IndexItem();
                    indexItem.date = item.optString("date", "");
                    indexItem.type = item.optString("type", "");
                    indexItem.name = item.optString("name", "");
                    indexItem.level = item.optString("level", "");
                    indexItem.category = item.optString("category", "");
                    indexItem.text = item.optString("text", "");
                    data.indices.add(indexItem);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing indices JSON", e);
        }
        return data;
    }

    public static WeatherWarningData parseWarnings(String jsonStr) {
        WeatherWarningData data = new WeatherWarningData();
        try {
            JSONObject json = new JSONObject(jsonStr);
            String code = json.optString("code", "");
            if (!"200".equals(code)) {
                Log.w(TAG, "Warnings response code: " + code);
                return data;
            }

            data.updateTime = json.optString("updateTime", "");
            data.fxLink = json.optString("fxLink", "");

            JSONArray warning = json.optJSONArray("warning");
            if (warning != null) {
                for (int i = 0; i < warning.length(); i++) {
                    JSONObject w = warning.getJSONObject(i);
                    WeatherWarningData.WarningItem item = new WeatherWarningData.WarningItem();
                    item.id = w.optString("id", "");
                    item.sender = w.optString("sender", "");
                    item.pubTime = w.optString("pubTime", "");
                    item.title = w.optString("title", "");
                    item.startTime = w.optString("startTime", "");
                    item.endTime = w.optString("endTime", "");
                    item.status = w.optString("status", "");
                    item.level = w.optString("level", "");
                    item.severity = w.optString("severity", "");
                    item.severityColor = w.optString("severityColor", "");
                    item.type = w.optString("type", "");
                    item.typeName = w.optString("typeName", "");
                    item.urgency = w.optString("urgency", "");
                    item.certainty = w.optString("certainty", "");
                    item.text = w.optString("text", "");
                    item.related = w.optString("related", "");
                    data.warnings.add(item);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing warnings JSON", e);
        }
        return data;
    }

    public static String formatAirQualityText(AirQualityData data) {
        if (data == null || !data.hasValidData()) {
            return "空气质量: 暂无数据";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("空气质量:\n");
        sb.append("AQI: ").append(data.aqiDisplay != null ? data.aqiDisplay : data.aqi).append("\n");
        sb.append("等级: ").append(data.category).append("\n");

        if (data.primaryPollutantName != null && !data.primaryPollutantName.isEmpty()) {
            sb.append("首要污染物: ").append(data.primaryPollutantName).append("\n");
        }

        if (data.healthEffect != null && !data.healthEffect.isEmpty()) {
            sb.append("健康影响: ").append(data.healthEffect).append("\n");
        }

        if (data.healthGeneralAdvice != null && !data.healthGeneralAdvice.isEmpty()) {
            sb.append("建议: ").append(data.healthGeneralAdvice).append("\n");
        }

        if (data.pollutants != null && !data.pollutants.isEmpty()) {
            sb.append("\n污染物详情:\n");
            for (AirQualityData.PollutantData p : data.pollutants) {
                sb.append(p.name).append(": ").append(p.getDisplayConcentration()).append("\n");
            }
        }

        return sb.toString();
    }

    public static String formatIndicesText(WeatherIndicesData data) {
        if (data == null || !data.hasValidData()) {
            return "生活指数: 暂无数据";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("生活指数:\n");

        for (WeatherIndicesData.IndexItem item : data.indices) {
            if (item.hasValidData()) {
                sb.append(item.name).append(": ").append(item.category);
                if (item.text != null && !item.text.isEmpty()) {
                    sb.append(" - ").append(item.text);
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    public static String formatWarningsText(WeatherWarningData data) {
        if (data == null || !data.hasValidData()) {
            return "天气预警:\n当前无天气预警";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("天气预警:\n");

        for (WeatherWarningData.WarningItem item : data.warnings) {
            sb.append("【").append(item.typeName).append("】");
            if (item.severity != null && !item.severity.isEmpty()) {
                sb.append("[").append(item.getSeverityDisplayName()).append("]");
            }
            sb.append("\n");
            if (item.title != null && !item.title.isEmpty()) {
                sb.append(item.title).append("\n");
            }
            if (item.sender != null && !item.sender.isEmpty()) {
                sb.append("发布: ").append(item.sender).append("\n");
            }
            if (item.pubTime != null && !item.pubTime.isEmpty()) {
                sb.append("时间: ").append(item.pubTime).append("\n");
            }
            if (item.text != null && !item.text.isEmpty()) {
                sb.append(item.text).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
