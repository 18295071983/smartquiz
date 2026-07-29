package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.oilquiz.app.R;
import com.oilquiz.app.util.QWeatherIconFont;
import com.oilquiz.app.util.QWeatherIconMapper;
import com.oilquiz.app.weather.CitySearchDialog;
import com.oilquiz.app.weather.QWeatherCityManager;
import com.oilquiz.app.weather.WeatherService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class WeatherDetailActivity extends AppCompatActivity {

    private static final String TAG = "WeatherDetail";

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
    private TextView tvPressure;
    private TextView tvUv;
    private TextView tvAirCategory;
    private TextView tvUpdateTime;
    private TextView tvLocationStatus;
    private TextView tvPrecip;
    private TextView tvCloud;
    private TextView tvDew;
    private TextView tvWind360;

    private TextView ivCurrentIcon;
    private TextView tvHumidityIcon;
    private TextView tvWindIcon;
    private TextView tvVisibilityIcon;
    private TextView tvPressureIcon;
    private TextView tvSunriseIcon;
    private TextView tvSunsetIcon;
    private TextView tvPrecipIcon;
    private TextView tvCloudIcon;
    private TextView tvDewIcon;
    private TextView tvWind360Icon;
    private TextView tvAirIcon;
    private TextView tvSunIcon;
    private TextView tvUvIcon;
    private TextView tvMoreIcon;
    private TextView tvIndicesIcon;
    private TextView tvAlertIcon;

    private TextView tvAirPm25;
    private TextView tvAirPm10;
    private TextView tvAirNo2;
    private TextView tvAirSo2;

    private LinearLayout llHourly;
    private LinearLayout llDaily;
    private LinearLayout llAlerts;
    private RecyclerView rvIndices;

    private ImageView btnBack;
    private ImageView btnShare;
    private TextView tvView15d;

    private LinearLayout cardAlerts;

    private String fxLinkCurrent = "";

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
        setupIconFonts();
        setupIndicesRecyclerView();
        showMockData();
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
        tvAirCategory = findViewById(R.id.tv_air_category);
        tvPressure = findViewById(R.id.tv_pressure);
        tvUv = findViewById(R.id.tv_uv);
        tvUpdateTime = findViewById(R.id.tv_update_time);
        tvLocationStatus = findViewById(R.id.tv_location_status);
        tvPrecip = findViewById(R.id.tv_precip);
        tvCloud = findViewById(R.id.tv_cloud);
        tvDew = findViewById(R.id.tv_dew);
        tvWind360 = findViewById(R.id.tv_wind360);

        ivCurrentIcon = findViewById(R.id.iv_current_icon);
        tvHumidityIcon = findViewById(R.id.tv_humidity_icon);
        tvWindIcon = findViewById(R.id.tv_wind_icon);
        tvVisibilityIcon = findViewById(R.id.tv_visibility_icon);
        tvPressureIcon = findViewById(R.id.tv_pressure_icon);
        tvSunriseIcon = findViewById(R.id.tv_sunrise_icon);
        tvSunsetIcon = findViewById(R.id.tv_sunset_icon);
        tvPrecipIcon = findViewById(R.id.tv_precip_icon);
        tvCloudIcon = findViewById(R.id.tv_cloud_icon);
        tvDewIcon = findViewById(R.id.tv_dew_icon);
        tvWind360Icon = findViewById(R.id.tv_wind360_icon);
        tvAirIcon = findViewById(R.id.tv_air_icon);
        tvSunIcon = findViewById(R.id.tv_sun_icon);
        tvUvIcon = findViewById(R.id.tv_uv_icon);
        tvMoreIcon = findViewById(R.id.tv_more_icon);
        tvIndicesIcon = findViewById(R.id.tv_indices_icon);
        tvAlertIcon = findViewById(R.id.tv_alert_icon);

        tvAirPm25 = findViewById(R.id.tv_air_pm25);
        tvAirPm10 = findViewById(R.id.tv_air_pm10);
        tvAirNo2 = findViewById(R.id.tv_air_no2);
        tvAirSo2 = findViewById(R.id.tv_air_so2);

        llHourly = findViewById(R.id.ll_hourly);
        llDaily = findViewById(R.id.ll_daily);
        llAlerts = findViewById(R.id.ll_alerts);
        rvIndices = findViewById(R.id.rv_indices);
        cardAlerts = findViewById(R.id.card_alerts);

        btnBack = findViewById(R.id.btn_back);
        btnShare = findViewById(R.id.btn_share);
        tvView15d = findViewById(R.id.tv_view_15d);

        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }
        if (btnShare != null) {
            btnShare.setOnClickListener(v -> shareWeather());
        }
        if (tvView15d != null) {
            tvView15d.setOnClickListener(v -> openFxLink());
        }
        if (tvCity != null) {
            tvCity.setOnClickListener(v -> openCitySearchDialog());
        }
    }

    private void openCitySearchDialog() {
        CitySearchDialog dialog = new CitySearchDialog(this, cityEntry -> {
            if (cityEntry != null && cityEntry.nameZh != null) {
                city = cityEntry.nameZh;
                lat = cityEntry.latitude;
                lon = cityEntry.longitude;
                hasLocation = (lat != 0 && lon != 0);
                if (tvCity != null) {
                    tvCity.setText(city);
                }
                loadWeatherData();
            }
        });
        dialog.show();
    }

    private void setupIconFonts() {
        Typeface iconTypeface = QWeatherIconFont.getTypeface(this);
        if (ivCurrentIcon != null) ivCurrentIcon.setTypeface(iconTypeface);
        if (tvHumidityIcon != null) tvHumidityIcon.setTypeface(iconTypeface);
        if (tvWindIcon != null) tvWindIcon.setTypeface(iconTypeface);
        if (tvVisibilityIcon != null) tvVisibilityIcon.setTypeface(iconTypeface);
        if (tvPressureIcon != null) tvPressureIcon.setTypeface(iconTypeface);
        if (tvSunriseIcon != null) tvSunriseIcon.setTypeface(iconTypeface);
        if (tvSunsetIcon != null) tvSunsetIcon.setTypeface(iconTypeface);
        if (tvPrecipIcon != null) tvPrecipIcon.setTypeface(iconTypeface);
        if (tvCloudIcon != null) tvCloudIcon.setTypeface(iconTypeface);
        if (tvDewIcon != null) tvDewIcon.setTypeface(iconTypeface);
        if (tvWind360Icon != null) tvWind360Icon.setTypeface(iconTypeface);
        if (tvAirIcon != null) tvAirIcon.setTypeface(iconTypeface);
        if (tvSunIcon != null) tvSunIcon.setTypeface(iconTypeface);
        if (tvUvIcon != null) tvUvIcon.setTypeface(iconTypeface);
        if (tvMoreIcon != null) tvMoreIcon.setTypeface(iconTypeface);
        if (tvIndicesIcon != null) tvIndicesIcon.setTypeface(iconTypeface);
        if (tvAlertIcon != null) tvAlertIcon.setTypeface(iconTypeface);
    }

    private void setupIndicesRecyclerView() {
        if (rvIndices != null) {
            rvIndices.setLayoutManager(new GridLayoutManager(this, 3));
            rvIndices.setAdapter(new IndicesAdapter(new ArrayList<>()));
        }
    }

    private void showMockData() {
        if (tvCity != null) tvCity.setText(city);
        if (tvTemp != null) tvTemp.setText("--°");
        if (tvWeather != null) tvWeather.setText("加载中...");
        if (tvTempRange != null) tvTempRange.setText("");
        if (tvFeelsLike != null) tvFeelsLike.setText("体感温度 --°");

        if (ivCurrentIcon != null) {
            ivCurrentIcon.setText(QWeatherIconFont.getIcon("999"));
        }
        if (tvHumidityIcon != null) tvHumidityIcon.setText(QWeatherIconFont.getIcon("501"));
        if (tvWindIcon != null) tvWindIcon.setText(QWeatherIconFont.getIcon("900"));
        if (tvVisibilityIcon != null) tvVisibilityIcon.setText(QWeatherIconFont.getIcon("500"));
        if (tvPressureIcon != null) tvPressureIcon.setText(QWeatherIconFont.getIcon("999"));
        if (tvSunriseIcon != null) tvSunriseIcon.setText(QWeatherIconFont.getIcon("100"));
        if (tvSunsetIcon != null) tvSunsetIcon.setText(QWeatherIconFont.getIcon("150"));
        if (tvPrecipIcon != null) tvPrecipIcon.setText(QWeatherIconFont.getIcon("305"));
        if (tvCloudIcon != null) tvCloudIcon.setText(QWeatherIconFont.getIcon("104"));
        if (tvDewIcon != null) tvDewIcon.setText(QWeatherIconFont.getIcon("313"));
        if (tvWind360Icon != null) tvWind360Icon.setText(QWeatherIconFont.getIcon("900"));
        if (tvAirIcon != null) tvAirIcon.setText(QWeatherIconFont.getIcon("1001"));
        if (tvSunIcon != null) tvSunIcon.setText(QWeatherIconFont.getIcon("100"));
        if (tvUvIcon != null) tvUvIcon.setText(QWeatherIconFont.getIcon("1004"));
        if (tvMoreIcon != null) tvMoreIcon.setText(QWeatherIconFont.getIcon("999"));
        if (tvIndicesIcon != null) tvIndicesIcon.setText(QWeatherIconFont.getIcon("1006"));
        if (tvAlertIcon != null) tvAlertIcon.setText(QWeatherIconFont.getIcon("1010"));

        if (tvHumidity != null) tvHumidity.setText("--%");
        if (tvWind != null) tvWind.setText("--");
        if (tvVisibility != null) tvVisibility.setText("--");
        if (tvPressure != null) tvPressure.setText("--");
        if (tvSunrise != null) tvSunrise.setText("--:--");
        if (tvSunset != null) tvSunset.setText("--:--");
        if (tvUv != null) tvUv.setText("紫外线强度: --");
        if (tvPrecip != null) tvPrecip.setText("--");
        if (tvCloud != null) tvCloud.setText("--");
        if (tvDew != null) tvDew.setText("--°");
        if (tvWind360 != null) tvWind360.setText("--");

        if (tvAirAqi != null) tvAirAqi.setText("--");
        if (tvAirCategory != null) tvAirCategory.setText("暂无数据");
        if (tvAirPm25 != null) tvAirPm25.setText("--");
        if (tvAirPm10 != null) tvAirPm10.setText("--");
        if (tvAirNo2 != null) tvAirNo2.setText("--");
        if (tvAirSo2 != null) tvAirSo2.setText("--");

        if (llHourly != null) {
            llHourly.removeAllViews();
            TextView tvLoading = new TextView(this);
            tvLoading.setText("正在加载...");
            tvLoading.setTextSize(13);
            tvLoading.setTextColor(getResources().getColor(R.color.weather_dark_text_secondary));
            tvLoading.setPadding(16, 24, 16, 24);
            llHourly.addView(tvLoading);
        }

        if (llDaily != null) {
            llDaily.removeAllViews();
        }

        if (llAlerts != null) {
            llAlerts.removeAllViews();
        }

        if (cardAlerts != null) {
            cardAlerts.setVisibility(View.GONE);
        }

        if (tvUpdateTime != null) {
            tvUpdateTime.setText("更新于 --:--");
        }
    }

    private void loadWeatherData() {
        if (weatherService == null) return;

        java.util.concurrent.CompletableFuture<WeatherService.WeatherBatchResult> future;
        if (hasLocation) {
            future = weatherService.getAllWeatherByLocation(lat, lon);
        } else {
            future = weatherService.getAllWeather(city);
        }

        future.thenAccept(result -> {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                if (result.currentWeather != null) {
                    parseAndUpdateCurrentWeather(result.currentWeather);
                }
                if (result.hourlyForecast != null) {
                    parseAndUpdateHourlyWeather(result.hourlyForecast);
                }
                if (result.dailyForecast != null) {
                    parseAndUpdateDailyWeather(result.dailyForecast);
                }
                if (result.airQuality != null) {
                    parseAndUpdateAirQuality(result.airQuality);
                }
                if (result.indices != null) {
                    parseAndUpdateIndices(result.indices);
                }
                if (result.alerts != null) {
                    parseAndUpdateAlerts(result.alerts);
                }
                if (result.sunInfo != null) {
                    parseAndUpdateSunInfo(result.sunInfo);
                }
                if (result.minutely != null) {
                    parseAndUpdateMinutely(result.minutely);
                }

                if (result.fxLinkCurrent != null && !result.fxLinkCurrent.isEmpty()) {
                    fxLinkCurrent = result.fxLinkCurrent;
                }

                if (tvUpdateTime != null) {
                    String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
                    tvUpdateTime.setText("更新于 " + time);
                }
            });
        }).exceptionally(e -> {
            Log.e(TAG, "Failed to load weather data: " + e.getMessage(), e);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (tvWeather != null) tvWeather.setText("加载失败");
                if (tvUpdateTime != null) tvUpdateTime.setText("更新失败: " + e.getMessage());
            });
            return null;
        });
    }

    private void parseAndUpdateSunInfo(String sunText) {
        if (sunText == null) return;

        String sunrise = "";
        String sunset = "";

        String[] lines = sunText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("日出:")) {
                String time = line.substring(3).trim();
                sunrise = simplifyTime(time);
            } else if (line.startsWith("日落:")) {
                String time = line.substring(3).trim();
                sunset = simplifyTime(time);
            }
        }

        final String finalSunrise = sunrise;
        final String finalSunset = sunset;

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (tvSunrise != null) tvSunrise.setText(finalSunrise.isEmpty() ? "--:--" : finalSunrise);
            if (tvSunset != null) tvSunset.setText(finalSunset.isEmpty() ? "--:--" : finalSunset);
        });
    }

    private String simplifyTime(String time) {
        if (time == null || time.isEmpty()) return "";
        int tIdx = time.indexOf('T');
        if (tIdx > 0) {
            time = time.substring(tIdx + 1);
        }
        int plusIdx = time.indexOf('+');
        if (plusIdx > 0) {
            time = time.substring(0, plusIdx);
        }
        if (time.length() >= 5) {
            return time.substring(0, 5);
        }
        return time;
    }

    private void parseAndUpdateCurrentWeather(String weatherText) {
        if (weatherText == null) return;

        String[] lines = weatherText.split("\n");
        String temp = "--";
        String weather = "";
        String humidity = "--";
        String windDir = "";
        String windScale = "";
        String windSpeed = "";
        String feelsLike = "--";
        String visibility = "--";
        String iconCode = "999";
        String highTemp = "";
        String lowTemp = "";
        String pressure = "--";
        String uv = "";
        String precip = "";
        String cloud = "";
        String dew = "";
        String wind360 = "";
        String fxLink = "";
        String cityName = "";

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("城市:")) {
                cityName = line.substring(3).trim();
            } else if (line.startsWith("温度:")) {
                temp = line.substring(3).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("天气:")) {
                weather = line.substring(3).trim();
            } else if (line.startsWith("湿度:")) {
                humidity = line.substring(3).trim().replace("%", "");
            } else if (line.startsWith("风速:")) {
                windSpeed = line.substring(3).trim().replace("km/h", "");
            } else if (line.startsWith("风向:")) {
                windDir = line.substring(3).trim();
            } else if (line.startsWith("风力:")) {
                windScale = line.substring(3).trim();
            } else if (line.startsWith("体感温度:")) {
                feelsLike = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("能见度:")) {
                visibility = line.substring(4).trim().replace("km", "");
            } else if (line.startsWith("图标:")) {
                iconCode = line.substring(3).trim();
            } else if (line.startsWith("最高温度:")) {
                highTemp = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("最低温度:")) {
                lowTemp = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("气压:")) {
                pressure = line.substring(3).trim().replace("hPa", "");
            } else if (line.startsWith("紫外线:")) {
                uv = line.substring(4).trim();
            } else if (line.startsWith("降水量:")) {
                precip = line.substring(4).trim().replace("mm", "");
            } else if (line.startsWith("云量:")) {
                cloud = line.substring(3).trim().replace("%", "");
            } else if (line.startsWith("露点温度:")) {
                dew = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("风向角度:")) {
                wind360 = line.substring(4).trim().replace("°", "");
            } else if (line.startsWith("链接:")) {
                fxLink = line.substring(3).trim();
            }
        }

        StringBuilder windBuilder = new StringBuilder();
        if (!windDir.isEmpty()) windBuilder.append(windDir);
        if (!windScale.isEmpty()) {
            if (windBuilder.length() > 0) windBuilder.append(" ");
            windBuilder.append(windScale).append("级");
        }
        if (!windSpeed.isEmpty()) {
            if (windBuilder.length() > 0) windBuilder.append(" ");
            windBuilder.append(windSpeed).append("km/h");
        }
        String wind = windBuilder.toString();

        this.fxLinkCurrent = fxLink;

        final String finalTemp = temp;
        final String finalWeather = weather;
        final String finalHumidity = humidity;
        final String finalWind = wind;
        final String finalFeelsLike = feelsLike;
        final String finalVisibility = visibility;
        final String finalIconCode = iconCode;
        final String finalHighTemp = highTemp;
        final String finalLowTemp = lowTemp;
        final String finalPressure = pressure;
        final String finalUv = uv;
        final String finalPrecip = precip;
        final String finalCloud = cloud;
        final String finalDew = dew;
        final String finalWind360 = wind360;
        final String finalCityName = cityName;

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (ivCurrentIcon != null) {
                ivCurrentIcon.setText(QWeatherIconFont.getIcon(finalIconCode));
            }
            if (tvTemp != null) tvTemp.setText(finalTemp + "°");
            if (tvWeather != null) tvWeather.setText(finalWeather.isEmpty() ? "暂无数据" : finalWeather);
            if (tvTempRange != null) {
                if (!finalHighTemp.isEmpty() && !finalLowTemp.isEmpty()) {
                    tvTempRange.setText(finalHighTemp + "° ~ " + finalLowTemp + "°");
                } else {
                    tvTempRange.setText("");
                }
            }
            if (tvFeelsLike != null) tvFeelsLike.setText("体感温度 " + (finalFeelsLike.isEmpty() ? "--°" : finalFeelsLike + "°"));
            if (tvHumidity != null) tvHumidity.setText(finalHumidity.isEmpty() ? "--" : finalHumidity + "%");
            if (tvWind != null) tvWind.setText(finalWind.isEmpty() ? "--" : finalWind);
            if (tvVisibility != null) tvVisibility.setText(finalVisibility.isEmpty() ? "--" : finalVisibility + "km");
            if (tvPressure != null) tvPressure.setText(finalPressure.isEmpty() ? "--" : finalPressure + "hPa");
            if (!finalUv.isEmpty() && tvUv != null) {
                tvUv.setText("紫外线: " + finalUv);
            }
            if (tvPrecip != null) tvPrecip.setText(finalPrecip.isEmpty() ? "--" : finalPrecip + "mm");
            if (tvCloud != null) tvCloud.setText(finalCloud.isEmpty() ? "--" : finalCloud + "%");
            if (tvDew != null) tvDew.setText(finalDew.isEmpty() ? "--°" : finalDew + "°");
            if (tvWind360 != null) tvWind360.setText(finalWind360.isEmpty() ? "--" : finalWind360 + "°");
            if (tvCity != null && !finalCityName.isEmpty()) {
                tvCity.setText(finalCityName);
            }
        });
    }

    private void parseAndUpdateHourlyWeather(String weatherText) {
        if (weatherText == null || llHourly == null) return;

        llHourly.removeAllViews();
        boolean inHourlySection = false;
        int count = 0;

        String[] lines = weatherText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("24小时预报:") || line.startsWith("逐小时预报:")) {
                inHourlySection = true;
                continue;
            }
            if (inHourlySection && line.startsWith("链接:")) {
                continue;
            }
            if (inHourlySection && !line.isEmpty()) {
                addHourlyItem(line);
                count++;
                if (count >= 24) break;
            }
        }

        if (count == 0) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("暂无逐小时数据");
            tvEmpty.setTextSize(13);
            tvEmpty.setTextColor(getResources().getColor(R.color.weather_dark_text_secondary));
            tvEmpty.setPadding(16, 24, 16, 24);
            llHourly.addView(tvEmpty);
        }
    }

    private void addHourlyItem(String line) {
        String time = "--";
        String temp = "--°";
        String iconCode = "999";
        String pop = "";

        String[] parts = line.split(" ");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) continue;

            if (i == 0 && part.length() >= 4) {
                time = part;
            } else if (part.endsWith("°C") || part.endsWith("°")) {
                temp = part.replace("°C", "").replace("°", "") + "°";
            } else if (part.startsWith("图标:")) {
                iconCode = part.substring(3).trim();
            } else if (part.startsWith("降水") && part.endsWith("%")) {
                pop = part.substring(2);
            }
        }

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(android.view.Gravity.CENTER);
        item.setPadding(12, 8, 12, 8);

        TextView tvTime = new TextView(this);
        tvTime.setText(time);
        tvTime.setTextSize(12);
        tvTime.setTextColor(getResources().getColor(R.color.weather_dark_text_secondary));
        item.addView(tvTime);

        TextView tvIcon = new TextView(this);
        tvIcon.setText(QWeatherIconFont.getIcon(iconCode));
        tvIcon.setTextSize(28);
        tvIcon.setTextColor(getResources().getColor(R.color.weather_dark_text));
        tvIcon.setTypeface(QWeatherIconFont.getTypeface(this));
        tvIcon.setPadding(0, 6, 0, 6);
        item.addView(tvIcon);

        TextView tvTemp = new TextView(this);
        tvTemp.setText(temp);
        tvTemp.setTextSize(14);
        tvTemp.setTextColor(getResources().getColor(R.color.weather_dark_text));
        tvTemp.setTypeface(null, android.graphics.Typeface.BOLD);
        item.addView(tvTemp);

        if (!pop.isEmpty()) {
            TextView tvPop = new TextView(this);
            tvPop.setText(pop);
            tvPop.setTextSize(11);
            tvPop.setTextColor(getResources().getColor(R.color.weather_dark_text_secondary));
            item.addView(tvPop);
        }

        llHourly.addView(item);
    }

    private void parseAndUpdateDailyWeather(String weatherText) {
        if (weatherText == null || llDaily == null) return;

        llDaily.removeAllViews();
        boolean inDailySection = false;
        int count = 0;
        String uvFromDaily = "";

        String[] lines = weatherText.split("\n");
        StringBuilder currentDayData = new StringBuilder();
        boolean collectingDay = false;

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("天气预报:") || line.startsWith("7天预报:") || line.startsWith("15天预报:")) {
                inDailySection = true;
                continue;
            }
            if (inDailySection && line.startsWith("链接:")) {
                continue;
            }
            if (!inDailySection) continue;

            if (line.isEmpty()) {
                if (collectingDay && currentDayData.length() > 0) {
                    addDailyItem(currentDayData.toString());
                    count++;
                    if (count >= 7) break;
                    collectingDay = false;
                    currentDayData = new StringBuilder();
                }
            } else if (line.startsWith("白天:") || line.startsWith("夜间:") || 
                       line.startsWith("日出:") || line.startsWith("风向:") || 
                       line.startsWith("湿度:")) {
                if (collectingDay) {
                    currentDayData.append("\n").append(line);
                }
                if (line.startsWith("湿度:") && line.contains("紫外线:")) {
                    int uvIdx = line.indexOf("紫外线:");
                    if (uvIdx > 0) {
                        uvFromDaily = line.substring(uvIdx + 4).trim();
                    }
                }
            } else if (!collectingDay && line.length() >= 8) {
                collectingDay = true;
                currentDayData = new StringBuilder(line);
            }
        }

        if (collectingDay && currentDayData.length() > 0 && count < 7) {
            addDailyItem(currentDayData.toString());
        }

        if (count == 0) {
            TextView tvEmpty = new TextView(this);
            tvEmpty.setText("暂无预报数据");
            tvEmpty.setTextSize(13);
            tvEmpty.setTextColor(getResources().getColor(R.color.weather_dark_text_secondary));
            tvEmpty.setPadding(16, 24, 16, 24);
            llDaily.addView(tvEmpty);
        }

        if (!uvFromDaily.isEmpty()) {
            final String uv = uvFromDaily;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (tvUv != null) {
                    tvUv.setText("紫外线强度: " + uv);
                }
            });
        }
    }

    private void addDailyItem(String data) {
        String date = "--";
        String highTemp = "--°";
        String lowTemp = "--°";
        String weatherDay = "";
        String weatherNight = "";

        String[] lines = data.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("白天:")) {
                String rest = line.substring(3).trim();
                String[] parts = rest.split(" ");
                weatherDay = parts.length > 0 ? parts[0] : "";
                for (String part : parts) {
                    if (part.endsWith("°C") || part.endsWith("°")) {
                        highTemp = part.replace("°C", "").replace("°", "") + "°";
                    }
                }
            } else if (line.startsWith("夜间:")) {
                String rest = line.substring(3).trim();
                String[] parts = rest.split(" ");
                weatherNight = parts.length > 0 ? parts[0] : "";
                for (String part : parts) {
                    if (part.endsWith("°C") || part.endsWith("°")) {
                        lowTemp = part.replace("°C", "").replace("°", "") + "°";
                    }
                }
            } else if (line.length() >= 8 && date.equals("--")) {
                date = line;
            }
        }

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(android.view.Gravity.CENTER_VERTICAL);
        item.setPadding(16, 10, 16, 10);

        TextView tvDay = new TextView(this);
        String dayLabel = date;
        if (dayLabel.length() > 10) {
            dayLabel = dayLabel.substring(5);
        }
        tvDay.setText(dayLabel);
        tvDay.setTextSize(14);
        tvDay.setTextColor(getResources().getColor(R.color.weather_dark_text));
        tvDay.setMinWidth(60);
        item.addView(tvDay);

        TextView tvIconDay = new TextView(this);
        tvIconDay.setText(QWeatherIconFont.getIcon(getIconFromText(weatherDay)));
        tvIconDay.setTextSize(22);
        tvIconDay.setTextColor(getResources().getColor(R.color.weather_dark_text));
        tvIconDay.setTypeface(QWeatherIconFont.getTypeface(this));
        tvIconDay.setPadding(20, 0, 0, 0);
        item.addView(tvIconDay);

        LinearLayout tempsLayout = new LinearLayout(this);
        tempsLayout.setOrientation(LinearLayout.HORIZONTAL);
        tempsLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tempsLayout.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        tempsLayout.setPadding(20, 0, 20, 0);

        TextView tvHigh = new TextView(this);
        tvHigh.setText(highTemp);
        tvHigh.setTextSize(14);
        tvHigh.setTextColor(getResources().getColor(R.color.weather_dark_text));
        tvHigh.setTypeface(null, android.graphics.Typeface.BOLD);
        tempsLayout.addView(tvHigh);

        View space = new View(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(16, 1));
        tempsLayout.addView(space);

        TextView tvLow = new TextView(this);
        tvLow.setText(lowTemp);
        tvLow.setTextSize(14);
        tvLow.setTextColor(getResources().getColor(R.color.weather_dark_text_secondary));
        tempsLayout.addView(tvLow);

        item.addView(tempsLayout);

        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        divider.setBackgroundColor(getResources().getColor(R.color.weather_dark_card_stroke));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(item);
        if (llDaily.getChildCount() > 0) {
            wrap.addView(divider, 0);
        }

        llDaily.addView(wrap);
    }

    private String getIconFromText(String weatherText) {
        if (weatherText == null || weatherText.isEmpty()) return "999";
        if (weatherText.contains("晴")) return "100";
        if (weatherText.contains("云") || weatherText.contains("阴")) return "104";
        if (weatherText.contains("雷")) return "302";
        if (weatherText.contains("雨夹")) return "301";
        if (weatherText.contains("暴雨")) return "318";
        if (weatherText.contains("大暴雨")) return "319";
        if (weatherText.contains("特大暴雨")) return "320";
        if (weatherText.contains("大雨")) return "313";
        if (weatherText.contains("中雨")) return "312";
        if (weatherText.contains("小雨")) return "305";
        if (weatherText.contains("阵雨")) return "300";
        if (weatherText.contains("冰雹")) return "350";
        if (weatherText.contains("雪") && weatherText.contains("阵")) return "400";
        if (weatherText.contains("暴雪")) return "409";
        if (weatherText.contains("大雪")) return "408";
        if (weatherText.contains("中雪")) return "404";
        if (weatherText.contains("小雪")) return "401";
        if (weatherText.contains("雾") || weatherText.contains("霾")) return "500";
        if (weatherText.contains("沙尘")) return "503";
        return "999";
    }

    private void parseAndUpdateAirQuality(String weatherText) {
        if (weatherText == null) return;

        String aqi = "--";
        String category = "暂无数据";
        String pm25 = "--";
        String pm10 = "--";
        String no2 = "--";
        String so2 = "--";

        boolean inAirSection = false;
        String[] lines = weatherText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("空气质量:")) {
                inAirSection = true;
                continue;
            }
            if (inAirSection && line.startsWith("链接:")) {
                continue;
            }
            if (!inAirSection) continue;

            if (line.startsWith("AQI:")) {
                String rest = line.substring(4).trim();
                int parenIdx = rest.indexOf('(');
                if (parenIdx > 0) {
                    aqi = rest.substring(0, parenIdx).trim();
                    String inside = rest.substring(parenIdx);
                    int commaIdx = inside.indexOf(',');
                    if (commaIdx > 0) {
                        String levelPart = inside.substring(1, commaIdx).trim();
                        category = levelPart.replace("等级", "").trim();
                        String catPart = inside.substring(commaIdx + 1).trim();
                        category = category + " " + catPart.replace(")", "").trim();
                    } else {
                        category = inside.substring(1, inside.length() - 1).replace("等级", "").trim();
                    }
                } else {
                    aqi = rest;
                }
            } else if (line.startsWith("PM2.5:")) {
                pm25 = line.substring(6).trim().replace(" μg/m³", "");
            } else if (line.startsWith("PM10:")) {
                pm10 = line.substring(5).trim().replace(" μg/m³", "");
            } else if (line.startsWith("NO2:")) {
                no2 = line.substring(4).trim().replace(" μg/m³", "");
            } else if (line.startsWith("SO2:")) {
                so2 = line.substring(4).trim().replace(" μg/m³", "");
            } else if (line.startsWith("首要污染物:")) {
                category = line.substring(5).trim();
            }
        }

        final String finalAqi = aqi;
        final String finalCategory = category;
        final String finalPm25 = pm25;
        final String finalPm10 = pm10;
        final String finalNo2 = no2;
        final String finalSo2 = so2;

        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (tvAirAqi != null) tvAirAqi.setText(finalAqi);
            if (tvAirCategory != null) tvAirCategory.setText(finalCategory);
            if (tvAirPm25 != null) tvAirPm25.setText(finalPm25);
            if (tvAirPm10 != null) tvAirPm10.setText(finalPm10);
            if (tvAirNo2 != null) tvAirNo2.setText(finalNo2);
            if (tvAirSo2 != null) tvAirSo2.setText(finalSo2);
        });
    }

    private void parseAndUpdateIndices(String weatherText) {
        if (weatherText == null || rvIndices == null) return;

        List<IndexItem> indexList = new ArrayList<>();
        boolean inIndicesSection = false;

        String[] lines = weatherText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("生活指数:") || line.startsWith("天气指数:")) {
                inIndicesSection = true;
                continue;
            }
            if (inIndicesSection && line.startsWith("---")) {
                break;
            }
            if (inIndicesSection && !line.isEmpty() && line.contains(":")) {
                int colonIdx = line.indexOf(":");
                if (colonIdx > 0) {
                    String name = line.substring(0, colonIdx).trim();
                    String value = line.substring(colonIdx + 1).trim();
                    indexList.add(new IndexItem(name, value, getIndexIconCode(name)));
                }
            }
        }

        final List<IndexItem> finalList = indexList;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (rvIndices != null && rvIndices.getAdapter() instanceof IndicesAdapter) {
                ((IndicesAdapter) rvIndices.getAdapter()).updateData(finalList);
            }
        });
    }

    private String getIndexIconCode(String indexName) {
        if (indexName.contains("运动") || indexName.contains("健身")) return "1001";
        if (indexName.contains("洗车")) return "1002";
        if (indexName.contains("穿衣") || indexName.contains("着装")) return "1003";
        if (indexName.contains("紫外线")) return "1004";
        if (indexName.contains("旅游") || indexName.contains("旅行")) return "1005";
        if (indexName.contains("感冒")) return "1006";
        if (indexName.contains("晾晒")) return "1007";
        if (indexName.contains("过敏")) return "1008";
        if (indexName.contains("钓鱼")) return "1009";
        if (indexName.contains("伞")) return "1010";
        return "1001";
    }

    private void parseAndUpdateAlerts(String weatherText) {
        if (weatherText == null || llAlerts == null || cardAlerts == null) return;

        llAlerts.removeAllViews();
        boolean inAlertSection = false;
        int count = 0;
        StringBuilder currentAlert = new StringBuilder();

        String[] lines = weatherText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("天气预警:") || line.startsWith("预警:")) {
                inAlertSection = true;
                continue;
            }
            if (inAlertSection && line.startsWith("链接:")) {
                continue;
            }
            if (!inAlertSection) continue;

            if (line.isEmpty()) {
                if (currentAlert.length() > 0) {
                    addAlertItem(currentAlert.toString());
                    count++;
                    currentAlert = new StringBuilder();
                }
            } else if (line.equals("暂无预警信息") || line.equals("当前无天气预警")) {
                break;
            } else {
                if (currentAlert.length() > 0) {
                    currentAlert.append("\n");
                }
                currentAlert.append(line);
            }
        }

        if (currentAlert.length() > 0) {
            addAlertItem(currentAlert.toString());
            count++;
        }

        if (count > 0) {
            cardAlerts.setVisibility(View.VISIBLE);
        } else {
            cardAlerts.setVisibility(View.GONE);
        }
    }

    private void addAlertItem(String alertText) {
        TextView tvAlert = new TextView(this);
        tvAlert.setText(alertText);
        tvAlert.setTextSize(13);
        tvAlert.setTextColor(getResources().getColor(R.color.weather_dark_text));
        tvAlert.setPadding(0, 8, 0, 8);
        llAlerts.addView(tvAlert);
    }

    private void parseAndUpdateMinutely(String minutelyText) {
        if (minutelyText == null) return;

        String summary = "";
        String[] lines = minutelyText.split("\n");
        boolean inMinutelySection = false;
        int rainPeriods = 0;
        double maxPrecip = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("分钟级降水:")) {
                inMinutelySection = true;
                continue;
            }
            if (inMinutelySection && (line.startsWith("链接:") || line.startsWith("未来"))) {
                continue;
            }
            if (!inMinutelySection || line.isEmpty()) continue;

            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String precipPart = line.substring(colonIdx + 1).trim();
                try {
                    String numStr = precipPart.replaceAll("[^0-9.]", "").trim();
                    if (!numStr.isEmpty()) {
                        double precip = Double.parseDouble(numStr);
                        if (precip > 0) {
                            rainPeriods++;
                            if (precip > maxPrecip) maxPrecip = precip;
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (rainPeriods > 0) {
            summary = String.format("未来2小时有%d个时段降水，最大降水量%.1fmm", rainPeriods, maxPrecip);
        } else {
            summary = "未来2小时无降水";
        }

        final String finalSummary = summary;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (tvPrecip != null) {
                tvPrecip.setText(finalSummary);
            }
        });
    }

    private void openFxLink() {
        if (fxLinkCurrent != null && !fxLinkCurrent.isEmpty()) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(fxLinkCurrent));
                startActivity(intent);
            } catch (Exception e) {
                Log.w(TAG, "Cannot open fxLink: " + e.getMessage());
            }
        }
    }

    private void shareWeather() {
        if (tvCity == null || tvTemp == null || tvWeather == null) return;
        String shareText = tvCity.getText() + "  " + tvTemp.getText() + "  " + tvWeather.getText();
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(intent, "分享天气"));
    }

    private static class IndexItem {
        String name;
        String value;
        String iconCode;

        IndexItem(String name, String value, String iconCode) {
            this.name = name;
            this.value = value;
            this.iconCode = iconCode;
        }
    }

    private class IndicesAdapter extends RecyclerView.Adapter<IndicesAdapter.IndexViewHolder> {

        private List<IndexItem> items;

        IndicesAdapter(List<IndexItem> items) {
            this.items = items != null ? items : new ArrayList<>();
        }

        void updateData(List<IndexItem> newItems) {
            this.items = newItems != null ? newItems : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public IndexViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_weather_index, parent, false);
            return new IndexViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull IndexViewHolder holder, int position) {
            IndexItem item = items.get(position);
            holder.tvName.setText(item.name);
            holder.tvValue.setText(item.value);
            holder.tvIcon.setText(QWeatherIconFont.getIcon(item.iconCode));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class IndexViewHolder extends RecyclerView.ViewHolder {
            TextView tvIcon;
            TextView tvName;
            TextView tvValue;

            IndexViewHolder(View itemView) {
                super(itemView);
                tvIcon = itemView.findViewById(R.id.tv_index_icon);
                tvName = itemView.findViewById(R.id.tv_index_name);
                tvValue = itemView.findViewById(R.id.tv_index_value);
                if (tvIcon != null) {
                    tvIcon.setTypeface(QWeatherIconFont.getTypeface(itemView.getContext()));
                }
            }
        }
    }
}
