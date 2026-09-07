package com.oilquiz.app.ui.activity;

import com.oilquiz.app.SmartQuizApplication;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
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

import com.oilquiz.app.theme.ThemeColors;
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
    private TextView tvWeatherSummary;
    private TextView tvUpdateTime;
    private TextView tvLocationInfo;
    private TextView tvLocationStatus;
    private TextView ivCurrentIcon;

    // 体感/云量/露点 chips
    private TextView chipFeelsLike;
    private TextView chipCloud;
    private TextView chipDew;
    private TextView chipVisibility;

    // 摘要行
    private TextView tvAirSummary;
    private TextView tvWindSummary;

    // 动态信息横幅
    private LinearLayout cardAlertsBanner;
    private LinearLayout alertBarClickable;
    private LinearLayout alertExpandedArea;
    private TextView bannerIcon;
    private TextView bannerTitle;
    private TextView bannerTag;
    private TextView bannerSummary;
    private LinearLayout bannerDots;
    private ImageView alertExpandArrow;
    private boolean alertExpanded = false;

    // 横幅轮播
    private static class BannerItem {
        final String icon;
        final String title;
        final String tag;
        final String summary;
        final int bgResId;
        final int tagBgResId;
        final boolean isAlert;

        BannerItem(String icon, String title, String tag, String summary, int bgResId, int tagBgResId, boolean isAlert) {
            this.icon = icon;
            this.title = title;
            this.tag = tag;
            this.summary = summary;
            this.bgResId = bgResId;
            this.tagBgResId = tagBgResId;
            this.isAlert = isAlert;
        }
    }
    private final List<BannerItem> bannerItems = new ArrayList<>();
    private int bannerIndex = 0;
    private final Handler bannerHandler = new Handler(Looper.getMainLooper());
    private static final int BANNER_INTERVAL = 5000;

    // 空气质量预报
    private LinearLayout cardAirForecast;
    private LinearLayout llAirForecast;

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
    private TextView tvAirPrimaryPollutant;
    private TextView tvAirHealth;
    private TextView tvAirPm25Label;
    private TextView tvAirPm25;
    private TextView tvAirPm10Label;
    private TextView tvAirPm10;
    private TextView tvAirNo2Label;
    private TextView tvAirNo2;
    private TextView tvAirSo2Label;
    private TextView tvAirSo2;
    private TextView tvAirCoLabel;
    private TextView tvAirCo;
    private TextView tvAirO3Label;
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

    // 今日天气预报数据（用于生成人性化说明）
    private String todayDayWeather = "";
    private String todayNightWeather = "";
    private String todayHighTemp = "";
    private String todayLowTemp = "";
    private String currentWeatherText = "";
    private String currentTempVal = "";
    private String currentFeelsLike = "";
    private String currentWindScale = "";
    private String minutelySummary = "";
    private String alertSummary = "";
    private String currentHumidity = "";
    private String currentUv = "";
    private String currentDew = "";
    private String currentPressure = "";
    private String currentVisibility = "";
    private String currentWindDir = "";
    private String currentAirAqi = "";
    private String currentAirCategory = "";
    private String currentAirPrimary = "";
    private List<AlertInfo> currentAlertInfos = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_weather_detail);

        city = getIntent().getStringExtra("city");
        lat = getIntent().getDoubleExtra("lat", 0);
        lon = getIntent().getDoubleExtra("lon", 0);
        hasLocation = (lat != 0 && lon != 0);

        if (city == null || city.isEmpty()) {
            city = "";
        }

        weatherService = WeatherService.getInstance(this);

        initViews();
        setupIconFonts();
        setupIndicesRecyclerView();
        setupHourlyRecyclerView();
        showMockData();

        /* ================================================================
         * 城市名显示的核心原则：
         *  1. 用户明确选择的城市名（Intent带city / 搜索选择）→ 直接显示API原始名，绝不拼接，绝不同步横幅
         *  2. 只有坐标没有明确城市名（GPS定位成功）→ Geocoder反解析具体地址，并同步横幅
         *  3. 两个场景完全独立！不要混为一谈！
         * ================================================================ */
        final boolean userPickedExplicitCity = (city != null && !city.isEmpty());
        if (userPickedExplicitCity) {
            // ---------- 场景1：Intent 里已经带了明确的城市名（用户选择过的）----------
            // 直接用 API 返回的原名，去掉省/市前缀即可（truncateCityName 只去前缀不切中间）
            if (tvCity != null) tvCity.setText(truncateCityName(city));
            // 顶部城市名保留用户选择，副行（tvLocationInfo）异步反解析具体地址（区+路），不覆盖顶部
            // 有坐标时反解析具体地址，无坐标时只显示经纬度
            if (hasLocation && lat != 0 && lon != 0) {
                asyncResolveFullAddress(lat, lon, (cityName, detail) -> {
                    // 只更新副行地址，不覆盖顶部已显示的城市名
                    if (detail != null && !detail.isEmpty() && tvLocationInfo != null) {
                        tvLocationInfo.setText(detail);
                    }
                });
            } else {
                updateLocationInfo();
            }
            loadWeatherData();
        } else if (hasLocation) {
            // ---------- 场景2A：有坐标没城市名 → GPS/系统缓存定位成功 ----------
            // 占位先，后台异步反解析：城市名（顶部）+ 具体地址（副行），并同步给横幅
            if (tvCity != null) tvCity.setText(getString(R.string.h_5eac11b4));
            asyncResolveFullAddress(lat, lon, (cityName, detail) -> {
                city = cityName;
                if (tvCity != null) tvCity.setText(truncateCityName(cityName));
                // 副行：有具体地址显示地址（区+路），否则维持坐标显示
                if (detail != null && !detail.isEmpty() && tvLocationInfo != null) {
                    tvLocationInfo.setText(detail);
                }
            });
            updateLocationInfo();
            loadWeatherData();
        } else {
            // ---------- 场景2B：连坐标都没有 → 请求GPS ----------
            tryGetGpsLocation();
        }
    }

    /** 更新界面上的经纬度显示 */
    private void updateLocationInfo() {
        if (tvLocationInfo == null) return;
        if (hasLocation) {
            tvLocationInfo.setText(String.format(java.util.Locale.US, "%.4f°N, %.4f°E", lat, lon));
        } else {
            tvLocationInfo.setText(getString(R.string.h_c63b316f));
        }
    }

    /**
     * 最简城市名处理：只做"去掉省/市级前缀"，其余原样返回，绝不截断中间内容
     *   - 如果以"XX省/自治区/特别行政区/XX市"开头，去掉这部分前缀
     *   - 其余情况（包括短名、区+路、路+小区等）全部原样返回
     *
     * 设计原则：宁显示长一点，也不能把"金凤"这种合法的字切掉。
     */
    private String truncateCityName(String name) {
        if (name == null || name.isEmpty()) return name;

        String s = name;

        // 1. 去掉开头的省级前缀（省 / 自治区 / 特别行政区）
        int provEnd = -1;
        int sheng = s.indexOf('省');
        int zhiQu = s.indexOf("自治区");
        int teBie = s.indexOf("特别行政区");
        if (sheng >= 0 && sheng < 8) provEnd = Math.max(provEnd, sheng + 1);
        if (zhiQu >= 0 && zhiQu < 12) provEnd = Math.max(provEnd, zhiQu + 3);
        if (teBie >= 0 && teBie < 15) provEnd = Math.max(provEnd, teBie + 5);
        if (provEnd > 0 && provEnd < s.length()) {
            s = s.substring(provEnd);
        }

        // 2. 去掉开头的市级前缀（XX市），只有当"市"在前6个字以内，且后面跟着区/路/街等才去，避免误删"市辖区XX路"
        int shiIdx = s.indexOf('市');
        if (shiIdx > 0 && shiIdx <= 5 && shiIdx + 1 < s.length()) {
            char after = s.charAt(shiIdx + 1);
            // 后面是区/县/路/街/巷/大道/小区/大厦 等具体名 → 去市级前缀
            if (after == '区' || after == '县' || after == '路' || after == '街'
                || after == '巷' || after == '大' || after == '小' || after == '花'
                || after == '园' || after == '大') {
                s = s.substring(shiIdx + 1);
            }
        }

        // 兜底：如果省/市前缀去完后变成空了，返回原名
        if (s.isEmpty()) return name;

        return s;
    }

    /** 尝试通过 GPS/网络定位获取坐标 */
    private void tryGetGpsLocation() {
        if (tvUpdateTime != null) tvUpdateTime.setText(getString(R.string.h_6db9502a));
        updateLocationInfo();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted, falling back to city name");
            if (tvUpdateTime != null) tvUpdateTime.setText(getString(R.string.h_72c5e9fe));
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
            // 先刷新UI占位，后台异步反解析：城市名（顶部）+ 具体地址（副行），并同步到横幅
            if (city == null || city.isEmpty()) city = "当前位置";
            if (tvCity != null) tvCity.setText(truncateCityName(city));
            asyncResolveFullAddress(lat, lon, (cityName, detail) -> {
                city = cityName;
                if (tvCity != null) tvCity.setText(truncateCityName(cityName));
                if (detail != null && !detail.isEmpty() && tvLocationInfo != null) {
                    tvLocationInfo.setText(detail);
                }
            });
            updateLocationInfo();
            if (tvUpdateTime != null) tvUpdateTime.setText(getString(R.string.h_e41ae320));
            loadWeatherData();
            return;
        }

        // 2. getLastKnownLocation 失败，请求单次定位更新
        Log.d(TAG, "Last known location null, requesting location updates...");
        if (tvUpdateTime != null) tvUpdateTime.setText(getString(R.string.h_b16a4dd3));

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
                // 先显示占位，后台异步反解析：城市名 + 具体地址，并同步到横幅（自动刷新）
                if (city == null || city.isEmpty()) city = "当前位置";
                if (tvCity != null) tvCity.setText(truncateCityName(city));
                asyncResolveFullAddress(lat, lon, (cityName, detail) -> {
                    city = cityName;
                    if (tvCity != null) tvCity.setText(truncateCityName(cityName));
                    if (detail != null && !detail.isEmpty() && tvLocationInfo != null) {
                        tvLocationInfo.setText(detail);
                    }
                });
                updateLocationInfo();
                if (tvUpdateTime != null) tvUpdateTime.setText(getString(R.string.h_e41ae320));
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
                if (city != null && !city.isEmpty()) {
                    Log.w(TAG, "GPS timeout, falling back to city name: " + city);
                    if (tvUpdateTime != null) tvUpdateTime.setText(getString(R.string.h_5f5b152c));
                    loadWeatherData();
                } else {
                    Log.w(TAG, "GPS timeout and no city name available");
                    if (tvUpdateTime != null) tvUpdateTime.setText(getString(R.string.h_3798353c));
                    if (tvCity != null) tvCity.setText(getString(R.string.h_0fcd7253));
                    if (tvWeather != null) tvWeather.setText(getString(R.string.h_9831baf6));
                }
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
                if (city != null && !city.isEmpty()) {
                    Log.w(TAG, "No location provider available, falling back to city name");
                    if (tvUpdateTime != null) tvUpdateTime.setText(getString(R.string.h_8872b579));
                    loadWeatherData();
                } else {
                    Log.w(TAG, "No location provider and no city available");
                    if (tvUpdateTime != null) tvUpdateTime.setText(getString(R.string.h_9160ec6c));
                    if (tvCity != null) tvCity.setText(getString(R.string.h_0fcd7253));
                    if (tvWeather != null) tvWeather.setText(getString(R.string.h_eeb9d7a3));
                }
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
        tvWeatherSummary = findViewById(R.id.tv_weather_summary);
        tvUpdateTime = findViewById(R.id.tv_update_time);
        tvLocationInfo = findViewById(R.id.tv_location_info);
        tvLocationStatus = findViewById(R.id.tv_location_status);
        ivCurrentIcon = findViewById(R.id.iv_current_icon);

        chipFeelsLike = findViewById(R.id.chip_feels_like);
        chipCloud = findViewById(R.id.chip_cloud);
        chipDew = findViewById(R.id.chip_dew);
        chipVisibility = findViewById(R.id.chip_visibility);

        tvAirSummary = findViewById(R.id.tv_air_summary);
        tvWindSummary = findViewById(R.id.tv_wind_summary);

        cardAlertsBanner = findViewById(R.id.card_alerts_banner);
        alertBarClickable = findViewById(R.id.alert_bar_clickable);
        alertExpandedArea = findViewById(R.id.alert_expanded_area);
        bannerIcon = findViewById(R.id.banner_icon);
        bannerTitle = findViewById(R.id.banner_title);
        bannerTag = findViewById(R.id.banner_tag);
        bannerSummary = findViewById(R.id.banner_summary);
        bannerDots = findViewById(R.id.banner_dots);
        alertExpandArrow = findViewById(R.id.alert_expand_arrow);
        llAlerts = findViewById(R.id.ll_alerts);

        cardAirForecast = findViewById(R.id.card_air_forecast);
        llAirForecast = findViewById(R.id.ll_air_forecast);

        if (alertBarClickable != null) {
            alertBarClickable.setOnClickListener(v -> {
                // 预警常驻模式（有预警，bannerItems 为空）：多条预警点击切换下一条，单条展开/折叠
                if (bannerItems.isEmpty() && currentAlertInfos != null && !currentAlertInfos.isEmpty()) {
                    if (currentAlertInfos.size() > 1) {
                        showAlertBanner(bannerIndex + 1);
                    } else {
                        toggleAlertExpand();
                    }
                    return;
                }
                // 如果当前是预警条目，展开/收起预警详情
                if (!bannerItems.isEmpty() && bannerItems.get(bannerIndex).isAlert) {
                    toggleAlertExpand();
                } else {
                    // 非预警条目：点击切换下一条
                    advanceBanner();
                }
            });
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
        tvAirPrimaryPollutant = findViewById(R.id.tv_air_primary_pollutant);
        tvAirHealth = findViewById(R.id.tv_air_health);
        tvAirPm25Label = findViewById(R.id.tv_air_pm25_label);
        tvAirPm25 = findViewById(R.id.tv_air_pm25);
        tvAirPm10Label = findViewById(R.id.tv_air_pm10_label);
        tvAirPm10 = findViewById(R.id.tv_air_pm10);
        tvAirNo2Label = findViewById(R.id.tv_air_no2_label);
        tvAirNo2 = findViewById(R.id.tv_air_no2);
        tvAirSo2Label = findViewById(R.id.tv_air_so2_label);
        tvAirSo2 = findViewById(R.id.tv_air_so2);
        tvAirCoLabel = findViewById(R.id.tv_air_co_label);
        tvAirCo = findViewById(R.id.tv_air_co);
        tvAirO3Label = findViewById(R.id.tv_air_o3_label);
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
            // 清空所有缓存，强制重新获取数据
            weatherService.clearCache();
            hasLocation = false;
            showMockData();
            tryGetGpsLocation();
        });
        if (tvView15d != null) tvView15d.setOnClickListener(v -> openLink(fxLinkDaily));
        if (tvCity != null) tvCity.setOnClickListener(v -> openCitySearchDialog());
        if (tvIndicesIcon != null) tvIndicesIcon.setOnClickListener(v -> openLink(fxLinkIndices));
    }

    // =====================================================
    // 横幅轮播逻辑
    // =====================================================

    /** 重建横幅条目并启动轮播 */
    private void rebuildBannerItems() {
        bannerItems.clear();
        bannerIndex = 0;

        // 有预警时：预警常驻显示（不参与轮播），后续普通条目暂不显示
        if (!alertSummary.isEmpty() && currentAlertInfos != null && !currentAlertInfos.isEmpty()) {
            showAlertBanner(0);
            return;
        }

        // 无预警：普通条目轮播
        // 1. 当前天气
        if (!currentWeatherText.isEmpty()) {
            String summary = currentTempVal + "° " + currentWeatherText;
            if (!currentFeelsLike.isEmpty()) summary += "，体感" + currentFeelsLike + "°";
            bannerItems.add(new BannerItem("🌤", "当前天气", "", summary, R.drawable.weather_card_glass, 0, false));
        }

        // 3. 空气质量
        if (currentAirAqi != null && !currentAirAqi.isEmpty() && !currentAirAqi.equals("--")) {
            String summary = "AQI " + currentAirAqi;
            if (currentAirCategory != null && !currentAirCategory.isEmpty()) summary += " " + currentAirCategory;
            if (!currentAirPrimary.isEmpty()) summary += "，首要污染物 " + currentAirPrimary;
            bannerItems.add(new BannerItem("🌬", "空气质量", "", summary, R.drawable.weather_card_glass, 0, false));
        }

        // 4. 降水预报
        if (!minutelySummary.isEmpty()) {
            bannerItems.add(new BannerItem("🌧", "降水预报", "", minutelySummary, R.drawable.weather_card_glass, 0, false));
        }

        // 5. 紫外线
        if (!currentUv.isEmpty()) {
            int uv = 0;
            try { uv = Integer.parseInt(currentUv); } catch (Exception ignored) {}
            String level = uvLevelText(uv);
            String summary = "指数 " + currentUv + "（" + level + "）";
            if (uv >= 8) summary += "，务必防晒";
            else if (uv >= 5) summary += "，建议防晒";
            bannerItems.add(new BannerItem("☀️", "紫外线", level, summary, R.drawable.weather_card_glass, 0, false));
        }

        // 6. 风力风向
        if (!currentWindScale.isEmpty()) {
            String summary = currentWindScale + "级";
            if (!currentWindDir.isEmpty()) summary += " " + currentWindDir;
            try {
                int scale = Integer.parseInt(currentWindScale);
                if (scale >= 8) summary += "，大风注意安全";
                else if (scale >= 6) summary += "，风力较大";
            } catch (Exception ignored) {}
            bannerItems.add(new BannerItem("💨", "风力风向", "", summary, R.drawable.weather_card_glass, 0, false));
        }

        // 7. 湿度
        if (!currentHumidity.isEmpty()) {
            int h = 0;
            try { h = Integer.parseInt(currentHumidity); } catch (Exception ignored) {}
            String summary = currentHumidity + "%（" + humidityComfort(h) + "）";
            if (h <= 30) summary += "，注意补水";
            else if (h >= 80) summary += "，体感闷热";
            bannerItems.add(new BannerItem("💧", "湿度", "", summary, R.drawable.weather_card_glass, 0, false));
        }

        // 8. 能见度
        if (!currentVisibility.isEmpty()) {
            double v = 0;
            try { v = Double.parseDouble(currentVisibility); } catch (Exception ignored) {}
            String level;
            if (v < 1) level = "极差";
            else if (v < 5) level = "差";
            else if (v < 10) level = "一般";
            else level = "良好";
            bannerItems.add(new BannerItem("👁", "能见度", level, currentVisibility + "km", R.drawable.weather_card_glass, 0, false));
        }

        // 9. 今日温度
        if (!todayHighTemp.isEmpty() && !todayLowTemp.isEmpty()) {
            String summary = todayLowTemp + "~" + todayHighTemp + "°";
            try {
                int hi = Integer.parseInt(todayHighTemp);
                int lo = Integer.parseInt(todayLowTemp);
                int diff = hi - lo;
                summary += "，温差" + diff + "°";
                if (diff >= 10) summary += "，注意增减衣物";
            } catch (Exception ignored) {}
            bannerItems.add(new BannerItem("🌡", "今日温度", "", summary, R.drawable.weather_card_glass, 0, false));
        }

        // 启动或更新轮播
        if (bannerItems.isEmpty()) {
            if (cardAlertsBanner != null) cardAlertsBanner.setVisibility(View.GONE);
            stopBannerRotation();
        } else {
            if (cardAlertsBanner != null) cardAlertsBanner.setVisibility(View.VISIBLE);
            showBannerItem(0);
            startBannerRotation();
        }
    }

    /** 显示指定预警并常驻（不参与轮播），多条预警可点击切换 */
    private void showAlertBanner(int alertIndex) {
        if (currentAlertInfos == null || currentAlertInfos.isEmpty()) return;
        alertIndex = Math.max(0, Math.min(alertIndex, currentAlertInfos.size() - 1));
        bannerIndex = alertIndex;
        AlertInfo ai = currentAlertInfos.get(alertIndex);
        String type = extractAlertType(ai.title);
        String level = ai.level.isEmpty() ? extractAlertColor(ai.title) : ai.level;
        String tagText = level.isEmpty() ? "预警" : level;
        String summary = ai.description.isEmpty() ? ai.summary : ai.description;
        if (summary.length() > 60) summary = summary.substring(0, 60) + "...";
        int bg = alertBgDrawable(level);

        if (cardAlertsBanner != null) cardAlertsBanner.setVisibility(View.VISIBLE);
        if (bannerIcon != null) bannerIcon.setText("⚠️");
        if (bannerTitle != null) bannerTitle.setText(type + getString(R.string.h_969a9f77));
        if (bannerSummary != null) bannerSummary.setText(summary);
        if (bannerTag != null) {
            bannerTag.setText(tagText);
            bannerTag.setVisibility(View.VISIBLE);
        }
        if (alertBarClickable != null) alertBarClickable.setBackgroundResource(bg);
        if (alertExpandArrow != null) alertExpandArrow.setVisibility(View.VISIBLE);

        // 多条预警：点击卡片切换下一条；单条则展开/折叠
        // 指示器圆点不显示（常驻模式）
        if (bannerDots != null) bannerDots.removeAllViews();

        // 停止轮播，预警常驻
        stopBannerRotation();
    }

    /** 显示指定位置的横幅条目 */
    private void showBannerItem(int index) {
        if (bannerItems.isEmpty() || index < 0 || index >= bannerItems.size()) return;
        bannerIndex = index;
        BannerItem item = bannerItems.get(index);

        if (bannerIcon != null) bannerIcon.setText(item.icon);
        if (bannerTitle != null) bannerTitle.setText(item.title);
        if (bannerSummary != null) bannerSummary.setText(item.summary);

        // 标签
        if (bannerTag != null) {
            if (item.tag != null && !item.tag.isEmpty()) {
                bannerTag.setText(item.tag);
                bannerTag.setVisibility(View.VISIBLE);
            } else {
                bannerTag.setVisibility(View.GONE);
            }
        }

        // 背景
        if (alertBarClickable != null) {
            alertBarClickable.setBackgroundResource(item.bgResId);
        }

        // 指示器圆点
        updateBannerDots();

        // 非预警条目收起展开区
        if (!item.isAlert && alertExpanded) {
            alertExpanded = false;
            if (alertExpandedArea != null) alertExpandedArea.setVisibility(View.GONE);
            if (alertExpandArrow != null) alertExpandArrow.setRotation(0f);
        }
        // 展开箭头：只有预警才显示
        if (alertExpandArrow != null) {
            alertExpandArrow.setVisibility(item.isAlert ? View.VISIBLE : View.GONE);
        }
    }

    /** 更新指示器圆点 */
    private void updateBannerDots() {
        if (bannerDots == null) return;
        bannerDots.removeAllViews();
        int count = bannerItems.size();
        if (count <= 1) return;
        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            int size = dp(5);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.setMarginStart(dp(3));
            dot.setLayoutParams(lp);
            if (i == bannerIndex) {
                dot.setBackgroundResource(R.drawable.weather_card_glass);
                dot.setAlpha(1.0f);
            } else {
                dot.setBackgroundResource(R.drawable.weather_card_glass);
                dot.setAlpha(0.3f);
            }
            bannerDots.addView(dot);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 切换到下一条 */
    private void advanceBanner() {
        if (bannerItems.isEmpty()) return;
        int next = (bannerIndex + 1) % bannerItems.size();
        showBannerItem(next);
    }

    /** 启动自动轮播 */
    private void startBannerRotation() {
        bannerHandler.removeCallbacksAndMessages(null);
        if (bannerItems.size() <= 1) return;
        bannerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) return;
                advanceBanner();
                bannerHandler.postDelayed(this, BANNER_INTERVAL);
            }
        }, BANNER_INTERVAL);
    }

    /** 停止自动轮播 */
    private void stopBannerRotation() {
        bannerHandler.removeCallbacksAndMessages(null);
    }

    private void toggleAlertExpand() {
        if (alertExpandedArea == null || alertExpandArrow == null) return;
        alertExpanded = !alertExpanded;
        // 注意：这里不调用 TransitionManager.beginDelayedTransition——它与数据刷新时的
        // removeAllViews+addView 交织会导致视图快照失效，折叠后展开区子视图丢失。
        // 直接切换 visibility 最可靠。
        alertExpandedArea.setVisibility(alertExpanded ? View.VISIBLE : View.GONE);
        alertExpandArrow.animate().rotation(alertExpanded ? 180f : 0f).setDuration(220).start();

        // 展开时暂停横幅轮播，避免5秒后自动切到非预警条目把展开区收起（表现为"过一会预警消失"）
        if (alertExpanded) {
            stopBannerRotation();
        } else {
            // 折叠后若有多条横幅才恢复轮播
            if (bannerItems.size() > 1) {
                startBannerRotation();
            }
        }
    }

    private void openPrecipMapDetail() {
        String radarUrl = fxLinkMinutely != null && !fxLinkMinutely.isEmpty() ? fxLinkMinutely : fxLinkCurrent;
        if (radarUrl == null || radarUrl.isEmpty()) {
            if (tvPrecipTip != null) tvPrecipTip.setText(getString(R.string.h_2b6827c2));
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(radarUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Cannot open radar map: " + e.getMessage());
            if (tvPrecipTip != null) tvPrecipTip.setText(getString(R.string.h_cd55d806));
        }
    }

    private void openCitySearchDialog() {
        CitySearchDialog dialog = new CitySearchDialog(this, cityEntry -> {
            if (cityEntry != null && cityEntry.nameZh != null) {
                // 清除新位置的缓存，强制刷新
                weatherService.clearCacheForLocation(cityEntry.latitude, cityEntry.longitude);
                weatherService.clearCacheForCity(cityEntry.nameZh);

                /* ---------- 用户主动选择的城市！只用 API 返回的 nameZh ----------
                 *  ❌ 绝不调用 asyncResolveFullAddress！
                 *      → 会把用户选的"银川市"变成"金凤区银川市"，造成歧义！
                 *  ❌ 绝不同步横幅！
                 *      → 用户只是临时想看看别的城市天气，不要污染首页的定位城市！
                 * -------------------------------------------------------------- */
                city = cityEntry.nameZh;
                lat  = cityEntry.latitude;
                lon  = cityEntry.longitude;
                hasLocation = (lat != 0 && lon != 0);
                if (tvCity != null) tvCity.setText(truncateCityName(city));
                updateLocationInfo();

                // 重置 UI 为加载状态
                showMockData();
                loadWeatherData();

                // 联动：切换城市后同步到首页天气横幅（写共享缓存 + 发广播），
                // 返回主页时横幅显示同一城市（与 GPS 定位后的同步行为一致）
                try {
                    com.oilquiz.app.ui.widget.WeatherBannerView
                            .updateSharedLocationCacheAndNotify(
                                    WeatherDetailActivity.this, city, lat, lon);
                } catch (Exception e) {
                    android.util.Log.w("WeatherDetail",
                            "同步切城市到横幅失败: " + e.getMessage());
                }
            }
        });
        dialog.show();
    }

    /**
     * 反解析坐标，返回 [城市名, 具体地址]：
     * - 城市名：locality（如"北京"），用于顶部标题与横幅联动
     * - 具体地址：区+路 / 路+地标 / 区 / 路（如"海淀区中关村大街"），用于副行补充
     */
    private String[] resolveCityAndAddress(double lat, double lon) {
        String cityName = null;
        String detail = null;
        if (Geocoder.isPresent()) {
            try {
                Geocoder geocoder = new Geocoder(this, java.util.Locale.CHINA);
                java.util.List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    String district = address.getSubLocality();  // 区
                    String locality = address.getLocality();     // 市
                    String road = address.getThoroughfare();     // 街道/路名
                    String feature = address.getFeatureName();   // 地标
                    if (locality != null && !locality.isEmpty()) cityName = locality;

                    // 具体地址（取最具体的，与地图 App 定位提示一致）
                    if (district != null && !district.isEmpty()
                        && road != null && !road.isEmpty()
                        && (district.length() + road.length() <= 12)) {
                        detail = district + road;
                    } else if (road != null && !road.isEmpty()
                               && feature != null && !feature.isEmpty()
                               && feature.length() <= 6
                               && (road.length() + feature.length() <= 12)) {
                        detail = road + feature;
                    } else if (district != null && !district.isEmpty()) {
                        detail = district;
                    } else if (road != null && !road.isEmpty() && road.length() <= 10) {
                        detail = road;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Geocoder failed: " + e.getMessage());
            }
        }
        return new String[]{cityName, detail};
    }

    /**
     * 异步反解析坐标：① 回调详情页设置城市名/具体地址；② 同步到天气横幅共享缓存并发广播。
     */
    private void asyncResolveFullAddress(double latitude, double longitude,
                                         java.util.function.BiConsumer<String, String> onResolved) {
        if (onResolved == null) return;
        final double latF = latitude;
        final double lonF = longitude;
        new Thread(() -> {
            String[] pair = resolveCityAndAddress(latF, lonF);
            String cityName = pair[0];
            String detail = pair[1];
            if (cityName == null || cityName.isEmpty()) {
                cityName = (detail != null && !detail.isEmpty()) ? detail : "当前位置";
            }
            final String finalCity = cityName;
            final String finalDetail = detail;
            runOnUiThread(() -> {
                onResolved.accept(finalCity, finalDetail);
                // 同步到横幅：写 SP + 发广播，主页面正在显示的 Banner 会立即刷新
                try {
                    com.oilquiz.app.ui.widget.WeatherBannerView
                            .updateSharedLocationCacheAndNotify(
                                    WeatherDetailActivity.this, finalCity, latF, lonF);
                } catch (Exception e) {
                    android.util.Log.w("WeatherDetail",
                            "同步地址到横幅失败: " + e.getMessage());
                }
            });
        }, "addr-resolve").start();
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
        if (tvCity != null) tvCity.setText(truncateCityName(city));
        if (tvTemp != null) tvTemp.setText("--°");
        if (tvWeather != null) tvWeather.setText(getString(R.string.h_26b5bd49));
        if (tvTempRange != null) tvTempRange.setText("");
        if (tvWeatherSummary != null) tvWeatherSummary.setVisibility(View.GONE);
        if (ivCurrentIcon != null) ivCurrentIcon.setText(QWeatherIconFont.getIcon("999"));

        if (chipFeelsLike != null) chipFeelsLike.setText(getString(R.string.h_1deb24ab));
        if (chipCloud != null) chipCloud.setText(getString(R.string.h_4db4795e));
        if (chipDew != null) chipDew.setText(getString(R.string.h_685a8412));
        if (chipVisibility != null) chipVisibility.setText(getString(R.string.h_4f917d13));

        if (cardAlertsBanner != null) cardAlertsBanner.setVisibility(View.GONE);
        alertExpanded = false;
        if (alertExpandedArea != null) alertExpandedArea.setVisibility(View.GONE);
        if (alertExpandArrow != null) alertExpandArrow.setRotation(0f);
        stopBannerRotation();
        bannerItems.clear();
        bannerIndex = 0;

        if (tvAirSummary != null) tvAirSummary.setText(getString(R.string.h_457351af));
        if (tvWindSummary != null) tvWindSummary.setText("--");
        if (tvAirHealth != null) tvAirHealth.setVisibility(View.GONE);
        if (cardAirForecast != null) cardAirForecast.setVisibility(View.GONE);

        if (hourlyAdapter != null) {
            List<HourlyItem> mockItems = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                mockItems.add(new HourlyItem("--:--", "999", "--", "--°", "", i == 0));
            }
            hourlyAdapter.updateData(mockItems);
        }

        if (tvSunrise != null) tvSunrise.setText("--:--");
        if (tvSunset != null) tvSunset.setText("--:--");

        if (tvWindDirection != null) tvWindDirection.setText(getString(R.string.h_bf320dfa));
        if (tvWindLevel != null) tvWindLevel.setText(getString(R.string.h_9508dd9e));
        if (tvWindSpeed != null) tvWindSpeed.setText("-- km/h");
        if (tvCompassDirection != null) tvCompassDirection.setText("");
        if (tvPressureValue != null) tvPressureValue.setText("--");

        if (tvAirAqi != null) tvAirAqi.setText("--");
        if (tvAirCategory != null) tvAirCategory.setText(getString(R.string.h_21efd88b));
        if (tvAirPm25Label != null) tvAirPm25Label.setText("PM2.5");
        if (tvAirPm10Label != null) tvAirPm10Label.setText("PM10");
        if (tvAirNo2Label != null) tvAirNo2Label.setText("NO₂");
        if (tvAirSo2Label != null) tvAirSo2Label.setText("SO₂");
        if (tvAirCoLabel != null) tvAirCoLabel.setText("CO");
        if (tvAirO3Label != null) tvAirO3Label.setText("O₃");
        if (tvAirPm25 != null) tvAirPm25.setText("--");
        if (tvAirPm10 != null) tvAirPm10.setText("--");
        if (tvAirNo2 != null) tvAirNo2.setText("--");
        if (tvAirSo2 != null) tvAirSo2.setText("--");
        if (tvAirCo != null) tvAirCo.setText("--");
        if (tvAirO3 != null) tvAirO3.setText("--");
        if (tvAirPrimaryPollutant != null) tvAirPrimaryPollutant.setVisibility(View.GONE);
        if (tvAirHealth != null) tvAirHealth.setVisibility(View.GONE);

        if (llDaily != null) llDaily.removeAllViews();
        if (llAlerts != null) llAlerts.removeAllViews();
        dailyItemCount = 0;
        todayDayWeather = "";
        todayNightWeather = "";
        todayHighTemp = "";
        todayLowTemp = "";
        currentWeatherText = "";
        currentTempVal = "";
        currentFeelsLike = "";
        currentWindScale = "";
        minutelySummary = "";
        alertSummary = "";
        currentHumidity = "";
        currentUv = "";
        currentDew = "";
        currentPressure = "";
        currentVisibility = "";
        currentWindDir = "";
        currentAirAqi = "";
        currentAirCategory = "";
        currentAirPrimary = "";
        currentAlertInfos = null;

        if (gaugeUvArc != null) {
            gaugeUvArc.setProgressImmediate(0);
            gaugeUvArc.setText("0");
            gaugeUvArc.setLabel("弱");
            gaugeUvArc.setArcColor(getResources().getColor(R.color.uv_low));
        }
        if (gaugeHumidityArc != null) {
            gaugeHumidityArc.setProgressImmediate(0);
            gaugeHumidityArc.setText("--%");
            gaugeHumidityArc.setLabel(getString(R.string.h_f1d25753));
            gaugeHumidityArc.setArcColor(getResources().getColor(R.color.humidity_comfort));
        }

        if (tvUpdateTime != null) tvUpdateTime.setText(getString(R.string.h_4458129c));
    }

    private void loadWeatherData() {
        if (weatherService == null) return;

        updateLocationInfo();

        // 没有坐标且城市为空时，无法查询天气
        if (!hasLocation && (city == null || city.isEmpty())) {
            Log.w(TAG, "No location and no city, cannot load weather");
            runOnUiThread(() -> {
                if (tvWeather != null) tvWeather.setText(getString(R.string.h_23681583));
                if (tvUpdateTime != null) tvUpdateTime.setText(getString(R.string.h_ce0ea572));
                if (tvCity != null) tvCity.setText(getString(R.string.h_0fcd7253));
            });
            return;
        }

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
                    if (result.airForecast != null) parseAndUpdateAirForecast(result.airForecast);
                } else {
                    if (tvAirCategory != null) tvAirCategory.setText(getString(R.string.h_4cbee84f));
                    if (tvAirSummary != null) tvAirSummary.setText(getString(R.string.h_4a27e834));
                    if (tvAirAqi != null) tvAirAqi.setText("--");
                    if (tvAirPrimaryPollutant != null) tvAirPrimaryPollutant.setVisibility(View.GONE);
                    if (tvAirHealth != null) tvAirHealth.setVisibility(View.GONE);
                    currentAlertInfos = null;
                    alertSummary = "";
                    rebuildBannerItems();
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
                    tvUpdateTime.setText(getString(R.string.h_dde79cba) + time);
                }
            });
        }).exceptionally(e -> {
            Log.e(TAG, "Failed to load weather data: " + e.getMessage(), e);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (tvWeather != null) tvWeather.setText(getString(R.string.h_866b795e));
                if (tvUpdateTime != null) tvUpdateTime.setText(getString(R.string.h_860225c4) + e.getMessage());
            });
            return null;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBannerRotation();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopBannerRotation();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBannerRotation();
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
        if (uv <= 2) return ThemeColors.get(R.color.hc_ff10b981);
        if (uv <= 5) return ThemeColors.get(R.color.hc_fff59e0b);
        if (uv <= 7) return ThemeColors.get(R.color.hc_ffef4444);
        if (uv <= 10) return ThemeColors.get(R.color.hc_ff7c3aed);
        return ThemeColors.get(R.color.hc_ff991b1b);
    }

    private static String humidityComfort(int h) {
        if (h < 40) return "干燥";
        if (h <= 70) return "舒适";
        return "潮湿";
    }

    private static int humidityColor(int h) {
        if (h < 40) return ThemeColors.get(R.color.hc_fffb923c);
        if (h <= 70) return ThemeColors.get(R.color.hc_ff22d3ee);
        return ThemeColors.get(R.color.hc_ff8b5cf6);
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
        int paren = v.indexOf('(');
        if (paren > 0) v = v.substring(0, paren).trim();
        return v.isEmpty() ? "--" : v;
    }

    private static String extractAirFullName(String line) {
        int paren = line.indexOf('(');
        int endParen = line.indexOf(')');
        if (paren > 0 && endParen > paren) {
            return line.substring(paren + 1, endParen).trim();
        }
        return "";
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
        String visibility = "";

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
            } else if (line.startsWith("能见度:")) {
                String vis = line.substring(4).trim().replace("km", "").trim();
                if (!vis.isEmpty()) visibility = vis;
            }
        }

        if (tvCity != null && city != null) tvCity.setText(truncateCityName(city));
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
                tvTempRange.setText(getString(R.string.h_9b53e2a0) + highTemp + getString(R.string.h_665f6e4c) + lowTemp + "°");
            } else {
                tvTempRange.setText("");
            }
        }

        if (chipFeelsLike != null) chipFeelsLike.setText(getString(R.string.h_af4f1044) + feelsLike + "°");
        if (chipCloud != null) chipCloud.setText(getString(R.string.h_60221e73) + cloud + "%");
        if (chipDew != null) chipDew.setText(getString(R.string.h_5b7b89a9) + dew + "°");
        if (chipVisibility != null) chipVisibility.setText(getString(R.string.h_148fc868) + (visibility.isEmpty() || visibility.equals("--") ? "--" : visibility + "km"));

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

        // 保存当前天气数据用于生成说明
        currentWeatherText = weather;
        currentTempVal = temp;
        currentFeelsLike = feelsLike;
        currentWindScale = windScale;
        currentWindDir = windDir;
        currentHumidity = humidity;
        currentUv = uv;
        currentDew = dew;
        currentPressure = pressure;
        currentVisibility = visibility;
        updateWeatherSummary();
        rebuildBannerItems();
    }

    private void parseAndUpdateHourlyWeather(String weatherText) {
        if (weatherText == null || hourlyAdapter == null) return;

        List<HourlyItem> items = new ArrayList<>();
        String[] lines = weatherText.split("\n");

        // 检测数据格式：多行格式(AIWeatherManager) vs 单行格式(SDK)
        boolean multiLineFormat = weatherText.contains("时间: ") && weatherText.contains("天气: ");

        if (multiLineFormat) {
            // AIWeatherManager 多行格式
            String time = "--:--", temp = "--°", iconCode = "999", weatherDesc = "", pop = "";
            boolean hasEntry = false;

            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("24小时预报") || line.startsWith("链接:")) continue;

                if (line.startsWith("时间:")) {
                    if (hasEntry && !time.equals("--:--")) {
                        items.add(new HourlyItem(time, iconCode,
                                weatherDesc.isEmpty() ? "--" : weatherDesc, temp, pop, items.isEmpty()));
                        if (items.size() >= 24) break;
                    }
                    time = line.substring(3).trim();
                    temp = "--°"; iconCode = "999"; weatherDesc = ""; pop = "";
                    hasEntry = true;
                } else if (line.startsWith("温度:")) {
                    temp = line.substring(3).trim().replace("°C", "").replace("°", "") + "°";
                } else if (line.startsWith("天气:")) {
                    weatherDesc = line.substring(3).trim();
                } else if (line.startsWith("降水:")) {
                    pop = line.substring(3).trim();
                    if (!pop.endsWith("%")) pop += "%";
                } else if (line.startsWith("图标:")) {
                    iconCode = line.substring(3).trim();
                }
            }
            if (hasEntry && !time.equals("--:--") && items.size() < 24) {
                items.add(new HourlyItem(time, iconCode,
                        weatherDesc.isEmpty() ? "--" : weatherDesc, temp, pop, items.isEmpty()));
            }
        } else {
            // SDK 单行格式: "14:00 25°C 晴 图标:100 降水0%"
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("24小时预报") || line.startsWith("链接:")) continue;

                String time = "--:--", temp = "--°", iconCode = "999", weatherDesc = "", pop = "";

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
                        pop = part.replace("降水", "").trim();
                        if (!pop.endsWith("%")) pop += "%";
                    } else {
                        if (weatherDesc.isEmpty() && !part.contains(":") && part.length() > 1) {
                            weatherDesc = part;
                        }
                    }
                }

                if (!time.equals("--:--")) {
                    items.add(new HourlyItem(time, iconCode,
                            weatherDesc.isEmpty() ? "--" : weatherDesc, temp, pop, items.isEmpty()));
                }
                if (items.size() >= 24) break;
            }
        }

        if (items.isEmpty()) {
            for (int i = 0; i < 8; i++) {
                items.add(new HourlyItem("--:--", "999", "--", "--°", "", i == 0));
            }
        }

        hourlyAdapter.updateData(items);
    }

    private void parseAndUpdateDailyWeather(String weatherText) {
        if (weatherText == null || llDaily == null) return;

        llDaily.removeAllViews();
        dailyItemCount = 0;

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
            tvEmpty.setText(getString(R.string.h_ac7bf3e0));
            tvEmpty.setTextSize(13);
            tvEmpty.setTextColor(getResources().getColor(R.color.future_text_secondary));
            tvEmpty.setPadding(16, 24, 16, 24);
            llDaily.addView(tvEmpty);
        }
    }

    private int dailyItemCount = 0;

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

        // 保存今日预报数据
        if (dailyItemCount == 0) {
            todayDayWeather = weatherDay;
            todayNightWeather = weatherNight;
            todayHighTemp = highTemp;
            todayLowTemp = lowTemp;
            updateWeatherSummary();
        }
        dailyItemCount++;

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
        StringBuilder healthAdvice = new StringBuilder();
        String primaryPollutant = "";
        String pm25 = "--"; String pm25Name = "";
        String pm10 = "--"; String pm10Name = "";
        String no2 = "--"; String no2Name = "";
        String so2 = "--"; String so2Name = "";
        String co = "--"; String coName = "";
        String o3 = "--"; String o3Name = "";

        if (weatherText.contains("查询失败") || weatherText.contains("暂无权限") || weatherText.contains("无权限")) {
            String errorMsg = weatherText.replace("空气质量:", "").replace("空气质量", "").trim();
            if (tvAirCategory != null) tvAirCategory.setText(errorMsg.isEmpty() ? getString(R.string.h_0d66ed02) : errorMsg);
            if (tvAirSummary != null) tvAirSummary.setText(getString(R.string.h_94b36989));
            if (tvAirHealth != null) tvAirHealth.setVisibility(View.GONE);
            if (tvAirPrimaryPollutant != null) tvAirPrimaryPollutant.setVisibility(View.GONE);
            currentAirAqi = "";
            currentAirCategory = "";
            currentAirPrimary = "";
            rebuildBannerItems();
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
            if (!inAirSection) continue;
            if (line.startsWith("链接:")) continue;
            if (line.startsWith("污染物浓度:")) continue;

            if (line.startsWith("AQI:")) {
                String rest = line.substring(4).trim();
                int parenIdx = rest.indexOf('(');
                if (parenIdx > 0) {
                    aqi = rest.substring(0, parenIdx).trim();
                    String inside = rest.substring(parenIdx);
                    int commaIdx = inside.indexOf(',');
                    if (commaIdx > 0) {
                        category = inside.substring(commaIdx + 1, inside.length() - 1).trim();
                    } else {
                        category = inside.substring(1, inside.length() - 1).trim();
                    }
                } else {
                    aqi = rest;
                }
            } else if (line.startsWith("PM2.5:") || line.startsWith("PM2p5:") || line.startsWith("PM 2.5:")) {
                pm25 = cleanAirValue(line);
                pm25Name = extractAirFullName(line);
            } else if (line.startsWith("PM10:") || line.startsWith("PM 10:")) {
                pm10 = cleanAirValue(line);
                pm10Name = extractAirFullName(line);
            } else if (line.startsWith("NO2:")) {
                no2 = cleanAirValue(line);
                no2Name = extractAirFullName(line);
            } else if (line.startsWith("SO2:")) {
                so2 = cleanAirValue(line);
                so2Name = extractAirFullName(line);
            } else if (line.startsWith("CO:")) {
                co = cleanAirValue(line);
                coName = extractAirFullName(line);
            } else if (line.startsWith("O3:")) {
                o3 = cleanAirValue(line);
                o3Name = extractAirFullName(line);
            } else if (line.startsWith("等级:")) {
                if (category.equals("暂无数据")) category = line.substring(3).trim();
            } else if (line.startsWith("首要污染物:")) {
                primaryPollutant = line.substring("首要污染物:".length()).trim();
            } else if (line.startsWith("健康影响:")) {
                if (healthAdvice.length() > 0) healthAdvice.append("\n");
                healthAdvice.append(line.substring(5).trim());
            } else if (line.startsWith("一般人群:")) {
                if (healthAdvice.length() > 0) healthAdvice.append("\n");
                healthAdvice.append(getString(R.string.h_2351df51)).append(line.substring(5).trim());
            } else if (line.startsWith("敏感人群:")) {
                if (healthAdvice.length() > 0) healthAdvice.append("\n");
                healthAdvice.append(getString(R.string.h_e908a14d)).append(line.substring(5).trim());
            } else if (line.startsWith("健康提示:")) {
                if (healthAdvice.length() > 0) healthAdvice.append("\n");
                healthAdvice.append(line.substring(5).trim());
            } else if (line.startsWith("健康建议:")) {
                healthAdvice = new StringBuilder(line.substring(5).trim());
            }
        }

        if (tvAirAqi != null) tvAirAqi.setText(aqi);
        if (tvAirCategory != null) tvAirCategory.setText(category);

        setAirPollutantDisplay(tvAirPm25Label, tvAirPm25, pm25, pm25Name, "PM2.5");
        setAirPollutantDisplay(tvAirPm10Label, tvAirPm10, pm10, pm10Name, "PM10");
        setAirPollutantDisplay(tvAirNo2Label, tvAirNo2, no2, no2Name, "NO₂");
        setAirPollutantDisplay(tvAirSo2Label, tvAirSo2, so2, so2Name, "SO₂");
        setAirPollutantDisplay(tvAirCoLabel, tvAirCo, co, coName, "CO");
        setAirPollutantDisplay(tvAirO3Label, tvAirO3, o3, o3Name, "O₃");

        if (tvAirPrimaryPollutant != null) {
            if (!primaryPollutant.isEmpty()) {
                tvAirPrimaryPollutant.setText(getString(R.string.h_5130aa9b) + primaryPollutant);
                tvAirPrimaryPollutant.setVisibility(View.VISIBLE);
            } else {
                tvAirPrimaryPollutant.setVisibility(View.GONE);
            }
        }

        if (tvAirHealth != null) {
            if (healthAdvice.length() > 0) {
                tvAirHealth.setText("💡 " + healthAdvice.toString());
                tvAirHealth.setVisibility(View.VISIBLE);
            } else {
                tvAirHealth.setVisibility(View.GONE);
            }
        }

        if (tvAirSummary != null && !aqi.equals("--")) {
            tvAirSummary.setText(aqi + " " + category);
        }

        // 保存空气质量数据供横幅使用
        currentAirAqi = aqi;
        currentAirCategory = category;
        currentAirPrimary = primaryPollutant;
        rebuildBannerItems();
    }

    private void setAirPollutantDisplay(TextView label, TextView value, String val, String fullName, String defaultLabel) {
        if (label != null) {
            label.setText((fullName != null && !fullName.isEmpty()) ? fullName : defaultLabel);
        }
        if (value != null) value.setText(val);
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
        List<String[]> alertTexts = new ArrayList<>(); // each: [rawText]
        StringBuilder currentAlert = new StringBuilder();

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
                    alertTexts.add(new String[]{currentAlert.toString()});
                    currentAlert = new StringBuilder();
                }
            } else if (line.equals("暂无预警信息") || line.equals("当前无天气预警")) {
                break;
            } else {
                if (currentAlert.length() > 0) currentAlert.append("\n");
                currentAlert.append(line);
            }
        }
        if (currentAlert.length() > 0) {
            alertTexts.add(new String[]{currentAlert.toString()});
        }

        if (alertTexts.isEmpty()) {
            currentAlertInfos = null;
            alertSummary = "";
            updateWeatherSummary();
            rebuildBannerItems();
            return;
        }

        // 解析所有预警为结构化数据
        List<AlertInfo> alertInfos = new ArrayList<>();
        for (String[] item : alertTexts) {
            alertInfos.add(parseAlertInfo(item[0]));
        }
        currentAlertInfos = alertInfos;

        // 生成预警摘要
        alertSummary = buildAlertSummary(alertInfos);
        updateWeatherSummary();

        // 为每条预警创建卡片
        for (int i = 0; i < alertInfos.size(); i++) {
            AlertInfo info = alertInfos.get(i);
            View card = buildAlertCard(info, i > 0);
            llAlerts.addView(card);
        }

        // 重建横幅（预警 + 天气信息轮播）
        rebuildBannerItems();
    }

    /** 构建预警摘要：取最新发布的预警提取关键信息，附其他预警数量 */
    private static String buildAlertSummary(List<AlertInfo> alertInfos) {
        if (alertInfos == null || alertInfos.isEmpty()) return "";

        // 按发布时间排序，最新的在前
        List<AlertInfo> sorted = new ArrayList<>(alertInfos);
        sorted.sort((a, b) -> {
            // publishTime 格式如 "2024-01-01 14:00" 或 "--"
            String ta = a.publishTime == null ? "" : a.publishTime;
            String tb = b.publishTime == null ? "" : b.publishTime;
            return tb.compareTo(ta); // 降序
        });

        AlertInfo latest = sorted.get(0);
        StringBuilder sb = new StringBuilder();

        // 提取预警类型和等级
        String type = extractAlertType(latest.title);
        String level = latest.level;
        if (level.isEmpty() || level.equals("预警")) {
            level = extractAlertColor(latest.title);
        }

        // 拼接类型+等级
        if (!level.isEmpty() && !level.equals("预警")) {
            sb.append("[").append(level).append(type).append("]");
        } else {
            sb.append("[").append(type).append(SmartQuizApplication.getAppContext().getString(R.string.h_d5e41258));
        }

        // 关键内容：显示完整描述
        if (!latest.description.isEmpty()) {
            sb.append("：").append(latest.description.trim());
        }

        // 防御行动：显示完整防御指南
        if (!latest.defense.isEmpty()) {
            if (!keyContent_endsWithPeriod(sb)) sb.append("。");
            sb.append(latest.defense.trim());
        }

        // 如果还有其他预警，附上数量和类型
        if (sorted.size() > 1) {
            sb.append(SmartQuizApplication.getAppContext().getString(R.string.h_68cbd329));
            for (int i = 1; i < sorted.size(); i++) {
                AlertInfo other = sorted.get(i);
                String otherType = extractAlertType(other.title);
                String otherLevel = extractAlertColor(other.title);
                if (i > 1) sb.append("、");
                if (!otherLevel.isEmpty()) {
                    sb.append(otherLevel).append(otherType);
                } else {
                    sb.append(otherType).append(SmartQuizApplication.getAppContext().getString(R.string.h_969a9f77));
                }
            }
            sb.append("）");
        }

        return sb.toString();
    }

    /** 从标题中提取预警类型，如 "【橙色】雷电(橙色)" → "雷电" */
    private static String extractAlertType(String title) {
        if (title == null || title.isEmpty()) return SmartQuizApplication.getAppContext().getString(R.string.h_265f273c);
        // 去掉【】部分
        String s = title;
        int bracketEnd = s.indexOf('】');
        if (bracketEnd >= 0) s = s.substring(bracketEnd + 1);
        // 去掉()部分
        int parenIdx = s.indexOf('(');
        if (parenIdx > 0) s = s.substring(0, parenIdx);
        s = s.trim();
        return s.isEmpty() ? "天气" : s;
    }

    /** 从标题中提取颜色等级 */
    private static String extractAlertColor(String title) {
        if (title == null) return "";
        if (title.contains("红")) return SmartQuizApplication.getAppContext().getString(R.string.h_52636511);
        if (title.contains("橙")) return SmartQuizApplication.getAppContext().getString(R.string.h_d9bbeb44);
        if (title.contains("黄")) return SmartQuizApplication.getAppContext().getString(R.string.h_ddb86dd3);
        if (title.contains("蓝")) return SmartQuizApplication.getAppContext().getString(R.string.h_9c9aabab);
        return "";
    }

    /** 提取第一句话，最大 maxLen 字符 */
    private static String firstSentence(String text, int maxLen) {
        if (text == null || text.isEmpty()) return "";
        // 按句号/分号/换行分割，取第一段
        String[] delimiters = {"。", "；", "\n", "，"};
        String first = text;
        for (String d : delimiters) {
            int idx = first.indexOf(d);
            if (idx > 0 && idx < first.length()) {
                first = first.substring(0, idx);
            }
        }
        first = first.trim();
        if (first.length() > maxLen) {
            first = first.substring(0, maxLen) + "...";
        }
        return first;
    }

    /** 检查 StringBuilder 是否以句号结尾 */
    private static boolean keyContent_endsWithPeriod(StringBuilder sb) {
        if (sb.length() == 0) return true;
        char last = sb.charAt(sb.length() - 1);
        return last == '。' || last == '.' || last == '；' || last == ';';
    }

    private static class AlertInfo {
        String title = SmartQuizApplication.getAppContext().getString(R.string.h_4685224b);
        String level = "预警";
        String summary = "";
        String description = "";
        String defense = "";
        String publishTime = "--";
        String sender = "";
        String urgency = "";
        String certainty = "";
        String effectiveTime = "";
        String onsetTime = "";
        String alertId = "";
        String icon = "";
    }

    private AlertInfo parseAlertInfo(String text) {
        AlertInfo info = new AlertInfo();
        StringBuilder descBuilder = new StringBuilder();

        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 第一行是标题行，格式如 【橙色】雷电(橙色)
            if (line.startsWith("【") && info.title.equals(getString(R.string.h_4685224b))) {
                info.title = line;
                if (line.contains("红")) info.level = "红色";
                else if (line.contains("橙")) info.level = "橙色";
                else if (line.contains("黄")) info.level = "黄色";
                else if (line.contains("蓝")) info.level = "蓝色";
                continue;
            }

            if (line.startsWith("标题:")) {
                info.title = line.substring(3).trim();
            } else if (line.startsWith("发布机构:")) {
                info.sender = line.substring(5).trim();
            } else if (line.startsWith("预警等级:")) {
                info.level = line.substring(5).trim();
            } else if (line.startsWith("紧急程度:")) {
                info.urgency = line.substring(5).trim();
            } else if (line.startsWith("确定性:")) {
                info.certainty = line.substring(4).trim();
            } else if (line.startsWith("起始时间:")) {
                info.onsetTime = line.substring(5).trim();
            } else if (line.startsWith("预警ID:")) {
                info.alertId = line.substring(5).trim();
            } else if (line.startsWith("预警图标:")) {
                info.icon = line.substring(5).trim();
            } else if (line.startsWith("发布时间:")) {
                info.publishTime = line.substring(5).trim();
            } else if (line.startsWith("生效时间:")) {
                info.effectiveTime = line.substring(5).trim();
            } else if (line.startsWith("内容:")) {
                info.description = line.substring(3).trim();
            } else if (line.startsWith("防御指南:")) {
                info.defense = line.substring(5).trim();
            } else if (line.length() > 20) {
                // 长文本作为描述
                if (descBuilder.length() > 0) descBuilder.append("\n");
                descBuilder.append(line);
            } else if (info.summary.isEmpty() && line.length() > 5) {
                info.summary = line;
            }
        }

        // 如果没有明确的描述，使用长文本
        if (info.description.isEmpty() && descBuilder.length() > 0) {
            info.description = descBuilder.toString();
        }
        // 如果没有防御指南但有内容，尝试从描述中提取
        if (info.defense.isEmpty()) {
            int idx = info.description.indexOf("防御");
            if (idx >= 0) {
                info.defense = info.description.substring(idx).trim();
                info.description = info.description.substring(0, idx).trim();
            }
        }

        // 格式化时间
        info.publishTime = formatAlertTime(info.publishTime);
        if (!info.onsetTime.isEmpty()) info.onsetTime = formatAlertTime(info.onsetTime);

        return info;
    }

    private String formatAlertTime(String raw) {
        if (raw == null || raw.isEmpty() || raw.equals("--")) return "--";
        // ISO format: 2024-01-01T06:30+08:00 -> 01日 06:30
        int tIdx = raw.indexOf('T');
        if (tIdx > 0 && raw.length() >= tIdx + 6) {
            String datePart = raw.substring(0, tIdx);
            String timePart = raw.substring(tIdx + 1, Math.min(tIdx + 6, raw.length()));
            // 提取月日
            String[] dateParts = datePart.split("-");
            if (dateParts.length >= 3) {
                return dateParts[1] + "月" + dateParts[2] + "日 " + timePart;
            }
        }
        return raw;
    }

    private String getAlertEmoji(String title) {
        if (title == null) return "⚠️";
        if (title.contains(getString(R.string.h_569d866e))) return "🌧️";
        if (title.contains(getString(R.string.h_43dfd700)) || title.contains(getString(R.string.h_db5f1712))) return "⛈️";
        if (title.contains(getString(R.string.h_46ad421d)) || title.contains(getString(R.string.h_cb9bc278))) return "💨";
        if (title.contains(getString(R.string.h_7cd28c6c)) || title.contains(getString(R.string.h_4fafcc2c))) return "❄️";
        if (title.contains(getString(R.string.h_ea25b6ea))) return "🔥";
        if (title.contains(getString(R.string.h_1ea91433)) || title.contains(getString(R.string.h_b04f6fd4))) return "🥶";
        if (title.contains(getString(R.string.h_5e9bb27c)) || title.contains(getString(R.string.h_e527a26f))) return "🧊";
        if (title.contains(getString(R.string.h_6ab232c9))) return "🌫️";
        if (title.contains("霾")) return "😷";
        if (title.contains(getString(R.string.h_b2036718)) || title.contains(getString(R.string.h_014e238d))) return "🏜️";
        if (title.contains(getString(R.string.h_647d33af))) return "🌀";
        if (title.contains(getString(R.string.h_cb1de6e5)) || title.contains(getString(R.string.h_ea27df54))) return "🌊";
        if (title.contains(getString(R.string.h_5f776911))) return "🏜️";
        if (title.contains(getString(R.string.h_33788212))) return "🧊";
        if (title.contains(getString(R.string.h_5d5b12fb))) return "❄️";
        if (title.contains(getString(R.string.h_cb3d7529)) || title.contains(getString(R.string.h_596297f1))) return "🔥";
        return "⚠️";
    }

    private int getLevelColor(String level) {
        if (level == null) return ThemeColors.get(R.color.hc_ff60a5fa);
        if (level.contains("红")) return ThemeColors.get(R.color.hc_ffef4444);
        if (level.contains("橙")) return ThemeColors.get(R.color.hc_fff97316);
        if (level.contains("黄")) return ThemeColors.get(R.color.hc_ffeab308);
        if (level.contains("蓝")) return ThemeColors.get(R.color.hc_ff3b82f6);
        return ThemeColors.get(R.color.hc_ff60a5fa);
    }

    private View buildAlertCard(AlertInfo info, boolean addTopMargin) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(28, 28, 28, 28);
        if (addTopMargin) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 24;
            card.setLayoutParams(lp);
        }

        // 圆角背景 + 等级颜色边
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(24);
        bg.setColor(ThemeColors.get(R.color.hc_1affffff));
        bg.setStroke(4, getLevelColor(info.level));
        card.setBackground(bg);

        // === 头部：emoji + 标题 + 等级标签 ===
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tvEmoji = new TextView(this);
        tvEmoji.setText(getAlertEmoji(info.title));
        tvEmoji.setTextSize(18);
        header.addView(tvEmoji);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(info.title);
        tvTitle.setTextSize(14);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(getResources().getColor(R.color.future_text_primary));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleLp.leftMargin = 24;
        tvTitle.setLayoutParams(titleLp);
        header.addView(tvTitle);

        TextView tvLevel = new TextView(this);
        tvLevel.setText(info.level);
        tvLevel.setTextSize(11);
        tvLevel.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
        tvLevel.setPadding(24, 6, 24, 6);
        android.graphics.drawable.GradientDrawable levelBg = new android.graphics.drawable.GradientDrawable();
        levelBg.setCornerRadius(20);
        levelBg.setColor(getLevelColor(info.level));
        tvLevel.setBackground(levelBg);
        header.addView(tvLevel);

        card.addView(header);

        // === 元数据区域 ===
        StringBuilder meta = new StringBuilder();
        if (!info.publishTime.isEmpty() && !info.publishTime.equals("--"))
            meta.append(getString(R.string.h_88f892c5)).append(info.publishTime);
        if (!info.sender.isEmpty()) {
            if (meta.length() > 0) meta.append("\n");
            meta.append(getString(R.string.h_84a3191b)).append(info.sender);
        }
        if (!info.effectiveTime.isEmpty()) {
            if (meta.length() > 0) meta.append("\n");
            meta.append(getString(R.string.h_1bf343fc)).append(info.effectiveTime);
        }
        if (!info.urgency.isEmpty()) {
            if (meta.length() > 0) meta.append("  ");
            meta.append(getString(R.string.h_3a68b89c)).append(info.urgency);
        }
        if (!info.certainty.isEmpty()) {
            if (meta.length() > 0) meta.append("  ");
            meta.append(getString(R.string.h_bae21822)).append(info.certainty);
        }

        if (meta.length() > 0) {
            TextView tvMeta = new TextView(this);
            tvMeta.setText(meta.toString());
            tvMeta.setTextSize(11);
            tvMeta.setTextColor(getResources().getColor(R.color.future_text_tertiary));
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            metaLp.topMargin = 16;
            tvMeta.setLayoutParams(metaLp);
            card.addView(tvMeta);
        }

        // === 描述 ===
        if (!info.description.isEmpty()) {
            TextView tvDesc = new TextView(this);
            tvDesc.setText(info.description);
            tvDesc.setTextSize(13);
            tvDesc.setTextColor(getResources().getColor(R.color.future_text_secondary));
            tvDesc.setLineSpacing(3, 1);
            LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            descLp.topMargin = 20;
            tvDesc.setLayoutParams(descLp);
            card.addView(tvDesc);
        }

        // === 防御指南 ===
        if (!info.defense.isEmpty()) {
            TextView tvDefenseLabel = new TextView(this);
            tvDefenseLabel.setText(getString(R.string.h_8aa8b23a));
            tvDefenseLabel.setTextSize(12);
            tvDefenseLabel.setTypeface(null, Typeface.BOLD);
            tvDefenseLabel.setTextColor(getLevelColor(info.level));
            LinearLayout.LayoutParams dLabelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            dLabelLp.topMargin = 24;
            tvDefenseLabel.setLayoutParams(dLabelLp);
            card.addView(tvDefenseLabel);

            TextView tvDefense = new TextView(this);
            tvDefense.setText(info.defense);
            tvDefense.setTextSize(12);
            tvDefense.setTextColor(getResources().getColor(R.color.future_text_tertiary));
            tvDefense.setLineSpacing(2, 1);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            dLp.topMargin = 8;
            tvDefense.setLayoutParams(dLp);
            card.addView(tvDefense);
        }

        return card;
    }

    private void parseAndUpdateAirForecast(String weatherText) {
        if (weatherText == null || llAirForecast == null || cardAirForecast == null) return;

        llAirForecast.removeAllViews();

        if (weatherText.contains("查询失败") || weatherText.contains("暂无预报") || weatherText.contains("不支持")) {
            cardAirForecast.setVisibility(View.GONE);
            return;
        }

        boolean inForecastSection = false;
        List<String[]> forecastDays = new ArrayList<>(); // date, aqi, category, primary

        String currentDate = "";
        String currentAqi = "--";
        String currentCategory = "";
        String currentPrimary = "";

        String[] lines = weatherText.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("空气质量预报:")) {
                inForecastSection = true;
                continue;
            }
            if (!inForecastSection) continue;
            if (line.isEmpty()) continue;

            // 日期行格式: 2026-08-01 AQI: 85 (良)
            if (line.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
                // 保存前一天的数据
                if (!currentDate.isEmpty()) {
                    forecastDays.add(new String[]{currentDate, currentAqi, currentCategory, currentPrimary});
                }
                currentDate = line.substring(0, 10);
                currentAqi = "--";
                currentCategory = "";
                currentPrimary = "";

                int aqiIdx = line.indexOf("AQI:");
                if (aqiIdx >= 0) {
                    String rest = line.substring(aqiIdx + 4).trim();
                    int parenIdx = rest.indexOf('(');
                    if (parenIdx > 0) {
                        currentAqi = rest.substring(0, parenIdx).trim();
                        int endParen = rest.indexOf(')');
                        if (endParen > parenIdx) {
                            currentCategory = rest.substring(parenIdx + 1, endParen).trim();
                        }
                    } else {
                        String[] parts = rest.split("\\s+");
                        if (parts.length > 0) currentAqi = parts[0];
                    }
                }
            } else if (line.startsWith("  首要污染物:")) {
                currentPrimary = line.substring(7).trim();
            }
        }
        // 保存最后一天
        if (!currentDate.isEmpty()) {
            forecastDays.add(new String[]{currentDate, currentAqi, currentCategory, currentPrimary});
        }

        if (forecastDays.isEmpty()) {
            cardAirForecast.setVisibility(View.GONE);
            return;
        }

        cardAirForecast.setVisibility(View.VISIBLE);

        for (String[] day : forecastDays) {
            View dayView = buildAirForecastDayView(day[0], day[1], day[2], day[3]);
            llAirForecast.addView(dayView);
        }
    }

    private View buildAirForecastDayView(String date, String aqi, String category, String primary) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        container.setLayoutParams(lp);

        // 日期 (MM-DD)
        String shortDate = date;
        if (date.length() >= 10) {
            shortDate = date.substring(5); // MM-DD
        }

        TextView tvDate = new TextView(this);
        tvDate.setText(shortDate);
        tvDate.setTextSize(11);
        tvDate.setTextColor(getResources().getColor(R.color.future_text_tertiary));
        container.addView(tvDate);

        // AQI 数值
        TextView tvAqi = new TextView(this);
        tvAqi.setText(aqi);
        tvAqi.setTextSize(18);
        tvAqi.setTypeface(null, Typeface.BOLD);
        int aqiColor = getAqiColor(aqi, category);
        tvAqi.setTextColor(aqiColor);
        LinearLayout.LayoutParams aqiLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        aqiLp.topMargin = 6;
        tvAqi.setLayoutParams(aqiLp);
        container.addView(tvAqi);

        // 等级
        TextView tvCategory = new TextView(this);
        tvCategory.setText(category.isEmpty() ? "--" : category);
        tvCategory.setTextSize(10);
        tvCategory.setTextColor(aqiColor);
        LinearLayout.LayoutParams catLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        catLp.topMargin = 4;
        tvCategory.setLayoutParams(catLp);
        container.addView(tvCategory);

        // 首要污染物
        if (!primary.isEmpty()) {
            TextView tvPrimary = new TextView(this);
            tvPrimary.setText(primary);
            tvPrimary.setTextSize(9);
            tvPrimary.setTextColor(getResources().getColor(R.color.future_text_tertiary));
            LinearLayout.LayoutParams primLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            primLp.topMargin = 4;
            tvPrimary.setLayoutParams(primLp);
            container.addView(tvPrimary);
        }

        return container;
    }

    private int getAqiColor(String aqi, String category) {
        if (category.contains("优")) return ThemeColors.get(R.color.hc_ff22c55e);
        if (category.contains("良")) return ThemeColors.get(R.color.hc_ffeab308);
        if (category.contains("轻度")) return ThemeColors.get(R.color.hc_fff97316);
        if (category.contains("中度")) return ThemeColors.get(R.color.hc_ffef4444);
        if (category.contains("重度") || category.contains("严重")) return ThemeColors.get(R.color.hc_ff991b1b);
        try {
            int val = Integer.parseInt(aqi);
            if (val <= 50) return ThemeColors.get(R.color.hc_ff22c55e);
            if (val <= 100) return ThemeColors.get(R.color.hc_ffeab308);
            if (val <= 150) return ThemeColors.get(R.color.hc_fff97316);
            if (val <= 200) return ThemeColors.get(R.color.hc_ffef4444);
            return ThemeColors.get(R.color.hc_ff991b1b);
        } catch (NumberFormatException e) {
            return ThemeColors.get(R.color.hc_ff60a5fa);
        }
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

    /** 生成人性化天气说明文字（与天气横幅共用 WeatherSummaryUtil，保证两处文案完全一致） */
    private void updateWeatherSummary() {
        if (tvWeatherSummary == null) return;
        String summary = com.oilquiz.app.weather.WeatherSummaryUtil.generate(
                todayDayWeather, todayNightWeather, todayHighTemp, todayLowTemp,
                currentTempVal, currentFeelsLike, currentWeatherText,
                currentHumidity, currentUv, currentWindScale,
                currentVisibility, minutelySummary, alertSummary);
        if (summary != null) {
            tvWeatherSummary.setText(summary);
            tvWeatherSummary.setVisibility(View.VISIBLE);
        } else {
            tvWeatherSummary.setVisibility(View.GONE);
        }
        return;
        // ========== 以下为旧版拼接逻辑，已由共享生成器替代，不再执行 ==========
        /*
        if (!todayDayWeather.isEmpty() || !todayNightWeather.isEmpty()) {
            if (!todayDayWeather.isEmpty() && !todayNightWeather.isEmpty()) {
                if (todayDayWeather.equals(todayNightWeather)) {
                    sb.append(getString(R.string.h_274efdb9)).append(todayDayWeather);
                } else {
                    sb.append(getString(R.string.h_7929767e)).append(todayDayWeather)
                      .append(getString(R.string.h_c7ba693e)).append(todayNightWeather);
                }
            } else if (!todayDayWeather.isEmpty()) {
                sb.append(getString(R.string.h_7929767e)).append(todayDayWeather);
            } else {
                sb.append(getString(R.string.h_d7befd55)).append(todayNightWeather);
            }
            // 温差
            if (!todayHighTemp.equals("--") && !todayLowTemp.equals("--")) {
                try {
                    int high = Integer.parseInt(todayHighTemp);
                    int low = Integer.parseInt(todayLowTemp);
                    int diff = high - low;
                    sb.append("，").append(low).append("~").append(high).append("°");
                    if (diff >= 10) sb.append(getString(R.string.h_de66c340)).append(diff).append(getString(R.string.h_e9100b89));
                } catch (NumberFormatException ignored) {}
            }
            sb.append("。");
        }

        // ② 当前体感与能见度
        boolean hasTemp = !currentTempVal.isEmpty() && !currentTempVal.equals("--");
        boolean hasFeels = !currentFeelsLike.isEmpty() && !currentFeelsLike.equals("--");
        boolean hasVis = !currentVisibility.isEmpty() && !currentVisibility.equals("--");
        if (hasTemp || hasFeels || hasVis) {
            sb.append(getString(R.string.h_f8ad32ea));
            if (hasTemp) sb.append(currentTempVal).append("°");
            if (hasFeels && hasTemp) {
                try {
                    int diff = Integer.parseInt(currentFeelsLike) - Integer.parseInt(currentTempVal);
                    if (diff >= 3) sb.append(getString(R.string.h_1a902d11)).append(currentFeelsLike).append("°");
                    else if (diff <= -3) sb.append(getString(R.string.h_1f38c85b)).append(currentFeelsLike).append("°");
                } catch (NumberFormatException ignored) {}
            } else if (hasFeels) {
                sb.append(getString(R.string.h_7e868f04)).append(currentFeelsLike).append("°");
            }
            if (hasVis) {
                try {
                    double vis = Double.parseDouble(currentVisibility);
                    if (vis < 1) sb.append(getString(R.string.h_95ce7d95));
                    else if (vis < 5) sb.append(getString(R.string.h_8e057650)).append(currentVisibility).append("km)");
                    else if (vis < 10) sb.append(getString(R.string.h_ad4463d0)).append(currentVisibility).append("km)");
                    else sb.append(getString(R.string.h_3ded4e65)).append(currentVisibility).append("km)");
                } catch (NumberFormatException ignored) {}
            }
            sb.append("。");
        }

        // ③ 天气状况提醒
        if (!currentWeatherText.isEmpty() && !currentWeatherText.equals("--")) {
            boolean added = false;
            if (currentWeatherText.contains("暴雨") || currentWeatherText.contains("大暴雨")) {
                sb.append(getString(R.string.h_2d1065ec)); added = true;
            } else if (currentWeatherText.contains("大雨")) {
                sb.append(getString(R.string.h_4bd97296)); added = true;
            } else if (currentWeatherText.contains("雨")) {
                sb.append(getString(R.string.h_66649225)); added = true;
            } else if (currentWeatherText.contains("暴") && currentWeatherText.contains("雪")) {
                sb.append(getString(R.string.h_79978b01)); added = true;
            } else if (currentWeatherText.contains("雪")) {
                sb.append(getString(R.string.h_30483476)); added = true;
            } else if (currentWeatherText.contains("雾") || currentWeatherText.contains("霾")) {
                sb.append(getString(R.string.h_9b7ddd10)); added = true;
            } else if (currentWeatherText.contains("沙尘")) {
                sb.append(getString(R.string.h_27d31044)); added = true;
            }
            if (!added) {
                // 温度提醒
                if (hasTemp) {
                    try {
                        int temp = Integer.parseInt(currentTempVal);
                        if (temp <= 0) sb.append(getString(R.string.h_b001cecf));
                        else if (temp <= 5) sb.append(getString(R.string.h_f87126c4));
                        else if (temp >= 35) sb.append(getString(R.string.h_f9797d64));
                        else if (temp >= 30) sb.append(getString(R.string.h_f3300ec5));
                        else if (temp > 15 && temp < 30 && currentWeatherText.contains("晴")) {
                            sb.append(getString(R.string.h_a1f59fc0));
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        // ④ 湿度/紫外线/风力
        List<String> envs = new ArrayList<>();
        if (!currentHumidity.isEmpty() && !currentHumidity.equals("--")) {
            try {
                int h = Integer.parseInt(currentHumidity);
                if (h <= 30) envs.add("空气干燥注意补水");
                else if (h >= 80) envs.add("湿度高体感闷热");
            } catch (NumberFormatException ignored) {}
        }
        if (!currentUv.isEmpty() && !currentUv.equals("--")) {
            try {
                int uvVal = Integer.parseInt(currentUv);
                if (uvVal >= 8) envs.add("紫外线强务必防晒");
                else if (uvVal >= 5) envs.add("紫外线较强建议防晒");
            } catch (NumberFormatException ignored) {}
        }
        if (!currentWindScale.isEmpty() && !currentWindScale.equals("--")) {
            try {
                int wind = Integer.parseInt(currentWindScale);
                if (wind >= 8) envs.add("风力极大减少外出");
                else if (wind >= 6) envs.add("风力较大注意安全");
            } catch (NumberFormatException ignored) {}
        }
        if (!envs.isEmpty()) {
            sb.append(" ").append(String.join("，", envs)).append("。");
        }

        // ⑤ 降水预报
        if (!minutelySummary.isEmpty()) {
            sb.append(" ").append(minutelySummary).append("。");
        }

        // ⑥ 天气预警
        if (!alertSummary.isEmpty()) {
            sb.append(" ⚠").append(alertSummary).append("。");
        }

        if (sb.length() > 0) {
            tvWeatherSummary.setText(sb.toString().trim());
            tvWeatherSummary.setVisibility(View.VISIBLE);
        } else {
            tvWeatherSummary.setVisibility(View.GONE);
        }
        */
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

        String[] lines = minutelyText.split("\n");
        boolean inMinutelySection = false;
        String apiSummary = "";
        double totalPrecip = 0;
        String precipType = "";

        // 记录每个5分钟间隔的降水状态
        List<boolean[]> intervals = new ArrayList<>(); // {isRaining, hasData}
        List<String> timeLabels = new ArrayList<>();

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
            if (line.startsWith("摘要:")) {
                apiSummary = line.substring(3).trim();
                continue;
            }
            if (inMinutelySection && line.startsWith("未来")) continue;
            if (!inMinutelySection || line.isEmpty()) continue;

            int colonIdx = line.lastIndexOf(':');
            if (colonIdx > 0) {
                String timeStr = line.substring(0, colonIdx).trim();
                String precipPart = line.substring(colonIdx + 1).trim();
                if (precipPart.contains("雪")) precipType = "雪";
                else if (precipPart.contains("雨")) precipType = "雨";
                try {
                    String numStr = precipPart.replaceAll("[^0-9.]", "").trim();
                    if (!numStr.isEmpty()) {
                        double precip = Double.parseDouble(numStr);
                        intervals.add(new boolean[]{precip > 0, true});
                        timeLabels.add(timeStr);
                        if (precip > 0) totalPrecip += precip;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        // 分析降水段和间歇段
        int rainMinutes = 0;       // 降水持续总分钟
        int gapMinutes = 0;         // 间歇总分钟
        int rainSegments = 0;       // 降水段数
        int currentSegMinutes = 0;  // 当前降水段时长
        int currentGapMinutes = 0;  // 当前间歇段时长
        String rainStopTime = "";
        boolean inRain = false;
        boolean hasRained = false;

        for (int i = 0; i < intervals.size(); i++) {
            boolean isRaining = intervals.get(i)[0];
            if (isRaining) {
                if (!inRain) {
                    // 新降水段开始
                    rainSegments++;
                    inRain = true;
                    // 如果之前有间歇，累加
                    if (hasRained) {
                        gapMinutes += currentGapMinutes;
                    }
                    currentGapMinutes = 0;
                }
                currentSegMinutes += 5;
                rainMinutes += 5;
                hasRained = true;
            } else {
                if (inRain) {
                    // 降水段结束
                    inRain = false;
                    currentSegMinutes = 0;
                    currentGapMinutes = 5;
                    if (i < timeLabels.size()) rainStopTime = timeLabels.get(i);
                } else if (hasRained) {
                    currentGapMinutes += 5;
                }
            }
        }
        // 如果结束时仍在下雨，没有雨停时间
        if (inRain) rainStopTime = "";

        // 生成摘要
        String summary;
        String tip;
        if (rainMinutes == 0) {
            summary = apiSummary.isEmpty() ? "2小时内无降水" : apiSummary;
            tip = "放心出行吧";
        } else {
            String levelText = precipLevelText(totalPrecip, precipType);
            String typeText = precipType.isEmpty() ? "降水" : precipType;
            boolean intermittent = rainSegments > 1;

            StringBuilder sb = new StringBuilder();
            if (intermittent) {
                sb.append(String.format(getString(R.string.h_c63d2db4), typeText));
                sb.append(String.format(getString(R.string.h_a9f57b3a), rainMinutes));
                sb.append(String.format(getString(R.string.h_e535deb9), gapMinutes));
            } else {
                sb.append(String.format(getString(R.string.h_3080e75d), typeText, rainMinutes));
            }
            sb.append(String.format(getString(R.string.h_d494de13), totalPrecip, levelText));

            // 雨停信息
            if (!rainStopTime.isEmpty()) {
                sb.append("，").append(rainStopTime).append("停");
            } else {
                sb.append(getString(R.string.h_a2f58ffa));
            }

            summary = sb.toString();
            if (!apiSummary.isEmpty()) {
                summary = apiSummary + "，" + summary;
            }
            tip = precipTipText(totalPrecip, precipType);
        }

        if (tvPrecipHours != null) {
            tvPrecipHours.setText(summary);
        }
        if (tvPrecipTip != null) {
            tvPrecipTip.setText(tip);
        }
        minutelySummary = rainMinutes == 0 ? "" : summary;
        updateWeatherSummary();
    }

    /** 2小时降水量等级文字 */
    private static String precipLevelText(double mm, String type) {
        if ("雪".equals(type)) {
            if (mm < 1) return "小雪";
            if (mm < 3) return "中雪";
            if (mm < 5) return "大雪";
            if (mm < 10) return "暴雪";
            return "大暴雪";
        }
        // 2小时累计降水量等级
        if (mm < 0.1) return "微量";
        if (mm < 4) return "小雨";
        if (mm < 12) return "中雨";
        if (mm < 25) return "大雨";
        if (mm < 50) return "暴雨";
        if (mm < 100) return "大暴雨";
        return "特大暴雨";
    }

    /** 2小时降水出行提示 */
    private static String precipTipText(double mm, String type) {
        if ("雪".equals(type)) {
            if (mm < 1) return "小雪纷飞，注意路滑";
            if (mm < 3) return "中雪，出行注意防滑";
            if (mm < 5) return "大雪，减少外出";
            return "暴雪天气，尽量不出门";
        }
        if (mm < 4) return "小雨绵绵，建议带伞";
        if (mm < 12) return "中雨，携带雨具出行";
        if (mm < 25) return "大雨倾盆，注意防范";
        if (mm < 50) return "暴雨天气，减少外出";
        return "极端降水，避免外出";
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
        String weatherDesc;
        String temp;
        String pop;
        boolean isNow;

        HourlyItem(String t, String ic, String wd, String tmp, String pop, boolean now) {
            time = t; iconCode = ic; weatherDesc = wd; temp = tmp; this.pop = pop; isNow = now;
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
            holder.tvWeatherDesc.setText(item.weatherDesc);
            holder.tvTemp.setText(item.temp);
            holder.itemView.setSelected(item.isNow);

            // 降水概率：仅在 >0% 时显示
            if (holder.tvPop != null) {
                if (item.pop != null && !item.pop.isEmpty() && !item.pop.equals("0%")) {
                    holder.tvPop.setText("💧" + item.pop);
                    holder.tvPop.setVisibility(View.VISIBLE);
                } else {
                    holder.tvPop.setVisibility(View.GONE);
                }
            }

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
            TextView tvWeatherDesc;
            TextView tvTemp;
            TextView tvPop;

            HourlyViewHolder(View itemView) {
                super(itemView);
                tvTime = itemView.findViewById(R.id.tv_hourly_time);
                tvIcon = itemView.findViewById(R.id.tv_hourly_icon);
                tvWeatherDesc = itemView.findViewById(R.id.tv_hourly_temp_level);
                tvTemp = itemView.findViewById(R.id.tv_hourly_temp);
                tvPop = itemView.findViewById(R.id.tv_hourly_pop);
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
