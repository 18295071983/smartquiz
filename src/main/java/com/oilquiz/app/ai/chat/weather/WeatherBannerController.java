package com.oilquiz.app.ai.chat.weather;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.oilquiz.app.ai.tool.AIWeatherManager;
import com.oilquiz.app.ai.tool.LocationTool;
import com.oilquiz.app.util.AILogger;
import com.oilquiz.app.util.QWeatherIconFont;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

import android.graphics.Typeface;

/**
 * 管理 AI 聊天页面的天气横幅。
 * GPS精准定位 → Android Geocoder反解析城市名 → 直接用经纬度查天气（不重复调GeoAPI）。
 * 支持天气/位置缓存、详情展开。
 */
public class WeatherBannerController {

    private static final String TAG = "WeatherBannerController";
    private static final String PREFS_NAME = "weather_location_cache";
    // 位置缓存
    private static final String KEY_LAT = "cached_lat";
    private static final String KEY_LON = "cached_lon";
    private static final String KEY_CITY = "cached_city";
    private static final String KEY_TIMESTAMP = "cached_timestamp";
    private static final long LOCATION_CACHE_DURATION = 30 * 60 * 1000; // 30分钟
    // 天气缓存
    private static final String KEY_W_CITY = "w_city";
    private static final String KEY_W_TEMP = "w_temp";
    private static final String KEY_W_COND = "w_condition";
    private static final String KEY_W_ICON = "w_icon";
    private static final String KEY_W_FEELS = "w_feels_like";
    private static final String KEY_W_HUMIDITY = "w_humidity";
    private static final String KEY_W_WIND = "w_wind";
    private static final String KEY_W_WIND_DIR = "w_wind_dir";
    private static final String KEY_W_VIS = "w_visibility";
    private static final String KEY_W_PRESSURE = "w_pressure";
    private static final String KEY_W_TIMESTAMP = "w_timestamp";
    private static final long WEATHER_CACHE_DURATION = 10 * 60 * 1000; // 10分钟

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
    // 详情区域
    private View weatherDetailContainer;
    private TextView weatherFeelsLike;
    private TextView weatherHumidity;
    private TextView weatherWind;
    private TextView weatherWindDir;
    private TextView weatherVisibility;
    private TextView weatherPressure;

