package com.oilquiz.app.weather;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.util.APIKeyManager;
import com.qweather.sdk.Callback;
import com.qweather.sdk.QWeather;
import com.qweather.sdk.TokenGenerator;
import com.qweather.sdk.basic.Lang;
import com.qweather.sdk.basic.Unit;
import com.qweather.sdk.parameter.air.AirV1Parameter;
import com.qweather.sdk.parameter.alert.WeatherAlertCurrentParameter;
import com.qweather.sdk.parameter.astronomy.AstronomySunParameter;
import com.qweather.sdk.basic.Indices;
import com.qweather.sdk.parameter.indices.IndicesParameter;
import com.qweather.sdk.parameter.minutely.MinutelyParameter;
import com.qweather.sdk.parameter.weather.WeatherCurrentParameter;
import com.qweather.sdk.response.weather.WeatherCurrentResponse;
import com.qweather.sdk.parameter.weather.WeatherHourlyParameter;
import com.qweather.sdk.response.weather.WeatherForecastHourly;
import com.qweather.sdk.response.weather.WeatherForecastHourlyResponse;
import com.qweather.sdk.parameter.weather.WeatherDailyParameter;
import com.qweather.sdk.response.weather.WeatherForecastDaily;
import com.qweather.sdk.response.weather.WeatherForecastDailyResponse;
import com.qweather.sdk.response.weather.WeatherPeriodForecast;
import com.qweather.sdk.response.ValueUnit;
import com.qweather.sdk.response.air.v1.AirDaily;
import com.qweather.sdk.response.air.v1.AirIndex;
import com.qweather.sdk.response.air.v1.AirV1CurrentResponse;
import com.qweather.sdk.response.air.v1.AirV1DailyResponse;
import com.qweather.sdk.response.air.v1.Health;
import com.qweather.sdk.response.air.v1.HealthAdvice;
import com.qweather.sdk.response.air.v1.Pollutant;
import com.qweather.sdk.response.air.v1.PollutantConcentration;
import com.qweather.sdk.response.air.v1.PrimaryPollutant;
import com.qweather.sdk.response.alert.WeatherAlert;
import com.qweather.sdk.response.alert.WeatherAlertCurrentResponse;
import com.qweather.sdk.response.alert.WeatherAlertEventType;
import com.qweather.sdk.response.astronomy.AstronomySunResponse;
import com.qweather.sdk.response.error.ErrorDetail;
import com.qweather.sdk.response.error.ErrorResponse;
import com.qweather.sdk.response.indices.IndicesDaily;
import com.qweather.sdk.response.indices.IndicesDailyResponse;
import com.qweather.sdk.response.minutely.Minutely;
import com.qweather.sdk.response.minutely.MinutelyResponse;
import com.qweather.sdk.response.weather.WeatherCurrentResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class QWeatherSdkManager {

    private static final String TAG = "QWeatherSdkManager";
    private static final int TIMEOUT_SECONDS = 30;

    private static final String DEFAULT_QWEATHER_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEICwOvrfAlLBDEnFi+yhRLmCql0P1oXEgu7Jb2akwAQmJ\n-----END PRIVATE KEY-----";
    private static final String DEFAULT_QWEATHER_PROJECT_ID = "2B89AN9KXV";
    private static final String DEFAULT_QWEATHER_KID = "CAPR2BDUDV";
    private static final String DEFAULT_QWEATHER_API_HOST = "https://m278m2y7ak.re.qweatherapi.com";

    private static QWeatherSdkManager instance;
    private final Context context;
    private boolean initialized = false;
    private String apiHost;

    private QWeatherSdkManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized QWeatherSdkManager getInstance(Context context) {
        if (instance == null) {
            instance = new QWeatherSdkManager(context);
        }
        return instance;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void initialize(String host, String privateKey, String projectId, String kid) {
        try {
            String sdkHost = host;
            if (sdkHost.startsWith("https://")) {
                sdkHost = sdkHost.substring(8);
            } else if (sdkHost.startsWith("http://")) {
                sdkHost = sdkHost.substring(7);
            }
            this.apiHost = sdkHost;
            Log.d(TAG, "Initializing SDK with host: " + sdkHost);

            QWeather.getInstance(context, sdkHost).setLogEnable(false);

            TokenGenerator tokenGenerator = new TokenGenerator() {
                private final QWeatherJwtGenerator jwtGen;
                {
                    try {
                        jwtGen = new QWeatherJwtGenerator(privateKey, projectId, kid);
                        Log.d(TAG, "Custom TokenGenerator initialized with BouncyCastle Ed25519");
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to init JWT generator", e);
                    }
                }

                @Override
                public String generator() {
                    return jwtGen.getToken();
                }
            };

            QWeather.getInstance(context, sdkHost).setTokenGenerator(tokenGenerator);

            initialized = true;
            Log.d(TAG, "QWeather SDK initialized successfully with custom TokenGenerator");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize QWeather SDK", e);
            initialized = false;
        }
    }

    public boolean initializeFromStorage() {
        APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);

        String privateKey = apiKeyManager.getQWeatherPrivateKey();
        String projectId = apiKeyManager.getQWeatherProjectId();
        String kid = apiKeyManager.getQWeatherKid();
        String apiHost = apiKeyManager.getQWeatherApiHost();

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
            apiHost = DEFAULT_QWEATHER_API_HOST;
        }

        initialize(apiHost, privateKey, projectId, kid);
        return initialized;
    }

    /**
     * 把 location 解析成 v1 接口需要的 {lat, lon}。
     *
     * <p><b>为什么需要它（v7→v1 迁移的核心约束）</b>：v7 的 {@code location} 参数接受
     * LocationID 或 {@code 经度,纬度}；而 v1 的参数类构造时**只接受经纬度**
     * （{@code new WeatherCurrentParameter(double lat, double lon)}），没有 location 参数。
     * 本类的调用方（WeatherService 详情页 / AIWeatherManager 工具）传进来的常常是**编码**
     * （如 {@code 101101003}），所以必须在内部换算成坐标。
     *
     * <p>解析顺序：① {@code "lon,lat"} / {@code "lat,lon"} 坐标串 → 直接判定取用；
     * ② 纯数字 LocationID → 查本地 CSV（assets/China-City-List-latest.csv，3578 城，含经纬度），
     * 与 AIWeatherManager 的 getHefengLatLon 同源但**离线**，不依赖和风 geo API
     * （该端点无 JWT 权限时 403）。
     *
     * @return {@code {lat, lon}}；无法解析返回 null（调用方应明确报错，不得静默改查别处）
     */
    private double[] resolveLatLon(String location) {
        if (location == null || location.trim().isEmpty()) return null;
        String loc = location.trim();

        // ① 坐标串：按"中国境内"判定经度在前
        if (loc.contains(",")) {
            String[] parts = loc.split(",");
            if (parts.length == 2) {
                try {
                    double a = Double.parseDouble(parts[0].trim());
                    double b = Double.parseDouble(parts[1].trim());
                    // 中国经度约 73~135、纬度约 3~54；据此判定顺序（避免把 lat,lon 当 lon,lat）
                    boolean aIsLon = a >= 73 && a <= 136;
                    double lon = aIsLon ? a : b;
                    double lat = aIsLon ? b : a;
                    return new double[]{lat, lon};
                } catch (NumberFormatException ignored) {
                }
            }
            return null;
        }

        // ② 纯数字 LocationID → 本地 CSV 反查坐标
        if (loc.matches("\\d{6,12}")) {
            try {
                com.oilquiz.app.weather.QWeatherCityManager mgr =
                        com.oilquiz.app.weather.QWeatherCityManager.getInstance(context);
                com.oilquiz.app.weather.QWeatherCityManager.CityEntry e = mgr.getCityById(loc);
                if (e != null) {
                    Log.i(TAG, "resolveLatLon: " + loc + " -> " + e.nameZh
                            + " (" + e.latitude + "," + e.longitude + ")");
                    return new double[]{e.latitude, e.longitude};
                }
                Log.w(TAG, "resolveLatLon: LocationID " + loc + " 不在本地城市表中");
            } catch (Throwable t) {
                Log.w(TAG, "resolveLatLon failed for " + loc + ": " + t.getMessage());
            }
        }
        return null;
    }

    public CompletableFuture<String> getCurrentWeather(String location) {
        return getCurrentWeather(location, null);
    }

    public CompletableFuture<String> getCurrentWeather(String location, String cityName) {
        if (!initialized) {
            return CompletableFuture.completedFuture("天气SDK未初始化");
        }

        // V1-MIGRATION：v7 weatherNow → v1 weatherCurrent（v1 只接受经纬度）
        final double[] ll = resolveLatLon(location);
        if (ll == null) {
            return CompletableFuture.completedFuture("天气查询失败: 无法把 '" + location + "' 解析成经纬度");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            WeatherCurrentParameter parameter = new WeatherCurrentParameter(ll[0], ll[1])
                    .lang(Lang.ZH_HANS);

            QWeather.getInstance(context, apiHost).weatherCurrent(parameter, new Callback<WeatherCurrentResponse>() {
                @Override
                public void onSuccess(WeatherCurrentResponse response) {
                    try {
                        future.complete(parseWeatherNow(response, cityName));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(ErrorResponse errorResponse) {
                    try {
                        future.complete("查询失败: " + parseError(errorResponse));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onException(Throwable e) {
                    try {
                        future.complete("查询异常: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
            });

            startTimeoutThread(latch, future, "天气查询超时");

        } catch (Exception e) {
            Log.e(TAG, "Failed to call weatherNow", e);
            future.complete("天气查询失败: " + e.getMessage());
            latch.countDown();
        }

        return future;
    }

    public CompletableFuture<String> getHourlyForecast(String location) {
        if (!initialized) {
            return CompletableFuture.completedFuture("小时预报SDK未初始化");
        }

        // V1-MIGRATION(2026-10-11)：v7 weather24h → v1 weatherForecastHourly。
        // v1 参数类构造时只接受经纬度（无 location 参数），故先把 location 换算成坐标；
        // 换算不出就明确报错，不静默改查别处（避免返回错误城市的数据）。
        final double[] ll = resolveLatLon(location);
        if (ll == null) {
            return CompletableFuture.completedFuture("小时预报查询失败: 无法把 '" + location + "' 解析成经纬度");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            WeatherHourlyParameter parameter = new WeatherHourlyParameter(ll[0], ll[1])
                    .lang(Lang.ZH_HANS)
                    .hours(24);

            QWeather.getInstance(context, apiHost).weatherForecastHourly(parameter, new Callback<WeatherForecastHourlyResponse>() {
                @Override
                public void onSuccess(WeatherForecastHourlyResponse response) {
                    try {
                        future.complete(parseHourly(response));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(ErrorResponse errorResponse) {
                    try {
                        future.complete("查询失败: " + parseError(errorResponse));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onException(Throwable e) {
                    try {
                        future.complete("查询异常: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
            });

            startTimeoutThread(latch, future, "小时预报查询超时");

        } catch (Exception e) {
            Log.e(TAG, "Failed to call weatherForecastHourly", e);
            future.complete("小时预报查询失败: " + e.getMessage());
            latch.countDown();
        }

        return future;
    }

    public CompletableFuture<String> getDailyForecast(String location) {
        if (!initialized) {
            return CompletableFuture.completedFuture("天气预报SDK未初始化");
        }

        // V1-MIGRATION：v7 weather7d → v1 weatherForecastDaily（同 hourly：v1 只接受经纬度）
        final double[] ll = resolveLatLon(location);
        if (ll == null) {
            return CompletableFuture.completedFuture("天气预报查询失败: 无法把 '" + location + "' 解析成经纬度");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            WeatherDailyParameter parameter = new WeatherDailyParameter(ll[0], ll[1])
                    .lang(Lang.ZH_HANS)
                    .days(7);

            QWeather.getInstance(context, apiHost).weatherForecastDaily(parameter, new Callback<WeatherForecastDailyResponse>() {
                @Override
                public void onSuccess(WeatherForecastDailyResponse response) {
                    try {
                        future.complete(parseDaily(response));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(ErrorResponse errorResponse) {
                    try {
                        future.complete("查询失败: " + parseError(errorResponse));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onException(Throwable e) {
                    try {
                        future.complete("查询异常: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
            });

            startTimeoutThread(latch, future, "天气预报查询超时");

        } catch (Exception e) {
            Log.e(TAG, "Failed to call weather7d", e);
            future.complete("天气预报查询失败: " + e.getMessage());
            latch.countDown();
        }

        return future;
    }

    public CompletableFuture<String> getAirQuality(String location) {
        if (!initialized) {
            return CompletableFuture.completedFuture("空气质量SDK未初始化");
        }

        // v1 API 仅支持经纬度，不支持城市ID
        double[] latLon = parseLatLonFromLocation(location);
        if (latLon == null) {
            return CompletableFuture.completedFuture("空气质量: v1 API需要经纬度坐标");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            AirV1Parameter parameter = new AirV1Parameter(latLon[0], latLon[1]);
            parameter.setLang(Lang.ZH_HANS);

            QWeather.getInstance(context, apiHost).airCurrent(parameter, new Callback<AirV1CurrentResponse>() {
                @Override
                public void onSuccess(AirV1CurrentResponse response) {
                    try {
                        future.complete(parseAirResponse(response));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(ErrorResponse errorResponse) {
                    try {
                        future.complete("空气质量查询失败: " + parseError(errorResponse));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onException(Throwable e) {
                    try {
                        future.complete("空气质量查询异常: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
            });

            startTimeoutThread(latch, future, "空气质量查询超时");

        } catch (Exception e) {
            Log.e(TAG, "Failed to call airCurrent", e);
            future.complete("空气质量查询失败: " + e.getMessage());
            latch.countDown();
        }

        return future;
    }

    public CompletableFuture<String> getAirForecast(String location) {
        if (!initialized) {
            return CompletableFuture.completedFuture("空气质量预报SDK未初始化");
        }

        double[] latLon = parseLatLonFromLocation(location);
        if (latLon == null) {
            return CompletableFuture.completedFuture("空气质量预报: v1 API需要经纬度坐标");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            AirV1Parameter parameter = new AirV1Parameter(latLon[0], latLon[1]);
            parameter.setLang(Lang.ZH_HANS);

            QWeather.getInstance(context, apiHost).airDaily(parameter, new Callback<AirV1DailyResponse>() {
                @Override
                public void onSuccess(AirV1DailyResponse response) {
                    try {
                        future.complete(parseAirForecastResponse(response));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(ErrorResponse errorResponse) {
                    try {
                        future.complete("空气质量预报查询失败: " + parseError(errorResponse));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onException(Throwable e) {
                    try {
                        future.complete("空气质量预报查询异常: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
            });

            startTimeoutThread(latch, future, "空气质量预报查询超时");

        } catch (Exception e) {
            Log.e(TAG, "Failed to call airDaily", e);
            future.complete("空气质量预报查询失败: " + e.getMessage());
            latch.countDown();
        }

        return future;
    }

    private String parseAirForecastResponse(AirV1DailyResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("空气质量预报:\n");

        List<AirDaily> days = response.getDays();
        if (days != null && !days.isEmpty()) {
            for (AirDaily day : days) {
                String startTime = day.getForecastStartTime();
                String date = "";
                if (startTime != null && !startTime.isEmpty()) {
                    int tIdx = startTime.indexOf('T');
                    date = tIdx > 0 ? startTime.substring(0, tIdx) : startTime;
                }

                List<AirIndex> dayIndexes = day.getIndexes();
                if (dayIndexes != null && !dayIndexes.isEmpty()) {
                    AirIndex index = dayIndexes.get(0);
                    String aqiDisplay = index.getAqiDisplay();
                    String category = index.getCategory();

                    if (!date.isEmpty()) {
                        sb.append(date);
                        if (aqiDisplay != null && !aqiDisplay.isEmpty())
                            sb.append(" AQI: ").append(aqiDisplay);
                        if (category != null && !category.isEmpty())
                            sb.append(" (").append(category).append(")");
                        sb.append("\n");
                    }

                    PrimaryPollutant primary = index.getPrimaryPollutant();
                    if (primary != null) {
                        String ppName = primary.getName();
                        if (ppName != null && !ppName.isEmpty()) {
                            sb.append("  首要污染物: ").append(ppName).append("\n");
                        }
                    }

                    Health health = index.getHealth();
                    if (health != null) {
                        String effect = health.getEffect();
                        if (effect != null && !effect.isEmpty()) {
                            sb.append("  健康影响: ").append(effect).append("\n");
                        }
                        HealthAdvice advice = health.getAdvice();
                        if (advice != null) {
                            String general = advice.getGeneralPopulation();
                            String sensitive = advice.getSensitivePopulation();
                            if (general != null && !general.isEmpty())
                                sb.append("  一般人群: ").append(general).append("\n");
                            if (sensitive != null && !sensitive.isEmpty())
                                sb.append("  敏感人群: ").append(sensitive).append("\n");
                        }
                    }
                }
            }
        }

        if (sb.toString().trim().equals("空气质量预报:")) {
            sb.append("暂无预报数据");
        }

        return sb.toString();
    }

    // 从 location 字符串解析经纬度，格式为 "经度,纬度"（与 v7 API 的 location 参数顺序一致）
    private double[] parseLatLonFromLocation(String location) {
        if (location == null || location.isEmpty()) {
            return null;
        }
        String[] parts = location.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            double lon = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            return new double[]{lat, lon};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public CompletableFuture<String> getWeatherAlerts(String location) {
        if (!initialized) {
            return CompletableFuture.completedFuture("天气预警SDK未初始化");
        }

        // v1 API 仅支持经纬度，不支持城市ID
        double[] latLon = parseLatLonFromLocation(location);
        if (latLon == null) {
            return CompletableFuture.completedFuture("天气预警: v1 API需要经纬度坐标");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            WeatherAlertCurrentParameter parameter = new WeatherAlertCurrentParameter(latLon[0], latLon[1], true);
            parameter.setLang(Lang.ZH_HANS);

            QWeather.getInstance(context, apiHost).weatherAlertCurrent(parameter, new Callback<WeatherAlertCurrentResponse>() {
                @Override
                public void onSuccess(WeatherAlertCurrentResponse response) {
                    try {
                        future.complete(parseAlerts(response));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(ErrorResponse errorResponse) {
                    try {
                        future.complete("查询失败: " + parseError(errorResponse));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onException(Throwable e) {
                    try {
                        future.complete("查询异常: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
            });

            startTimeoutThread(latch, future, "天气预警查询超时");

        } catch (Exception e) {
            Log.e(TAG, "Failed to call weatherAlertCurrent", e);
            future.complete("天气预警查询失败: " + e.getMessage());
            latch.countDown();
        }

        return future;
    }

    public CompletableFuture<String> getIndices(String location) {
        if (!initialized) {
            return CompletableFuture.completedFuture("生活指数SDK未初始化");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            IndicesParameter parameter = new IndicesParameter(location, Indices.SPT, Indices.CW, Indices.DRSG, Indices.UV, Indices.FIS, Indices.COMF, Indices.FLU);
            parameter.lang(Lang.ZH_HANS);

            QWeather.getInstance(context, apiHost).indices1d(parameter, new Callback<IndicesDailyResponse>() {
                @Override
                public void onSuccess(IndicesDailyResponse response) {
                    try {
                        future.complete(parseIndices(response));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(ErrorResponse errorResponse) {
                    try {
                        future.complete("查询失败: " + parseError(errorResponse));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onException(Throwable e) {
                    try {
                        future.complete("查询异常: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
            });

            startTimeoutThread(latch, future, "生活指数查询超时");

        } catch (Exception e) {
            Log.e(TAG, "Failed to call indices1d", e);
            future.complete("生活指数查询失败: " + e.getMessage());
            latch.countDown();
        }

        return future;
    }

    public CompletableFuture<String> getMinutelyByLocation(String location) {
        return getMinutely(location);
    }

    public CompletableFuture<String> getMinutely(String location) {
        if (!initialized) {
            return CompletableFuture.completedFuture("分钟级降水:\n暂无数据");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            String[] parts = location.split(",");
            double lon = 116.41;
            double lat = 39.92;
            if (parts.length >= 2) {
                lon = Double.parseDouble(parts[0].trim());
                lat = Double.parseDouble(parts[1].trim());
            }

            MinutelyParameter parameter = new MinutelyParameter(lon, lat);
            parameter.lang(Lang.ZH_HANS);

            QWeather.getInstance(context, apiHost).minutely(parameter, new Callback<MinutelyResponse>() {
                @Override
                public void onSuccess(MinutelyResponse response) {
                    try {
                        future.complete(parseMinutely(response));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(ErrorResponse errorResponse) {
                    try {
                        String errorMsg = parseError(errorResponse);
                        Log.w(TAG, "Minutely failed: " + errorMsg);
                        future.complete("分钟级降水:\n暂无数据");
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onException(Throwable e) {
                    try {
                        Log.e(TAG, "Minutely exception", e);
                        future.complete("分钟级降水:\n暂无数据");
                    } finally {
                        latch.countDown();
                    }
                }
            });

            startTimeoutThread(latch, future, "分钟级降水查询超时");

        } catch (Exception e) {
            Log.e(TAG, "Failed to call minutely5m", e);
            future.complete("分钟级降水:\n暂无数据");
            latch.countDown();
        }

        return future;
    }

    public CompletableFuture<String> getSunByLocation(String location) {
        return getSun(location);
    }

    public CompletableFuture<String> getSun(String location) {
        if (!initialized) {
            return CompletableFuture.completedFuture("日出日落SDK未初始化");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
            String date = sdf.format(new java.util.Date());

            AstronomySunParameter parameter = new AstronomySunParameter(location, date);

            QWeather.getInstance(context, apiHost).astronomySun(parameter, new Callback<AstronomySunResponse>() {
                @Override
                public void onSuccess(AstronomySunResponse response) {
                    try {
                        future.complete(parseSun(response));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onFailure(ErrorResponse errorResponse) {
                    try {
                        future.complete("查询失败: " + parseError(errorResponse));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onException(Throwable e) {
                    try {
                        future.complete("查询异常: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
            });

            startTimeoutThread(latch, future, "日出日落查询超时");

        } catch (Exception e) {
            Log.e(TAG, "Failed to call astronomySun", e);
            future.complete("日出日落查询失败: " + e.getMessage());
            latch.countDown();
        }

        return future;
    }

    private void startTimeoutThread(CountDownLatch latch, CompletableFuture<String> future, String timeoutMessage) {
        new Thread(() -> {
            try {
                boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!completed) {
                    future.complete(timeoutMessage);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private String parseError(ErrorResponse errorResponse) {
        if (errorResponse == null) {
            return "未知错误";
        }
        ErrorDetail error = errorResponse.getError();
        if (error != null) {
            String title = error.getTitle();
            String detail = error.getDetail();
            return (title != null ? title : "") + " " + (detail != null ? detail : "");
        }
        return errorResponse.toString();
    }

    /**
     * 解析 v1 实时天气（{@code weatherCurrent}）。
     *
     * <p>V1-MIGRATION 逐字段差异（对照 javap 实测 WeatherCurrentResponse）：
     * <ul>
     *   <li>天气现象：{@code getText()/getIcon()} → {@code getCondition().getText()/.getCode()}</li>
     *   <li>温度/体感/气压/能见度/露点：String → {@code ValueUnit}，取 {@code getValue()}
     *       （能见度 v1 单位为 m、v7 为 km；此处按接口返回的 unit 输出，不写死）</li>
     *   <li>湿度/云量：v1 是 <b>0~1 小数</b> → ×100 还原百分比</li>
     *   <li>风：v7 {@code getWindDir()/getWind360()/getWindScale()/getWindSpeed()}
     *       → v1 {@code getWind().getDirection().getCompass()/.getDegree()} +
     *       {@code getWind().getScale()} + {@code getWind().getSpeed()}（v1 风速为 m/s）</li>
     *   <li>降水：{@code getPrecip()} → {@code getPrecipitation().getIntensity()}（ValueUnit）</li>
     *   <li><b>无观测时间/更新时间/fxLink</b>（v7 有 obsTime/updateTime/fxLink；
     *       v1 只有 metadata.tag/sources，均已在 v7 版保留？否——故这三行不再输出）</li>
     * </ul>
     */
    private String parseWeatherNow(WeatherCurrentResponse response, String cityName) {
        StringBuilder sb = new StringBuilder();
        sb.append("天气信息:\n");

        if (cityName != null && !cityName.isEmpty()) {
            sb.append("城市: ").append(cityName).append("\n");
        }

        if (response.getCondition() != null) {
            String text = response.getCondition().getText();
            if (text != null && !text.isEmpty()) {
                sb.append("天气: ").append(text).append("\n");
            }
            String code = response.getCondition().getCode();
            if (code != null && !code.isEmpty()) {
                sb.append("图标: ").append(code).append("\n");
            }
        }

        if (response.getTemperature() != null && response.getTemperature().getValue() != null) {
            sb.append("温度: ").append(valueOf(response.getTemperature()))
              .append(response.getTemperature().getUnit() != null ? response.getTemperature().getUnit() : "°C").append("\n");
        }
        if (response.getFeelsLike() != null && response.getFeelsLike().getValue() != null) {
            sb.append("体感温度: ").append(valueOf(response.getFeelsLike()))
              .append(response.getFeelsLike().getUnit() != null ? response.getFeelsLike().getUnit() : "°C").append("\n");
        }
        if (response.getHumidity() != null) {
            sb.append("湿度: ").append(pct(response.getHumidity())).append("%\n");
        }
        if (response.getPrecipitation() != null && response.getPrecipitation().getIntensity() != null) {
            // SEMANTIC-CHANGE(v1)：v7 的 now.precip 是**累计降水量(mm)**，v1 的
            // precipitation.intensity 是**降水强度(mm/h)** —— 数值都对但语义不同，故标签随之改。
            // 已确认详情页对 "降水量:" 的解析结果 precip 是**死变量**（赋值后从未被读取），
            // 改标签不会破坏任何下游逻辑。
            sb.append("降水强度: ").append(valueOf(response.getPrecipitation().getIntensity()))
              .append(response.getPrecipitation().getIntensity().getUnit() != null
                      ? response.getPrecipitation().getIntensity().getUnit() : "mm/h").append("\n");
        }
        if (response.getWind() != null) {
            if (response.getWind().getDirection() != null) {
                // 方位代码 → 中文风名（下游按 v7 文本格式解析/渲染，见 compassToChinese 说明）
                String dirZh = compassToChinese(response.getWind().getDirection().getCompass());
                if (dirZh != null && !dirZh.isEmpty()) {
                    sb.append("风向: ").append(dirZh).append("\n");
                }
                Integer degree = response.getWind().getDirection().getDegree();
                if (degree != null) {
                    sb.append("风向角度: ").append(degree).append("°\n");
                }
            }
            if (response.getWind().getScale() != null) {
                sb.append("风力: ").append(response.getWind().getScale()).append("级\n");
            }
            if (response.getWind().getSpeed() != null && response.getWind().getSpeed().getValue() != null) {
                // v1 风速 m/s → v7 km/h（见 V1-UNIT-MAP）
                String kmh = windKmh(response.getWind().getSpeed());
                if (kmh != null) {
                    sb.append("风速: ").append(kmh).append("km/h\n");
                }
            }
        }
        if (response.getVisibility() != null && response.getVisibility().getValue() != null) {
            // v1 能见度 米 → v7 公里（见 V1-UNIT-MAP）
            String km = visKm(response.getVisibility());
            if (km != null) {
                sb.append("能见度: ").append(km).append("km\n");
            }
        }
        if (response.getPressure() != null && response.getPressure().getValue() != null) {
            sb.append("气压: ").append(valueOf(response.getPressure()))
              .append(response.getPressure().getUnit() != null ? response.getPressure().getUnit() : "hPa").append("\n");
        }
        if (response.getCloudCover() != null) {
            sb.append("云量: ").append(pct(response.getCloudCover())).append("%\n");
        }
        if (response.getDewPoint() != null && response.getDewPoint().getValue() != null) {
            sb.append("露点温度: ").append(valueOf(response.getDewPoint()))
              .append(response.getDewPoint().getUnit() != null ? response.getDewPoint().getUnit() : "°C").append("\n");
        }
        // 紫外线指数：v1 新增（v7 的 now 里没有该字段）。
        // 详情页早已能解析 "紫外线:" 并喂给仪表盘/防晒建议（WeatherDetailActivity L1368/L1447/L628），
        // 但此前无数据源导致该仪表盘恒为空 —— 这里补上即激活它。
        if (response.getUvIndex() != null) {
            sb.append("紫外线: ").append(response.getUvIndex()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 解析 v1 小时预报（{@code weatherForecastHourly}）。
     *
     * <p>V1-MIGRATION：v1 与 v7 的结构差异远大于"换类型名"，必须逐字段改写：
     * <ul>
     *   <li>外层：v7 {@code getHourly()} + {@code getFxLink()} → v1 {@code getHours()}，
     *       且**无 fxLink**（改为 metadata.tag，对用户无意义，故不再输出"链接"行）</li>
     *   <li>时间：v7 {@code getFxTime()} → v1 {@code getForecastTime()}</li>
     *   <li>温度/体感/气压/能见度/露点：{@code String} → {@code ValueUnit}，取 {@code getValue()}
     *       （单位以 {@code getUnit()} 为准，避免写死 °C/hPa/km）</li>
     *   <li>天气现象：{@code getText()/getIcon()} → {@code getCondition().getText()/.getCode()}，
     *       且 v1 的图标字段名是 <b>code</b> 非 icon</li>
     *   <li>湿度/云量：v1 是 <b>0~1 小数</b>，需 ×100 才是百分比</li>
     *   <li>风速：v1 是 <b>m/s</b>（v7 为 km/h），需换算后标注</li>
     *   <li>降水概率：v7 {@code getPop()} → v1 {@code PrecipitationHourly}（此处保留其展示文案）</li>
     * </ul>
     * 输出保持与 v7 版本相同的行内格式（"HH:mm 温度°C 天气 图标:x 降水x%"），上层无需改动。
     */
    private String parseHourly(WeatherForecastHourlyResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("24小时预报:\n");

        List<WeatherForecastHourly> hourlyList = response.getHours();
        if (hourlyList != null) {
            for (WeatherForecastHourly hourly : hourlyList) {
                String time = hourly.getForecastTime();
                if (time != null && time.length() >= 16) {
                    sb.append(time.substring(11, 16)).append(" ");
                }
                appendValueUnit(sb, hourly.getTemperature(), "°C", true);
                sb.append(" ");
                if (hourly.getCondition() != null && hourly.getCondition().getText() != null) {
                    sb.append(hourly.getCondition().getText()).append(" ");
                    String code = hourly.getCondition().getCode();
                    if (code != null && !code.isEmpty()) {
                        sb.append("图标:").append(code).append(" ");
                    }
                }
                // 降水概率：v1 是 0~1 小数，×100 还原成 v7 的百分比文案
                if (hourly.getPrecipitation() != null
                        && hourly.getPrecipitation().getProbability() != null) {
                    double pop = hourly.getPrecipitation().getProbability() * 100.0;
                    sb.append("降水").append(Math.round(pop)).append("%");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /** 输出 v1 的 ValueUnit（{@code value + unit}）；label 为期望单位，unitIsFixed=true 时用 label 而非接口 unit */
    private void appendValueUnit(StringBuilder sb, com.qweather.sdk.response.ValueUnit v, String label, boolean unitIsFixed) {
        if (v == null || v.getValue() == null) return;
        double val = v.getValue();
        // 整数则不带小数，避免 "24.0°C" 这类噪声
        String num = (val == Math.floor(val)) ? String.valueOf((long) val) : String.valueOf(val);
        String unit = (unitIsFixed || v.getUnit() == null || v.getUnit().isEmpty()) ? label : v.getUnit();
        sb.append(num).append(unit);
    }

    /**
     * v1 风向方位代码 → v7 中文风名（含"风"字）。
     *
     * <p><b>为什么必须映射</b>：官方文档明确 —— v7 的 {@code windDir} 在中文语言下返回中文
     * （"东南风"）；而 v1 的 {@code direction.compass} 返回**方位代码且为小写英文**
     * （{@code n}/{@code nne}/…/{@code se}/…，见 dev.qweather.com/docs/api/weather/wind-guide/）。
     * 下游（WeatherDetailActivity 等）按 v7 文本格式解析并渲染：
     * {@code windDir.endsWith("风") ? windDir : windDir + " 风"}，
     * 若直接透传代码会显示成 <b>"se 风"</b>。此处统一映射回中文，保持输出契约不变。
     *
     * <p>特殊值：{@code none}（无持续风向）、{@code vrb}（风向变化不定）——v7 对应
     * "无持续风向"/"旋转风"，此处按中文语义输出且**不带"风"字**（避免"无持续风向 风"）。
     */
    private static String compassToChinese(String compass) {
        if (compass == null) return null;
        switch (compass.trim().toLowerCase()) {
            case "n":   return "北风";
            case "nne": return "东北偏北风";
            case "ne":  return "东北风";
            case "ene": return "东北偏东风";
            case "e":   return "东风";
            case "ese": return "东南偏东风";
            case "se":  return "东南风";
            case "sse": return "东南偏南风";
            case "s":   return "南风";
            case "ssw": return "西南偏南风";
            case "sw":  return "西南风";
            case "wsw": return "西南偏西风";
            case "w":   return "西风";
            case "wnw": return "西北偏西风";
            case "nw":  return "西北风";
            case "nnw": return "西北偏北风";
            case "none": return "无持续风向";
            case "vrb":  return "风向变化不定";
            default: return compass; // 未知值原样输出，避免丢信息
        }
    }

    // ============================================================
    // v1 → v7 单位/格式映射表（V1-UNIT-MAP，2026-10-11）
    //
    // 为什么需要这张表：SDK 已迁 v1，但 App 内**另一条 HTTP 回退路径仍是 v7**
    // （WeatherService 在 SDK 失败时回退 AIWeatherManager.getHefengWeather*），
    // 且下游（WeatherDetailActivity 等）的解析与渲染是按 v7 文本契约写死的：
    //   能见度  → replace("km","")，并在显示层硬加 "km"；L2583 还有公里级判断（<1/<5/<10）
    //   风速    → replace("km/h","")，显示层硬加 "km/h"
    //   风向    → endsWith("风") ? : + " 风"
    // 故 v1 数据必须在**解析层归一化回 v7 形态**，两条路径才能输出一致、下游零改动。
    //
    // 依据（官方文档）：
    //   dev.qweather.com/docs/resource/unit/          —— 单位对照 + v1 仅公制
    //   dev.qweather.com/docs/api/weather/wind-guide/ —— 风向方位代码（v1 小写英文 / v7 中文）
    //   dev.qweather.com/docs/api/air-quality/        —— 空气质量 v1（AQI + 带单位的浓度）
    //
    // ── 一、天气三接口 weatherCurrent / weatherForecastHourly / weatherForecastDaily ──
    //  数据        v7(公制)   v1           处理
    //  温度/体感    °C         °C           直通（valueOf）
    //  露点温度     °C         °C           直通（valueOf）
    //  气压         hPa        hPa          直通（valueOf）
    //  降水量       mm         —            v1 无"累计降水量"，见下"降水强度"
    //  降水强度     ✗ v7 无    mm/h         直通（本行标签由"降水量"改为"降水强度"，
    //                                        因 v7 precip=累计量、v1 precipitation.intensity=强度）
    //  风速         km/h       m/s          ×3.6    windKmh
    //  能见度       km         m            ÷1000   visKm
    //  湿度         百分比     [0,1] 小数    ×100    pct
    //  云量         百分比     [0,1] 小数    ×100    pct
    //  降水概率     百分比     [0,1] 小数    ×100    pct
    //  风向         中文风名   小写英文代码   查表    compassToChinese
    //  风向角度     度         度            直通
    //  风力等级     级         级            直通
    //  紫外线       ✗ v7 无    uvIndex       直通（新增可用数据：详情页早已能解析"紫外线:"
    //                                        并渲染仪表盘/防晒建议，此前无数据源故恒为空）
    //
    // ── 二、空气质量 airCurrent / airDaily（v1）──
    //  本接口**无需单位换算**，理由：v1 直接给出成品形态 ——
    //    · AQI 用 aqiDisplay（接口已格式化的字符串），等级/类别/首要污染物均为字符串
    //    · 污染物浓度自带单位字段（conc.getUnit()，如 µg/m³ / mg/m³ / ppm），原样输出
    //    · 健康影响/建议为字符串
    //  唯一处理：浓度数值是 Double，直接用会显示 "46.0"，故经 num() 去除多余小数
    //
    // ── 三、天气预警 weatherAlertCurrent（v1，本轮未改）──
    //  本接口**早已是 v1**，且代码内已有映射，无需再动：
    //    · severity（minor/moderate/severe/extreme）→ convertSeverity() 中文级别
    //    · color.code（blue/yellow/orange/red）      → convertColorCode() 中文颜色
    //
    // ── 四、indices1d / minutely / astronomySun（**仍是 v7**）──
    //  这三个接口 v1 尚未提供（SDK 5.3.0 的方法面里 indices 仍是 v7 语义），
    //  输出天然已是 v7 形态，无需映射。
    // ============================================================

    /** v1 湿度/云量/降水概率（[0,1] 小数）→ v7 百分比字符串 */
    private static String pct(Double v) {
        return v == null ? null : String.valueOf(Math.round(v * 100.0));
    }

    /**
     * v1 数值 → 简洁字符串：整数不带小数（{@code 46.0} → {@code "46"}），非整数保留原值。
     * v1 的浓度/AQI 等字段是 {@code Double}，直接拼串会出现 "PM2.5: 46.0 µg/m³" 这类噪声。
     */
    private static String num(Double v) {
        if (v == null) return null;
        return (v == Math.floor(v) && !v.isInfinite()) ? String.valueOf((long) (double) v) : String.valueOf(v);
    }

    /**
     * v1 能见度（米）→ v7 公里字符串。
     *
     * <p>不信任接口的 {@code unit} 字段做分支 —— 官方明确 v1 为公制固定单位（米），
     * 以数值量级兜底：≥ 100 视为米（÷1000），否则已按公里处理（防上游误传公里）。
     * 输出保留 1 位小数，与 v7 的 "16" / "8.5" 量级一致。
     */
    private static String visKm(com.qweather.sdk.response.ValueUnit v) {
        if (v == null || v.getValue() == null) return null;
        double val = v.getValue();
        double km = val >= 100 ? val / 1000.0 : val;
        if (km == Math.floor(km)) return String.valueOf((long) km);
        return String.format(java.util.Locale.US, "%.1f", km);
    }

    /** v1 风速（m/s）→ v7 公里/小时字符串 */
    private static String windKmh(com.qweather.sdk.response.ValueUnit v) {
        if (v == null || v.getValue() == null) return null;
        double kmh = v.getValue() * 3.6;
        if (kmh == Math.floor(kmh)) return String.valueOf((long) kmh);
        return String.format(java.util.Locale.US, "%.1f", kmh);
    }

    /** 取 v1 的 ValueUnit 数值（整数不带小数，避免 "24.0" 噪声）；null 时返回 null */
    private static String valueOf(com.qweather.sdk.response.ValueUnit v) {
        if (v == null || v.getValue() == null) return null;
        double val = v.getValue();
        return (val == Math.floor(val)) ? String.valueOf((long) val) : String.valueOf(val);
    }

    /** 取一天白/夜时段（v1 把 condition/temperature/wind/humidity 放在 WeatherPeriodForecast 里） */
    private static WeatherPeriodForecast periodOf(WeatherForecastDaily d, boolean day) {
        if (d == null) return null;
        return day ? d.getDaytime() : d.getNighttime();
    }

    private static String periodStr(WeatherForecastDaily d, boolean day, boolean wantText,
                                    boolean wantTemp, boolean wantWind, boolean wantHumidity) {
        WeatherPeriodForecast p = periodOf(d, day);
        if (p == null) return "-";
        if (wantText) {
            return (p.getCondition() != null && p.getCondition().getText() != null)
                    ? p.getCondition().getText() : "-";
        }
        if (wantTemp) {
            String v = valueOf(p.getTemperatureMax());
            return v != null ? v : "-";
        }
        if (wantWind) {
            StringBuilder w = new StringBuilder();
            // 同样要把 v1 的方位代码映射成中文风名（见 compassToChinese 说明）
            String dirZh = (p.getWind() != null && p.getWind().getDirection() != null)
                    ? compassToChinese(p.getWind().getDirection().getCompass()) : null;
            w.append(dirZh != null && !dirZh.isEmpty() ? dirZh : "-");
            w.append(" ");
            w.append(p.getWind() != null && p.getWind().getScale() != null
                    ? p.getWind().getScale() : "-");
            w.append("级");
            return w.toString();
        }
        if (wantHumidity) {
            String v = pct(p.getHumidity());
            return v != null ? v : "-";
        }
        return "-";
    }

    /**
     * 解析 v1 日预报（{@code weatherForecastDaily}）。
     *
     * <p>V1-MIGRATION 字段差异（对照 javap 实测）：
     * <ul>
     *   <li>外层：v7 {@code getDaily()} + fxLink → v1 {@code getDays()}，无 fxLink</li>
     *   <li>日期：{@code getFxDate()} → {@code getForecastStartTime()}（ISO8601，取前 10 位）</li>
     *   <li><b>湿度和云量在时段对象里</b>，不在日顶层：
     *       {@code getDaytime()/getNighttime()} 返回 {@code WeatherPeriodForecast}，
     *       其内含 condition/temperature/wind/precipitation/cloudCover/humidity</li>
     *   <li>温度：顶层 {@code getTemperatureMax()/Min()}（ValueUnit）与时段内同名方法两套，展示用顶层</li>
     *   <li>日出日落：{@code getSunrise()/getSunset()} → {@code getAstro().getSunrise()/.getSunset()}</li>
     *   <li>紫外线：{@code getUvIndex()} → {@code getUvIndexMax()}（Integer）</li>
     *   <li>湿度：v1 是 0~1 小数 → ×100</li>
     * </ul>
     */
    private String parseDaily(WeatherForecastDailyResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("天气预报:\n");

        List<WeatherForecastDaily> dailyList = response.getDays();
        if (dailyList != null) {
            for (WeatherForecastDaily daily : dailyList) {
                String start = daily.getForecastStartTime();
                String date = (start != null && start.length() >= 10) ? start.substring(0, 10) : String.valueOf(start);
                sb.append(date).append("\n");

                // v7 的 白天/夜间 温度来自 getTempMax/getTempMin；v1 的白/夜时段**各自有**
                // 温度（daytime/nighttime 的 temperatureMax/Min），取时段值才符合"白天/夜间温度"语义。
                // 顶层 temperatureMax/Min 是当日总最高/最低，用括号附在行后保留该信息。
                WeatherPeriodForecast dayP = periodOf(daily, true);
                WeatherPeriodForecast nightP = periodOf(daily, false);
                String dayTemp = dayP != null ? valueOf(dayP.getTemperatureMax()) : null;
                String nightTemp = nightP != null ? valueOf(nightP.getTemperatureMin()) : null;
                String dailyMax = valueOf(daily.getTemperatureMax());
                String dailyMin = valueOf(daily.getTemperatureMin());
                sb.append("  白天: ").append(periodStr(daily, true, true, false, false, false))
                  .append(" ").append(dayTemp != null ? dayTemp : "-").append("°C")
                  .append(" (当日 ").append(dailyMax != null ? dailyMax : "-").append("°C)\n");
                sb.append("  夜间: ").append(periodStr(daily, false, true, false, false, false))
                  .append(" ").append(nightTemp != null ? nightTemp : "-").append("°C")
                  .append(" (当日 ").append(dailyMin != null ? dailyMin : "-").append("°C)\n");

                com.qweather.sdk.response.Astro astro = daily.getAstro();
                sb.append("  日出: ").append(astro != null ? astro.getSunrise() : "-")
                  .append("  日落: ").append(astro != null ? astro.getSunset() : "-").append("\n");

                sb.append("  风向: ").append(periodStr(daily, true, false, false, true, false)).append("\n");
                sb.append("  湿度: ").append(periodStr(daily, true, false, false, false, true)).append("%")
                  .append("  紫外线: ").append(daily.getUvIndexMax() != null ? daily.getUvIndexMax() : "-").append("\n");
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private String parseAlerts(WeatherAlertCurrentResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("天气预警:\n");

        List<WeatherAlert> alertList = response.getAlerts();
        if (alertList == null || alertList.isEmpty()) {
            sb.append("暂无预警信息");
        } else {
            for (WeatherAlert alert : alertList) {
                String severity = alert.getSeverity();
                String headline = alert.getHeadline();
                String description = alert.getDescription();
                String instruction = alert.getInstruction();
                String sender = alert.getSenderName();
                String issuedTime = alert.getIssuedTime();
                String effectiveTime = alert.getEffectiveTime();
                String expireTime = alert.getExpireTime();

                String typeName = "";
                WeatherAlertEventType eventType = alert.getEventType();
                if (eventType != null) {
                    typeName = eventType.getName();
                    if (typeName == null) typeName = "";
                }

                // v1 API: severity 是英文值 (minor/moderate/severe/extreme)
                String level = convertSeverity(severity);
                // v1 API: color.code 是英文颜色 (blue/yellow/orange/red)
                String color = "";
                com.qweather.sdk.response.alert.WeatherAlertColor colorObj = alert.getColor();
                if (colorObj != null) {
                    color = convertColorCode(colorObj.getCode());
                }

                if (!typeName.isEmpty()) {
                    if (!level.isEmpty()) {
                        sb.append("【").append(level).append("】").append(typeName);
                    } else {
                        sb.append("【").append(typeName).append("】");
                    }
                    if (!color.isEmpty()) {
                        sb.append("(").append(color).append(")");
                    }
                    sb.append("\n");
                }
                if (headline != null && !headline.isEmpty()) {
                    sb.append("标题: ").append(headline).append("\n");
                }
                if (sender != null && !sender.isEmpty()) {
                    sb.append("发布机构: ").append(sender).append("\n");
                }
                if (!level.isEmpty()) {
                    sb.append("预警等级: ").append(level).append("\n");
                }

                String urgency = alert.getUrgency();
                if (urgency != null && !urgency.isEmpty()) sb.append("紧急程度: ").append(urgency).append("\n");
                String certainty = alert.getCertainty();
                if (certainty != null && !certainty.isEmpty()) sb.append("确定性: ").append(certainty).append("\n");
                String onsetTime = alert.getOnsetTime();
                if (onsetTime != null && !onsetTime.isEmpty()) {
                    sb.append("起始时间: ").append(onsetTime).append("\n");
                }
                String alertId = alert.getId();
                if (alertId != null && !alertId.isEmpty()) {
                    sb.append("预警ID: ").append(alertId).append("\n");
                }
                String icon = alert.getIcon();
                if (icon != null && !icon.isEmpty()) {
                    sb.append("预警图标: ").append(icon).append("\n");
                }

                if (issuedTime != null && !issuedTime.isEmpty()) {
                    sb.append("发布时间: ").append(issuedTime).append("\n");
                }
                if ((effectiveTime != null && !effectiveTime.isEmpty())
                        || (expireTime != null && !expireTime.isEmpty())) {
                    String start = effectiveTime != null ? effectiveTime : "";
                    String end = expireTime != null ? expireTime : "";
                    sb.append("生效时间: ").append(start).append(" ~ ").append(end).append("\n");
                }
                if (description != null && !description.isEmpty()) {
                    sb.append("内容: ").append(description).append("\n");
                }
                if (instruction != null && !instruction.isEmpty()) {
                    sb.append("防御指南:\n").append(instruction).append("\n");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private String convertSeverity(String severity) {
        if (severity == null || severity.isEmpty()) return "";
        switch (severity.toLowerCase()) {
            case "extreme": return "特强";
            case "severe": return "强烈";
            case "moderate": return "中等";
            case "minor": return "轻微";
            default: return severity;
        }
    }

    private String convertColorCode(String code) {
        if (code == null || code.isEmpty()) return "";
        switch (code.toLowerCase()) {
            case "red": return "红色";
            case "orange": return "橙色";
            case "yellow": return "黄色";
            case "blue": return "蓝色";
            case "white": return "白色";
            default: return code;
        }
    }

    private String parseIndices(IndicesDailyResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("生活指数:\n");

        String fxLink = response.getFxLink();
        if (fxLink != null && !fxLink.isEmpty()) {
            sb.append("链接: ").append(fxLink).append("\n");
        }

        List<IndicesDaily> dailyList = response.getDaily();
        if (dailyList != null) {
            for (IndicesDaily daily : dailyList) {
                sb.append(daily.getName()).append(": ").append(daily.getCategory());
                if (daily.getLevel() != null) {
                    sb.append(" (").append(daily.getLevel()).append("级)");
                }
                sb.append("\n");
                if (daily.getText() != null) {
                    sb.append("  ").append(daily.getText()).append("\n");
                }
            }
        }

        return sb.toString();
    }

    private String parseMinutely(MinutelyResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("分钟级降水:\n");

        String fxLink = response.getFxLink();
        if (fxLink != null && !fxLink.isEmpty()) {
            sb.append("链接: ").append(fxLink).append("\n");
        }

        // API 返回的降水摘要
        try {
            String summary = response.getSummary();
            if (summary != null && !summary.isEmpty()) {
                sb.append("摘要: ").append(summary).append("\n");
            }
        } catch (Exception ignored) {}

        List<Minutely> minutelyList = response.getMinutely();
        if (minutelyList == null || minutelyList.isEmpty()) {
            return "分钟级降水:\n暂无数据";
        }

        sb.append("未来2小时每5分钟降水预测:\n");

        for (Minutely item : minutelyList) {
            if (item.getFxTime() != null) {
                String time = item.getFxTime();
                if (time.length() >= 16) {
                    time = time.substring(11, 16);
                }
                String precip = item.getPrecip() != null ? item.getPrecip() : "0";
                String typeText = "rain".equals(item.getType()) ? "雨" : ("snow".equals(item.getType()) ? "雪" : "");
                sb.append(time).append(": ").append(precip).append("mm").append(typeText).append("\n");
            }
        }

        return sb.toString();
    }

    private String parseSun(AstronomySunResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("日出日落:\n");

        String fxLink = response.getFxLink();
        if (fxLink != null && !fxLink.isEmpty()) {
            sb.append("链接: ").append(fxLink).append("\n");
        }

        String sunrise = response.getSunrise();
        if (sunrise != null && !sunrise.isEmpty()) {
            sb.append("日出: ").append(sunrise).append("\n");
        }

        String sunset = response.getSunset();
        if (sunset != null && !sunset.isEmpty()) {
            sb.append("日落: ").append(sunset).append("\n");
        }

        return sb.toString();
    }

    private String parseAirResponse(AirV1CurrentResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("空气质量:\n");

        List<AirIndex> indexes = response.getIndexes();
        if (indexes != null && !indexes.isEmpty()) {
            AirIndex index = indexes.get(0);
            String aqiDisplay = index.getAqiDisplay();
            String level = index.getLevel();
            String category = index.getCategory();

            if (aqiDisplay != null && !aqiDisplay.isEmpty()) {
                sb.append("AQI: ").append(aqiDisplay);
                if (level != null && !level.isEmpty()) {
                    sb.append(" (等级").append(level);
                    if (category != null && !category.isEmpty()) {
                        sb.append(", ").append(category);
                    }
                    sb.append(")");
                }
                sb.append("\n");
            }

            String idxName = index.getName();
            String idxCode = index.getCode();
            if (idxName != null && !idxName.isEmpty()) sb.append("指数名称: ").append(idxName).append("\n");
            if (idxCode != null && !idxCode.isEmpty()) sb.append("指数代码: ").append(idxCode).append("\n");

            PrimaryPollutant primary = index.getPrimaryPollutant();
            if (primary != null) {
                String primaryName = primary.getName();
                if (primaryName != null && !primaryName.isEmpty()) {
                    sb.append("首要污染物: ").append(primaryName).append("\n");
                }
            }

            Health health = index.getHealth();
            if (health != null) {
                String effect = health.getEffect();
                if (effect != null && !effect.isEmpty()) {
                    sb.append("健康影响: ").append(effect).append("\n");
                }
                HealthAdvice advice = health.getAdvice();
                if (advice != null) {
                    String general = advice.getGeneralPopulation();
                    String sensitive = advice.getSensitivePopulation();
                    if (general != null && !general.isEmpty()) sb.append("一般人群: ").append(general).append("\n");
                    if (sensitive != null && !sensitive.isEmpty()) sb.append("敏感人群: ").append(sensitive).append("\n");
                }
            }
        }

        List<Pollutant> pollutants = response.getPollutants();
        if (pollutants != null && !pollutants.isEmpty()) {
            sb.append("\n污染物浓度:\n");
            for (Pollutant p : pollutants) {
                String code = p.getCode();
                String name = p.getName();
                PollutantConcentration conc = p.getConcentration();
                if (conc != null) {
                    Double value = conc.getValue();
                    String unit = conc.getUnit();
                    if (value != null) {
                        String displayName = (name != null && !name.isEmpty()) ? name.toUpperCase() : (code != null ? code.toUpperCase() : "");
                        String displayUnit = (unit != null && !unit.isEmpty()) ? " " + unit : "";
                        sb.append(displayName).append(": ").append(num(value)).append(displayUnit);
                        String fullName = p.getFullName();
                        if (fullName != null && !fullName.isEmpty() && !fullName.equals(displayName)) {
                            sb.append(" (").append(fullName).append(")");
                        }
                        sb.append("\n");
                    }
                }
            }
        }

        if (sb.toString().equals("空气质量:\n")) {
            sb.append("暂无空气质量数据\n");
        }

        return sb.toString();
    }

    private void appendField(StringBuilder sb, String name, String value, String unit) {
        if (value != null && !value.isEmpty()) {
            sb.append(name).append(": ").append(value).append(unit).append("\n");
        }
    }

    private String generateJwtToken() {
        try {
            QWeatherJwtGenerator jwtGen = new QWeatherJwtGenerator(
                    DEFAULT_QWEATHER_PRIVATE_KEY,
                    DEFAULT_QWEATHER_PROJECT_ID,
                    DEFAULT_QWEATHER_KID
            );
            return jwtGen.getToken();
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate JWT token", e);
            return null;
        }
    }

    private String parseAirV7Response(String response) {
        try {
            com.google.gson.JsonObject json = new com.google.gson.JsonParser().parse(response).getAsJsonObject();

            String code = json.has("code") ? json.get("code").getAsString() : "";
            if (!"200".equals(code) && !code.isEmpty()) {
                String msg = json.has("message") ? json.get("message").getAsString() : "未知错误";
                return "空气质量: 错误 " + code + " - " + msg;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("空气质量:\n");

            if (json.has("now") && !json.get("now").isJsonNull()) {
                com.google.gson.JsonObject now = json.getAsJsonObject("now");

                String aqi = now.has("aqi") ? now.get("aqi").getAsString() : "";
                String level = now.has("level") ? now.get("level").getAsString() : "";
                String category = now.has("category") ? now.get("category").getAsString() : "";
                String primary = now.has("primary") ? now.get("primary").getAsString() : "";

                if (!aqi.isEmpty()) {
                    sb.append("  AQI: ").append(aqi);
                    if (!level.isEmpty()) sb.append(" (等级").append(level);
                    if (!category.isEmpty()) sb.append(", ").append(category).append(")");
                    sb.append("\n");
                }

                if (!primary.isEmpty()) sb.append("  首要污染物: ").append(primary).append("\n");

                sb.append("\n污染物浓度:\n");

                String pm10 = now.has("pm10") ? now.get("pm10").getAsString() : "";
                if (!pm10.isEmpty()) sb.append("  PM10: ").append(pm10).append(" μg/m³\n");

                String pm2p5 = now.has("pm2p5") ? now.get("pm2p5").getAsString() : "";
                if (!pm2p5.isEmpty()) sb.append("  PM2.5: ").append(pm2p5).append(" μg/m³\n");

                String no2 = now.has("no2") ? now.get("no2").getAsString() : "";
                if (!no2.isEmpty()) sb.append("  NO2: ").append(no2).append(" μg/m³\n");

                String so2 = now.has("so2") ? now.get("so2").getAsString() : "";
                if (!so2.isEmpty()) sb.append("  SO2: ").append(so2).append(" μg/m³\n");

                String co = now.has("co") ? now.get("co").getAsString() : "";
                if (!co.isEmpty()) sb.append("  CO: ").append(co).append(" mg/m³\n");

                String o3 = now.has("o3") ? now.get("o3").getAsString() : "";
                if (!o3.isEmpty()) sb.append("  O3: ").append(o3).append(" μg/m³\n");
            }

            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse air v7 response", e);
            return "空气质量解析失败: " + e.getMessage();
        }
    }
}
