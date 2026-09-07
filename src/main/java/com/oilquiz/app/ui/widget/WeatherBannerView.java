package com.oilquiz.app.ui.widget;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.location.Address;
import android.location.Geocoder;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.LocationTool;
import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.PermissionResourceProvider;
import com.oilquiz.app.util.QWeatherIconFont;
import com.oilquiz.app.weather.WeatherService;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WeatherBannerView extends LinearLayout {

    private static final String TAG = "WeatherBannerView";
    private static final long MIN_REFRESH_INTERVAL_MS = 5 * 60 * 1000;
    private static final String PREFS_NAME = "weather_location_cache";
    private static final String KEY_LAT = "cached_lat";
    private static final String KEY_LON = "cached_lon";
    private static final String KEY_CITY = "cached_city";
    private static final String KEY_TIMESTAMP = "cached_timestamp";
    private static final long LOCATION_CACHE_DURATION = 30 * 60 * 1000;

    /** 广播Action：WeatherDetailActivity 解析到具体地址（区+路）时发送，通知所有 Banner 实例立即刷新 */
    public static final String ACTION_LOCATION_UPDATED
            = "com.oilquiz.app.action.WEATHER_LOCATION_UPDATED";
    public static final String EXTRA_CITY = "city";
    public static final String EXTRA_LAT  = "lat";
    public static final String EXTRA_LON  = "lon";

    private TextView weatherIcon;
    private TextView weatherCity;
    private TextView weatherTemp;
    private TextView weatherDesc;
    private ImageView weatherArrow;
    private ImageView weatherLocateBtn;
    private View weatherBanner;
    // 天气详情总介绍（一句话汇总，与详情页天气介绍同数据源）
    private TextView weatherSummary;

    private WeatherService weatherService;
    private String currentCity = "";
    private boolean autoLoad = true;
    private boolean locationPermissionGranted = false;
    private double cachedLat = 0;
    private double cachedLon = 0;
    private String cachedFxLink = "";
    private long lastRefreshTime = 0;
    private String cachedAddress = "";
    /** 今日预报文本（用于拼接与详情页一致的完整总介绍，3小时缓存） */
    private String forecastText = null;
    /** 具体地址（区+路），总介绍末尾补充显示；无则为空 */
    private String detailAddress = "";

    private android.content.BroadcastReceiver locationUpdateReceiver;

    /**
     * 天气数据更新监听器（仿 AIService 状态观察者实时推送）：
     * WeatherService 网络拉取成功即通知，横幅收到后立即刷新显示最新数据。
     * 仅处理与当前横幅位置/城市匹配的数据（避免其它城市刷新干扰本横幅）。
     */
    private final WeatherService.WeatherUpdateListener weatherUpdateListener
            = (city, lat, lon, weatherText) -> {
        if (weatherText == null || weatherText.isEmpty()) return;
        try {
            boolean match = false;
            if (lat != 0 && lon != 0 && cachedLat != 0 && cachedLon != 0) {
                match = Math.abs(lat - cachedLat) < 0.5 && Math.abs(lon - cachedLon) < 0.5;
            } else if (city != null && !city.isEmpty()
                    && currentCity != null && !currentCity.isEmpty()) {
                match = city.equals(currentCity);
            }
            if (!match) return;
            // 新数据到达：预报文本失效重拉（拼总介绍用最新预报），直接更新 UI
            forecastText = null;
            lastRefreshTime = System.currentTimeMillis();
            updateUIWithForecast(weatherText);
            Log.i(TAG, "收到天气数据更新推送，横幅已刷新: " + city + " (" + lat + "," + lon + ")");
        } catch (Exception e) {
            Log.w(TAG, "天气更新推送处理失败: " + e.getMessage());
        }
    };

    /** 周期刷新定时器：主界面停留时每 MIN_REFRESH_INTERVAL_MS 自动拉最新天气（动态数据） */
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable periodicRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            periodicRefresh();
            // 重新排下一次（只有 onResume/startPeriodicRefresh 后保持运行，onPause 停止）
            refreshHandler.postDelayed(this, MIN_REFRESH_INTERVAL_MS);
        }
    };
    private boolean periodicRefreshRunning = false;

    /**
     * 统一入口：写入 SP 缓存 + 双发广播（全局+本地）确保所有监听器都能收到。
     *   - WeatherDetailActivity（GPS定位成功后）：调用此方法同步具体地址给横幅
     *   - WeatherBannerView 自己定位成功后：也通过 saveCachedLocation → 调用此方法，保证写入时间戳一致
     */
    public static void updateSharedLocationCacheAndNotify(Context context,
                                                          String city, double lat, double lon) {
        if (context == null) return;
        if (city == null || city.isEmpty()) return;
        final long now = System.currentTimeMillis();
        // 1. 写入 SP（Banner 下次初始化 / onResume 时间戳对比时会读到）
        try {
            android.content.SharedPreferences prefs
                    = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putLong(KEY_LAT, Double.doubleToRawLongBits(lat))
                    .putLong(KEY_LON, Double.doubleToRawLongBits(lon))
                    .putString(KEY_CITY, city)
                    .putLong(KEY_TIMESTAMP, now)
                    .apply();
        } catch (Exception e) {
            android.util.Log.w(TAG, "Failed to write shared location cache: " + e.getMessage());
        }
        // 2. 双发广播：全局（动态 registerReceiver）+ LocalBroadcastManager（本地）
        //    两种都发，避免注册方式不一致导致收不到
        try {
            android.content.Intent intent = new android.content.Intent(ACTION_LOCATION_UPDATED);
            intent.setPackage(context.getPackageName());
            intent.putExtra(EXTRA_CITY, city);
            intent.putExtra(EXTRA_LAT, lat);
            intent.putExtra(EXTRA_LON, lon);
            // 全局广播（给 Context.registerReceiver 动态注册的接收器）
            try { context.sendBroadcast(intent); }
            catch (Exception ignored) {}
            // 本地广播（给 LocalBroadcastManager 注册的接收器）
            try {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context)
                        .sendBroadcast(intent);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            android.util.Log.w(TAG, "Failed to broadcast location: " + e.getMessage());
        }
    }

    public WeatherBannerView(Context context) {
        super(context);
        init(null);
    }

    public WeatherBannerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public WeatherBannerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(AttributeSet attrs) {
        LayoutInflater.from(getContext()).inflate(R.layout.widget_weather_banner, this, true);

        weatherIcon = findViewById(R.id.weather_icon);
        weatherCity = findViewById(R.id.weather_city);
        weatherTemp = findViewById(R.id.weather_temp);
        weatherDesc = findViewById(R.id.weather_desc);
        weatherArrow = findViewById(R.id.weather_arrow);
        weatherLocateBtn = findViewById(R.id.weather_locate_btn);
        weatherBanner = findViewById(R.id.weather_banner);
        // 天气详情总介绍
        weatherSummary = findViewById(R.id.weather_summary);
        // 自动跑马灯滚动：总结内容较长时循环滚动展示，无需手动滑动
        if (weatherSummary != null) {
            weatherSummary.setMarqueeRepeatLimit(-1); // 无限循环
            weatherSummary.setSelected(true);          // 触发 marquee 滚动
        }

        // 让所有子 View 都响应点击（点击横幅任意位置都跳转详情页）。
        // 每个子 View 自带 OnClick 监听：事件不会被内部控件吞掉导致"点了没反应"；
        // 滑动由系统 slop 判定不触发点击，父容器滚动不受影响。
        bindClickToAllChildren(this);

        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> onBannerClicked());

        // 获取定位按钮：未授予定位权限时显示，点击主动请求定位（需在 bindClickToAllChildren 之后设置，
        // 覆盖"点横幅跳详情页"的默认监听，让该按钮单独响应定位请求）
        if (weatherLocateBtn != null) {
            weatherLocateBtn.setOnClickListener(v -> requestLocationPermissionFromBanner());
            updateLocateButtonVisibility();
        }

        Typeface iconTypeface = QWeatherIconFont.getTypeface(getContext());
        if (weatherIcon != null) {
            weatherIcon.setTypeface(iconTypeface);
            weatherIcon.setText(QWeatherIconFont.getIcon("999"));
        }

        weatherService = WeatherService.getInstance(getContext());

        // 注册天气数据更新监听：WeatherService 网络拉取成功后实时推送刷新（仿模型状态 observer 模式）
        try {
            weatherService.registerWeatherUpdateListener(weatherUpdateListener);
        } catch (Exception e) {
            Log.w(TAG, "注册天气更新监听失败: " + e.getMessage());
        }

        if (attrs != null) {
            TypedArray ta = getContext().obtainStyledAttributes(attrs, R.styleable.WeatherBannerView);
            autoLoad = ta.getBoolean(R.styleable.WeatherBannerView_autoLoad, true);
            String city = ta.getString(R.styleable.WeatherBannerView_defaultCity);
            if (city != null && !city.isEmpty()) {
                currentCity = city;
            }
            ta.recycle();
        }

        if (autoLoad) {
            if (!loadFromCachedLocation()) {
                requestLocationAndLoad();
            }
        }

        // 注册位置变更广播：详情页解析到具体地址后会发通知，立即刷新
        try {
            locationUpdateReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, android.content.Intent intent) {
                    if (intent == null) return;
                    String city = intent.getStringExtra(EXTRA_CITY);
                    double lat  = intent.getDoubleExtra(EXTRA_LAT, 0);
                    double lon  = intent.getDoubleExtra(EXTRA_LON, 0);
                    if (city == null || city.isEmpty()) return;
                    Log.i(TAG, "收到位置更新广播: " + city + " (" + lat + "," + lon + ")");
                    // 避免重复处理：如果城市名和坐标都没变，跳过
                    if (city.equals(currentCity) && lat == cachedLat && lon == cachedLon) {
                        return;
                    }
                    currentCity = city;
                    cachedLat = lat;
                    cachedLon = lon;
                    // 注意：此处只写 SP 缓存，不再调用 saveCachedLocation（会发广播导致死循环）
                    try {
                        android.content.SharedPreferences prefs
                                = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        prefs.edit()
                                .putLong(KEY_LAT, Double.doubleToRawLongBits(lat))
                                .putLong(KEY_LON, Double.doubleToRawLongBits(lon))
                                .putString(KEY_CITY, city)
                                .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                                .apply();
                    } catch (Exception ignored) {}
                    lastRefreshTime = System.currentTimeMillis();
                    // 立即显示新地址，并用新坐标请求天气（如坐标=0则用城市名兜底）
                    post(() -> {
                        if (lat != 0 && lon != 0) {
                            loadWeatherByLocationDirect(lat, lon, city);
                        } else {
                            loadWeatherWithCity(city);
                        }
                    });
                }
            };
            android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_LOCATION_UPDATED);
            // 同时兼容全局广播和 LocalBroadcastManager
            try {
                getContext().registerReceiver(locationUpdateReceiver, filter);
            } catch (Exception ignored) {}
            try {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(getContext())
                        .registerReceiver(locationUpdateReceiver, filter);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.w(TAG, "Failed to register location receiver: " + e.getMessage());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 停止周期定时刷新，避免 View 销毁后定时器空跑/泄漏
        refreshHandler.removeCallbacks(periodicRefreshRunnable);
        periodicRefreshRunning = false;
        // 注销天气数据更新监听
        try {
            if (weatherService != null) {
                weatherService.unregisterWeatherUpdateListener(weatherUpdateListener);
            }
        } catch (Exception ignored) {
        }
        if (locationUpdateReceiver != null) {
            try {
                getContext().unregisterReceiver(locationUpdateReceiver);
            } catch (Exception ignored) {}
            try {
                androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(getContext())
                        .unregisterReceiver(locationUpdateReceiver);
            } catch (Exception ignored) {}
            locationUpdateReceiver = null;
        }
    }

    private void navigateToWeatherDetail() {
        String city = currentCity;
        if (weatherCity != null) {
            String displayCity = weatherCity.getText().toString();
            if (displayCity != null && !displayCity.isEmpty() 
                && !displayCity.equals("定位中...") 
                && !displayCity.equals("未知")) {
                city = displayCity;
            }
        }

        Log.d(TAG, "navigateToWeatherDetail: city=" + city + ", lat=" + cachedLat + ", lon=" + cachedLon);
        
        try {
            android.content.Intent intent = new android.content.Intent(getContext(),
                    com.oilquiz.app.ui.activity.WeatherDetailActivity.class);
            intent.putExtra("city", city);
            if (cachedLat != 0 && cachedLon != 0) {
                intent.putExtra("lat", cachedLat);
                intent.putExtra("lon", cachedLon);
            }
            getContext().startActivity(intent);
            Log.d(TAG, "WeatherDetailActivity started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start WeatherDetailActivity", e);
            // 跳转失败时给出提示，避免"点击无反应"的假象
            try {
                android.widget.Toast.makeText(getContext(),
                        "打开天气详情失败: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        }
    }

    public void onResume() {
        long now = System.currentTimeMillis();

        // 刷新获取定位按钮显隐（用户可能已在设置/详情页授权或拒绝）
        updateLocateButtonVisibility();

        // ================================================================
        // 每次 onResume 都检测 SP 缓存是否比自己的 lastRefreshTime 更新
        // （详情页 asyncResolveFullAddress 成功后会写入 SP 并发广播）
        // 如果缓存更新 → 立即重刷（解决返回主页面后横幅显示旧名字的问题）
        // ================================================================
        try {
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long cacheTs = prefs.getLong(KEY_TIMESTAMP, 0);
            if (cacheTs > lastRefreshTime) {
                Log.i(TAG, "onResume 检测到 SP 位置缓存更新: cacheTs=" + cacheTs
                        + " > lastRefreshTime=" + lastRefreshTime + "，立即刷新");
                if (loadFromCachedLocation()) {
                    lastRefreshTime = cacheTs;
                    startPeriodicRefresh();
                    return;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "onResume 检查SP缓存失败: " + e.getMessage());
        }

        // 5 分钟超时兜底：超过 5 分钟才重新请求网络/重新定位
        if (lastRefreshTime == 0 || (now - lastRefreshTime) > MIN_REFRESH_INTERVAL_MS) {
            if (loadFromCachedLocation()) {
                startPeriodicRefresh();
                return;
            }
            requestLocationAndLoad();
        }
        // 无论是否触发刷新，都启动周期定时刷新（停留页面时数据持续动态更新）
        startPeriodicRefresh();
    }

    /**
     * 页面暂停：停止周期定时刷新（避免后台空跑网络请求）。
     */
    public void onPause() {
        refreshHandler.removeCallbacks(periodicRefreshRunnable);
        periodicRefreshRunning = false;
    }

    /** 启动周期刷新（幂等：重复调用只重置计时，不叠加任务） */
    private void startPeriodicRefresh() {
        if (periodicRefreshRunning) return;
        periodicRefreshRunning = true;
        refreshHandler.removeCallbacks(periodicRefreshRunnable);
        refreshHandler.postDelayed(periodicRefreshRunnable, MIN_REFRESH_INTERVAL_MS);
        Log.d(TAG, "周期刷新已启动（每 " + (MIN_REFRESH_INTERVAL_MS / 1000) + "s 自动拉取最新天气）");
    }

    /**
     * 周期刷新：清天气缓存后重新拉取（保证看到最新实时数据），
     * 位置优先复用缓存坐标（省去重新定位），位置缓存过期时才重新定位。
     */
    private void periodicRefresh() {
        if (!isNetworkAvailable()) return;
        Log.i(TAG, "周期刷新触发，重新拉取最新天气");
        // 读取位置：优先内存坐标，其次 SP 位置缓存
        double lat = cachedLat, lon = cachedLon;
        String city = currentCity;
        if (lat == 0 && lon == 0) {
            try {
                SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                long ts = prefs.getLong(KEY_TIMESTAMP, 0);
                if (ts != 0 && System.currentTimeMillis() - ts <= LOCATION_CACHE_DURATION) {
                    lat = Double.longBitsToDouble(prefs.getLong(KEY_LAT, 0));
                    lon = Double.longBitsToDouble(prefs.getLong(KEY_LON, 0));
                    city = prefs.getString(KEY_CITY, "");
                }
            } catch (Exception ignored) {}
        }
        if (lat != 0 && lon != 0) {
            // 强制清天气缓存，确保拉到最新实时数据（而非 5 分钟缓存）
            weatherService.clearCacheForLocation(lat, lon);
            // 预报文本一并失效，重新拉取拼总介绍
            forecastText = null;
            if (cachedLat == 0 && cachedLon == 0) {
                cachedLat = lat;
                cachedLon = lon;
            }
            if (currentCity == null || currentCity.isEmpty()) {
                currentCity = city;
            }
            loadWeatherByLocationDirect(lat, lon, currentCity);
            return;
        }
        // 无缓存位置：重新定位并加载
        requestLocationAndLoad();
    }

    private boolean loadFromCachedLocation() {
        try {
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long timestamp = prefs.getLong(KEY_TIMESTAMP, 0);
            if (timestamp == 0) return false;

            long age = System.currentTimeMillis() - timestamp;
            if (age > LOCATION_CACHE_DURATION) {
                Log.i(TAG, "Location cache expired");
                return false;
            }

            double lat = Double.longBitsToDouble(prefs.getLong(KEY_LAT, 0));
            double lon = Double.longBitsToDouble(prefs.getLong(KEY_LON, 0));
            String city = prefs.getString(KEY_CITY, "");

            if (lat == 0 && lon == 0) return false;

            Log.i(TAG, "Using cached location: " + city + " (" + lat + "," + lon + ")");
            currentCity = city;
            cachedLat = lat;
            cachedLon = lon;
            loadWeatherByLocationDirect(lat, lon, city);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Error reading cached location: " + e.getMessage());
            return false;
        }
    }

    private void loadWeatherByLocationDirect(double lat, double lon, String cityName) {
        if (!isNetworkAvailable()) {
            if (weatherDesc != null) weatherDesc.setText("无网络连接");
            if (weatherCity != null) weatherCity.setText(cityName);
            return;
        }

        if (weatherCity != null) weatherCity.setText(cityName);
        if (weatherTemp != null) weatherTemp.setText("--°");
        if (weatherDesc != null) weatherDesc.setText("正在获取天气...");

        weatherService.getCurrentWeatherByLocation(lat, lon, cityName).thenAccept(weather -> {
            lastRefreshTime = System.currentTimeMillis();
            post(() -> updateUIWithForecast(weather));
        }).exceptionally(e -> {
            Log.e(TAG, "Failed to load weather by location: " + e.getMessage(), e);
            post(() -> loadWeatherWithCity(cityName));
            return null;
        });
    }

    /**
     * 反解析坐标：返回城市名（locality）；无城市名时退化为具体地址。
     * 天气横幅顶部显示城市名（如"北京"），具体地址由 resolveDetailAddress 单独取。
     */
    private String getCityNameFromGeocoder(double lat, double lon) {
        String[] pair = resolveGeocoder(lat, lon);
        if (pair == null) return null;
        String city = pair[0];
        if (city == null || city.isEmpty()) city = pair[1];
        return city;
    }

    /** 反解析坐标：返回具体地址（区+路），用于总介绍末尾补充 */
    private String resolveDetailAddress(double lat, double lon) {
        String[] pair = resolveGeocoder(lat, lon);
        return pair != null ? pair[1] : null;
    }

    /**
     * 反解析坐标 → [城市名, 具体地址]。
     * 城市名=locality（北京）；具体地址=区+路 > 路+地标 > 区 > 路。
     */
    private String[] resolveGeocoder(double lat, double lon) {
        if (!Geocoder.isPresent()) return new String[]{null, null};
        try {
            Geocoder geocoder = new Geocoder(getContext(), Locale.CHINA);
            List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
            if (addresses == null || addresses.isEmpty()) return new String[]{null, null};
            Address a = addresses.get(0);
            String city = a.getLocality();                  // 市
            String district = a.getSubLocality();           // 区/县
            String road = a.getThoroughfare();              // 街道/路名
            String feature = a.getFeatureName();            // 地标/门牌
            String detail = null;
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
            return new String[]{city, detail};
        } catch (Exception e) {
            Log.w(TAG, "Geocoder failed: " + e.getMessage());
        }
        return new String[]{null, null};
    }

    /**
     * 保存位置缓存：复用统一入口 updateSharedLocationCacheAndNotify，
     * 保证 SP 写入时间戳格式、广播发送完全一致，避免和详情页同步时不一致。
     */
    private void saveCachedLocation(double lat, double lon, String city) {
        try {
            Log.i(TAG, "saveCachedLocation -> updateSharedLocationCacheAndNotify: "
                    + city + " (" + lat + "," + lon + ")");
            updateSharedLocationCacheAndNotify(getContext(), city, lat, lon);
        } catch (Exception e) {
            Log.w(TAG, "Error saving cached location: " + e.getMessage());
        }
    }

    public void forceRefresh() {
        requestLocationAndRefresh();
    }

    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    public String getCachedAddress() {
        return cachedAddress;
    }

    public void loadWeatherByCityName(String cityName) {
        if (cityName == null || cityName.trim().isEmpty()) return;
        currentCity = cityName.trim();
        cachedLat = 0;
        cachedLon = 0;
        loadWeatherWithCity(currentCity);
    }

    public void onBannerClicked() {
        Log.d(TAG, "onBannerClicked() called, onBannerClickedListener=" + onBannerClickedListener);
        if (onBannerClickedListener != null) {
            String city = currentCity;
            if (weatherCity != null) {
                String displayCity = weatherCity.getText().toString();
                if (displayCity != null && !displayCity.isEmpty() 
                    && !displayCity.equals("定位中...") 
                    && !displayCity.equals("未知")) {
                    city = displayCity;
                }
            }
            Log.d(TAG, "Calling listener with city: " + city);
            onBannerClickedListener.onBannerClicked(city);
        } else {
            Log.d(TAG, "No listener, navigating to weather detail");
            navigateToWeatherDetail();
        }
    }

    /**
     * 获取定位按钮显隐：未授予定位权限时显示（提示可点击定位），有权限时隐藏。
     * 无权限时横幅用默认城市独立更新天气，用户可点击此按钮主动升级为定位天气。
     */
    private void updateLocateButtonVisibility() {
        if (weatherLocateBtn == null) return;
        boolean has = AppResourceManager.getInstance(getContext()).hasLocationPermission();
        weatherLocateBtn.setVisibility(has ? View.GONE : View.VISIBLE);
    }

    /** 用户点击"获取定位"：主动请求定位权限，授权后立即独立定位并更新天气 */
    private void requestLocationPermissionFromBanner() {
        Activity activity = tryGetActivity();
        if (activity == null) return;
        if (weatherDesc != null) weatherDesc.setText("正在请求定位权限...");
        AppResourceManager.getInstance(getContext()).permissions()
                .requestLocationPermission(activity, new PermissionResourceProvider.PermissionCallback() {
                    @Override
                    public void onGranted() {
                        locationPermissionGranted = true;
                        post(() -> {
                            updateLocateButtonVisibility();
                            locateAndLoadWeather();
                        });
                    }

                    @Override
                    public void onDenied(List<String> deniedPermissions) {
                        post(() -> {
                            updateLocateButtonVisibility();
                            if (weatherDesc != null) weatherDesc.setText("定位权限被拒绝，点击右侧图标重试");
                        });
                    }
                });
    }

    public void requestLocationAndLoad() {
        AppResourceManager resources = AppResourceManager.getInstance(getContext());
        if (resources.hasLocationPermission()) {
            locationPermissionGranted = true;
            locateAndLoadWeather();
            return;
        }

        // 无定位权限：不弹权限框。
        // 横幅保持独立更新——用布局配置的默认城市（currentCity，如"银川"）直接拉取天气，
        // 并在周期刷新时持续独立刷新，完全不依赖天气详情页先获取数据。
        locationPermissionGranted = false;
        post(() -> {
            if (currentCity != null && !currentCity.isEmpty()) {
                loadWeatherWithCity(currentCity);
            } else {
                if (weatherCity != null) weatherCity.setText("未定位");
                if (weatherDesc != null) weatherDesc.setText("点击开启定位天气");
                if (weatherTemp != null) weatherTemp.setText("--°");
            }
        });
    }

    private void requestLocationAndRefresh() {
        AppResourceManager resources = AppResourceManager.getInstance(getContext());
        if (resources.hasLocationPermission()) {
            locationPermissionGranted = true;
            locateAndRefreshWeather();
            return;
        }

        // 与 requestLocationAndLoad 一致：无权限时不弹权限框，用默认城市独立刷新
        locationPermissionGranted = false;
        post(() -> {
            if (currentCity != null && !currentCity.isEmpty()) {
                refreshWeatherWithCity(currentCity);
            } else {
                if (weatherCity != null) weatherCity.setText("未定位");
                if (weatherDesc != null) weatherDesc.setText("点击开启定位天气");
                if (weatherTemp != null) weatherTemp.setText("--°");
            }
        });
    }

    private void locateAndLoadWeather() {
        if (!isNetworkAvailable()) {
            post(() -> {
                if (weatherDesc != null) weatherDesc.setText("无网络连接");
                if (weatherCity != null) weatherCity.setText(currentCity);
            });
            return;
        }

        // 快速显示加载状态，同时后台更新位置
        if (weatherCity != null) weatherCity.setText("定位中...");
        if (weatherDesc != null) weatherDesc.setText("定位中，正在更新...");

        // 后台尝试获取精确位置
        new Thread(() -> {
            try {
                LocationTool locationTool = new LocationTool(getContext());
                LocationTool.SmartLocationResult result = locationTool.getSmartLocation();

                if (result.success && result.location != null) {
                    double lat = result.location.latitude;
                    double lon = result.location.longitude;

                    // 如果位置变化不大，就不重新加载
                    if (cachedLat != 0 && cachedLon != 0) {
                        float[] results = new float[1];
                        android.location.Location.distanceBetween(cachedLat, cachedLon, lat, lon, results);
                        if (results[0] < 500) { // 500米内不更新
                            return;
                        }
                    }

                    cachedLat = lat;
                    cachedLon = lon;

                    String cityName = getCityNameFromGeocoder(lat, lon);
                    if (cityName == null || cityName.isEmpty()) {
                        cityName = currentCity.isEmpty() ? "未知位置" : currentCity;
                    }
                    currentCity = cityName;
                    cachedAddress = cityName;
                    detailAddress = resolveDetailAddress(lat, lon);

                    saveCachedLocation(lat, lon, cityName);

                    final String finalCity = cityName;
                    post(() -> {
                        if (weatherCity != null) weatherCity.setText(finalCity);
                        loadWeatherByLocationDirect(lat, lon, finalCity);
                    });
                } else {
                    // GPS失败，显示提示
                    post(() -> {
                        if (weatherCity != null) weatherCity.setText("未定位");
                        if (weatherDesc != null) weatherDesc.setText("定位失败，点击重试");
                        if (weatherTemp != null) weatherTemp.setText("--°");
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "Background location update failed: " + e.getMessage());
                post(() -> {
                    if (weatherCity != null) weatherCity.setText("未定位");
                    if (weatherDesc != null) weatherDesc.setText("定位失败，点击重试");
                    if (weatherTemp != null) weatherTemp.setText("--°");
                });
            }
        }, "LocationUpdate").start();
    }

    private void locateAndRefreshWeather() {
        if (!isNetworkAvailable()) {
            post(() -> {
                if (weatherDesc != null) weatherDesc.setText("无网络连接");
                if (weatherCity != null) weatherCity.setText(currentCity);
            });
            return;
        }

        if (weatherCity != null) weatherCity.setText("定位中...");
        if (weatherTemp != null) weatherTemp.setText("--°");
        if (weatherDesc != null) weatherDesc.setText("正在定位...");

        new Thread(() -> {
            try {
                LocationTool locationTool = new LocationTool(getContext());
                LocationTool.SmartLocationResult result = locationTool.getSmartLocation();

                if (result.success && result.location != null) {
                    double lat = result.location.latitude;
                    double lon = result.location.longitude;
                    cachedLat = lat;
                    cachedLon = lon;

                    String cityName = getCityNameFromGeocoder(lat, lon);
                    if (cityName == null || cityName.isEmpty()) {
                        cityName = currentCity.isEmpty() ? "未知位置" : currentCity;
                    }
                    currentCity = cityName;
                    cachedAddress = cityName;
                    detailAddress = resolveDetailAddress(lat, lon);

                    saveCachedLocation(lat, lon, cityName);

                    weatherService.clearCacheForLocation(lat, lon);
                    final String finalCity = cityName;
                    post(() -> loadWeatherByLocationDirect(lat, lon, finalCity));
                    return;
                }
                Log.w(TAG, "Refresh location returned no valid coordinates");
            } catch (Exception e) {
                Log.e(TAG, "Refresh location failed: " + e.getMessage(), e);
            }

            // 定位失败，显示错误状态而不回退到默认城市
            post(() -> {
                if (weatherCity != null) weatherCity.setText("未定位");
                if (weatherDesc != null) weatherDesc.setText("定位失败，点击重试");
                if (weatherTemp != null) weatherTemp.setText("--°");
            });
        }).start();
    }

    private Activity tryGetActivity() {
        Context ctx = getContext();
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private void loadWeatherWithCity(String city) {
        if (!isNetworkAvailable()) {
            if (weatherDesc != null) weatherDesc.setText("无网络连接");
            if (weatherCity != null) weatherCity.setText(city);
            return;
        }

        if (weatherCity != null) weatherCity.setText(city);
        if (weatherTemp != null) weatherTemp.setText("--°");
        if (weatherDesc != null) weatherDesc.setText("正在获取天气...");

        weatherService.getCurrentWeather(city).thenAccept(weather -> {
            lastRefreshTime = System.currentTimeMillis();
            post(() -> updateUIWithForecast(weather));
        }).exceptionally(e -> {
            Log.e(TAG, "Failed to load weather for city " + city + ": " + e.getMessage(), e);
            post(() -> {
                if (weatherDesc != null) weatherDesc.setText("获取失败，点击重试");
            });
            return null;
        });
    }

    private void refreshWeatherWithCity(String city) {
        if (!isNetworkAvailable()) {
            if (weatherDesc != null) weatherDesc.setText("无网络连接");
            if (weatherCity != null) weatherCity.setText(city);
            return;
        }

        if (weatherCity != null) weatherCity.setText(city);
        if (weatherTemp != null) weatherTemp.setText("--°");
        if (weatherDesc != null) weatherDesc.setText("正在刷新...");

        weatherService.clearCacheForCity(city);
        weatherService.getCurrentWeather(city).thenAccept(weather -> {
            lastRefreshTime = System.currentTimeMillis();
            post(() -> updateUIWithForecast(weather));
        }).exceptionally(e -> {
            Log.e(TAG, "Failed to refresh weather for city " + city + ": " + e.getMessage(), e);
            post(() -> {
                if (weatherDesc != null) weatherDesc.setText("刷新失败，点击重试");
            });
            return null;
        });
    }

    /** 更新当前天气 + 异步拉取今日预报/降水/预警拼完整总介绍（预报 3h、降水 5min、预警 10min 缓存，不阻塞） */
    private void updateUIWithForecast(String weatherText) {
        if (forecastText == null) {
            try {
                if (cachedLat != 0 && cachedLon != 0) {
                    weatherService.getForecastByLocation(cachedLat, cachedLon).thenAccept(fc -> {
                        forecastText = fc;
                        post(() -> updateUI(weatherText));
                    }).exceptionally(e -> {
                        post(() -> updateUI(weatherText));
                        return null;
                    });
                    return;
                }
                weatherService.getForecast(currentCity).thenAccept(fc -> {
                    forecastText = fc;
                    post(() -> updateUI(weatherText));
                }).exceptionally(e -> {
                    post(() -> updateUI(weatherText));
                    return null;
                });
                return;
            } catch (Exception e) {
                Log.w(TAG, "加载今日预报失败(不影响当前天气显示): " + e.getMessage());
            }
        }
        updateUI(weatherText);
    }

    private void updateUI(String weatherText) {
        WeatherBannerManager.WeatherInfo info = parseWeather(weatherText);

        if (info.fxLink != null && !info.fxLink.isEmpty()) {
            cachedFxLink = info.fxLink;
        }

        if (weatherIcon != null) {
            String iconCode = info.iconCode != null && !info.iconCode.isEmpty() ? info.iconCode : "999";
            weatherIcon.setText(QWeatherIconFont.getIcon(iconCode));
        }
        if (weatherCity != null) {
            String displayCity = info.city;
            if (displayCity == null || displayCity.isEmpty() || displayCity.equals("未知")) {
                displayCity = currentCity;
            } else {
                currentCity = displayCity;
            }
            weatherCity.setText(truncateCityName(displayCity));
        }
        if (weatherTemp != null) {
            String t = (info.temp == null || info.temp.isEmpty()) ? "--" : info.temp;
            weatherTemp.setText(t + "°");
        }
        if (weatherDesc != null) {
            String desc = info.description;
            if (desc == null || desc.isEmpty()) desc = "暂无数据";
            weatherDesc.setText(desc);
        }

        // ========== 天气详情总介绍：完整一句话（今日预报段 + 当前实时段 + 降水 + 预警，与详情页完全一致）==========
        if (weatherSummary != null) {
            updateSummaryAsync(info);
        }
    }

    /**
     * 异步生成完整总介绍（与详情页同款）：并行拉取今日预报(已有) + 分钟级降水 + 天气预警，
     * 全部就绪后调用 WeatherSummaryUtil.generate 生成完整一句话（含降水/预警段）。
     */
    private void updateSummaryAsync(final WeatherBannerManager.WeatherInfo info) {
        try {
            java.util.concurrent.CompletableFuture<String> minutelyF;
            java.util.concurrent.CompletableFuture<String> alertsF;
            if (cachedLat != 0 && cachedLon != 0) {
                minutelyF = weatherService.getMinutelyByLocation(cachedLat, cachedLon);
                alertsF = weatherService.getAlertsByLocation(cachedLat, cachedLon);
            } else {
                minutelyF = weatherService.getMinutely(currentCity);
                alertsF = weatherService.getAlerts(currentCity);
            }
            java.util.concurrent.CompletableFuture.allOf(minutelyF, alertsF).thenAccept(v -> {
                String minutelySummary = com.oilquiz.app.weather.WeatherSummaryUtil
                        .parseMinutelySummary(minutelyF.join());
                String alertSummary = com.oilquiz.app.weather.WeatherSummaryUtil
                        .parseAlertSummary(alertsF.join());
                String[] today = parseForecastToday(forecastText);
                final String summary = com.oilquiz.app.weather.WeatherSummaryUtil.generate(
                        today[0], today[1], today[2], today[3],
                        info.temp, info.feelsLike, info.description,
                        info.humidity, info.uv, info.windScale,
                        info.visibility, minutelySummary, alertSummary);
                post(() -> applySummaryText(summary));
            }).exceptionally(e -> {
                Log.w(TAG, "加载降水/预警摘要失败，总介绍降级(无降水/预警段): " + e.getMessage());
                String[] today = parseForecastToday(forecastText);
                final String summary = com.oilquiz.app.weather.WeatherSummaryUtil.generate(
                        today[0], today[1], today[2], today[3],
                        info.temp, info.feelsLike, info.description,
                        info.humidity, info.uv, info.windScale,
                        info.visibility, null, null);
                post(() -> applySummaryText(summary));
                return null;
            });
        } catch (Exception e) {
            Log.w(TAG, "总介绍异步生成失败: " + e.getMessage());
            String[] today = parseForecastToday(forecastText);
            final String summary = com.oilquiz.app.weather.WeatherSummaryUtil.generate(
                    today[0], today[1], today[2], today[3],
                    info.temp, info.feelsLike, info.description,
                    info.humidity, info.uv, info.windScale,
                    info.visibility, null, null);
            post(() -> applySummaryText(summary));
        }
    }

    /** 设置总介绍文本（含具体地址补充） */
    private void applySummaryText(String summary) {
        if (weatherSummary == null) return;
        StringBuilder s = new StringBuilder();
        if (summary != null) {
            // 具体地址补充（如" · 海淀区中关村大街"）
            if (detailAddress != null && !detailAddress.isEmpty()) {
                s.append(summary).append(" · ").append(detailAddress);
            } else {
                s.append(summary);
            }
        }
        weatherSummary.setText(s.toString().trim());
    }

    /** 解析今日预报文本：返回 [白天天气, 夜间天气, 最高温, 最低温] */
    private String[] parseForecastToday(String forecast) {
        String[] result = {"", "", "", ""};
        if (forecast == null) return result;
        try {
            for (String line : forecast.split("\n")) {
                line = line.trim();
                if (line.startsWith("白天:") || line.startsWith("白天天气:")) {
                    String rest = line.substring(line.indexOf(':') + 1).trim();
                    result[0] = extractWeatherWord(rest);
                } else if (line.startsWith("夜间:") || line.startsWith("夜间天气:")) {
                    String rest = line.substring(line.indexOf(':') + 1).trim();
                    result[1] = extractWeatherWord(rest);
                } else if (line.startsWith("最高温度:")) {
                    result[2] = line.substring(5).trim().replace("°C", "").replace("°", "");
                } else if (line.startsWith("最低温度:")) {
                    result[3] = line.substring(5).trim().replace("°C", "").replace("°", "");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "解析今日预报失败: " + e.getMessage());
        }
        return result;
    }

    /** 从"多云 12° / 24°"这类行提取天气词（跳过数字/度数） */
    private static String extractWeatherWord(String rest) {
        if (rest == null) return "";
        for (String part : rest.split("\\s+")) {
            if (part.isEmpty()) continue;
            if (part.contains("°")) continue;
            if (part.matches("-?\\d+(\\.\\d+)?")) continue;
            return part;
        }
        return rest.length() > 0 ? rest.split("\\s+")[0] : "";
    }

    /**
     * 最简城市名处理：只"去掉省/市级前缀"，其余原样返回，绝不截断中间内容
     */
    private static String truncateCityName(String name) {
        if (name == null || name.isEmpty()) return name;
        String s = name;

        int provEnd = -1;
        int sheng = s.indexOf('省');
        int zhiQu = s.indexOf("自治区");
        int teBie = s.indexOf("特别行政区");
        if (sheng >= 0 && sheng < 8) provEnd = Math.max(provEnd, sheng + 1);
        if (zhiQu >= 0 && zhiQu < 12) provEnd = Math.max(provEnd, zhiQu + 3);
        if (teBie >= 0 && teBie < 15) provEnd = Math.max(provEnd, teBie + 5);
        if (provEnd > 0 && provEnd < s.length()) s = s.substring(provEnd);

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

    private WeatherBannerManager.WeatherInfo parseWeather(String weatherText) {
        WeatherBannerManager.WeatherInfo info = new WeatherBannerManager.WeatherInfo();
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
                    info.temp = line.substring(3).trim().replace("°C", "").replace("°", "");
                } else if (line.startsWith("湿度:")) {
                    info.humidity = line.substring(3).trim().replace("%", "");
                } else if (line.startsWith("风速:")) {
                    info.wind = line.substring(3).trim().replace(" km/h", "").replace("m/s", "");
                } else if (line.startsWith("风向:")) {
                    info.windDir = line.substring(3).trim();
                } else if (line.startsWith("风力:")) {
                    info.windScale = line.substring(3).trim().replace("级", "");
                } else if (line.startsWith("紫外线:")) {
                    info.uv = line.substring(4).trim();
                } else if (line.startsWith("体感温度:")) {
                    info.feelsLike = line.substring(5).trim().replace("°C", "").replace("°", "");
                } else if (line.startsWith("能见度:")) {
                    info.visibility = line.substring(4).trim().replace(" km", "");
                } else if (line.startsWith("气压:")) {
                    info.pressure = line.substring(3).trim().replace(" hPa", "");
                } else if (line.startsWith("链接:")) {
                    info.fxLink = line.substring(3).trim();
                }
            }

            if (!iconCode.isEmpty()) {
                info.icon = QWeatherIconFont.getIcon(iconCode);
            } else {
                info.icon = QWeatherIconFont.getIcon("999");
            }
        } catch (Exception e) {
            info.description = "解析失败";
        }
        return info;
    }

    public void setCity(String city) {
        this.currentCity = city;
        loadWeatherWithCity(currentCity);
    }

    public String getCity() {
        return currentCity;
    }

    public void setAutoLoad(boolean autoLoad) {
        this.autoLoad = autoLoad;
    }

    public boolean isLocationPermissionGranted() {
        return locationPermissionGranted;
    }

    /**
     * 给所有子 View 绑定点击跳转：保证点击横幅任意位置（文字/图标/空白）都触发跳转，
     * 避免子 View 拦截事件导致 onClickListener 不触发。
     */
    private void bindClickToAllChildren(ViewGroup viewGroup) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            // 获取定位按钮独立处理（点击请求定位权限），不绑定"跳详情页"，避免覆盖其监听
            if (child.getId() == R.id.weather_locate_btn) {
                continue;
            }
            child.setOnClickListener(v -> onBannerClicked());
            if (child instanceof ViewGroup) {
                bindClickToAllChildren((ViewGroup) child);
            }
        }
    }

    public interface OnBannerClickedListener {
        void onBannerClicked(String city);
    }

    private OnBannerClickedListener onBannerClickedListener;

    public void setOnBannerClickedListener(OnBannerClickedListener listener) {
        this.onBannerClickedListener = listener;
    }
}
