package com.oilquiz.app.weather;

import android.util.Log;

import com.oilquiz.app.weather.model.AirQualityData;
import com.oilquiz.app.weather.model.DailyForecastData;
import com.oilquiz.app.weather.model.HourlyForecastData;
import com.oilquiz.app.weather.model.WeatherNowData;

import java.util.ArrayList;
import java.util.List;

public class WeatherDataParser {

    private static final String TAG = "WeatherDataParser";

    public static WeatherNowData getEmptyData() {
        WeatherNowData data = new WeatherNowData();
        data.temp = "";
        data.text = "";
        data.iconCode = "";
        data.feelsLike = "";
        data.humidity = "";
        data.windDir = "";
        data.windScale = "";
        data.windSpeed = "";
        data.precip = "";
        data.pressure = "";
        data.visibility = "";
        data.cloud = "";
        data.dew = "";
        data.highTemp = "";
        data.lowTemp = "";
        data.uvIndex = "";
        data.sunRise = "";
        data.sunSet = "";
        data.cityName = "";
        data.fxLink = "";
        return data;
    }

    public static WeatherNowData parseNowFromText(String text) {
        if (text == null || text.isEmpty()) return getEmptyData();

        WeatherNowData data = getEmptyData();
        String[] lines = text.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("城市:")) {
                data.cityName = line.substring(3).trim();
            } else if (line.startsWith("天气:")) {
                data.text = line.substring(3).trim();
            } else if (line.startsWith("图标:")) {
                data.iconCode = line.substring(3).trim();
            } else if (line.startsWith("温度:")) {
                data.temp = line.substring(3).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("体感温度:")) {
                data.feelsLike = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("湿度:")) {
                data.humidity = line.substring(3).trim().replace("%", "");
            } else if (line.startsWith("风向:")) {
                data.windDir = line.substring(3).trim();
            } else if (line.startsWith("风力:")) {
                data.windScale = line.substring(3).trim().replace("级", "");
            } else if (line.startsWith("风速:")) {
                data.windSpeed = line.substring(3).trim().replace("km/h", "");
            } else if (line.startsWith("能见度:")) {
                data.visibility = line.substring(4).trim().replace("km", "");
            } else if (line.startsWith("气压:")) {
                data.pressure = line.substring(3).trim().replace("hPa", "");
            } else if (line.startsWith("降水量:")) {
                data.precip = line.substring(4).trim().replace("mm", "");
            } else if (line.startsWith("云量:")) {
                data.cloud = line.substring(3).trim().replace("%", "");
            } else if (line.startsWith("露点温度:")) {
                data.dew = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("风向角度:")) {
                data.wind360 = line.substring(4).trim();
            } else if (line.startsWith("最高温度:")) {
                data.highTemp = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("最低温度:")) {
                data.lowTemp = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("紫外线:")) {
                data.uvIndex = line.substring(4).trim();
            } else if (line.startsWith("日出:")) {
                data.sunRise = line.substring(3).trim();
            } else if (line.startsWith("日落:")) {
                data.sunSet = line.substring(3).trim();
            } else if (line.startsWith("链接:") || line.startsWith("链接:")) {
                data.fxLink = line.substring(4).trim();
            } else if (line.startsWith("更新时间:")) {
                data.updateTime = line.substring(5).trim();
            } else if (line.startsWith("观测时间:")) {
                data.obsTime = line.substring(5).trim();
            }
        }

        return data;
    }

    public static List<HourlyForecastData> parseHourlyFromText(String text) {
        List<HourlyForecastData> list = new ArrayList<>();
        if (text == null || text.isEmpty()) return list;

        String[] lines = text.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("24小时预报") || line.startsWith("链接:")) continue;

            HourlyForecastData item = new HourlyForecastData();

            String[] parts = line.split("\\s+");
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (part.matches("\\d{2}:\\d{2}")) {
                    item.time = part;
                } else if (part.endsWith("°C")) {
                    item.temp = part.replace("°C", "");
                } else if (part.matches("降水\\d+%")) {
                    item.pop = part.replace("降水", "").replace("%", "");
                } else {
                    if (item.text == null || item.text.isEmpty()) {
                        item.text = part;
                    }
                }
            }

            if (item.time != null && !item.time.isEmpty()) {
                list.add(item);
            }
        }

        return list;
    }

    public static List<DailyForecastData> parseDailyFromText(String text) {
        List<DailyForecastData> list = new ArrayList<>();
        if (text == null || text.isEmpty()) return list;

        String[] lines = text.split("\n");
        DailyForecastData current = null;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("天气预报:") || line.startsWith("链接:")) continue;

            if (line.matches("\\d{4}-\\d{2}-\\d{2}")) {
                current = new DailyForecastData();
                current.date = line;
                list.add(current);
            } else if (current != null) {
                if (line.startsWith("白天:")) {
                    String val = line.substring(3).trim();
                    String[] parts = val.split("\\s+");
                    for (String p : parts) {
                        if (p.endsWith("°C")) {
                            current.tempMax = p.replace("°C", "");
                        } else if (current.textDay == null || current.textDay.isEmpty()) {
                            current.textDay = p;
                        }
                    }
                } else if (line.startsWith("夜间:")) {
                    String val = line.substring(3).trim();
                    String[] parts = val.split("\\s+");
                    for (String p : parts) {
                        if (p.endsWith("°C")) {
                            current.tempMin = p.replace("°C", "");
                        } else if (current.textNight == null || current.textNight.isEmpty()) {
                            current.textNight = p;
                        }
                    }
                } else if (line.startsWith("日出:")) {
                    current.sunRise = line.substring(3).trim();
                } else if (line.startsWith("日落:")) {
                    current.sunSet = line.substring(3).trim();
                } else if (line.startsWith("风向:")) {
                    String val = line.substring(3).trim();
                    String[] parts = val.split("\\s+");
                    if (parts.length >= 1) current.windDirDay = parts[0];
                    if (parts.length >= 2) current.windScaleDay = parts[1].replace("级", "");
                } else if (line.startsWith("湿度:")) {
                    String val = line.substring(3).trim();
                    String[] parts = val.split("\\s+");
                    for (String p : parts) {
                        if (p.endsWith("%")) {
                            current.humidity = p.replace("%", "");
                        } else if (!p.isEmpty() && (current.uvIndex == null || current.uvIndex.isEmpty())) {
                            current.uvIndex = p;
                        }
                    }
                }
            }
        }

        return list;
    }

    public static AirQualityData parseAirFromText(String text) {
        AirQualityData data = new AirQualityData();
        if (text == null || text.isEmpty()) return data;

        String[] lines = text.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("空气质量:") || line.startsWith("链接:")) continue;

            if (line.startsWith("AQI:")) {
                data.aqi = line.substring(4).trim();
            } else if (line.startsWith("等级:")) {
                data.category = line.substring(3).trim();
            } else if (line.startsWith("PM2.5:")) {
                data.pm2p5 = line.substring(6).trim();
            } else if (line.startsWith("PM10:")) {
                data.pm10 = line.substring(5).trim();
            } else if (line.startsWith("NO2:")) {
                data.no2 = line.substring(4).trim();
            } else if (line.startsWith("SO2:")) {
                data.so2 = line.substring(4).trim();
            } else if (line.startsWith("CO:")) {
                data.co = line.substring(3).trim();
            } else if (line.startsWith("O3:")) {
                data.o3 = line.substring(3).trim();
            }
        }

        return data;
    }

    public static String normalizeNowText(String rawText) {
        if (rawText == null || rawText.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        String[] lines = rawText.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("天气信息:") || line.startsWith("天气:")) {
                if (line.startsWith("天气信息:")) continue;
            }
            sb.append(line).append("\n");
        }

        return sb.toString().trim();
    }
}