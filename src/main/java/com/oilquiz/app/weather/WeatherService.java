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
    
    private static final long CACHE_DURATION_NOW = 5 * 60 * 1000;
    private static final long CACHE_DURATION_FORECAST = 30 * 60 * 1000;
    private static final long CACHE_DURATION_AIR = 10 * 60 * 1000;
    private static final long CACHE_DURATION_ALERTS = 5 * 60 * 1000;
    private static final long CACHE_DURATION_HOURLY = 15 * 60 * 1000;
    private static final long CACHE_DURATION_INDICES = 60 * 60 * 1000;

    private final Context context;
    private final AIWeatherManager weatherManager;
    private final WeatherCacheManager cacheManager;
    private final Gson gson;

    private static WeatherService instance;

    private WeatherService(Context context) {
        this.context = context.getApplicationContext();
        this.weatherManager = new AIWeatherManager(this.context, AIWeatherManager.WeatherProvider.HEFENG);
        this.cacheManager = WeatherCacheManager.getInstance(this.context);
        this.gson = new Gson();
    }

    public static synchronized WeatherService getInstance(Context context) {
        if (instance == null) {
            instance = new WeatherService(context);
        }
        return instance;
    }

    public CompletableFuture<String> getCurrentWeather(String city) {
        String cacheKey = "weather_now_" + city;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_NOW)) {
            Log.d(TAG, "Returning cached weather for " + city);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        return weatherManager.getCurrentWeather(city).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getCurrentWeatherByLocation(double lat, double lon) {
        String cacheKey = "weather_now_" + lat + "_" + lon;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_NOW)) {
            Log.d(TAG, "Returning cached weather for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        return weatherManager.getCurrentWeatherByLocation(lat, lon).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getForecastByLocation(double lat, double lon) {
        String cacheKey = "weather_forecast_" + lat + "_" + lon;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_FORECAST)) {
            Log.d(TAG, "Returning cached forecast for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        return weatherManager.getHefengForecastByLocation(lat, lon).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getHourlyByLocation(double lat, double lon) {
        String cacheKey = "weather_hourly_" + lat + "_" + lon;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_HOURLY)) {
            Log.d(TAG, "Returning cached hourly for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        return weatherManager.getHefengHourlyByLocation(lat, lon).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getAirQualityByLocation(double lat, double lon) {
        String cacheKey = "weather_air_" + lat + "_" + lon;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_AIR)) {
            Log.d(TAG, "Returning cached air quality for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        return weatherManager.getHefengAirQualityByLocation(lat, lon).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getAlertsByLocation(double lat, double lon) {
        String cacheKey = "weather_alerts_" + lat + "_" + lon;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_ALERTS)) {
            Log.d(TAG, "Returning cached alerts for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        return weatherManager.getHefengAlertsByLocation(lat, lon).thenApply(result -> {
            cacheManager.saveCache(cacheKey, result);
            return result;
        });
    }

    public CompletableFuture<String> getIndicesByLocation(double lat, double lon) {
        String cacheKey = "weather_indices_" + lat + "_" + lon;
        WeatherCacheManager.CacheEntry cacheEntry = cacheManager.getCache(cacheKey);

        if (cacheEntry != null && !cacheEntry.isExpired(CACHE_DURATION_INDICES)) {
            Log.d(TAG, "Returning cached indices for location " + lat + "," + lon);
            return CompletableFuture.completedFuture(cacheEntry.getData());
        }

        return weatherManager.getHefengIndicesByLocation(lat, lon).thenApply(result -> {
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

    public long getCacheSize() {
        return cacheManager.getCacheSize();
    }

    public void setApiKey(String apiKey) {
        APIKeyManager.getInstance(context).saveAPIKey(APIKeyManager.Service.HEFENG_WEATHER, apiKey);
    }
}