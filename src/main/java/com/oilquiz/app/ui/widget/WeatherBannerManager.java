package com.oilquiz.app.ui.widget;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.LocationTool;
import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.weather.WeatherService;

import java.util.HashMap;
import java.util.Map;

public class WeatherBannerManager {

    private static final String TAG = "WeatherBannerManager";

    private final Context context;
    private final WeatherService weatherService;

    private View weatherBanner;
    private ImageView weatherIcon;
    private TextView weatherCity;
    private TextView weatherTemp;
    private TextView weatherDesc;
    private TextView weatherFeelsLike;   // 体感温度 chip
    private TextView weatherHumidity;    // 湿度 chip
    private TextView weatherWind;        // 风力+风向 chip
    private TextView weatherVisibility;  // 能见度 chip
    private boolean bannerVisible = false;

    public interface OnWeatherLoadedListener {
        void onWeatherLoaded(WeatherInfo info);
        void onWeatherLoadFailed(String error);
    }

    public WeatherBannerManager(Context context) {
        this.context = context;
        this.weatherService = WeatherService.getInstance(context);
    }

    /**
     * 绑定横幅所有 UI 控件。
     *   feelsLikeChip : 体感温度（"体感 --°"）
     *   humidityChip  : 湿度（"💧 --%"）
     *   windChip      : 风向风力（"🌬 东北风3级"）
     *   visibilityChip: 能见度（"👁 15km"）
     */
    public void setupViews(View banner, ImageView icon,
                           TextView city, TextView temp, TextView desc,
                           TextView feelsLikeChip,
                           TextView humidityChip,
                           TextView windChip,
                           TextView visibilityChip) {
        this.weatherBanner = banner;
        this.weatherIcon   = icon;
        this.weatherCity   = city;
        this.weatherTemp   = temp;
        this.weatherDesc   = desc;
        this.weatherFeelsLike  = feelsLikeChip;
        this.weatherHumidity   = humidityChip;
        this.weatherWind       = windChip;
        this.weatherVisibility = visibilityChip;
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
                    // 城市未知时隐藏banner，不回退到默认城市
                    runOnUiThread(() -> {
                        if (weatherBanner != null) weatherBanner.setVisibility(View.GONE);
                    });
                    return;
                }

