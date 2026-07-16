package com.oilquiz.app.ai.chat.weather;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.oilquiz.app.ai.tool.AIWeatherManager;
import com.oilquiz.app.util.QWeatherIconMapper;

import org.json.JSONObject;

/**
 * 管理 AI 聊天页面的天气横幅。
 * 从 AIChatActivity 中提取的独立模块。
 */
public class WeatherBannerController {

    private static final String TAG = "WeatherBannerController";

    public interface Callback {
        void onShowToast(String message);
    }

    private final Activity activity;
    private final Callback callback;
    private AIWeatherManager weatherManager;

    private View weatherBanner;
    private TextView weatherIcon;
    private TextView weatherCity;
    private TextView weatherTemp;
    private TextView weatherDesc;
    private TextView weatherHumidity;
    private TextView weatherWind;

    private boolean isVisible = false;
    private String currentCity = "";
    private double currentLat = 0;
    private double currentLon = 0;

    public WeatherBannerController(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
        this.weatherManager = new AIWeatherManager(activity, AIWeatherManager.WeatherProvider.HEFENG);
    }

    public void bindViews(View weatherBanner, TextView weatherIcon, TextView weatherCity,
                          TextView weatherTemp, TextView weatherDesc,
                          TextView weatherHumidity, TextView weatherWind) {
        this.weatherBanner = weatherBanner;
        this.weatherIcon = weatherIcon;
        this.weatherCity = weatherCity;
        this.weatherTemp = weatherTemp;
        this.weatherDesc = weatherDesc;
        this.weatherHumidity = weatherHumidity;
        this.weatherWind = weatherWind;
    }

    public void loadWeather() {
        if (weatherManager == null || weatherBanner == null) return;

        if (weatherCity != null) weatherCity.setText("正在获取天气...");
        if (weatherTemp != null) weatherTemp.setText("--°C");
        if (weatherDesc != null) weatherDesc.setText("加载中...");

        new Thread(() -> {
            try {
                String result = weatherManager.getCurrentWeather("北京").get();
                if (result != null && !result.isEmpty() && !result.contains("失败")) {
                    activity.runOnUiThread(() -> {
                        try {
                            JSONObject weatherJson = parseWeatherResponse(result);
                            if (weatherJson != null) {
                                updateUI(weatherJson);
                                weatherBanner.setVisibility(View.VISIBLE);
                                isVisible = true;
                            } else {
                                hide();
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing weather response", e);
                            hide();
                        }
                    });
                } else {
                    activity.runOnUiThread(this::hide);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error loading weather banner", e);
                activity.runOnUiThread(this::hide);
            }
        }).start();
    }

    public void hide() {
        if (weatherBanner != null) {
            weatherBanner.setVisibility(View.GONE);
            isVisible = false;
        }
    }

    public void show() {
        if (weatherBanner != null) {
            weatherBanner.setVisibility(View.VISIBLE);
            isVisible = true;
        }
    }

    public boolean isVisible() {
        return isVisible;
    }

    public String getCurrentCity() {
        return currentCity;
    }

    public double getCurrentLat() {
        return currentLat;
    }

    public double getCurrentLon() {
        return currentLon;
    }

    private void updateUI(JSONObject weatherJson) {
        String cityName = weatherJson.optString("city", "北京");
        String tempStr = weatherJson.optString("temperature", "--");
        String condition = weatherJson.optString("condition", "--");
        String iconCode = weatherJson.optString("icon", "");

        if (weatherIcon != null) {
            weatherIcon.setText(iconCode.isEmpty() ? "" : QWeatherIconMapper.getEmojiIcon(iconCode));
        }
        if (weatherCity != null) weatherCity.setText(cityName);
        if (weatherTemp != null) weatherTemp.setText(tempStr + "°C");
        if (weatherDesc != null) weatherDesc.setText(condition);

        currentCity = cityName;
    }

    private JSONObject parseWeatherResponse(String weatherText) {
        try {
            JSONObject result = new JSONObject();
            if (weatherText == null || weatherText.isEmpty()) return null;
            if (weatherText.contains("失败") || weatherText.contains("错误")) return null;

            String[] lines = weatherText.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("城市:")) {
                    result.put("city", line.substring(3).trim());
                } else if (line.startsWith("天气:")) {
                    result.put("condition", line.substring(3).trim());
                } else if (line.startsWith("温度:")) {
                    String temp = line.substring(3).trim().replace("°C", "").replace("°", "");
                    result.put("temperature", temp);
                } else if (line.startsWith("图标:")) {
                    result.put("icon", line.substring(3).trim());
                }
            }
            return result.has("city") ? result : null;
        } catch (Exception e) {
            Log.e(TAG, "Parse error", e);
            return null;
        }
    }
}
