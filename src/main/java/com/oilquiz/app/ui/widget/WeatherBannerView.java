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
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
    private View weatherBanner;
    // 介绍行 chips（类似天气详情页的介绍功能）
    private TextView weatherFeelsLike;   // 体感温度
    private TextView weatherHumidity;    // 湿度
    private TextView weatherWind;        // 风向+风力
    private TextView weatherVisibility;  // 能见度

    private WeatherService weatherService;
    private String currentCity = "";
    private boolean autoLoad = true;
    private boolean locationPermissionGranted = false;
    private double cachedLat = 0;
    private double cachedLon = 0;
    private String cachedFxLink = "";
    private long lastRefreshTime = 0;
    private String cachedAddress = "";

    private android.content.BroadcastReceiver locationUpdateReceiver;

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
        weatherBanner = findViewById(R.id.weather_banner);
        // 介绍行 chips
        weatherFeelsLike  = findViewById(R.id.weather_feels_like);
        weatherHumidity   = findViewById(R.id.weather_humidity);
        weatherWind       = findViewById(R.id.weather_wind);
        weatherVisibility = findViewById(R.id.weather_visibility);

        disableChildClicks(this);

        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> onBannerClicked());

        if (weatherBanner != null) {
            weatherBanner.setClickable(false);
            weatherBanner.setFocusable(false);
            weatherBanner.setOnClickListener(null);
        }

        Typeface iconTypeface = QWeatherIconFont.getTypeface(getContext());
        if (weatherIcon != null) {
            weatherIcon.setTypeface(iconTypeface);
            weatherIcon.setText(QWeatherIconFont.getIcon("999"));
        }

        weatherService = WeatherService.getInstance(getContext());

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

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        int action = event.getAction();
        
        if (action == MotionEvent.ACTION_UP) {
            Log.d(TAG, "dispatchTouchEvent ACTION_UP at (" + x + ", " + y + ")");
            post(this::onBannerClicked);
            return true;
        }
        
        if (action == MotionEvent.ACTION_DOWN) {
            Log.d(TAG, "dispatchTouchEvent ACTION_DOWN at (" + x + ", " + y + ")");
        }
        
        return super.dispatchTouchEvent(event);
    }

    private void setupListeners() {
        if (weatherBanner != null) {
            weatherBanner.setOnClickListener(v -> onBannerClicked());
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
        }
    }

    public void onResume() {
        long now = System.currentTimeMillis();

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
                    return;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "onResume 检查SP缓存失败: " + e.getMessage());
        }

        // 5 分钟超时兜底：超过 5 分钟才重新请求网络/重新定位
        if (lastRefreshTime == 0 || (now - lastRefreshTime) > MIN_REFRESH_INTERVAL_MS) {
            if (loadFromCachedLocation()) return;
            requestLocationAndLoad();
        }
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
            post(() -> updateUI(weather));
        }).exceptionally(e -> {
            Log.e(TAG, "Failed to load weather by location: " + e.getMessage(), e);
            post(() -> loadWeatherWithCity(cityName));
            return null;
        });
    }

    /** 反解析坐标：按"区+路 > 区+市 > 市"的优先级取最具体的地址，不再冗余拼省名 */
    private String getCityNameFromGeocoder(double lat, double lon) {
        if (!Geocoder.isPresent()) return null;
        try {
            Geocoder geocoder = new Geocoder(getContext(), Locale.CHINA);
            List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
            if (addresses == null || addresses.isEmpty()) return null;
            Address a = addresses.get(0);
            String province = a.getAdminArea();         // 省（用于去除重复，不拼到结果）
            String city     = a.getLocality();          // 市
            String district = a.getSubLocality();       // 区/县
            String road     = a.getThoroughfare();      // 街道/路名（最关键）
            String feature  = a.getFeatureName();       // 地标/小区名
            String result = null;
            // 1. 区 + 路（最优，最具体）
            if (district != null && !district.isEmpty()
                && road != null && !road.isEmpty()) {
                result = district + road;
            }
            // 2. 路 + 地标
            else if (road != null && !road.isEmpty()
                     && feature != null && !feature.isEmpty()) {
                result = road + feature;
            }
            // 3. 区 + 市
            else if (district != null && !district.isEmpty()
                     && city != null && !city.isEmpty()
                     && !city.equals(district)
                     && !city.equals(province)) {
                result = district + city;
            }
            // 4. 单路名
            else if (road != null && !road.isEmpty()) {
                result = road;
            }
            // 5. 单区 / 单市 / 单地标
            else {
                if (district != null && !district.isEmpty()) result = district;
                else if (city != null && !city.isEmpty())   result = city;
                else if (feature != null && !feature.isEmpty()) result = feature;
            }
            if (result == null || result.isEmpty()) {
                result = a.getFeatureName();
            }
            if (result != null && !result.isEmpty()) {
                Log.i(TAG, "Geocoder反解析成功: " + result);
                return result;
            }
        } catch (Exception e) {
            Log.w(TAG, "Geocoder failed: " + e.getMessage());
        }
        return null;
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

    public void requestLocationAndLoad() {
        AppResourceManager resources = AppResourceManager.getInstance(getContext());
        if (resources.hasLocationPermission()) {
            locationPermissionGranted = true;
            locateAndLoadWeather();
            return;
        }

        if (weatherDesc != null) weatherDesc.setText("正在请求定位权限...");

        Activity activity = tryGetActivity();
        if (activity == null) {
            if (weatherCity != null) weatherCity.setText("未定位");
            if (weatherDesc != null) weatherDesc.setText("无法获取Activity");
            if (weatherTemp != null) weatherTemp.setText("--°");
            return;
        }

        resources.permissions().requestLocationPermission(activity, new PermissionResourceProvider.PermissionCallback() {
            @Override
            public void onGranted() {
                locationPermissionGranted = true;
                post(() -> locateAndLoadWeather());
            }

            @Override
            public void onDenied(List<String> deniedPermissions) {
                locationPermissionGranted = false;
                post(() -> {
                    if (weatherCity != null) weatherCity.setText("未定位");
                    if (weatherDesc != null) weatherDesc.setText("定位权限被拒绝");
                    if (weatherTemp != null) weatherTemp.setText("--°");
                });
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

        if (weatherDesc != null) weatherDesc.setText("正在请求定位权限...");

        Activity activity = tryGetActivity();
        if (activity == null) {
            if (weatherCity != null) weatherCity.setText("未定位");
            if (weatherDesc != null) weatherDesc.setText("无法获取Activity");
            if (weatherTemp != null) weatherTemp.setText("--°");
            return;
        }

        resources.permissions().requestLocationPermission(activity, new PermissionResourceProvider.PermissionCallback() {
            @Override
            public void onGranted() {
                locationPermissionGranted = true;
                post(() -> locateAndRefreshWeather());
            }

            @Override
            public void onDenied(List<String> deniedPermissions) {
                locationPermissionGranted = false;
                post(() -> {
                    if (weatherCity != null) weatherCity.setText("未定位");
                    if (weatherDesc != null) weatherDesc.setText("定位权限被拒绝");
                    if (weatherTemp != null) weatherTemp.setText("--°");
                });
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
            post(() -> updateUI(weather));
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
            post(() -> updateUI(weather));
        }).exceptionally(e -> {
            Log.e(TAG, "Failed to refresh weather for city " + city + ": " + e.getMessage(), e);
            post(() -> {
                if (weatherDesc != null) weatherDesc.setText("刷新失败，点击重试");
            });
            return null;
        });
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

        // ========== 介绍行 chips：体感 / 湿度 / 风向风力 / 能见度（类似详情页）==========
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
            StringBuilder w = new StringBuilder("🌬 ");
            boolean hasAny = false;
            if (info.windDir != null && !info.windDir.isEmpty() && !"--".equals(info.windDir)) {
                w.append(info.windDir);
                hasAny = true;
            }
            if (info.wind != null && !info.wind.isEmpty() && !"--".equals(info.wind)) {
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

    private void disableChildClicks(ViewGroup viewGroup) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child instanceof ViewGroup) {
                disableChildClicks((ViewGroup) child);
            } else {
                child.setClickable(false);
                child.setFocusable(false);
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
