package com.oilquiz.app.weather;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.tool.AIWeatherManager;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

public class WeatherService {

    private static final String TAG = "WeatherService";
    
    /** 实时天气缓存时长：5 分钟（与横幅刷新周期一致，保证手动刷新/重进页面能及时看到新数据） */
    private static final long CACHE_DURATION_NOW = 5 * 60 * 1000;
    private static final long CACHE_DURATION_FORECAST = 3 * 60 * 60 * 1000;
    private static final long CACHE_DURATION_AIR = 30 * 60 * 1000;
    private static final long CACHE_DURATION_ALERTS = 10 * 60 * 1000;
    private static final long CACHE_DURATION_HOURLY = 30 * 60 * 1000;
    private static final long CACHE_DURATION_INDICES = 8 * 60 * 60 * 1000;
    private static final long CACHE_DURATION_MINUTELY = 5 * 60 * 1000;

    private static String locationKey(double lat, double lon) {
        return String.format(java.util.Locale.US, "%.2f_%.2f", lat, lon);
    }

    /** 检查响应是否为错误，错误响应不缓存 */
    private static boolean isValidForCache(String data) {
        if (data == null || data.isEmpty()) return false;
        String lower = data.toLowerCase();
        return !data.contains("失败") && !data.contains("暂无权限") && !data.contains("无权限")
            && !data.contains("错误") && !data.contains("403")
            && !data.contains("查询失败") && !data.contains("请稍后")
            && !lower.contains("error") && !lower.contains("forbidden");
    }

    /**
     * 校验 SDK 空气质量/预报返回是否为真实错误。
     * 注意不能用 contains("异常")——SDK 健康建议文本含"极少数异常敏感人群"，
     * 会把成功数据误判为失败（曾导致成功结果被丢弃后回退 HTTP 遇 403）。
     * 只认错误前缀/明确的错误特征。
     */
    private static boolean isSdkAirResultValid(String result) {
        if (result == null || result.isEmpty()) return false;
        return result.contains("AQI") || result.contains("空气质量预报")
            || (result.startsWith("空气质量") && !result.contains("查询失败") && !result.contains("暂无")
                && !result.contains("未初始化") && !result.contains("查询异常"));
    }

    /** 仅缓存有效响应，跳过错误响应 */
    private void saveCacheIfValid(String key, String data) {
        if (isValidForCache(data)) {
            cacheManager.saveCache(key, data);
        } else {
            Log.w(TAG, "Skipping cache for invalid/error response: " + key);
        }
    }

    private static String extractFxLink(String data) {
        if (data == null) return null;
        int index = data.indexOf("链接: ");
        if (index >= 0) {
            int endIndex = data.indexOf("\n", index + 4);
            if (endIndex >= 0) {
                return data.substring(index + 4, endIndex).trim();
            }
            return data.substring(index + 4).trim();
        }
        return null;
    }

    private final Context context;
    private final AIWeatherManager weatherManager;
    private final WeatherCacheManager cacheManager;
    private final Gson gson;
    private final QWeatherSdkManager sdkManager;

    /** 天气数据更新监听器（仿 AIService 状态观察者：数据源变化主动推送，UI 实时刷新） */
    public interface WeatherUpdateListener {
        /**
         * 天气数据已更新（网络拉取成功）。
         * @param city 城市名（可能为空）
         * @param lat  纬度（城市级更新为 0）
         * @param lon  经度（城市级更新为 0）
         * @param weatherText 最新天气文本（已解析为可展示格式）
         */
        void onWeatherUpdated(String city, double lat, double lon, String weatherText);
    }

    private final java.util.List<WeatherUpdateListener> weatherUpdateListeners
            = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public void registerWeatherUpdateListener(WeatherUpdateListener listener) {
        if (listener != null && !weatherUpdateListeners.contains(listener)) {
            weatherUpdateListeners.add(listener);
        }
    }

    public void unregisterWeatherUpdateListener(WeatherUpdateListener listener) {
        weatherUpdateListeners.remove(listener);
    }

