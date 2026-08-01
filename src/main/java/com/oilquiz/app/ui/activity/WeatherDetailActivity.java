package com.oilquiz.app.ui.activity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.TransitionManager;

import com.oilquiz.app.R;
import com.oilquiz.app.ui.widget.CircularGaugeView;
import com.oilquiz.app.ui.widget.TempRangeBarView;
import com.oilquiz.app.util.QWeatherIconFont;
import com.oilquiz.app.weather.CitySearchDialog;
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

    // 顶部
    private TextView tvCity;
    private TextView tvTemp;
    private TextView tvWeather;
    private TextView tvTempRange;
    private TextView tvUpdateTime;
    private TextView tvLocationInfo;
    private TextView tvLocationStatus;
    private TextView ivCurrentIcon;

    // 体感/云量/露点 chips
    private TextView chipFeelsLike;
    private TextView chipCloud;
    private TextView chipDew;

    // 摘要行
    private TextView tvAirSummary;
    private TextView tvWindSummary;

    // 预警醒目条
    private LinearLayout cardAlertsBanner;
    private LinearLayout alertBarClickable;
    private LinearLayout alertExpandedArea;
    private TextView alertBannerTitle;
    private TextView alertBannerLevel;
    private TextView alertBannerSummary;
    private TextView alertPublishTime;
    private TextView alertDescription;
    private TextView alertDefense;
    private ImageView alertExpandArrow;
    private boolean alertExpanded = false;

    // 逐小时（横向 RecyclerView）
    private RecyclerView rvHourly;
    private HourlyAdapter hourlyAdapter;

    // 7天
    private LinearLayout llDaily;

    // 降水大卡
    private TextView tvPrecipHours;
    private TextView tvPrecipTip;
    private FrameLayout framePrecipMap;
    private ImageView ivPrecipMap;

    // UV + 湿度大卡
    private CircularGaugeView gaugeUvArc;
    private CircularGaugeView gaugeHumidityArc;

    // 风向风速 + 气压
    private TextView tvWindDirection;
    private TextView tvWindLevel;
    private TextView tvWindSpeed;
    private TextView tvCompassDirection;
    private TextView tvPressureValue;

    // 空气质量
    private TextView tvAirAqi;
    private TextView tvAirCategory;
    private TextView tvAirPm25;
    private TextView tvAirPm10;
    private TextView tvAirNo2;
    private TextView tvAirSo2;
    private TextView tvAirCo;
    private TextView tvAirO3;

    // 日出日落
    private TextView tvSunrise;
    private TextView tvSunset;

    // 多预警列表
    private LinearLayout llAlerts;

    // 生活指数
    private RecyclerView rvIndices;

    // 按钮
    private ImageView btnBack;
    private ImageView btnShare;
    private ImageView btnGpsRefresh;
    private TextView tvView15d;

    // 图标 font
    private TextView tvAirIcon;
    private TextView tvSunIcon;
    private TextView tvSunriseIcon;
    private TextView tvSunsetIcon;
    private TextView tvIndicesIcon;

    private String fxLinkCurrent = "";
    private String fxLinkIndices = "";
    private String fxLinkMinutely = "";
    private String fxLinkDaily = "";

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
        setupHourlyRecyclerView();
        showMockData();

        if (hasLocation) {
            loadWeatherData();
        } else {
            // 没有从 Intent 获取到坐标，尝试 GPS 定位
            tryGetGpsLocation();
        }
    }

    /** 更新界面上的经纬度显示 */
    private void updateLocationInfo() {
        if (tvLocationInfo == null) return;
        if (hasLocation) {
            tvLocationInfo.setText(String.format(java.util.Locale.US, "%.4f°N, %.4f°E", lat, lon));
        } else {
            tvLocationInfo.setText("无GPS坐标，使用城市名查询");
        }
    }

    /** 尝试通过 GPS/网络定位获取坐标 */
    private void tryGetGpsLocation() {
        if (tvUpdateTime != null) tvUpdateTime.setText("正在定位...");
        updateLocationInfo();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted, falling back to city name");
            if (tvUpdateTime != null) tvUpdateTime.setText("无定位权限，使用城市查询");
            loadWeatherData();
            return;
        }

        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            Log.w(TAG, "LocationManager not available, falling back to city name");
            loadWeatherData();
            return;
        }

        // 1. 先尝试 getLastKnownLocation（快速）
        Location lastKnown = null;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (lastKnown == null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "SecurityException getting last known location", e);
        }

        if (lastKnown != null) {
            lat = lastKnown.getLatitude();
                lon = lastKnown.getLongitude();
                hasLocation = true;
                Log.d(TAG, "Got last known location: " + lat + ", " + lon);
                updateLocationInfo();
                if (tvUpdateTime != null) tvUpdateTime.setText("定位成功，加载中...");
                loadWeatherData();
            return;
        }

        // 2. getLastKnownLocation 失败，请求单次定位更新
        Log.d(TAG, "Last known location null, requesting location updates...");
        if (tvUpdateTime != null) tvUpdateTime.setText("正在获取GPS定位...");

        final LocationManager lm = locationManager;
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location == null) return;
                lm.removeUpdates(this);
                handler.removeCallbacksAndMessages(null);
                if (isFinishing() || isDestroyed()) return;

                lat = location.getLatitude();
                lon = location.getLongitude();
                hasLocation = true;
                Log.d(TAG, "Got GPS location: " + lat + ", " + lon);
                updateLocationInfo();
                if (tvUpdateTime != null) tvUpdateTime.setText("定位成功，加载中...");
                loadWeatherData();
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        // 超时回退：10 秒后如果还没获取到坐标，用城市名查询
        handler.postDelayed(() -> {
            lm.removeUpdates(listener);
            if (!hasLocation) {
                if (isFinishing() || isDestroyed()) return;
                Log.w(TAG, "GPS timeout, falling back to city name: " + city);
                if (tvUpdateTime != null) tvUpdateTime.setText("定位超时，使用城市查询");
                loadWeatherData();
            }
        }, 10000);

        // 请求定位更新
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, listener, android.os.Looper.getMainLooper());
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, listener, android.os.Looper.getMainLooper());
            } else {
                // 没有可用的定位提供者
                handler.removeCallbacksAndMessages(null);
                Log.w(TAG, "No location provider available, falling back to city name");
                if (tvUpdateTime != null) tvUpdateTime.setText("无法定位，使用城市查询");
                loadWeatherData();
            }
        } catch (SecurityException e) {
            handler.removeCallbacksAndMessages(null);
            Log.w(TAG, "SecurityException requesting location updates", e);
            loadWeatherData();
        }
    }

    private void initViews() {
        tvCity = findViewById(R.id.tv_city);
        tvTemp = findViewById(R.id.tv_temp);
        tvWeather = findViewById(R.id.tv_weather);
        tvTempRange = findViewById(R.id.tv_temp_range);
        tvUpdateTime = findViewById(R.id.tv_update_time);
        tvLocationInfo = findViewById(R.id.tv_location_info);
        tvLocationStatus = findViewById(R.id.tv_location_status);
        ivCurrentIcon = findViewById(R.id.iv_current_icon);

        chipFeelsLike = findViewById(R.id.chip_feels_like);
        chipCloud = findViewById(R.id.chip_cloud);
        chipDew = findViewById(R.id.chip_dew);

        tvAirSummary = findViewById(R.id.tv_air_summary);
        tvWindSummary = findViewById(R.id.tv_wind_summary);

        cardAlertsBanner = findViewById(R.id.card_alerts_banner);
        alertBarClickable = findViewById(R.id.alert_bar_clickable);
        alertExpandedArea = findViewById(R.id.alert_expanded_area);
        alertBannerTitle = findViewById(R.id.alert_banner_title);
        alertBannerLevel = findViewById(R.id.alert_banner_level);
        alertBannerSummary = findViewById(R.id.alert_banner_summary);
        alertPublishTime = findViewById(R.id.alert_publish_time);
        alertDescription = findViewById(R.id.alert_description);
        alertDefense = findViewById(R.id.alert_defense);
        alertExpandArrow = findViewById(R.id.alert_expand_arrow);
        llAlerts = findViewById(R.id.ll_alerts);

        if (alertBarClickable != null) {
            alertBarClickable.setOnClickListener(v -> toggleAlertExpand());
        }

        rvHourly = findViewById(R.id.rv_hourly);
        llDaily = findViewById(R.id.ll_daily);

        tvPrecipHours = findViewById(R.id.tv_precip_hours);
        tvPrecipTip = findViewById(R.id.tv_precip_tip);
        framePrecipMap = findViewById(R.id.frame_precip_map);
        ivPrecipMap = findViewById(R.id.iv_precip_map);

        if (framePrecipMap != null) {
            framePrecipMap.setOnClickListener(v -> openPrecipMapDetail());
        }

        gaugeUvArc = findViewById(R.id.gauge_uv_arc);
        gaugeHumidityArc = findViewById(R.id.gauge_humidity_arc);

        tvWindDirection = findViewById(R.id.tv_wind_direction);
        tvWindLevel = findViewById(R.id.tv_wind_level);
        tvWindSpeed = findViewById(R.id.tv_wind_speed);
        tvCompassDirection = findViewById(R.id.tv_compass_direction);
        tvPressureValue = findViewById(R.id.tv_pressure_value);

        tvAirIcon = findViewById(R.id.tv_air_icon);
        tvAirAqi = findViewById(R.id.tv_air_aqi);
        tvAirCategory = findViewById(R.id.tv_air_category);
        tvAirPm25 = findViewById(R.id.tv_air_pm25);
        tvAirPm10 = findViewById(R.id.tv_air_pm10);
        tvAirNo2 = findViewById(R.id.tv_air_no2);
        tvAirSo2 = findViewById(R.id.tv_air_so2);
        tvAirCo = findViewById(R.id.tv_air_co);
        tvAirO3 = findViewById(R.id.tv_air_o3);

        tvSunIcon = findViewById(R.id.tv_sun_icon);
        tvSunriseIcon = findViewById(R.id.tv_sunrise_icon);
        tvSunsetIcon = findViewById(R.id.tv_sunset_icon);
        tvSunrise = findViewById(R.id.tv_sunrise);
        tvSunset = findViewById(R.id.tv_sunset);

        tvIndicesIcon = findViewById(R.id.tv_indices_icon);
        rvIndices = findViewById(R.id.rv_indices);

        btnBack = findViewById(R.id.btn_back);
        btnShare = findViewById(R.id.btn_share);
        btnGpsRefresh = findViewById(R.id.btn_gps_refresh);
        tvView15d = findViewById(R.id.tv_view_15d);

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (btnShare != null) btnShare.setOnClickListener(v -> shareWeather());
        if (btnGpsRefresh != null) btnGpsRefresh.setOnClickListener(v -> {
            // 强制重新获取 GPS 定位并刷新天气
            hasLocation = false;
            showMockData();
            tryGetGpsLocation();
        });
        if (tvView15d != null) tvView15d.setOnClickListener(v -> openLink(fxLinkDaily));
        if (tvCity != null) tvCity.setOnClickListener(v -> openCitySearchDialog());
        if (tvIndicesIcon != null) tvIndicesIcon.setOnClickListener(v -> openLink(fxLinkIndices));
    }

    private void toggleAlertExpand() {
        if (alertExpandedArea == null || alertExpandArrow == null) return;
        alertExpanded = !alertExpanded;
        ViewGroup parent = (ViewGroup) alertExpandedArea.getParent();
        if (parent != null) {
            try { TransitionManager.beginDelayedTransition(parent); } catch (Exception ignored) {}
        }
        alertExpandedArea.setVisibility(alertExpanded ? View.VISIBLE : View.GONE);
        alertExpandArrow.animate().rotation(alertExpanded ? 180f : 0f).setDuration(220).start();
    }

    private void openPrecipMapDetail() {
        String radarUrl = fxLinkMinutely != null && !fxLinkMinutely.isEmpty() ? fxLinkMinutely : fxLinkCurrent;
        if (radarUrl == null || radarUrl.isEmpty()) {
            if (tvPrecipTip != null) tvPrecipTip.setText("暂无降水地图链接");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(radarUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Cannot open radar map: " + e.getMessage());
            if (tvPrecipTip != null) tvPrecipTip.setText("无法打开降水地图");
        }
    }

    private void openCitySearchDialog() {
        CitySearchDialog dialog = new CitySearchDialog(this, cityEntry -> {
            if (cityEntry != null && cityEntry.nameZh != null) {
                // 清除新位置的缓存，强制刷新
                weatherService.clearCacheForLocation(cityEntry.latitude, cityEntry.longitude);
                weatherService.clearCacheForCity(cityEntry.nameZh);

                city = cityEntry.nameZh;
                lat = cityEntry.latitude;
                lon = cityEntry.longitude;
                hasLocation = (lat != 0 && lon != 0);
                if (tvCity != null) tvCity.setText(city);

                // 重置 UI 为加载状态
                showMockData();

                loadWeatherData();
            }
        });
        dialog.show();
    }

    private void setupIconFonts() {
        Typeface iconTypeface = QWeatherIconFont.getTypeface(this);
        if (ivCurrentIcon != null) ivCurrentIcon.setTypeface(iconTypeface);
        if (tvAirIcon != null) {
            tvAirIcon.setTypeface(iconTypeface);
            tvAirIcon.setText(QWeatherIconFont.getIcon("104"));
        }
        if (tvSunIcon != null) {
            tvSunIcon.setTypeface(iconTypeface);
            tvSunIcon.setText(QWeatherIconFont.getIcon("100"));
        }
        if (tvSunriseIcon != null) {
            tvSunriseIcon.setTypeface(iconTypeface);
            tvSunriseIcon.setText(QWeatherIconFont.getIcon("100"));
        }
        if (tvSunsetIcon != null) {
            tvSunsetIcon.setTypeface(iconTypeface);
            tvSunsetIcon.setText(QWeatherIconFont.getIcon("150"));
        }
        if (tvIndicesIcon != null) {
            tvIndicesIcon.setTypeface(iconTypeface);
            tvIndicesIcon.setText(QWeatherIconFont.getIcon("1003"));
        }
    }

    private void setupIndicesRecyclerView() {
        if (rvIndices != null) {
            rvIndices.setLayoutManager(new GridLayoutManager(this, 3));
            rvIndices.setAdapter(new IndicesAdapter(new ArrayList<>()));
        }
    }

    private void setupHourlyRecyclerView() {
        if (rvHourly != null) {
            LinearLayoutManager lm = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
            rvHourly.setLayoutManager(lm);
            hourlyAdapter = new HourlyAdapter(new ArrayList<>());
            rvHourly.setAdapter(hourlyAdapter);
        }
    }

    private void showMockData() {
        if (tvCity != null) tvCity.setText(city);
        if (tvTemp != null) tvTemp.setText("--°");
        if (tvWeather != null) tvWeather.setText("加载中...");
        if (tvTempRange != null) tvTempRange.setText("");
        if (ivCurrentIcon != null) ivCurrentIcon.setText(QWeatherIconFont.getIcon("999"));

        if (chipFeelsLike != null) chipFeelsLike.setText("体感 --°");
        if (chipCloud != null) chipCloud.setText("云量 --%");
        if (chipDew != null) chipDew.setText("露点 --°");

        if (cardAlertsBanner != null) cardAlertsBanner.setVisibility(View.GONE);
        alertExpanded = false;
        if (alertExpandedArea != null) alertExpandedArea.setVisibility(View.GONE);
        if (alertExpandArrow != null) alertExpandArrow.setRotation(0f);

        if (tvAirSummary != null) tvAirSummary.setText("-- 空气质量");
        if (tvWindSummary != null) tvWindSummary.setText("--");

        if (hourlyAdapter != null) {
            List<HourlyItem> mockItems = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                mockItems.add(new HourlyItem("--:--", "999", "--级", "--°", i == 0));
            }
            hourlyAdapter.updateData(mockItems);
        }

        if (tvSunrise != null) tvSunrise.setText("--:--");
        if (tvSunset != null) tvSunset.setText("--:--");

        if (tvWindDirection != null) tvWindDirection.setText("-- 风");
        if (tvWindLevel != null) tvWindLevel.setText("--级");
        if (tvWindSpeed != null) tvWindSpeed.setText("-- km/h");
        if (tvCompassDirection != null) tvCompassDirection.setText("");
        if (tvPressureValue != null) tvPressureValue.setText("--");

        if (tvAirAqi != null) tvAirAqi.setText("--");
        if (tvAirCategory != null) tvAirCategory.setText("暂无数据");
        if (tvAirPm25 != null) tvAirPm25.setText("--");
        if (tvAirPm10 != null) tvAirPm10.setText("--");
        if (tvAirNo2 != null) tvAirNo2.setText("--");
        if (tvAirSo2 != null) tvAirSo2.setText("--");
        if (tvAirCo != null) tvAirCo.setText("--");
        if (tvAirO3 != null) tvAirO3.setText("--");

        if (llDaily != null) llDaily.removeAllViews();
        if (llAlerts != null) llAlerts.removeAllViews();

        if (gaugeUvArc != null) {
            gaugeUvArc.setProgressImmediate(0);
            gaugeUvArc.setText("0");
            gaugeUvArc.setLabel("弱");
            gaugeUvArc.setArcColor(getResources().getColor(R.color.uv_low));
        }
        if (gaugeHumidityArc != null) {
            gaugeHumidityArc.setProgressImmediate(0);
            gaugeHumidityArc.setText("--%");
            gaugeHumidityArc.setLabel("舒适");
            gaugeHumidityArc.setArcColor(getResources().getColor(R.color.humidity_comfort));
        }

        if (tvUpdateTime != null) tvUpdateTime.setText("更新于 --:--");
    }

    private void loadWeatherData() {
        if (weatherService == null) return;

        updateLocationInfo();

        java.util.concurrent.CompletableFuture<WeatherService.WeatherBatchResult> future;
        if (hasLocation) {
            future = weatherService.getAllWeatherByLocation(lat, lon);
        } else {
            future = weatherService.getAllWeather(city);
        }

        future.thenAccept(result -> {
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                if (result.currentWeather != null) parseAndUpdateCurrentWeather(result.currentWeather);
                if (result.hourlyForecast != null) parseAndUpdateHourlyWeather(result.hourlyForecast);
                if (result.dailyForecast != null) parseAndUpdateDailyWeather(result.dailyForecast);

                // 空气质量和天气预警需要经纬度坐标，无坐标时显示提示
                if (hasLocation) {
                    if (result.airQuality != null) parseAndUpdateAirQuality(result.airQuality);
                    if (result.alerts != null) parseAndUpdateAlerts(result.alerts);
                } else {
                    if (tvAirCategory != null) tvAirCategory.setText("需要定位才能查询");
                    if (tvAirSummary != null) tvAirSummary.setText("无GPS坐标");
                    if (tvAirAqi != null) tvAirAqi.setText("--");
                    if (cardAlertsBanner != null) cardAlertsBanner.setVisibility(View.GONE);
                }

                if (result.indices != null) parseAndUpdateIndices(result.indices);
                if (result.sunInfo != null) parseAndUpdateSunInfo(result.sunInfo);
                if (result.minutely != null) parseAndUpdateMinutely(result.minutely);

                if (result.fxLinkCurrent != null && !result.fxLinkCurrent.isEmpty()) {
                    fxLinkCurrent = result.fxLinkCurrent;
                }
                if (result.fxLinkDaily != null && !result.fxLinkDaily.isEmpty()) {
                    fxLinkDaily = result.fxLinkDaily;
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

    // =====================================================
    // 工具方法
    // =====================================================
    private static String uvLevelText(int uv) {
        if (uv <= 2) return "弱";
        if (uv <= 5) return "中";
        if (uv <= 7) return "强";
        if (uv <= 10) return "很强";
        return "极强";
    }

    private static int uvColor(int uv) {
        if (uv <= 2) return 0xFF10B981;
        if (uv <= 5) return 0xFFF59E0B;
        if (uv <= 7) return 0xFFEF4444;
        if (uv <= 10) return 0xFF7C3AED;
        return 0xFF991B1B;
    }

    private static String humidityComfort(int h) {
        if (h < 40) return "干燥";
        if (h <= 70) return "舒适";
        return "潮湿";
    }

    private static int humidityColor(int h) {
        if (h < 40) return 0xFFFB923C;
        if (h <= 70) return 0xFF22D3EE;
        return 0xFF8B5CF6;
    }

    private static int alertBgDrawable(String level) {
        if (level == null) return R.drawable.weather_card_warning_blue;
        if (level.contains("红")) return R.drawable.weather_card_warning_red;
        if (level.contains("橙")) return R.drawable.weather_card_warning_orange;
        if (level.contains("黄")) return R.drawable.weather_card_warning_yellow;
        return R.drawable.weather_card_warning_blue;
    }

    private String getIconFromText(String weatherText) {
        if (weatherText == null || weatherText.isEmpty()) return "999";
        if (weatherText.contains("晴")) return "100";
        if (weatherText.contains("云") || weatherText.contains("阴")) return "104";
        if (weatherText.contains("雷")) return "302";
        if (weatherText.contains("雨夹")) return "301";
        if (weatherText.contains("大暴雨")) return "319";
        if (weatherText.contains("特大暴雨")) return "320";
        if (weatherText.contains("暴雨")) return "318";
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

    private static String tempLevelText(int temp) {
        if (temp <= 0) return "严寒";
        if (temp <= 5) return "寒冷";
        if (temp <= 10) return "冷";
        if (temp <= 15) return "凉";
        if (temp <= 20) return "舒适";
        if (temp <= 25) return "宜人";
        if (temp <= 28) return "温暖";
        if (temp <= 32) return "热";
        return "酷热";
    }

    private static String cleanAirValue(String line) {
        int idx = line.indexOf(":");
        if (idx < 0) return "--";
        String v = line.substring(idx + 1).trim();
        v = v.replace(" μg/m³", "").replace("μg/m³", "")
             .replace(" mg/m³", "").replace("mg/m³", "")
             .replace(" ug/m3", "").replace("ug/m3", "")
             .trim();
        return v.isEmpty() ? "--" : v;
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

    // =====================================================
    // 解析方法
    // =====================================================

    private void parseAndUpdateCurrentWeather(String weatherText) {
        if (weatherText == null) return;

        String temp = "--";
        String feelsLike = "--";
        String weather = "--";
        String iconCode = "999";
        String humidity = "--";
        String windDir = "--";
        String windScale = "--";
        String windSpeed = "";
        String pressure = "--";
        String cloud = "--";
        String dew = "--";
        String highTemp = "";
        String lowTemp = "";
        String uv = "";
        String precip = "";

        String[] lines = weatherText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("天气信息:") || line.startsWith("链接:")) continue;

            if (line.startsWith("天气:")) {
                weather = line.substring(3).trim();
            } else if (line.startsWith("图标:")) {
                iconCode = line.substring(3).trim();
            } else if (line.startsWith("温度:")) {
                temp = line.substring(3).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("体感温度:")) {
                feelsLike = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("湿度:")) {
                humidity = line.substring(3).trim().replace("%", "");
            } else if (line.startsWith("风向:")) {
                windDir = line.substring(3).trim();
            } else if (line.startsWith("风力:")) {
                windScale = line.substring(3).trim().replace("级", "");
            } else if (line.startsWith("风速:")) {
                windSpeed = line.substring(3).trim().replace("km/h", "");
            } else if (line.startsWith("气压:")) {
                pressure = line.substring(3).trim().replace("hPa", "");
            } else if (line.startsWith("云量:")) {
                cloud = line.substring(3).trim().replace("%", "");
            } else if (line.startsWith("露点温度:")) {
                dew = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("最高温度:")) {
                highTemp = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("最低温度:")) {
                lowTemp = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("紫外线:")) {
                uv = line.substring(4).trim();
            } else if (line.startsWith("降水量:")) {
                precip = line.substring(4).trim().replace("mm", "");
            }
        }

        if (tvCity != null && city != null) tvCity.setText(city);
        if (tvTemp != null) tvTemp.setText(temp + "°");
        if (tvWeather != null) tvWeather.setText(weather);
        if (ivCurrentIcon != null) ivCurrentIcon.setText(QWeatherIconFont.getIcon(iconCode));

        if (highTemp.isEmpty() && lowTemp.isEmpty()) {
            if (temp.contains("/")) {
                String[] parts = temp.split("/");
                highTemp = parts.length > 0 ? parts[0].trim() : "";
                lowTemp = parts.length > 1 ? parts[1].trim() : "";
            }
        }
        if (tvTempRange != null) {
            if (!highTemp.isEmpty() && !lowTemp.isEmpty()) {
                tvTempRange.setText("最高" + highTemp + "° 最低" + lowTemp + "°");
            } else {
                tvTempRange.setText("");
            }
        }

        if (chipFeelsLike != null) chipFeelsLike.setText("体感 " + feelsLike + "°");
        if (chipCloud != null) chipCloud.setText("云量 " + cloud + "%");
        if (chipDew != null) chipDew.setText("露点 " + dew + "°");

        // 风向风速 - 两个等级（风向 + 风力等级 + 风速）
        if (tvWindDirection != null) {
            tvWindDirection.setText(windDir.endsWith("风") ? windDir : windDir + " 风");
        }
        if (tvWindLevel != null) {
            tvWindLevel.setText(windScale + "级");
        }
        if (tvWindSpeed != null) {
            if (!windSpeed.isEmpty()) {
                tvWindSpeed.setText(windSpeed + " km/h");
            } else {
                tvWindSpeed.setText("-- km/h");
            }
        }
        if (tvWindSummary != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(windDir);
            if (!windScale.isEmpty()) sb.append(windScale).append("级");
            if (!windSpeed.isEmpty()) sb.append(" ").append(windSpeed).append("km/h");
            tvWindSummary.setText(sb.length() > 0 ? sb.toString() : "--");
        }
        if (tvCompassDirection != null) {
            tvCompassDirection.setText(windDir);
        }

        // 气压
        if (tvPressureValue != null) {
            tvPressureValue.setText(pressure);
        }

        // 湿度仪表盘
        if (gaugeHumidityArc != null) {
            try {
                int h = Integer.parseInt(humidity);
                gaugeHumidityArc.setProgressImmediate(h);
                gaugeHumidityArc.setText(h + "%");
                gaugeHumidityArc.setLabel(humidityComfort(h));
                gaugeHumidityArc.setArcColor(humidityColor(h));
            } catch (NumberFormatException e) {
                gaugeHumidityArc.setText(humidity + "%");
            }
        }

        // 紫外线仪表盘
        if (gaugeUvArc != null && !uv.isEmpty()) {
            try {
                int uvVal = Integer.parseInt(uv);
                gaugeUvArc.setProgressImmediate(uvVal);
                gaugeUvArc.setText(String.valueOf(uvVal));
                gaugeUvArc.setLabel(uvLevelText(uvVal));
                gaugeUvArc.setArcColor(uvColor(uvVal));
            } catch (NumberFormatException e) {
                // ignore
            }
        }
    }

    private void parseAndUpdateHourlyWeather(String weatherText) {
        if (weatherText == null || hourlyAdapter == null) return;

        List<HourlyItem> items = new ArrayList<>();
        String[] lines = weatherText.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("24小时预报") || line.startsWith("链接:")) continue;

            String time = "--:--";
            String temp = "--°";
            String iconCode = "999";
            String weatherDesc = "";

            String[] parts = line.split("\\s+");
            for (String part : parts) {
                if (part.isEmpty()) continue;
                if (part.matches("\\d{2}:\\d{2}")) {
                    time = part;
                } else if (part.endsWith("°C") || part.endsWith("°")) {
                    temp = part.replace("°C", "").replace("°", "") + "°";
                } else if (part.startsWith("图标:")) {
                    iconCode = part.substring(3).trim();
                } else if (part.startsWith("降水")) {
                    // skip pop for now
                } else {
                    if (weatherDesc.isEmpty() && !part.contains(":") && part.length() > 1) {
                        weatherDesc = part;
                    }
                }
            }

            int tempVal = 20;
            try {
                tempVal = Integer.parseInt(temp.replace("°", "").trim());
            } catch (NumberFormatException e) {
                // keep default
            }

            items.add(new HourlyItem(time, iconCode, tempLevelText(tempVal), temp, items.isEmpty()));
            if (items.size() >= 24) break;
        }

        if (items.isEmpty()) {
            for (int i = 0; i < 8; i++) {
                items.add(new HourlyItem("--:--", "999", "--级", "--°", i == 0));
            }
        }

        hourlyAdapter.updateData(items);
    }

    private void parseAndUpdateDailyWeather(String weatherText) {
        if (weatherText == null || llDaily == null) return;

        llDaily.removeAllViews();

        boolean inDailySection = false;
        String[] lines = weatherText.split("\n");
        StringBuilder currentDayData = new StringBuilder();
        boolean collectingDay = false;
        int count = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("天气预报:") || line.startsWith("7天预报:") || line.startsWith("15天预报:")) {
                inDailySection = true;
                continue;
            }
            if (inDailySection && line.startsWith("链接:")) continue;
            if (!inDailySection) continue;

            if (line.isEmpty()) {
                if (collectingDay && currentDayData.length() > 0) {
                    addDailyItem(currentDayData.toString());
                    count++;
                    if (count >= 7) break;
                    collectingDay = false;
                    currentDayData = new StringBuilder();
                }
            } else if (line.startsWith("日期:") || line.startsWith("白天:") || line.startsWith("白天天气:") ||
                       line.startsWith("最高温度:") || line.startsWith("最低温度:") ||
                       line.startsWith("图标:") || line.startsWith("夜间:") || line.startsWith("夜间天气:")) {
                if (collectingDay) {
                    currentDayData.append("\n").append(line);
                }
            } else if (!collectingDay && line.length() >= 6) {
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
            tvEmpty.setTextColor(getResources().getColor(R.color.future_text_secondary));
            tvEmpty.setPadding(16, 24, 16, 24);
            llDaily.addView(tvEmpty);
        }
    }

    private void addDailyItem(String data) {
        String date = "--";
        String highTemp = "--";
        String lowTemp = "--";
        String weatherDay = "";
        String weatherNight = "";
        String iconCode = "";

        String[] lines = data.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("日期:")) {
                date = line.substring(3).trim();
            } else if (line.startsWith("白天:") || line.startsWith("白天天气:")) {
                String prefix = line.startsWith("白天天气:") ? "白天天气:" : "白天:";
                String rest = line.substring(prefix.length()).trim();
                String[] parts = rest.split("\\s+");
                for (String p : parts) {
                    if (p.endsWith("°C") || p.endsWith("°")) {
                        highTemp = p.replace("°C", "").replace("°", "");
                    } else if (weatherDay.isEmpty() && !p.isEmpty() && !p.contains("°")) {
                        weatherDay = p;
                    }
                }
                if (weatherDay.isEmpty() && rest.length() > 0) {
                    weatherDay = rest.split("\\s+")[0];
                }
            } else if (line.startsWith("夜间:") || line.startsWith("夜间天气:")) {
                String prefix = line.startsWith("夜间天气:") ? "夜间天气:" : "夜间:";
                String rest = line.substring(prefix.length()).trim();
                String[] parts = rest.split("\\s+");
                for (String p : parts) {
                    if (p.endsWith("°C") || p.endsWith("°")) {
                        lowTemp = p.replace("°C", "").replace("°", "");
                    } else if (weatherNight.isEmpty() && !p.isEmpty() && !p.contains("°")) {
                        weatherNight = p;
                    }
                }
                if (weatherNight.isEmpty() && rest.length() > 0) {
                    weatherNight = rest.split("\\s+")[0];
                }
            } else if (line.startsWith("最高温度:")) {
                highTemp = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("最低温度:")) {
                lowTemp = line.substring(5).trim().replace("°C", "").replace("°", "");
            } else if (line.startsWith("图标:")) {
                iconCode = line.substring(3).trim();
            } else if (date.equals("--") && line.length() >= 6) {
                date = line;
            }
        }

        String displayText = !weatherDay.isEmpty() ? weatherDay : weatherNight;
        if (!weatherDay.isEmpty() && !weatherNight.isEmpty() && !weatherDay.equals(weatherNight)) {
            displayText = weatherDay + "转" + weatherNight;
        }

        int high = 20, low = 10;
        try { high = Integer.parseInt(highTemp); } catch (NumberFormatException e) { /* ignore */ }
        try { low = Integer.parseInt(lowTemp); } catch (NumberFormatException e) { /* ignore */ }

        if (date.equals("--")) date = "—";
        String dayLabel = date;
        if (dayLabel.length() > 10) {
            dayLabel = dayLabel.substring(5);
        }

        View itemView = getLayoutInflater().inflate(R.layout.item_weather_daily, llDaily, false);
        TextView tvDay = itemView.findViewById(R.id.tv_daily_day);
        TextView tvIcon = itemView.findViewById(R.id.tv_daily_icon);
        TextView tvText = itemView.findViewById(R.id.tv_daily_text);
        TextView tvLow = itemView.findViewById(R.id.tv_daily_low);
        TextView tvHigh = itemView.findViewById(R.id.tv_daily_high);
        TempRangeBarView bar = itemView.findViewById(R.id.bar_daily_temp);

        tvDay.setText(dayLabel);
        String ic = !iconCode.isEmpty() ? iconCode : getIconFromText(weatherDay);
        tvIcon.setTypeface(QWeatherIconFont.getTypeface(this));
        tvIcon.setText(QWeatherIconFont.getIcon(ic));
        tvText.setText(displayText);
        tvLow.setText(lowTemp + "°");
        tvHigh.setText(highTemp + "°");
        bar.setTempRange(low, high, -10, 40);

        llDaily.addView(itemView);
    }

    private void parseAndUpdateAirQuality(String weatherText) {
        if (weatherText == null) return;

        String aqi = "--";
        String category = "暂无数据";
        String pm25 = "--";
        String pm10 = "--";
        String no2 = "--";
        String so2 = "--";
        String co = "--";
        String o3 = "--";

        // 检查错误响应
        if (weatherText.contains("查询失败") || weatherText.contains("暂无权限") || weatherText.contains("无权限")) {
            String errorMsg = weatherText.replace("空气质量:", "").replace("空气质量", "").trim();
            if (tvAirCategory != null) tvAirCategory.setText(errorMsg.isEmpty() ? "查询失败" : errorMsg);
            if (tvAirSummary != null) tvAirSummary.setText("空气质量查询失败");
            return;
        }

        boolean inAirSection = false;
        String[] lines = weatherText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("空气质量:")) {
                inAirSection = true;
                continue;
            }
            if (inAirSection && line.startsWith("链接:")) continue;
            if (!inAirSection) continue;
            if (line.startsWith("污染物浓度:")) continue;

            if (line.startsWith("AQI:")) {
                String rest = line.substring(4).trim();
                int parenIdx = rest.indexOf('(');
                if (parenIdx > 0) {
                    aqi = rest.substring(0, parenIdx).trim();
                    String inside = rest.substring(parenIdx);
                    int commaIdx = inside.indexOf(',');
                    if (commaIdx > 0) {
                        String catPart = inside.substring(commaIdx + 1).trim();
                        category = catPart.replace(")", "").trim();
                    } else {
                        category = inside.substring(1, inside.length() - 1).trim();
                    }
                } else {
                    aqi = rest;
                }
            } else if (line.startsWith("PM2.5:") || line.startsWith("PM2p5:")) {
                pm25 = cleanAirValue(line);
            } else if (line.startsWith("PM10:")) {
                pm10 = cleanAirValue(line);
            } else if (line.startsWith("NO2:")) {
                no2 = cleanAirValue(line);
            } else if (line.startsWith("SO2:")) {
                so2 = cleanAirValue(line);
            } else if (line.startsWith("CO:")) {
                co = cleanAirValue(line);
            } else if (line.startsWith("O3:")) {
                o3 = cleanAirValue(line);
            } else if (line.startsWith("等级:")) {
                if (category.equals("暂无数据")) category = line.substring(3).trim();
            } else if (line.startsWith("首要污染物:")) {
                // 不覆盖 category
            }
        }

        if (tvAirAqi != null) tvAirAqi.setText(aqi);
        if (tvAirCategory != null) tvAirCategory.setText(category);
        if (tvAirPm25 != null) tvAirPm25.setText(pm25);
        if (tvAirPm10 != null) tvAirPm10.setText(pm10);
        if (tvAirNo2 != null) tvAirNo2.setText(no2);
        if (tvAirSo2 != null) tvAirSo2.setText(so2);
        if (tvAirCo != null) tvAirCo.setText(co);
        if (tvAirO3 != null) tvAirO3.setText(o3);

        if (tvAirSummary != null && !aqi.equals("--")) {
            tvAirSummary.setText(aqi + " " + category);
        }
    }

    private void parseAndUpdateIndices(String weatherText) {
        if (weatherText == null || rvIndices == null) return;

        List<IndexItem> indexList = new ArrayList<>();
        boolean inIndicesSection = false;
        int uvFromIndices = -1;

        String[] lines = weatherText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("生活指数:") || line.startsWith("天气指数:")) {
                inIndicesSection = true;
                continue;
            }
            if (inIndicesSection && line.startsWith("链接:")) {
                fxLinkIndices = line.substring(3).trim();
                continue;
            }
            if (inIndicesSection && line.startsWith("---")) break;
            if (inIndicesSection && !line.isEmpty() && line.contains(":")) {
                int colonIdx = line.indexOf(":");
                if (colonIdx > 0) {
                    String name = line.substring(0, colonIdx).trim();
                    String value = line.substring(colonIdx + 1).trim();
                    indexList.add(new IndexItem(name, value, getIndexIconCode(name)));

                    // 从紫外线指数提取 UV 值，格式: "中等 (3级)"
                    if (name.contains("紫外线") && uvFromIndices < 0) {
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d+)级\\)").matcher(value);
                        if (m.find()) {
                            try { uvFromIndices = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        }

        if (rvIndices.getAdapter() instanceof IndicesAdapter) {
            ((IndicesAdapter) rvIndices.getAdapter()).updateData(indexList);
        }

        // 用生活指数中的 UV 值更新紫外线仪表盘
        if (uvFromIndices >= 0 && gaugeUvArc != null) {
            gaugeUvArc.setProgressImmediate(uvFromIndices);
            gaugeUvArc.setText(String.valueOf(uvFromIndices));
            gaugeUvArc.setLabel(uvLevelText(uvFromIndices));
            gaugeUvArc.setArcColor(uvColor(uvFromIndices));
        }
    }

    private void parseAndUpdateAlerts(String weatherText) {
        if (weatherText == null) return;

        llAlerts.removeAllViews();

        boolean inAlertSection = false;
        int count = 0;
        StringBuilder currentAlert = new StringBuilder();
        String firstAlertTitle = "";
        String firstAlertLevel = "";
        String firstAlertSummary = "";
        String firstAlertDesc = "";
        String firstAlertDefense = "";
        String firstAlertTime = "";

        String[] lines = weatherText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("天气预警:") || line.startsWith("预警:")) {
                inAlertSection = true;
                continue;
            }
            if (inAlertSection && line.startsWith("链接:")) continue;
            if (!inAlertSection) continue;

            if (line.isEmpty()) {
                if (currentAlert.length() > 0) {
                    String alertText = currentAlert.toString();
                    if (count == 0) {
                        firstAlertTitle = extractAlertTitle(alertText);
                        firstAlertLevel = extractAlertLevel(alertText);
                        firstAlertSummary = extractAlertSummary(alertText);
                        firstAlertDesc = extractAlertDescription(alertText);
                        firstAlertDefense = extractAlertDefense(alertText);
                        firstAlertTime = extractAlertTime(alertText);
                    }
                    addAlertItem(alertText);
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
            String alertText = currentAlert.toString();
            if (count == 0) {
                firstAlertTitle = extractAlertTitle(alertText);
                firstAlertLevel = extractAlertLevel(alertText);
                firstAlertSummary = extractAlertSummary(alertText);
                firstAlertDesc = extractAlertDescription(alertText);
                firstAlertDefense = extractAlertDefense(alertText);
                firstAlertTime = extractAlertTime(alertText);
            }
            addAlertItem(alertText);
            count++;
        }

        if (count > 0 && cardAlertsBanner != null) {
            cardAlertsBanner.setVisibility(View.VISIBLE);
            if (alertBannerTitle != null) alertBannerTitle.setText(firstAlertTitle);
            if (alertBannerLevel != null) alertBannerLevel.setText(firstAlertLevel);
            if (alertBannerSummary != null) alertBannerSummary.setText(firstAlertSummary);
            if (alertPublishTime != null) alertPublishTime.setText("发布时间：" + firstAlertTime);
            if (alertDescription != null) alertDescription.setText(firstAlertDesc);
            if (alertDefense != null) alertDefense.setText("防御指南：" + firstAlertDefense);

            int bgRes = alertBgDrawable(firstAlertLevel);
            if (alertBarClickable != null) {
                alertBarClickable.setBackgroundResource(bgRes);
            }
        } else {
            if (cardAlertsBanner != null) cardAlertsBanner.setVisibility(View.GONE);
        }
    }

    private String extractAlertTitle(String text) {
        String[] parts = text.split("\n");
        for (String p : parts) {
            p = p.trim();
            if (!p.isEmpty() && p.length() < 30) return p;
        }
        return "天气预警";
    }

    private String extractAlertLevel(String text) {
        if (text.contains("红")) return "红色";
        if (text.contains("橙")) return "橙色";
        if (text.contains("黄")) return "黄色";
        if (text.contains("蓝")) return "蓝色";
        return "预警";
    }

    private String extractAlertSummary(String text) {
        String[] parts = text.split("\n");
        for (String p : parts) {
            p = p.trim();
            if (p.length() > 10 && p.length() < 60) return p;
        }
        return "";
    }

    private String extractAlertDescription(String text) {
        StringBuilder sb = new StringBuilder();
        String[] parts = text.split("\n");
        for (String p : parts) {
            p = p.trim();
            if (p.length() > 20) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(p);
            }
        }
        return sb.length() > 0 ? sb.toString() : text;
    }

    private String extractAlertDefense(String text) {
        int idx = text.indexOf("防御");
        if (idx >= 0) return text.substring(idx).trim();
        idx = text.indexOf("指南");
        if (idx >= 0) return text.substring(idx).trim();
        return "暂无防御指南";
    }

    private String extractAlertTime(String text) {
        for (String line : text.split("\n")) {
            if (line.contains("发布") || line.contains("时间") || line.matches(".*\\d{4}.*")) {
                return line.trim();
            }
        }
        return "--";
    }

    private void addAlertItem(String alertText) {
        TextView tvAlert = new TextView(this);
        tvAlert.setText(alertText);
        tvAlert.setTextSize(13);
        tvAlert.setTextColor(getResources().getColor(R.color.future_text_primary));
        tvAlert.setPadding(0, 8, 0, 8);
        llAlerts.addView(tvAlert);
    }

    private void parseAndUpdateSunInfo(String weatherText) {
        if (weatherText == null) return;

        String sunRise = "--:--";
        String sunSet = "--:--";

        String[] lines = weatherText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("日出:")) {
                sunRise = formatTimeString(line.substring(3).trim());
            } else if (line.startsWith("日落:")) {
                sunSet = formatTimeString(line.substring(3).trim());
            }
        }

        if (tvSunrise != null) tvSunrise.setText(sunRise);
        if (tvSunset != null) tvSunset.setText(sunSet);
    }

    /** 从各种时间格式中提取 HH:mm，如 "06:30", "2024-01-01T06:30+08:00" */
    private static String formatTimeString(String raw) {
        if (raw == null || raw.isEmpty()) return "--:--";
        // 已经是 HH:mm 格式
        if (raw.matches("^\\d{2}:\\d{2}$")) return raw;
        // ISO 格式：提取 T 后面的 HH:mm
        int tIdx = raw.indexOf('T');
        if (tIdx >= 0 && raw.length() >= tIdx + 6) {
            String timePart = raw.substring(tIdx + 1);
            if (timePart.length() >= 5) return timePart.substring(0, 5);
        }
        // 尝试其他格式：提取所有 HH:mm 模式
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2}:\\d{2})").matcher(raw);
        if (m.find()) return m.group(1);
        return raw;
    }

    private void parseAndUpdateMinutely(String minutelyText) {
        if (minutelyText == null) return;

        String summary = "";
        String[] lines = minutelyText.split("\n");
        boolean inMinutelySection = false;
        int rainPeriods = 0;
        double maxPrecip = 0;
        int totalPrecipMinutes = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("分钟级降水:")) {
                inMinutelySection = true;
                continue;
            }
            if (inMinutelySection && line.startsWith("链接:")) {
                fxLinkMinutely = line.substring(3).trim();
                continue;
            }
            if (inMinutelySection && line.startsWith("未来")) continue;
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
                            totalPrecipMinutes += 5;
                            if (precip > maxPrecip) maxPrecip = precip;
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (rainPeriods > 0) {
            summary = String.format("%d分钟内有降水，最大%.1fmm", totalPrecipMinutes, maxPrecip);
        } else {
            summary = "2小时内无降水";
        }

        if (tvPrecipHours != null) {
            tvPrecipHours.setText(summary);
        }
        if (tvPrecipTip != null) {
            if (rainPeriods == 0) {
                tvPrecipTip.setText("放心出行吧");
            } else if (maxPrecip < 2) {
                tvPrecipTip.setText("降水较弱，携带雨具");
            } else if (maxPrecip < 5) {
                tvPrecipTip.setText("降水中等，注意防范");
            } else {
                tvPrecipTip.setText("降水较强，减少外出");
            }
        }
    }

    private void openFxLink() {
        openLink(fxLinkCurrent);
    }

    private void openLink(String url) {
        if (url == null || url.isEmpty()) {
            Log.w(TAG, "Link is empty");
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Cannot open link: " + e.getMessage());
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

    // =====================================================
    // Adapter 内部类
    // =====================================================

    private static class HourlyItem {
        String time;
        String iconCode;
        String tempLevel;
        String temp;
        boolean isNow;

        HourlyItem(String t, String ic, String tl, String tmp, boolean now) {
            time = t; iconCode = ic; tempLevel = tl; temp = tmp; isNow = now;
        }
    }

    private class HourlyAdapter extends RecyclerView.Adapter<HourlyAdapter.HourlyViewHolder> {
        private List<HourlyItem> items;

        HourlyAdapter(List<HourlyItem> items) {
            this.items = items != null ? items : new ArrayList<>();
        }

        void updateData(List<HourlyItem> newItems) {
            this.items = newItems != null ? newItems : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public HourlyViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_weather_hourly, parent, false);
            return new HourlyViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull HourlyViewHolder holder, int position) {
            HourlyItem item = items.get(position);
            holder.tvTime.setText(item.time);
            holder.tvIcon.setText(QWeatherIconFont.getIcon(item.iconCode));
            holder.tvTempLevel.setText(item.tempLevel);
            holder.tvTemp.setText(item.temp);
            holder.itemView.setSelected(item.isNow);

            if (item.isNow) {
                holder.tvTemp.setTextColor(getResources().getColor(R.color.weather_primary));
            } else {
                holder.tvTemp.setTextColor(getResources().getColor(R.color.future_text_primary));
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class HourlyViewHolder extends RecyclerView.ViewHolder {
            TextView tvTime;
            TextView tvIcon;
            TextView tvTempLevel;
            TextView tvTemp;

            HourlyViewHolder(View itemView) {
                super(itemView);
                tvTime = itemView.findViewById(R.id.tv_hourly_time);
                tvIcon = itemView.findViewById(R.id.tv_hourly_icon);
                tvTempLevel = itemView.findViewById(R.id.tv_hourly_temp_level);
                tvTemp = itemView.findViewById(R.id.tv_hourly_temp);
                if (tvIcon != null) {
                    tvIcon.setTypeface(QWeatherIconFont.getTypeface(itemView.getContext()));
                }
            }
        }
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
