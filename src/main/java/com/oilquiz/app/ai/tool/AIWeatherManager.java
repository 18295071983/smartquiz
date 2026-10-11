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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.HashMap;

@Tool(
    value = "ai_weather",
    description = "天气查询工具：按需查询实时天气/未来几天预报/逐小时/空气质量/预警/生活指数。"
            + "按用户需求选 action：current(实时)/forecast(未来几天)/hourly(逐小时)/"
            + "air_quality(空气质量)/alerts(预警)/indices(生活指数)/all(全部)。"
            + "位置用 city=城市名 或 和风城市编码 或 lat+lon 经纬度(二选一)。"
            + "要未来天气→forecast，要空气质量→air_quality，要生活指数→indices。",
    category = "weather",
    aliases = {"weather", "get_weather"},
    actions = {
        @Action(name = "current", description = "Get current weather (实时天气)"),
        @Action(name = "forecast", description = "Get weather forecast (未来几天预报)"),
        @Action(name = "hourly", description = "Get hourly weather (逐小时天气)"),
        @Action(name = "air_quality", description = "Get air quality (空气质量)"),
        @Action(name = "alerts", description = "Get weather alerts (天气预警)"),
        @Action(name = "indices", description = "Get life indices (生活指数)"),
        @Action(name = "all", description = "Get all weather info (全部)")
    },
    params = {
        @Param(name = "city", type = "string", description = "City name 城市名 或 和风城市编码，与经纬度二选一", required = false),
        @Param(name = "lat", type = "float", description = "Latitude 纬度(与city二选一，配合lon；别名latitude)", required = false),
        @Param(name = "lon", type = "float", description = "Longitude 经度(与city二选一，配合lat；别名longitude)", required = false),
        @Param(name = "action", type = "string", description = "Action type 操作类型，按需求选: current(实时)/forecast(预报)/hourly(逐小时)/air_quality(空气质量)/alerts(预警)/indices(生活指数)/all(全部)", required = false)
    }
)
public class AIWeatherManager implements AITool {

    private static final String TAG = "AIWeatherManager";
    
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
    private String getGeocodeUrl() { return apiHost + "/geo/v2/city/lookup"; }
    
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
                // 注意：返回值可能是 LocationID，也可能是 "lon,lat" 坐标串 ——
                // 两者都是和风 location 参数接受的形式（见 resolveHefengLocationParam 说明）
                String location = resolveHefengLocationParam(city);
                if (location != null && !location.isEmpty()) {
                    return location;
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to resolve location param for city: " + city, e);
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

    public WeatherProvider getWeatherProvider() {
        return currentProvider;
    }

    /**
     * 为和风 API 请求添加 Android 应用标识头（X-Android-Package-Name / X-Android-Cert）。
     * 官方 SDK 自动携带；第三方 HTTP 直连时若不携带，凭据一旦设置 Android 应用限制即被 403。
     */
    private void addAndroidAppHeaders(okhttp3.Request.Builder builder) {
        try {
            if (context == null) return;
            String packageName = context.getPackageName();
            if (packageName != null && !packageName.isEmpty()) {
                builder.addHeader("X-Android-Package-Name", packageName);
            }
            String certSha1 = getAppCertSha1();
            if (certSha1 != null && !certSha1.isEmpty()) {
                builder.addHeader("X-Android-Cert", certSha1);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to add Android app headers: " + t.getMessage());
        }
    }

    /** 获取应用签名的 SHA-1 指纹（十六进制大写，冒号分隔），供 X-Android-Cert 头使用 */
    private String getAppCertSha1() {
        try {
            if (context == null) return null;
            android.content.pm.PackageManager pm = context.getPackageManager();
            String packageName = context.getPackageName();
            android.content.pm.PackageInfo info = pm.getPackageInfo(packageName,
                    android.content.pm.PackageManager.GET_SIGNATURES);
            if (info.signatures == null || info.signatures.length == 0) return null;
            byte[] cert = info.signatures[0].toByteArray();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(cert);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) sb.append(":");
                sb.append(String.format(java.util.Locale.US, "%02X", digest[i]));
            }
            return sb.toString();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to get app cert SHA-1: " + t.getMessage());
            return null;
        }
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

            // 和风第三方 API 调用要求携带 Android 应用标识头，否则凭据设置了应用限制时返回 403 Security Restriction
            addAndroidAppHeaders(requestBuilder);

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
        HEFENG
    }

    // 获取当前天气信息（根据选择的提供者调用相应API）
    public CompletableFuture<String> getCurrentWeather(String city) {
        return getHefengCurrentWeather(city);
    }

    public CompletableFuture<String> getCurrentWeatherByLocation(double lat, double lon) {
        return getCurrentWeatherByLocation(lat, lon, null);
    }