    /** 通知所有监听者：天气数据已更新（主线程回调，UI 可直接刷新） */
    private void notifyWeatherUpdate(String city, double lat, double lon, String weatherText) {
        if (weatherUpdateListeners.isEmpty()) return;
        final String c = city;
        final double la = lat;
        final double lo = lon;
        final String w = weatherText;
        mainHandler.post(() -> {
            for (WeatherUpdateListener l : weatherUpdateListeners) {
                try {
                    l.onWeatherUpdated(c, la, lo, w);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static WeatherService instance;

    private WeatherService(Context context) {
        this.context = context.getApplicationContext();
        this.cacheManager = WeatherCacheManager.getInstance(this.context);
        this.gson = new Gson();
        this.sdkManager = QWeatherSdkManager.getInstance(this.context);

        // Auto-initialize SDK with default JWT credentials
        if (!sdkManager.isInitialized()) {
            sdkManager.initializeFromStorage();
            if (sdkManager.isInitialized()) {
                Log.i(TAG, "QWeather SDK auto-initialized with JWT credentials");
            } else {
                Log.w(TAG, "QWeather SDK initialization failed, falling back to direct API");
            }
        }

        this.weatherManager = new AIWeatherManager(this.context, AIWeatherManager.WeatherProvider.HEFENG);
    }

    public static synchronized WeatherService getInstance(Context context) {
        if (instance == null) {
            instance = new WeatherService(context);
        }
        return instance;
    }

    public void initializeSdk(String apiHost, String privateKey, String projectId, String kid) {
        sdkManager.initialize(apiHost, privateKey, projectId, kid);
    }

    /**
     * Initialize JWT authentication for QWeather API.
     * @param privateKeyPem Ed25519 private key in PEM format
     * @param projectId QWeather project ID
     * @param kid QWeather key ID (credential ID from console)
     * @param apiHost API host (e.g., "https://xxx.qweatherapi.com")
     */
    public void initializeJwt(String privateKeyPem, String projectId, String kid, String apiHost) {
        weatherManager.initializeJwt(privateKeyPem, projectId, kid, apiHost);
        Log.i(TAG, "QWeather JWT authentication initialized");
    }

    public boolean isSdkInitialized() {
        return sdkManager.isInitialized();
    }

    /**
     * Check if JWT authentication is configured.
     */
    public boolean isJwtInitialized() {
        return weatherManager.isJwtInitialized();
    }

    public CompletableFuture<String> getCurrentWeather(String city) {
        String cacheKey = "weather_now_" + city;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_NOW)) {
            Log.d(TAG, "Returning cached weather for " + city);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        if (sdkManager.isInitialized()) {
            return sdkManager.getCurrentWeather(city).thenApply(result -> {
                saveCacheIfValid(cacheKey, result);
                notifyWeatherUpdate(city, 0, 0, result);
                return result;
            });
        }

        return weatherManager.getCurrentWeather(city).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            notifyWeatherUpdate(city, 0, 0, result);
            return result;
        });
    }

    public CompletableFuture<String> getCurrentWeatherByLocation(double lat, double lon) {
        return getCurrentWeatherByLocation(lat, lon, null);
    }

    public CompletableFuture<String> getCurrentWeatherByLocation(double lat, double lon, String cityName) {
        String cacheKey = "weather_now_" + locationKey(lat, lon);
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_NOW)) {
            Log.d(TAG, "Returning cached weather for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
        if (sdkManager.isInitialized()) {
            return sdkManager.getCurrentWeather(location, cityName).thenCompose(result -> {
                if (result != null && !result.contains("失败") && !result.contains("异常") && !result.contains("SDK未初始化")) {
                    saveCacheIfValid(cacheKey, result);
                    notifyWeatherUpdate(cityName, lat, lon, result);
                    return CompletableFuture.completedFuture(result);
                }
                Log.w(TAG, "SDK weather failed, falling back to HTTP");
                return weatherManager.getCurrentWeatherByLocation(lat, lon, cityName).thenApply(httpResult -> {
                    saveCacheIfValid(cacheKey, httpResult);
                    notifyWeatherUpdate(cityName, lat, lon, httpResult);
                    return httpResult;
                });
            }).exceptionally(e -> {
                Log.w(TAG, "SDK weather failed, falling back to HTTP", e);
                try {
                    String httpResult = weatherManager.getCurrentWeatherByLocation(lat, lon, cityName).get();
                    saveCacheIfValid(cacheKey, httpResult);
                    notifyWeatherUpdate(cityName, lat, lon, httpResult);
                    return httpResult;
                } catch (Exception ex) {
                    return "天气信息解析失败";
                }
            });
        }

        return weatherManager.getCurrentWeatherByLocation(lat, lon, cityName).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            notifyWeatherUpdate(cityName, lat, lon, result);
            return result;
        });
    }

