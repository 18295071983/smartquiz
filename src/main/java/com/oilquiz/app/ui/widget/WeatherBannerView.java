package com.oilquiz.app.ui.widget;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.LocationTool;
import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.PermissionResourceProvider;
import com.oilquiz.app.weather.WeatherService;

import java.util.List;
import java.util.Map;

public class WeatherBannerView extends LinearLayout {

    private TextView weatherIcon;
    private TextView weatherCity;
    private TextView weatherTemp;
    private TextView weatherDesc;
    private TextView weatherHumidity;
    private TextView weatherWind;
    private TextView weatherTempRange;
    private TextView weatherForecast;
    private View refreshButton;
    private View closeButton;

    private WeatherService weatherService;
    private String currentCity = "北京";
    private boolean autoLoad = true;
    private boolean locationPermissionGranted = false;
    private double cachedLat = 0;
    private double cachedLon = 0;

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
        weatherHumidity = findViewById(R.id.weather_humidity);
        weatherWind = findViewById(R.id.weather_wind);
        weatherTempRange = findViewById(R.id.weather_temp_range);
        weatherForecast = findViewById(R.id.weather_forecast);
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
            requestLocationAndLoad();
        }
    }

    private void setupListeners() {
        refreshButton.setOnClickListener(v -> requestLocationAndRefresh());

        closeButton.setOnClickListener(v -> setVisibility(View.GONE));
    }

    public void onBannerClicked() {
        if (weatherCity != null) {
            String city = weatherCity.getText().toString();
            if (city != null && !city.equals("定位中...") && !city.equals("未知") && !city.equals("刷新中...") && !city.equals("正在获取天气...") && !city.equals("正在刷新...")) {
                if (onBannerClickedListener != null) {
                    onBannerClickedListener.onBannerClicked(city);
                } else {
                    navigateToWeatherDetail(city);
                }
            }
        }
    }

    private void navigateToWeatherDetail(String city) {
        android.content.Intent intent = new android.content.Intent(getContext(),
            com.oilquiz.app.ui.activity.WeatherDetailActivity.class);
        intent.putExtra("city", city);
        if (cachedLat != 0 && cachedLon != 0) {
            intent.putExtra("lat", cachedLat);
            intent.putExtra("lon", cachedLon);
        }
        getContext().startActivity(intent);
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
        if (weatherCity != null) weatherCity.setText("定位中...");
        if (weatherTemp != null) weatherTemp.setText("--°C");
        if (weatherDesc != null) weatherDesc.setText("正在定位...");

        new Thread(() -> {
            try {
                LocationTool locationTool = new LocationTool(getContext());
                java.util.Map<String, Object> params = new java.util.HashMap<>();
                params.put("action", "get_current");
                AIToolResult result = locationTool.execute(params);

                if (result.isSuccess()) {
                    Object resObj = result.getResult();
                    if (resObj instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) resObj;
                        String city = String.valueOf(map.get("city"));
                        if (city != null && !city.equals("未知") && !city.equals("null") && !city.isEmpty()) {
                            currentCity = city;
                        }
                        double lat = map.get("latitude") instanceof Number ? ((Number) map.get("latitude")).doubleValue() : 0;
                        double lon = map.get("longitude") instanceof Number ? ((Number) map.get("longitude")).doubleValue() : 0;
                        if (lat != 0 && lon != 0) {
                            cachedLat = lat;
                            cachedLon = lon;
                            post(() -> loadWeatherByLocation(lat, lon));
                            return;
                        }
                    }
                }

                post(() -> loadWeatherWithCity(currentCity));
            } catch (Exception e) {
                post(() -> loadWeatherWithCity(currentCity));
            }
        }).start();
    }

    private void locateAndRefreshWeather() {
        if (weatherCity != null) weatherCity.setText("定位中...");
        if (weatherTemp != null) weatherTemp.setText("--°C");
        if (weatherDesc != null) weatherDesc.setText("正在定位...");

        new Thread(() -> {
            try {
                LocationTool locationTool = new LocationTool(getContext());
                java.util.Map<String, Object> params = new java.util.HashMap<>();
                params.put("action", "get_current");
                AIToolResult result = locationTool.execute(params);

                if (result.isSuccess()) {
                    Object resObj = result.getResult();
                    if (resObj instanceof Map) {
                        Map<?, ?> map = (Map<?, ?>) resObj;
                        String city = String.valueOf(map.get("city"));
                        if (city != null && !city.equals("未知") && !city.equals("null") && !city.isEmpty()) {
                            currentCity = city;
                        }
                        double lat = map.get("latitude") instanceof Number ? ((Number) map.get("latitude")).doubleValue() : 0;
                        double lon = map.get("longitude") instanceof Number ? ((Number) map.get("longitude")).doubleValue() : 0;
                        if (lat != 0 && lon != 0) {
                            cachedLat = lat;
                            cachedLon = lon;
                            post(() -> refreshWeatherByLocation(lat, lon));
                            return;
                        }
                    }
                }

                post(() -> refreshWeatherWithCity(currentCity));
            } catch (Exception e) {
                post(() -> refreshWeatherWithCity(currentCity));
            }
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
        if (weatherCity != null) weatherCity.setText(city);
        if (weatherTemp != null) weatherTemp.setText("--°C");
        if (weatherDesc != null) weatherDesc.setText("正在获取天气...");

        weatherService.getCurrentWeather(city).thenAccept(weather -> {
            post(() -> updateUI(weather));
        }).exceptionally(e -> {
            post(() -> {
                if (weatherDesc != null) weatherDesc.setText("获取失败");
            });
            return null;
        });
    }

    private void loadWeatherByLocation(double lat, double lon) {
        if (weatherCity != null) weatherCity.setText(currentCity);
        if (weatherTemp != null) weatherTemp.setText("--°C");
        if (weatherDesc != null) weatherDesc.setText("正在获取天气...");

        weatherService.getCurrentWeatherByLocation(lat, lon).thenAccept(weather -> {
            post(() -> updateUI(weather));
        }).exceptionally(e -> {
            post(() -> loadWeatherWithCity(currentCity));
            return null;
        });
    }

    private void refreshWeatherWithCity(String city) {
        if (weatherCity != null) weatherCity.setText(city);
        if (weatherTemp != null) weatherTemp.setText("--°C");
        if (weatherDesc != null) weatherDesc.setText("正在刷新...");

        weatherService.clearCacheForCity(city);
        weatherService.getCurrentWeather(city).thenAccept(weather -> {
            post(() -> updateUI(weather));
        }).exceptionally(e -> {
            post(() -> {
                if (weatherDesc != null) weatherDesc.setText("刷新失败");
            });
            return null;
        });
    }

    private void refreshWeatherByLocation(double lat, double lon) {
        if (weatherCity != null) weatherCity.setText(currentCity);
        if (weatherTemp != null) weatherTemp.setText("--°C");
        if (weatherDesc != null) weatherDesc.setText("正在刷新...");

        weatherService.getCurrentWeatherByLocation(lat, lon).thenAccept(weather -> {
            post(() -> updateUI(weather));
        }).exceptionally(e -> {
            post(() -> refreshWeatherWithCity(currentCity));
            return null;
        });
    }

    private void updateUI(String weatherText) {
        WeatherBannerManager.WeatherInfo info = parseWeather(weatherText);

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
        if (weatherTemp != null) weatherTemp.setText(info.temp);
        if (weatherDesc != null) weatherDesc.setText(info.description);
        if (weatherHumidity != null) weatherHumidity.setText("湿度: " + info.humidity);
        if (weatherWind != null) weatherWind.setText("风速: " + info.wind);
        if (weatherTempRange != null && info.tempRange != null && !info.tempRange.isEmpty()) {
            weatherTempRange.setText(info.tempRange);
            weatherTempRange.setVisibility(View.VISIBLE);
        }
        if (weatherForecast != null && info.forecast != null && !info.forecast.isEmpty()) {
            weatherForecast.setText(info.forecast);
            weatherForecast.setVisibility(View.VISIBLE);
        }

        loadForecastForBanner();
    }

    private void loadForecastForBanner() {
        if (currentCity == null || currentCity.equals("定位中...")) return;

        new Thread(() -> {
            try {
                String forecastText;
                if (cachedLat != 0 && cachedLon != 0) {
                    forecastText = weatherService.getForecastByLocation(cachedLat, cachedLon).get();
                } else {
                    forecastText = weatherService.getForecast(currentCity).get();
                }

                if (forecastText != null) {
                    WeatherBannerManager.WeatherInfo forecastInfo = parseForecast(forecastText);
                    post(() -> {
                        if (forecastInfo.tempRange != null && !forecastInfo.tempRange.isEmpty()) {
                            if (weatherTempRange != null) {
                                weatherTempRange.setText(forecastInfo.tempRange);
                                weatherTempRange.setVisibility(View.VISIBLE);
                            }
                        }
                        if (forecastInfo.forecast != null && !forecastInfo.forecast.isEmpty()) {
                            if (weatherForecast != null) {
                                weatherForecast.setText(forecastInfo.forecast);
                                weatherForecast.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    private WeatherBannerManager.WeatherInfo parseForecast(String forecastText) {
        WeatherBannerManager.WeatherInfo info = new WeatherBannerManager.WeatherInfo();
        if (forecastText == null) return info;

        try {
            StringBuilder forecastSummary = new StringBuilder();
            String highTemp = null;
            String lowTemp = null;

            String[] lines = forecastText.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("最高温度:") || line.startsWith("最高温:")) {
                    String val = line.substring(line.indexOf(":") + 1).trim();
                    if (highTemp == null) highTemp = val;
                }
                if (line.startsWith("最低温度:") || line.startsWith("最低温:")) {
                    String val = line.substring(line.indexOf(":") + 1).trim();
                    if (lowTemp == null) lowTemp = val;
                }
                if (line.startsWith("日期:")) {
                    String date = line.substring(3).trim();
                    forecastSummary.append(date).append(" ");
                }
                if (line.startsWith("白天天气:")) {
                    forecastSummary.append(line.substring(5).trim()).append(" ");
                }
                if (line.startsWith("最高温:") || line.startsWith("最高温度:")) {
                    forecastSummary.append("↑").append(line.substring(line.indexOf(":") + 1).trim()).append("°C ");
                }
                if (line.startsWith("最低温:") || line.startsWith("最低温度:")) {
                    forecastSummary.append("↓").append(line.substring(line.indexOf(":") + 1).trim()).append("°C");
                }
            }

            if (highTemp != null && lowTemp != null) {
                info.tempRange = "↓" + lowTemp + "°C ↑" + highTemp + "°C";
            } else if (highTemp != null) {
                info.tempRange = "↑" + highTemp + "°C";
            } else if (lowTemp != null) {
                info.tempRange = "↓" + lowTemp + "°C";
            }

            if (forecastSummary.length() > 0) {
                info.forecast = forecastSummary.toString();
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
                    info.temp = line.substring(3).trim();
                } else if (line.startsWith("湿度:")) {
                    info.humidity = line.substring(3).trim();
                } else if (line.startsWith("风速:")) {
                    info.wind = line.substring(3).trim();
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
