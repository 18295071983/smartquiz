package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.oilquiz.app.R;
import com.oilquiz.app.util.QWeatherIconMapper;
import com.oilquiz.app.weather.WeatherService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class WeatherDetailActivity extends AppCompatActivity {

    private static final String TAG = "WeatherDetail";
    private static final String QWEATHER_ICON_URL = "https://static.qweather.com/icons/";

    private String city;
    private double lat = 0;
    private double lon = 0;
    private boolean hasLocation = false;

    private WeatherService weatherService;

    private TextView tvCity;
    private TextView tvTemp;
    private TextView tvWeather;
    private TextView tvTempRange;
    private TextView tvWind;
    private TextView tvHumidity;
    private TextView tvFeelsLike;
    private TextView tvVisibility;
    private TextView tvSunrise;
    private TextView tvSunset;
    private TextView tvAirAqi;

    private ImageView ivCurrentIcon;

    private LinearLayout llHourly;
    private LinearLayout llDaily;
    private LinearLayout llAir;
    private LinearLayout llAlerts;
    private LinearLayout llIndices;

    private ImageView btnBack;
    private ImageView btnRefresh;
    private LinearLayout btnWeatherDetail;
    private TextView btnHourlyAll;
    private TextView btnDailyAll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_weather_detail);

        city = getIntent().getStringExtra("city");
        lat = getIntent().getDoubleExtra("lat", 0);
        lon = getIntent().getDoubleExtra("lon", 0);
        hasLocation = (lat != 0 && lon != 0);

        if (city == null || city.isEmpty()) {
            city = "北京";
        }

        weatherService = WeatherService.getInstance(this);

        initViews();
        loadWeatherData();
    }

    private void initViews() {
        tvCity = findViewById(R.id.tv_city);
        tvTemp = findViewById(R.id.tv_temp);
        tvWeather = findViewById(R.id.tv_weather);
        tvTempRange = findViewById(R.id.tv_temp_range);
        tvWind = findViewById(R.id.tv_wind);
        tvHumidity = findViewById(R.id.tv_humidity);
        tvFeelsLike = findViewById(R.id.tv_feels_like);
        tvVisibility = findViewById(R.id.tv_visibility);
        tvSunrise = findViewById(R.id.tv_sunrise);
        tvSunset = findViewById(R.id.tv_sunset);
        tvAirAqi = findViewById(R.id.tv_air_aqi);

        ivCurrentIcon = findViewById(R.id.iv_current_icon);

        llHourly = findViewById(R.id.ll_hourly);
        llDaily = findViewById(R.id.ll_daily);
        llAir = findViewById(R.id.ll_air);
        llAlerts = findViewById(R.id.ll_alerts);
        llIndices = findViewById(R.id.ll_indices);

        btnBack = findViewById(R.id.btn_back);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnWeatherDetail = findViewById(R.id.btn_weather_detail);
        btnHourlyAll = findViewById(R.id.btn_hourly_all);
        btnDailyAll = findViewById(R.id.btn_daily_all);

        btnBack.setOnClickListener(v -> finish());
        btnRefresh.setOnClickListener(v -> loadWeatherData());

        // 天气预报详情按钮
        btnWeatherDetail.setOnClickListener(v -> {
            showWeatherForecastDetail();
        });

        // 查看全部小时预报
        btnHourlyAll.setOnClickListener(v -> {
            showHourlyForecastDetail();
        });

        // 查看15日天气
        btnDailyAll.setOnClickListener(v -> {
            showDailyForecastDetail();
        });

        tvCity.setText(city);
    }

    private void loadWeatherData() {
        setLoading(true);

        new Thread(() -> {
            try {
                String currentWeather;
                if (hasLocation) {
                    currentWeather = weatherService.getCurrentWeatherByLocation(lat, lon, city).get();
                } else {
                    currentWeather = weatherService.getCurrentWeather(city).get();
                }
                parseAndUpdateCurrentWeather(currentWeather);

                String hourlyForecast;
                if (hasLocation) {
                    hourlyForecast = weatherService.getHourlyByLocation(lat, lon).get();
                } else {
                    hourlyForecast = weatherService.getHourly(city).get();
                }
                parseAndUpdateHourly(hourlyForecast);

                String dailyForecast;
                if (hasLocation) {
                    dailyForecast = weatherService.getForecastByLocation(lat, lon).get();
                } else {
                    dailyForecast = weatherService.getForecast(city).get();
                }
                parseAndUpdateDaily(dailyForecast);

                String airQuality;
                if (hasLocation) {
                    airQuality = weatherService.getAirQualityByLocation(lat, lon).get();
                } else {
                    airQuality = weatherService.getAirQuality(city).get();
                }
                parseAndUpdateAir(airQuality);

                String alerts;
                if (hasLocation) {
                    alerts = weatherService.getAlertsByLocation(lat, lon).get();
                } else {
                    alerts = weatherService.getAlerts(city).get();
                }
                parseAndUpdateAlerts(alerts);

                String indices;
                if (hasLocation) {
                    indices = weatherService.getIndicesByLocation(lat, lon).get();
                } else {
                    indices = weatherService.getIndices(city).get();
                }
                parseAndUpdateIndices(indices);

                String sunInfo;
                if (hasLocation) {
                    sunInfo = weatherService.getSunByLocation(lat, lon).get();
                } else {
                    sunInfo = "暂无数据";
                }
                parseAndUpdateSun(sunInfo);

                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        setLoading(false);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading weather data", e);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        setLoading(false);
                        if (tvWeather != null) {
                            tvWeather.setText("加载失败");
                        }
                    }
                });
            }
        }).start();
    }

    private void setLoading(boolean loading) {
        if (loading) {
            if (tvTemp != null) tvTemp.setText("--");
            if (tvWeather != null) tvWeather.setText("加载中...");
            if (tvTempRange != null) tvTempRange.setText("");
            if (tvWind != null) tvWind.setText("暂无");
            if (tvHumidity != null) tvHumidity.setText("--%");
            if (tvFeelsLike != null) tvFeelsLike.setText("--°");
            if (tvVisibility != null) tvVisibility.setText("--km");
            if (tvSunrise != null) tvSunrise.setText("--:--");
            if (tvSunset != null) tvSunset.setText("--:--");
            if (tvAirAqi != null) tvAirAqi.setText("--");
            if (llHourly != null) llHourly.removeAllViews();
            if (llDaily != null) llDaily.removeAllViews();
            if (llAir != null) llAir.removeAllViews();
            if (llAlerts != null) llAlerts.removeAllViews();
            if (llIndices != null) llIndices.removeAllViews();
        }
    }

    private void parseAndUpdateCurrentWeather(String weatherText) {
        if (weatherText == null) return;

        String[] lines = weatherText.split("\n");
        String temp = "--";
        String weather = "";
        String humidity = "--";
        String wind = "";
        String feelsLike = "--";
        String visibility = "--";
        String iconCode = "900";
        String highTemp = "";
        String lowTemp = "";

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("温度:")) {
                temp = line.substring(3).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("天气:")) {
                weather = line.substring(3).trim();
            } else if (line.startsWith("湿度:")) {
                humidity = line.substring(3).trim();
            } else if (line.startsWith("风速:")) {
                wind = line.substring(3).trim();
            } else if (line.startsWith("风向:")) {
                String dir = line.substring(3).trim();
                if (!wind.isEmpty()) wind = dir + " " + wind;
                else wind = dir;
            } else if (line.startsWith("体感温度:")) {
                feelsLike = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("能见度:")) {
                visibility = line.substring(4).trim();
            } else if (line.startsWith("图标:")) {
                iconCode = line.substring(3).trim();
            } else if (line.startsWith("最高温度:")) {
                highTemp = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("最低温度:")) {
                lowTemp = line.substring(5).trim().replace("°C", "").replace("°", "");
            }
        }

        final String finalTemp = temp;
        final String finalWeather = weather;
        final String finalHumidity = humidity;
        final String finalWind = wind;
        final String finalFeelsLike = feelsLike;
        final String finalVisibility = visibility;
        final String finalIconCode = iconCode;
        final String finalHighTemp = highTemp;
        final String finalLowTemp = lowTemp;

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (tvTemp != null) tvTemp.setText(finalTemp);
            if (tvWeather != null) tvWeather.setText(finalWeather.isEmpty() ? "暂无" : finalWeather);
            if (tvHumidity != null) tvHumidity.setText(finalHumidity);
            if (tvWind != null) tvWind.setText(finalWind.isEmpty() ? "暂无" : finalWind);
            if (tvFeelsLike != null) tvFeelsLike.setText(finalFeelsLike + "°");
            if (tvVisibility != null) tvVisibility.setText(finalVisibility);
            if (tvTempRange != null) {
                if (!finalHighTemp.isEmpty() && !finalLowTemp.isEmpty()) {
                    tvTempRange.setText("最高" + finalHighTemp + "° 最低" + finalLowTemp + "°");
                }
            }
            loadWeatherIcon(ivCurrentIcon, finalIconCode);
        });
    }

    private void loadWeatherIcon(ImageView imageView, String iconCode) {
        if (imageView == null || iconCode == null || iconCode.isEmpty()) return;
        
        String iconUrl = QWEATHER_ICON_URL + iconCode + ".png";
        Glide.with(this)
            .load(iconUrl)
            .placeholder(R.drawable.ic_weather)
            .error(R.drawable.ic_weather)
            .into(imageView);
    }

    private void parseAndUpdateHourly(String hourlyText) {
        if (hourlyText == null) return;

        String[] lines = hourlyText.split("\n");
        StringBuilder items = new StringBuilder();

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("时间:")) {
                String time = line.substring(3).trim();
                items.append(time).append("\n");
            } else if (line.startsWith("温度:")) {
                String temp = line.substring(3).trim().replace("°C", "") + "°";
                items.append(temp).append("\n");
            } else if (line.startsWith("天气:")) {
                String weather = line.substring(3).trim();
                items.append(weather).append("\n");
            } else if (line.startsWith("降水:")) {
                String pop = line.substring(3).trim();
                items.append(pop).append("\n");
            } else if (line.startsWith("图标:")) {
                String icon = line.substring(3).trim();
                items.append(icon).append("\n");
            } else if (line.startsWith("风力:")) {
                items.append("\n");
            }
        }

        final String finalItems = items.toString();
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || llHourly == null) return;
            llHourly.removeAllViews();
            String[] parts = finalItems.split("\n\n");
            for (String part : parts) {
                if (part.trim().isEmpty()) continue;
                String[] lines2 = part.trim().split("\n");
                if (lines2.length >= 4) {
                    LinearLayout item = new LinearLayout(this);
                    item.setOrientation(LinearLayout.VERTICAL);
                    item.setPadding(16, 12, 16, 12);
                    item.setGravity(android.view.Gravity.CENTER);

                    TextView tvTime = new TextView(this);
                    tvTime.setText(lines2[0]);
                    tvTime.setTextSize(13);
                    tvTime.setTextColor(getResources().getColor(R.color.weather_dark_text_secondary));
                    tvTime.setPadding(0, 0, 0, 8);

                    String iconCode = lines2.length > 4 ? lines2[4] : "900";
                    ImageView ivIcon = new ImageView(this);
                    ivIcon.setLayoutParams(new LinearLayout.LayoutParams(48, 48));
                    ivIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    ivIcon.setPadding(0, 0, 0, 8);
                    loadWeatherIcon(ivIcon, iconCode);

                    TextView tvTemp = new TextView(this);
                    tvTemp.setText(lines2[1]);
                    tvTemp.setTextSize(18);
                    tvTemp.setTypeface(null, android.graphics.Typeface.BOLD);
                    tvTemp.setTextColor(getResources().getColor(R.color.weather_dark_text));
                    tvTemp.setPadding(0, 8, 0, 4);

                    TextView tvWeather = new TextView(this);
                    tvWeather.setText(lines2[2]);
                    tvWeather.setTextSize(12);
                    tvWeather.setTextColor(getResources().getColor(R.color.weather_dark_text_secondary));
                    tvWeather.setPadding(0, 0, 0, 4);

                    TextView tvPop = new TextView(this);
                    tvPop.setText("降水 " + lines2[3]);
                    tvPop.setTextSize(11);
                    tvPop.setTextColor(getResources().getColor(R.color.weather_dark_text_tertiary));

                    item.addView(tvTime);
                    item.addView(ivIcon);
                    item.addView(tvTemp);
                    item.addView(tvWeather);
                    item.addView(tvPop);
                    llHourly.addView(item);
                }
            }
        });
    }

    private void parseAndUpdateDaily(String dailyText) {
        if (dailyText == null) return;

        String[] lines = dailyText.split("\n");
        StringBuilder items = new StringBuilder();

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("日期:")) {
                String date = line.substring(3).trim();
                items.append(date).append("\n");
            } else if (line.startsWith("最高温度:")) {
                String high = line.substring(5).trim().replace("°C", "").replace("°", "");
                items.append(high).append("\n");
            } else if (line.startsWith("最低温度:")) {
                String low = line.substring(5).trim().replace("°C", "").replace("°", "");
                items.append(low).append("\n");
            } else if (line.startsWith("白天天气:")) {
                String weather = line.substring(5).trim();
                items.append(weather).append("\n");
            } else if (line.startsWith("夜间天气:")) {
                items.append("\n");
            }
        }

        final String finalItems = items.toString();
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || llDaily == null) return;
            llDaily.removeAllViews();
            String[] parts = finalItems.split("\n\n");
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (part.trim().isEmpty()) continue;
                String[] lines2 = part.trim().split("\n");
                if (lines2.length >= 4) {
                    LinearLayout item = new LinearLayout(this);
                    item.setOrientation(LinearLayout.HORIZONTAL);
                    item.setPadding(16, 14, 16, 14);
                    item.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    item.setBackgroundResource(R.drawable.weather_dark_card);

                    if (i > 0) {
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        lp.setMargins(0, 8, 0, 0);
                        item.setLayoutParams(lp);
                    }

                    TextView tvDate = new TextView(this);
                    tvDate.setText(lines2[0]);
                    tvDate.setTextSize(14);
                    tvDate.setTextColor(getResources().getColor(R.color.weather_dark_text_secondary));
                    tvDate.setLayoutParams(new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f));

                    TextView tvWeather = new TextView(this);
                    tvWeather.setText(lines2[3]);
                    tvWeather.setTextSize(14);
                    tvWeather.setTextColor(getResources().getColor(R.color.weather_dark_text));
                    tvWeather.setLayoutParams(new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                    tvWeather.setGravity(android.view.Gravity.CENTER);

                    LinearLayout tempLayout = new LinearLayout(this);
                    tempLayout.setOrientation(LinearLayout.HORIZONTAL);
                    tempLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    tempLayout.setLayoutParams(new LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1));

                    TextView tvHigh = new TextView(this);
                    tvHigh.setText(lines2[1] + "°");
                    tvHigh.setTextSize(14);
                    tvHigh.setTextColor(getResources().getColor(R.color.orange_400));
                    tvHigh.setTypeface(null, android.graphics.Typeface.BOLD);
                    tvHigh.setPadding(0, 0, 4, 0);

                    TextView tvSeparator = new TextView(this);
                    tvSeparator.setText(" / ");
                    tvSeparator.setTextSize(14);
                    tvSeparator.setTextColor(getResources().getColor(R.color.weather_dark_text_secondary));

                    TextView tvLow = new TextView(this);
                    tvLow.setText(lines2[2] + "°");
                    tvLow.setTextSize(14);
                    tvLow.setTextColor(getResources().getColor(R.color.blue_400));
                    tvLow.setPadding(4, 0, 0, 0);

                    tempLayout.addView(tvHigh);
                    tempLayout.addView(tvSeparator);
                    tempLayout.addView(tvLow);

                    item.addView(tvDate);
                    item.addView(tvWeather);
                    item.addView(tempLayout);
                    llDaily.addView(item);
                }
            }
        });
    }

    private void parseAndUpdateAir(String airText) {
        if (airText == null) return;

        String aqiValue = "";

        String[] lines = airText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("AQI:")) {
                aqiValue = line.substring(4).trim();
                break;
            } else if (line.startsWith("aqi:")) {
                aqiValue = line.substring(4).trim();
                break;
            }
        }

        final String finalAqi = aqiValue;

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (tvAirAqi != null && !finalAqi.isEmpty()) {
                tvAirAqi.setText(finalAqi);
            }

            if (llAir == null) return;
            llAir.removeAllViews();
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("空气质量:")) continue;

                TextView tv = new TextView(this);
                tv.setText(line);
                tv.setTextSize(14);
                tv.setTextColor(getResources().getColor(R.color.weather_dark_text));
                tv.setPadding(0, 6, 0, 6);
                llAir.addView(tv);
            }
        });
    }

    private void parseAndUpdateAlerts(String alertsText) {
        if (alertsText == null) return;

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || llAlerts == null) return;
            llAlerts.removeAllViews();
            String[] lines = alertsText.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("天气预警:")) continue;

                TextView tv = new TextView(this);
                tv.setText(line);
                tv.setTextSize(14);
                tv.setTextColor(getResources().getColor(R.color.weather_dark_text));
                tv.setPadding(0, 6, 0, 6);
                llAlerts.addView(tv);
            }
        });
    }

    private void parseAndUpdateIndices(String indicesText) {
        if (indicesText == null) return;

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || llIndices == null) return;
            llIndices.removeAllViews();
            String[] lines = indicesText.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("生活指数:")) continue;

                TextView tv = new TextView(this);
                tv.setText(line);
                tv.setTextSize(13);
                tv.setTextColor(getResources().getColor(R.color.weather_dark_text));
                tv.setPadding(0, 6, 0, 6);
                llIndices.addView(tv);
            }
        });
    }

    private void parseAndUpdateSun(String sunText) {
        if (sunText == null) return;

        String sunrise = "--:--";
        String sunset = "--:--";

        String[] lines = sunText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("日出:")) {
                sunrise = line.substring(3).trim();
            } else if (line.startsWith("日落:")) {
                sunset = line.substring(3).trim();
            }
        }

        final String finalSunrise = sunrise;
        final String finalSunset = sunset;

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (tvSunrise != null) tvSunrise.setText(finalSunrise);
            if (tvSunset != null) tvSunset.setText(finalSunset);

            // Calculate and display sun position and daylight duration
            updateSunPosition(finalSunrise, finalSunset);
        });
    }

    /**
     * Update sun position on the arc and daylight duration.
     */
    private void updateSunPosition(String sunrise, String sunset) {
        try {
            TextView tvDuration = findViewById(R.id.tv_sun_duration);
            TextView tvSunPosition = findViewById(R.id.tv_sun_position);

            long sunriseMs = parseTimeToMillis(sunrise);
            long sunsetMs = parseTimeToMillis(sunset);

            if (sunriseMs < 0 || sunsetMs < 0) return;

            long now = System.currentTimeMillis();
            long dayLength = sunsetMs - sunriseMs;

            // Calculate daylight duration
            if (tvDuration != null && dayLength > 0) {
                long hours = dayLength / (1000 * 60 * 60);
                long minutes = (dayLength % (1000 * 60 * 60)) / (1000 * 60);
                tvDuration.setText(String.format(Locale.CHINA, "日照 %dh %02dm", hours, minutes));
            }

            // Calculate sun position on arc (0 = sunrise, 1 = sunset)
            if (tvSunPosition != null && dayLength > 0) {
                double progress = Math.max(0, Math.min(1, (double)(now - sunriseMs) / dayLength));

                // Arc dimensions
                int arcWidth = 120;
                int arcHeight = 60;

                // Parabolic position: y = 4 * height * (progress - 0.5)^2
                float x = (float)(progress * arcWidth);
                float y = (float)(4 * arcHeight * Math.pow(progress - 0.5, 2));

                // Center the emoji and flip y axis
                x -= 9; // half of emoji width
                y = arcHeight - y - 9; // flip y and offset

                // Show sun only during daylight
                if (now >= sunriseMs && now <= sunsetMs) {
                    tvSunPosition.setVisibility(View.VISIBLE);
                    tvSunPosition.setTranslationX(x);
                    tvSunPosition.setTranslationY(y);
                } else {
                    tvSunPosition.setVisibility(View.GONE);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to update sun position", e);
        }
    }

    /**
     * Parse time string (HH:mm) to milliseconds since today's midnight.
     */
    private long parseTimeToMillis(String timeStr) {
        try {
            String[] parts = timeStr.split(":");
            if (parts.length != 2) return -1;

            int hour = Integer.parseInt(parts[0].trim());
            int minute = Integer.parseInt(parts[1].trim());

            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
            cal.set(java.util.Calendar.MINUTE, minute);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);

            return cal.getTimeInMillis();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 显示天气预报详情（弹窗显示完整预报信息）
     */
    private void showWeatherForecastDetail() {
        new Thread(() -> {
            try {
                String forecast;
                if (hasLocation) {
                    forecast = weatherService.getForecastByLocation(lat, lon).get();
                } else {
                    forecast = weatherService.getForecast(city).get();
                }

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;

                    // 构建详细预报内容
                    StringBuilder content = new StringBuilder();
                    content.append("未来天气预报 - ").append(city).append("\n\n");

                    String[] lines = forecast.split("\n");
                    for (String line : lines) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            content.append(line).append("\n");
                        }
                    }

                    // 显示对话框
                    new android.app.AlertDialog.Builder(WeatherDetailActivity.this)
                        .setTitle("天气预报详情")
                        .setMessage(content.toString())
                        .setPositiveButton("确定", null)
                        .show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading forecast detail", e);
            }
        }).start();
    }

    /**
     * 显示完整小时预报
     */
    private void showHourlyForecastDetail() {
        new Thread(() -> {
            try {
                String hourly;
                if (hasLocation) {
                    hourly = weatherService.getHourlyByLocation(lat, lon).get();
                } else {
                    hourly = weatherService.getHourly(city).get();
                }

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;

                    StringBuilder content = new StringBuilder();
                    content.append("24小时预报 - ").append(city).append("\n\n");

                    String[] lines = hourly.split("\n");
                    for (String line : lines) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            content.append(line).append("\n");
                        }
                    }

                    new android.app.AlertDialog.Builder(WeatherDetailActivity.this)
                        .setTitle("24小时天气预报")
                        .setMessage(content.toString())
                        .setPositiveButton("确定", null)
                        .show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading hourly detail", e);
            }
        }).start();
    }

    /**
     * 显示15日天气预报
     */
    private void showDailyForecastDetail() {
        new Thread(() -> {
            try {
                String forecast;
                if (hasLocation) {
                    forecast = weatherService.getForecastByLocation(lat, lon).get();
                } else {
                    forecast = weatherService.getForecast(city).get();
                }

                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;

                    StringBuilder content = new StringBuilder();
                    content.append("未来天气预报 - ").append(city).append("\n\n");

                    String[] lines = forecast.split("\n");
                    for (String line : lines) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            content.append(line).append("\n");
                        }
                    }

                    new android.app.AlertDialog.Builder(WeatherDetailActivity.this)
                        .setTitle("未来15日天气")
                        .setMessage(content.toString())
                        .setPositiveButton("确定", null)
                        .show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading daily forecast", e);
            }
        }).start();
    }
}