    public CompletableFuture<String> getCurrentWeatherByLocation(double lat, double lon, String cityName) {
        return getHefengCurrentWeatherByLocation(lat, lon, cityName);
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

    // 和风天气当前天气查询
    private CompletableFuture<String> getHefengCurrentWeather(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String location = resolveHefengLocationParam(city);
                if (location == null || location.isEmpty()) {
                    return "天气信息:\n查询失败（无法获取城市位置）";
                }

                String urlString = getWeatherUrl() + "?location=" + location;
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

    /**
     * 把城市入参统一解析成 CSV 城市条目（CITY-RESOLVE-UNIFY, 2026-10-11）。
     *
     * <p><b>为什么抽出来</b>：{@link #resolveHefengLocationParam(String)} 与
     * {@link #getHefengLatLon(String)} 原本各自维护一份完全相同的解析链
     * （数字 ID 特判 + CSV 精确匹配 + 去行政后缀 + 关键词搜索）。两处只修一处就会出 bug ——
     * COORD-CODE-FIX 那次的教训正是：编码特判只加在了前者，后者拿编码去查名字索引必然 miss，
     * 导致 air_quality/alerts 这类**必须坐标**的 action 直接失败。现在共享本方法，
     * 两者不再可能漂移。</p>
     *
     * <p>入参两种形态都能解析：<b>纯数字编码</b>（走主键 {@code getCityById}）与
     * <b>城市名</b>（精确 → 去后缀 → 关键词搜索）。</p>
     *
     * @return 命中的城市条目；CSV 无此城市时返回 null（由调用方决定各自回退）
     */
    private com.oilquiz.app.weather.QWeatherCityManager.CityEntry resolveCityEntry(String city) {
        if (city == null || city.trim().isEmpty()) return null;
        try {
            com.oilquiz.app.weather.QWeatherCityManager cityMgr =
                    com.oilquiz.app.weather.QWeatherCityManager.getInstance(context);

            // 0. 纯数字 → 按和风城市编码（主键）反查
            if (city.matches("\\d{6,12}")) {
                com.oilquiz.app.weather.QWeatherCityManager.CityEntry byId = cityMgr.getCityById(city);
                if (byId != null) {
                    Log.i(TAG, "City code lookup hit: " + city + " -> " + byId.nameZh
                            + " (" + byId.latitude + "," + byId.longitude + ")");
                }
                return byId;
            }

            // 1. 精确匹配
            com.oilquiz.app.weather.QWeatherCityManager.CityEntry entry =
                    cityMgr.getCityByName(city);
            // 2. 去后缀匹配（市/县/区/旗/盟/州/地区/自治州）：模型可能传"银川市/五台县"
            if (entry == null) {
                String stripped = city.replaceAll("(自治州|地区|市|县|区|旗|盟|州)$", "");
                if (!stripped.isEmpty() && !stripped.equals(city)) {
                    entry = cityMgr.getCityByName(stripped);
                }
            }
            // 3. 关键词搜索（含"五台"匹配"五台县"）
            if (entry == null) {
                java.util.List<com.oilquiz.app.weather.QWeatherCityManager.CityEntry> matches =
                        cityMgr.searchCities(city);
                if (matches != null && !matches.isEmpty()) {
                    entry = matches.get(0);
                }
            }
            // 4. PARENT-FALLBACK(2026-10-11)：入参比城市表更"具体"时，向上回退到表内城市。
            //    例："宁夏银川"→银川、"山西省五台县"→五台县、"广东省深圳市南山区"→深圳南山。
            //    选优规则：**先用省/市信息消歧**（入参含该条目的 adm2Zh/adm1Zh 则加分），
            //    再比名字长度取更具体者。
            //    为什么必须消歧：同名的区县很多 —— 实测"广东省深圳市南山区"若只按名字最长匹配
            //    会选中**黑龙江鹤岗的南山**(47.31,130.28)，张冠李戴。
            //    注意：本层无法处理表内完全没有的地名（如乡镇"阳白乡"）——
            //    表只到市/县级且无县级字段，无从推断其行政父级，那类仍会走到"查不到"。
            if (entry == null) {
                com.oilquiz.app.weather.QWeatherCityManager.CityEntry best = null;
                int bestScore = -1;
                java.util.List<com.oilquiz.app.weather.QWeatherCityManager.CityEntry> all = cityMgr.getAllCities();
                if (all != null) {
                    for (com.oilquiz.app.weather.QWeatherCityManager.CityEntry c : all) {
                        if (c == null) continue;
                        String name = c.nameZh;
                        if (name == null || name.length() < 2) continue;
                        if (!city.contains(name)) continue;
                        // 消歧评分：入参里出现了该条目的市名/省名，说明上下文吻合
                        int score = 0;
                        if (c.adm2Zh != null && c.adm2Zh.length() >= 2 && city.contains(c.adm2Zh)) score++;
                        if (c.adm1Zh != null && c.adm1Zh.length() >= 2 && city.contains(c.adm1Zh)) score++;
                        if (best == null || score > bestScore
                                || (score == bestScore && name.length() > best.nameZh.length())) {
                            best = c;
                            bestScore = score;
                        }
                    }
                }
                if (best != null) {
                    entry = best;
                    Log.i(TAG, "Parent-city fallback: " + city + " -> " + entry.nameZh
                            + " (" + entry.adm1Zh + "/" + entry.adm2Zh + ", score=" + bestScore + ")");
                }
            }
            if (entry != null) {
                Log.i(TAG, "Local city lookup hit: " + city + " -> " + entry.nameZh
                        + " (" + entry.locationId + ")");
            }
            return entry;
        } catch (Throwable t) {
            Log.w(TAG, "resolveCityEntry failed for " + city + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * 把城市入参解析成**可直接作为和风 `location` 参数使用**的值。
     *
     * <p><b>⚠ 返回值不是单一形态 —— 这是本方法最容易被误读的地方</b>
     * （原名 {@code getHefengLocationId} 就是被这个名称坑过：见下）：</p>
     *
     * <ul>
     *   <li>CSV 命中 → <b>LocationID</b>，如 {@code "101280101"}</li>
     *   <li>CSV 未命中、设备 Geocoder 命中 → <b>坐标串</b>，如 {@code "116.4053,39.9050"}（经度,纬度）</li>
     *   <li>都未命中、geo API 命中 → <b>LocationID</b></li>
     * </ul>
     *
     * <p>之所以能这样混用，是因为和风的 {@code location} 参数**原生接受** LocationID 与
     * {@code 经度,纬度} 两种形式（见各处 {@code "?location=" + location} 拼接）。所以本方法返回的是
     * "参数值"而不是"ID" —— 原方法名 {@code getHefengLocationId} 名实不符，</p>
     *
     * <p><b>命名教训（COORD-CODE-FIX，2026-10-11）</b>：正因原名暗示"这是取 ID 的"，
     * {@link #getHefengLatLon(String)} 的作者没有跟着加"纯数字编码"特判，
     * 导致模型传 LocationID（如 {@code city=101280101}）时坐标解析 miss，
     * {@code air_quality}/{@code alerts}/{@code indices}/{@code minutely} 这类
     * **URL 路径必须坐标**的 action 直接失败（而 {@code current}/{@code forecast} 用 location 参数
     * 恰好接受编码，所以默认查询正常、只有空气质量/预警/生活指数会炸，不易发现）。</p>
     *
     * @throws Exception 本地表、系统 Geocoder、geo API 全部无法解析该城市时
     */
    private String resolveHefengLocationParam(String city) throws Exception {
        // 0/1. 本地城市 CSV（数字编码 → 城市名），离线可用且不依赖 geo API：
        //      天气详情界面用 QWeatherCityManager 从 assets CSV 查城市 ID，从不调 geo API
        //      ——这就是详情界面正常而 Agent 403 的原因。这里对齐详情界面。
        com.oilquiz.app.weather.QWeatherCityManager.CityEntry entry = resolveCityEntry(city);
        if (entry != null && entry.locationId != null && !entry.locationId.isEmpty()) {
            return entry.locationId;
        }

        // 2. Android 系统 Geocoder 正地理编码（地名→坐标）：完全本地系统服务，
        //    不依赖和风 geo API（该端点 JWT 403）。返回 "经度,纬度" 格式——
        //    和风 v7 的 location 参数原生支持该格式，调用方无需区分 ID/坐标。
        try {
            if (android.location.Geocoder.isPresent()) {
                android.location.Geocoder geocoder =
                        new android.location.Geocoder(context, java.util.Locale.CHINA);
                java.util.List<android.location.Address> addresses =
                        geocoder.getFromLocationName(city, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    android.location.Address addr = addresses.get(0);
                    if (addr.hasLatitude() && addr.hasLongitude()) {
                        String loc = String.format(java.util.Locale.US, "%.4f,%.4f",
                                addr.getLongitude(), addr.getLatitude());
                        Log.i(TAG, "System Geocoder hit: " + city + " -> " + loc);
                        return loc;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "System Geocoder lookup failed for " + city + ": " + t.getMessage());
        }

        // 3. geo API 兜底（仅在本地 CSV / 系统 Geocoder 都未命中时；JWT 认证）
        // 先做输入合理性校验：和风 geo 端点是**模糊匹配**，脏输入会被硬解析到毫不相干的地点
        // （真机实测 city="'; DROP TABLE weather;--" 竟返回美国 Table Rock 18°C）。
        // 没有注入风险，但会"一本正经地答错"，且让调用方以为该城市存在。
        if (!looksLikePlaceName(city)) {
            throw new Exception("无法定位城市: " + city + "（不是有效的地名）");
        }
        try {
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.name());
            String urlString = getGeocodeUrl() + "?location=" + encodedCity;
                String locationId = getHefengLocationFromGeoAPI(urlString, true);
            if (locationId != null) {
                return locationId;
            }
        } catch (Exception e) {
            Log.w(TAG, "GeoAPI failed for city " + city + ": " + e.getMessage());
        }
        
        throw new Exception("无法定位城市: " + city
                + "（不在城市表中）。该地名可能是乡镇/街道等更小单位，城市表只到市/县级。"
                + "请用你自己的地理知识判断它属于哪个县或市，然后用那个上级地名重新调用本工具"
                + "（例如\"阳白乡\"属于\"五台县\"，就传 city=五台县）；"
                + "若你不确定，改用 network_search 搜索\"" + city + "属于哪个县市\"后再查。"
                + "不要编造或猜测天气数据。");
    }

    /**
     * 把位置解析成 **"经度,纬度"** 串，供只接受经纬度的 v1 类接口使用
     * （和风 v1：空气质量 {@code airquality/v1}、天气预警 {@code weatheralert/v1}）。
     * 这些接口**不支持 LocationID**，SDK 侧会直接返回"v1 API需要经纬度坐标"。
     *
     * <p>COORD-ONLY-V1(2026-10-11)：此前 {@code getAirQualityAITool} / {@code getAlertsAITool} /
     * {@code getIndicesAITool} 的 SDK 分支传的是 {@code buildSdkLocation(city,...)}，
     * 而后者对"有编码的城市"返回的正是**编码本身** → 这两个 action 在 SDK 路径上必然失败。
     * 实测现象：问"五台县的空气质量"→ {@code 空气质量: v1 API需要经纬度坐标}；
     * 而同一城市的 {@code current} 正常（weather/now 接受编码）。</p>
     *
     * <p>解析顺序：① 显式 lat/lon（模型直接给了坐标，优先级最高）
     * → ② {@link #getHefengLatLon(String)}（本地 CSV / 系统 Geocoder / geo API，支持编码与城市名）。
     * 都拿不到返回 null，由调用方回退到按城市的 HTTP 实现（那些实现会自行解析）。</p>
     *
     * @return {@code "经度,纬度"}，顺序与和风 location 参数一致（先经度后纬度）
     */
    private String resolveLatLonString(Double lat, Double lon, String city) {
        double[] ll = null;
        if (lat != null && lon != null) {
            ll = new double[]{lat, lon};
        } else if (city != null && !city.trim().isEmpty()) {
            try {
                // getHefengLatLon 返回 {lat, lon}；其第 0 步已支持纯数字编码主键反查
                ll = getHefengLatLon(city);
            } catch (Exception e) {
                Log.w(TAG, "resolveLatLonString failed for " + city + ": " + e.getMessage());
            }
        }
        if (ll == null || ll.length < 2) return null;
        return String.format(java.util.Locale.US, "%.4f,%.4f", ll[1], ll[0]); // 经度,纬度
    }

    private double[] getHefengLatLon(String city) throws Exception {
        // 1. 本地城市 CSV（数字编码 → 城市名）；与 resolveHefengLocationParam 共用 resolveCityEntry，
        //    两者不再可能各自漂移（见该方法注释里的 COORD-CODE-FIX 教训）。
        com.oilquiz.app.weather.QWeatherCityManager.CityEntry entry = resolveCityEntry(city);
        if (entry != null) {
            return new double[]{entry.latitude, entry.longitude};
        }

        // 2. Android 系统 Geocoder 正地理编码（地名→坐标）：完全本地系统服务，
        // 不依赖和风 geo API（该端点 JWT 403）。CSV 未覆盖的生僻地名也常能解析。
        try {
            if (android.location.Geocoder.isPresent()) {
                android.location.Geocoder geocoder =
                        new android.location.Geocoder(context, java.util.Locale.CHINA);
                java.util.List<android.location.Address> addresses =
                        geocoder.getFromLocationName(city, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    android.location.Address addr = addresses.get(0);
                    if (addr.hasLatitude() && addr.hasLongitude()) {
                        Log.i(TAG, "System Geocoder hit: " + city + " -> "
                                + addr.getLatitude() + "," + addr.getLongitude()
                                + " (" + addr.getFeatureName() + ")");
                        return new double[]{addr.getLatitude(), addr.getLongitude()};
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "System Geocoder lookup failed for " + city + ": " + t.getMessage());
        }

        // 3. geo API 兜底（仅在本地 CSV 与系统 Geocoder 都未命中时）
        try {
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8.name());
            String urlString = getGeocodeUrl() + "?location=" + encodedCity;
                String response = httpGet(urlString);

                JsonObject jsonObject = gson.fromJson(response, JsonObject.class);
            if (jsonObject.has("code") && "200".equals(jsonObject.get("code").getAsString())) {
                JsonArray locationArray = jsonObject.getAsJsonArray("location");
                if (locationArray != null && locationArray.size() > 0) {
                    JsonObject location = locationArray.get(0).getAsJsonObject();
                    if (!location.has("lat") || !location.has("lon")) {
                        throw new Exception("geo 响应缺少坐标字段（" + city + "）");
                    }
                    double lat = location.get("lat").getAsDouble();
                    double lon = location.get("lon").getAsDouble();
                    return new double[]{lat, lon};
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get lat/lon from GeoAPI for city " + city, e);
            throw new Exception("地理编码失败（" + city + "）: " + e.getMessage());
        }
        throw new Exception("无法定位城市坐标: " + city
                + "。若该地名是乡镇/街道等更小单位，请用你的地理知识判断它属于哪个县或市，"
                + "改用那个上级地名（例如\"阳白乡\"→\"五台县\"）重新调用；"
                + "不确定则先用 network_search 查\"" + city + "属于哪个县市\"。不要编造天气数据。");
    }

    public String getHefengCityNameByLocation(double lat, double lon) {
        try {
            String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
            String urlString = getGeocodeUrl() + "?location=" + location;
            // geo 端点：与 getHefengLocationFromGeoAPI 一致走 httpGet(JWT)
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
        // geo 端点仅作兜底（本地 CSV 已覆盖绝大多数城市）。走 httpGet(JWT)，
        // 与天气 v7 端点一致；若仍 403，调用方会得到明确错误并提示用坐标。
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
                String location = resolveHefengLocationParam(city);
                if (location == null || location.isEmpty()) {
                    return "天气预报:\n查询失败（无法获取城市位置）";
                }

                String urlString = getForecastUrl() + "?location=" + location;
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
                String location = resolveHefengLocationParam(city);
                if (location == null || location.isEmpty()) {
                    return "24小时预报:\n查询失败（无法获取城市位置）";
                }

                String urlString = getHourlyUrl() + "?location=" + location;
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
        // 本地 CSV 优先（与 resolveHefengLocationParam 一致，离线可用）
        try {
            com.oilquiz.app.weather.QWeatherCityManager cityMgr =
                    com.oilquiz.app.weather.QWeatherCityManager.getInstance(context);
            com.oilquiz.app.weather.QWeatherCityManager.CityEntry entry =
                    cityMgr.getCityById(locationId);
            if (entry != null) {
                return new double[]{entry.latitude, entry.longitude};
            }
        } catch (Throwable t) {
            Log.w(TAG, "Local city lookup failed for id " + locationId + ": " + t.getMessage());
        }
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

    /**
     * 把城市名解析成经纬度（air_quality/alerts/indices/minutely 走 URL 路径必须坐标，
     * 但模型通常只给 city）。
     *
     * <p><b>COORD-FORCE-LOCAL(2026-10-11)</b>：此前这几个 action 在「有 city、无坐标」时直接调
     * {@code getHefengAirQuality(city)} 等「按城市」实现，而那些实现内部又调
     * {@link #getHefengLatLon(String)} 解析 —— 若本地 CSV 与系统 Geocoder 都未命中，
     * 会落到和风 geo API（该端点无 JWT 权限时 403）而失败。这里改为**先走本地链路**拿到坐标，
     * 拿到就用 {@code *ByLocation} 实现直接打坐标 URL，与 coordinates 分支走同一条可靠路径。
     *
     * <p><b>为什么不直接用设备定位坐标</b>：用户问的是"北京天气"，用设备坐标会返回错误城市的
     * 数据（{@code buildSdkLocation} 坐标优先、{@code *ByLocation} 根本不收 city 参数）
     * —— 那比查询失败更糟。所以本方法只接受 city→坐标的**忠实换算**，换算不出就返回 null，
     * 由调用方回退到原「按城市」实现（那里失败会返回"无法获取城市位置信息"，属如实告知）。
     *
     * @return 与 API 一致的 {@code {lat, lon}}；解析不出返回 null
     */
    private double[] resolveHefengLatLonForCity(String city) {
        if (city == null || city.trim().isEmpty()) return null;
        try {
            return getHefengLatLon(city);
        } catch (Throwable t) {
            Log.w(TAG, "resolveHefengLatLonForCity failed for " + city + ": " + t.getMessage());
            return null;
        }
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
            Object actionObj = parameters.get("action");
            String action = null;
            if (actionObj != null) {
                if (actionObj instanceof String) {
                    action = (String) actionObj;
                } else if (actionObj instanceof Number) {
                    action = String.valueOf(actionObj);
                } else {
                    action = String.valueOf(actionObj);
                }
            }
            if (action == null) {
                action = "current";
            }
            
            Object cityObj = parameters.get("city");
            String city = null;
            if (cityObj != null) {
                if (cityObj instanceof String) {
                    city = (String) cityObj;
                } else if (cityObj instanceof Number) {
                    city = String.valueOf(cityObj);
                } else if (cityObj instanceof org.json.JSONObject) {
                    // 模型可能传入 {"name":"北京"} 或 {"city":"北京"} 结构
                    org.json.JSONObject jo = (org.json.JSONObject) cityObj;
                    city = jo.optString("name", jo.optString("city", jo.optString("location", jo.toString())));
                } else {
                    city = String.valueOf(cityObj);
                }
            }
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
            boolean hasCity = city != null && !city.trim().isEmpty();

            // CONFlict-GUARD：city 与 lat/lon 同时传入时，两条路径都只用坐标、
            // 但会把 city 原样回显（"城市: 北京" 配银川的数据）—— 输出张冠李戴，极具误导性。
            // 真机模型自测（50+ 用例）专门验证过这一点。这里直接要求二选一，不再静默择一。
            if (hasCity && useLocation) {
                Map<String, Object> err = new HashMap<>();
                err.put("status", "error");
                err.put("error", "city 与 lat/lon 只能二选一");
                return new AIToolResult(
                        "参数冲突: 请只传 city（按城市查）或只传成对的 lat+lon（按坐标查），不要同时传",
                        err);
            }

            // AUTO-LOCATION：不传 city 也不传坐标时，按工具描述承诺的行为**自动使用设备定位**。
            // 此前这里直接报"缺少城市参数"，与工具描述（"系统会自动用手机定位"）矛盾 ——
            // 模型据描述认为不传参可行，实际必然失败（真机模型自测发现）。
            if (!hasCity && !useLocation) {
                double[] loc = tryAutoLocate();
                if (loc != null) {
                    lat = loc[0];
                    lon = loc[1];
                    useLocation = true;
                    Log.i(TAG, "ai_weather 未指定位置，已自动使用设备定位: " + lat + "," + lon);
                } else {
                    Map<String, Object> err = new HashMap<>();
                    err.put("status", "error");
                    err.put("error", "无法获取设备定位，且未提供 city");
                    return new AIToolResult(
                            "无法获取设备定位: 请提供 city，或打开系统定位后重试", err);
                }
            }

            // 需要城市或坐标的 action：两者都缺失时明确报错，不再静默回退"北京"
            switch (action) {
                case "current":
                case "forecast":
                case "hourly":
                case "air_quality":
                case "alerts":
                case "indices":
                case "all": {
                    if (!hasCity && !useLocation) {
                        Map<String, Object> err = new HashMap<>();
                        err.put("status", "error");
                        err.put("error", "缺少城市参数(city)或坐标(lat/lon)");
                        return new AIToolResult("缺少城市参数: 请提供 city 或 lat/lon", err);
                    }
                    break;
                }
                default:
                    break;
            }
            
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
                    // COORD-ONLY-V1：空气质量走 v1 接口，SDK 只接受经纬度（不支持 LocationID），
                    // 故不能像 current/forecast 那样传 buildSdkLocation 的编码，必须先解析成坐标串
                    String location = resolveLatLonString(lat, lon, city);
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
                    // COORD-FORCE-LOCAL：先本地换坐标（见 resolveHefengLatLonForCity 说明），
                    // 换不出再退回「按城市」实现（其内部最终可能落 geo API）
                    double[] ll = resolveHefengLatLonForCity(city);
                    result = (ll != null && ll.length >= 2)
                            ? getHefengAirQualityByLocation(ll[0], ll[1]).get()
                            : getHefengAirQuality(city).get();
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
                    // COORD-ONLY-V1：天气预警走 weatheralert/v1，SDK 只接受经纬度
                    String location = resolveLatLonString(lat, lon, city);
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
                    // COORD-FORCE-LOCAL：同 getAirQualityAITool；alerts 尤重——
                    // 其 URL（getAlertUrl）把经纬度拼在路径里，没有坐标就完全无法请求
                    double[] ll = resolveHefengLatLonForCity(city);
                    result = (ll != null && ll.length >= 2)
                            ? getHefengAlertsByLocation(ll[0], ll[1]).get()
                            : getHefengAlerts(city).get();
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
                    // COORD-ONLY-V1：生活指数（v7 indices）同样以经纬度作为 location 参数最稳；
                    // 原本传 buildSdkLocation 的编码会在 SDK 侧再解析一次（可能落 geo API）
                    String location = resolveLatLonString(lat, lon, city);
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
                    // COORD-FORCE-LOCAL：indices 的 URL（getIndicesUrl）同样把坐标拼进 location 参数
                    double[] ll = resolveHefengLatLonForCity(city);
                    result = (ll != null && ll.length >= 2)
                            ? getHefengIndicesByLocation(ll[0], ll[1]).get()
                            : getHefengIndices(city).get();
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
                    // COORD-ONLY(2026-10-11)：SDK 的 getMinutely 用 split(",") 解析 location，
                    // 拿不到坐标时**静默回退北京 (116.41,39.92)** —— 与"错误数据冒充正确数据"同源，
                    // 故必须先解析出坐标串。注：minutely 当前未暴露为 ai_weather 的 action（不可达），
                    // 此处修正为将来暴露时不再踩坑。
                    String location = resolveLatLonString(lat, lon, city);
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
    
    /**
     * 判断一个字符串**像不像地名**，用于在 geo 兜底前挡掉明显非地名的输入。
     *
     * <p>背景：和风 geo 端点（{@code /geo/v2/city/lookup}）是模糊匹配，脏输入会被解析到
     * 毫不相干的地点（真机实测 {@code city="'; DROP TABLE weather;--"} → 美国 Table Rock 18°C）。
     * 无注入风险，但属"一本正经地答错"。
     *
     * <p>规则刻意宽松，**不校验真实地名**（否则会误伤"纽约"这类本地城市表没有的海外城市）：
     * <ol>
     *   <li>至少要有字母或 CJK 字符（排除纯符号/纯数字垃圾）</li>
     *   <li>不含 SQL 元字符 {@code ; ' " ` \ -- /*}（合法地名不会包含它们）</li>
     * </ol>
     */
    private static boolean looksLikePlaceName(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        boolean hasLetter = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) { hasLetter = true; break; }
        }
        if (!hasLetter) return false;
        if (t.contains(";") || t.contains("'") || t.contains("\"") || t.contains("`")
                || t.contains("\\") || t.contains("--") || t.contains("/*")) {
            return false;
        }
        return true;
    }

    /**
     * 自动获取设备定位（供 ai_weather 在未指定位置时使用）。
     *
     * <p>复用 {@code ToolContextProvider.getCurrentLocation}（与引擎题注注入、location 工具同一套实现：
     * getLastKnownLocation 优先，必要时 requestLocationUpdates，5 秒超时）。
     * 它本身是**异步回调**，而本工具是同步返回，故用 CountDownLatch 在**工作线程**上等待
     * （execute 由推理线程调用，非主线程，不会 ANR）。等待上限略大于其内部超时。
     *
     * @return {@code {lat, lon}}；拿不到则返回 null（调用方给明确错误，不静默兜底别的城市）
     */
    private double[] tryAutoLocate() {
        final double[] out = new double[2];
        final boolean[] ok = {false};
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            com.oilquiz.app.ai.agent.ToolContextProvider.getCurrentLocation(
                    context,
                    new com.oilquiz.app.ai.agent.ToolContextProvider.LocationCallback() {
                        @Override
                        public void onLocationReady(String cityName, double lat, double lon) {
                            out[0] = lat;
                            out[1] = lon;
                            ok[0] = true;
                            latch.countDown();
                        }

                        @Override
                        public void onLocationFailed(String error) {
                            Log.w(TAG, "自动定位失败: " + error);
                            latch.countDown();
                        }
                    });
            // 工具内部 5 秒超时 + Geocoder 反查可能稍慢，这里给 8 秒上限
            if (!latch.await(8, TimeUnit.SECONDS)) {
                Log.w(TAG, "自动定位超时");
                return null;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "自动定位异常: " + t.getMessage());
            return null;
        }
        return ok[0] ? out : null;
    }

    private AIToolResult getAllWeatherAITool(String city, Double lat, Double lon, boolean useLocation) {
        try {
            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "success");
            resultMap.put("type", "all");

            String sdkLocation = (sdkManager != null && sdkManager.isInitialized())
                ? buildSdkLocation(city, lat, lon, useLocation) : null;
            // COORD-ONLY-V1：air_quality / alerts 走 v1 接口，只接受经纬度，
            // 不能复用上面的 sdkLocation（它可能是 LocationID）—— 否则 action=all 里这两项恒失败
            String sdkCoordLocation = (sdkManager != null && sdkManager.isInitialized())
                ? resolveLatLonString(lat, lon, city) : null;

            CompletableFuture<String> currentFuture, forecastFuture, hourlyFuture, airFuture, alertsFuture, indicesFuture;

            if (sdkLocation != null) {
                // SDK路径：自动处理JWT/压缩/JSON解析
                currentFuture = sdkManager.getCurrentWeather(sdkLocation, city).exceptionally(e -> "查询失败: " + e.getMessage());
                forecastFuture = sdkManager.getDailyForecast(sdkLocation).exceptionally(e -> "查询失败: " + e.getMessage());
                hourlyFuture = sdkManager.getHourlyForecast(sdkLocation).exceptionally(e -> "查询失败: " + e.getMessage());
                // v1 类接口用坐标串；拿不到坐标就给出明确失败（交给下面合并展示，不再报"需要经纬度坐标"）
                airFuture = (sdkCoordLocation != null
                        ? sdkManager.getAirQuality(sdkCoordLocation)
                        : CompletableFuture.completedFuture("查询失败: 无法解析该城市的经纬度"))
                    .exceptionally(e -> "查询失败: " + e.getMessage());
                alertsFuture = (sdkCoordLocation != null
                        ? sdkManager.getWeatherAlerts(sdkCoordLocation)
                        : CompletableFuture.completedFuture("查询失败: 无法解析该城市的经纬度"))
                    .exceptionally(e -> "查询失败: " + e.getMessage());
                indicesFuture = sdkManager.getIndices(sdkLocation).exceptionally(e -> "查询失败: " + e.getMessage());
            } else {
                // HTTP回退路径
                currentFuture = useLocation
                    ? getCurrentWeatherByLocation(lat, lon).exceptionally(e -> "查询失败: " + e.getMessage())
                    : getCurrentWeather(city).exceptionally(e -> "查询失败: " + e.getMessage());
                // city 为空时不再静默回退"北京"（旧逻辑 6 处硬编码导致"查天气"返回北京天气）
                String fallbackCity = (city != null && !city.isEmpty()) ? city : null;
                String noCityMsg = "查询失败: 缺少城市参数(city)或坐标(lat/lon)";
                forecastFuture = useLocation
                    ? getHefengForecastByLocation(lat, lon).exceptionally(e -> "查询失败: " + e.getMessage())
                    : (fallbackCity != null
                        ? getHefengForecast(fallbackCity).exceptionally(e -> "查询失败: " + e.getMessage())
                        : CompletableFuture.completedFuture(noCityMsg));
                hourlyFuture = useLocation
                    ? getHefengHourlyByLocation(lat, lon).exceptionally(e -> "查询失败: " + e.getMessage())
                    : (fallbackCity != null
                        ? getHefengHourly(fallbackCity).exceptionally(e -> "查询失败: " + e.getMessage())
                        : CompletableFuture.completedFuture(noCityMsg));
                airFuture = useLocation
                    ? getHefengAirQualityByLocation(lat, lon).exceptionally(e -> "查询失败: " + e.getMessage())
                    : (fallbackCity != null
                        ? getHefengAirQuality(fallbackCity).exceptionally(e -> "查询失败: " + e.getMessage())
                        : CompletableFuture.completedFuture(noCityMsg));
                alertsFuture = useLocation
                    ? getHefengAlertsByLocation(lat, lon).exceptionally(e -> "查询失败: " + e.getMessage())
                    : (fallbackCity != null
                        ? getHefengAlerts(fallbackCity).exceptionally(e -> "查询失败: " + e.getMessage())
                        : CompletableFuture.completedFuture(noCityMsg));
                indicesFuture = useLocation
                    ? getHefengIndicesByLocation(lat, lon).exceptionally(e -> "查询失败: " + e.getMessage())
                    : (fallbackCity != null
                        ? getHefengIndices(fallbackCity).exceptionally(e -> "查询失败: " + e.getMessage())
                        : CompletableFuture.completedFuture(noCityMsg));
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
                    // MERGE-CONCAT(2026-10-11)：formatted_result 必须**拼接**而非覆盖 ——
                    // 6 个 action 各自都产出该 key，直接 put 会让后者覆盖前者，
                    // 最终只剩最后一个（indices）的内容，表现为"all 只返回生活指数"
                    // （真机模型两次自测都报了这一点，根因在此）。
                    if ("formatted_result".equals(key) && target.containsKey(key)) {
                        Object prev = target.get(key);
                        target.put(key, String.valueOf(prev) + "\n\n" + value);
                    } else {
                        target.put(key, value);
                    }
                }
            }
        }
    }
    
    @Override
    public String getName() {
        return "ai_weather";
    }

    @Override
    public String getDescription() {
        return "天气查询工具：按需查询实时天气/未来几天预报/逐小时/空气质量/预警/生活指数（和风天气数据源）。"
                + "按用户需求选 action：current(实时)/forecast(未来几天)/hourly(逐小时)/"
                + "air_quality(空气质量)/alerts(预警)/indices(生活指数)/all(全部)。"
                + "位置用 city=城市名 或 和风城市编码 或 lat+lon 经纬度(二选一)。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        
        descriptions.put("city", "城市名称或和风城市编码（支持中英文）。与 lat/lon **二选一，不要同时传**；"
                + "用户没指定城市时留空不传，系统会自动使用设备定位");
        descriptions.put("lat", "纬度（与 lon 成对使用，别名 latitude）。与 city 二选一");
        descriptions.put("lon", "经度（与 lat 成对使用，别名 longitude）。与 city 二选一");
        
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

            // SDK 天气路径返回的是**格式化文本**（非 JSON），上面的分支会把它塞进
            // formatted_result 并把 status 置为 success —— 于是哪怕内容是
            // "查询失败: …"/"缺少城市参数"/"无权限"，调用方看到的仍是 status:success。
            // 真机实测（模型自测报告）确认过这个问题：错误一律被包成 success，
            // 只能靠读文本才能发现真相。此处统一按文本特征纠正为 error。
            // 注意：不检查裸"异常"二字（健康建议含"异常敏感人群"会误判），只认明确失败标记。
            if ("success".equals(resultMap.get("status"))) {
                Object fr = resultMap.get("formatted_result");
                if (fr instanceof String && looksLikeFailureText((String) fr)) {
                    resultMap.put("status", "error");
                    resultMap.put("error", fr);
                }
            }

            return resultMap;
        } catch (Exception e) {
            Log.e(TAG, "Error parsing weather result to map", e);
            resultMap.put("status", "error");
            resultMap.put("error", "解析天气数据失败: " + e.getMessage());
            return resultMap;
        }
    }

    /**
     * 判断一段**天气结果文本**是否表示失败。
     *
     * <p>用于纠正「SDK 格式化文本一律被标成 status:success」的问题（真机模型自测发现）。
     * 只认明确的失败标记，**刻意不检查裸"异常"二字** —— SDK 健康建议文本含
     * "极少数异常敏感人群"，用它判定会把成功数据误判为失败。
     */
    private static boolean looksLikeFailureText(String text) {
        if (text == null || text.isEmpty()) return true;
        return text.contains("查询失败")
            || text.contains("获取失败")
            || text.contains("查询异常")
            || text.contains("工具执行失败")
            || text.contains("参数冲突")
            || text.contains("缺少城市参数")
            // "无法定位城市: X（本地城市表与 geo 查询均无结果）" —— 此前遗漏，
            // 导致该错误仍被标记为 success（真机模型复测报告指出）
            || text.contains("无法定位")
            || text.contains("无法获取")
            || text.contains("无法解析")
            || text.contains("无权限")
            || text.contains("未初始化")
            || text.contains("未找到")
            || text.contains("暂无数据")
            || text.contains("需要经纬度");
    }
}