                String finalCity = city;
                if (lat != 0 && lon != 0) {
                    weatherService.getCurrentWeatherByLocation(lat, lon, finalCity).thenAccept(weather -> {
                        runOnUiThread(() -> updateWeatherBannerUI(parseWeatherFromText(weather)));
                    }).exceptionally(e -> {
                        Log.e(TAG, "Failed to load weather by location", e);
                        weatherService.getCurrentWeather(finalCity).thenAccept(weather -> {
                            runOnUiThread(() -> updateWeatherBannerUI(parseWeatherFromText(weather)));
                        });
                        return null;
                    });
                } else {
                    weatherService.getCurrentWeather(finalCity).thenAccept(weather -> {
                        runOnUiThread(() -> updateWeatherBannerUI(parseWeatherFromText(weather)));
                    }).exceptionally(e -> {
                        AppLogger.aiE(TAG, "Error loading weather: " + e.getMessage());
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

        if (weatherIcon != null) {
            try {
                android.graphics.drawable.Drawable drawable = com.oilquiz.app.util.QWeatherIconMapper.getIconDrawable(info.iconCode != null ? info.iconCode : "999", context, 36);
                if (drawable != null) {
                    weatherIcon.setImageDrawable(drawable);
                } else {
                    weatherIcon.setImageResource(R.drawable.wi_999);
                }
            } catch (Exception e) {
                weatherIcon.setImageResource(R.drawable.wi_999);
            }
        }
        if (weatherCity       != null) weatherCity.setText(truncateCityName(info.city));
        if (weatherTemp       != null) weatherTemp.setText((info.temp == null || info.temp.isEmpty()) ? "--°" : (info.temp.contains("°") ? info.temp : info.temp + "°"));
        if (weatherDesc       != null) weatherDesc.setText(info.description);

        // ---- 介绍行：体感 / 湿度 / 风向风力 / 能见度（类似详情页的 chips 展示）----
        if (weatherFeelsLike != null) {
            String feels = (info.feelsLike == null || info.feelsLike.isEmpty() || "--".equals(info.feelsLike))
                           ? "体感 --°" : "体感 " + info.feelsLike + "°";
            weatherFeelsLike.setText(feels);
        }
        if (weatherHumidity != null) {
            String h = (info.humidity == null || info.humidity.isEmpty() || "--".equals(info.humidity))
                       ? "--" : info.humidity;
            weatherHumidity.setText("💧 " + h + "%");
        }
        if (weatherWind != null) {
            // 风向（windDir）+ 风力等级/风速（wind），拼接类似：🌬 东北风3级
            StringBuilder w = new StringBuilder("🌬 ");
            boolean hasAny = false;
            if (info.windDir != null && !info.windDir.isEmpty() && !"--".equals(info.windDir)) {
                w.append(info.windDir);
                hasAny = true;
            }
            if (info.wind != null && !info.wind.isEmpty() && !"--".equals(info.wind)) {
                // 如果 wind 本身已经包含"级/风/米"等字样就直接加，否则补"级"
                if (hasAny) w.append(" ");
                w.append(info.wind);
                hasAny = true;
            }
            if (!hasAny) w.append("--");
            weatherWind.setText(w.toString());
        }
        if (weatherVisibility != null) {
            String vis = (info.visibility == null || info.visibility.isEmpty() || "--".equals(info.visibility))
                         ? "--" : info.visibility;
            // 数字型补 km，非数字原样
            if (vis.matches("-?\\d+(\\.\\d+)?")) {
                weatherVisibility.setText("👁 " + vis + "km");
            } else {
                weatherVisibility.setText("👁 " + vis);
            }
        }
    }

    /**
     * 最简城市名处理：只"去掉省/市级前缀"，其余原样返回，绝不截断中间内容
     */
    static String truncateCityName(String name) {
        if (name == null || name.isEmpty()) return name;
        String s = name;

        // 1. 去省级前缀（前12字内的"省/自治区/特别行政区"）
        int provEnd = -1;
        int sheng = s.indexOf('省');
        int zhiQu = s.indexOf("自治区");
        int teBie = s.indexOf("特别行政区");
        if (sheng >= 0 && sheng < 8) provEnd = Math.max(provEnd, sheng + 1);
        if (zhiQu >= 0 && zhiQu < 12) provEnd = Math.max(provEnd, zhiQu + 3);
        if (teBie >= 0 && teBie < 15) provEnd = Math.max(provEnd, teBie + 5);
        if (provEnd > 0 && provEnd < s.length()) s = s.substring(provEnd);

        // 2. 去市级前缀（前6字内的市，后面跟着区/路/街等才去）
        int shiIdx = s.indexOf('市');
        if (shiIdx > 0 && shiIdx <= 5 && shiIdx + 1 < s.length()) {
            char a = s.charAt(shiIdx + 1);
            if (a == '区' || a == '县' || a == '路' || a == '街' || a == '巷'
                || a == '大' || a == '小' || a == '花' || a == '园') {
                s = s.substring(shiIdx + 1);
            }
        }
        return s.isEmpty() ? name : s;
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
                    info.description = line.substring(3).trim();
                } else if (line.startsWith("图标:")) {
                    iconCode = line.substring(3).trim();
                    info.iconCode = iconCode;
                } else if (line.startsWith("温度:")) {
                    info.temp = line.substring(3).trim().replace("°C", "°");
                } else if (line.startsWith("湿度:")) {
                    info.humidity = line.substring(3).trim().replace("%", "%");
                } else if (line.startsWith("风速:")) {
                    info.wind = line.substring(3).trim();
                } else if (line.startsWith("风向:")) {
                    info.windDir = line.substring(3).trim();
                } else if (line.startsWith("体感温度:")) {
                    info.feelsLike = line.substring(5).trim().replace("°C", "°");
                } else if (line.startsWith("能见度:")) {
                    info.visibility = line.substring(4).trim();
                } else if (line.startsWith("气压:")) {
                    info.pressure = line.substring(3).trim();
                } else if (line.startsWith("链接:")) {
                    info.fxLink = line.substring(3).trim();
                }
            }

            if (!iconCode.isEmpty()) {
                info.icon = com.oilquiz.app.util.QWeatherIconMapper.getEmojiIcon(iconCode);
            } else {
                info.icon = "🌤️";
            }
        } catch (Exception e) {
            AppLogger.aiE(TAG, "Error parsing weather text: " + e.getMessage());
            info.description = "解析失败";
        }
        return info;
    }

    public static class WeatherInfo {
        public String icon = "🌤️";
        public String iconCode = "999";
        public String city = "未知";
        public String temp = "--";
        public String description = "暂无数据";
        public String humidity = "--";
        public String wind = "--";
        public String windDir = "--";
        public String windScale = "--";
        public String uv = "--";
        public String tempRange = "";
        public String forecast = "";
        public String feelsLike = "--";
        public String visibility = "--";
        public String pressure = "--";
        public String fxLink = "";
    }

    private void runOnUiThread(Runnable runnable) {
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(runnable);
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(runnable);
        }
    }
}