    /**
     * 直接用经纬度查天气，不做GeoAPI反解析（省一次API调用）
     * 城市名由调用方通过Geocoder提供
     */
    public CompletableFuture<String> getCurrentWeatherByLocationDirect(double lat, double lon) {
        String cacheKey = "weather_now_" + locationKey(lat, lon);
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_NOW)) {
            Log.d(TAG, "Returning cached weather for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        return weatherManager.getCurrentWeatherByLocationDirect(lat, lon).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            notifyWeatherUpdate(null, lat, lon, result);
            return result;
        });
    }

    public CompletableFuture<String> getForecastByLocation(double lat, double lon) {
        String cacheKey = "weather_forecast_" + locationKey(lat, lon);
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_FORECAST)) {
            Log.d(TAG, "Returning cached forecast for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
        if (sdkManager.isInitialized()) {
            return sdkManager.getDailyForecast(location).thenApply(result -> {
                saveCacheIfValid(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengForecastByLocation(lat, lon).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getHourlyByLocation(double lat, double lon) {
        String cacheKey = "weather_hourly_" + locationKey(lat, lon);
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_HOURLY)) {
            Log.d(TAG, "Returning cached hourly for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
        if (sdkManager.isInitialized()) {
            return sdkManager.getHourlyForecast(location).thenApply(result -> {
                saveCacheIfValid(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengHourlyByLocation(lat, lon).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getAirQualityByLocation(double lat, double lon) {
        String cacheKey = "weather_air_v2_" + locationKey(lat, lon);
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_AIR)) {
            Log.d(TAG, "Returning cached air quality for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);

        // 尝试 SDK，如果失败则回退到 HTTP
        if (sdkManager.isInitialized()) {
            return sdkManager.getAirQuality(location).thenCompose(result -> {
                if (isSdkAirResultValid(result)) {
                    saveCacheIfValid(cacheKey, result);
                    return CompletableFuture.completedFuture(result);
                }
                // SDK 失败，回退到 HTTP
                Log.w(TAG, "SDK air quality failed, falling back to HTTP. SDK result: " + result);
                return weatherManager.getHefengAirQualityByLocation(lat, lon).thenApply(r -> {
                    saveCacheIfValid(cacheKey, r);
                    return r;
                });
            });
        }

        return weatherManager.getHefengAirQualityByLocation(lat, lon).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getAirForecastByLocation(double lat, double lon) {
        String cacheKey = "weather_air_forecast_v2_" + locationKey(lat, lon);
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_AIR)) {
            Log.d(TAG, "Returning cached air forecast for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);

        if (sdkManager.isInitialized()) {
            return sdkManager.getAirForecast(location).thenCompose(result -> {
                if (isSdkAirResultValid(result)) {
                    saveCacheIfValid(cacheKey, result);
                    return CompletableFuture.completedFuture(result);
                }
                Log.w(TAG, "SDK air forecast failed, falling back to HTTP. SDK result: " + result);
                return weatherManager.getHefengAirForecastByLocation(lat, lon).thenApply(r -> {
                    saveCacheIfValid(cacheKey, r);
                    return r;
                });
            });
        }

        return weatherManager.getHefengAirForecastByLocation(lat, lon).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getAlertsByLocation(double lat, double lon) {
        String cacheKey = "weather_alerts_" + locationKey(lat, lon);
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_ALERTS)) {
            Log.d(TAG, "Returning cached alerts for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);

        // 尝试 SDK，如果失败则回退到 HTTP，再失败则尝试备用API
        if (sdkManager.isInitialized()) {
            return sdkManager.getWeatherAlerts(location).thenCompose(result -> {
                if (result != null && !result.contains("失败") && !result.contains("无权限") && !result.contains("异常")) {
                    saveCacheIfValid(cacheKey, result);
                    return CompletableFuture.completedFuture(result);
                }
                // SDK 失败，回退到 HTTP
                Log.w(TAG, "SDK alerts failed, falling back to HTTP. SDK result: " + result);
                return weatherManager.getHefengAlertsByLocation(lat, lon).thenCompose(httpResult -> {
                    if (httpResult != null && !httpResult.contains("失败") && !httpResult.contains("无权限") && !httpResult.contains("异常")) {
                        saveCacheIfValid(cacheKey, httpResult);
                        return CompletableFuture.completedFuture(httpResult);
                    }
                    // HTTP 也失败，尝试备用API (国家预警中心数据)
                    Log.w(TAG, "HTTP alerts failed, falling back to backup API");
                    return weatherManager.getBackupAlerts(lat, lon).thenApply(backupResult -> {
                        String finalResult = weatherManager.parseBackupAlertsResponse(backupResult);
                        if (finalResult == null) {
                            finalResult = "天气预警:\n当前无天气预警";
                        }
                        saveCacheIfValid(cacheKey, finalResult);
                        return finalResult;
                    });
                });
            });
        }

        // 直接使用 HTTP，失败则尝试备用API
        return weatherManager.getHefengAlertsByLocation(lat, lon).thenCompose(result -> {
            if (result != null && !result.contains("失败") && !result.contains("无权限") && !result.contains("异常")) {
                saveCacheIfValid(cacheKey, result);
                return CompletableFuture.completedFuture(result);
            }
            // HTTP 失败，尝试备用API
            Log.w(TAG, "HTTP alerts failed, falling back to backup API");
            return weatherManager.getBackupAlerts(lat, lon).thenApply(backupResult -> {
                String finalResult = weatherManager.parseBackupAlertsResponse(backupResult);
                if (finalResult == null) {
                    finalResult = "天气预警:\n当前无天气预警";
                }
                saveCacheIfValid(cacheKey, finalResult);
                return finalResult;
            });
        });
    }

    public CompletableFuture<String> getIndicesByLocation(double lat, double lon) {
        String cacheKey = "weather_indices_" + locationKey(lat, lon);
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_INDICES)) {
            Log.d(TAG, "Returning cached indices for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);

        // 尝试 SDK，如果失败则回退到 HTTP
        if (sdkManager.isInitialized()) {
            return sdkManager.getIndices(location).thenCompose(result -> {
                if (result != null && !result.contains("失败") && !result.contains("无权限") && !result.contains("异常") && !result.contains("解析失败")) {
                    saveCacheIfValid(cacheKey, result);
                    return CompletableFuture.completedFuture(result);
                }
                // SDK 失败，回退到 HTTP
                Log.w(TAG, "SDK indices failed, falling back to HTTP");
                return weatherManager.getHefengIndicesByLocation(lat, lon).thenApply(r -> {
                    saveCacheIfValid(cacheKey, r);
                    return r;
                });
            });
        }

        return weatherManager.getHefengIndicesByLocation(lat, lon).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getMinutelyByLocation(double lat, double lon) {
        String cacheKey = "weather_minutely_" + locationKey(lat, lon);
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_MINUTELY)) {
            Log.d(TAG, "Returning cached minutely for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);

        if (sdkManager.isInitialized()) {
            return sdkManager.getMinutelyByLocation(location).thenCompose(result -> {
                Log.d(TAG, "SDK minutely result: " + result);
                if (result != null && !result.contains("失败") && !result.contains("异常")) {
                    saveCacheIfValid(cacheKey, result);
                    return CompletableFuture.completedFuture(result);
                }
                Log.w(TAG, "SDK minutely failed, falling back to HTTP");
                return weatherManager.getHefengMinutelyByLocation(lat, lon).thenApply(r -> {
                    Log.d(TAG, "HTTP minutely result: " + r);
                    saveCacheIfValid(cacheKey, r);
                    return r;
                });
            });
        }

        return weatherManager.getHefengMinutelyByLocation(lat, lon).thenApply(result -> {
            Log.d(TAG, "HTTP minutely result: " + result);
            saveCacheIfValid(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getSunByLocation(double lat, double lon) {
        String cacheKey = "weather_sun_" + locationKey(lat, lon);
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_FORECAST)) {
            Log.d(TAG, "Returning cached sun info for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);

        // 尝试 SDK，如果失败则回退到 HTTP
        if (sdkManager.isInitialized()) {
            return sdkManager.getSunByLocation(location).thenCompose(result -> {
                if (result != null && !result.contains("失败") && !result.contains("异常")) {
                    saveCacheIfValid(cacheKey, result);
                    return CompletableFuture.completedFuture(result);
                }
                // SDK 失败，回退到 HTTP
                Log.w(TAG, "SDK sun failed, falling back to HTTP");
                return weatherManager.getHefengSunByLocation(lat, lon).thenApply(r -> {
                    saveCacheIfValid(cacheKey, r);
                    return r;
                });
            });
        }

        return weatherManager.getHefengSunByLocation(lat, lon).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getSun(String city) {
        String cacheKey = "weather_sun_" + city;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_FORECAST)) {
            Log.d(TAG, "Returning cached sun info for " + city);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        if (sdkManager.isInitialized()) {
            return sdkManager.getSun(city).thenApply(result -> {
                saveCacheIfValid(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengSun(city).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getForecast(String city) {
        String cacheKey = "weather_forecast_" + city;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_FORECAST)) {
            Log.d(TAG, "Returning cached forecast for " + city);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        if (sdkManager.isInitialized()) {
            return sdkManager.getDailyForecast(city).thenApply(result -> {
                saveCacheIfValid(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengForecast(city).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getHourly(String city) {
        String cacheKey = "weather_hourly_" + city;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_HOURLY)) {
            Log.d(TAG, "Returning cached hourly for " + city);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        if (sdkManager.isInitialized()) {
            return sdkManager.getHourlyForecast(city).thenApply(result -> {
                saveCacheIfValid(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengHourly(city).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getAirQuality(String city) {
        String cacheKey = "weather_air_v2_" + city;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_AIR)) {
            Log.d(TAG, "Returning cached air quality for " + city);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        if (sdkManager.isInitialized()) {
            return sdkManager.getAirQuality(city).thenApply(result -> {
                saveCacheIfValid(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengAirQuality(city).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getAlerts(String city) {
        String cacheKey = "weather_alerts_" + city;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_ALERTS)) {
            Log.d(TAG, "Returning cached alerts for " + city);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        if (sdkManager.isInitialized()) {
            return sdkManager.getWeatherAlerts(city).thenApply(result -> {
                saveCacheIfValid(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengAlerts(city).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getIndices(String city) {
        String cacheKey = "weather_indices_" + city;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_INDICES)) {
            Log.d(TAG, "Returning cached indices for " + city);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        if (sdkManager.isInitialized()) {
            return sdkManager.getIndices(city).thenCompose(result -> {
                if (result != null && !result.contains("失败") && !result.contains("无权限") && !result.contains("异常") && !result.contains("解析失败")) {
                    saveCacheIfValid(cacheKey, result);
                    return CompletableFuture.completedFuture(result);
                }
                Log.w(TAG, "SDK indices failed, falling back to HTTP");
                return weatherManager.getHefengIndices(city).thenApply(r -> {
                    saveCacheIfValid(cacheKey, r);
                    return r;
                });
            });
        }

        return weatherManager.getHefengIndices(city).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getMinutely(String city) {
        String cacheKey = "weather_minutely_" + city;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_MINUTELY)) {
            Log.d(TAG, "Returning cached minutely for " + city);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        return weatherManager.getHefengMinutely(city).thenApply(result -> {
            saveCacheIfValid(cacheKey, result);
            return result;
        });
    }

    public void clearCache() {
        cacheManager.clearAllCache();
    }

    public void clearCacheForCity(String city) {
        cacheManager.removeCache("weather_now_" + city);
        cacheManager.removeCache("weather_forecast_" + city);
        cacheManager.removeCache("weather_hourly_" + city);
        cacheManager.removeCache("weather_air_v2_" + city);
        cacheManager.removeCache("weather_alerts_" + city);
        cacheManager.removeCache("weather_indices_" + city);
        cacheManager.removeCache("weather_minutely_" + city);
        cacheManager.removeCache("weather_sun_" + city);
    }

    public static class WeatherBatchResult {
        public String currentWeather;
        public String hourlyForecast;
        public String dailyForecast;
        public String airQuality;
        public String airForecast;
        public String alerts;
        public String indices;
        public String sunInfo;
        public String minutely;
        
        public String fxLinkCurrent;
        public String fxLinkDaily;
        public String fxLinkHourly;
        public String fxLinkAir;
        public String fxLinkIndices;
    }

    public CompletableFuture<WeatherBatchResult> getAllWeatherByLocation(double lat, double lon) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CompletableFuture<String> currentFuture = getCurrentWeatherByLocation(lat, lon)
                    .exceptionally(e -> "获取当前天气失败: " + e.getMessage());
                CompletableFuture<String> hourlyFuture = getHourlyByLocation(lat, lon)
                    .exceptionally(e -> "获取小时预报失败: " + e.getMessage());
                CompletableFuture<String> dailyFuture = getForecastByLocation(lat, lon)
                    .exceptionally(e -> "获取天气预报失败: " + e.getMessage());
                CompletableFuture<String> airFuture = getAirQualityByLocation(lat, lon)
                    .exceptionally(e -> "获取空气质量失败: " + e.getMessage());
                CompletableFuture<String> airForecastFuture = getAirForecastByLocation(lat, lon)
                    .exceptionally(e -> "空气质量预报: 查询失败: " + e.getMessage());
                CompletableFuture<String> alertsFuture = getAlertsByLocation(lat, lon)
                    .exceptionally(e -> "天气预警: 请在和风天气控制台开通权限");

                CompletableFuture<String> indicesFuture = getIndicesByLocation(lat, lon)
                    .exceptionally(e -> "生活指数: 请在和风天气控制台开通权限");

                CompletableFuture<String> sunFuture = getSunByLocation(lat, lon)
                    .exceptionally(e -> "获取日出日落失败: " + e.getMessage());

                CompletableFuture<String> minutelyFuture = getMinutelyByLocation(lat, lon)
                    .exceptionally(e -> "获取分钟级降水失败: " + e.getMessage());

                CompletableFuture.allOf(currentFuture, hourlyFuture, dailyFuture, airFuture, airForecastFuture, alertsFuture, indicesFuture, sunFuture, minutelyFuture)
                    .get(20, java.util.concurrent.TimeUnit.SECONDS);

                WeatherBatchResult result = new WeatherBatchResult();
                result.currentWeather = currentFuture.get();
                result.hourlyForecast = hourlyFuture.get();
                result.dailyForecast = dailyFuture.get();
                result.airQuality = airFuture.get();
                result.airForecast = airForecastFuture.get();
                result.alerts = alertsFuture.get();
                result.indices = indicesFuture.get();
                result.sunInfo = sunFuture.get();
                result.minutely = minutelyFuture.get();
                
                result.fxLinkCurrent = extractFxLink(result.currentWeather);
                result.fxLinkHourly = extractFxLink(result.hourlyForecast);
                result.fxLinkDaily = extractFxLink(result.dailyForecast);
                result.fxLinkAir = extractFxLink(result.airQuality);
                result.fxLinkIndices = extractFxLink(result.indices);

                return result;
            } catch (Exception e) {
                Log.e(TAG, "Error getting all weather data", e);
                WeatherBatchResult result = new WeatherBatchResult();
                result.currentWeather = "获取天气数据失败: " + e.getMessage();
                return result;
            }
        });
    }

    public CompletableFuture<WeatherBatchResult> getAllWeather(String city) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                CompletableFuture<String> currentFuture = getCurrentWeather(city)
                    .exceptionally(e -> "获取当前天气失败: " + e.getMessage());
                CompletableFuture<String> hourlyFuture = getHourly(city)
                    .exceptionally(e -> "获取小时预报失败: " + e.getMessage());
                CompletableFuture<String> dailyFuture = getForecast(city)
                    .exceptionally(e -> "获取天气预报失败: " + e.getMessage());
                CompletableFuture<String> airFuture = getAirQuality(city)
                    .exceptionally(e -> "获取空气质量失败: " + e.getMessage());
                CompletableFuture<String> alertsFuture = getAlerts(city)
                    .exceptionally(e -> "天气预警: 请在和风天气控制台开通权限");

                CompletableFuture<String> indicesFuture = getIndices(city)
                    .exceptionally(e -> "生活指数: 请在和风天气控制台开通权限");

                CompletableFuture<String> sunFuture = getSun(city)
                    .exceptionally(e -> "获取日出日落失败: " + e.getMessage());

                CompletableFuture<String> minutelyFuture = getMinutely(city)
                    .exceptionally(e -> "获取分钟级降水失败: " + e.getMessage());

                CompletableFuture.allOf(currentFuture, hourlyFuture, dailyFuture, airFuture, alertsFuture, indicesFuture, sunFuture, minutelyFuture)
                    .get(20, java.util.concurrent.TimeUnit.SECONDS);

                WeatherBatchResult result = new WeatherBatchResult();
                result.currentWeather = currentFuture.get();
                result.hourlyForecast = hourlyFuture.get();
                result.dailyForecast = dailyFuture.get();
                result.airQuality = airFuture.get();
                result.alerts = alertsFuture.get();
                result.indices = indicesFuture.get();
                result.sunInfo = sunFuture.get();
                result.minutely = minutelyFuture.get();
                
                result.fxLinkCurrent = extractFxLink(result.currentWeather);
                result.fxLinkHourly = extractFxLink(result.hourlyForecast);
                result.fxLinkDaily = extractFxLink(result.dailyForecast);
                result.fxLinkAir = extractFxLink(result.airQuality);
                result.fxLinkIndices = extractFxLink(result.indices);

                return result;
            } catch (Exception e) {
                Log.e(TAG, "Error getting all weather data", e);
                WeatherBatchResult result = new WeatherBatchResult();
                result.currentWeather = "获取天气数据失败: " + e.getMessage();
                return result;
            }
        });
    }

    public void clearCacheForLocation(double lat, double lon) {
        String key = locationKey(lat, lon);
        cacheManager.removeCache("weather_now_" + key);
        cacheManager.removeCache("weather_forecast_" + key);
        cacheManager.removeCache("weather_hourly_" + key);
        cacheManager.removeCache("weather_air_v2_" + key);
        cacheManager.removeCache("weather_alerts_" + key);
        cacheManager.removeCache("weather_indices_" + key);
        cacheManager.removeCache("weather_minutely_" + key);
        cacheManager.removeCache("weather_sun_" + key);
    }

    public long getCacheSize() {
        return cacheManager.getCacheSize();
    }

    public void setApiKey(String apiKey) {
        APIKeyManager.getInstance(context).saveAPIKey(APIKeyManager.Service.HEFENG_WEATHER, apiKey);
    }
}