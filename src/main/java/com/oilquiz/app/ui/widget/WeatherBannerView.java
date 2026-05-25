package com.oilquiz.app.ui.widget;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
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
    private TextView weatherFeelsLike;
    private TextView weatherVisibility;
    private HorizontalScrollView hsvForecast;
    private View refreshButton;
    private View closeButton;

    private WeatherService weatherService;
    private String currentCity = "北京";
    private boolean autoLoad = true;
    private boolean locationPermissionGranted = false;
    private double cachedLat = 0;
    private double cachedLon = 0;
    private String cachedFxLink = "";

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
        weatherFeelsLike = findViewById(R.id.weather_feels_like);
        weatherVisibility = findViewById(R.id.weather_visibility);
        hsvForecast = findViewById(R.id.hsv_forecast);
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
        android.content.Intent intent;
        if (cachedFxLink != null && !cachedFxLink.isEmpty()) {
            intent = new android.content.Intent(getContext(), com.oilquiz.app.ui.activity.WeatherWebViewActivity.class);
            intent.putExtra("url", cachedFxLink);
            intent.putExtra("title", city + " - 天气详情");
        } else {
            intent = new android.content.Intent(getContext(),
                com.oilquiz.app.ui.activity.WeatherDetailActivity.class);
            intent.putExtra("city", city);
            if (cachedLat != 0 && cachedLon != 0) {
                intent.putExtra("lat", cachedLat);
                intent.putExtra("lon", cachedLon);
            }
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

                if (result != null && result.isSuccess()) {
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
            } catch (Exception ignored) {
            }

            post(() -> loadWeatherWithCity(currentCity));
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

                if (result != null && result.isSuccess()) {
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
            } catch (Exception ignored) {
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
        if (weatherTemp != null) weatherTemp.setText(info.temp);
        if (weatherDesc != null) weatherDesc.setText(info.description);
        if (weatherHumidity != null) weatherHumidity.setText("湿度: " + info.humidity);
        if (weatherWind != null) weatherWind.setText("风速: " + info.wind);
        if (weatherFeelsLike != null) weatherFeelsLike.setText("体感: " + info.feelsLike + "°C");
        if (weatherVisibility != null) weatherVisibility.setText("能见度: " + info.visibility + " km");
        if (weatherTempRange != null && info.tempRange != null && !info.tempRange.isEmpty()) {
            weatherTempRange.setText(info.tempRange);
            weatherTempRange.setVisibility(View.VISIBLE);
        }
        if (weatherForecast != null && info.forecast != null && !info.forecast.isEmpty()) {
            weatherForecast.setText(info.forecast);
            weatherForecast.setVisibility(View.VISIBLE);
            weatherForecast.setSelected(true);
            startMarquee(weatherForecast);
        }

        loadForecastForBanner();
    }

    private void startMarquee(final TextView textView) {
        textView.post(() -> {
            textView.setSelected(true);
            textView.requestFocus();
        });
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
                                startMarquee(weatherForecast);
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
                    info.temp = line.substring(3).trim();
                } else if (line.startsWith("湿度:")) {
                    info.humidity = line.substring(3).trim();
                } else if (line.startsWith("风速:")) {
                    info.wind = line.substring(3).trim();
                } else if (line.startsWith("体感温度:")) {
                    info.feelsLike = line.substring(5).trim().replace("°C", "").replace("°", "");
                } else if (line.startsWith("能见度:")) {
                    info.visibility = line.substring(4).trim().replace(" km", "");
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
