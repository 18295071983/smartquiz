package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.oilquiz.app.ai.util.APIKeyManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.HashMap;

public class AIWeatherManager implements AITool {

    private static final String TAG = "AIWeatherManager";
    
    // OpenWeatherMap API URLs
    private static final String CURRENT_WEATHER_URL = "https://api.openweathermap.org/data/2.5/weather";
    private static final String ONE_CALL_URL = "https://api.openweathermap.org/data/3.0/onecall";
    private static final String ONE_CALL_TIMESTAMP_URL = "https://api.openweathermap.org/data/3.0/onecall/timemachine";
    private static final String ONE_CALL_DAILY_AGGREGATION_URL = "https://api.openweathermap.org/data/3.0/onecall/day_summary";
    private static final String ONE_CALL_OVERVIEW_URL = "https://api.openweathermap.org/data/3.0/onecall/overview";
    
    // 和风天气 API URLs
    private static final String HEFENG_API_HOST = "https://m278m2y7ak.re.qweatherapi.com";
    private static final String HEFENG_GEO_HOST = "https://m278m2y7ak.re.qweatherapi.com";
    private static final String HEFENG_WEATHER_URL = HEFENG_API_HOST + "/v7/weather/now";
    private static final String HEFENG_FORECAST_URL = HEFENG_API_HOST + "/v7/weather/7d";
    private static final String HEFENG_HOURLY_URL = HEFENG_API_HOST + "/v7/weather/hourly";
    private static final String HEFENG_AIR_URL = HEFENG_API_HOST + "/v7/air/now";
    private static final String HEFENG_ALERT_URL = HEFENG_API_HOST + "/v7/warning/now";
    private static final String HEFENG_INDICES_URL = HEFENG_API_HOST + "/v7/indices/1d";
    private static final String HEFENG_GEOCODE_URL = HEFENG_GEO_HOST + "/v2/city/lookup";
    private static final String HEFENG_DEFAULT_API_KEY = "be2af1f8490344feb8a7125ab46608dd";

    private final Context context;
    private final Gson gson;
    private WeatherProvider currentProvider = WeatherProvider.HEFENG;

    public AIWeatherManager(Context context) {
        this.context = context;
        this.gson = new Gson();
    }

    public AIWeatherManager(Context context, WeatherProvider provider) {
        this.context = context;
        this.gson = new Gson();
        this.currentProvider = provider;
    }

    public void setWeatherProvider(WeatherProvider provider) {
        this.currentProvider = provider;
    }

    private String getHefengApiKey() {
        APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
        String apiKey = apiKeyManager.getAPIKey(APIKeyManager.Service.HEFENG_WEATHER);
        if (apiKey == null || apiKey.isEmpty()) {
            Log.w(TAG, "和风天气API Key未配置，使用默认Key");
            return HEFENG_DEFAULT_API_KEY;
        }
        return apiKey;
    }

    private String getOpenWeatherMapApiKey() {
        APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
        String apiKey = apiKeyManager.getAPIKey(APIKeyManager.Service.OPENWEATHERMAP);
        if (apiKey == null || apiKey.isEmpty()) {
            Log.w(TAG, "OpenWeatherMap API Key未配置，使用默认Key");
            return "37574c70b8d19d7db691c97af40b5947";
        }
        return apiKey;
    }

    public WeatherProvider getWeatherProvider() {
        return currentProvider;
    }

