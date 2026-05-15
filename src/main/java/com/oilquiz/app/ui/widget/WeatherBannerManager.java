package com.oilquiz.app.ui.widget;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.oilquiz.app.ai.tool.AIWeatherManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.LocationTool;
import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.util.QWeatherIconMapper;

import java.util.HashMap;
import java.util.Map;

public class WeatherBannerManager {

    private static final String TAG = "WeatherBannerManager";

    private final Context context;
    private final AIWeatherManager weatherManager;

    private View weatherBanner;
    private TextView weatherIcon;
    private TextView weatherCity;
    private TextView weatherTemp;
    private TextView weatherDesc;
    private TextView weatherHumidity;
    private TextView weatherWind;

    private boolean bannerVisible = false;

    public interface OnWeatherLoadedListener {
        void onWeatherLoaded(WeatherInfo info);
        void onWeatherLoadFailed(String error);
    }

    public WeatherBannerManager(Context context) {
        this.context = context;
        this.weatherManager = new AIWeatherManager(context, AIWeatherManager.WeatherProvider.HEFENG);
    }

    public void setupViews(View banner, TextView icon, TextView city, TextView temp, 
                           TextView desc, TextView humidity, TextView wind) {
        this.weatherBanner = banner;
        this.weatherIcon = icon;
        this.weatherCity = city;
        this.weatherTemp = temp;
        this.weatherDesc = desc;
        this.weatherHumidity = humidity;
        this.weatherWind = wind;
    }

    public void loadWeatherBanner() {
        new Thread(() -> {
            try {
                LocationTool locationTool = new LocationTool(context);
                Map<String, Object> params = new HashMap<>();
                params.put("action", "get_current");
                AIToolResult locResult = locationTool.execute(params);

                if (!locResult.isSuccess()) {
                    runOnUiThread(() -> {
                        if (weatherBanner != null) weatherBanner.setVisibility(View.GONE);
                    });
                    return;
                }

                Object resObj = locResult.getResult();
                if (!(resObj instanceof Map)) {
                    runOnUiThread(() -> {
                        if (weatherBanner != null) weatherBanner.setVisibility(View.GONE);
                    });
                    return;
                }

                Map<?, ?> map = (Map<?, ?>) resObj;
                String city = String.valueOf(map.get("city"));
                double lat = map.get("latitude") instanceof Number ? ((Number) map.get("latitude")).doubleValue() : 0;
                double lon = map.get("longitude") instanceof Number ? ((Number) map.get("longitude")).doubleValue() : 0;

                if (city.equals("未知") || city.equals("null")) {
                    if (weatherManager != null) {
                        weatherManager.getCurrentWeather("Beijing").thenAccept(weather -> {
                            runOnUiThread(() -> updateWeatherBannerUI(parseWeatherFromText(weather)));
                        });
                    }
                    return;
                }

                if (weatherManager != null) {
                    weatherManager.getCurrentWeather(city).thenAccept(weather -> {
                        runOnUiThread(() -> updateWeatherBannerUI(parseWeatherFromText(weather)));
                    }).exceptionally(throwable -> {
                        if (lat != 0 && lon != 0 && weatherManager != null) {
                            weatherManager.getOneCallWeather(lat, lon, null, "metric", "zh_cn")
                                .thenAccept(weather -> runOnUiThread(() -> updateWeatherBannerUI(parseWeatherFromText(weather))));
                        }
                        return null;
                    });
                }
            } catch (Exception e) {
                AppLogger.aiE(TAG, "Error loading weather banner: " + e.getMessage());
            }
        }).start();
    }

    public void refreshWeatherBanner() {
        if (weatherBanner != null) {
            weatherBanner.setVisibility(View.VISIBLE);
            bannerVisible = true;
            if (weatherCity != null) weatherCity.setText("刷新中...");
            if (weatherTemp != null) weatherTemp.setText("--°C");
            if (weatherDesc != null) weatherDesc.setText("正在刷新...");
        }
        loadWeatherBanner();
    }

    public void updateWeatherBannerUI(WeatherInfo info) {
        if (weatherBanner == null) return;
        weatherBanner.setVisibility(View.VISIBLE);
        bannerVisible = true;

        if (weatherIcon != null) weatherIcon.setText(info.icon);
        if (weatherCity != null) weatherCity.setText(info.city);
        if (weatherTemp != null) weatherTemp.setText(info.temp);
        if (weatherDesc != null) weatherDesc.setText(info.description);
        if (weatherHumidity != null) weatherHumidity.setText("湿度: " + info.humidity);
        if (weatherWind != null) weatherWind.setText("风速: " + info.wind);
    }

    public void hideBanner() {
        bannerVisible = false;
        if (weatherBanner != null) {
            weatherBanner.setVisibility(View.GONE);
        }
    }

    public void showBanner() {
        bannerVisible = true;
        if (weatherBanner != null) {
            weatherBanner.setVisibility(View.VISIBLE);
        }
    }

    public boolean isBannerVisible() {
        return bannerVisible;
    }

    public WeatherInfo parseWeatherFromText(String weatherText) {
        WeatherInfo info = new WeatherInfo();
        if (weatherText == null) return info;

        try {
            String[] lines = weatherText.split("\n");
            String iconCode = "";
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("城市:")) {
                    info.city = line.substring(3).trim();
                } else if (line.startsWith("天气:")) {
                    String weatherPart = line.substring(3).trim();
                    info.description = weatherPart;
                } else if (line.startsWith("图标:")) {
                    iconCode = line.substring(3).trim();
                } else if (line.startsWith("温度:")) {
                    info.temp = line.substring(3).trim();
                } else if (line.startsWith("湿度:")) {
                    info.humidity = line.substring(3).trim();
                } else if (line.startsWith("风速:")) {
                    info.wind = line.substring(3).trim();
                }
            }

            if (!iconCode.isEmpty()) {
                info.icon = QWeatherIconMapper.getEmojiIcon(iconCode);
            } else {
                info.icon = QWeatherIconMapper.getEmojiIcon("999");
            }
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error parsing weather text: " + e.getMessage());
        }
        return info;
    }

    public static class WeatherInfo {
        public String icon = "🌤️";
        public String city = "未知";
        public String temp = "--°C";
        public String description = "暂无数据";
        public String humidity = "--%";
        public String wind = "-- m/s";
        public String tempRange = "";
        public String forecast = "";
    }

    private void runOnUiThread(Runnable runnable) {
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(runnable);
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(runnable);
        }
    }
}