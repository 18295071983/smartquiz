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

    private TextView weatherIcon;
    private TextView weatherCity;
    private TextView weatherTemp;
    private TextView weatherDesc;
    private ImageView weatherArrow;
    private View weatherBanner;

    private WeatherService weatherService;
    private String currentCity = "北京";
    private boolean autoLoad = true;
    private boolean locationPermissionGranted = false;
    private double cachedLat = 0;
    private double cachedLon = 0;
    private String cachedFxLink = "";
    private long lastRefreshTime = 0;
    private String cachedAddress = "";

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

    private String getCityNameFromGeocoder(double lat, double lon) {
        if (Geocoder.isPresent()) {
            try {
                Geocoder geocoder = new Geocoder(getContext(), Locale.CHINA);
                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    String name = address.getSubLocality();
                    if (name == null) name = address.getLocality();
                    if (name == null) name = address.getAdminArea();
                    if (name != null) {
                        Log.i(TAG, "Geocoder反解析成功: " + name);
                        return name;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Geocoder failed: " + e.getMessage());
            }
        }
        return null;
    }

    private void saveCachedLocation(double lat, double lon, String city) {
        try {
            SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putLong(KEY_LAT, Double.doubleToRawLongBits(lat))
                    .putLong(KEY_LON, Double.doubleToRawLongBits(lon))
                    .putString(KEY_CITY, city)
                    .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                    .apply();
            Log.i(TAG, "Location cached: " + city + " (" + lat + "," + lon + ")");
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
            loadWeatherWithCity(currentCity);
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
                    if (weatherDesc != null) weatherDesc.setText("定位权限被拒绝，使用默认城市");
                    loadWeatherWithCity(currentCity);
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
            refreshWeatherWithCity(currentCity);
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
                    if (weatherDesc != null) weatherDesc.setText("定位权限被拒绝，使用默认城市");
                    refreshWeatherWithCity(currentCity);
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

        // 快速显示当前城市（缓存或默认），同时后台更新位置
        if (weatherCity != null) weatherCity.setText(currentCity);
        if (weatherDesc != null) weatherDesc.setText("定位中，正在更新...");

        // 先用已有城市名加载天气，同时后台获取精确位置
        loadWeatherWithCity(currentCity);

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
                        cityName = currentCity;
                    }
                    currentCity = cityName;
                    cachedAddress = cityName;

                    saveCachedLocation(lat, lon, cityName);

                    final String finalCity = cityName;
                    post(() -> {
                        if (weatherCity != null) weatherCity.setText(finalCity);
                        loadWeatherByLocationDirect(lat, lon, finalCity);
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "Background location update failed: " + e.getMessage());
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
                        cityName = currentCity;
                    }
                    currentCity = cityName;
                    cachedAddress = cityName;

                    saveCachedLocation(lat, lon, cityName);

                    weatherService.clearCacheForLocation(lat, lon);
                    final String finalCity = cityName;
                    post(() -> loadWeatherByLocationDirect(lat, lon, finalCity));
                    return;
                }
                Log.w(TAG, "Refresh location returned no valid coordinates, falling back to city: " + currentCity);
            } catch (Exception e) {
                Log.e(TAG, "Refresh location failed: " + e.getMessage(), e);
            }

            post(() -> refreshWeatherWithCity(currentCity));
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
            weatherCity.setText(displayCity);
        }
        if (weatherTemp != null) {
            weatherTemp.setText(info.temp + "°");
        }
        if (weatherDesc != null) {
            String desc = info.description;
            if (desc == null || desc.isEmpty()) {
                desc = "暂无数据";
            }
            if (info.humidity != null && !info.humidity.isEmpty() && !info.humidity.equals("--")) {
                desc += " · 湿度" + info.humidity + "%";
            }
            weatherDesc.setText(desc);
        }
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
