package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.oilquiz.app.ai.model.APIConfig;
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
import okhttp3.ResponseBody;
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
    
    // 和风天气 API URLs (all use the same JWT-authenticated host)
    private static final String DEFAULT_HEFENG_API_HOST = "https://m278m2y7ak.re.qweatherapi.com";
    private static final String HEFENG_INDICES_TYPE_ALL = "0";

    // v1 API 端点 (空气质量、天气预警) - 使用路径参数 {latitude}/{longitude}
    // v7/air/now 已于 2026-06-01 停止服务，v7/warning/now 将于 2026-09-01 停止服务
    // v1 端点必须使用 JWT 专用主机 (apiHost)，公共主机 api.qweather.com 会返回 403
    private String getAirUrl(double lat, double lon) {
        return apiHost + "/airquality/v1/current/" + String.format(java.util.Locale.US, "%.2f", lat) + "/" + String.format(java.util.Locale.US, "%.2f", lon);
    }
    private String getAirDailyUrl(double lat, double lon) {
        return apiHost + "/airquality/v1/daily/" + String.format(java.util.Locale.US, "%.2f", lat) + "/" + String.format(java.util.Locale.US, "%.2f", lon);
    }
    private String getAlertUrl(double lat, double lon) {
        return apiHost + "/weatheralert/v1/current/" + String.format(java.util.Locale.US, "%.2f", lat) + "/" + String.format(java.util.Locale.US, "%.2f", lon);
    }

    // v7 API 端点 (生活指数、日出日落) - 使用 location 参数
    private String getIndicesUrl(double lat, double lon) {
        return apiHost + "/v7/indices/1d?type=" + HEFENG_INDICES_TYPE_ALL + "&location=" + String.format(java.util.Locale.US, "%.2f", lon) + "," + String.format(java.util.Locale.US, "%.2f", lat);
    }
    private String getSunUrl(double lat, double lon) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
        String date = sdf.format(new java.util.Date());
        return apiHost + "/v7/astronomy/sun?location=" + String.format(java.util.Locale.US, "%.2f", lon) + "," + String.format(java.util.Locale.US, "%.2f", lat) + "&date=" + date;
    }

    // v7 API 端点 (天气)
    private String getWeatherUrl() { return apiHost + "/v7/weather/now"; }
    private String getForecastUrl() { return apiHost + "/v7/weather/7d"; }
    private String getHourlyUrl() { return apiHost + "/v7/weather/24h"; }
    private String getMinutelyUrl(double lat, double lon) {
        return apiHost + "/v7/minutely/5m?location=" + String.format(java.util.Locale.US, "%.2f", lon) + "," + String.format(java.util.Locale.US, "%.2f", lat);
    }
    private String getGeocodeUrl() { return "https://api.qweather.com/geo/v2/city/lookup"; }
    
    // 备用天气预警API (APISpace - 数据来自国家预警中心)
    private static final String APISPACE_ALERTS_URL = "https://eolink.o.apispace.com/467456/weather/v001/alarm";
    private static final String APISPACE_TOKEN = "";
    
    private static final String DEFAULT_QWEATHER_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEICwOvrfAlLBDEnFi+yhRLmCql0P1oXEgu7Jb2akwAQmJ\n-----END PRIVATE KEY-----";
    private static final String DEFAULT_QWEATHER_PROJECT_ID = "2B89AN9KXV";
    private static final String DEFAULT_QWEATHER_KID = "CAPR2BDUDV";

    private final Context context;
    private final Gson gson;
    private WeatherProvider currentProvider = WeatherProvider.HEFENG;
    private QWeatherJwtGenerator jwtGenerator;
    private String apiHost = DEFAULT_HEFENG_API_HOST;
    private com.oilquiz.app.weather.QWeatherSdkManager sdkManager;

    public AIWeatherManager(Context context) {
        this.context = context;
        this.gson = new Gson();
        tryLoadJwtCredentials();
        initSdkManager();
    }

    public AIWeatherManager(Context context, WeatherProvider provider) {
        this.context = context;
        this.gson = new Gson();
        this.currentProvider = provider;
        tryLoadJwtCredentials();
        initSdkManager();
    }

    /** 初始化 QWeather SDK Manager（优先走SDK路径，自动处理JWT/压缩/JSON解析） */
    private void initSdkManager() {
        try {
            sdkManager = com.oilquiz.app.weather.QWeatherSdkManager.getInstance(context);
            if (!sdkManager.isInitialized()) {
                sdkManager.initializeFromStorage();
            }
            if (sdkManager.isInitialized()) {
                Log.i(TAG, "QWeather SDK initialized for AI weather tool");
            } else {
                Log.w(TAG, "QWeather SDK not initialized, will use HTTP fallback");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to init QWeather SDK, will use HTTP fallback: " + e.getMessage());
            sdkManager = null;
        }
    }

    /** 构建SDK所需的location参数（坐标或位置ID） */
    private String buildSdkLocation(String city, Double lat, Double lon, boolean useLocation) {
        if (useLocation && lat != null && lon != null) {
            return String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
        } else if (city != null && !city.isEmpty()) {
            try {
                String locationId = getHefengLocationId(city);
                if (locationId != null && !locationId.isEmpty()) {
                    return locationId;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to get location ID for city: " + city, e);
            }
            return city;
        }
        return null;
    }

    /** 检查SDK返回结果是否为有效天气数据（非错误） */
    private boolean isSdkResultValid(String result) {
        return result != null && !result.isEmpty()
            && !result.contains("查询失败") && !result.contains("查询异常")
            && !result.contains("SDK未初始化") && !result.contains("超时");
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
            // 优先从存储加载凭证，如果没有则使用默认凭证
            String privateKey = apiKeyManager.getQWeatherPrivateKey();
            String projectId = apiKeyManager.getQWeatherProjectId();
            String kid = apiKeyManager.getQWeatherKid();
            String apiHost = apiKeyManager.getQWeatherApiHost();

            // 如果存储中没有凭证，使用默认凭证并保存
            if (privateKey == null || privateKey.isEmpty()) {
                privateKey = DEFAULT_QWEATHER_PRIVATE_KEY;
                projectId = DEFAULT_QWEATHER_PROJECT_ID;
                kid = DEFAULT_QWEATHER_KID;
                apiHost = DEFAULT_HEFENG_API_HOST;
                apiKeyManager.saveQWeatherJwtCredentials(privateKey, projectId, kid, apiHost);
                Log.d(TAG, "Using default QWeather credentials");
            }

            jwtGenerator = new QWeatherJwtGenerator(privateKey, projectId, kid);
            if (apiHost != null && !apiHost.isEmpty()) {
                updateHefengApiHost(apiHost);
            }
            Log.d(TAG, "QWeather JWT initialized (projectId=" + projectId + ", kid=" + kid + ", host=" + apiHost + ")");
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
        this.apiHost = newHost;
        APIKeyManager.getInstance(context).saveAPIHost(APIKeyManager.Service.HEFENG_WEATHER, newHost);
    }

    private String getApiHost() {
        return apiHost;
    }

    /** 获取和风天气 API Key（从 APIKeyManager 或 APIConfig 中获取） */
    private String getQWeatherApiKey() {
        try {
            APIKeyManager mgr = APIKeyManager.getInstance(context);
            // 1. 尝试从旧式存储获取
            String key = mgr.getAPIKey(APIKeyManager.Service.HEFENG_WEATHER);
            if (key != null && !key.isEmpty()) return key;
            // 2. 尝试从 APIConfig 获取
            APIConfig config = mgr.getAPIConfigByServiceType(APIConfig.ServiceType.HEFENG_WEATHER);
            if (config != null && config.getApiKey() != null && !config.getApiKey().isEmpty()) {
                return config.getApiKey();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting QWeather API Key", e);
        }
        return null;
    }

    /** 使用 API Key 认证 (X-QW-Api-Key) 发送 GET 请求 */
    private String httpGetWithApiKey(String urlString, String apiKey) throws Exception {
        Request.Builder requestBuilder = new Request.Builder()
                .url(urlString)
                .addHeader("User-Agent", "SmartQuiz/1.0")
                .addHeader("Accept", "application/json")
                .addHeader("Accept-Language", "zh-CN")
                .addHeader("Accept-Encoding", "gzip, deflate")
                .addHeader("X-QW-Api-Key", apiKey);

        Request request = requestBuilder.build();
        try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
            if (response.isSuccessful()) {
                return readResponseBody(response);
            }
            int statusCode = response.code();
            String errorBody = "";
            try { errorBody = readResponseBody(response); } catch (Exception ignored) {}
            Log.w(TAG, "API Key HTTP " + statusCode + " for URL: " + urlString + " | Response: " + errorBody);
            throw new Exception("HTTP " + statusCode + ". Body: " + errorBody);
        }
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

    private String httpGet(String urlString) throws Exception {
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

            String jwtToken = getHefengJwtToken();
            if (jwtToken != null) {
                requestBuilder.addHeader("Authorization", "Bearer " + jwtToken);
                Log.d(TAG, "HTTP GET: " + urlString + " | Token: " + jwtToken.substring(0, Math.min(50, jwtToken.length())) + "...");
            } else {
                throw new Exception("JWT token not available");
            }

            Request request = requestBuilder.build();

            try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                if (response.isSuccessful()) {
                    return readResponseBody(response);
                }

                int statusCode = response.code();
                // 捕获错误响应体用于调试（处理gzip压缩）
                String errorBody = "";
                try {
                    errorBody = readResponseBody(response);
                } catch (Exception ignored) {}
                Log.w(TAG, "HTTP " + statusCode + " for URL: " + urlString + " | Response: " + errorBody);

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
                        throw new Exception("HTTP " + statusCode + " after " + maxRetries + " retries. Body: " + errorBody);
                    }
                } else {
                    throw new Exception("HTTP " + statusCode + ". Body: " + errorBody);
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

    /** 读取响应体，自动处理gzip解压（NetworkUtil拦截器手动添加了Accept-Encoding:gzip，OkHttp不会自动解压） */
    private String readResponseBody(Response response) throws IOException {
        if (response.body() == null) return "";
        String contentEncoding = response.header("Content-Encoding");
        if (contentEncoding != null && contentEncoding.contains("gzip")) {
            try (java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(response.body().byteStream())) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int len;
                while ((len = gis.read(buffer)) != -1) {
                    baos.write(buffer, 0, len);
                }
                return baos.toString("UTF-8");
            }
        }
        return response.body().string();
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
                String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
                String urlString = getWeatherUrl() + "?location=" + location;
                String response = httpGet(urlString);
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
                String locationId = getHefengLocationId(city);
                if (locationId == null || locationId.isEmpty()) {
                    return "天气信息:\n查询失败（无法获取城市位置ID）";
                }

                String urlString = getWeatherUrl() + "?location=" + locationId;
                String response = httpGet(urlString);
                return parseHefengWeatherResponse(response, city);
            } catch (Exception e) {
                Log.e(TAG, "Error getting current weather from Hefeng", e);
                return "天气信息:\n查询失败: " + e.getMessage();
            }
        });
    }

    private CompletableFuture<String> getHefengCurrentWeatherByLocation(double lat, double lon, String cityName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
                String urlString = getWeatherUrl() + "?location=" + location;
                String response = httpGet(urlString);
                return parseHefengWeatherResponse(response, cityName);
            } catch (Exception e) {
                Log.e(TAG, "Error getting current weather by location from Hefeng", e);
                return "天气信息:\n查询失败: " + e.getMessage();
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

    // 获取和风天气城市ID（基于和风天气LocationList，覆盖全国主要城市）
    private static final java.util.Map<String, String> DEFAULT_CITY_IDS = new java.util.HashMap<String, String>() {{
        // 直辖市
        put("北京", "101010100");
        put("上海", "101020100");
        put("天津", "101030100");
        put("重庆", "101040100");

        // 省会城市
        put("哈尔滨", "101050101");
        put("长春", "101060101");
        put("沈阳", "101070101");
        put("呼和浩特", "101080101");
        put("乌鲁木齐", "101130101");
        put("拉萨", "101140101");
        put("银川", "101170101");
        put("西宁", "101150101");
        put("兰州", "101160101");
        put("西安", "101110101");
        put("太原", "101100101");
        put("石家庄", "101090101");
        put("济南", "101120101");
        put("郑州", "101180101");
        put("合肥", "101220101");
        put("南京", "101190101");
        put("成都", "101270101");
        put("贵阳", "101260101");
        put("昆明", "101290101");
        put("长沙", "101250101");
        put("武汉", "101200101");
        put("南昌", "101240101");
        put("杭州", "101210101");
        put("福州", "101230101");
        put("广州", "101280101");
        put("南宁", "101300101");
        put("海口", "101310101");

        // 主要城市
        put("深圳", "101280601");
        put("珠海", "101280701");
        put("汕头", "101280501");
        put("佛山", "101280800");
        put("东莞", "101281601");
        put("中山", "101281701");
        put("惠州", "101281301");
        put("江门", "101281101");
        put("湛江", "101310201");
        put("茂名", "101310301");
        put("肇庆", "101280901");
        put("揭阳", "101281901");
        put("梅州", "101281401");
        put("汕尾", "101282101");
        put("河源", "101281201");
        put("潮州", "101282001");
        put("阳江", "101281801");
        put("清远", "101281001");
        put("韶关", "101280201");

        put("苏州", "101190401");
        put("无锡", "101190201");
        put("常州", "101191101");
        put("南京", "101190101");
        put("镇江", "101191101");
        put("扬州", "101190601");
        put("泰州", "101191201");
        put("南通", "101190501");
        put("徐州", "101190801");
        put("盐城", "101190701");
        put("淮安", "101190901");
        put("连云港", "101191001");
        put("宿迁", "101191301");

        put("宁波", "101210401");
        put("温州", "101210701");
        put("嘉兴", "101210301");
        put("湖州", "101210201");
        put("绍兴", "101210501");
        put("金华", "101210801");
        put("台州", "101210601");
        put("衢州", "101211001");
        put("丽水", "101210901");
        put("舟山", "101211101");

        put("青岛", "101120201");
        put("济南", "101120101");
        put("烟台", "101120501");
        put("潍坊", "101120601");
        put("临沂", "101120901");
        put("淄博", "101120301");
        put("济宁", "101120701");
        put("泰安", "101120801");
        put("聊城", "101121201");
        put("德州", "101120401");
        put("威海", "101121301");
        put("枣庄", "101121401");
        put("日照", "101121501");
        put("菏泽", "101121001");
        put("滨州", "101121101");
        put("东营", "101121601");

        put("大连", "101070201");
        put("沈阳", "101070101");
        put("鞍山", "101070301");
        put("抚顺", "101070401");
        put("本溪", "101070501");
        put("丹东", "101070601");
        put("锦州", "101070701");
        put("营口", "101070801");
        put("阜新", "101070901");
        put("辽阳", "101071001");
        put("盘锦", "101071101");
        put("铁岭", "101071201");
        put("朝阳", "101071301");
        put("葫芦岛", "101071401");

        put("长春", "101060101");
        put("吉林", "101060201");
        put("四平", "101060301");
        put("辽源", "101060401");
        put("通化", "101060501");
        put("白山", "101060601");
        put("松原", "101060701");
        put("白城", "101060801");

        put("哈尔滨", "101050101");
        put("齐齐哈尔", "101050201");
        put("牡丹江", "101050301");
        put("佳木斯", "101050401");
        put("绥化", "101050501");
        put("黑河", "101050601");
        put("大庆", "101050801");
        put("伊春", "101050901");
        put("鸡西", "101051001");
        put("鹤岗", "101051101");
        put("双鸭山", "101051201");
        put("七台河", "101051301");

        put("呼和浩特", "101080101");
        put("包头", "101080201");
        put("鄂尔多斯", "101080301");
        put("赤峰", "101080601");
        put("通辽", "101080501");
        put("呼伦贝尔", "101080801");
        put("巴彦淖尔", "101081001");
        put("乌兰察布", "101080901");
        put("乌海", "101080401");

        put("太原", "101100101");
        put("大同", "101100201");
        put("阳泉", "101100301");
        put("长治", "101100401");
        put("晋城", "101100501");
        put("朔州", "101100601");
        put("晋中", "101100701");
        put("运城", "101100801");
        put("忻州", "101100901");
        put("临汾", "101101001");
        put("吕梁", "101101101");

        put("石家庄", "101090101");
        put("唐山", "101090201");
        put("秦皇岛", "101090301");
        put("邯郸", "101090401");
        put("邢台", "101090501");
        put("保定", "101090601");
        put("张家口", "101090701");
        put("承德", "101090801");
        put("沧州", "101090901");
        put("廊坊", "101091001");
        put("衡水", "101091101");

        put("郑州", "101180101");
        put("开封", "101180201");
        put("洛阳", "101180301");
        put("平顶山", "101180401");
        put("安阳", "101180501");
        put("新乡", "101180601");
        put("焦作", "101180701");
        put("濮阳", "101180801");
        put("许昌", "101180901");
        put("漯河", "101181001");
        put("三门峡", "101181101");
        put("南阳", "101181201");
        put("商丘", "101181301");
        put("信阳", "101181401");
        put("周口", "101181501");
        put("驻马店", "101181601");
        put("鹤壁", "101181701");

        put("合肥", "101220101");
        put("芜湖", "101220201");
        put("蚌埠", "101220301");
        put("淮南", "101220401");
        put("马鞍山", "101220501");
        put("淮北", "101220601");
        put("铜陵", "101220701");
        put("安庆", "101220801");
        put("黄山", "101220901");
        put("滁州", "101221001");
        put("阜阳", "101221101");
        put("宿州", "101221201");
        put("六安", "101221301");
        put("亳州", "101221401");
        put("池州", "101221501");
        put("宣城", "101221601");

        put("武汉", "101200101");
        put("黄石", "101200201");
        put("十堰", "101200301");
        put("宜昌", "101200401");
        put("襄阳", "101200501");
        put("鄂州", "101200601");
        put("荆门", "101200701");
        put("孝感", "101200801");
        put("荆州", "101200901");
        put("黄冈", "101201001");
        put("咸宁", "101201101");
        put("随州", "101201201");
        put("襄阳", "101200501");

        put("长沙", "101250101");
        put("株洲", "101250201");
        put("湘潭", "101250301");
        put("衡阳", "101250401");
        put("邵阳", "101250501");
        put("岳阳", "101250601");
        put("常德", "101250701");
        put("张家界", "101250801");
        put("益阳", "101250901");
        put("郴州", "101251001");
        put("永州", "101251101");
        put("怀化", "101251201");
        put("娄底", "101251301");
        put("湘西", "101251401");

        put("成都", "101270101");
        put("绵阳", "101270201");
        put("德阳", "101270301");
        put("宜宾", "101270401");
        put("南充", "101270501");
        put("达州", "101270601");
        put("遂宁", "101270701");
        put("广安", "101270801");
        put("自贡", "101270901");
        put("攀枝花", "101271001");
        put("泸州", "101271101");
        put("乐山", "101271201");
        put("内江", "101271301");
        put("眉山", "101271401");
        put("广元", "101271501");
        put("资阳", "101271601");
        put("雅安", "101271701");
        put("巴中", "101271801");
        put("凉山", "101271901");

        put("贵阳", "101260101");
        put("遵义", "101260201");
        put("六盘水", "101260301");
        put("安顺", "101260401");
        put("铜仁", "101260501");
        put("毕节", "101260601");
        put("黔东南", "101260701");
        put("黔南", "101260801");
        put("黔西南", "101260901");

        put("昆明", "101290101");
        put("曲靖", "101290201");
        put("玉溪", "101290301");
        put("保山", "101290401");
        put("昭通", "101290501");
        put("丽江", "101290601");
        put("普洱", "101290701");
        put("临沧", "101290801");
        put("楚雄", "101290901");
        put("红河", "101291001");
        put("文山", "101291101");
        put("西双版纳", "101291201");
        put("大理", "101291301");
        put("德宏", "101291401");
        put("怒江", "101291501");
        put("迪庆", "101291601");

        put("南宁", "101300101");
        put("柳州", "101300201");
        put("桂林", "101300301");
        put("梧州", "101300401");
        put("北海", "101300501");
        put("防城港", "101300601");
        put("钦州", "101300701");
        put("贵港", "101300801");
        put("玉林", "101300901");
        put("百色", "101301001");
        put("贺州", "101301101");
        put("河池", "101301201");
        put("来宾", "101301301");
        put("崇左", "101301401");

        put("海口", "101310101");
        put("三亚", "101310201");
        put("三沙", "101310301");
        put("儋州", "101310401");

        put("拉萨", "101140101");
        put("日喀则", "101140201");
        put("昌都", "101140301");
        put("林芝", "101140401");
        put("山南", "101140501");
        put("那曲", "101140601");
        put("阿里", "101140701");

        put("西宁", "101150101");
        put("海东", "101150201");
        put("海北", "101150301");
        put("黄南", "101150401");
        put("海南州", "101150501");
        put("果洛", "101150601");
        put("玉树", "101150701");
        put("海西", "101150801");

        put("银川", "101170101");
        put("石嘴山", "101170201");
        put("吴忠", "101170301");
        put("固原", "101170401");
        put("中卫", "101170501");

        put("兰州", "101160101");
        put("嘉峪关", "101160201");
        put("金昌", "101160301");
        put("白银", "101160401");
        put("天水", "101160501");
        put("武威", "101160601");
        put("张掖", "101160701");
        put("平凉", "101160801");
        put("酒泉", "101160901");
        put("庆阳", "101161001");
        put("定西", "101161101");
        put("陇南", "101161201");
        put("临夏", "101161301");
        put("甘南", "101161401");

        put("乌鲁木齐", "101130101");
        put("克拉玛依", "101130201");
        put("吐鲁番", "101130301");
        put("哈密", "101130401");
        put("昌吉", "101130501");
        put("博尔塔拉", "101130601");
        put("巴音郭楞", "101130701");
        put("阿克苏", "101130801");
        put("克孜勒苏", "101130901");
        put("喀什", "101131001");
        put("和田", "101131101");
        put("伊犁", "101131201");
        put("塔城", "101131301");
        put("阿勒泰", "101131401");

        put("台北", "101340101");
        put("高雄", "101340201");
        put("台中", "101340401");
        put("台南", "101340301");
        put("新竹", "101340501");
        put("嘉义", "101340601");

        put("香港", "101320101");
        put("澳门", "101330101");
    }};

    private String getHefengLocationId(String city) throws Exception {
        String defaultId = DEFAULT_CITY_IDS.get(city);
        if (defaultId != null) {
            return defaultId;
        }
        
        try {
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.name());
            String urlString = getGeocodeUrl() + "?location=" + encodedCity;
                String locationId = getHefengLocationFromGeoAPI(urlString, true);
            if (locationId != null) {
                return locationId;
            }
        } catch (Exception e) {
            Log.w(TAG, "GeoAPI failed for city " + city + ", using default fallback");
        }
        
        return DEFAULT_CITY_IDS.get("北京");
    }

    private double[] getHefengLatLon(String city) throws Exception {
        try {
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.name());
            String urlString = getGeocodeUrl() + "?location=" + encodedCity;
                String response = httpGet(urlString);

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

    public String getHefengCityNameByLocation(double lat, double lon) {
        try {
            String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
            String urlString = getGeocodeUrl() + "?location=" + location;
                String response = httpGet(urlString);

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

    public static class AdministrativeHierarchy {
        public String province;
        public String city;
        public String district;
        public double[] provinceLatLon;
        public double[] cityLatLon;
        public double[] districtLatLon;
    }

    public AdministrativeHierarchy getAdministrativeHierarchy(double lat, double lon) {
        try {
            String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
            String urlString = getGeocodeUrl() + "?location=" + location;
            String response = httpGet(urlString);

            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            if (jsonObject.has("code") && "200".equals(jsonObject.get("code").getAsString())) {
                JsonArray locationArray = jsonObject.getAsJsonArray("location");
                if (locationArray != null && locationArray.size() > 0) {
                    JsonObject loc = locationArray.get(0).getAsJsonObject();
                    
                    AdministrativeHierarchy hierarchy = new AdministrativeHierarchy();
                    
                    hierarchy.district = loc.has("name") ? loc.get("name").getAsString() : "";
                    hierarchy.districtLatLon = new double[]{lat, lon};
                    
                    hierarchy.city = loc.has("parentCity") ? loc.get("parentCity").getAsString() : "";
                    hierarchy.province = loc.has("adminArea") ? loc.get("adminArea").getAsString() : "";
                    
                    String districtId = loc.has("id") ? loc.get("id").getAsString() : "";
                    if (!districtId.isEmpty() && districtId.length() >= 6) {
                        String cityId = districtId.substring(0, 6) + "00";
                        String provinceId = districtId.substring(0, 2) + "0000";
                        
                        try {
                            String cityUrl = getGeocodeUrl() + "?location=" + cityId;
                            String cityResponse = httpGet(cityUrl);
                            JsonObject cityJson = gson.fromJson(cityResponse, JsonObject.class);
                            if (cityJson.has("code") && "200".equals(cityJson.get("code").getAsString())) {
                                JsonArray cityArray = cityJson.getAsJsonArray("location");
                                if (cityArray != null && cityArray.size() > 0) {
                                    JsonObject cityLoc = cityArray.get(0).getAsJsonObject();
                                    double cityLat = cityLoc.has("lat") ? cityLoc.get("lat").getAsDouble() : lat;
                                    double cityLon = cityLoc.has("lon") ? cityLoc.get("lon").getAsDouble() : lon;
                                    hierarchy.cityLatLon = new double[]{cityLat, cityLon};
                                    if (hierarchy.city.isEmpty()) {
                                        hierarchy.city = cityLoc.has("name") ? cityLoc.get("name").getAsString() : "";
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to get city info", e);
                            hierarchy.cityLatLon = new double[]{lat, lon};
                        }
                        
                        try {
                            String provinceUrl = getGeocodeUrl() + "?location=" + provinceId;
                            String provinceResponse = httpGet(provinceUrl);
                            JsonObject provinceJson = gson.fromJson(provinceResponse, JsonObject.class);
                            if (provinceJson.has("code") && "200".equals(provinceJson.get("code").getAsString())) {
                                JsonArray provinceArray = provinceJson.getAsJsonArray("location");
                                if (provinceArray != null && provinceArray.size() > 0) {
                                    JsonObject provinceLoc = provinceArray.get(0).getAsJsonObject();
                                    double provinceLat = provinceLoc.has("lat") ? provinceLoc.get("lat").getAsDouble() : lat;
                                    double provinceLon = provinceLoc.has("lon") ? provinceLoc.get("lon").getAsDouble() : lon;
                                    hierarchy.provinceLatLon = new double[]{provinceLat, provinceLon};
                                    if (hierarchy.province.isEmpty()) {
                                        hierarchy.province = provinceLoc.has("name") ? provinceLoc.get("name").getAsString() : "";
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to get province info", e);
                            hierarchy.provinceLatLon = new double[]{lat, lon};
                        }
                    } else {
                        hierarchy.cityLatLon = new double[]{lat, lon};
                        hierarchy.provinceLatLon = new double[]{lat, lon};
                    }
                    
                    Log.d(TAG, "Administrative hierarchy: province=" + hierarchy.province + ", city=" + hierarchy.city + ", district=" + hierarchy.district);
                    return hierarchy;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get administrative hierarchy", e);
        }
        
        AdministrativeHierarchy fallback = new AdministrativeHierarchy();
        fallback.district = "";
        fallback.city = "";
        fallback.province = "";
        fallback.districtLatLon = new double[]{lat, lon};
        fallback.cityLatLon = new double[]{lat, lon};
        fallback.provinceLatLon = new double[]{lat, lon};
        return fallback;
    }

    private String getHefengLocationFromGeoAPI(String urlString, boolean returnId) throws Exception {
        String response = httpGet(urlString);

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

        // 前置检查：非 JSON 响应（如 HTML 错误页、压缩内容）直接返回，避免 Gson 抛异常
        String trimmed = response.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return "天气信息:\n查询失败（响应非JSON格式: "
                + trimmed.substring(0, Math.min(100, trimmed.length())) + "）";
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
                    return "天气信息:\n请在和风天气控制台开通权限";
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
            String windSpeed = now.has("windSpeed") && !now.get("windSpeed").isJsonNull() ? now.get("windSpeed").getAsString() : "";
            String windDir = now.has("windDir") && !now.get("windDir").isJsonNull() ? now.get("windDir").getAsString() : "";
            String windScale = now.has("windScale") && !now.get("windScale").isJsonNull() ? now.get("windScale").getAsString() : "";
            String wind360 = now.has("wind360") && !now.get("wind360").isJsonNull() ? now.get("wind360").getAsString() : "";
            String feelsLike = now.has("feelsLike") && !now.get("feelsLike").isJsonNull() ? now.get("feelsLike").getAsString() : "--";
            String visibility = now.has("vis") && !now.get("vis").isJsonNull() ? now.get("vis").getAsString() : "";
            String pressure = now.has("pressure") && !now.get("pressure").isJsonNull() ? now.get("pressure").getAsString() : "";
            String uv = now.has("uvIndex") && !now.get("uvIndex").isJsonNull() ? now.get("uvIndex").getAsString() : "";
            String precip = now.has("precip") && !now.get("precip").isJsonNull() ? now.get("precip").getAsString() : "";
            String cloud = now.has("cloud") && !now.get("cloud").isJsonNull() ? now.get("cloud").getAsString() : "";
            String dew = now.has("dew") && !now.get("dew").isJsonNull() ? now.get("dew").getAsString() : "";
            String obsTime = now.has("obsTime") && !now.get("obsTime").isJsonNull() ? now.get("obsTime").getAsString() : "";

            StringBuilder weatherInfo = new StringBuilder();
            weatherInfo.append("城市: ").append(city).append("\n");
            if (!obsTime.isEmpty()) {
                weatherInfo.append("观测时间: ").append(obsTime).append("\n");
            }
            weatherInfo.append("天气: ").append(weather).append("\n");
            if (!icon.isEmpty()) {
                weatherInfo.append("图标: ").append(icon).append("\n");
            }
            weatherInfo.append("温度: ").append(temp).append("°C\n");
            weatherInfo.append("体感温度: ").append(feelsLike).append("°C\n");
            weatherInfo.append("湿度: ").append(humidity).append("%\n");
            if (!precip.isEmpty() && !"0.0".equals(precip) && !"0".equals(precip)) {
                weatherInfo.append("降水量: ").append(precip).append("mm\n");
            }
            weatherInfo.append("风向: ").append(windDir).append("\n");
            if (!wind360.isEmpty()) {
                weatherInfo.append("风向角度: ").append(wind360).append("°\n");
            }
            if (!windScale.isEmpty()) {
                weatherInfo.append("风力: ").append(windScale).append("级\n");
            }
            weatherInfo.append("风速: ").append(windSpeed).append("km/h\n");
            weatherInfo.append("能见度: ").append(visibility).append("km\n");
            weatherInfo.append("气压: ").append(pressure).append("hPa\n");
            if (!cloud.isEmpty()) {
                weatherInfo.append("云量: ").append(cloud).append("%\n");
            }
            if (!dew.isEmpty()) {
                weatherInfo.append("露点温度: ").append(dew).append("°C\n");
            }
            if (!uv.isEmpty()) {
                weatherInfo.append("紫外线: ").append(uv).append("\n");
            }
            
            if (jsonObject.has("fxLink") && !jsonObject.get("fxLink").isJsonNull()) {
                String fxLink = jsonObject.get("fxLink").getAsString();
                weatherInfo.append("链接: ").append(fxLink).append("\n");
            }

            if (jsonObject.has("updateTime") && !jsonObject.get("updateTime").isJsonNull()) {
                String updateTime = jsonObject.get("updateTime").getAsString();
                weatherInfo.append("更新时间: ").append(updateTime).append("\n");
            }

            return "天气信息:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng weather response", e);
            return "天气信息:\n查询失败（" + e.getClass().getSimpleName() + ": " + e.getMessage() + "）";
        }
    }

    // 获取和风天气预报（3-7天）
    public CompletableFuture<String> getHefengForecast(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String locationId = getHefengLocationId(city);
                if (locationId == null || locationId.isEmpty()) {
                    return "天气预报:\n查询失败（无法获取城市位置ID）";
                }

                String urlString = getForecastUrl() + "?location=" + locationId;
                String response = httpGet(urlString);
                return parseHefengForecastResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng forecast", e);
                return "天气预报:\n查询失败: " + e.getMessage();
            }
        });
    }

    // 解析和风天气预报响应
    private String parseHefengForecastResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "天气预报:\n查询失败（响应为空）";
        }

        String trimmed = response.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return "天气预报:\n查询失败（响应非JSON格式: "
                + trimmed.substring(0, Math.min(100, trimmed.length())) + "）";
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
                    return "天气预报:\n请在和风天气控制台开通权限";
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
            
            if (jsonObject.has("fxLink") && !jsonObject.get("fxLink").isJsonNull()) {
                String fxLink = jsonObject.get("fxLink").getAsString();
                weatherInfo.append("链接: ").append(fxLink).append("\n");
            }

            for (int i = 0; i < dailyArray.size(); i++) {
                JsonElement dailyElement = dailyArray.get(i);
                if (!dailyElement.isJsonObject()) continue;
                JsonObject daily = dailyElement.getAsJsonObject();
                
                String date = daily.has("fxDate") && !daily.get("fxDate").isJsonNull() ? daily.get("fxDate").getAsString() : "";
                String weatherDay = daily.has("textDay") && !daily.get("textDay").isJsonNull() ? daily.get("textDay").getAsString() : "";
                String weatherNight = daily.has("textNight") && !daily.get("textNight").isJsonNull() ? daily.get("textNight").getAsString() : "";
                String highTemp = daily.has("tempMax") && !daily.get("tempMax").isJsonNull() ? daily.get("tempMax").getAsString() : "--";
                String lowTemp = daily.has("tempMin") && !daily.get("tempMin").isJsonNull() ? daily.get("tempMin").getAsString() : "--";
                String iconDay = daily.has("iconDay") && !daily.get("iconDay").isJsonNull() ? daily.get("iconDay").getAsString() : "";

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
                if (!iconDay.isEmpty()) {
                    weatherInfo.append("图标: " + iconDay + "\n");
                }
                weatherInfo.append("\n");
            }

            return "天气预报:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng forecast response", e);
            return "天气预报:\n查询失败（" + e.getClass().getSimpleName() + ": " + e.getMessage() + "）";
        }
    }

    // 获取和风天气小时预报（24小时）
    public CompletableFuture<String> getHefengHourly(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String locationId = getHefengLocationId(city);
                if (locationId == null || locationId.isEmpty()) {
                    return "24小时预报:\n查询失败（无法获取城市位置ID）";
                }

                String urlString = getHourlyUrl() + "?location=" + locationId;
                String response = httpGet(urlString);
                return parseHefengHourlyResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng hourly forecast", e);
                return "24小时预报:\n查询失败: " + e.getMessage();
            }
        });
    }

    // 解析和风天气小时预报响应
    private String parseHefengHourlyResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "24小时预报:\n查询失败（响应为空）";
        }

        String trimmed = response.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return "24小时预报:\n查询失败（响应非JSON格式: "
                + trimmed.substring(0, Math.min(100, trimmed.length())) + "）";
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
                    return "24小时预报:\n请在和风天气控制台开通权限";
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
            
            if (jsonObject.has("fxLink") && !jsonObject.get("fxLink").isJsonNull()) {
                String fxLink = jsonObject.get("fxLink").getAsString();
                weatherInfo.append("链接: ").append(fxLink).append("\n");
            }

            for (int i = 0; i < Math.min(24, hourlyArray.size()); i++) {
                JsonElement hourlyElement = hourlyArray.get(i);
                if (!hourlyElement.isJsonObject()) continue;
                JsonObject hourly = hourlyElement.getAsJsonObject();
                
                String time = hourly.has("fxTime") && !hourly.get("fxTime").isJsonNull() ? hourly.get("fxTime").getAsString() : "";
                String weather = hourly.has("text") && !hourly.get("text").isJsonNull() ? hourly.get("text").getAsString() : "";
                String temp = hourly.has("temp") && !hourly.get("temp").isJsonNull() ? hourly.get("temp").getAsString() : "--";
                String pop = hourly.has("pop") && !hourly.get("pop").isJsonNull() ? hourly.get("pop").getAsString() : "0";
                String icon = hourly.has("icon") && !hourly.get("icon").isJsonNull() ? hourly.get("icon").getAsString() : "";
                String humidity = hourly.has("humidity") && !hourly.get("humidity").isJsonNull() ? hourly.get("humidity").getAsString() : "";
                String windSpeed = hourly.has("windSpeed") && !hourly.get("windSpeed").isJsonNull() ? hourly.get("windSpeed").getAsString() : "";
                String windDir = hourly.has("windDir") && !hourly.get("windDir").isJsonNull() ? hourly.get("windDir").getAsString() : "";

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
                if (!humidity.isEmpty()) {
                    weatherInfo.append("湿度: " + humidity + "%\n");
                }
                if (!windSpeed.isEmpty()) {
                    weatherInfo.append("风速: " + windSpeed + " km/h\n");
                }
                if (!windDir.isEmpty() && !windSpeed.isEmpty()) {
                    weatherInfo.append("风向: " + windDir + "\n");
                }
                weatherInfo.append("\n");
            }

            return "24小时预报:\n" + weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng hourly response", e);
            return "24小时预报:\n查询失败（" + e.getClass().getSimpleName() + ": " + e.getMessage() + "）";
        }
    }

    // 获取和风天气空气质量（通过城市名）
    public CompletableFuture<String> getHefengAirQuality(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                double[] latLon = getHefengLatLon(city);
                if (latLon == null || latLon.length < 2) {
                    return "空气质量:\n查询失败（无法获取城市位置信息）";
                }
                double lat = latLon[0];
                double lon = latLon[1];

                String urlString = getAirUrl(lat, lon);
                Log.d(TAG, "Air quality URL (by city): " + urlString);
                String response = httpGet(urlString);
                return parseHefengAirQualityResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng air quality", e);
                return "空气质量:\n查询失败: " + e.getMessage();
            }
        });
    }

    // 解析和风天气空气质量响应（v1 API 格式）
    private String parseHefengAirQualityResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "空气质量: 查询失败（响应为空）";
        }

        Log.d(TAG, "Air quality response: " + response.substring(0, Math.min(300, response.length())));

        try {
            JsonElement jsonElement = gson.fromJson(response, JsonElement.class);

            if (jsonElement == null || !jsonElement.isJsonObject()) {
                return "空气质量: 查询失败（响应格式错误）";
            }

            JsonObject jsonObject = jsonElement.getAsJsonObject();

            // 检查 v1 API 错误格式
            if (jsonObject.has("error") && !jsonObject.get("error").isJsonNull()) {
                JsonElement errorElement = jsonObject.get("error");
                if (errorElement.isJsonObject()) {
                    JsonObject error = errorElement.getAsJsonObject();
                    int status = error.has("status") ? error.get("status").getAsInt() : 0;
                    String title = error.has("title") ? error.get("title").getAsString() : "";
                    String detail = error.has("detail") ? error.get("detail").getAsString() : "";
                    if (status == 403) {
                        return "空气质量: 暂无权限（" + title + "）\n" + detail;
                    }
                    return "空气质量: 查询失败（" + title + "）";
                }
            }

            // 检查 v7 API 错误格式（兼容旧响应）
            if (jsonObject.has("code") && !jsonObject.get("code").isJsonNull()) {
                String code = jsonObject.get("code").getAsString();
                if (!"200".equals(code) && !code.isEmpty()) {
                    if ("403".equals(code)) {
                        return "空气质量: 请在和风天气控制台开通权限";
                    }
                    String msg = jsonObject.has("message") && !jsonObject.get("message").isJsonNull()
                        ? jsonObject.get("message").getAsString() : "未知错误";
                    return "空气质量: 查询失败（" + msg + "）";
                }
            }

            StringBuilder weatherInfo = new StringBuilder();
            weatherInfo.append("空气质量:\n");

            // v1 API: 解析 indexes 数组，取第一个作为主 AQI
            if (jsonObject.has("indexes") && !jsonObject.get("indexes").isJsonNull()) {
                JsonArray indexes = jsonObject.getAsJsonArray("indexes");
                if (indexes != null && indexes.size() > 0) {
                    JsonObject index = indexes.get(0).getAsJsonObject();
                    String aqiDisplay = getJsonStr(index, "aqiDisplay");
                    String level = getJsonStr(index, "level");
                    String category = getJsonStr(index, "category");

                    weatherInfo.append("AQI: ").append(aqiDisplay.isEmpty() ? "--" : aqiDisplay);
                    if (!level.isEmpty() && !category.isEmpty()) {
                        weatherInfo.append(" (等级").append(level).append(", ").append(category).append(")");
                    }
                    weatherInfo.append("\n");

                    // 指数名称和代码
                    String idxName = getJsonStr(index, "name");
                    String idxCode = getJsonStr(index, "code");
                    if (!idxName.isEmpty()) weatherInfo.append("指数名称: ").append(idxName).append("\n");
                    if (!idxCode.isEmpty()) weatherInfo.append("指数代码: ").append(idxCode).append("\n");

                    // 首要污染物
                    if (index.has("primaryPollutant") && !index.get("primaryPollutant").isJsonNull() && index.get("primaryPollutant").isJsonObject()) {
                        JsonObject pp = index.getAsJsonObject("primaryPollutant");
                        String ppName = getJsonStr(pp, "name");
                        if (!ppName.isEmpty()) {
                            weatherInfo.append("首要污染物: ").append(ppName).append("\n");
                        }
                    }

                    // 健康建议
                    if (index.has("health") && !index.get("health").isJsonNull() && index.get("health").isJsonObject()) {
                        JsonObject health = index.getAsJsonObject("health");
                        String effect = getJsonStr(health, "effect");
                        if (!effect.isEmpty()) {
                            weatherInfo.append("健康影响: ").append(effect).append("\n");
                        }
                        if (health.has("advice") && !health.get("advice").isJsonNull() && health.get("advice").isJsonObject()) {
                            JsonObject advice = health.getAsJsonObject("advice");
                            String general = getJsonStr(advice, "generalPopulation");
                            String sensitive = getJsonStr(advice, "sensitivePopulation");
                            if (!general.isEmpty()) weatherInfo.append("一般人群: ").append(general).append("\n");
                            if (!sensitive.isEmpty()) weatherInfo.append("敏感人群: ").append(sensitive).append("\n");
                        }
                        String tip = getJsonStr(health, "tip");
                        if (!tip.isEmpty()) weatherInfo.append("健康提示: ").append(tip).append("\n");
                    }
                }
            }

            // v7 API 兼容: 解析 now 对象
            if (jsonObject.has("now") && !jsonObject.get("now").isJsonNull() && jsonObject.get("now").isJsonObject()) {
                JsonObject now = jsonObject.getAsJsonObject("now");
                String aqi = getJsonStr(now, "aqi");
                String level = getJsonStr(now, "level");
                String category = getJsonStr(now, "category");
                String primary = getJsonStr(now, "primary");

                if (!aqi.isEmpty()) {
                    weatherInfo.append("AQI: ").append(aqi);
                    if (!level.isEmpty() && !category.isEmpty()) {
                        weatherInfo.append(" (等级").append(level).append(", ").append(category).append(")");
                    }
                    weatherInfo.append("\n");
                }
                if (!primary.isEmpty()) {
                    weatherInfo.append("首要污染物: ").append(primary).append("\n");
                }

                // v7 污染物
                String[] pollutantKeys = {"pm10", "pm2p5", "no2", "so2", "co", "o3"};
                String[] pollutantNames = {"PM10", "PM2.5", "NO2", "SO2", "CO", "O3"};
                for (int i = 0; i < pollutantKeys.length; i++) {
                    if (now.has(pollutantKeys[i]) && !now.get(pollutantKeys[i]).isJsonNull()) {
                        String value = now.get(pollutantKeys[i]).getAsString();
                        if (!value.isEmpty()) {
                            if (!weatherInfo.toString().contains("污染物浓度")) weatherInfo.append("\n污染物浓度:\n");
                            String unit = "co".equals(pollutantKeys[i]) ? "mg/m³" : "μg/m³";
                            weatherInfo.append(pollutantNames[i]).append(": ").append(value).append(" ").append(unit).append("\n");
                        }
                    }
                }
            }

            // v1 API: 解析 pollutants 数组
            if (jsonObject.has("pollutants") && !jsonObject.get("pollutants").isJsonNull()) {
                JsonArray pollutants = jsonObject.getAsJsonArray("pollutants");
                if (pollutants != null && pollutants.size() > 0) {
                    weatherInfo.append("\n污染物浓度:\n");
                    for (int i = 0; i < pollutants.size(); i++) {
                        if (!pollutants.get(i).isJsonObject()) continue;
                        JsonObject pollutant = pollutants.get(i).getAsJsonObject();
                        String code = getJsonStr(pollutant, "code");
                        String name = getJsonStr(pollutant, "name");
                        String displayName = name.replace(" ", "").toUpperCase();
                        if (displayName.isEmpty()) displayName = code.toUpperCase();

                        if (pollutant.has("concentration") && !pollutant.get("concentration").isJsonNull() && pollutant.get("concentration").isJsonObject()) {
                            JsonObject conc = pollutant.getAsJsonObject("concentration");
                            String value = getJsonStr(conc, "value");
                            String unit = getJsonStr(conc, "unit");
                            if (!value.isEmpty()) {
                                weatherInfo.append(displayName).append(": ").append(value);
                                if (!unit.isEmpty()) weatherInfo.append(" ").append(unit);
                                // 全称
                                String fullName = getJsonStr(pollutant, "fullName");
                                if (!fullName.isEmpty() && !fullName.equals(displayName)) {
                                    weatherInfo.append(" (").append(fullName).append(")");
                                }
                                weatherInfo.append("\n");
                            }
                        }
                    }
                }
            }

            if (weatherInfo.toString().trim().equals("空气质量:")) {
                return "空气质量: 暂无数据";
            }

            return weatherInfo.toString().trim();

        } catch (Exception e) {
            Log.e(TAG, "Error parsing air quality response", e);
            return "空气质量: 查询失败（" + e.getMessage() + "）";
        }
    }

    private static String getJsonStr(JsonObject obj, String key) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            JsonElement elem = obj.get(key);
            if (elem.isJsonPrimitive()) {
                com.google.gson.JsonPrimitive prim = elem.getAsJsonPrimitive();
                if (prim.isString()) return prim.getAsString();
                if (prim.isNumber()) {
                    String raw = prim.toString();
                    // 去除可能的 .0 后缀整数
                    if (raw.endsWith(".0")) {
                        try {
                            long l = Long.parseLong(raw.substring(0, raw.length() - 2));
                            return String.valueOf(l);
                        } catch (NumberFormatException ignored) {}
                    }
                    return raw;
                }
                if (prim.isBoolean()) return String.valueOf(prim.getAsBoolean());
            }
        }
        return "";
    }

    // 获取和风天气预警信息
    public CompletableFuture<String> getHefengAlerts(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                double[] latLon = getHefengLatLon(city);
                if (latLon == null || latLon.length < 2) {
                    return "天气预警:\n查询失败（无法获取城市位置信息）";
                }
                double lat = latLon[0];
                double lon = latLon[1];

                String districtResponse = httpGet(getAlertUrl(lat, lon));
                Log.d(TAG, "Alerts URL (by city): " + getAlertUrl(lat, lon));
                
                if (!isEmptyAlertResponse(districtResponse)) {
                    return parseHefengAlertsResponse(districtResponse);
                }
                
                AdministrativeHierarchy hierarchy = getAdministrativeHierarchy(lat, lon);
                
                if (hierarchy.cityLatLon != null && !"".equals(hierarchy.city) && !hierarchy.city.equals(city)) {
                    String cityResponse = httpGet(getAlertUrl(hierarchy.cityLatLon[0], hierarchy.cityLatLon[1]));
                    Log.d(TAG, "Alerts URL (city fallback): " + getAlertUrl(hierarchy.cityLatLon[0], hierarchy.cityLatLon[1]));
                    
                    if (!isEmptyAlertResponse(cityResponse)) {
                        return parseHefengAlertsResponse(cityResponse);
                    }
                }
                
                if (hierarchy.provinceLatLon != null && !"".equals(hierarchy.province) && !hierarchy.province.equals(city)) {
                    String provinceResponse = httpGet(getAlertUrl(hierarchy.provinceLatLon[0], hierarchy.provinceLatLon[1]));
                    Log.d(TAG, "Alerts URL (province fallback): " + getAlertUrl(hierarchy.provinceLatLon[0], hierarchy.provinceLatLon[1]));
                    
                    if (!isEmptyAlertResponse(provinceResponse)) {
                        return parseHefengAlertsResponse(provinceResponse);
                    }
                }
                
                return parseHefengAlertsResponse(districtResponse);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng alerts", e);
                return "天气预警:\n查询失败: " + e.getMessage();
            }
        });
    }

    // 获取备用天气预警信息 (APISpace - 数据来自国家预警中心)
    public CompletableFuture<String> getBackupAlerts(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            if (APISPACE_TOKEN == null || APISPACE_TOKEN.isEmpty()) {
                Log.w(TAG, "APISpace token not configured, skipping backup alerts");
                return null;
            }
            
            try {
                String urlString = APISPACE_ALERTS_URL + "?lonlat=" + 
                    String.format(java.util.Locale.US, "%.6f", lon) + "," + 
                    String.format(java.util.Locale.US, "%.6f", lat);
                
                Log.d(TAG, "Backup alerts URL: " + urlString);
                
                OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();
                
                Request request = new Request.Builder()
                    .url(urlString)
                    .header("X-APISpace-Token", APISPACE_TOKEN)
                    .header("Authorization-Type", "apikey")
                    .build();
                
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseBody = response.body().string();
                        Log.d(TAG, "Backup alerts response: " + responseBody.substring(0, Math.min(200, responseBody.length())));
                        return responseBody;
                    } else {
                        Log.w(TAG, "Backup alerts request failed: " + response.code());
                        return null;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting backup alerts", e);
                return null;
            }
        });
    }

    // 解析备用预警API响应 (APISpace)
    public String parseBackupAlertsResponse(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }
        
        try {
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            if (!jsonObject.has("status") || jsonObject.get("status").getAsInt() != 0) {
                return null;
            }
            
            if (!jsonObject.has("result")) {
                return null;
            }
            
            JsonObject result = jsonObject.getAsJsonObject("result");
            
            if (!result.has("alerts") || result.get("alerts").isJsonNull()) {
                return "天气预警:\n暂无预警信息";
            }
            
            JsonArray alerts = result.getAsJsonArray("alerts");
            
            if (alerts == null || alerts.size() == 0) {
                return "天气预警:\n暂无预警信息";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("天气预警:\n");
            
            for (int i = 0; i < alerts.size(); i++) {
                JsonObject alert = alerts.get(i).getAsJsonObject();
                
                String title = alert.has("title") && !alert.get("title").isJsonNull() 
                    ? alert.get("title").getAsString() : "";
                String type = alert.has("type") && !alert.get("type").isJsonNull() 
                    ? alert.get("type").getAsString() : "";
                String level = alert.has("level") && !alert.get("level").isJsonNull() 
                    ? alert.get("level").getAsString() : "";
                String desc = alert.has("desc") && !alert.get("desc").isJsonNull() 
                    ? alert.get("desc").getAsString() : "";
                String publicTime = alert.has("public_time") && !alert.get("public_time").isJsonNull() 
                    ? alert.get("public_time").getAsString() : "";
                
                sb.append(type).append(" ").append(level).append("\n");
                if (!title.isEmpty()) sb.append(title).append("\n");
                if (!desc.isEmpty()) sb.append(desc).append("\n");
                if (!publicTime.isEmpty()) sb.append("发布时间: ").append(publicTime).append("\n");
                sb.append("\n");
            }
            
            return sb.toString().trim();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing backup alerts response", e);
            return null;
        }
    }

    private double[] getCoordinatesFromLocationId(String locationId) {
        try {
            String urlString = getGeocodeUrl() + "?location=" + locationId;
                String response = httpGet(urlString);
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

    // 解析和风天气预警响应 (v7 API: /v7/warning/now，兼容v1旧格式)
    private String parseHefengAlertsResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "天气预警:\n查询失败（响应为空）";
        }
        
        Log.d(TAG, "Alerts response: " + response.substring(0, Math.min(200, response.length())));

        try {
            JsonElement jsonElement = gson.fromJson(response, JsonElement.class);
            
            if (jsonElement == null || !jsonElement.isJsonObject()) {
                return "天气预警:\n查询失败（响应格式错误）";
            }
            
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            
            if (jsonObject.has("code") && !jsonObject.get("code").isJsonNull()) {
                String code = jsonObject.get("code").getAsString();
                if (!"200".equals(code) && !code.isEmpty()) {
                    String msg = jsonObject.has("message") && !jsonObject.get("message").isJsonNull() 
                        ? jsonObject.get("message").getAsString() : "未知错误";
                    return "天气预警:\n查询失败（" + msg + "）";
                }
            }

            if (jsonObject.has("metadata")) {
                JsonObject metadata = jsonObject.getAsJsonObject("metadata");
                if (metadata.has("zeroResult") && metadata.get("zeroResult").getAsBoolean()) {
                    return "天气预警:\n暂无预警信息";
                }
            }

            JsonArray alertArray = null;
            
            if (jsonObject.has("alerts") && !jsonObject.get("alerts").isJsonNull()) {
                JsonElement alertArrayElement = jsonObject.get("alerts");
                if (alertArrayElement.isJsonArray()) {
                    alertArray = alertArrayElement.getAsJsonArray();
                }
            }
            
            if (jsonObject.has("warning") && !jsonObject.get("warning").isJsonNull()) {
                JsonElement alertArrayElement = jsonObject.get("warning");
                if (alertArrayElement.isJsonArray()) {
                    alertArray = alertArrayElement.getAsJsonArray();
                }
            }

            if (alertArray == null || alertArray.size() == 0) {
                return "天气预警:\n暂无预警信息";
            }

            StringBuilder weatherInfo = new StringBuilder();

            for (int i = 0; i < alertArray.size(); i++) {
                JsonElement alertElement = alertArray.get(i);
                if (!alertElement.isJsonObject()) continue;
                JsonObject alert = alertElement.getAsJsonObject();
                
                String sender = "";
                if (alert.has("senderName") && !alert.get("senderName").isJsonNull()) {
                    sender = alert.get("senderName").getAsString();
                } else if (alert.has("sender") && !alert.get("sender").isJsonNull()) {
                    sender = alert.get("sender").getAsString();
                }

                String typeName = "";
                if (alert.has("eventType") && !alert.get("eventType").isJsonNull()) {
                    JsonObject eventType = alert.get("eventType").getAsJsonObject();
                    if (eventType.has("name") && !eventType.get("name").isJsonNull()) {
                        typeName = eventType.get("name").getAsString();
                    }
                } else if (alert.has("typeName") && !alert.get("typeName").isJsonNull()) {
                    typeName = alert.get("typeName").getAsString();
                }

                String level = "";
                if (alert.has("severity") && !alert.get("severity").isJsonNull()) {
                    level = convertSeverity(alert.get("severity").getAsString());
                } else if (alert.has("level") && !alert.get("level").isJsonNull()) {
                    level = alert.get("level").getAsString();
                }

                String title = "";
                if (alert.has("headline") && !alert.get("headline").isJsonNull()) {
                    title = alert.get("headline").getAsString();
                } else if (alert.has("title") && !alert.get("title").isJsonNull()) {
                    title = alert.get("title").getAsString();
                }

                String text = "";
                if (alert.has("description") && !alert.get("description").isJsonNull()) {
                    text = alert.get("description").getAsString();
                } else if (alert.has("text") && !alert.get("text").isJsonNull()) {
                    text = alert.get("text").getAsString();
                }

                String pubTime = "";
                if (alert.has("issuedTime") && !alert.get("issuedTime").isJsonNull()) {
                    pubTime = alert.get("issuedTime").getAsString();
                } else if (alert.has("pubTime") && !alert.get("pubTime").isJsonNull()) {
                    pubTime = alert.get("pubTime").getAsString();
                }

                String startTime = "";
                if (alert.has("effectiveTime") && !alert.get("effectiveTime").isJsonNull()) {
                    startTime = alert.get("effectiveTime").getAsString();
                } else if (alert.has("startTime") && !alert.get("startTime").isJsonNull()) {
                    startTime = alert.get("startTime").getAsString();
                }

                String endTime = "";
                if (alert.has("expireTime") && !alert.get("expireTime").isJsonNull()) {
                    endTime = alert.get("expireTime").getAsString();
                } else if (alert.has("endTime") && !alert.get("endTime").isJsonNull()) {
                    endTime = alert.get("endTime").getAsString();
                }

                String instruction = "";
                if (alert.has("instruction") && !alert.get("instruction").isJsonNull()) {
                    instruction = alert.get("instruction").getAsString();
                }

                String color = "";
                if (alert.has("color") && !alert.get("color").isJsonNull()) {
                    JsonObject colorObj = alert.get("color").getAsJsonObject();
                    if (colorObj.has("code") && !colorObj.get("code").isJsonNull()) {
                        color = convertColorCode(colorObj.get("code").getAsString());
                    }
                }

                if (!typeName.isEmpty()) {
                    if (!level.isEmpty()) {
                        weatherInfo.append("【" + level + "】" + typeName);
                    } else {
                        weatherInfo.append("【" + typeName + "】");
                    }
                    if (!color.isEmpty()) {
                        weatherInfo.append("(" + color + ")");
                    }
                    weatherInfo.append("\n");
                }
                if (!title.isEmpty()) {
                    weatherInfo.append("标题: " + title + "\n");
                }
                if (!sender.isEmpty()) {
                    weatherInfo.append("发布机构: " + sender + "\n");
                }
                if (!level.isEmpty()) {
                    weatherInfo.append("预警等级: " + level + "\n");
                }

                // 额外字段
                String urgency = getJsonStr(alert, "urgency");
                if (!urgency.isEmpty()) weatherInfo.append("紧急程度: ").append(urgency).append("\n");
                String certainty = getJsonStr(alert, "certainty");
                if (!certainty.isEmpty()) weatherInfo.append("确定性: ").append(certainty).append("\n");
                String onsetTime = getJsonStr(alert, "onsetTime");
                if (!onsetTime.isEmpty()) weatherInfo.append("起始时间: ").append(formatTime(onsetTime)).append("\n");
                String alertId = getJsonStr(alert, "id");
                if (!alertId.isEmpty()) weatherInfo.append("预警ID: ").append(alertId).append("\n");
                String icon = getJsonStr(alert, "icon");
                if (!icon.isEmpty()) weatherInfo.append("预警图标: ").append(icon).append("\n");

                if (!pubTime.isEmpty()) {
                    String displayTime = formatTime(pubTime);
                    weatherInfo.append("发布时间: " + displayTime + "\n");
                }
                if (!startTime.isEmpty() || !endTime.isEmpty()) {
                    String startDisplay = formatTime(startTime);
                    String endDisplay = formatTime(endTime);
                    weatherInfo.append("生效时间: " + startDisplay + " ~ " + endDisplay + "\n");
                }
                if (!text.isEmpty()) {
                    weatherInfo.append("内容: " + text + "\n");
                }
                if (!instruction.isEmpty()) {
                    weatherInfo.append("防御指南:\n" + instruction + "\n");
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
    
    private String convertSeverity(String severity) {
        switch (severity.toLowerCase()) {
            case "extreme": return "特强";
            case "severe": return "强烈";
            case "moderate": return "中等";
            case "minor": return "轻微";
            default: return severity;
        }
    }
    
    private String convertColorCode(String color) {
        switch (color.toLowerCase()) {
            case "red": return "红色";
            case "orange": return "橙色";
            case "yellow": return "黄色";
            case "blue": return "蓝色";
            case "green": return "绿色";
            default: return color;
        }
    }
    
    private String formatTime(String time) {
        if (time == null || time.isEmpty()) {
            return "";
        }
        if (time.length() > 16) {
            return time.substring(5, 16);
        }
        return time;
    }

    // 解析和风天气分钟级降水响应 (v7 API: /v7/minutely/5m)
    private String parseHefengMinutelyResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "分钟级降水:\n查询失败（响应为空）";
        }
        
        try {
            JsonElement jsonElement = gson.fromJson(response, JsonElement.class);
            
            if (jsonElement == null || !jsonElement.isJsonObject()) {
                return "分钟级降水:\n查询失败（响应格式错误）";
            }
            
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            
            String code = "";
            if (jsonObject.has("code") && !jsonObject.get("code").isJsonNull()) {
                code = jsonObject.get("code").getAsString();
            }
            
            if (!"200".equals(code)) {
                String msg = jsonObject.has("message") && !jsonObject.get("message").isJsonNull() 
                    ? jsonObject.get("message").getAsString() : "未知错误";
                return "分钟级降水:\n查询失败（" + msg + "）";
            }

            String summary = "";
            if (jsonObject.has("summary") && !jsonObject.get("summary").isJsonNull()) {
                summary = jsonObject.get("summary").getAsString();
            }

            if (!jsonObject.has("minutely") || jsonObject.get("minutely").isJsonNull()) {
                return "分钟级降水:\n" + (summary.isEmpty() ? "暂无数据" : summary);
            }

            JsonElement minutelyArrayElement = jsonObject.get("minutely");
            if (!minutelyArrayElement.isJsonArray()) {
                return "分钟级降水:\n" + (summary.isEmpty() ? "暂无数据" : summary);
            }
            JsonArray minutelyArray = minutelyArrayElement.getAsJsonArray();

            if (minutelyArray == null || minutelyArray.size() == 0) {
                return "分钟级降水:\n" + (summary.isEmpty() ? "暂无数据" : summary);
            }

            StringBuilder weatherInfo = new StringBuilder();
            weatherInfo.append("分钟级降水:\n");
            
            if (!summary.isEmpty()) {
                weatherInfo.append("摘要: ").append(summary).append("\n\n");
            }

            weatherInfo.append("未来2小时每5分钟降水预测:\n");
            
            for (int i = 0; i < minutelyArray.size(); i++) {
                JsonElement minutelyElement = minutelyArray.get(i);
                if (!minutelyElement.isJsonObject()) continue;
                JsonObject minutely = minutelyElement.getAsJsonObject();
                
                String fxTime = minutely.has("fxTime") && !minutely.get("fxTime").isJsonNull() 
                    ? minutely.get("fxTime").getAsString() : "";
                String precip = minutely.has("precip") && !minutely.get("precip").isJsonNull() 
                    ? minutely.get("precip").getAsString() : "";
                String type = minutely.has("type") && !minutely.get("type").isJsonNull() 
                    ? minutely.get("type").getAsString() : "";

                if (!fxTime.isEmpty()) {
                    String displayTime = fxTime.length() > 16 ? fxTime.substring(11, 16) : fxTime;
                    String typeText = "rain".equals(type) ? "雨" : ("snow".equals(type) ? "雪" : "");
                    weatherInfo.append(displayTime).append(": ").append(precip).append("mm").append(typeText).append("\n");
                }
            }

            return weatherInfo.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing Hefeng minutely response", e);
            return "分钟级降水:\n查询失败";
        }
    }

    // 获取和风天气生活指数（通过城市名）
    public CompletableFuture<String> getHefengIndices(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                double[] latLon = getHefengLatLon(city);
                if (latLon == null || latLon.length < 2) {
                    return "生活指数:\n查询失败（无法获取城市位置信息）";
                }
                double lat = latLon[0];
                double lon = latLon[1];

                String urlString = getIndicesUrl(lat, lon);
                Log.d(TAG, "Indices URL (by city): " + urlString);
                String response = httpGet(urlString);
                return parseHefengIndicesResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng indices", e);
                return "生活指数:\n查询失败: " + e.getMessage();
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
            
            if (jsonObject.has("fxLink") && !jsonObject.get("fxLink").isJsonNull()) {
                String fxLink = jsonObject.get("fxLink").getAsString();
                weatherInfo.append("链接: ").append(fxLink).append("\n");
            }

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

    private String fetchAllIndices(String location) {
        try {
            // 解析 location 字符串为经纬度
            String[] parts = location.split(",");
            if (parts.length >= 2) {
                double lon = Double.parseDouble(parts[0].trim());
                double lat = Double.parseDouble(parts[1].trim());
                String urlString = getIndicesUrl(lat, lon);
                Log.d(TAG, "Indices URL: " + urlString);
                String response = httpGet(urlString);
                String parsed = parseHefengIndicesResponse(response);
                if (parsed != null && !parsed.isEmpty()) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error fetching indices", e);
        }
        return "生活指数:\n暂无数据";
    }

    public CompletableFuture<String> getHefengForecastByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
                String urlString = getForecastUrl() + "?location=" + location;
                String response = httpGet(urlString);
                return parseHefengForecastResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng forecast by location", e);
                return "天气预报:\n查询失败: " + e.getMessage();
            }
        });
    }

    public CompletableFuture<String> getHefengHourlyByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
                String urlString = getHourlyUrl() + "?location=" + location;
                String response = httpGet(urlString);
                return parseHefengHourlyResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng hourly by location", e);
                return "24小时预报:\n查询失败: " + e.getMessage();
            }
        });
    }

    public CompletableFuture<String> getHefengAirQualityByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String urlString = getAirUrl(lat, lon);
                Log.d(TAG, "Air quality URL: " + urlString);
                String response = httpGet(urlString);
                return parseHefengAirQualityResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng air quality by location", e);
                return "空气质量:\n查询失败: " + e.getMessage();
            }
        });
    }

    public CompletableFuture<String> getHefengAirForecastByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String urlString = getAirDailyUrl(lat, lon);
                Log.d(TAG, "Air forecast URL: " + urlString);
                String response = httpGet(urlString);
                return parseHefengAirForecastResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng air forecast", e);
                return "空气质量预报:\n查询失败: " + e.getMessage();
            }
        });
    }

    private String parseHefengAirForecastResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "空气质量预报: 查询失败（响应为空）";
        }

        Log.d(TAG, "Air forecast response: " + response.substring(0, Math.min(300, response.length())));

        try {
            JsonElement jsonElement = gson.fromJson(response, JsonElement.class);
            if (jsonElement == null || !jsonElement.isJsonObject()) {
                return "空气质量预报: 查询失败（响应格式错误）";
            }

            JsonObject jsonObject = jsonElement.getAsJsonObject();

            // 检查错误
            if (jsonObject.has("error") && !jsonObject.get("error").isJsonNull()) {
                JsonElement errorElement = jsonObject.get("error");
                if (errorElement.isJsonObject()) {
                    JsonObject error = errorElement.getAsJsonObject();
                    String title = error.has("title") ? error.get("title").getAsString() : "";
                    return "空气质量预报: 查询失败（" + title + "）";
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("空气质量预报:\n");

            // v1 daily API: 解析 days 数组
            // 文档: https://dev.qweather.com/en/docs/api/air-quality/air-daily-forecast/
            if (jsonObject.has("days") && !jsonObject.get("days").isJsonNull()) {
                JsonArray days = jsonObject.getAsJsonArray("days");
                for (int i = 0; i < days.size(); i++) {
                    if (!days.get(i).isJsonObject()) continue;
                    JsonObject day = days.get(i).getAsJsonObject();

                    // forecastStartTime: ISO8601 格式，提取日期部分
                    String startTime = getJsonStr(day, "forecastStartTime");
                    String date = startTime;
                    int tIdx = startTime.indexOf('T');
                    if (tIdx > 0) date = startTime.substring(0, tIdx);

                    // indexes 数组，取第一个作为主 AQI
                    if (day.has("indexes") && !day.get("indexes").isJsonNull() && day.get("indexes").isJsonArray()) {
                        JsonArray indexes = day.getAsJsonArray("indexes");
                        if (indexes.size() > 0) {
                            JsonObject index = indexes.get(0).getAsJsonObject();
                            String aqiDisplay = getJsonStr(index, "aqiDisplay");
                            String level = getJsonStr(index, "level");
                            String category = getJsonStr(index, "category");
                            String idxName = getJsonStr(index, "name");

                            if (!date.isEmpty()) {
                                sb.append(date);
                                if (!aqiDisplay.isEmpty()) sb.append(" AQI: ").append(aqiDisplay);
                                if (!category.isEmpty()) sb.append(" (").append(category).append(")");
                                sb.append("\n");
                            }

                            // 首要污染物
                            if (index.has("primaryPollutant") && !index.get("primaryPollutant").isJsonNull() && index.get("primaryPollutant").isJsonObject()) {
                                JsonObject pp = index.getAsJsonObject("primaryPollutant");
                                String ppName = getJsonStr(pp, "name");
                                if (!ppName.isEmpty()) {
                                    sb.append("  首要污染物: ").append(ppName).append("\n");
                                }
                            }

                            // 健康建议
                            if (index.has("health") && !index.get("health").isJsonNull() && index.get("health").isJsonObject()) {
                                JsonObject health = index.getAsJsonObject("health");
                                String effect = getJsonStr(health, "effect");
                                if (!effect.isEmpty()) {
                                    sb.append("  健康影响: ").append(effect).append("\n");
                                }
                                if (health.has("advice") && !health.get("advice").isJsonNull() && health.get("advice").isJsonObject()) {
                                    JsonObject advice = health.getAsJsonObject("advice");
                                    String general = getJsonStr(advice, "generalPopulation");
                                    String sensitive = getJsonStr(advice, "sensitivePopulation");
                                    if (!general.isEmpty()) sb.append("  一般人群: ").append(general).append("\n");
                                    if (!sensitive.isEmpty()) sb.append("  敏感人群: ").append(sensitive).append("\n");
                                }
                            }
                        }
                    }
                }
            }

            if (sb.toString().trim().equals("空气质量预报:")) {
                sb.append("暂无预报数据");
            }

            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error parsing air forecast response", e);
            return "空气质量预报: 查询失败（解析错误）";
        }
    }

    public CompletableFuture<String> getHefengAlertsByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String districtResponse = httpGet(getAlertUrl(lat, lon));
                Log.d(TAG, "Alerts URL (district): " + getAlertUrl(lat, lon));
                
                if (!isEmptyAlertResponse(districtResponse)) {
                    return parseHefengAlertsResponse(districtResponse);
                }
                
                AdministrativeHierarchy hierarchy = getAdministrativeHierarchy(lat, lon);
                
                if (hierarchy.cityLatLon != null && !"".equals(hierarchy.city)) {
                    String cityResponse = httpGet(getAlertUrl(hierarchy.cityLatLon[0], hierarchy.cityLatLon[1]));
                    Log.d(TAG, "Alerts URL (city): " + getAlertUrl(hierarchy.cityLatLon[0], hierarchy.cityLatLon[1]));
                    
                    if (!isEmptyAlertResponse(cityResponse)) {
                        return parseHefengAlertsResponse(cityResponse);
                    }
                }
                
                if (hierarchy.provinceLatLon != null && !"".equals(hierarchy.province)) {
                    String provinceResponse = httpGet(getAlertUrl(hierarchy.provinceLatLon[0], hierarchy.provinceLatLon[1]));
                    Log.d(TAG, "Alerts URL (province): " + getAlertUrl(hierarchy.provinceLatLon[0], hierarchy.provinceLatLon[1]));
                    
                    if (!isEmptyAlertResponse(provinceResponse)) {
                        return parseHefengAlertsResponse(provinceResponse);
                    }
                }
                
                return parseHefengAlertsResponse(districtResponse);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng alerts by location", e);
                return "天气预警:\n查询失败: " + e.getMessage();
            }
        });
    }

    private boolean isEmptyAlertResponse(String response) {
        if (response == null || response.isEmpty()) {
            return true;
        }
        
        try {
            JsonElement jsonElement = gson.fromJson(response, JsonElement.class);
            if (jsonElement == null || !jsonElement.isJsonObject()) {
                return true;
            }
            
            JsonObject jsonObject = jsonElement.getAsJsonObject();
            
            if (jsonObject.has("metadata")) {
                JsonObject metadata = jsonObject.getAsJsonObject("metadata");
                if (metadata.has("zeroResult") && metadata.get("zeroResult").getAsBoolean()) {
                    return true;
                }
            }
            
            if (jsonObject.has("alerts")) {
                JsonElement alertsElement = jsonObject.get("alerts");
                if (alertsElement.isJsonArray()) {
                    JsonArray alertsArray = alertsElement.getAsJsonArray();
                    if (alertsArray.size() == 0) {
                        return true;
                    }
                }
            }
            
            if (jsonObject.has("warning")) {
                JsonElement warningElement = jsonObject.get("warning");
                if (warningElement.isJsonArray()) {
                    JsonArray warningArray = warningElement.getAsJsonArray();
                    if (warningArray.size() == 0) {
                        return true;
                    }
                }
            }
            
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Error checking empty alert response", e);
            return true;
        }
    }

    public CompletableFuture<String> getHefengIndicesByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String urlString = getIndicesUrl(lat, lon);
                Log.d(TAG, "Indices URL: " + urlString);
                String response = httpGet(urlString);
                return parseHefengIndicesResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng indices by location", e);
                return "生活指数:\n查询失败: " + e.getMessage();
            }
        });
    }

    // 获取和风天气分钟级降水
    public CompletableFuture<String> getHefengMinutelyByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String urlString = getMinutelyUrl(lat, lon);
                Log.d(TAG, "Minutely URL: " + urlString);
                String response = httpGet(urlString);
                return parseHefengMinutelyResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng minutely precipitation", e);
                return "分钟级降水:\n暂无数据";
            }
        });
    }

    public CompletableFuture<String> getHefengMinutely(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                double[] latLon = getHefengLatLon(city);
                if (latLon == null || latLon.length < 2) {
                    return "分钟级降水:\n暂无数据";
                }
                double lat = latLon[0];
                double lon = latLon[1];
                return getHefengMinutelyByLocation(lat, lon).get();
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng minutely", e);
                return "分钟级降水:\n暂无数据";
            }
        });
    }

    public CompletableFuture<String> getHefengSunByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String urlString = getSunUrl(lat, lon);
                Log.d(TAG, "Sun URL: " + urlString);
                String response = httpGet(urlString);
                return parseHefengSunResponse(response);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng sunrise/sunset", e);
                return "日出日落:\n获取失败";
            }
        });
    }

    public CompletableFuture<String> getHefengSun(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
                String date = sdf.format(new java.util.Date());
                String urlString = apiHost + "/v7/astronomy/sun?location=" + city + "&date=" + date;
                Log.d(TAG, "Sun URL by city: " + urlString);
                return httpGet(urlString);
            } catch (Exception e) {
                Log.e(TAG, "Error getting Hefeng sunrise/sunset by city", e);
                return "{\"code\":\"500\",\"message\":\"获取日出日落失败: " + e.getMessage() + "\"}";
            }
        });
    }

    private String parseHefengSunResponse(String response) {
        try {
            JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            
            String code = jsonObject.has("code") ? jsonObject.get("code").getAsString() : "";
            if (!"200".equals(code)) {
                String msg = jsonObject.has("message") ? jsonObject.get("message").getAsString() : "未知错误";
                return "日出日落查询失败: " + msg;
            }

            String sunrise = "";
            String sunset = "";
            
            if (jsonObject.has("sun") && !jsonObject.get("sun").isJsonNull()) {
                JsonObject sunObj = jsonObject.getAsJsonObject("sun");
                sunrise = sunObj.has("sunrise") ? sunObj.get("sunrise").getAsString() : "";
                sunset = sunObj.has("sunset") ? sunObj.get("sunset").getAsString() : "";
            }

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
    
    private void normalizeParameters(Map<String, Object> parameters) {
        if (parameters == null) return;
        
        // 经纬度别名
        if (parameters.containsKey("latitude") && !parameters.containsKey("lat")) {
            parameters.put("lat", parameters.get("latitude"));
        }
        if (parameters.containsKey("longitude") && !parameters.containsKey("lon")) {
            parameters.put("lon", parameters.get("longitude"));
        }
        if (parameters.containsKey("lng") && !parameters.containsKey("lon")) {
            parameters.put("lon", parameters.get("lng"));
        }
        
        // 城市名别名
        if (parameters.containsKey("city_name") && !parameters.containsKey("city")) {
            parameters.put("city", parameters.get("city_name"));
        }
        if (parameters.containsKey("location") && !parameters.containsKey("city")) {
            Object location = parameters.get("location");
            if (location instanceof String) {
                parameters.put("city", location);
            }
        }
        
        // action别名
        if (parameters.containsKey("type") && !parameters.containsKey("action")) {
            parameters.put("action", parameters.get("type"));
        }
        if (parameters.containsKey("query_type") && !parameters.containsKey("action")) {
            parameters.put("action", parameters.get("query_type"));
        }
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            normalizeParameters(parameters);
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
                } else if (latObj instanceof String) {
                    try { lat = Double.parseDouble((String) latObj); }
                    catch (NumberFormatException e) { Log.w(TAG, "无法解析lat: " + latObj); }
                }
            }
            if (lonObj != null) {
                if (lonObj instanceof Double) {
                    lon = (Double) lonObj;
                } else if (lonObj instanceof Number) {
                    lon = ((Number) lonObj).doubleValue();
                } else if (lonObj instanceof String) {
                    try { lon = Double.parseDouble((String) lonObj); }
                    catch (NumberFormatException e) { Log.w(TAG, "无法解析lon: " + lonObj); }
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
            String result = null;

            // 优先使用SDK（自动处理JWT/压缩/JSON解析）
            if (sdkManager != null && sdkManager.isInitialized()) {
                try {
                    String location = buildSdkLocation(city, lat, lon, useLocation);
                    if (location != null) {
                        result = sdkManager.getCurrentWeather(location, city).get(15, TimeUnit.SECONDS);
                        if (!isSdkResultValid(result)) {
                            Log.w(TAG, "SDK current weather failed, falling back to HTTP: " + result);
                            result = null;
                        }
                    }
                } catch (Exception sdkEx) {
                    Log.w(TAG, "SDK current weather exception, falling back to HTTP: " + sdkEx.getMessage());
                    result = null;
                }
            }

            // HTTP回退
            if (result == null) {
                if (useLocation) {
                    result = getCurrentWeatherByLocation(lat, lon).get();
                } else if (city != null && !city.isEmpty()) {
                    result = getCurrentWeather(city).get();
                } else {
                    return new AIToolResult("请提供城市名称或经纬度", new HashMap<>());
                }
            }

            Map<String, Object> resultMap = parseWeatherResultToMap(result);
            resultMap.put("type", "current");
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting current weather: " + e.getMessage(), e);
            return new AIToolResult("获取当前天气失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult getForecastAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            String result = null;

            if (sdkManager != null && sdkManager.isInitialized()) {
                try {
                    String location = buildSdkLocation(city, lat, lon, useLocation);
                    if (location != null) {
                        result = sdkManager.getDailyForecast(location).get(15, TimeUnit.SECONDS);
                        if (!isSdkResultValid(result)) {
                            Log.w(TAG, "SDK forecast failed, falling back to HTTP: " + result);
                            result = null;
                        }
                    }
                } catch (Exception sdkEx) {
                    Log.w(TAG, "SDK forecast exception, falling back to HTTP: " + sdkEx.getMessage());
                    result = null;
                }
            }

            if (result == null) {
                if (useLocation) {
                    result = getHefengForecastByLocation(lat, lon).get();
                } else if (city != null && !city.isEmpty()) {
                    result = getHefengForecast(city).get();
                } else {
                    return new AIToolResult("请提供城市名称或经纬度", new HashMap<>());
                }
            }

            Map<String, Object> resultMap = parseWeatherResultToMap(result);
            resultMap.put("type", "forecast");
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting forecast: " + e.getMessage(), e);
            return new AIToolResult("获取天气预报失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult getHourlyAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            String result = null;

            if (sdkManager != null && sdkManager.isInitialized()) {
                try {
                    String location = buildSdkLocation(city, lat, lon, useLocation);
                    if (location != null) {
                        result = sdkManager.getHourlyForecast(location).get(15, TimeUnit.SECONDS);
                        if (!isSdkResultValid(result)) {
                            Log.w(TAG, "SDK hourly failed, falling back to HTTP: " + result);
                            result = null;
                        }
                    }
                } catch (Exception sdkEx) {
                    Log.w(TAG, "SDK hourly exception, falling back to HTTP: " + sdkEx.getMessage());
                    result = null;
                }
            }

            if (result == null) {
                if (useLocation) {
                    result = getHefengHourlyByLocation(lat, lon).get();
                } else if (city != null && !city.isEmpty()) {
                    result = getHefengHourly(city).get();
                } else {
                    return new AIToolResult("请提供城市名称或经纬度", new HashMap<>());
                }
            }

            Map<String, Object> resultMap = parseWeatherResultToMap(result);
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting hourly: " + e.getMessage(), e);
            return new AIToolResult("获取小时预报失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult getAirQualityAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            String result = null;

            if (sdkManager != null && sdkManager.isInitialized()) {
                try {
                    String location = buildSdkLocation(city, lat, lon, useLocation);
                    if (location != null) {
                        result = sdkManager.getAirQuality(location).get(15, TimeUnit.SECONDS);
                        if (!isSdkResultValid(result)) {
                            Log.w(TAG, "SDK air quality failed, falling back to HTTP: " + result);
                            result = null;
                        }
                    }
                } catch (Exception sdkEx) {
                    Log.w(TAG, "SDK air quality exception, falling back to HTTP: " + sdkEx.getMessage());
                    result = null;
                }
            }

            if (result == null) {
                if (useLocation) {
                    result = getHefengAirQualityByLocation(lat, lon).get();
                } else if (city != null && !city.isEmpty()) {
                    result = getHefengAirQuality(city).get();
                } else {
                    return new AIToolResult("请提供城市名称或经纬度", new HashMap<>());
                }
            }

            Map<String, Object> resultMap = parseWeatherResultToMap(result);
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting air quality: " + e.getMessage(), e);
            return new AIToolResult("获取空气质量失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult getAlertsAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            String result = null;

            if (sdkManager != null && sdkManager.isInitialized()) {
                try {
                    String location = buildSdkLocation(city, lat, lon, useLocation);
                    if (location != null) {
                        result = sdkManager.getWeatherAlerts(location).get(15, TimeUnit.SECONDS);
                        if (!isSdkResultValid(result)) {
                            Log.w(TAG, "SDK alerts failed, falling back to HTTP: " + result);
                            result = null;
                        }
                    }
                } catch (Exception sdkEx) {
                    Log.w(TAG, "SDK alerts exception, falling back to HTTP: " + sdkEx.getMessage());
                    result = null;
                }
            }

            if (result == null) {
                if (useLocation) {
                    result = getHefengAlertsByLocation(lat, lon).get();
                } else if (city != null && !city.isEmpty()) {
                    result = getHefengAlerts(city).get();
                } else {
                    return new AIToolResult("请提供城市名称或经纬度", new HashMap<>());
                }
            }

            Map<String, Object> resultMap = parseWeatherResultToMap(result);
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting alerts: " + e.getMessage(), e);
            return new AIToolResult("获取天气预警失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult getIndicesAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            String result = null;

            if (sdkManager != null && sdkManager.isInitialized()) {
                try {
                    String location = buildSdkLocation(city, lat, lon, useLocation);
                    if (location != null) {
                        result = sdkManager.getIndices(location).get(15, TimeUnit.SECONDS);
                        if (!isSdkResultValid(result)) {
                            Log.w(TAG, "SDK indices failed, falling back to HTTP: " + result);
                            result = null;
                        }
                    }
                } catch (Exception sdkEx) {
                    Log.w(TAG, "SDK indices exception, falling back to HTTP: " + sdkEx.getMessage());
                    result = null;
                }
            }

            if (result == null) {
                if (useLocation) {
                    result = getHefengIndicesByLocation(lat, lon).get();
                } else if (city != null && !city.isEmpty()) {
                    result = getHefengIndices(city).get();
                } else {
                    return new AIToolResult("请提供城市名称或经纬度", new HashMap<>());
                }
            }

            Map<String, Object> resultMap = parseWeatherResultToMap(result);
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting indices: " + e.getMessage(), e);
            return new AIToolResult("获取生活指数失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult getMinutelyAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            String result = null;

            if (sdkManager != null && sdkManager.isInitialized()) {
                try {
                    String location = buildSdkLocation(city, lat, lon, useLocation);
                    if (location != null) {
                        result = sdkManager.getMinutelyByLocation(location).get(15, TimeUnit.SECONDS);
                        if (!isSdkResultValid(result)) {
                            Log.w(TAG, "SDK minutely failed, falling back to HTTP: " + result);
                            result = null;
                        }
                    }
                } catch (Exception sdkEx) {
                    Log.w(TAG, "SDK minutely exception, falling back to HTTP: " + sdkEx.getMessage());
                    result = null;
                }
            }

            if (result == null) {
                if (useLocation) {
                    result = getHefengMinutelyByLocation(lat, lon).get();
                } else if (city != null && !city.isEmpty()) {
                    result = getHefengMinutely(city).get();
                } else {
                    return new AIToolResult("请提供城市名称或经纬度", new HashMap<>());
                }
            }

            Map<String, Object> resultMap = parseWeatherResultToMap(result);
            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting minutely: " + e.getMessage(), e);
            return new AIToolResult("获取分钟级降水失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult getAllWeatherAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("type", "all");

            String sdkLocation = (sdkManager != null && sdkManager.isInitialized())
                ? buildSdkLocation(city, lat, lon, useLocation) : null;

            CompletableFuture<String> currentFuture, forecastFuture, hourlyFuture, airFuture, alertsFuture, indicesFuture;

            if (sdkLocation != null) {
                // SDK路径：自动处理JWT/压缩/JSON解析
                currentFuture = sdkManager.getCurrentWeather(sdkLocation, city).exceptionally(e -> "查询失败: " + e.getMessage());
                forecastFuture = sdkManager.getDailyForecast(sdkLocation).exceptionally(e -> "查询失败: " + e.getMessage());
                hourlyFuture = sdkManager.getHourlyForecast(sdkLocation).exceptionally(e -> "查询失败: " + e.getMessage());
                airFuture = sdkManager.getAirQuality(sdkLocation).exceptionally(e -> "查询失败: " + e.getMessage());
                alertsFuture = sdkManager.getWeatherAlerts(sdkLocation).exceptionally(e -> "查询失败: " + e.getMessage());
                indicesFuture = sdkManager.getIndices(sdkLocation).exceptionally(e -> "查询失败: " + e.getMessage());
            } else {
                // HTTP回退路径
                currentFuture = useLocation
                    ? getCurrentWeatherByLocation(lat, lon).exceptionally(e -> "查询失败: " + e.getMessage())
                    : getCurrentWeather(city).exceptionally(e -> "查询失败: " + e.getMessage());
                forecastFuture = useLocation
                    ? getHefengForecastByLocation(lat, lon).exceptionally(e -> "查询失败: " + e.getMessage())
                    : getHefengForecast(city != null ? city : "北京").exceptionally(e -> "查询失败: " + e.getMessage());
                hourlyFuture = useLocation
                    ? getHefengHourlyByLocation(lat, lon).exceptionally(e -> "查询失败: " + e.getMessage())
                    : getHefengHourly(city != null ? city : "北京").exceptionally(e -> "查询失败: " + e.getMessage());
                airFuture = useLocation
                    ? getHefengAirQualityByLocation(lat, lon).exceptionally(e -> "查询失败: " + e.getMessage())
                    : getHefengAirQuality(city != null ? city : "北京").exceptionally(e -> "查询失败: " + e.getMessage());
                alertsFuture = useLocation
                    ? getHefengAlertsByLocation(lat, lon).exceptionally(e -> "查询失败: " + e.getMessage())
                    : getHefengAlerts(city != null ? city : "北京").exceptionally(e -> "查询失败: " + e.getMessage());
                indicesFuture = useLocation
                    ? getHefengIndicesByLocation(lat, lon).exceptionally(e -> "查询失败: " + e.getMessage())
                    : getHefengIndices(city != null ? city : "北京").exceptionally(e -> "查询失败: " + e.getMessage());
            }

            CompletableFuture.allOf(currentFuture, forecastFuture, hourlyFuture, airFuture, alertsFuture, indicesFuture).get(30, TimeUnit.SECONDS);

            mergeWeatherResult(resultMap, parseWeatherResultToMap(currentFuture.get()));
            mergeWeatherResult(resultMap, parseWeatherResultToMap(forecastFuture.get()));
            mergeWeatherResult(resultMap, parseWeatherResultToMap(hourlyFuture.get()));
            mergeWeatherResult(resultMap, parseWeatherResultToMap(airFuture.get()));
            mergeWeatherResult(resultMap, parseWeatherResultToMap(alertsFuture.get()));
            mergeWeatherResult(resultMap, parseWeatherResultToMap(indicesFuture.get()));

            return new AIToolResult(resultMap, new HashMap<>());
        } catch (Exception e) {
            Log.e(TAG, "Error getting all weather: " + e.getMessage(), e);
            return AIToolResult.fail("获取完整天气信息失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private void mergeWeatherResult(Map<String, Object> target, Map<String, Object> source) {
        if (source == null || source.isEmpty()) return;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!"status".equals(key) && !"type".equals(key) && !"error".equals(key) && !"message".equals(key)) {
                if (value != null && !value.toString().isEmpty()) {
                    target.put(key, value);
                }
            }
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
            
            Map<String, Object> resultMap = parseWeatherResultToMap(result);
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

        String lastFxLink = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            for (WeatherProvider provider : providers) {
                try {
                    String result = executeQuery(provider, queryType, city, lat, lon);
                    if (result != null && !result.startsWith("获取") && !result.startsWith("解析")) {
                        if (queryType.equals("current")) {
                            try {
                                JsonElement jsonElement = gson.fromJson(result, JsonElement.class);
                                if (jsonElement != null && jsonElement.isJsonObject()) {
                                    JsonObject jsonObject = jsonElement.getAsJsonObject();
                                    if (jsonObject.has("fxLink") && !jsonObject.get("fxLink").isJsonNull()) {
                                        lastFxLink = jsonObject.get("fxLink").getAsString();
                                    }
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to extract fxLink from result", e);
                            }
                        }
                        return new QueryRetryResult(true, result, provider.name(), queryType, attempt, null);
                    }
                    
                    if (result != null && result.contains("fxLink")) {
                        try {
                            JsonElement jsonElement = gson.fromJson(result, JsonElement.class);
                            if (jsonElement != null && jsonElement.isJsonObject()) {
                                JsonObject jsonObject = jsonElement.getAsJsonObject();
                                if (jsonObject.has("fxLink") && !jsonObject.get("fxLink").isJsonNull()) {
                                    lastFxLink = jsonObject.get("fxLink").getAsString();
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to extract fxLink from error result", e);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Query failed on provider " + provider + ", attempt " + attempt + ": " + e.getMessage());
                }
            }
        }

        if ("current".equals(queryType) && lastFxLink != null) {
            try {
                String htmlResult = getWeatherFromFxLink(lastFxLink);
                if (htmlResult != null) {
                    Log.i(TAG, "Successfully fetched weather from fxLink as fallback");
                    return new QueryRetryResult(true, htmlResult, "FXLINK_HTML", queryType, maxAttempts + 1, null);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to fetch weather from fxLink", e);
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
                    
                case "minutely":
                    if (useLocation) {
                        return getHefengMinutelyByLocation(lat, lon).get(10, TimeUnit.SECONDS);
                    } else if (city != null && !city.isEmpty()) {
                        return getHefengMinutely(city).get(10, TimeUnit.SECONDS);
                    }
                    return "请提供城市名称或经纬度";
                    
                default:
                    return "未知的查询类型: " + queryType;
            }
        } finally {
            currentProvider = originalProvider;
        }
    }

    public String getWeatherFromFxLink(String fxLink) {
        if (fxLink == null || fxLink.isEmpty()) {
            return null;
        }
        
        try {
            String html = fetchHtmlPage(fxLink);
            if (html == null || html.isEmpty()) {
                Log.w(TAG, "Failed to fetch HTML from fxLink: " + fxLink);
                return null;
            }
            
            return parseQWeatherHtml(html, fxLink);
        } catch (Exception e) {
            Log.e(TAG, "Error getting weather from fxLink", e);
            return null;
        }
    }
    
    private String fetchHtmlPage(String url) throws Exception {
        Request request = NetworkUtil.createRequestBuilder(url).build();
        
        try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("HTTP " + response.code());
            }
            
            ResponseBody body = response.body();
            if (body == null) {
                return "";
            }
            
            return body.string();
        }
    }
    
    private String parseQWeatherHtml(String html, String fxLink) {
        try {
            JsonObject resultJson = new JsonObject();
            resultJson.addProperty("code", "200");
            
            JsonObject location = new JsonObject();
            
            String cityName = extractCityNameFromHtml(html);
            if (cityName == null) {
                cityName = extractCityNameFromUrl(fxLink);
            }
            if (cityName != null) {
                location.addProperty("name", cityName);
            }
            resultJson.add("location", location);
            
            JsonObject now = new JsonObject();
            
            String temperature = extractTemperatureFromHtml(html);
            if (temperature != null) {
                now.addProperty("temp", temperature);
            }
            
            String weather = extractWeatherFromHtml(html);
            if (weather != null) {
                now.addProperty("text", weather);
            }
            
            String humidity = extractHumidityFromHtml(html);
            if (humidity != null) {
                now.addProperty("humidity", humidity);
            }
            
            String windSpeed = extractWindSpeedFromHtml(html);
            if (windSpeed != null) {
                now.addProperty("windSpeed", windSpeed);
            }
            
            String windDir = extractWindDirFromHtml(html);
            if (windDir != null) {
                now.addProperty("windDir", windDir);
            }
            
            String feelsLike = extractFeelsLikeFromHtml(html);
            if (feelsLike != null) {
                now.addProperty("feelsLike", feelsLike);
            }
            
            String visibility = extractVisibilityFromHtml(html);
            if (visibility != null) {
                now.addProperty("vis", visibility);
            }
            
            String pressure = extractPressureFromHtml(html);
            if (pressure != null) {
                now.addProperty("pressure", pressure);
            }
            
            String uv = extractUvFromHtml(html);
            if (uv != null) {
                now.addProperty("uv", uv);
            }
            
            resultJson.add("now", now);
            
            JsonArray daily = parseDailyForecastFromHtml(html);
            if (daily != null && daily.size() > 0) {
                resultJson.add("daily", daily);
            }
            
            resultJson.addProperty("fxLink", fxLink);
            
            return gson.toJson(resultJson);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing QWeather HTML", e);
            return null;
        }
    }
    
    private String extractCityNameFromHtml(String html) {
        int idx = html.indexOf("<h1");
        if (idx >= 0) {
            int endIdx = html.indexOf("</h1>", idx);
            if (endIdx > idx) {
                String h1 = html.substring(idx, endIdx);
                h1 = h1.replaceAll("<[^>]+>", "");
                h1 = h1.trim();
                if (!h1.isEmpty()) {
                    return h1;
                }
            }
        }
        
        idx = html.indexOf("<title");
        if (idx >= 0) {
            int endIdx = html.indexOf("</title>", idx);
            if (endIdx > idx) {
                String title = html.substring(idx, endIdx);
                title = title.replaceAll("<[^>]+>", "");
                title = title.trim();
                if (title.contains("-")) {
                    title = title.substring(0, title.indexOf("-")).trim();
                    if (!title.isEmpty()) {
                        return title;
                    }
                }
            }
        }
        
        return null;
    }
    
    private String extractCityNameFromUrl(String url) {
        int idx = url.indexOf("/weather/");
        if (idx >= 0) {
            int dashIdx = url.indexOf("-", idx + 9);
            if (dashIdx > idx) {
                return url.substring(idx + 9, dashIdx);
            }
        }
        return null;
    }
    
    private String extractTemperatureFromHtml(String html) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d{1,3})°");
        java.util.regex.Matcher matcher = pattern.matcher(html);
        
        int tempIdx = html.indexOf("温度");
        if (tempIdx >= 0) {
            String tempSection = html.substring(tempIdx, Math.min(tempIdx + 200, html.length()));
            matcher = pattern.matcher(tempSection);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        
        matcher = pattern.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        return null;
    }
    
    private String extractWeatherFromHtml(String html) {
        String[] weathers = {"晴", "多云", "阴", "小雨", "中雨", "大雨", "暴雨", "雷阵雨", "雪", "雾", "霾", "大风", "沙尘"};
        
        for (String w : weathers) {
            if (html.contains(w)) {
                return w;
            }
        }
        
        int idx = html.indexOf("天气");
        if (idx >= 0) {
            int endIdx = html.indexOf("<", idx);
            if (endIdx > idx) {
                String weather = html.substring(idx + 2, endIdx);
                weather = weather.trim();
                if (weather.length() <= 10 && !weather.isEmpty()) {
                    return weather;
                }
            }
        }
        
        return null;
    }
    
    private String extractHumidityFromHtml(String html) {
        int idx = html.indexOf("相对湿度");
        if (idx >= 0) {
            int percentIdx = html.indexOf("%", idx);
            if (percentIdx > idx && percentIdx < idx + 50) {
                int startIdx = percentIdx - 1;
                while (startIdx >= idx && Character.isDigit(html.charAt(startIdx))) {
                    startIdx--;
                }
                return html.substring(startIdx + 1, percentIdx);
            }
        }
        
        idx = html.indexOf("湿度");
        if (idx >= 0) {
            int percentIdx = html.indexOf("%", idx);
            if (percentIdx > idx && percentIdx < idx + 50) {
                int startIdx = percentIdx - 1;
                while (startIdx >= idx && Character.isDigit(html.charAt(startIdx))) {
                    startIdx--;
                }
                return html.substring(startIdx + 1, percentIdx);
            }
        }
        
        return null;
    }
    
    private String extractWindSpeedFromHtml(String html) {
        int idx = html.indexOf("风速");
        if (idx >= 0) {
            int endIdx = html.indexOf("<", idx);
            if (endIdx > idx && endIdx < idx + 100) {
                String speed = html.substring(idx + 2, endIdx);
                speed = speed.trim();
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\d+)");
                java.util.regex.Matcher matcher = pattern.matcher(speed);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        
        return null;
    }
    
    private String extractWindDirFromHtml(String html) {
        String[] directions = {"北风", "南风", "东风", "西风", "东北风", "东南风", "西北风", "西南风"};
        
        for (String dir : directions) {
            if (html.contains(dir)) {
                return dir;
            }
        }
        
        int idx = html.indexOf("风向");
        if (idx >= 0) {
            int endIdx = html.indexOf("<", idx);
            if (endIdx > idx && endIdx < idx + 50) {
                String dir = html.substring(idx + 2, endIdx);
                dir = dir.trim();
                if (dir.length() <= 4 && !dir.isEmpty()) {
                    return dir;
                }
            }
        }
        
        return null;
    }
    
    private String extractFeelsLikeFromHtml(String html) {
        int idx = html.indexOf("体感温度");
        if (idx >= 0) {
            int degreeIdx = html.indexOf("°", idx);
            if (degreeIdx > idx && degreeIdx < idx + 50) {
                int startIdx = degreeIdx - 1;
                while (startIdx >= idx && Character.isDigit(html.charAt(startIdx))) {
                    startIdx--;
                }
                return html.substring(startIdx + 1, degreeIdx);
            }
        }
        
        return null;
    }
    
    private String extractVisibilityFromHtml(String html) {
        int idx = html.indexOf("能见度");
        if (idx >= 0) {
            int kmIdx = html.indexOf("km", idx);
            if (kmIdx > idx && kmIdx < idx + 50) {
                int startIdx = kmIdx - 1;
                while (startIdx >= idx && (Character.isDigit(html.charAt(startIdx)) || html.charAt(startIdx) == '.')) {
                    startIdx--;
                }
                return html.substring(startIdx + 1, kmIdx);
            }
        }
        
        return null;
    }
    
    private String extractPressureFromHtml(String html) {
        int idx = html.indexOf("气压");
        if (idx >= 0) {
            int hpaIdx = html.indexOf("hPa", idx);
            if (hpaIdx > idx && hpaIdx < idx + 50) {
                int startIdx = hpaIdx - 1;
                while (startIdx >= idx && Character.isDigit(html.charAt(startIdx))) {
                    startIdx--;
                }
                return html.substring(startIdx + 1, hpaIdx);
            }
        }
        
        return null;
    }
    
    private String extractUvFromHtml(String html) {
        int idx = html.indexOf("紫外线");
        if (idx >= 0) {
            int endIdx = html.indexOf("<", idx);
            if (endIdx > idx && endIdx < idx + 50) {
                String uv = html.substring(idx + 3, endIdx);
                uv = uv.trim();
                if (uv.equals("强")) return "8";
                if (uv.equals("中")) return "5";
                if (uv.equals("弱")) return "2";
                if (uv.equals("极强")) return "10";
            }
        }
        
        return null;
    }
    
    private JsonArray parseDailyForecastFromHtml(String html) {
        JsonArray daily = new JsonArray();
        
        int idx = html.indexOf("未来预报");
        if (idx < 0) idx = html.indexOf("未来7天");
        if (idx < 0) idx = html.indexOf("天气预报");
        
        if (idx < 0) {
            return daily;
        }
        
        int endIdx = html.indexOf("空气质量", idx);
        if (endIdx < idx) endIdx = html.indexOf("生活指数", idx);
        if (endIdx < idx) endIdx = Math.min(idx + 2000, html.length());
        
        String forecastSection = html.substring(idx, endIdx);
        
        java.util.regex.Pattern datePattern = java.util.regex.Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
        java.util.regex.Matcher dateMatcher = datePattern.matcher(forecastSection);
        
        java.util.regex.Pattern tempPattern = java.util.regex.Pattern.compile("(\\d{1,3})°.*?(\\d{1,3})°");
        java.util.regex.Matcher tempMatcher = tempPattern.matcher(forecastSection);
        
        int count = 0;
        while (dateMatcher.find() && count < 7) {
            JsonObject day = new JsonObject();
            day.addProperty("fxDate", dateMatcher.group(1));
            
            if (tempMatcher.find()) {
                day.addProperty("tempMax", tempMatcher.group(1));
                day.addProperty("tempMin", tempMatcher.group(2));
            }
            
            daily.add(day);
            count++;
        }
        
        return daily;
    }
    
    private Map<String, Object> parseWeatherResultToMap(String result) {
        Map<String, Object> resultMap = new HashMap<>();
        
        if (result == null || result.isEmpty()) {
            resultMap.put("status", "error");
            resultMap.put("error", "天气数据为空");
            return resultMap;
        }

        try {
            // 前置检查：非JSON响应（如SDK格式化文本、HTML错误页）直接存储，避免Gson抛异常
            String trimmed = result.trim();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
                resultMap.put("formatted_result", result);
                resultMap.put("status", "success");
                return resultMap;
            }

            JsonElement jsonElement = gson.fromJson(result, JsonElement.class);
            if (jsonElement != null && jsonElement.isJsonObject()) {
                JsonObject jsonObject = jsonElement.getAsJsonObject();
                
                String code = jsonObject.has("code") && !jsonObject.get("code").isJsonNull() 
                    ? jsonObject.get("code").getAsString() : "";
                
                if (!"200".equals(code)) {
                    resultMap.put("status", "error");
                    resultMap.put("error", "API返回错误: " + code);
                    if (jsonObject.has("message")) {
                        resultMap.put("message", jsonObject.get("message").getAsString());
                    }
                    return resultMap;
                }

                if (jsonObject.has("location") && jsonObject.get("location").isJsonObject()) {
                    JsonObject location = jsonObject.get("location").getAsJsonObject();
                    if (location.has("name")) resultMap.put("city", location.get("name").getAsString());
                    if (location.has("lat")) resultMap.put("latitude", location.get("lat").getAsString());
                    if (location.has("lon")) resultMap.put("longitude", location.get("lon").getAsString());
                    if (location.has("id")) resultMap.put("location_id", location.get("id").getAsString());
                }

                if (jsonObject.has("now") && jsonObject.get("now").isJsonObject()) {
                    JsonObject now = jsonObject.get("now").getAsJsonObject();
                    if (now.has("text")) resultMap.put("weather", now.get("text").getAsString());
                    if (now.has("temp")) resultMap.put("temperature", now.get("temp").getAsString() + "°C");
                    if (now.has("feelsLike")) resultMap.put("feels_like", now.get("feelsLike").getAsString() + "°C");
                    if (now.has("humidity")) resultMap.put("humidity", now.get("humidity").getAsString() + "%");
                    if (now.has("windSpeed")) resultMap.put("wind_speed", now.get("windSpeed").getAsString() + " km/h");
                    if (now.has("windDir")) resultMap.put("wind_direction", now.get("windDir").getAsString());
                    if (now.has("vis")) resultMap.put("visibility", now.get("vis").getAsString() + " km");
                    if (now.has("pressure")) resultMap.put("pressure", now.get("pressure").getAsString() + " hPa");
                    if (now.has("icon")) resultMap.put("icon", now.get("icon").getAsString());
                    if (now.has("obsTime")) resultMap.put("observation_time", now.get("obsTime").getAsString());
                }

                if (jsonObject.has("daily") && jsonObject.get("daily").isJsonArray()) {
                    JsonArray daily = jsonObject.get("daily").getAsJsonArray();
                    StringBuilder forecastStr = new StringBuilder();
                    StringBuilder indicesStr = new StringBuilder();
                    boolean isForecast = false;
                    boolean isIndices = false;
                    
                    for (int i = 0; i < daily.size(); i++) {
                        JsonObject day = daily.get(i).getAsJsonObject();
                        
                        if (day.has("fxDate")) {
                            isForecast = true;
                            String date = day.has("fxDate") ? day.get("fxDate").getAsString() : "";
                            String textDay = day.has("textDay") ? day.get("textDay").getAsString() : "";
                            String tempMax = day.has("tempMax") ? day.get("tempMax").getAsString() : "--";
                            String tempMin = day.has("tempMin") ? day.get("tempMin").getAsString() : "--";
                            if (!date.isEmpty() && i < 7) {
                                forecastStr.append(date).append(": ").append(textDay)
                                    .append(" ").append(tempMin).append("°C~").append(tempMax).append("°C\n");
                            }
                        } else if (day.has("name")) {
                            isIndices = true;
                            String name = day.has("name") ? day.get("name").getAsString() : "";
                            String level = day.has("level") ? day.get("level").getAsString() : "";
                            String category = day.has("category") ? day.get("category").getAsString() : "";
                            String text = day.has("text") ? day.get("text").getAsString() : "";
                            if (!name.isEmpty()) {
                                indicesStr.append(name).append("(").append(level).append("): ").append(text).append("\n");
                            }
                        }
                    }
                    
                    if (isForecast) {
                        resultMap.put("forecast", forecastStr.toString().trim());
                    }
                    if (isIndices) {
                        resultMap.put("life_indices", indicesStr.toString().trim());
                    }
                }

                if (jsonObject.has("hourly") && jsonObject.get("hourly").isJsonArray()) {
                    JsonArray hourly = jsonObject.get("hourly").getAsJsonArray();
                    StringBuilder hourlyStr = new StringBuilder();
                    for (int i = 0; i < Math.min(24, hourly.size()); i++) {
                        JsonObject hour = hourly.get(i).getAsJsonObject();
                        String time = hour.has("fxTime") ? hour.get("fxTime").getAsString().substring(11, 16) : "";
                        String text = hour.has("text") ? hour.get("text").getAsString() : "";
                        String temp = hour.has("temp") ? hour.get("temp").getAsString() : "--";
                        if (!time.isEmpty()) {
                            hourlyStr.append(time).append(": ").append(text).append(" ").append(temp).append("°C\n");
                        }
                    }
                    resultMap.put("hourly", hourlyStr.toString().trim());
                }
                
                if (jsonObject.has("minutely") && jsonObject.get("minutely").isJsonArray()) {
                    JsonArray minutely = jsonObject.get("minutely").getAsJsonArray();
                    StringBuilder minutelyStr = new StringBuilder();
                    
                    if (jsonObject.has("summary") && !jsonObject.get("summary").isJsonNull()) {
                        minutelyStr.append("摘要: ").append(jsonObject.get("summary").getAsString()).append("\n");
                    }
                    
                    for (int i = 0; i < minutely.size(); i++) {
                        JsonObject minute = minutely.get(i).getAsJsonObject();
                        String time = minute.has("fxTime") ? minute.get("fxTime").getAsString() : "";
                        String precip = minute.has("precip") ? minute.get("precip").getAsString() : "--";
                        String type = minute.has("type") ? minute.get("type").getAsString() : "";
                        
                        if (!time.isEmpty()) {
                            String displayTime = time.length() > 16 ? time.substring(11, 16) : time;
                            String typeText = "rain".equals(type) ? "雨" : ("snow".equals(type) ? "雪" : "");
                            minutelyStr.append(displayTime).append(": ").append(precip).append("mm").append(typeText).append("\n");
                        }
                    }
                    resultMap.put("minutely", minutelyStr.toString().trim());
                }

                if (jsonObject.has("now") && jsonObject.get("now").isJsonObject()) {
                    JsonObject air = jsonObject.get("now").getAsJsonObject();
                    if (air.has("aqi")) resultMap.put("aqi", air.get("aqi").getAsString());
                    if (air.has("level")) resultMap.put("air_level", air.get("level").getAsString());
                    if (air.has("primary")) resultMap.put("primary_pollutant", air.get("primary").getAsString());
                    if (air.has("pm2p5")) resultMap.put("pm2.5", air.get("pm2p5").getAsString());
                    if (air.has("pm10")) resultMap.put("pm10", air.get("pm10").getAsString());
                    if (air.has("no2")) resultMap.put("no2", air.get("no2").getAsString());
                    if (air.has("so2")) resultMap.put("so2", air.get("so2").getAsString());
                    if (air.has("co")) resultMap.put("co", air.get("co").getAsString());
                    if (air.has("o3")) resultMap.put("o3", air.get("o3").getAsString());
                }

                if (jsonObject.has("indices") && jsonObject.get("indices").isJsonArray()) {
                    JsonArray indices = jsonObject.get("indices").getAsJsonArray();
                    StringBuilder indicesStr = new StringBuilder();
                    for (int i = 0; i < indices.size(); i++) {
                        JsonObject idx = indices.get(i).getAsJsonObject();
                        String name = idx.has("name") ? idx.get("name").getAsString() : "";
                        String level = idx.has("level") ? idx.get("level").getAsString() : "";
                        String category = idx.has("category") ? idx.get("category").getAsString() : "";
                        String text = idx.has("text") ? idx.get("text").getAsString() : "";
                        if (!name.isEmpty()) {
                            indicesStr.append(name).append("(").append(level).append("): ").append(text).append("\n");
                        }
                    }
                    resultMap.put("life_indices", indicesStr.toString().trim());
                }

                if (jsonObject.has("warning") && jsonObject.get("warning").isJsonArray()) {
                    JsonArray warnings = jsonObject.get("warning").getAsJsonArray();
                    StringBuilder alertsStr = new StringBuilder();
                    for (int i = 0; i < warnings.size(); i++) {
                        JsonObject warn = warnings.get(i).getAsJsonObject();
                        String title = warn.has("title") ? warn.get("title").getAsString() : "";
                        String level = warn.has("level") ? warn.get("level").getAsString() : "";
                        String text = warn.has("text") ? warn.get("text").getAsString() : "";
                        if (!title.isEmpty()) {
                            alertsStr.append("[").append(level).append("] ").append(title).append(": ").append(text).append("\n");
                        }
                    }
                    resultMap.put("alerts", alertsStr.toString().trim());
                }
                
                if (jsonObject.has("alerts") && jsonObject.get("alerts").isJsonArray()) {
                    JsonArray alerts = jsonObject.get("alerts").getAsJsonArray();
                    StringBuilder alertsStr = new StringBuilder();
                    for (int i = 0; i < alerts.size(); i++) {
                        JsonObject alert = alerts.get(i).getAsJsonObject();
                        String title = "";
                        if (alert.has("headline") && !alert.get("headline").isJsonNull()) {
                            title = alert.get("headline").getAsString();
                        } else if (alert.has("title") && !alert.get("title").isJsonNull()) {
                            title = alert.get("title").getAsString();
                        }
                        
                        String level = "";
                        if (alert.has("severity") && !alert.get("severity").isJsonNull()) {
                            level = convertSeverity(alert.get("severity").getAsString());
                        } else if (alert.has("level") && !alert.get("level").isJsonNull()) {
                            level = alert.get("level").getAsString();
                        }
                        
                        String text = "";
                        if (alert.has("description") && !alert.get("description").isJsonNull()) {
                            text = alert.get("description").getAsString();
                        } else if (alert.has("text") && !alert.get("text").isJsonNull()) {
                            text = alert.get("text").getAsString();
                        }
                        
                        String sender = "";
                        if (alert.has("senderName") && !alert.get("senderName").isJsonNull()) {
                            sender = alert.get("senderName").getAsString();
                        } else if (alert.has("sender") && !alert.get("sender").isJsonNull()) {
                            sender = alert.get("sender").getAsString();
                        }
                        
                        if (!title.isEmpty()) {
                            alertsStr.append("[").append(level).append("] ").append(title);
                            if (!sender.isEmpty()) {
                                alertsStr.append(" (").append(sender).append(")");
                            }
                            alertsStr.append(": ").append(text).append("\n");
                        }
                    }
                    resultMap.put("alerts", alertsStr.toString().trim());
                }

                if (jsonObject.has("sun") && jsonObject.get("sun").isJsonObject()) {
                    JsonObject sun = jsonObject.get("sun").getAsJsonObject();
                    if (sun.has("sunrise")) resultMap.put("sunrise", sun.get("sunrise").getAsString());
                    if (sun.has("sunset")) resultMap.put("sunset", sun.get("sunset").getAsString());
                }

            } else {
                // SDK返回的格式化文本或其他非JSON结果，直接存储
                resultMap.put("formatted_result", result);
            }
            
            if (!resultMap.containsKey("status")) {
                resultMap.put("status", "success");
            }
            
            return resultMap;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing weather result to map", e);
            resultMap.put("status", "error");
            resultMap.put("error", "解析天气数据失败: " + e.getMessage());
            return resultMap;
        }
    }
}
