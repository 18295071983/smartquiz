package com.oilquiz.app.weather;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.util.APIKeyManager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class QWeatherSdkManager {

    private static final String TAG = "QWeatherSdkManager";
    private static final int TIMEOUT_SECONDS = 30;

    // Default QWeather JWT credentials
    private static final String DEFAULT_QWEATHER_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2VwBCIEIA9Gw1Of0+TGrE3/tdXfmthWrhNE92KwaCeknzauUu+T\n-----END PRIVATE KEY-----";
    private static final String DEFAULT_QWEATHER_PROJECT_ID = "2A89PF2EBQ";
    private static final String DEFAULT_QWEATHER_KID = "TGGVDKVJGN";
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

    /**
     * Initialize QWeather SDK with explicit credentials.
     */
    public void initialize(String host, String privateKey, String projectId, String kid) {
        try {
            // SDK expects host WITHOUT https:// prefix
            String sdkHost = host;
            if (sdkHost.startsWith("https://")) {
                sdkHost = sdkHost.substring(8);
            } else if (sdkHost.startsWith("http://")) {
                sdkHost = sdkHost.substring(7);
            }
            this.apiHost = sdkHost;
            Log.d(TAG, "Initializing SDK with host: " + sdkHost);

            Class<?> qweatherClass = Class.forName("com.qweather.sdk.QWeather");
            java.lang.reflect.Method getInstanceMethod = qweatherClass.getMethod("getInstance", android.content.Context.class, String.class);
            Object qweather = getInstanceMethod.invoke(null, context, sdkHost);
            invokeMethod(qweather, "setLogEnable", false);

            // Use our own TokenGenerator with BouncyCastle Ed25519 instead of SDK's JWTGenerator
            com.qweather.sdk.TokenGenerator tokenGenerator = new com.qweather.sdk.TokenGenerator() {
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

            Class<?> tokenGeneratorInterface = Class.forName("com.qweather.sdk.TokenGenerator");
            java.lang.reflect.Method setTokenMethod = qweatherClass.getMethod("setTokenGenerator", tokenGeneratorInterface);
            setTokenMethod.invoke(qweather, tokenGenerator);

            initialized = true;
            Log.d(TAG, "QWeather SDK initialized successfully with custom TokenGenerator");
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize QWeather SDK", e);
            initialized = false;
        }
    }

    /**
     * Initialize QWeather SDK using saved JWT credentials from APIKeyManager.
     * Call this after APIKeyManager has QWeather JWT credentials saved.
     */
    public boolean initializeFromStorage() {
        APIKeyManager apiKeyManager = APIKeyManager.getInstance(context);

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
            apiHost = DEFAULT_QWEATHER_API_HOST;
        }

        initialize(apiHost, privateKey, projectId, kid);
        return initialized;
    }

    public CompletableFuture<String> getCurrentWeather(String location) {
        if (!initialized) {
            return CompletableFuture.completedFuture("天气SDK未初始化");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            Class<?> weatherParamClass = Class.forName("com.qweather.sdk.parameter.weather.WeatherParameter");
            Object parameter = newInstance(weatherParamClass, location);
            invokeMethod(parameter, "lang", getEnumValue("com.qweather.sdk.basic.Lang", "ZH_HANS"));
            invokeMethod(parameter, "unit", getEnumValue("com.qweather.sdk.basic.Unit", "METRIC"));

            Object qweather = getQWeatherInstance();
            Log.d(TAG, "QWeather instance class: " + qweather.getClass().getName());

            SyncCallback<Object> callback = new SyncCallback<>(future, latch, "parseWeatherNow");
            Log.d(TAG, "Callback class: " + callback.getClass().getName()
                + ", implements Callback: " + com.qweather.sdk.Callback.class.isInstance(callback));

            invokeMethod(qweather, "weatherNow", parameter, callback);
            Log.d(TAG, "weatherNow invoked successfully");

            new Thread(() -> {
                try {
                    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (!completed) {
                        future.complete("天气查询超时");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();

        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Log.e(TAG, "Failed to call weatherNow: " + cause.getClass().getName() + ": " + cause.getMessage(), cause);
            future.complete("天气查询失败: " + cause.getMessage());
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
            Class<?> weatherParamClass = Class.forName("com.qweather.sdk.parameter.weather.WeatherParameter");
            Object parameter = newInstance(weatherParamClass, location);
            invokeMethod(parameter, "lang", getEnumValue("com.qweather.sdk.basic.Lang", "ZH_HANS"));
            invokeMethod(parameter, "unit", getEnumValue("com.qweather.sdk.basic.Unit", "METRIC"));

            Object qweather = getQWeatherInstance();

            invokeMethod(qweather, "weather24h", parameter, new SyncCallback<>(future, latch, "parseHourly"));

            new Thread(() -> {
                try {
                    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();

        } catch (Exception e) {
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
            Class<?> weatherParamClass = Class.forName("com.qweather.sdk.parameter.weather.WeatherParameter");
            Object parameter = newInstance(weatherParamClass, location);
            invokeMethod(parameter, "lang", getEnumValue("com.qweather.sdk.basic.Lang", "ZH_HANS"));
            invokeMethod(parameter, "unit", getEnumValue("com.qweather.sdk.basic.Unit", "METRIC"));

            Object qweather = getQWeatherInstance();

            invokeMethod(qweather, "weather7d", parameter, new SyncCallback<>(future, latch, "parseDaily"));

            new Thread(() -> {
                try {
                    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();

        } catch (Exception e) {
            future.complete("天气预报查询失败: " + e.getMessage());
            latch.countDown();
        }

        return future;
    }

    public CompletableFuture<String> getAirQuality(String location) {
        if (!initialized) {
            return CompletableFuture.completedFuture("空气质量SDK未初始化");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            Class<?> airParamClass = Class.forName("com.qweather.sdk.parameter.air.AirParameter");
            Object parameter = newInstance(airParamClass, location);
            invokeMethod(parameter, "lang", getEnumValue("com.qweather.sdk.basic.Lang", "ZH_HANS"));

            Object qweather = getQWeatherInstance();

            invokeMethod(qweather, "airNow", parameter, new SyncCallback<>(future, latch, "parseAir"));

            new Thread(() -> {
                try {
                    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();

        } catch (Exception e) {
            future.complete("空气质量查询失败: " + e.getMessage());
            latch.countDown();
        }

        return future;
    }

    public CompletableFuture<String> getWeatherAlerts(String location) {
        if (!initialized) {
            return CompletableFuture.completedFuture("天气预警SDK未初始化");
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);

        try {
            Class<?> warningParamClass = Class.forName("com.qweather.sdk.parameter.warning.WarningNowParameter");
            Object parameter = newInstance(warningParamClass, location);

            Object qweather = getQWeatherInstance();

            invokeMethod(qweather, "warningNow", parameter, new SyncCallback<>(future, latch, "parseAlerts"));

            new Thread(() -> {
                try {
                    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();

        } catch (Exception e) {
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
            Class<?> indicesParamClass = Class.forName("com.qweather.sdk.parameter.indices.IndicesParameter");
            Object parameter = newInstance(indicesParamClass, location, new String[]{"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17"});
            invokeMethod(parameter, "lang", getEnumValue("com.qweather.sdk.basic.Lang", "ZH_HANS"));

            Object qweather = getQWeatherInstance();

            invokeMethod(qweather, "indices1d", parameter, new SyncCallback<>(future, latch, "parseIndices"));

            new Thread(() -> {
                try {
                    latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();

        } catch (Exception e) {
            future.complete("生活指数查询失败: " + e.getMessage());
            latch.countDown();
        }

        return future;
    }

    private String parseWeatherNow(Object response) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("天气信息:\n");

            Object location = invokeMethod(response, "getLocation");
            if (location != null) {
                Object name = invokeMethod(location, "getName");
                if (name != null) {
                    sb.append("城市: ").append(name).append("\n");
                }
            }

            Object now = invokeMethod(response, "getNow");
            if (now != null) {
                Object text = invokeMethod(now, "getText");
                if (text != null) sb.append("天气: ").append(text).append("\n");
                
                Object icon = invokeMethod(now, "getIcon");
                if (icon != null) sb.append("图标: ").append(icon).append("\n");
                
                Object temp = invokeMethod(now, "getTemp");
                if (temp != null) sb.append("温度: ").append(temp).append("°C\n");
                
                Object feelsLike = invokeMethod(now, "getFeelsLike");
                if (feelsLike != null) sb.append("体感温度: ").append(feelsLike).append("°C\n");
                
                Object humidity = invokeMethod(now, "getHumidity");
                if (humidity != null) sb.append("湿度: ").append(humidity).append("%\n");
                
                Object windSpeed = invokeMethod(now, "getWindSpeed");
                if (windSpeed != null) sb.append("风速: ").append(windSpeed).append(" km/h\n");
                
                Object windDir = invokeMethod(now, "getWindDir");
                if (windDir != null) sb.append("风向: ").append(windDir).append("\n");
                
                Object vis = invokeMethod(now, "getVis");
                if (vis != null) sb.append("能见度: ").append(vis).append(" km\n");
            }

            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse weather now", e);
            return "天气信息解析失败";
        }
    }

    private String parseHourly(Object response) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("24小时预报:\n");

            Object hourlyList = invokeMethod(response, "getHourly");
            if (hourlyList != null && hourlyList instanceof java.util.List) {
                for (Object hourly : (java.util.List<?>) hourlyList) {
                    Object fxTime = invokeMethod(hourly, "getFxTime");
                    if (fxTime != null) {
                        String time = fxTime.toString();
                        if (time.length() >= 16) {
                            sb.append("时间: ").append(time.substring(11, 16)).append("\n");
                        }
                    }
                    
                    Object temp = invokeMethod(hourly, "getTemp");
                    if (temp != null) sb.append("温度: ").append(temp).append("°C\n");
                    
                    Object text = invokeMethod(hourly, "getText");
                    if (text != null) sb.append("天气: ").append(text).append("\n");
                    
                    Object pop = invokeMethod(hourly, "getPop");
                    if (pop != null) sb.append("降水: ").append(pop).append("%\n");
                    
                    Object icon = invokeMethod(hourly, "getIcon");
                    if (icon != null) sb.append("图标: ").append(icon).append("\n");
                    
                    sb.append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse hourly", e);
            return "小时预报解析失败";
        }
    }

    private String parseDaily(Object response) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("天气预报:\n");

            Object dailyList = invokeMethod(response, "getDaily");
            if (dailyList != null && dailyList instanceof java.util.List) {
                for (Object daily : (java.util.List<?>) dailyList) {
                    Object fxDate = invokeMethod(daily, "getFxDate");
                    if (fxDate != null) sb.append("日期: ").append(fxDate).append("\n");
                    
                    Object tempMax = invokeMethod(daily, "getTempMax");
                    if (tempMax != null) sb.append("最高温度: ").append(tempMax).append("°C\n");
                    
                    Object tempMin = invokeMethod(daily, "getTempMin");
                    if (tempMin != null) sb.append("最低温度: ").append(tempMin).append("°C\n");
                    
                    Object textDay = invokeMethod(daily, "getTextDay");
                    if (textDay != null) sb.append("白天天气: ").append(textDay).append("\n");
                    
                    Object textNight = invokeMethod(daily, "getTextNight");
                    if (textNight != null) sb.append("夜间天气: ").append(textNight).append("\n");
                    
                    sb.append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse daily", e);
            return "天气预报解析失败";
        }
    }

    private String parseAir(Object response) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("空气质量:\n");

            Object now = invokeMethod(response, "getNow");
            if (now != null) {
                Object aqi = invokeMethod(now, "getAqi");
                Object level = invokeMethod(now, "getLevel");
                Object category = invokeMethod(now, "getCategory");
                
                if (aqi != null) {
                    sb.append("  AQI: ").append(aqi);
                    if (level != null) sb.append(" (等级").append(level);
                    if (category != null) sb.append(", ").append(category).append(")");
                    sb.append("\n");
                }

                Object primary = invokeMethod(now, "getPrimary");
                if (primary != null) sb.append("  首要污染物: ").append(primary).append("\n");

                sb.append("\n污染物浓度:\n");
                Object pm10 = invokeMethod(now, "getPm10");
                if (pm10 != null) sb.append("  PM10: ").append(pm10).append(" μg/m³\n");
                
                Object pm2p5 = invokeMethod(now, "getPm2p5");
                if (pm2p5 != null) sb.append("  PM2.5: ").append(pm2p5).append(" μg/m³\n");
                
                Object no2 = invokeMethod(now, "getNo2");
                if (no2 != null) sb.append("  NO2: ").append(no2).append(" μg/m³\n");
                
                Object so2 = invokeMethod(now, "getSo2");
                if (so2 != null) sb.append("  SO2: ").append(so2).append(" μg/m³\n");
                
                Object co = invokeMethod(now, "getCo");
                if (co != null) sb.append("  CO: ").append(co).append(" mg/m³\n");
                
                Object o3 = invokeMethod(now, "getO3");
                if (o3 != null) sb.append("  O3: ").append(o3).append(" μg/m³\n");
            }

            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse air", e);
            return "空气质量解析失败";
        }
    }

    private String parseAlerts(Object response) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("天气预警:\n");

            Object warningList = invokeMethod(response, "getWarning");
            if (warningList != null && warningList instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) warningList;
                if (list.isEmpty()) {
                    sb.append("暂无预警信息\n");
                } else {
                    for (Object warning : list) {
                        Object title = invokeMethod(warning, "getTitle");
                        if (title != null) sb.append("标题: ").append(title).append("\n");
                        
                        Object severity = invokeMethod(warning, "getSeverity");
                        if (severity != null) sb.append("预警等级: ").append(severity).append("\n");
                        
                        Object text = invokeMethod(warning, "getText");
                        if (text != null) sb.append("描述: ").append(text).append("\n");
                        
                        sb.append("\n");
                    }
                }
            } else {
                sb.append("暂无预警信息\n");
            }

            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse alerts", e);
            return "天气预警解析失败";
        }
    }

    private String parseIndices(Object response) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("生活指数:\n");

            Object dailyList = invokeMethod(response, "getDaily");
            if (dailyList != null && dailyList instanceof java.util.List) {
                for (Object daily : (java.util.List<?>) dailyList) {
                    Object name = invokeMethod(daily, "getName");
                    if (name != null) {
                        sb.append("  ").append(name).append(": ");
                        
                        Object category = invokeMethod(daily, "getCategory");
                        if (category != null) {
                            sb.append(category);
                            Object level = invokeMethod(daily, "getLevel");
                            if (level != null) sb.append("(等级").append(level).append(")");
                        }
                        sb.append("\n");
                        
                        Object text = invokeMethod(daily, "getText");
                        if (text != null) sb.append("    ").append(text).append("\n");
                        
                        sb.append("\n");
                    }
                }
            }

            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse indices", e);
            return "生活指数解析失败";
        }
    }

    /**
     * Get QWeather SDK instance using stored apiHost.
     */
    private Object getQWeatherInstance() throws Exception {
        Class<?> qweatherClass = Class.forName("com.qweather.sdk.QWeather");
        java.lang.reflect.Method getInstanceMethod = qweatherClass.getMethod("getInstance", android.content.Context.class, String.class);
        return getInstanceMethod.invoke(null, context, apiHost);
    }

    private Object invokeStaticMethod(Class<?> clazz, String methodName, Object... args) throws Exception {
        if (args == null || args.length == 0) {
            return clazz.getMethod(methodName).invoke(null);
        }
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = args[i].getClass();
        }
        return clazz.getMethod(methodName, paramTypes).invoke(null, args);
    }

    private Object invokeMethod(Object obj, String methodName, Object... args) throws Exception {
        if (obj == null) return null;
        if (args == null || args.length == 0) {
            return obj.getClass().getMethod(methodName).invoke(obj);
        }
        // Try to find the method by iterating through declared methods
        for (java.lang.reflect.Method method : obj.getClass().getMethods()) {
            if (!method.getName().equals(methodName)) continue;
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length != args.length) continue;

            boolean match = true;
            for (int i = 0; i < paramTypes.length; i++) {
                Class<?> paramType = paramTypes[i];
                Object arg = args[i];

                // Handle primitive types
                if (paramType == boolean.class && arg instanceof Boolean) {
                    continue;
                } else if (paramType == int.class && arg instanceof Integer) {
                    continue;
                } else if (paramType == long.class && arg instanceof Long) {
                    continue;
                } else if (paramType == float.class && arg instanceof Float) {
                    continue;
                } else if (paramType == double.class && arg instanceof Double) {
                    continue;
                } else if (paramType.isInstance(arg)) {
                    continue;
                } else {
                    match = false;
                    break;
                }
            }
            if (match) {
                return method.invoke(obj, args);
            }
        }
        throw new NoSuchMethodException(obj.getClass().getName() + "." + methodName + " with matching parameters");
    }

    private Object newInstance(Class<?> clazz, Object... args) throws Exception {
        if (args == null || args.length == 0) {
            return clazz.getConstructor().newInstance();
        }
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = args[i].getClass();
        }
        return clazz.getConstructor(paramTypes).newInstance(args);
    }

    private Object getEnumValue(String className, String enumName) throws Exception {
        Class<?> enumClass = Class.forName(className);
        return enumClass.getField(enumName).get(null);
    }

    private class SyncCallback<T> implements com.qweather.sdk.Callback<T> {
        private final CompletableFuture<String> future;
        private final CountDownLatch latch;
        private final String parseMethod;

        SyncCallback(CompletableFuture<String> future, CountDownLatch latch, String parseMethod) {
            this.future = future;
            this.latch = latch;
            this.parseMethod = parseMethod;
        }

        @Override
        public void onSuccess(T response) {
            Log.d(TAG, "SDK onSuccess called, response class: " + (response != null ? response.getClass().getName() : "null"));
            try {
                if (response == null) {
                    future.complete("天气数据为空");
                    return;
                }
                String result = (String) invokeMethod(QWeatherSdkManager.this, parseMethod, response);
                Log.d(TAG, "Parse result: " + (result != null ? result.substring(0, Math.min(50, result.length())) : "null"));
                future.complete(result);
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse response", e);
                future.complete("解析失败: " + e.getMessage());
            } finally {
                latch.countDown();
            }
        }

        @Override
        public void onFailure(com.qweather.sdk.response.error.ErrorResponse errorResponse) {
            Log.w(TAG, "SDK onFailure called");
            try {
                Object error = invokeMethod(errorResponse, "getError");
                if (error != null) {
                    Object status = invokeMethod(error, "getStatus");
                    Object title = invokeMethod(error, "getTitle");
                    Object detail = invokeMethod(error, "getDetail");
                    Log.w(TAG, "Error: status=" + status + ", title=" + title + ", detail=" + detail);
                    future.complete("查询失败: " + (title != null ? title : "") + " " + (detail != null ? detail : ""));
                } else {
                    // Fallback: try toString
                    String responseStr = errorResponse.toString();
                    Log.w(TAG, "ErrorResponse toString: " + responseStr);
                    future.complete("查询失败: " + responseStr);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse error", e);
                future.complete("查询失败: " + e.getMessage());
            } finally {
                latch.countDown();
            }
        }

        @Override
        public void onException(Throwable e) {
            Log.e(TAG, "SDK onException called", e);
            future.complete("查询异常: " + e.getMessage());
            latch.countDown();
        }
    }
}