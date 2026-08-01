package com.oilquiz.app.weather;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.weather.model.WeatherNowData;
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
import com.qweather.sdk.parameter.weather.WeatherParameter;
import com.qweather.sdk.response.air.AirNowResponse;
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
import com.qweather.sdk.response.weather.WeatherDaily;
import com.qweather.sdk.response.weather.WeatherDailyResponse;
import com.qweather.sdk.response.weather.WeatherHourly;
import com.qweather.sdk.response.weather.WeatherHourlyResponse;
import com.qweather.sdk.response.weather.WeatherNow;
import com.qweather.sdk.response.weather.WeatherNowResponse;

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

    public CompletableFuture<String> getCurrentWeather(String location) {
        return getCurrentWeather(location, null);
    }

    public CompletableFuture<String> getCurrentWeather(String location, String cityName) {
        if (!initialized) {
            return CompletableFuture.completedFuture("天气SDK未初始化");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            WeatherParameter parameter = new WeatherParameter(location)
                    .lang(Lang.ZH_HANS)
                    .unit(Unit.METRIC);

            QWeather.getInstance(context, apiHost).weatherNow(parameter, new Callback<WeatherNowResponse>() {
                @Override
                public void onSuccess(WeatherNowResponse response) {
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

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            WeatherParameter parameter = new WeatherParameter(location)
                    .lang(Lang.ZH_HANS)
                    .unit(Unit.METRIC);

            QWeather.getInstance(context, apiHost).weather24h(parameter, new Callback<WeatherHourlyResponse>() {
                @Override
                public void onSuccess(WeatherHourlyResponse response) {
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
            Log.e(TAG, "Failed to call weather24h", e);
            future.complete("小时预报查询失败: " + e.getMessage());
            latch.countDown();
        }

        return future;
    }

    public CompletableFuture<String> getDailyForecast(String location) {
        if (!initialized) {
            return CompletableFuture.completedFuture("天气预报SDK未初始化");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            WeatherParameter parameter = new WeatherParameter(location)
                    .lang(Lang.ZH_HANS)
                    .unit(Unit.METRIC);

            QWeather.getInstance(context, apiHost).weather7d(parameter, new Callback<WeatherDailyResponse>() {
                @Override
                public void onSuccess(WeatherDailyResponse response) {
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

    private String parseWeatherNow(WeatherNowResponse response, String cityName) {
        StringBuilder sb = new StringBuilder();
        sb.append("天气信息:\n");

        if (cityName != null && !cityName.isEmpty()) {
            sb.append("城市: ").append(cityName).append("\n");
        }

        String fxLink = response.getFxLink();
        if (fxLink != null && !fxLink.isEmpty()) {
            sb.append("链接: ").append(fxLink).append("\n");
        }

        WeatherNow now = response.getNow();
        if (now != null) {
            if (now.getObsTime() != null && !now.getObsTime().isEmpty()) {
                sb.append("观测时间: ").append(now.getObsTime()).append("\n");
            }
            sb.append("天气: ").append(now.getText()).append("\n");
            if (now.getIcon() != null && !now.getIcon().isEmpty()) {
                sb.append("图标: ").append(now.getIcon()).append("\n");
            }
            sb.append("温度: ").append(now.getTemp()).append("°C\n");
            sb.append("体感温度: ").append(now.getFeelsLike()).append("°C\n");
            sb.append("湿度: ").append(now.getHumidity()).append("%\n");
            if (now.getPrecip() != null && !now.getPrecip().isEmpty()) {
                sb.append("降水量: ").append(now.getPrecip()).append("mm\n");
            }
            sb.append("风向: ").append(now.getWindDir()).append("\n");
            if (now.getWind360() != null && !now.getWind360().isEmpty()) {
                sb.append("风向角度: ").append(now.getWind360()).append("°\n");
            }
            sb.append("风力: ").append(now.getWindScale()).append("级\n");
            sb.append("风速: ").append(now.getWindSpeed()).append("km/h\n");
            sb.append("能见度: ").append(now.getVis()).append("km\n");
            sb.append("气压: ").append(now.getPressure()).append("hPa\n");
            if (now.getCloud() != null && !now.getCloud().isEmpty()) {
                sb.append("云量: ").append(now.getCloud()).append("%\n");
            }
            if (now.getDew() != null && !now.getDew().isEmpty()) {
                sb.append("露点温度: ").append(now.getDew()).append("°C\n");
            }
        }

        String updateTime = response.getUpdateTime();
        if (updateTime != null && !updateTime.isEmpty()) {
            sb.append("更新时间: ").append(updateTime).append("\n");
        }

        return sb.toString();
    }

    public WeatherNowData getWeatherNowData(WeatherNowResponse response, String cityName, WeatherDaily dailyForecast, AstronomySunResponse sunResponse, AirNowResponse airResponse) {
        WeatherNowData data = WeatherDataParser.getEmptyData();

        data.cityName = cityName != null ? cityName : "";
        data.fxLink = response.getFxLink() != null ? response.getFxLink() : "";
        data.updateTime = response.getUpdateTime() != null ? response.getUpdateTime() : "";

        WeatherNow now = response.getNow();
        if (now != null) {
            data.obsTime = now.getObsTime() != null ? now.getObsTime() : "";
            data.temp = now.getTemp() != null ? now.getTemp() : "";
            data.feelsLike = now.getFeelsLike() != null ? now.getFeelsLike() : "";
            data.iconCode = now.getIcon() != null ? now.getIcon() : "";
            data.text = now.getText() != null ? now.getText() : "";
            data.wind360 = now.getWind360() != null ? now.getWind360() : "";
            data.windDir = now.getWindDir() != null ? now.getWindDir() : "";
            data.windScale = now.getWindScale() != null ? now.getWindScale() : "";
            data.windSpeed = now.getWindSpeed() != null ? now.getWindSpeed() : "";
            data.humidity = now.getHumidity() != null ? now.getHumidity() : "";
            data.precip = now.getPrecip() != null ? now.getPrecip() : "";
            data.pressure = now.getPressure() != null ? now.getPressure() : "";
            data.visibility = now.getVis() != null ? now.getVis() : "";
            data.cloud = now.getCloud() != null ? now.getCloud() : "";
            data.dew = now.getDew() != null ? now.getDew() : "";
        }

        if (dailyForecast != null) {
            data.highTemp = dailyForecast.getTempMax() != null ? dailyForecast.getTempMax() : "";
            data.lowTemp = dailyForecast.getTempMin() != null ? dailyForecast.getTempMin() : "";
            data.uvIndex = dailyForecast.getUvIndex() != null ? dailyForecast.getUvIndex() : "";
        }

        if (sunResponse != null) {
            data.sunRise = sunResponse.getSunrise() != null ? sunResponse.getSunrise() : "";
            data.sunSet = sunResponse.getSunset() != null ? sunResponse.getSunset() : "";
        }

        return data;
    }

    private String parseHourly(WeatherHourlyResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("24小时预报:\n");

        String fxLink = response.getFxLink();
        if (fxLink != null && !fxLink.isEmpty()) {
            sb.append("链接: ").append(fxLink).append("\n");
        }

        List<WeatherHourly> hourlyList = response.getHourly();
        if (hourlyList != null) {
            for (WeatherHourly hourly : hourlyList) {
                if (hourly.getFxTime() != null) {
                    String time = hourly.getFxTime();
                    if (time.length() >= 16) {
                        sb.append(time.substring(11, 16)).append(" ");
                    }
                }
                sb.append(hourly.getTemp()).append("°C ");
                sb.append(hourly.getText()).append(" ");
                String icon = hourly.getIcon();
                if (icon != null && !icon.isEmpty()) {
                    sb.append("图标:").append(icon).append(" ");
                }
                if (hourly.getPop() != null) {
                    sb.append("降水").append(hourly.getPop()).append("%");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private String parseDaily(WeatherDailyResponse response) {
        StringBuilder sb = new StringBuilder();
        sb.append("天气预报:\n");

        String fxLink = response.getFxLink();
        if (fxLink != null && !fxLink.isEmpty()) {
            sb.append("链接: ").append(fxLink).append("\n");
        }

        List<WeatherDaily> dailyList = response.getDaily();
        if (dailyList != null) {
            for (WeatherDaily daily : dailyList) {
                sb.append(daily.getFxDate()).append("\n");
                sb.append("  白天: ").append(daily.getTextDay()).append(" ").append(daily.getTempMax()).append("°C\n");
                sb.append("  夜间: ").append(daily.getTextNight()).append(" ").append(daily.getTempMin()).append("°C\n");
                sb.append("  日出: ").append(daily.getSunrise()).append("  日落: ").append(daily.getSunset()).append("\n");
                sb.append("  风向: ").append(daily.getWindDirDay()).append(" ").append(daily.getWindScaleDay()).append("级\n");
                sb.append("  湿度: ").append(daily.getHumidity()).append("%  紫外线: ").append(daily.getUvIndex()).append("\n");
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
                        sb.append(displayName).append(": ").append(value).append(displayUnit);
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
