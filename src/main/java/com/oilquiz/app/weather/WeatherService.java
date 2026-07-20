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
    
    private static final long CACHE_DURATION_NOW = 15 * 60 * 1000;
    private static final long CACHE_DURATION_FORECAST = 3 * 60 * 60 * 1000;
    private static final long CACHE_DURATION_AIR = 30 * 60 * 1000;
    private static final long CACHE_DURATION_ALERTS = 10 * 60 * 1000;
    private static final long CACHE_DURATION_HOURLY = 30 * 60 * 1000;
    private static final long CACHE_DURATION_INDICES = 8 * 60 * 60 * 1000;
    private static final long CACHE_DURATION_MINUTELY = 5 * 60 * 1000;

    private static String locationKey(double lat, double lon) {
        return String.format(java.util.Locale.US, "%.2f_%.2f", lat, lon);
    }

    private final Context context;
    private final AIWeatherManager weatherManager;
    private final WeatherCacheManager cacheManager;
    private final Gson gson;
    private final QWeatherSdkManager sdkManager;

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
                cacheManager.saveCache(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getCurrentWeather(city).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
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
            return sdkManager.getCurrentWeather(location).thenApply(result -> {
                cacheManager.saveCache(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getCurrentWeatherByLocation(lat, lon, cityName).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
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
            cacheManager.saveCache(cacheKey, result);
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
                cacheManager.saveCache(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengForecastByLocation(lat, lon).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
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
                cacheManager.saveCache(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengHourlyByLocation(lat, lon).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getAirQualityByLocation(double lat, double lon) {
        String cacheKey = "weather_air_" + locationKey(lat, lon);
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_AIR)) {
            Log.d(TAG, "Returning cached air quality for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        String location = String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
        if (sdkManager.isInitialized()) {
            return sdkManager.getAirQuality(location).thenApply(result -> {
                cacheManager.saveCache(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengAirQualityByLocation(lat, lon).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
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
        if (sdkManager.isInitialized()) {
            return sdkManager.getWeatherAlerts(location).thenApply(result -> {
                cacheManager.saveCache(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengAlertsByLocation(lat, lon).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
            return result;
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
        if (sdkManager.isInitialized()) {
            return sdkManager.getIndices(location).thenApply(result -> {
                cacheManager.saveCache(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengIndicesByLocation(lat, lon).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
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

        return weatherManager.getHefengMinutelyByLocation(lat, lon).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
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

        return weatherManager.getHefengSunByLocation(lat, lon).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
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
                cacheManager.saveCache(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengForecast(city).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
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
                cacheManager.saveCache(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengHourly(city).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getAirQuality(String city) {
        String cacheKey = "weather_air_" + city;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_AIR)) {
            Log.d(TAG, "Returning cached air quality for " + city);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        if (sdkManager.isInitialized()) {
            return sdkManager.getAirQuality(city).thenApply(result -> {
                cacheManager.saveCache(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengAirQuality(city).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
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
                cacheManager.saveCache(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengAlerts(city).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
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
            return sdkManager.getIndices(city).thenApply(result -> {
                cacheManager.saveCache(cacheKey, result);
                return result;
            });
        }

        return weatherManager.getHefengIndices(city).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
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
        cacheManager.removeCache("weather_air_" + city);
        cacheManager.removeCache("weather_alerts_" + city);
        cacheManager.removeCache("weather_indices_" + city);
    }

    public void clearCacheForLocation(double lat, double lon) {
        String key = locationKey(lat, lon);
        cacheManager.removeCache("weather_now_" + key);
        cacheManager.removeCache("weather_forecast_" + key);
        cacheManager.removeCache("weather_hourly_" + key);
        cacheManager.removeCache("weather_air_" + key);
        cacheManager.removeCache("weather_alerts_" + key);
        cacheManager.removeCache("weather_indices_" + key);
    }

    public long getCacheSize() {
        return cacheManager.getCacheSize();
    }

    public void setApiKey(String apiKey) {
        APIKeyManager.getInstance(context).saveAPIKey(APIKeyManager.Service.HEFENG_WEATHER, apiKey);
    }
}