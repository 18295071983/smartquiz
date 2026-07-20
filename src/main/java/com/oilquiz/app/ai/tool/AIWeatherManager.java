package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.weather.QWeatherJwtGenerator;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import com.oilquiz.app.ai.util.NetworkUtil;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;

@Tool(
    value = "ai_weather",
    description = "Weather query tool, supports current weather, forecast, hourly weather, air quality, weather alerts, life indices",
    category = "weather",
    aliases = {"weather", "get_weather"},
    actions = {
        @Action(name = "current", description = "Get current weather"),
        @Action(name = "forecast", description = "Get weather forecast"),
        @Action(name = "hourly", description = "Get hourly weather"),
        @Action(name = "air_quality", description = "Get air quality"),
        @Action(name = "alerts", description = "Get weather alerts"),
        @Action(name = "indices", description = "Get life indices"),
        @Action(name = "all", description = "Get all weather info")
    },
    params = {
        @Param(name = "city", type = "string", description = "City name", required = false),
        @Param(name = "lat", type = "float", description = "Latitude", required = false),
        @Param(name = "lon", type = "float", description = "Longitude", required = false),
        @Param(name = "action", type = "string", description = "Action type: current/forecast/hourly/air_quality/alerts/indices/all", required = false)
    }
)
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
    private static final String HEFENG_HOURLY_URL = HEFENG_API_HOST + "/v7/weather/24h";
    private static final String HEFENG_AIR_URL = HEFENG_API_HOST + "/v7/air/now";
    private static final String HEFENG_ALERT_URL = HEFENG_API_HOST + "/v7/warning/now";
    private static final String HEFENG_MINUTELY_URL = HEFENG_API_HOST + "/v7/minutely/5m";
    private static final String HEFENG_INDICES_URL = HEFENG_API_HOST + "/v7/indices/1d";
    private static final String HEFENG_INDICES_TYPE_ALL = "0";
    private static final String HEFENG_SUN_URL = HEFENG_API_HOST + "/v7/astronomy/sun";
    private static final String HEFENG_GEOCODE_URL = HEFENG_GEO_HOST + "/geo/v2/city/lookup";
    private static final String DEFAULT_HEFENG_API_KEY = "be2af1f8490344feb8a7125ab46608dd";
    private static final String DEFAULT_QWEATHER_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEIA9Gw1Of0+TGrE3/tdXfmthWrhNE92KwaCeknzauUu+T\n-----END PRIVATE KEY-----";
    private static final String DEFAULT_QWEATHER_PROJECT_ID = "2A89PF2EBQ";
    private static final String DEFAULT_QWEATHER_KID = "TGGVDKVJGN";

    private final Context context;
    private final Gson gson;
    private WeatherProvider currentProvider = WeatherProvider.HEFENG;
    private QWeatherJwtGenerator jwtGenerator;

    public AIWeatherManager(Context context) {
        this.context = context;
        this.gson = new Gson();
        tryLoadJwtCredentials();
    }

    public AIWeatherManager(Context context, WeatherProvider provider) {
        this.context = context;
        this.gson = new Gson();
        this.currentProvider = provider;
        tryLoadJwtCredentials();
    }

    public void setWeatherProvider(WeatherProvider provider) {
        this.currentProvider = provider;
    }

    /**
     * Initialize JWT authentication with credentials.
     * @param privateKeyPem Ed25519 private key in PEM format
     * @param projectId QWeather project ID
     * @param kid QWeather key ID (credential ID)
     * @param apiHost Optional API host override (null to use default)
     */
    public void initializeJwt(String privateKeyPem, String projectId, String kid, String apiHost) {
        try {
            jwtGenerator = new QWeatherJwtGenerator(privateKeyPem, projectId, kid);
            // Save credentials for future sessions
            APIKeyManager.getInstance(context).saveQWeatherJwtCredentials(privateKeyPem, projectId, kid, apiHost);
            if (apiHost != null && !apiHost.isEmpty()) {
                updateHefengApiHost(apiHost);
            }
            Log.i(TAG, "QWeather JWT authentication initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize QWeather JWT", e);
            jwtGenerator = null;
        }
    }

    /**
     * Try to load saved JWT credentials from storage.
     */
    private void tryLoadJwtCredentials() {
        APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
        try {
            String privateKey = apiKeyManager.getQWeatherPrivateKey();
            String projectId = apiKeyManager.getQWeatherProjectId();
            String kid = apiKeyManager.getQWeatherKid();
            String apiHost = apiKeyManager.getQWeatherApiHost();

            // Fall back to defaults if not configured in storage
            if (privateKey == null || privateKey.isEmpty()) {
                privateKey = DEFAULT_QWEATHER_PRIVATE_KEY;
            }
            if (projectId == null || projectId.isEmpty()) {
                projectId = DEFAULT_QWEATHER_PROJECT_ID;
            }
            if (kid == null || kid.isEmpty()) {
                kid = DEFAULT_QWEATHER_KID;
            }
            if (apiHost == null || apiHost.isEmpty()) {
                apiHost = HEFENG_API_HOST;
            }

            jwtGenerator = new QWeatherJwtGenerator(privateKey, projectId, kid);
            updateHefengApiHost(apiHost);
            Log.d(TAG, "QWeather JWT initialized (projectId=" + projectId + ", kid=" + kid + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize QWeather JWT", e);
            jwtGenerator = null;
        }
    }

    /**
     * Check if JWT authentication is available.
     */
    public boolean isJwtInitialized() {
        return jwtGenerator != null;
    }

    /**
     * Get JWT token for QWeather API.
     * Returns null if JWT is not initialized.
     */
    private String getHefengJwtToken() {
        if (jwtGenerator == null) {
            return null;
        }
        return jwtGenerator.getToken();
    }

    /**
     * Update the dynamic API host for QWeather.
     */
    private void updateHefengApiHost(String newHost) {
        // Update the static fields by reflection is complex, instead we store it
        // and use it in URL construction
        APIKeyManager.getInstance(context).saveAPIHost(APIKeyManager.Service.HEFENG_WEATHER, newHost);
    }

    private String getHefengApiKey() {
        APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
        String apiKey = apiKeyManager.getAPIKey(APIKeyManager.Service.HEFENG_WEATHER);
        if (apiKey == null || apiKey.isEmpty()) {
            return DEFAULT_HEFENG_API_KEY;
        }
        return apiKey;
    }

    private String getOpenWeatherMapApiKey() {
        APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);
        String apiKey = apiKeyManager.getAPIKey(APIKeyManager.Service.OPENWEATHERMAP);
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("OpenWeatherMap API Key未配置，请在设置中配置");
        }
        return apiKey;
    }

    public WeatherProvider getWeatherProvider() {
        return currentProvider;
    }

    private String httpGet(String urlString, String apiKey) throws Exception {
        int maxRetries = 5;
        int retryCount = 0;
        long baseDelay = 1000;
        java.util.Random random = new java.util.Random();

        while (retryCount <= maxRetries) {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(urlString)
                    .addHeader("User-Agent", "SmartQuiz/1.0")
                    .addHeader("Accept", "application/json")
                    .addHeader("Accept-Language", "zh-CN")
                    .addHeader("Accept-Encoding", "gzip, deflate");

            // JWT authentication takes priority over API KEY
            String jwtToken = getHefengJwtToken();
            if (jwtToken != null) {
                requestBuilder.addHeader("Authorization", "Bearer " + jwtToken);
            } else {
                requestBuilder.addHeader("X-QW-Api-Key", apiKey);
            }

            Request request = requestBuilder.build();

            try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                if (response.isSuccessful()) {
                    return response.body() != null ? response.body().string() : "";
                }

                int statusCode = response.code();
                // 401 Unauthorized - try refreshing JWT token
                if (statusCode == 401 && jwtGenerator != null && retryCount < maxRetries) {
                    Log.w(TAG, "JWT token expired, refreshing...");
                    jwtGenerator.refreshToken();
                    retryCount++;
                    continue;
                }
                if (statusCode == 429 && retryCount < maxRetries) {
                    long delay = baseDelay * (long) Math.pow(2, retryCount) + random.nextInt((int) (baseDelay * Math.pow(2, retryCount)));
                    Thread.sleep(delay);
                    retryCount++;
                } else if (statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504) {
                    if (retryCount < maxRetries) {
                        long delay = baseDelay * (long) Math.pow(2, retryCount) + random.nextInt((int) (baseDelay * Math.pow(2, retryCount)));
                        Thread.sleep(delay);
                        retryCount++;
                    } else {
                        throw new Exception("HTTP " + statusCode + " after " + maxRetries + " retries");
                    }
                } else {
                    throw new Exception("HTTP " + statusCode);
                }
            } catch (IOException e) {
                if (retryCount < maxRetries) {
                    long delay = baseDelay * (long) Math.pow(2, retryCount) + random.nextInt((int) (baseDelay * Math.pow(2, retryCount)));
                    Thread.sleep(delay);
                    retryCount++;
                } else {
                    throw e;
                }
            }
        }

        throw new Exception("Max retries exceeded");
    }

    private String httpGetWithBearer(String urlString, String apiKey) throws Exception {
        int maxRetries = 5;
        int retryCount = 0;
        long baseDelay = 1000;
        java.util.Random random = new java.util.Random();

        while (retryCount <= maxRetries) {
            Request request = new Request.Builder()
                    .url(urlString)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("User-Agent", "SmartQuiz/1.0")
                    .addHeader("Accept", "application/json")
                    .addHeader("Accept-Language", "zh-CN")
                    .addHeader("Accept-Encoding", "gzip, deflate")
                    .build();

            try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                if (response.isSuccessful()) {
                    return response.body() != null ? response.body().string() : "";
                }

                int statusCode = response.code();
                if (statusCode == 429 && retryCount < maxRetries) {
                    long delay = baseDelay * (long) Math.pow(2, retryCount) + random.nextInt((int) (baseDelay * Math.pow(2, retryCount)));
                    Thread.sleep(delay);
                    retryCount++;
                } else if (statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504) {
                    if (retryCount < maxRetries) {
                        long delay = baseDelay * (long) Math.pow(2, retryCount) + random.nextInt((int) (baseDelay * Math.pow(2, retryCount)));
                        Thread.sleep(delay);
                        retryCount++;
                    } else {
                        throw new Exception("HTTP " + statusCode + " after " + maxRetries + " retries");
                    }
                } else {
                    throw new Exception("HTTP " + statusCode);
                }
            } catch (IOException e) {
                if (retryCount < maxRetries) {
                    long delay = baseDelay * (long) Math.pow(2, retryCount) + random.nextInt((int) (baseDelay * Math.pow(2, retryCount)));
                    Thread.sleep(delay);
                    retryCount++;
                } else {
                    throw e;
                }
            }
        }

        throw new Exception("Max retries exceeded");
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
        return getCurrentWeatherByLocation(lat, lon, null);
    }

    public CompletableFuture<String> getCurrentWeatherByLocation(double lat, double lon, String cityName) {
        switch (currentProvider) {
            case HEFENG:
                return getHefengCurrentWeatherByLocation(lat, lon, cityName);
            case OPENWEATHERMAP:
            default:
                return getOpenWeatherMapCurrentWeatherByLocation(lat, lon);
        }
    }

    /**
     * 直接用经纬度查天气，不做城市名反解析（省一次GeoAPI调用）
     * 城市名由调用方通过GPS反解析提供
     */
    public CompletableFuture<String> getCurrentWeatherByLocationDirect(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();
                String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
                String urlString = HEFENG_WEATHER_URL + "?location=" + location;
                String response = httpGet(urlString, apiKey);
                return parseHefengWeatherResponse(response, null);
            } catch (Exception e) {
                Log.e(TAG, "Error getting weather by location (direct)", e);
                return "获取天气信息失败: " + e.getMessage();
            }
        });
    }

    // OpenWeatherMap 当前天气查询
    private CompletableFuture<String> getOpenWeatherMapCurrentWeather(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getOpenWeatherMapApiKey();
                
                String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.name());
                String urlString = CURRENT_WEATHER_URL + "?q=" + encodedCity + "&appid=" + apiKey + "&units=metric&lang=zh_cn";
                
                Request request = NetworkUtil.createApiRequestBuilder(urlString).build();
                
                try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new Exception("HTTP " + response.code());
                    }
                    String responseBody = response.body() != null ? response.body().string() : "";
                    return parseCurrentWeatherResponse(responseBody);
                }
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

    private CompletableFuture<String> getHefengCurrentWeatherByLocation(double lat, double lon, String cityName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();

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
                
                Request request = NetworkUtil.createApiRequestBuilder(urlString).build();
                
                try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new Exception("HTTP " + response.code());
                    }
                    String responseBody = response.body() != null ? response.body().string() : "";
                    return parseCurrentWeatherResponse(responseBody);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting current weather by location from OpenWeatherMap", e);
                return "获取天气信息失败: " + e.getMessage();
            }
        });
    }

    // 获取和风天气城市ID
    private static final java.util.Map<String, String> DEFAULT_CITY_IDS = new java.util.HashMap<String, String>() {{
        put("北京", "101010100");
        put("上海", "101020100");
        put("广州", "101280101");
        put("深圳", "101280601");
        put("杭州", "101210101");
        put("南京", "101190101");
        put("成都", "101270101");
        put("武汉", "101200101");
        put("西安", "101110101");
        put("重庆", "101040100");
    }};

    private String getHefengLocationId(String city, String apiKey) throws Exception {
        String defaultId = DEFAULT_CITY_IDS.get(city);
        if (defaultId != null) {
            return defaultId;
        }
        
        try {
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.name());
            String urlString = HEFENG_GEOCODE_URL + "?location=" + encodedCity;
                String locationId = getHefengLocationFromGeoAPI(urlString, true, apiKey);
            if (locationId != null) {
                return locationId;
            }
        } catch (Exception e) {
            Log.w(TAG, "GeoAPI failed for city " + city + ", using default fallback");
        }
        
        return DEFAULT_CITY_IDS.get("北京");
    }

    private double[] getHefengLatLon(String city, String apiKey) throws Exception {
        try {
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.name());
            String urlString = HEFENG_GEOCODE_URL + "?location=" + encodedCity;
                String response = httpGet(urlString, apiKey);

                JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            if (jsonObject.has("code") && "200".equals(jsonObject.get("code").getAsString())) {
                JsonArray locationArray = jsonObject.getAsJsonArray("location");
                if (locationArray != null && locationArray.size() > 0) {
                    JsonObject location = locationArray.get(0).getAsJsonObject();
                    double lat = location.has("lat") ? location.get("lat").getAsDouble() : 39.92;
                    double lon = location.has("lon") ? location.get("lon").getAsDouble() : 116.41;
                    return new double[]{lat, lon};
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get lat/lon from GeoAPI for city " + city, e);
        }
        return new double[]{39.92, 116.41};
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
        if (response == null || response.isEmpty()) {
            return "天气信息:\n查询失败（响应为空）";
        }
        
        try {
            JsonElement jsonElement = gson.fromJson(response, JsonElement.class);
            
            if (jsonElement == null || !jsonElement.isJsonObject()) {
                return "天气信息:\n查询失败（响应格式错误）";
            }
            
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            
            String code = "";
            if (jsonObject.has("code") && !jsonObject.get("code").isJsonNull()) {
                code = jsonObject.get("code").getAsString();
            }
            
            if (!"200".equals(code)) {
                if ("403".equals(code)) {
                    return "天气信息:\n暂无权限（需在和风天气控制台开启权限）";
                }
                String msg = jsonObject.has("message") && !jsonObject.get("message").isJsonNull() 
                    ? jsonObject.get("message").getAsString() : "未知错误";
                return "天气信息:\n查询失败（" + msg + "）";
            }

            JsonObject now = null;
            if (jsonObject.has("now") && !jsonObject.get("now").isJsonNull()) {
                JsonElement nowElement = jsonObject.get("now");
                if (nowElement.isJsonObject()) {
                    now = nowElement.getAsJsonObject();
                }
            }
            
            if (now == null) {
                return "天气信息:\n暂无数据";
            }

            String city = cityName;
            if (city == null || city.isEmpty()) {
                if (jsonObject.has("location") && !jsonObject.get("location").isJsonNull()) {
                    JsonElement locationElement = jsonObject.get("location");
                    if (locationElement.isJsonObject()) {
                        JsonObject location = locationElement.getAsJsonObject();
                        if (location.has("name") && !location.get("name").isJsonNull()) {
                            city = location.get("name").getAsString();
                        }
                    }
                }
            }
            if (city == null || city.isEmpty()) {
                city = "未知";
            }

            String weather = now.has("text") && !now.get("text").isJsonNull() ? now.get("text").getAsString() : "--";
            String icon = now.has("icon") && !now.get("icon").isJsonNull() ? now.get("icon").getAsString() : "";
            String temp = now.has("temp") && !now.get("temp").isJsonNull() ? now.get("temp").getAsString() : "--";
            String humidity = now.has("humidity") && !now.get("humidity").isJsonNull() ? now.get("humidity").getAsString() : "--";
            String windSpeed = now.has("windSpeed") && !now.get("windSpeed").isJsonNull() ? now.get("windSpeed").getAsString() : "--";
            String windDir = now.has("windDir") && !now.get("windDir").isJsonNull() ? now.get("windDir").getAsString() : "--";
            String feelsLike = now.has("feelsLike") && !now.get("feelsLike").isJsonNull() ? now.get("feelsLike").getAsString() : "--";
            String visibility = now.has("vis") && !now.get("vis").isJsonNull() ? now.get("vis").getAsString() : "--";
            String pressure = now.has("pressure") && !now.get("pressure").isJsonNull() ? now.get("pressure").getAsString() : "--";

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
            weatherInfo.append("气压: " + pressure + " hPa\n");

            if (jsonObject.has("fxLink") && !jsonObject.get("fxLink").isJsonNull()) {
                String fxLink = jsonObject.get("fxLink").getAsString();
                weatherInfo.append("链接: " + fxLink + "\n");
            }

            return "天气信息:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng weather response", e);
            return "天气信息:\n查询失败";
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
        if (response == null || response.isEmpty()) {
            return "天气预报:\n查询失败（响应为空）";
        }
        
        try {
            JsonElement jsonElement = gson.fromJson(response, JsonElement.class);
            
            if (jsonElement == null || !jsonElement.isJsonObject()) {
                return "天气预报:\n查询失败（响应格式错误）";
            }
            
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            
            String code = "";
            if (jsonObject.has("code") && !jsonObject.get("code").isJsonNull()) {
                code = jsonObject.get("code").getAsString();
            }
            
            if (!"200".equals(code)) {
                if ("403".equals(code)) {
                    return "天气预报:\n暂无权限（需在和风天气控制台开启权限）";
                }
                String msg = jsonObject.has("message") && !jsonObject.get("message").isJsonNull() 
                    ? jsonObject.get("message").getAsString() : "未知错误";
                return "天气预报:\n查询失败（" + msg + "）";
            }

            JsonArray dailyArray = null;
            if (jsonObject.has("daily") && !jsonObject.get("daily").isJsonNull()) {
                JsonElement dailyElement = jsonObject.get("daily");
                if (dailyElement.isJsonArray()) {
                    dailyArray = dailyElement.getAsJsonArray();
                }
            }

            if (dailyArray == null || dailyArray.size() == 0) {
                return "天气预报:\n暂无数据";
            }

            StringBuilder weatherInfo = new StringBuilder();

            for (int i = 0; i < dailyArray.size(); i++) {
                JsonElement dailyElement = dailyArray.get(i);
                if (!dailyElement.isJsonObject()) continue;
                JsonObject daily = dailyElement.getAsJsonObject();
                
                String date = daily.has("fxDate") && !daily.get("fxDate").isJsonNull() ? daily.get("fxDate").getAsString() : "";
                String weatherDay = daily.has("textDay") && !daily.get("textDay").isJsonNull() ? daily.get("textDay").getAsString() : "";
                String weatherNight = daily.has("textNight") && !daily.get("textNight").isJsonNull() ? daily.get("textNight").getAsString() : "";
                String highTemp = daily.has("tempMax") && !daily.get("tempMax").isJsonNull() ? daily.get("tempMax").getAsString() : "--";
                String lowTemp = daily.has("tempMin") && !daily.get("tempMin").isJsonNull() ? daily.get("tempMin").getAsString() : "--";

                if (!date.isEmpty()) {
                    weatherInfo.append("日期: " + date + "\n");
                }
                if (!highTemp.equals("--")) {
                    weatherInfo.append("最高温度: " + highTemp + "°C\n");
                }
                if (!lowTemp.equals("--")) {
                    weatherInfo.append("最低温度: " + lowTemp + "°C\n");
                }
                if (!weatherDay.isEmpty()) {
                    weatherInfo.append("白天天气: " + weatherDay + "\n");
                }
                if (!weatherNight.isEmpty()) {
                    weatherInfo.append("夜间天气: " + weatherNight + "\n");
                }
                weatherInfo.append("\n");
            }

            return "天气预报:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng forecast response", e);
            return "天气预报:\n查询失败";
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
        if (response == null || response.isEmpty()) {
            return "24小时预报:\n查询失败（响应为空）";
        }
        
        try {
            JsonElement jsonElement = gson.fromJson(response, JsonElement.class);
            
            if (jsonElement == null || !jsonElement.isJsonObject()) {
                return "24小时预报:\n查询失败（响应格式错误）";
            }
            
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            
            String code = "";
            if (jsonObject.has("code") && !jsonObject.get("code").isJsonNull()) {
                code = jsonObject.get("code").getAsString();
            }
            
            if (!"200".equals(code)) {
                if ("403".equals(code)) {
                    return "24小时预报:\n暂无权限（需在和风天气控制台开启权限）";
                }
                String msg = jsonObject.has("message") && !jsonObject.get("message").isJsonNull() 
                    ? jsonObject.get("message").getAsString() : "未知错误";
                return "24小时预报:\n查询失败（" + msg + "）";
            }

            JsonArray hourlyArray = null;
            if (jsonObject.has("hourly") && !jsonObject.get("hourly").isJsonNull()) {
                JsonElement hourlyElement = jsonObject.get("hourly");
                if (hourlyElement.isJsonArray()) {
                    hourlyArray = hourlyElement.getAsJsonArray();
                }
            }

            if (hourlyArray == null || hourlyArray.size() == 0) {
                return "24小时预报:\n暂无数据";
            }

            StringBuilder weatherInfo = new StringBuilder();

            for (int i = 0; i < Math.min(24, hourlyArray.size()); i++) {
                JsonElement hourlyElement = hourlyArray.get(i);
                if (!hourlyElement.isJsonObject()) continue;
                JsonObject hourly = hourlyElement.getAsJsonObject();
                
                String time = hourly.has("fxTime") && !hourly.get("fxTime").isJsonNull() ? hourly.get("fxTime").getAsString() : "";
                String weather = hourly.has("text") && !hourly.get("text").isJsonNull() ? hourly.get("text").getAsString() : "";
                String temp = hourly.has("temp") && !hourly.get("temp").isJsonNull() ? hourly.get("temp").getAsString() : "--";
                String pop = hourly.has("pop") && !hourly.get("pop").isJsonNull() ? hourly.get("pop").getAsString() : "0";
                String icon = hourly.has("icon") && !hourly.get("icon").isJsonNull() ? hourly.get("icon").getAsString() : "";

                if (!time.isEmpty()) {
                    weatherInfo.append("时间: " + time.substring(11, 16) + "\n");
                }
                if (!temp.isEmpty()) {
                    weatherInfo.append("温度: " + temp + "°C\n");
                }
                if (!weather.isEmpty()) {
                    weatherInfo.append("天气: " + weather + "\n");
                }
                weatherInfo.append("降水: " + pop + "%\n");
                if (!icon.isEmpty()) {
                    weatherInfo.append("图标: " + icon + "\n");
                }
                weatherInfo.append("\n");
            }

            return "24小时预报:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng hourly response", e);
            return "24小时预报:\n查询失败";
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

    // 解析和风天气空气质量响应（智能识别数据格式）
    private String parseHefengAirQualityResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "空气质量: 查询失败（响应为空）";
        }
        
        try {
            JsonElement jsonElement = gson.fromJson(response, JsonElement.class);
            
            if (jsonElement == null || !jsonElement.isJsonObject()) {
                return "空气质量: 查询失败（响应格式错误）";
            }
            
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            
            String code = "";
            if (jsonObject.has("code") && !jsonObject.get("code").isJsonNull()) {
                code = jsonObject.get("code").getAsString();
            }
            
            if (!"200".equals(code)) {
                if ("403".equals(code)) {
                    return "空气质量: 暂无权限（需在和风天气控制台开启权限）";
                }
                String msg = jsonObject.has("message") && !jsonObject.get("message").isJsonNull() 
                    ? jsonObject.get("message").getAsString() : "未知错误";
                return "空气质量: 查询失败（" + msg + "）";
            }

            StringBuilder weatherInfo = new StringBuilder();
            weatherInfo.append("空气质量:\n");

            if (jsonObject.has("now") && !jsonObject.get("now").isJsonNull()) {
                JsonElement nowElement = jsonObject.get("now");
                if (nowElement.isJsonObject()) {
                    JsonObject now = nowElement.getAsJsonObject();
                    String aqi = now.has("aqi") && !now.get("aqi").isJsonNull() ? now.get("aqi").getAsString() : "--";
                    String level = now.has("level") && !now.get("level").isJsonNull() ? now.get("level").getAsString() : "--";
                    String category = now.has("category") && !now.get("category").isJsonNull() ? now.get("category").getAsString() : "--";
                    String primary = now.has("primary") && !now.get("primary").isJsonNull() ? now.get("primary").getAsString() : "";
                    
                    if (!"--".equals(aqi)) {
                        weatherInfo.append("  AQI: " + aqi);
                        if (!"--".equals(level) && !"--".equals(category)) {
                            weatherInfo.append(" (等级" + level + ", " + category + ")");
                        }
                        weatherInfo.append("\n");
                    }
                    if (!primary.isEmpty()) {
                        weatherInfo.append("  首要污染物: " + primary + "\n");
                    }

                    String[] pollutantKeys = {"pm10", "pm2p5", "no2", "so2", "co", "o3"};
                    String[] pollutantNames = {"PM10", "PM2.5", "NO2", "SO2", "CO", "O3"};
                    boolean hasPollutants = false;
                    
                    for (int i = 0; i < pollutantKeys.length; i++) {
                        if (now.has(pollutantKeys[i]) && !now.get(pollutantKeys[i]).isJsonNull()) {
                            hasPollutants = true;
                            break;
                        }
                    }
                    
                    if (hasPollutants) {
                        weatherInfo.append("\n污染物浓度:\n");
                        for (int i = 0; i < pollutantKeys.length; i++) {
                            if (now.has(pollutantKeys[i]) && !now.get(pollutantKeys[i]).isJsonNull()) {
                                String value = now.get(pollutantKeys[i]).getAsString();
                                String unit = "μg/m³";
                                if ("co".equals(pollutantKeys[i])) {
                                    unit = "mg/m³";
                                }
                                weatherInfo.append("  " + pollutantNames[i] + ": " + value + " " + unit + "\n");
                            }
                        }
                    }
                }
            }

            if (jsonObject.has("indexes") && !jsonObject.get("indexes").isJsonNull()) {
                JsonElement indexesElement = jsonObject.get("indexes");
                if (indexesElement.isJsonArray()) {
                    JsonArray indexes = indexesElement.getAsJsonArray();
                    if (indexes != null && indexes.size() > 0) {
                        for (int i = 0; i < indexes.size(); i++) {
                            JsonElement indexElement = indexes.get(i);
                            if (indexElement.isJsonObject()) {
                                JsonObject index = indexElement.getAsJsonObject();
                                String name = index.has("name") && !index.get("name").isJsonNull() ? index.get("name").getAsString() : "";
                                String aqiDisplay = index.has("aqiDisplay") && !index.get("aqiDisplay").isJsonNull() ? index.get("aqiDisplay").getAsString() : "";
                                String level = index.has("level") && !index.get("level").isJsonNull() ? index.get("level").getAsString() : "";
                                String category = index.has("category") && !index.get("category").isJsonNull() ? index.get("category").getAsString() : "";
                                
                                if (!name.isEmpty()) {
                                    weatherInfo.append("  " + name + ": " + aqiDisplay);
                                    if (!level.isEmpty() && !category.isEmpty()) {
                                        weatherInfo.append(" (等级" + level + ", " + category + ")");
                                    }
                                    weatherInfo.append("\n");
                                }
                                
                                String primaryPollutant = "";
                                if (index.has("primaryPollutant") && !index.get("primaryPollutant").isJsonNull()) {
                                    JsonElement ppElement = index.get("primaryPollutant");
                                    if (ppElement.isJsonObject()) {
                                        JsonObject pp = ppElement.getAsJsonObject();
                                        primaryPollutant = pp.has("name") && !pp.get("name").isJsonNull() ? pp.get("name").getAsString() : "";
                                    }
                                }
                                if (!primaryPollutant.isEmpty()) {
                                    weatherInfo.append("    首要污染物: " + primaryPollutant + "\n");
                                }
                                
                                if (index.has("health") && !index.get("health").isJsonNull()) {
                                    JsonElement healthElement = index.get("health");
                                    if (healthElement.isJsonObject()) {
                                        JsonObject health = healthElement.getAsJsonObject();
                                        String effect = health.has("effect") && !health.get("effect").isJsonNull() ? health.get("effect").getAsString() : "";
                                        if (!effect.isEmpty()) {
                                            weatherInfo.append("    健康影响: " + effect + "\n");
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (jsonObject.has("pollutants") && !jsonObject.get("pollutants").isJsonNull()) {
                JsonElement pollutantsElement = jsonObject.get("pollutants");
                if (pollutantsElement.isJsonArray()) {
                    JsonArray pollutants = pollutantsElement.getAsJsonArray();
                    if (pollutants != null && pollutants.size() > 0) {
                        boolean alreadyHasPollutants = weatherInfo.toString().contains("污染物浓度");
                        if (!alreadyHasPollutants) {
                            weatherInfo.append("\n污染物浓度:\n");
                        }
                        
                        for (int i = 0; i < pollutants.size(); i++) {
                            JsonElement pollutantElement = pollutants.get(i);
                            if (pollutantElement.isJsonObject()) {
                                JsonObject pollutant = pollutantElement.getAsJsonObject();
                                String name = pollutant.has("name") && !pollutant.get("name").isJsonNull() ? pollutant.get("name").getAsString() : "";
                                
                                if (!name.isEmpty() && pollutant.has("concentration") && !pollutant.get("concentration").isJsonNull()) {
                                    JsonElement concElement = pollutant.get("concentration");
                                    if (concElement.isJsonObject()) {
                                        JsonObject conc = concElement.getAsJsonObject();
                                        String value = conc.has("value") && !conc.get("value").isJsonNull() ? conc.get("value").getAsString() : "";
                                        String unit = conc.has("unit") && !conc.get("unit").isJsonNull() ? conc.get("unit").getAsString() : "";
                                        if (!value.isEmpty()) {
                                            weatherInfo.append("  " + name + ": " + value);
                                            if (!unit.isEmpty()) {
                                                weatherInfo.append(" " + unit);
                                            }
                                            weatherInfo.append("\n");
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (weatherInfo.length() == 0 || weatherInfo.toString().equals("空气质量:\n")) {
                return "空气质量: 暂无数据";
            }

            return weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng air quality response", e);
            return "空气质量: 查询失败";
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

    private double[] getCoordinatesFromLocationId(String locationId, String apiKey) {
        try {
            String urlString = HEFENG_GEOCODE_URL + "?location=" + locationId;
                String response = httpGet(urlString, apiKey);
                JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            if ("200".equals(jsonObject.get("code").getAsString())) {
                JsonArray locationArray = jsonObject.getAsJsonArray("location");
                if (locationArray != null && locationArray.size() > 0) {
                    JsonObject location = locationArray.get(0).getAsJsonObject();
                    double lat = Double.parseDouble(location.get("lat").getAsString());
                    double lon = Double.parseDouble(location.get("lon").getAsString());
                    return new double[]{lat, lon};
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get coordinates from location ID", e);
        }
        return null;
    }

    // 解析和风天气预警响应
    private String parseHefengAlertsResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "天气预警:\n查询失败（响应为空）";
        }
        
        try {
            JsonElement jsonElement = gson.fromJson(response, JsonElement.class);
            
            if (jsonElement == null || !jsonElement.isJsonObject()) {
                return "天气预警:\n查询失败（响应格式错误）";
            }
            
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            
            String code = "";
            if (jsonObject.has("code") && !jsonObject.get("code").isJsonNull()) {
                code = jsonObject.get("code").getAsString();
            }
            
            if (!"200".equals(code)) {
                String msg = jsonObject.has("message") && !jsonObject.get("message").isJsonNull() 
                    ? jsonObject.get("message").getAsString() : "未知错误";
                return "天气预警:\n查询失败（" + msg + "）";
            }

            if (!jsonObject.has("alerts") || jsonObject.get("alerts").isJsonNull()) {
                return "天气预警:\n暂无预警信息";
            }

            boolean zeroResult = false;
            if (jsonObject.has("metadata") && !jsonObject.get("metadata").isJsonNull()) {
                JsonElement metadataElement = jsonObject.get("metadata");
                if (metadataElement.isJsonObject()) {
                    JsonObject metadata = metadataElement.getAsJsonObject();
                    zeroResult = metadata.has("zeroResult") && !metadata.get("zeroResult").isJsonNull() 
                        && metadata.get("zeroResult").getAsBoolean();
                }
            }
            
            JsonElement alertArrayElement = jsonObject.get("alerts");
            if (!alertArrayElement.isJsonArray()) {
                return "天气预警:\n暂无预警信息";
            }
            JsonArray alertArray = alertArrayElement.getAsJsonArray();

            StringBuilder weatherInfo = new StringBuilder();

            if (zeroResult || alertArray == null || alertArray.size() == 0) {
                weatherInfo.append("暂无预警信息");
                return "天气预警:\n" + weatherInfo.toString();
            }

            for (int i = 0; i < alertArray.size(); i++) {
                JsonElement alertElement = alertArray.get(i);
                if (!alertElement.isJsonObject()) continue;
                JsonObject alert = alertElement.getAsJsonObject();
                
                String senderName = alert.has("senderName") && !alert.get("senderName").isJsonNull() ? alert.get("senderName").getAsString() : "";
                String eventTypeName = "";
                if (alert.has("eventType") && !alert.get("eventType").isJsonNull()) {
                    JsonElement eventTypeElement = alert.get("eventType");
                    if (eventTypeElement.isJsonObject()) {
                        JsonObject eventType = eventTypeElement.getAsJsonObject();
                        eventTypeName = eventType.has("name") && !eventType.get("name").isJsonNull() ? eventType.get("name").getAsString() : "";
                    }
                }
                
                String severity = alert.has("severity") && !alert.get("severity").isJsonNull() ? alert.get("severity").getAsString() : "未知";
                String colorCode = "";
                if (alert.has("color") && !alert.get("color").isJsonNull()) {
                    JsonElement colorElement = alert.get("color");
                    if (colorElement.isJsonObject()) {
                        JsonObject color = colorElement.getAsJsonObject();
                        colorCode = color.has("code") && !color.get("code").isJsonNull() ? color.get("code").getAsString() : "";
                    }
                }
                
                String headline = alert.has("headline") && !alert.get("headline").isJsonNull() ? alert.get("headline").getAsString() : "";
                String description = alert.has("description") && !alert.get("description").isJsonNull() ? alert.get("description").getAsString() : "";
                String instruction = alert.has("instruction") && !alert.get("instruction").isJsonNull() ? alert.get("instruction").getAsString() : "";
                String effectiveTime = alert.has("effectiveTime") && !alert.get("effectiveTime").isJsonNull() ? alert.get("effectiveTime").getAsString() : "";
                String expireTime = alert.has("expireTime") && !alert.get("expireTime").isJsonNull() ? alert.get("expireTime").getAsString() : "";

                if (!eventTypeName.isEmpty()) {
                    weatherInfo.append("【" + colorCode + "】" + eventTypeName + "\n");
                }
                if (!senderName.isEmpty()) {
                    weatherInfo.append("发布机构: " + senderName + "\n");
                }
                if (!"未知".equals(severity)) {
                    weatherInfo.append("预警等级: " + severity + "\n");
                }
                if (!headline.isEmpty()) {
                    weatherInfo.append("标题: " + headline + "\n");
                }
                if (!description.isEmpty()) {
                    weatherInfo.append("描述: " + description + "\n");
                }
                if (!instruction.isEmpty()) {
                    weatherInfo.append("防御指南: " + instruction + "\n");
                }
                if (!effectiveTime.isEmpty() || !expireTime.isEmpty()) {
                    weatherInfo.append("生效时间: " + effectiveTime + " ~ " + expireTime + "\n");
                }
                weatherInfo.append("\n");
            }

            if (weatherInfo.length() == 0) {
                return "天气预警:\n暂无预警信息";
            }

            return "天气预警:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng alerts response", e);
            return "天气预警:\n查询失败";
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

                return fetchAllIndices(locationId, apiKey);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng indices", e);
                return "获取生活指数失败: " + e.getMessage();
            }
        });
    }

    // 解析和风天气生活指数响应
    private String parseHefengIndicesResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "生活指数:\n查询失败（响应为空）";
        }
        
        try {
            JsonElement jsonElement = gson.fromJson(response, JsonElement.class);
            
            if (jsonElement == null || !jsonElement.isJsonObject()) {
                return "生活指数:\n查询失败（响应格式错误）";
            }
            
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            
            String code = "";
            if (jsonObject.has("code") && !jsonObject.get("code").isJsonNull()) {
                code = jsonObject.get("code").getAsString();
            }
            
            if (!"200".equals(code)) {
                String msg = jsonObject.has("message") && !jsonObject.get("message").isJsonNull() 
                    ? jsonObject.get("message").getAsString() : "未知错误";
                return "生活指数:\n查询失败（" + msg + "）";
            }

            if (!jsonObject.has("daily") || jsonObject.get("daily").isJsonNull()) {
                return "生活指数:\n暂无数据";
            }

            JsonElement indicesArrayElement = jsonObject.get("daily");
            if (!indicesArrayElement.isJsonArray()) {
                return "生活指数:\n暂无数据";
            }
            JsonArray indicesArray = indicesArrayElement.getAsJsonArray();

            if (indicesArray == null || indicesArray.size() == 0) {
                return "生活指数:\n暂无数据";
            }

            StringBuilder weatherInfo = new StringBuilder();

            for (int i = 0; i < indicesArray.size(); i++) {
                JsonElement indicesElement = indicesArray.get(i);
                if (!indicesElement.isJsonObject()) continue;
                JsonObject indices = indicesElement.getAsJsonObject();
                
                String name = indices.has("name") && !indices.get("name").isJsonNull() ? indices.get("name").getAsString() : "";
                String level = indices.has("level") && !indices.get("level").isJsonNull() ? indices.get("level").getAsString() : "";
                String category = indices.has("category") && !indices.get("category").isJsonNull() ? indices.get("category").getAsString() : "";
                String text = indices.has("text") && !indices.get("text").isJsonNull() ? indices.get("text").getAsString() : "";

                if (!name.isEmpty()) {
                    weatherInfo.append("  " + name + ":");
                    if (!category.isEmpty()) {
                        weatherInfo.append(" " + category);
                        if (!level.isEmpty()) {
                            weatherInfo.append("(等级" + level + ")");
                        }
                    }
                    weatherInfo.append("\n");
                    
                    if (!text.isEmpty()) {
                        weatherInfo.append("    " + text + "\n");
                    }
                    weatherInfo.append("\n");
                }
            }

            if (weatherInfo.length() == 0) {
                return "生活指数:\n暂无数据";
            }

            return "生活指数:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng indices response", e);
            return "生活指数:\n查询失败";
        }
    }

    private String fetchAllIndices(String location, String apiKey) {
        try {
            String urlString = HEFENG_INDICES_URL + "?location=" + location + "&type=" + HEFENG_INDICES_TYPE_ALL;
            String response = httpGet(urlString, apiKey);
            String parsed = parseHefengIndicesResponse(response);
            if (parsed != null && !parsed.isEmpty()) {
                return parsed;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error fetching indices", e);
        }
        return "生活指数:\n暂无数据";
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
                return fetchAllIndices(location, apiKey);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng indices by location", e);
                return "获取生活指数失败: " + e.getMessage();
            }
        });
    }

    // 获取和风天气分钟级降水
    public CompletableFuture<String> getHefengMinutelyByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();
                String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
                String urlString = HEFENG_MINUTELY_URL + "?location=" + location;
                String response = httpGet(urlString, apiKey);
                return parseHefengMinutelyResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng minutely precipitation", e);
                return "获取分钟级降水失败: " + e.getMessage();
            }
        });
    }

    // 解析和风天气分钟级降水响应
    private String parseHefengMinutelyResponse(String response) {
        try {
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            if (!"200".equals(jsonObject.get("code").getAsString())) {
                return "分钟级降水查询失败: " + jsonObject.get("code").getAsString();
            }

            String summary = jsonObject.has("summary") ? jsonObject.get("summary").getAsString() : "";
            JsonArray minutelyArray = jsonObject.getAsJsonArray("minutely");

            StringBuilder weatherInfo = new StringBuilder();
            weatherInfo.append("摘要: " + summary + "\n\n");

            if (minutelyArray == null || minutelyArray.size() == 0) {
                weatherInfo.append("暂无分钟级降水数据");
                return "分钟级降水:\n" + weatherInfo.toString();
            }

            for (int i = 0; i < minutelyArray.size(); i++) {
                JsonObject minutely = minutelyArray.get(i).getAsJsonObject();
                String fxTime = minutely.has("fxTime") ? minutely.get("fxTime").getAsString() : "";
                String precip = minutely.has("precip") ? minutely.get("precip").getAsString() : "0";
                String type = minutely.has("type") ? minutely.get("type").getAsString() : "";

                String timeStr = fxTime;
                if (fxTime.length() > 16) {
                    timeStr = fxTime.substring(11, 16);
                }
                
                String typeStr = "rain".equals(type) ? "雨" : ("snow".equals(type) ? "雪" : type);
                
                if (Double.parseDouble(precip) > 0) {
                    weatherInfo.append(timeStr + ": " + precip + "mm (" + typeStr + ")\n");
                }
            }

            return "分钟级降水:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng minutely response", e);
            return "解析分钟级降水失败: " + e.getMessage();
        }
    }

    public CompletableFuture<String> getHefengSunByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String apiKey = getHefengApiKey();
                String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd");
                String date = sdf.format(new java.util.Date());
                String urlString = HEFENG_SUN_URL + "?location=" + location + "&date=" + date;
                String response = httpGet(urlString, apiKey);
                return parseHefengSunResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng sunrise/sunset", e);
                return "获取日出日落失败: " + e.getMessage();
            }
        });
    }

    private String parseHefengSunResponse(String response) {
        try {
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            if (!"200".equals(jsonObject.get("code").getAsString())) {
                return "日出日落查询失败";
            }

            String sunrise = jsonObject.has("sunrise") ? jsonObject.get("sunrise").getAsString() : "";
            String sunset = jsonObject.has("sunset") ? jsonObject.get("sunset").getAsString() : "";

            String sunriseTime = sunrise.length() > 16 ? sunrise.substring(11, 16) : sunrise;
            String sunsetTime = sunset.length() > 16 ? sunset.substring(11, 16) : sunset;

            return "日出日落:\n日出: " + sunriseTime + "\n日落: " + sunsetTime;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng sun response", e);
            return "解析日出日落失败: " + e.getMessage();
        }
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
                
                Request request = NetworkUtil.createApiRequestBuilder(urlBuilder.toString()).build();
                
                try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new Exception("HTTP " + response.code());
                    }
                    String responseBody = response.body() != null ? response.body().string() : "";
                    return parseOneCallWeatherResponse(responseBody);
                }
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
                
                Request request = NetworkUtil.createApiRequestBuilder(urlBuilder.toString()).build();
                
                try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new Exception("HTTP " + response.code());
                    }
                    String responseBody = response.body() != null ? response.body().string() : "";
                    return parseTimestampWeatherResponse(responseBody);
                }
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
                
                Request request = NetworkUtil.createApiRequestBuilder(urlBuilder.toString()).build();
                
                try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new Exception("HTTP " + response.code());
                    }
                    String responseBody = response.body() != null ? response.body().string() : "";
                    return parseDailyAggregationResponse(responseBody);
                }
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
                
                Request request = NetworkUtil.createApiRequestBuilder(urlBuilder.toString()).build();
                
                try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new Exception("HTTP " + response.code());
                    }
                    String responseBody = response.body() != null ? response.body().string() : "";
                    return parseWeatherOverviewResponse(responseBody);
                }
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
            
            CompletableFuture.allOf(currentFuture, forecastFuture, hourlyFuture, airFuture, alertsFuture, indicesFuture).get(15, TimeUnit.SECONDS);
            
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
            
            String result = getOneCallWeather(lat, lon, exclude, units, lang).get(10, TimeUnit.SECONDS);
            
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

    /**
     * 智能查询结果类，包含重试信息
     */
    public static class QueryRetryResult {
        public boolean success;
        public String data;
        public String provider;
        public String queryType;
        public int attempt;
        public String errorMessage;

        public QueryRetryResult(boolean success, String data, String provider, String queryType, int attempt, String errorMessage) {
            this.success = success;
            this.data = data;
            this.provider = provider;
            this.queryType = queryType;
            this.attempt = attempt;
            this.errorMessage = errorMessage;
        }
    }

    /**
     * 智能获取当前天气（支持重试和多提供商）
     */
    public QueryRetryResult getCurrentWeatherSmart(String city, Double lat, Double lon) {
        return executeSmartQuery("current", city, lat, lon);
    }

    /**
     * 智能获取天气预报（支持重试和多提供商）
     */
    public QueryRetryResult getForecastSmart(String city, Double lat, Double lon) {
        return executeSmartQuery("forecast", city, lat, lon);
    }

    /**
     * 智能获取小时预报（支持重试和多提供商）
     */
    public QueryRetryResult getHourlySmart(String city, Double lat, Double lon) {
        return executeSmartQuery("hourly", city, lat, lon);
    }

    /**
     * 智能获取空气质量（支持重试和多提供商）
     */
    public QueryRetryResult getAirQualitySmart(String city, Double lat, Double lon) {
        return executeSmartQuery("air_quality", city, lat, lon);
    }

    /**
     * 智能获取天气预警（支持重试和多提供商）
     */
    public QueryRetryResult getAlertsSmart(String city, Double lat, Double lon) {
        return executeSmartQuery("alerts", city, lat, lon);
    }

    /**
     * 智能获取生活指数（支持重试和多提供商）
     */
    public QueryRetryResult getIndicesSmart(String city, Double lat, Double lon) {
        return executeSmartQuery("indices", city, lat, lon);
    }

    /**
     * 执行智能查询（支持重试和多提供商切换）
     */
    private QueryRetryResult executeSmartQuery(String queryType, String city, Double lat, Double lon) {
        int maxAttempts = 2;
        WeatherProvider[] providers = {currentProvider};
        
        if (currentProvider == WeatherProvider.HEFENG) {
            providers = new WeatherProvider[]{WeatherProvider.HEFENG, WeatherProvider.OPENWEATHERMAP};
        } else {
            providers = new WeatherProvider[]{WeatherProvider.OPENWEATHERMAP, WeatherProvider.HEFENG};
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            for (WeatherProvider provider : providers) {
                try {
                    String result = executeQuery(provider, queryType, city, lat, lon);
                    if (result != null && !result.startsWith("获取") && !result.startsWith("解析")) {
                        return new QueryRetryResult(true, result, provider.name(), queryType, attempt, null);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Query failed on provider " + provider + ", attempt " + attempt + ": " + e.getMessage());
                }
            }
        }

        return new QueryRetryResult(false, null, currentProvider.name(), queryType, maxAttempts, "所有提供商查询均失败");
    }

    /**
     * 根据提供商和查询类型执行具体查询
     */
    private String executeQuery(WeatherProvider provider, String queryType, String city, Double lat, Double lon) throws Exception {
        WeatherProvider originalProvider = currentProvider;
        currentProvider = provider;
        
        try {
            boolean useLocation = lat != null && lon != null;
            
            switch (queryType) {
                case "current":
                    if (useLocation) {
                        return getCurrentWeatherByLocation(lat, lon).get(10, TimeUnit.SECONDS);
                    } else if (city != null && !city.isEmpty()) {
                        return getCurrentWeather(city).get(10, TimeUnit.SECONDS);
                    }
                    return "请提供城市名称或经纬度";
                    
                case "forecast":
                    if (useLocation) {
                        return getHefengForecastByLocation(lat, lon).get(10, TimeUnit.SECONDS);
                    } else if (city != null && !city.isEmpty()) {
                        return getHefengForecast(city).get(10, TimeUnit.SECONDS);
                    }
                    return "请提供城市名称或经纬度";
                    
                case "hourly":
                    if (useLocation) {
                        return getHefengHourlyByLocation(lat, lon).get(10, TimeUnit.SECONDS);
                    } else if (city != null && !city.isEmpty()) {
                        return getHefengHourly(city).get(10, TimeUnit.SECONDS);
                    }
                    return "请提供城市名称或经纬度";
                    
                case "air_quality":
                    if (useLocation) {
                        return getHefengAirQualityByLocation(lat, lon).get(10, TimeUnit.SECONDS);
                    } else if (city != null && !city.isEmpty()) {
                        return getHefengAirQuality(city).get(10, TimeUnit.SECONDS);
                    }
                    return "请提供城市名称或经纬度";
                    
                case "alerts":
                    if (useLocation) {
                        return getHefengAlertsByLocation(lat, lon).get(10, TimeUnit.SECONDS);
                    } else if (city != null && !city.isEmpty()) {
                        return getHefengAlerts(city).get(10, TimeUnit.SECONDS);
                    }
                    return "请提供城市名称或经纬度";
                    
                case "indices":
                    if (useLocation) {
                        return getHefengIndicesByLocation(lat, lon).get(10, TimeUnit.SECONDS);
                    } else if (city != null && !city.isEmpty()) {
                        return getHefengIndices(city).get(10, TimeUnit.SECONDS);
                    }
                    return "请提供城市名称或经纬度";
                    
                default:
                    return "未知的查询类型: " + queryType;
            }
        } finally {
            currentProvider = originalProvider;
        }
    }
}