    private String httpGet(String urlString, String apiKey) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("X-QW-Api-Key", apiKey);
        connection.setRequestProperty("Accept-Encoding", "gzip, deflate");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
            StringBuilder errorResponse = new StringBuilder();
            String line;
            while ((line = errorReader.readLine()) != null) {
                errorResponse.append(line);
            }
            errorReader.close();
            throw new Exception("HTTP " + responseCode + ": " + errorResponse.toString());
        }

        String encoding = connection.getContentEncoding();
        InputStream inputStream;
        if ("gzip".equalsIgnoreCase(encoding)) {
            inputStream = new GZIPInputStream(connection.getInputStream());
        } else if ("deflate".equalsIgnoreCase(encoding)) {
            inputStream = new InflaterInputStream(connection.getInputStream());
        } else {
            inputStream = connection.getInputStream();
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        connection.disconnect();

        return response.toString();
    }

    public enum WeatherProvider {
        OPENWEATHERMAP,
        HEFENG
    }

    // 获取当前天气信息（根据选择的提供者调用相应API）
    public CompletableFuture<String> getCurrentWeather(String city) {
        switch (currentProvider) {
            case HEFENG:
                return getHefengCurrentWeather(city);
            case OPENWEATHERMAP:
            default:
                return getOpenWeatherMapCurrentWeather(city);
        }
    }

    public CompletableFuture<String> getCurrentWeatherByLocation(double lat, double lon) {
        switch (currentProvider) {
            case HEFENG:
                return getHefengCurrentWeatherByLocation(lat, lon);
            case OPENWEATHERMAP:
            default:
                return getOpenWeatherMapCurrentWeatherByLocation(lat, lon);
        }
    }

    // OpenWeatherMap 当前天气查询
    private CompletableFuture<String> getOpenWeatherMapCurrentWeather(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getOpenWeatherMapApiKey();
                
                String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.name());
                String urlString = CURRENT_WEATHER_URL + "?q=" + encodedCity + "&appid=" + apiKey + "&units=metric&lang=zh_cn";
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                return parseCurrentWeatherResponse(response.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error getting current weather from OpenWeatherMap", e);
                return "获取天气信息失败: " + e.getMessage();
            }
        });
    }

    // 和风天气当前天气查询
    private CompletableFuture<String> getHefengCurrentWeather(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();

                String locationId = getHefengLocationId(city, apiKey);
                if (locationId == null || locationId.isEmpty()) {
                    return "无法获取城市 " + city + " 的位置ID";
                }

                String urlString = HEFENG_WEATHER_URL + "?location=" + locationId;
                String response = httpGet(urlString, apiKey);
                return parseHefengWeatherResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting current weather from Hefeng", e);
                return "获取天气信息失败: " + e.getMessage();
            }
        });
    }

    private CompletableFuture<String> getHefengCurrentWeatherByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();

                String cityName = getHefengCityNameByLocation(lat, lon, apiKey);

                String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
                String urlString = HEFENG_WEATHER_URL + "?location=" + location;
                String response = httpGet(urlString, apiKey);
                return parseHefengWeatherResponse(response, cityName);
            } catch (Exception e) {
                Log.e(TAG, "Error getting current weather by location from Hefeng", e);
                return "获取天气信息失败: " + e.getMessage();
            }
        });
    }

    private CompletableFuture<String> getOpenWeatherMapCurrentWeatherByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getOpenWeatherMapApiKey();

                String urlString = CURRENT_WEATHER_URL + "?lat=" + lat + "&lon=" + lon + "&appid=" + apiKey + "&units=metric&lang=zh_cn";
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                return parseCurrentWeatherResponse(response.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error getting current weather by location from OpenWeatherMap", e);
                return "获取天气信息失败: " + e.getMessage();
            }
        });
    }

    // 获取和风天气城市ID
    private String getHefengLocationId(String city, String apiKey) throws Exception {
        String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.name());
        String urlString = HEFENG_GEOCODE_URL + "?location=" + encodedCity;
        return getHefengLocationFromGeoAPI(urlString, true, apiKey);
    }

    private String getHefengCityNameByLocation(double lat, double lon, String apiKey) {
        try {
            String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
            String urlString = HEFENG_GEOCODE_URL + "?location=" + location;
            String response = httpGet(urlString, apiKey);

            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            if (jsonObject.has("code") && "200".equals(jsonObject.get("code").getAsString())) {
                JsonArray locationArray = jsonObject.getAsJsonArray("location");
                if (locationArray != null && locationArray.size() > 0) {
                    JsonObject loc = locationArray.get(0).getAsJsonObject();
                    return loc.get("name").getAsString();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get city name from GeoAPI by location", e);
        }
        return null;
    }

    private String getHefengLocationFromGeoAPI(String urlString, boolean returnId, String apiKey) throws Exception {
        String response = httpGet(urlString, apiKey);

        JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
        if (jsonObject.has("code") && "200".equals(jsonObject.get("code").getAsString())) {
            JsonArray locationArray = jsonObject.getAsJsonArray("location");
            if (locationArray != null && locationArray.size() > 0) {
                JsonObject location = locationArray.get(0).getAsJsonObject();
                return returnId ? location.get("id").getAsString() : location.get("name").getAsString();
            }
        }
        return null;
    }

    // 解析和风天气响应
    private String parseHefengWeatherResponse(String response) {
        return parseHefengWeatherResponse(response, null);
    }

    private String parseHefengWeatherResponse(String response, String cityName) {
        try {
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            if (!"200".equals(jsonObject.get("code").getAsString())) {
                return "天气查询失败: " + jsonObject.get("code").getAsString();
            }

            JsonObject now = jsonObject.getAsJsonObject("now");

            String city = cityName;
            if (city == null || city.isEmpty()) {
                if (jsonObject.has("location") && !jsonObject.get("location").isJsonNull()) {
                    JsonObject location = jsonObject.getAsJsonObject("location");
                    if (location.has("name")) {
                        city = location.get("name").getAsString();
                    }
                }
            }
            if (city == null || city.isEmpty()) {
                city = "未知";
            }

            String weather = now.get("text").getAsString();
            String icon = now.has("icon") ? now.get("icon").getAsString() : "";
            String temp = now.get("temp").getAsString();
            String humidity = now.get("humidity").getAsString();
            String windSpeed = now.get("windSpeed").getAsString();
            String windDir = now.get("windDir").getAsString();
            String feelsLike = now.get("feelsLike").getAsString();
            String visibility = now.get("vis").getAsString();

            StringBuilder weatherInfo = new StringBuilder();
            weatherInfo.append("城市: " + city + "\n");
            weatherInfo.append("天气: " + weather + "\n");
            if (!icon.isEmpty()) {
                weatherInfo.append("图标: " + icon + "\n");
            }
            weatherInfo.append("温度: " + temp + "°C\n");
            weatherInfo.append("体感温度: " + feelsLike + "°C\n");
            weatherInfo.append("湿度: " + humidity + "%\n");
            weatherInfo.append("风速: " + windSpeed + " km/h\n");
            weatherInfo.append("风向: " + windDir + "\n");
            weatherInfo.append("能见度: " + visibility + " km\n");

            return "天气信息:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng weather response", e);
            return "解析天气信息失败: " + e.getMessage();
        }
    }

    // 获取和风天气预报（3-7天）
    public CompletableFuture<String> getHefengForecast(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();

                String locationId = getHefengLocationId(city, apiKey);
                if (locationId == null || locationId.isEmpty()) {
                    return "无法获取城市 " + city + " 的位置ID";
                }

                String urlString = HEFENG_FORECAST_URL + "?location=" + locationId;
                String response = httpGet(urlString, apiKey);
                return parseHefengForecastResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng forecast", e);
                return "获取天气预报失败: " + e.getMessage();
            }
        });
    }

    // 解析和风天气预报响应
    private String parseHefengForecastResponse(String response) {
        try {
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            if (!"200".equals(jsonObject.get("code").getAsString())) {
                return "天气预报查询失败: " + jsonObject.get("code").getAsString();
            }

            JsonArray dailyArray = jsonObject.getAsJsonArray("daily");

            StringBuilder weatherInfo = new StringBuilder();

            for (int i = 0; i < dailyArray.size(); i++) {
                JsonObject daily = dailyArray.get(i).getAsJsonObject();
                String date = daily.get("fxDate").getAsString();
                String weather = daily.get("textDay").getAsString();
                String weatherNight = daily.has("textNight") ? daily.get("textNight").getAsString() : "";
                String highTemp = daily.get("tempMax").getAsString();
                String lowTemp = daily.get("tempMin").getAsString();
                String windDir = daily.has("windDirDay") ? daily.get("windDirDay").getAsString() : "";
                String windSpeed = daily.has("windSpeedDay") ? daily.get("windSpeedDay").getAsString() : "";
                String humidity = daily.has("humidity") ? daily.get("humidity").getAsString() : "--";

                weatherInfo.append("\n日期: " + date + "\n");
                weatherInfo.append("天气: " + weather + " / " + weatherNight + "\n");
                weatherInfo.append("温度: " + lowTemp + "°C ~ " + highTemp + "°C\n");
                weatherInfo.append("风向: " + windDir + " " + windSpeed + "\n");
                weatherInfo.append("湿度: " + humidity + "%\n");
            }

            return "天气预报:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng forecast response", e);
            return "解析天气预报失败: " + e.getMessage();
        }
    }

    // 获取和风天气小时预报（24小时）
    public CompletableFuture<String> getHefengHourly(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();

                String locationId = getHefengLocationId(city, apiKey);
                if (locationId == null || locationId.isEmpty()) {
                    return "无法获取城市 " + city + " 的位置ID";
                }

                String urlString = HEFENG_HOURLY_URL + "?location=" + locationId;
                String response = httpGet(urlString, apiKey);
                return parseHefengHourlyResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng hourly forecast", e);
                return "获取小时预报失败: " + e.getMessage();
            }
        });
    }

    // 解析和风天气小时预报响应
    private String parseHefengHourlyResponse(String response) {
        try {
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            if (!"200".equals(jsonObject.get("code").getAsString())) {
                return "小时预报查询失败: " + jsonObject.get("code").getAsString();
            }

            JsonArray hourlyArray = jsonObject.getAsJsonArray("hourly");

            StringBuilder weatherInfo = new StringBuilder();

            for (int i = 0; i < Math.min(24, hourlyArray.size()); i++) {
                JsonObject hourly = hourlyArray.get(i).getAsJsonObject();
                String time = hourly.get("fxTime").getAsString();
                String weather = hourly.get("text").getAsString();
                String temp = hourly.get("temp").getAsString();
                String humidity = hourly.has("humidity") ? hourly.get("humidity").getAsString() : "--";
                String windDir = hourly.has("windDir") ? hourly.get("windDir").getAsString() : "";
                String windSpeed = hourly.has("windSpeed") ? hourly.get("windSpeed").getAsString() : "";
                String pop = hourly.has("pop") ? hourly.get("pop").getAsString() : "0";

                weatherInfo.append(time.substring(11, 16) + " | " + weather + " | " + temp + "°C | 湿度: " + humidity + "% | 降水概率: " + pop + "%\n");
            }

            return "24小时预报:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng hourly response", e);
            return "解析小时预报失败: " + e.getMessage();
        }
    }

    // 获取和风天气空气质量
    public CompletableFuture<String> getHefengAirQuality(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();

                String locationId = getHefengLocationId(city, apiKey);
                if (locationId == null || locationId.isEmpty()) {
                    return "无法获取城市 " + city + " 的位置ID";
                }

                String urlString = HEFENG_AIR_URL + "?location=" + locationId;
                String response = httpGet(urlString, apiKey);
                return parseHefengAirQualityResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng air quality", e);
                return "获取空气质量失败: " + e.getMessage();
            }
        });
    }

    // 解析和风天气空气质量响应
    private String parseHefengAirQualityResponse(String response) {
        try {
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            if (!"200".equals(jsonObject.get("code").getAsString())) {
                return "空气质量查询失败: " + jsonObject.get("code").getAsString();
            }

            JsonObject now = jsonObject.getAsJsonObject("now");

            String aqi = now.get("aqi").getAsString();
            String category = now.has("category") ? now.get("category").getAsString() : "--";
            String primary = now.has("primary") ? now.get("primary").getAsString() : "NA";
            String pm25 = now.has("pm2p5") ? now.get("pm2p5").getAsString() : "--";
            String pm10 = now.has("pm10") ? now.get("pm10").getAsString() : "--";
            String so2 = now.has("so2") ? now.get("so2").getAsString() : "--";
            String no2 = now.has("no2") ? now.get("no2").getAsString() : "--";
            String co = now.has("co") ? now.get("co").getAsString() : "--";
            String o3 = now.has("o3") ? now.get("o3").getAsString() : "--";

            StringBuilder weatherInfo = new StringBuilder();
            weatherInfo.append("AQI: " + aqi + "\n");
            weatherInfo.append("等级: " + category + "\n");
            weatherInfo.append("首要污染物: " + primary + "\n");
            weatherInfo.append("PM2.5: " + pm25 + " μg/m³\n");
            weatherInfo.append("PM10: " + pm10 + " μg/m³\n");
            weatherInfo.append("SO2: " + so2 + " μg/m³\n");
            weatherInfo.append("NO2: " + no2 + " μg/m³\n");
            weatherInfo.append("CO: " + co + " mg/m³\n");
            weatherInfo.append("O3: " + o3 + " μg/m³\n");

            return "空气质量:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng air quality response", e);
            return "解析空气质量失败: " + e.getMessage();
        }
    }

    // 获取和风天气预警信息
    public CompletableFuture<String> getHefengAlerts(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();

                String locationId = getHefengLocationId(city, apiKey);
                if (locationId == null || locationId.isEmpty()) {
                    return "无法获取城市 " + city + " 的位置ID";
                }

                String urlString = HEFENG_ALERT_URL + "?location=" + locationId;
                String response = httpGet(urlString, apiKey);
                return parseHefengAlertsResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng alerts", e);
                return "获取天气预警失败: " + e.getMessage();
            }
        });
    }

    // 解析和风天气预警响应
    private String parseHefengAlertsResponse(String response) {
        try {
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            String code = jsonObject.get("code").getAsString();
            if (!"200".equals(code)) {
                return "天气预警查询失败: " + code;
            }

            JsonArray alertArray = jsonObject.getAsJsonArray("warning");

            StringBuilder weatherInfo = new StringBuilder();

            if (alertArray == null || alertArray.size() == 0) {
                weatherInfo.append("暂无预警信息");
                return "天气预警:\n" + weatherInfo.toString();
            }

            for (int i = 0; i < alertArray.size(); i++) {
                JsonObject alert = alertArray.get(i).getAsJsonObject();
                String type = alert.has("typeName") ? alert.get("typeName").getAsString() : "";
                String severity = alert.has("severity") ? alert.get("severity").getAsString() : "未知";
                String severityColor = alert.has("severityColor") ? alert.get("severityColor").getAsString() : "";
                String title = alert.has("title") ? alert.get("title").getAsString() : "";
                String desc = alert.has("text") ? alert.get("text").getAsString() : "";
                String startTime = alert.has("startTime") ? alert.get("startTime").getAsString() : "";
                String endTime = alert.has("endTime") ? alert.get("endTime").getAsString() : "";

                weatherInfo.append("[" + severityColor + "] " + type + "\n");
                weatherInfo.append("预警等级: " + severity + "\n");
                weatherInfo.append("标题: " + title + "\n");
                weatherInfo.append("描述: " + desc + "\n");
                weatherInfo.append("生效时间: " + startTime + " ~ " + endTime + "\n");
            }

            return "天气预警:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng alerts response", e);
            return "解析天气预警失败: " + e.getMessage();
        }
    }

    // 获取和风天气生活指数
    public CompletableFuture<String> getHefengIndices(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();

                String locationId = getHefengLocationId(city, apiKey);
                if (locationId == null || locationId.isEmpty()) {
                    return "无法获取城市 " + city + " 的位置ID";
                }

                String urlString = HEFENG_INDICES_URL + "?location=" + locationId;
                String response = httpGet(urlString, apiKey);
                return parseHefengIndicesResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng indices", e);
                return "获取生活指数失败: " + e.getMessage();
            }
        });
    }

    // 解析和风天气生活指数响应
    private String parseHefengIndicesResponse(String response) {
        try {
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            if (!"200".equals(jsonObject.get("code").getAsString())) {
                return "生活指数查询失败: " + jsonObject.get("code").getAsString();
            }

            JsonArray indicesArray = jsonObject.getAsJsonArray("daily");

            StringBuilder weatherInfo = new StringBuilder();

            for (int i = 0; i < indicesArray.size(); i++) {
                JsonObject indices = indicesArray.get(i).getAsJsonObject();
                String type = indices.has("typeName") ? indices.get("typeName").getAsString() : "";
                String level = indices.has("level") ? indices.get("level").getAsString() : "";
                String desc = indices.has("desc") ? indices.get("desc").getAsString() : "";

                weatherInfo.append(type + ": " + level + "\n");
                weatherInfo.append("  " + desc + "\n\n");
            }

            return "生活指数:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng indices response", e);
            return "解析生活指数失败: " + e.getMessage();
        }
    }

    public CompletableFuture<String> getHefengForecastByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();
                String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
                String urlString = HEFENG_FORECAST_URL + "?location=" + location;
                String response = httpGet(urlString, apiKey);
                return parseHefengForecastResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng forecast by location", e);
                return "获取天气预报失败: " + e.getMessage();
            }
        });
    }

    public CompletableFuture<String> getHefengHourlyByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();
                String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
                String urlString = HEFENG_HOURLY_URL + "?location=" + location;
                String response = httpGet(urlString, apiKey);
                return parseHefengHourlyResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng hourly by location", e);
                return "获取小时预报失败: " + e.getMessage();
            }
        });
    }

    public CompletableFuture<String> getHefengAirQualityByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();
                String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
                String urlString = HEFENG_AIR_URL + "?location=" + location;
                String response = httpGet(urlString, apiKey);
                return parseHefengAirQualityResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng air quality by location", e);
                return "获取空气质量失败: " + e.getMessage();
            }
        });
    }

    public CompletableFuture<String> getHefengAlertsByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();
                String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
                String urlString = HEFENG_ALERT_URL + "?location=" + location;
                String response = httpGet(urlString, apiKey);
                return parseHefengAlertsResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng alerts by location", e);
                return "获取天气预警失败: " + e.getMessage();
            }
        });
    }

    public CompletableFuture<String> getHefengIndicesByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();
                String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
                String urlString = HEFENG_INDICES_URL + "?location=" + location;
                String response = httpGet(urlString, apiKey);
                return parseHefengIndicesResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng indices by location", e);
                return "获取生活指数失败: " + e.getMessage();
            }
        });
    }

    // 获取详细天气信息（使用One Call 3.0，需要额外订阅）
    public CompletableFuture<String> getOneCallWeather(double lat, double lon, String exclude, String units, String lang) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getOpenWeatherMapApiKey();
                
                StringBuilder urlBuilder = new StringBuilder(ONE_CALL_URL);
                urlBuilder.append("?lat=").append(lat);
                urlBuilder.append("&lon=").append(lon);
                if (exclude != null && !exclude.isEmpty()) {
                    urlBuilder.append("&exclude=").append(exclude);
                }
                if (units != null && !units.isEmpty()) {
                    urlBuilder.append("&units=").append(units);
                } else {
                    urlBuilder.append("&units=metric");
                }
                if (lang != null && !lang.isEmpty()) {
                    urlBuilder.append("&lang=").append(lang);
                } else {
                    urlBuilder.append("&lang=zh_cn");
                }
                urlBuilder.append("&appid=").append(apiKey);
                
                URL url = new URL(urlBuilder.toString());
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                // 读取响应
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // 解析天气数据
                return parseOneCallWeatherResponse(response.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error getting one call weather", e);
                return "获取详细天气信息失败: " + e.getMessage();
            }
        });
    }

    // 获取指定时间的天气数据（历史或未来）
    public CompletableFuture<String> getTimestampWeather(double lat, double lon, long timestamp, String units, String lang) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getOpenWeatherMapApiKey();
                
                StringBuilder urlBuilder = new StringBuilder(ONE_CALL_TIMESTAMP_URL);
                urlBuilder.append("?lat=").append(lat);
                urlBuilder.append("&lon=").append(lon);
                urlBuilder.append("&dt=").append(timestamp);
                if (units != null && !units.isEmpty()) {
                    urlBuilder.append("&units=").append(units);
                } else {
                    urlBuilder.append("&units=metric");
                }
                if (lang != null && !lang.isEmpty()) {
                    urlBuilder.append("&lang=").append(lang);
                } else {
                    urlBuilder.append("&lang=zh_cn");
                }
                urlBuilder.append("&appid=").append(apiKey);
                
                URL url = new URL(urlBuilder.toString());
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                // 读取响应
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // 解析天气数据
                return parseTimestampWeatherResponse(response.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error getting timestamp weather", e);
                return "获取指定时间天气信息失败: " + e.getMessage();
            }
        });
    }

    // 获取每日聚合天气数据
    public CompletableFuture<String> getDailyAggregationWeather(double lat, double lon, long startDate, long endDate, String units, String lang) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getOpenWeatherMapApiKey();
                
                StringBuilder urlBuilder = new StringBuilder(ONE_CALL_DAILY_AGGREGATION_URL);
                urlBuilder.append("?lat=").append(lat);
                urlBuilder.append("&lon=").append(lon);
                urlBuilder.append("&start_date=").append(startDate);
                urlBuilder.append("&end_date=").append(endDate);
                if (units != null && !units.isEmpty()) {
                    urlBuilder.append("&units=").append(units);
                } else {
                    urlBuilder.append("&units=metric");
                }
                if (lang != null && !lang.isEmpty()) {
                    urlBuilder.append("&lang=").append(lang);
                } else {
                    urlBuilder.append("&lang=zh_cn");
                }
                urlBuilder.append("&appid=").append(apiKey);
                
                URL url = new URL(urlBuilder.toString());
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                // 读取响应
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // 解析天气数据
                return parseDailyAggregationResponse(response.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error getting daily aggregation weather", e);
                return "获取每日聚合天气信息失败: " + e.getMessage();
            }
        });
    }

    // 获取天气概览
    public CompletableFuture<String> getWeatherOverview(double lat, double lon, String units, String lang) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getOpenWeatherMapApiKey();
                
                StringBuilder urlBuilder = new StringBuilder(ONE_CALL_OVERVIEW_URL);
                urlBuilder.append("?lat=").append(lat);
                urlBuilder.append("&lon=").append(lon);
                if (units != null && !units.isEmpty()) {
                    urlBuilder.append("&units=").append(units);
                } else {
                    urlBuilder.append("&units=metric");
                }
                if (lang != null && !lang.isEmpty()) {
                    urlBuilder.append("&lang=").append(lang);
                } else {
                    urlBuilder.append("&lang=zh_cn");
                }
                urlBuilder.append("&appid=").append(apiKey);
                
                URL url = new URL(urlBuilder.toString());
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                // 读取响应
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // 解析天气数据
                return parseWeatherOverviewResponse(response.toString());
            } catch (Exception e) {
                Log.e(TAG, "Error getting weather overview", e);
                return "获取天气概览失败: " + e.getMessage();
            }
        });
    }

    // 解析当前天气响应
    private String parseCurrentWeatherResponse(String response) {
        try {
            // 使用Gson解析JSON响应
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            // 获取城市名称
            String city = jsonObject.get("name").getAsString();
            
            // 获取天气信息
            JsonArray weatherArray = jsonObject.getAsJsonArray("weather");
            String weather = "N/A";
            String description = "N/A";
            if (weatherArray != null && weatherArray.size() > 0) {
                JsonObject weatherObject = weatherArray.get(0).getAsJsonObject();
                weather = weatherObject.get("main").getAsString();
                description = weatherObject.get("description").getAsString();
            }
            
            // 获取温度信息
            JsonObject mainObject = jsonObject.getAsJsonObject("main");
            String temp = mainObject.get("temp").getAsString();
            String humidity = mainObject.get("humidity").getAsString();
            
            // 获取风速信息
            JsonObject windObject = jsonObject.getAsJsonObject("wind");
            String windSpeed = windObject.get("speed").getAsString();

            StringBuilder weatherInfo = new StringBuilder();
            weatherInfo.append("城市: " + city + "\n");
            weatherInfo.append("天气: " + weather + " - " + description + "\n");
            weatherInfo.append("温度: " + temp + "°C\n");
            weatherInfo.append("湿度: " + humidity + "%\n");
            weatherInfo.append("风速: " + windSpeed + " m/s\n");

            return "天气信息:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing current weather response", e);
            return "解析天气信息失败: " + e.getMessage();
        }
    }

    // 解析One Call 3.0天气响应
    private String parseOneCallWeatherResponse(String response) {
        try {
            // 使用Gson解析JSON响应
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            // 获取地理位置
            double lat = jsonObject.get("lat").getAsDouble();
            double lon = jsonObject.get("lon").getAsDouble();
            String timezone = jsonObject.get("timezone").getAsString();
            
            // 获取当前天气
            JsonObject current = jsonObject.getAsJsonObject("current");
            long dt = current.get("dt").getAsLong();
            double temp = current.get("temp").getAsDouble();
            double feelsLike = current.get("feels_like").getAsDouble();
            int humidity = current.get("humidity").getAsInt();
            double windSpeed = current.get("wind_speed").getAsDouble();
            
            // 获取天气描述
            JsonArray weatherArray = current.getAsJsonArray("weather");
            String weather = "N/A";
            String description = "N/A";
            if (weatherArray != null && weatherArray.size() > 0) {
                JsonObject weatherObject = weatherArray.get(0).getAsJsonObject();
                weather = weatherObject.get("main").getAsString();
                description = weatherObject.get("description").getAsString();
            }
            
            StringBuilder weatherInfo = new StringBuilder();
            weatherInfo.append("地理位置: " + lat + ", " + lon + "\n");
            weatherInfo.append("时区: " + timezone + "\n");
            weatherInfo.append("当前时间: " + new java.util.Date(dt * 1000) + "\n");
            weatherInfo.append("天气: " + weather + " - " + description + "\n");
            weatherInfo.append("温度: " + temp + "°C\n");
            weatherInfo.append("体感温度: " + feelsLike + "°C\n");
            weatherInfo.append("湿度: " + humidity + "%\n");
            weatherInfo.append("风速: " + windSpeed + " m/s\n");
            
            // 检查是否有分钟预报
            if (jsonObject.has("minutely")) {
                JsonArray minutelyArray = jsonObject.getAsJsonArray("minutely");
                weatherInfo.append("\n1小时分钟预报:\n");
                for (int i = 0; i < Math.min(10, minutelyArray.size()); i++) {
                    JsonObject minutely = minutelyArray.get(i).getAsJsonObject();
                    long minutelyDt = minutely.get("dt").getAsLong();
                    double precipitation = minutely.get("precipitation").getAsDouble();
                    weatherInfo.append(new java.util.Date(minutelyDt * 1000) + ": 降水量 " + precipitation + " mm/h\n");
                }
            }
            
            // 检查是否有小时预报
            if (jsonObject.has("hourly")) {
                JsonArray hourlyArray = jsonObject.getAsJsonArray("hourly");
                weatherInfo.append("\n24小时预报:\n");
                for (int i = 0; i < Math.min(8, hourlyArray.size()); i++) {
                    JsonObject hourly = hourlyArray.get(i).getAsJsonObject();
                    long hourlyDt = hourly.get("dt").getAsLong();
                    double hourlyTemp = hourly.get("temp").getAsDouble();
                    int hourlyPop = (int) (hourly.get("pop").getAsDouble() * 100);
                    weatherInfo.append(new java.util.Date(hourlyDt * 1000) + ": " + hourlyTemp + "°C, 降水概率: " + hourlyPop + "%\n");
                }
            }
            
            // 检查是否有每日预报
            if (jsonObject.has("daily")) {
                JsonArray dailyArray = jsonObject.getAsJsonArray("daily");
                weatherInfo.append("\n7天预报:\n");
                for (int i = 0; i < Math.min(3, dailyArray.size()); i++) {
                    JsonObject daily = dailyArray.get(i).getAsJsonObject();
                    long dailyDt = daily.get("dt").getAsLong();
                    JsonObject dailyTemp = daily.getAsJsonObject("temp");
                    double maxTemp = dailyTemp.get("max").getAsDouble();
                    double minTemp = dailyTemp.get("min").getAsDouble();
                    int dailyPop = (int) (daily.get("pop").getAsDouble() * 100);
                    weatherInfo.append(new java.util.Date(dailyDt * 1000) + ": 最高 " + maxTemp + "°C, 最低 " + minTemp + "°C, 降水概率: " + dailyPop + "%\n");
                }
            }
            
            // 检查是否有警报
            if (jsonObject.has("alerts")) {
                JsonArray alertsArray = jsonObject.getAsJsonArray("alerts");
                weatherInfo.append("\n天气警报:\n");
                for (int i = 0; i < alertsArray.size(); i++) {
                    JsonObject alert = alertsArray.get(i).getAsJsonObject();
                    String senderName = alert.get("sender_name").getAsString();
                    String event = alert.get("event").getAsString();
                    long start = alert.get("start").getAsLong();
                    long end = alert.get("end").getAsLong();
                    String alertDescription = alert.get("description").getAsString();
                    weatherInfo.append("事件: " + event + "\n");
                    weatherInfo.append("来源: " + senderName + "\n");
                    weatherInfo.append("开始: " + new java.util.Date(start * 1000) + "\n");
                    weatherInfo.append("结束: " + new java.util.Date(end * 1000) + "\n");
                    weatherInfo.append("描述: " + alertDescription + "\n\n");
                }
            }

            return "详细天气信息:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing one call weather response", e);
            return "解析详细天气信息失败: " + e.getMessage();
        }
    }

    // 解析时间戳天气响应
    private String parseTimestampWeatherResponse(String response) {
        try {
            // 使用Gson解析JSON响应
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            // 获取地理位置
            double lat = jsonObject.get("lat").getAsDouble();
            double lon = jsonObject.get("lon").getAsDouble();
            
            // 获取时间戳天气数据
            JsonArray dataArray = jsonObject.getAsJsonArray("data");
            if (dataArray != null && dataArray.size() > 0) {
                JsonObject data = dataArray.get(0).getAsJsonObject();
                long dt = data.get("dt").getAsLong();
                double temp = data.get("temp").getAsDouble();
                double feelsLike = data.get("feels_like").getAsDouble();
                int humidity = data.get("humidity").getAsInt();
                double windSpeed = data.get("wind_speed").getAsDouble();
                
                // 获取天气描述
                JsonArray weatherArray = data.getAsJsonArray("weather");
                String weather = "N/A";
                String description = "N/A";
                if (weatherArray != null && weatherArray.size() > 0) {
                    JsonObject weatherObject = weatherArray.get(0).getAsJsonObject();
                    weather = weatherObject.get("main").getAsString();
                    description = weatherObject.get("description").getAsString();
                }
                
                StringBuilder weatherInfo = new StringBuilder();
                weatherInfo.append("地理位置: " + lat + ", " + lon + "\n");
                weatherInfo.append("时间: " + new java.util.Date(dt * 1000) + "\n");
                weatherInfo.append("天气: " + weather + " - " + description + "\n");
                weatherInfo.append("温度: " + temp + "°C\n");
                weatherInfo.append("体感温度: " + feelsLike + "°C\n");
                weatherInfo.append("湿度: " + humidity + "%\n");
                weatherInfo.append("风速: " + windSpeed + " m/s\n");
                
                return "指定时间天气信息:\n" + weatherInfo.toString();
            } else {
                return "未找到指定时间的天气数据";
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing timestamp weather response", e);
            return "解析指定时间天气信息失败: " + e.getMessage();
        }
    }

    // 解析每日聚合天气响应
    private String parseDailyAggregationResponse(String response) {
        try {
            // 使用Gson解析JSON响应
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            // 获取地理位置
            double lat = jsonObject.get("lat").getAsDouble();
            double lon = jsonObject.get("lon").getAsDouble();
            
            // 获取每日聚合数据
            JsonArray dailyArray = jsonObject.getAsJsonArray("daily");
            StringBuilder weatherInfo = new StringBuilder();
            weatherInfo.append("地理位置: " + lat + ", " + lon + "\n\n");
            weatherInfo.append("每日聚合天气数据:\n");
            
            for (int i = 0; i < dailyArray.size(); i++) {
                JsonObject daily = dailyArray.get(i).getAsJsonObject();
                long dt = daily.get("dt").getAsLong();
                JsonObject temp = daily.getAsJsonObject("temp");
                double maxTemp = temp.get("max").getAsDouble();
                double minTemp = temp.get("min").getAsDouble();
                double avgTemp = temp.get("avg").getAsDouble();
                int humidity = daily.get("humidity").getAsInt();
                double windSpeed = daily.get("wind_speed").getAsDouble();
                
                // 获取天气描述
                JsonArray weatherArray = daily.getAsJsonArray("weather");
                String weather = "N/A";
                String description = "N/A";
                if (weatherArray != null && weatherArray.size() > 0) {
                    JsonObject weatherObject = weatherArray.get(0).getAsJsonObject();
                    weather = weatherObject.get("main").getAsString();
                    description = weatherObject.get("description").getAsString();
                }
                
                weatherInfo.append("日期: " + new java.util.Date(dt * 1000) + "\n");
                weatherInfo.append("天气: " + weather + " - " + description + "\n");
                weatherInfo.append("最高温度: " + maxTemp + "°C\n");
                weatherInfo.append("最低温度: " + minTemp + "°C\n");
                weatherInfo.append("平均温度: " + avgTemp + "°C\n");
                weatherInfo.append("湿度: " + humidity + "%\n");
                weatherInfo.append("风速: " + windSpeed + " m/s\n\n");
            }
            
            return "每日聚合天气信息:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing daily aggregation response", e);
            return "解析每日聚合天气信息失败: " + e.getMessage();
        }
    }

    // 解析天气概览响应
    private String parseWeatherOverviewResponse(String response) {
        try {
            // 使用Gson解析JSON响应
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            // 获取地理位置
            double lat = jsonObject.get("lat").getAsDouble();
            double lon = jsonObject.get("lon").getAsDouble();
            String timezone = jsonObject.get("timezone").getAsString();
            
            // 获取今天的概览
            JsonObject today = jsonObject.getAsJsonObject("today");
            String todaySummary = today.get("summary").getAsString();
            
            // 获取明天的概览
            JsonObject tomorrow = jsonObject.getAsJsonObject("tomorrow");
            String tomorrowSummary = tomorrow.get("summary").getAsString();
            
            StringBuilder weatherInfo = new StringBuilder();
            weatherInfo.append("地理位置: " + lat + ", " + lon + "\n");
            weatherInfo.append("时区: " + timezone + "\n\n");
            weatherInfo.append("今天天气概览:\n" + todaySummary + "\n\n");
            weatherInfo.append("明天天气概览:\n" + tomorrowSummary + "\n");
            
            return "天气概览:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing weather overview response", e);
            return "解析天气概览失败: " + e.getMessage();
        }
    }

    // 简化的天气查询方法（保持向后兼容）
    public CompletableFuture<String> getWeather(String city) {
        return getCurrentWeather(city);
    }

    // 工具使用说明
    public String getWeatherToolUsage() {
        return "天气工具使用说明:\n\n" +
               "1. 基本天气查询: 输入城市名称，例如 '北京天气'\n" +
               "2. 详细天气查询: 输入经纬度，例如 '详细天气 39.9 116.4'\n" +
               "3. 历史天气查询: 输入经纬度和时间戳，例如 '历史天气 39.9 116.4 1620000000'\n" +
               "4. 每日聚合天气: 输入经纬度和日期范围，例如 '每日天气 39.9 116.4 1620000000 1620864000'\n" +
               "5. 天气概览: 输入经纬度，例如 '天气概览 39.9 116.4'\n\n" +
               "注意: One Call 3.0 API 需要单独订阅，部分功能可能需要付费使用。\n" +
               "如果使用默认API密钥，可能会遇到限制或错误。";
    }
    
    @Override
    public String getName() {
        return "ai_weather";
    }
    
    @Override
    public String getDescription() {
        return "AI天气管理工具，支持实时天气查询、天气预报、24小时预报、空气质量、天气预警、生活指数等功能，支持和风天气和OpenWeatherMap两个API提供商";
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = (String) parameters.get("action");
            if (action == null) {
                action = "current";
            }
            
            String city = (String) parameters.get("city");
            Object latObj = parameters.get("lat");
            Object lonObj = parameters.get("lon");
            Double lat = null;
            Double lon = null;
            
            if (latObj != null) {
                if (latObj instanceof Double) {
                    lat = (Double) latObj;
                } else if (latObj instanceof Number) {
                    lat = ((Number) latObj).doubleValue();
                }
            }
            if (lonObj != null) {
                if (lonObj instanceof Double) {
                    lon = (Double) lonObj;
                } else if (lonObj instanceof Number) {
                    lon = ((Number) lonObj).doubleValue();
                }
            }
            
            boolean useLocation = lat != null && lon != null;
            
            switch (action) {
                case "current":
                    return getCurrentWeatherAITool(city, lat, lon, useLocation);
                case "forecast":
                    return getForecastAITool(city, lat, lon, useLocation);
                case "hourly":
                    return getHourlyAITool(city, lat, lon, useLocation);
                case "air_quality":
                    return getAirQualityAITool(city, lat, lon, useLocation);
                case "alerts":
                    return getAlertsAITool(city, lat, lon, useLocation);
                case "indices":
                    return getIndicesAITool(city, lat, lon, useLocation);
                case "all":
                    return getAllWeatherAITool(city, lat, lon, useLocation);
                case "one_call":
                    return getOneCallAITool(lat, lon, parameters);
                default:
                    return getCurrentWeatherAITool(city, lat, lon, useLocation);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error executing AI weather tool: " + e.getMessage(), e);
            return new AIToolResult("天气查询失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getCurrentWeatherAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            String result;
            if (useLocation) {
                result = getCurrentWeatherByLocation(lat, lon).get();
            } else if (city != null && !city.isEmpty()) {
                result = getCurrentWeather(city).get();
            } else {
                return new AIToolResult("请提供城市名称或经纬度", new HashMap<>());
            }
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("data", result);
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting current weather: " + e.getMessage(), e);
            return new AIToolResult("获取当前天气失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult getForecastAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            String result;
            if (useLocation) {
                result = getHefengForecastByLocation(lat, lon).get();
            } else if (city != null && !city.isEmpty()) {
                result = getHefengForecast(city).get();
            } else {
                return new AIToolResult("请提供城市名称或经纬度", new HashMap<>());
            }
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("data", result);
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting forecast: " + e.getMessage(), e);
            return new AIToolResult("获取天气预报失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult getHourlyAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            String result;
            if (useLocation) {
                result = getHefengHourlyByLocation(lat, lon).get();
            } else if (city != null && !city.isEmpty()) {
                result = getHefengHourly(city).get();
            } else {
                return new AIToolResult("请提供城市名称或经纬度", new HashMap<>());
            }
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("data", result);
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting hourly: " + e.getMessage(), e);
            return new AIToolResult("获取小时预报失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult getAirQualityAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            String result;
            if (useLocation) {
                result = getHefengAirQualityByLocation(lat, lon).get();
            } else if (city != null && !city.isEmpty()) {
                result = getHefengAirQuality(city).get();
            } else {
                return new AIToolResult("请提供城市名称或经纬度", new HashMap<>());
            }
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("data", result);
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting air quality: " + e.getMessage(), e);
            return new AIToolResult("获取空气质量失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult getAlertsAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            String result;
            if (useLocation) {
                result = getHefengAlertsByLocation(lat, lon).get();
            } else if (city != null && !city.isEmpty()) {
                result = getHefengAlerts(city).get();
            } else {
                return new AIToolResult("请提供城市名称或经纬度", new HashMap<>());
            }
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("data", result);
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting alerts: " + e.getMessage(), e);
            return new AIToolResult("获取天气预警失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult getIndicesAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            String result;
            if (useLocation) {
                result = getHefengIndicesByLocation(lat, lon).get();
            } else if (city != null && !city.isEmpty()) {
                result = getHefengIndices(city).get();
            } else {
                return new AIToolResult("请提供城市名称或经纬度", new HashMap<>());
            }
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("data", result);
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting indices: " + e.getMessage(), e);
            return new AIToolResult("获取生活指数失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult getAllWeatherAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            StringBuilder allData = new StringBuilder();
            
            CompletableFuture<String> currentFuture = useLocation ? 
                getCurrentWeatherByLocation(lat, lon) : getCurrentWeather(city);
            CompletableFuture<String> forecastFuture = useLocation ?
                getHefengForecastByLocation(lat, lon) : getHefengForecast(city != null ? city : "北京");
            CompletableFuture<String> hourlyFuture = useLocation ?
                getHefengHourlyByLocation(lat, lon) : getHefengHourly(city != null ? city : "北京");
            CompletableFuture<String> airFuture = useLocation ?
                getHefengAirQualityByLocation(lat, lon) : getHefengAirQuality(city != null ? city : "北京");
            CompletableFuture<String> alertsFuture = useLocation ?
                getHefengAlertsByLocation(lat, lon) : getHefengAlerts(city != null ? city : "北京");
            CompletableFuture<String> indicesFuture = useLocation ?
                getHefengIndicesByLocation(lat, lon) : getHefengIndices(city != null ? city : "北京");
            
            CompletableFuture.allOf(currentFuture, forecastFuture, hourlyFuture, airFuture, alertsFuture, indicesFuture).get();
            
            allData.append(currentFuture.get()).append("\n\n");
            allData.append(forecastFuture.get()).append("\n\n");
            allData.append(hourlyFuture.get()).append("\n\n");
            allData.append(airFuture.get()).append("\n\n");
            allData.append(alertsFuture.get()).append("\n\n");
            allData.append(indicesFuture.get());
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("data", allData.toString());
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting all weather: " + e.getMessage(), e);
            return new AIToolResult("获取完整天气信息失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult getOneCallAITool(Double lat, Double lon, Map<String, Object> parameters) {
        if (lat == null || lon == null) {
            return new AIToolResult("one_call 需要提供经纬度", new HashMap<>());
        }
        
        try {
            String exclude = (String) parameters.get("exclude");
            String units = (String) parameters.get("units");
            String lang = (String) parameters.get("lang");
            
            String result = getOneCallWeather(lat, lon, exclude, units, lang).get();
            
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("data", result);
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting one call weather: " + e.getMessage(), e);
            return new AIToolResult("获取详细天气信息失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: current(当前天气), forecast(天气预报), hourly(24小时预报), air_quality(空气质量), alerts(天气预警), indices(生活指数), all(全部信息), one_call(详细天气)");
        descriptions.put("city", "城市名称（用于按城市查询）");
        descriptions.put("lat", "纬度（用于按坐标查询）");
        descriptions.put("lon", "经度（用于按坐标查询）");
        descriptions.put("provider", "API提供商: hefeng(和风天气,默认), openweathermap");
        return descriptions;
    }
}