    private boolean isVisible = false;
    private boolean isDetailExpanded = false;
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
        if (this.weatherIcon != null) {
            Typeface iconTypeface = QWeatherIconFont.getTypeface(activity);
            this.weatherIcon.setTypeface(iconTypeface);
            this.weatherIcon.setText(QWeatherIconFont.getIcon("999"));
        }
    }

    /**
     * 绑定详情区域视图
     */
    public void bindDetailViews(View detailContainer, TextView feelsLike, TextView humidity,
                                TextView wind, TextView windDir, TextView visibility, TextView pressure) {
        this.weatherDetailContainer = detailContainer;
        this.weatherFeelsLike = feelsLike;
        // 注意：humidity 和 wind 已在 bindViews 中设置，这里不再重复设置
        this.weatherWindDir = windDir;
        this.weatherVisibility = visibility;
        this.weatherPressure = pressure;
    }

    /**
     * 加载天气：优先使用天气缓存 → 位置缓存 → GPS定位
     */
    public void loadWeather() {
        loadWeather(false);
    }

    public void loadWeather(boolean forceRefresh) {
        if (weatherManager == null || weatherBanner == null) return;

        // 1. 先检查天气缓存（非强制刷新时）
        if (!forceRefresh) {
            CachedWeather cachedWeather = getCachedWeather();
            if (cachedWeather != null) {
                AILogger.i(TAG, "Using cached weather: " + cachedWeather.city + " " + cachedWeather.temp + "°C");
                // 同时恢复经纬度（用于天气详情页跳转）
                CachedLocation cachedLoc = getCachedLocation();
                if (cachedLoc != null) {
                    currentLat = cachedLoc.lat;
                    currentLon = cachedLoc.lon;
                }
                activity.runOnUiThread(() -> {
                    updateUIFromCache(cachedWeather);
                    weatherBanner.setVisibility(View.VISIBLE);
                    isVisible = true;
                });
                return;
            }
        }

        if (weatherCity != null) weatherCity.setText(forceRefresh ? "刷新中..." : "正在定位...");
        if (weatherTemp != null) weatherTemp.setText("--°C");
        if (weatherDesc != null) weatherDesc.setText("加载中...");

        // 2. 检查位置缓存
        CachedLocation cached = getCachedLocation();
        if (cached != null) {
            AILogger.i(TAG, "Using cached location: " + cached.city + " (" + cached.lat + "," + cached.lon + ")");
            currentLat = cached.lat;
            currentLon = cached.lon;
            currentCity = cached.city;
            fetchWeatherByLocation(cached.lat, cached.lon, cached.city);
            return;
        }

        // 3. 缓存过期或不存在，GPS定位
        if (!LocationTool.hasLocationPermission(activity)) {
            AILogger.w(TAG, "No location permission, hiding weather banner");
            activity.runOnUiThread(this::hide);
            return;
        }

        if (weatherCity != null) weatherCity.setText("正在定位...");
        new Thread(() -> {
            try {
                LocationTool locationTool = new LocationTool(activity);
                LocationTool.SmartLocationResult result = locationTool.getSmartLocation();

                if (result.success && result.location != null) {
                    double lat = result.location.latitude;
                    double lon = result.location.longitude;
                    AILogger.i(TAG, "GPS定位成功: lat=" + lat + ", lon=" + lon + ", provider=" + result.providerUsed);

                    // 用Android Geocoder反解析城市名（如"金凤区"）
                    String cityName = getCityName(lat, lon);
                    if (cityName == null || cityName.isEmpty()) {
                        cityName = "当前位置";
                    }

                    // 缓存位置
                    saveCachedLocation(lat, lon, cityName);

                    currentLat = lat;
                    currentLon = lon;
                    currentCity = cityName;

                    // 用经纬度直接查天气（不做GeoAPI反解析，省一次API调用）
                    fetchWeatherByLocation(lat, lon, cityName);
                } else {
                    AILogger.w(TAG, "GPS定位失败，隐藏天气banner");
                    activity.runOnUiThread(this::hide);
                }
            } catch (Exception e) {
                Log.e(TAG, "Location error", e);
                activity.runOnUiThread(this::hide);
            }
        }).start();
    }

    /**
     * 用经纬度获取天气
     * 使用 getCurrentWeatherByLocation（含GeoAPI反解析），获取最精确的地址
     * 和风GeoAPI可能返回乡镇级地址，比Android Geocoder更精确
     */
    private void fetchWeatherByLocation(double lat, double lon, String geocoderCity) {
        new Thread(() -> {
            try {
                String result = weatherManager.getCurrentWeatherByLocation(lat, lon).get();
                if (result != null && !result.isEmpty() && !result.contains("失败")) {
                    activity.runOnUiThread(() -> {
                        try {
                            JSONObject weatherJson = parseWeatherResponse(result);
                            if (weatherJson != null) {
                                // 优先使用Geocoder的完整位置信息用于显示
                                if (geocoderCity != null && !geocoderCity.isEmpty()) {
                                    weatherJson.put("city", geocoderCity);
                                } else {
                                    // Geocoder没有结果时，用API返回的地址
                                    String apiCity = weatherJson.optString("city", "");
                                    if (apiCity.isEmpty() || apiCity.equals("未知")) {
                                        weatherJson.put("city", "当前位置");
                                    }
                                }
                                updateUI(weatherJson);
                                saveCachedWeather(weatherJson);
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
                Log.e(TAG, "Error loading weather by location", e);
                activity.runOnUiThread(this::hide);
            }
        }).start();
    }

    /**
     * 用城市名获取天气（降级方案）
     */
    private void fetchWeatherByCity(String city) {
        new Thread(() -> {
            try {
                String result = weatherManager.getCurrentWeather(city).get();
                if (result != null && !result.isEmpty() && !result.contains("失败")) {
                    activity.runOnUiThread(() -> {
                        try {
                            JSONObject weatherJson = parseWeatherResponse(result);
                            if (weatherJson != null) {
                                updateUI(weatherJson);
                                saveCachedWeather(weatherJson);
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
                Log.e(TAG, "Error loading weather by city", e);
                activity.runOnUiThread(this::hide);
            }
        }).start();
    }

    /**
     * GPS反解析城市名：使用Android Geocoder（区/县级精确地址）
     * 不再使用和风GeoAPI，避免重复API调用
     */
    private String getCityName(double lat, double lon) {
        if (Geocoder.isPresent()) {
            try {
                Geocoder geocoder = new Geocoder(activity, Locale.CHINA);
                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    // 拼接完整位置信息：省 + 市 + 区
                    StringBuilder sb = new StringBuilder();
                    String province = address.getAdminArea();    // 省
                    String city = address.getLocality();         // 市
                    String district = address.getSubLocality();  // 区
                    if (province != null && !province.isEmpty()) sb.append(province);
                    if (city != null && !city.isEmpty() && !city.equals(province)) {
                        sb.append(city);
                    }
                    if (district != null && !district.isEmpty()) sb.append(district);
                    String name = sb.toString();
                    if (name.isEmpty()) {
                        // 兜底：尝试featureName（如街道名）
                        name = address.getFeatureName();
                    }
                    if (name != null && !name.isEmpty()) {
                        AILogger.i(TAG, "Geocoder反解析成功: " + name);
                        return name;
                    }
                }
            } catch (Exception e) {
                AILogger.w(TAG, "Geocoder failed: " + e.getMessage());
            }
        }
        return null;
    }

    // ========== 位置缓存 ==========

    private static class CachedLocation {
        double lat;
        double lon;
        String city;
        long timestamp;
    }

    private CachedLocation getCachedLocation() {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long timestamp = prefs.getLong(KEY_TIMESTAMP, 0);
            if (timestamp == 0) return null;

            long age = System.currentTimeMillis() - timestamp;
            if (age > LOCATION_CACHE_DURATION) {
                AILogger.i(TAG, "Location cache expired (age=" + (age / 1000) + "s)");
                return null;
            }

            CachedLocation cached = new CachedLocation();
            cached.lat = Double.longBitsToDouble(prefs.getLong(KEY_LAT, 0));
            cached.lon = Double.longBitsToDouble(prefs.getLong(KEY_LON, 0));
            cached.city = prefs.getString(KEY_CITY, "");
            cached.timestamp = timestamp;

            if (cached.lat == 0 && cached.lon == 0) return null;
            return cached;
        } catch (Exception e) {
            AILogger.w(TAG, "Error reading cached location: " + e.getMessage());
            return null;
        }
    }

    private void saveCachedLocation(double lat, double lon, String city) {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putLong(KEY_LAT, Double.doubleToRawLongBits(lat))
                    .putLong(KEY_LON, Double.doubleToRawLongBits(lon))
                    .putString(KEY_CITY, city)
                    .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                    .apply();
            AILogger.i(TAG, "Location cached: " + city + " (" + lat + "," + lon + ")");
        } catch (Exception e) {
            AILogger.w(TAG, "Error saving cached location: " + e.getMessage());
        }
    }

    // ========== 天气缓存 ==========

    private static class CachedWeather {
        String city;
        String temp;
        String condition;
        String icon;
        String feelsLike;
        String humidity;
        String wind;
        String windDir;
        String visibility;
        String pressure;
        long timestamp;
    }

    private CachedWeather getCachedWeather() {
        try {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long timestamp = prefs.getLong(KEY_W_TIMESTAMP, 0);
            if (timestamp == 0) return null;

            long age = System.currentTimeMillis() - timestamp;
            if (age > WEATHER_CACHE_DURATION) {
                AILogger.i(TAG, "Weather cache expired (age=" + (age / 1000) + "s)");
                return null;
            }

            String city = prefs.getString(KEY_W_CITY, "");
            if (city.isEmpty()) return null;

            CachedWeather cached = new CachedWeather();
            cached.city = city;
            cached.temp = prefs.getString(KEY_W_TEMP, "--");
            cached.condition = prefs.getString(KEY_W_COND, "--");
            cached.icon = prefs.getString(KEY_W_ICON, "");
            cached.feelsLike = prefs.getString(KEY_W_FEELS, "--");
            cached.humidity = prefs.getString(KEY_W_HUMIDITY, "--");
            cached.wind = prefs.getString(KEY_W_WIND, "--");
            cached.windDir = prefs.getString(KEY_W_WIND_DIR, "--");
            cached.visibility = prefs.getString(KEY_W_VIS, "--");
            cached.pressure = prefs.getString(KEY_W_PRESSURE, "--");
            cached.timestamp = timestamp;
            return cached;
        } catch (Exception e) {
            AILogger.w(TAG, "Error reading cached weather: " + e.getMessage());
            return null;
        }
    }

    private void saveCachedWeather(JSONObject weatherJson) {
        try {
            String city = weatherJson.optString("city", "");
            String temp = weatherJson.optString("temperature", "--");
            String condition = weatherJson.optString("condition", "--");
            String icon = weatherJson.optString("icon", "");
            String feelsLike = weatherJson.optString("feelsLike", "--");
            String humidity = weatherJson.optString("humidity", "--");
            String wind = weatherJson.optString("windSpeed", "--");
            String windDir = weatherJson.optString("windDir", "--");
            String visibility = weatherJson.optString("visibility", "--");
            String pressure = weatherJson.optString("pressure", "--");

            SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_W_CITY, city)
                    .putString(KEY_W_TEMP, temp)
                    .putString(KEY_W_COND, condition)
                    .putString(KEY_W_ICON, icon)
                    .putString(KEY_W_FEELS, feelsLike)
                    .putString(KEY_W_HUMIDITY, humidity)
                    .putString(KEY_W_WIND, wind)
                    .putString(KEY_W_WIND_DIR, windDir)
                    .putString(KEY_W_VIS, visibility)
                    .putString(KEY_W_PRESSURE, pressure)
                    .putLong(KEY_W_TIMESTAMP, System.currentTimeMillis())
                    .apply();
            AILogger.i(TAG, "Weather cached: " + city + " " + temp + "°C " + condition);
        } catch (Exception e) {
            AILogger.w(TAG, "Error saving cached weather: " + e.getMessage());
        }
    }

    // ========== UI更新 ==========

    private void updateUI(JSONObject w) {
        String cityName = w.optString("city", "未知");
        String tempStr = w.optString("temperature", "--");
        String condition = w.optString("condition", "--");
        String iconCode = w.optString("icon", "");
        String feelsLike = w.optString("feelsLike", "--");
        String humidity = w.optString("humidity", "--");
        String windSpeed = w.optString("windSpeed", "--");
        String windDir = w.optString("windDir", "--");
        String visibility = w.optString("visibility", "--");
        String pressure = w.optString("pressure", "--");

        if (weatherIcon != null) {
            weatherIcon.setText(iconCode.isEmpty() ? QWeatherIconFont.getIcon("999") : QWeatherIconFont.getIcon(iconCode));
        }
        if (weatherCity != null) weatherCity.setText(cityName);
        if (weatherTemp != null) weatherTemp.setText(tempStr + "°C");
        if (weatherDesc != null) weatherDesc.setText(condition);

        // 详情区域
        if (weatherFeelsLike != null) weatherFeelsLike.setText("体感温度: " + feelsLike + "°C");
        if (weatherHumidity != null) weatherHumidity.setText("湿度: " + humidity + "%");
        if (weatherWind != null) weatherWind.setText("风速: " + windSpeed + " km/h");
        if (weatherWindDir != null) weatherWindDir.setText("风向: " + windDir);
        if (weatherVisibility != null) weatherVisibility.setText("能见度: " + visibility + " km");
        if (weatherPressure != null) weatherPressure.setText("气压: " + pressure + " hPa");

        currentCity = cityName;
    }

    private void updateUIFromCache(CachedWeather c) {
        if (weatherIcon != null) {
            weatherIcon.setText(c.icon.isEmpty() ? QWeatherIconFont.getIcon("999") : QWeatherIconFont.getIcon(c.icon));
        }
        if (weatherCity != null) weatherCity.setText(c.city);
        if (weatherTemp != null) weatherTemp.setText(c.temp + "°C");
        if (weatherDesc != null) weatherDesc.setText(c.condition);

        if (weatherFeelsLike != null) weatherFeelsLike.setText("体感温度: " + c.feelsLike + "°C");
        if (weatherHumidity != null) weatherHumidity.setText("湿度: " + c.humidity + "%");
        if (weatherWind != null) weatherWind.setText("风速: " + c.wind + " km/h");
        if (weatherWindDir != null) weatherWindDir.setText("风向: " + c.windDir);
        if (weatherVisibility != null) weatherVisibility.setText("能见度: " + c.visibility + " km");
        if (weatherPressure != null) weatherPressure.setText("气压: " + c.pressure + " hPa");

        currentCity = c.city;
    }

    /**
     * 切换详情区域展开/折叠
     */
    public void toggleDetail() {
        if (weatherDetailContainer == null) return;
        isDetailExpanded = !isDetailExpanded;
        weatherDetailContainer.setVisibility(isDetailExpanded ? View.VISIBLE : View.GONE);
    }

    public void hide() {
        if (weatherBanner != null) {
            weatherBanner.setVisibility(View.GONE);
            isVisible = false;
            // 同时折叠详情
            if (weatherDetailContainer != null) {
                weatherDetailContainer.setVisibility(View.GONE);
                isDetailExpanded = false;
            }
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

    /**
     * 解析天气工具返回的文本格式
     * 格式: "城市: xxx\n天气: xxx\n图标: xxx\n温度: xxx\n体感温度: xxx\n湿度: xxx\n风速: xxx\n风向: xxx\n能见度: xxx\n..."
     */
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
                } else if (line.startsWith("天气:") && !line.startsWith("天气信息")) {
                    result.put("condition", line.substring(3).trim());
                } else if (line.startsWith("图标:")) {
                    result.put("icon", line.substring(3).trim());
                } else if (line.startsWith("温度:")) {
                    String temp = line.substring(3).trim().replace("°C", "").replace("°", "");
                    result.put("temperature", temp);
                } else if (line.startsWith("体感温度:")) {
                    result.put("feelsLike", line.substring(5).trim().replace("°C", "").replace("°", ""));
                } else if (line.startsWith("湿度:")) {
                    result.put("humidity", line.substring(3).trim().replace("%", ""));
                } else if (line.startsWith("风速:")) {
                    result.put("windSpeed", line.substring(3).trim().replace("km/h", "").trim());
                } else if (line.startsWith("风向:")) {
                    result.put("windDir", line.substring(3).trim());
                } else if (line.startsWith("能见度:")) {
                    result.put("visibility", line.substring(4).trim().replace("km", "").trim());
                } else if (line.startsWith("气压:")) {
                    result.put("pressure", line.substring(3).trim().replace("hPa", "").trim());
                }
            }
            return result.has("city") ? result : null;
        } catch (Exception e) {
            Log.e(TAG, "Parse error", e);
            return null;
        }
    }
}
