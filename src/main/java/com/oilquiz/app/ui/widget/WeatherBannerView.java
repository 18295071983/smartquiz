package com.oilquiz.app.ui.widget;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.location.Address;
import android.location.Geocoder;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.LocationTool;
import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.PermissionResourceProvider;
import com.oilquiz.app.weather.WeatherService;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WeatherBannerView extends LinearLayout {

    private static final String TAG = "WeatherBannerView";
    private static final long MIN_REFRESH_INTERVAL_MS = 5 * 60 * 1000;
    // 位置缓存（与AI对话页面共用同一个SharedPreferences）
    private static final String PREFS_NAME = "weather_location_cache";
    private static final String KEY_LAT = "cached_lat";
    private static final String KEY_LON = "cached_lon";
    private static final String KEY_CITY = "cached_city";
    private static final String KEY_TIMESTAMP = "cached_timestamp";
    private static final long LOCATION_CACHE_DURATION = 30 * 60 * 1000; // 30分钟

    private TextView weatherIcon;
    private TextView weatherCity;
    private TextView weatherTemp;
    private TextView weatherDesc;
    private View btnWeatherDetail;
    private View refreshButton;
    private View closeButton;

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
        btnWeatherDetail = findViewById(R.id.btn_weather_detail);
        refreshButton = findViewById(R.id.btn_weather_refresh);
        closeButton = findViewById(R.id.btn_weather_close);

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

        setupListeners();

        if (autoLoad) {
            // 优先使用缓存位置（应用关闭后不丢失）
            if (!loadFromCachedLocation()) {
                requestLocationAndLoad();
            }
        }
    }

    private void setupListeners() {
        refreshButton.setOnClickListener(v -> requestLocationAndRefresh());
        closeButton.setOnClickListener(v -> setVisibility(View.GONE));
        btnWeatherDetail.setOnClickListener(v -> toggleDetail());
    }

    private void toggleDetail() {
        navigateToWeatherDetail();
    }

    private void navigateToWeatherDetail() {
        String city = currentCity;
        if (weatherCity != null) {
            city = weatherCity.getText().toString();
        }
        
        if (city == null || city.isEmpty() || city.equals("定位中...")) {
            return;
        }

        android.content.Intent intent = new android.content.Intent(getContext(),
                com.oilquiz.app.ui.activity.WeatherDetailActivity.class);
        intent.putExtra("city", city);
        if (cachedLat != 0 && cachedLon != 0) {
            intent.putExtra("lat", cachedLat);
            intent.putExtra("lon", cachedLon);
        }
        getContext().startActivity(intent);
    }

    /**
     * Activity onResume 时调用，超过最小刷新间隔则自动刷新
     */
    public void onResume() {
        long now = System.currentTimeMillis();
        if (lastRefreshTime == 0 || (now - lastRefreshTime) > MIN_REFRESH_INTERVAL_MS) {
            // 优先使用缓存位置（应用关闭后不丢失）
            if (loadFromCachedLocation()) return;
            requestLocationAndLoad();
        }
    }

    /**
     * 从缓存位置加载天气，返回true表示使用了缓存
     */
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

    /**
     * 用经纬度查天气，使用getCurrentWeatherByLocation（含GeoAPI反解析）
     * 和风GeoAPI可能返回乡镇级地址，比Android Geocoder更精确
     */
    private void loadWeatherByLocationDirect(double lat, double lon, String cityName) {
        if (!isNetworkAvailable()) {
            if (weatherDesc != null) weatherDesc.setText("无网络连接");
            if (weatherCity != null) weatherCity.setText(cityName);
            return;
        }

        if (weatherCity != null) weatherCity.setText(cityName);
        if (weatherTemp != null) weatherTemp.setText("--°C");
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

    /**
     * 用Android Geocoder反解析城市名（区/县级精确地址）
     */
    private String getCityNameFromGeocoder(double lat, double lon) {
        if (Geocoder.isPresent()) {
            try {
                Geocoder geocoder = new Geocoder(getContext(), Locale.CHINA);
                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    String name = address.getSubLocality(); // 区
                    if (name == null) name = address.getLocality(); // 市
                    if (name == null) name = address.getAdminArea(); // 省
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

    /**
     * 缓存位置到SharedPreferences（持久化，应用关闭后不丢失）
     */
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

    /**
     * 强制刷新，忽略时间间隔限制
     */
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

    /**
     * 根据坐标获取可读地址（坐标→地址转换）
     */
    public String getCachedAddress() {
        return cachedAddress;
    }

    /**
     * 设置外部传入的城市名进行天气查询（地址转换入口）
     */
    public void loadWeatherByCityName(String cityName) {
        if (cityName == null || cityName.trim().isEmpty()) return;
        currentCity = cityName.trim();
        cachedLat = 0;
        cachedLon = 0;
        loadWeatherWithCity(currentCity);
    }

    public void onBannerClicked() {
        if (weatherCity != null) {
            String city = weatherCity.getText().toString();
            if (city != null && !city.equals("定位中...") && !city.equals("未知") && !city.equals("刷新中...") && !city.equals("正在获取天气...") && !city.equals("正在刷新...")) {
                if (onBannerClickedListener != null) {
                    onBannerClickedListener.onBannerClicked(city);
                } else {
                    navigateToWeatherDetail();
                }
            }
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

        if (weatherCity != null) weatherCity.setText("定位中...");
        if (weatherTemp != null) weatherTemp.setText("--°C");
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

                    // 用Android Geocoder反解析城市名（如"金凤区"）
                    String cityName = getCityNameFromGeocoder(lat, lon);
                    if (cityName == null || cityName.isEmpty()) {
                        cityName = currentCity;
                    }
                    currentCity = cityName;
                    cachedAddress = cityName;

                    // 缓存位置（持久化，应用关闭后不丢失）
                    saveCachedLocation(lat, lon, cityName);

                    // 用经纬度直接查天气（不调GeoAPI反解析，省一次API调用）
                    final String finalCity = cityName;
                    post(() -> loadWeatherByLocationDirect(lat, lon, finalCity));
                    return;
                }
                Log.w(TAG, "Location returned no valid coordinates, falling back to city: " + currentCity);
            } catch (Exception e) {
                Log.e(TAG, "Location failed: " + e.getMessage(), e);
            }

            post(() -> loadWeatherWithCity(currentCity));
        }).start();
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
        if (weatherTemp != null) weatherTemp.setText("--°C");
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

                    // 刷新时清除天气缓存，强制获取最新数据
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
        if (weatherTemp != null) weatherTemp.setText("--°C");
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

    private void loadWeatherByLocation(double lat, double lon) {
        if (!isNetworkAvailable()) {
            if (weatherDesc != null) weatherDesc.setText("无网络连接");
            if (weatherCity != null) weatherCity.setText(currentCity);
            return;
        }

        if (weatherCity != null) weatherCity.setText(currentCity);
        if (weatherTemp != null) weatherTemp.setText("--°C");
        if (weatherDesc != null) weatherDesc.setText("正在获取天气...");

        weatherService.getCurrentWeatherByLocation(lat, lon).thenAccept(weather -> {
            lastRefreshTime = System.currentTimeMillis();
            post(() -> updateUI(weather));
        }).exceptionally(e -> {
            Log.e(TAG, "Failed to load weather by location: " + e.getMessage(), e);
            post(() -> loadWeatherWithCity(currentCity));
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
        if (weatherTemp != null) weatherTemp.setText("--°C");
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

    private void refreshWeatherByLocation(double lat, double lon) {
        if (!isNetworkAvailable()) {
            if (weatherDesc != null) weatherDesc.setText("无网络连接");
            if (weatherCity != null) weatherCity.setText(currentCity);
            return;
        }

        if (weatherCity != null) weatherCity.setText(currentCity);
        if (weatherTemp != null) weatherTemp.setText("--°C");
        if (weatherDesc != null) weatherDesc.setText("正在刷新...");

        weatherService.getCurrentWeatherByLocation(lat, lon).thenAccept(weather -> {
            lastRefreshTime = System.currentTimeMillis();
            post(() -> updateUI(weather));
        }).exceptionally(e -> {
            Log.e(TAG, "Failed to refresh weather by location: " + e.getMessage(), e);
            post(() -> refreshWeatherWithCity(currentCity));
            return null;
        });
    }

    private void updateUI(String weatherText) {
        WeatherBannerManager.WeatherInfo info = parseWeather(weatherText);

        if (info.fxLink != null && !info.fxLink.isEmpty()) {
            cachedFxLink = info.fxLink;
        }

        if (weatherIcon != null) weatherIcon.setText(info.icon);
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
            weatherTemp.setText(info.temp + "°C");
        }
        if (weatherDesc != null) weatherDesc.setText(info.description);
    }

    private WeatherBannerManager.WeatherInfo parseForecast(String forecastText) {
        WeatherBannerManager.WeatherInfo info = new WeatherBannerManager.WeatherInfo();
        if (forecastText == null) return info;

        try {
            StringBuilder forecastSummary = new StringBuilder();
            String highTemp = null;
            String lowTemp = null;
            String dayText = null;
            String nightText = null;

            java.util.List<String> dailyForecasts = new java.util.ArrayList<>();
            String[] lines = forecastText.split("\n");
            
            String currentDate = null;
            String currentHigh = null;
            String currentLow = null;
            String currentDayWeather = null;
            String currentNightWeather = null;

            for (String line : lines) {
                line = line.trim();
                
                if (line.startsWith("日期:")) {
                    if (currentDate != null) {
                        if (currentHigh != null && currentLow != null) {
                            String weatherDesc = currentDayWeather != null ? currentDayWeather : (currentNightWeather != null ? currentNightWeather : "");
                            dailyForecasts.add(currentHigh + "°/" + currentLow + "°" + (weatherDesc.isEmpty() ? "" : " " + weatherDesc));
                            if (highTemp == null) highTemp = currentHigh;
                            if (lowTemp == null) lowTemp = currentLow;
                        }
                    }
                    currentDate = line.substring(3).trim();
                    currentHigh = null;
                    currentLow = null;
                    currentDayWeather = null;
                    currentNightWeather = null;
                }
                if (line.startsWith("最高温度:") || line.startsWith("最高温:")) {
                    String val = line.substring(line.indexOf(":") + 1).trim();
                    if (val.endsWith("°C") || val.endsWith("°")) {
                        val = val.replace("°C", "").replace("°", "");
                    }
                    currentHigh = val;
                    if (highTemp == null) highTemp = val;
                }
                if (line.startsWith("最低温度:") || line.startsWith("最低温:")) {
                    String val = line.substring(line.indexOf(":") + 1).trim();
                    if (val.endsWith("°C") || val.endsWith("°")) {
                        val = val.replace("°C", "").replace("°", "");
                    }
                    currentLow = val;
                    if (lowTemp == null) lowTemp = val;
                }
                if (line.startsWith("白天天气:")) {
                    currentDayWeather = line.substring(5).trim();
                    if (dayText == null) dayText = currentDayWeather;
                }
                if (line.startsWith("夜间天气:")) {
                    currentNightWeather = line.substring(5).trim();
                    if (nightText == null) nightText = currentNightWeather;
                }
            }

            if (currentDate != null) {
                if (currentHigh != null && currentLow != null) {
                    String weatherDesc = currentDayWeather != null ? currentDayWeather : (currentNightWeather != null ? currentNightWeather : "");
                    dailyForecasts.add(currentHigh + "°/" + currentLow + "°" + (weatherDesc.isEmpty() ? "" : " " + weatherDesc));
                }
            }

            if (highTemp != null && lowTemp != null) {
                info.tempRange = lowTemp + "° ~ " + highTemp + "°";
            } else if (highTemp != null) {
                info.tempRange = "最高 " + highTemp + "°";
            } else if (lowTemp != null) {
                info.tempRange = "最低 " + lowTemp + "°";
            }

            if (!dailyForecasts.isEmpty()) {
                for (int i = 0; i < Math.min(3, dailyForecasts.size()); i++) {
                    if (i > 0) forecastSummary.append("   ");
                    String[] dayNames = {"今天", "明天", "后天"};
                    String prefix = i < dayNames.length ? dayNames[i] + " " : "";
                    forecastSummary.append(prefix).append(dailyForecasts.get(i));
                }
                info.forecast = forecastSummary.toString();
            } else if (dayText != null && nightText != null) {
                String weatherChange = dayText.equals(nightText) ? dayText : dayText + "转" + nightText;
                info.forecast = weatherChange;
            }
        } catch (Exception ignored) {
        }
        return info;
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
                info.icon = com.oilquiz.app.util.QWeatherIconMapper.getEmojiIcon(iconCode);
            } else {
                info.icon = com.oilquiz.app.util.QWeatherIconMapper.getEmojiIcon("999");
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

    public interface OnBannerClickedListener {
        void onBannerClicked(String city);
    }

    private OnBannerClickedListener onBannerClickedListener;

    public void setOnBannerClickedListener(OnBannerClickedListener listener) {
        this.onBannerClickedListener = listener;
    }
}
