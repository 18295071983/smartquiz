package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.util.APIKeyManager;
import com.oilquiz.app.ui.widget.WeatherChartView;
import com.oilquiz.app.util.QWeatherIconMapper;
import com.oilquiz.app.util.WeatherIconManager;
import android.graphics.drawable.Drawable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class WeatherDetailActivity extends AppCompatActivity {

    private static final String TAG = "WeatherDetail";
    private static final String API_HOST = "https://m278m2y7ak.re.qweatherapi.com";
    private static final String DEFAULT_API_KEY = "be2af1f8490344feb8a7125ab46608dd";

    private String city;
    private double lat = 0;
    private double lon = 0;
    private boolean hasLocation = false;
    private String apiKey;
    private String fxLink;

    private TextView tvCity;
    private TextView tvCurrentTemp;
    private TextView tvCurrentDesc;
    private ImageView ivCurrentIcon;
    private TextView tvHumidity;
    private TextView tvWind;
    private TextView tvPressure;
    private TextView tvVisibility;
    private TextView tvFeelsLike;

    private WeatherIconManager iconManager;

    private LinearLayout hourlyContainer;
    private LinearLayout forecastContainer;
    private LinearLayout airContainer;
    private LinearLayout alertsContainer;
    private GridView indicesGrid;

    private WeatherChartView weatherChart;

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

        apiKey = DEFAULT_API_KEY;
        APIKeyManager apiKeyManager = APIKeyManager.getInstance(this);
        String savedKey = apiKeyManager.getAPIKey(APIKeyManager.Service.HEFENG_WEATHER);
        if (savedKey != null && !savedKey.isEmpty()) {
            apiKey = savedKey;
        }

        initViews();
        loadAllWeatherData();
    }

    private void initViews() {
        tvCity = findViewById(R.id.tv_city);
        tvCurrentTemp = findViewById(R.id.tv_current_temp);
        tvCurrentDesc = findViewById(R.id.tv_current_desc);
        ivCurrentIcon = findViewById(R.id.iv_current_icon);
        tvHumidity = findViewById(R.id.tv_humidity);
        tvWind = findViewById(R.id.tv_wind);
        tvPressure = findViewById(R.id.tv_pressure);
        tvVisibility = findViewById(R.id.tv_visibility);
        tvFeelsLike = findViewById(R.id.tv_feels_like);

        hourlyContainer = findViewById(R.id.hourly_container);
        forecastContainer = findViewById(R.id.forecast_container);
        airContainer = findViewById(R.id.air_container);
        alertsContainer = findViewById(R.id.alerts_container);
        indicesGrid = findViewById(R.id.grid_indices);

        weatherChart = findViewById(R.id.weather_chart);

        iconManager = WeatherIconManager.getInstance(this);

        ImageView backBtn = findViewById(R.id.btn_back);
        backBtn.setOnClickListener(v -> finish());

        ImageView alertExpandBtn = findViewById(R.id.iv_alert_expand);
        alertExpandBtn.setOnClickListener(v -> toggleAlertExpand());

        com.google.android.material.button.MaterialButton btnOpenWeb = findViewById(R.id.btn_open_web);
        btnOpenWeb.setOnClickListener(v -> {
            if (fxLink != null && !fxLink.isEmpty()) {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(fxLink)));
            }
        });
    }

    private void toggleAlertExpand() {
        View alertDetails = findViewById(R.id.alert_details);
        ImageView expandIcon = findViewById(R.id.iv_alert_expand);

        if (alertDetails.getVisibility() == View.VISIBLE) {
            alertDetails.setVisibility(View.GONE);
            expandIcon.setRotation(0);
        } else {
            alertDetails.setVisibility(View.VISIBLE);
            expandIcon.setRotation(180);
        }
    }

    private String getLocationParam() {
        if (hasLocation) {
            return String.format(java.util.Locale.US, "%.2f,%.2f", lon, lat);
        }
        try {
            return URLEncoder.encode(city, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return city;
        }
    }

    private void loadAllWeatherData() {
        loadCurrentWeather();
        loadHourlyForecast();
        loadDailyForecast();
        loadAlerts();
        loadIndices();
        if (airContainer != null) airContainer.setVisibility(View.GONE);
    }

    private String httpGet(String urlString) throws Exception {
        java.net.URL url = new java.net.URL(urlString);
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("X-QW-Api-Key", apiKey);
        connection.setRequestProperty("Accept-Encoding", "gzip, deflate");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP " + responseCode);
        }

        String encoding = connection.getContentEncoding();
        java.io.InputStream inputStream;
        if ("gzip".equalsIgnoreCase(encoding)) {
            inputStream = new java.util.zip.GZIPInputStream(connection.getInputStream());
        } else if ("deflate".equalsIgnoreCase(encoding)) {
            inputStream = new java.util.zip.InflaterInputStream(connection.getInputStream());
        } else {
            inputStream = connection.getInputStream();
        }

        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(inputStream, "UTF-8"));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        connection.disconnect();
        return response.toString();
    }

    private com.google.gson.Gson gson = new com.google.gson.Gson();

    private void loadCurrentWeather() {
        CompletableFuture.runAsync(() -> {
            try {
                String location = getLocationParam();
                String url = API_HOST + "/v7/weather/now?location=" + location;
                String response = httpGet(url);
                JsonObject json = gson.fromJson(response, JsonObject.class);

                if (!"200".equals(json.get("code").getAsString())) {
                    Log.e(TAG, "Weather now failed: " + json.get("code"));
                    return;
                }

                JsonObject now = json.getAsJsonObject("now");
                final String finalCityName;
                if (json.has("location") && !json.get("location").isJsonNull()) {
                    JsonObject loc = json.getAsJsonObject("location");
                    if (loc.has("name")) {
                        finalCityName = loc.get("name").getAsString();
                    } else {
                        finalCityName = city;
                    }
                } else {
                    finalCityName = city;
                }

                String weather = now.get("text").getAsString();
                String icon = now.has("icon") ? now.get("icon").getAsString() : "";
                String temp = now.get("temp").getAsString();
                String humidity = now.get("humidity").getAsString();
                String windSpeed = now.get("windSpeed").getAsString();
                String windDir = now.get("windDir").getAsString();
                String feelsLike = now.get("feelsLike").getAsString();
                String vis = now.get("vis").getAsString();
                String pressure = now.has("pressure") ? now.get("pressure").getAsString() : "--";

                if (json.has("fxLink") && !json.get("fxLink").isJsonNull()) {
                    fxLink = json.get("fxLink").getAsString();
                }

                runOnUiThread(() -> {
                    tvCity.setText(finalCityName);
                    tvCurrentTemp.setText(temp + "°C");
                    tvCurrentDesc.setText(weather);
                    tvHumidity.setText("湿度 " + humidity + "%");
                    tvWind.setText("风速 " + windSpeed + " km/h");
                    tvFeelsLike.setText("体感温度 " + feelsLike + "°C");
                    tvVisibility.setText("能见度 " + vis + " km");
                    tvPressure.setText("气压 " + pressure + " hPa");

                    if (!icon.isEmpty()) {
                        String emoji = QWeatherIconMapper.getEmojiIcon(icon);
                        if (ivCurrentIcon != null) ivCurrentIcon.setImageDrawable(null);
                        tvCurrentDesc.setText(emoji + " " + weather);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading current weather", e);
            }
        });
    }

    private void loadHourlyForecast() {
        CompletableFuture.runAsync(() -> {
            try {
                String location = getLocationParam();
                String url = API_HOST + "/v7/weather/hourly?location=" + location;
                String response = httpGet(url);
                JsonObject json = gson.fromJson(response, JsonObject.class);

                if (!"200".equals(json.get("code").getAsString())) {
                    return;
                }

                JsonArray hourlyArray = json.getAsJsonArray("hourly");
                if (hourlyArray == null) return;

                List<Integer> temps = new ArrayList<>();
                List<String> times = new ArrayList<>();
                List<String> icons = new ArrayList<>();

                int count = Math.min(24, hourlyArray.size());
                for (int i = 0; i < count; i++) {
                    JsonObject h = hourlyArray.get(i).getAsJsonObject();
                    String fxTime = h.get("fxTime").getAsString();
                    String hour = fxTime.substring(11, 16);
                    String tempStr = h.get("temp").getAsString();
                    String iconCode = h.has("icon") ? h.get("icon").getAsString() : "";

                    times.add(hour);
                    icons.add(iconCode);
                    try {
                        temps.add(Integer.parseInt(tempStr));
                    } catch (NumberFormatException e) {
                        temps.add(0);
                    }
                }

                runOnUiThread(() -> {
                    hourlyContainer.removeAllViews();
                    for (int i = 0; i < times.size(); i++) {
                        View hourItem = getLayoutInflater().inflate(R.layout.item_hourly, hourlyContainer, false);
                        TextView tvHour = hourItem.findViewById(R.id.tv_hour);
                        TextView tvHourIcon = hourItem.findViewById(R.id.tv_hour_icon);
                        TextView tvHourTemp = hourItem.findViewById(R.id.tv_hour_temp);

                        tvHour.setText(times.get(i));
                        String emoji = QWeatherIconMapper.getEmojiIcon(icons.get(i));
                        tvHourIcon.setText(emoji);
                        tvHourTemp.setText(temps.get(i) + "°C");

                        hourlyContainer.addView(hourItem);
                    }

                    if (!temps.isEmpty() && !times.isEmpty()) {
                        weatherChart.setData(temps, times);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading hourly forecast", e);
            }
        });
    }

    private void loadDailyForecast() {
        CompletableFuture.runAsync(() -> {
            try {
                String location = getLocationParam();
                String url = API_HOST + "/v7/weather/7d?location=" + location;
                String response = httpGet(url);
                JsonObject json = gson.fromJson(response, JsonObject.class);

                if (!"200".equals(json.get("code").getAsString())) {
                    return;
                }

                JsonArray dailyArray = json.getAsJsonArray("daily");
                if (dailyArray == null) return;

                List<String> dates = new ArrayList<>();
                List<String> descs = new ArrayList<>();
                List<String> highTemps = new ArrayList<>();
                List<String> lowTemps = new ArrayList<>();
                List<String> iconDays = new ArrayList<>();

                for (int i = 0; i < dailyArray.size(); i++) {
                    JsonObject d = dailyArray.get(i).getAsJsonObject();
                    dates.add(d.get("fxDate").getAsString());
                    descs.add(d.get("textDay").getAsString());
                    highTemps.add(d.get("tempMax").getAsString());
                    lowTemps.add(d.get("tempMin").getAsString());
                    iconDays.add(d.has("iconDay") ? d.get("iconDay").getAsString() : "");
                }

                runOnUiThread(() -> {
                    forecastContainer.removeAllViews();
                    for (int i = 0; i < dates.size(); i++) {
                        addForecastItem(dates.get(i), descs.get(i), highTemps.get(i), lowTemps.get(i), iconDays.get(i));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading daily forecast", e);
            }
        });
    }

    private void addForecastItem(String date, String desc, String high, String low, String iconDay) {
        View item = getLayoutInflater().inflate(R.layout.item_forecast, forecastContainer, false);
        TextView tvDate = item.findViewById(R.id.tv_date);
        TextView tvDesc = item.findViewById(R.id.tv_desc);
        TextView tvIcon = item.findViewById(R.id.tv_icon);
        TextView tvTemp = item.findViewById(R.id.tv_temp);

        tvDate.setText(date);
        tvDesc.setText(desc);
        if (!iconDay.isEmpty()) {
            tvIcon.setText(QWeatherIconMapper.getEmojiIcon(iconDay));
        } else {
            tvIcon.setText(getWeatherIconFromDesc(desc));
        }
        tvTemp.setText(low + "°C ~ " + high + "°C");

        forecastContainer.addView(item);
    }

    private void loadAirQuality() {
        CompletableFuture.runAsync(() -> {
            try {
                String location = getLocationParam();
                String url = API_HOST + "/v7/air/now?location=" + location;
                String response = httpGet(url);
                JsonObject json = gson.fromJson(response, JsonObject.class);

                if (!"200".equals(json.get("code").getAsString())) {
                    return;
                }

                JsonObject now = json.getAsJsonObject("now");
                if (now == null) return;

                String aqi = now.has("aqi") ? now.get("aqi").getAsString() : "--";
                String category = now.has("category") ? now.get("category").getAsString() : "--";
                String pm25 = now.has("pm2p5") ? now.get("pm2p5").getAsString() : "--";
                String pm10 = now.has("pm10") ? now.get("pm10").getAsString() : "--";
                String so2 = now.has("so2") ? now.get("so2").getAsString() : "--";
                String no2 = now.has("no2") ? now.get("no2").getAsString() : "--";

                runOnUiThread(() -> {
                    TextView tvAqi = findViewById(R.id.tv_aqi);
                    TextView tvAqiLevel = findViewById(R.id.tv_aqi_level);
                    TextView tvAqiDesc = findViewById(R.id.tv_aqi_desc);
                    TextView tvPm25 = findViewById(R.id.tv_pm25);
                    TextView tvPm10 = findViewById(R.id.tv_pm10);
                    TextView tvSo2 = findViewById(R.id.tv_so2);
                    TextView tvNo2 = findViewById(R.id.tv_no2);

                    tvAqi.setText(aqi);
                    tvAqiLevel.setText(category);
                    tvAqiDesc.setText(getAqiDescription(category));
                    tvPm25.setText("PM2.5 " + pm25);
                    tvPm10.setText("PM10 " + pm10);
                    tvSo2.setText("SO2 " + so2);
                    tvNo2.setText("NO2 " + no2);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading air quality", e);
            }
        });
    }

    private void loadAlerts() {
        CompletableFuture.runAsync(() -> {
            try {
                String location = getLocationParam();
                String url = API_HOST + "/v7/warning/now?location=" + location;
                String response = httpGet(url);
                JsonObject json = gson.fromJson(response, JsonObject.class);

                if (!"200".equals(json.get("code").getAsString())) {
                    return;
                }

                JsonArray warningArray = json.getAsJsonArray("warning");
                if (warningArray == null || warningArray.size() == 0) {
                    runOnUiThread(() -> alertsContainer.setVisibility(View.GONE));
                    return;
                }

                JsonObject firstAlert = warningArray.get(0).getAsJsonObject();
                String severity = firstAlert.has("severity") ? firstAlert.get("severity").getAsString() : "未知";
                String severityColor = firstAlert.has("severityColor") ? firstAlert.get("severityColor").getAsString() : "";
                String title = firstAlert.has("title") ? firstAlert.get("title").getAsString() : "";
                String desc = firstAlert.has("text") ? firstAlert.get("text").getAsString() : "";
                String startTime = firstAlert.has("startTime") ? firstAlert.get("startTime").getAsString() : "";

                String levelDisplay = severityColor.isEmpty() ? severity : severityColor + "色" + severity + "预警";

                runOnUiThread(() -> {
                    alertsContainer.setVisibility(View.VISIBLE);
                    TextView tvAlertLevel = findViewById(R.id.tv_alert_level);
                    TextView tvAlertContent = findViewById(R.id.tv_alert_content);
                    TextView tvAlertTime = findViewById(R.id.tv_alert_time);

                    tvAlertLevel.setText(levelDisplay);
                    tvAlertContent.setText(desc);
                    tvAlertTime.setText("发布时间: " + startTime);
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading alerts", e);
                runOnUiThread(() -> alertsContainer.setVisibility(View.GONE));
            }
        });
    }

    private void loadIndices() {
        CompletableFuture.runAsync(() -> {
            try {
                String location = getLocationParam();
                String url = API_HOST + "/v7/indices/1d?location=" + location;
                String response = httpGet(url);
                JsonObject json = gson.fromJson(response, JsonObject.class);

                if (!"200".equals(json.get("code").getAsString())) {
                    return;
                }

                JsonArray dailyArray = json.getAsJsonArray("daily");
                if (dailyArray == null) return;

                List<IndexItem> indexList = new ArrayList<>();
                for (int i = 0; i < dailyArray.size(); i++) {
                    JsonObject idx = dailyArray.get(i).getAsJsonObject();
                    String name = idx.has("name") ? idx.get("name").getAsString() : 
                                 (idx.has("type") ? idx.get("type").getAsString() : "");
                    String category = idx.has("category") ? idx.get("category").getAsString() : "";
                    if (!name.isEmpty()) {
                        indexList.add(new IndexItem(name, category));
                    }
                }

                runOnUiThread(() -> {
                    if (indexList.isEmpty()) {
                        indicesGrid.setVisibility(View.GONE);
                    } else {
                        indicesGrid.setAdapter(new IndicesAdapter(indexList));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading indices", e);
            }
        });
    }

    private String getAqiDescription(String level) {
        switch (level) {
            case "优": return "空气质量令人满意，基本无污染";
            case "良": return "空气质量可接受，某些污染物可能对敏感人群有影响";
            case "轻度污染": return "易感人群症状有轻度加剧";
            case "中度污染": return "进一步加剧易感人群症状";
            case "重度污染": return "健康人群出现明显症状";
            case "严重污染": return "所有人的健康都会受到严重影响";
            default: return "空气质量描述";
        }
    }

    private String getWeatherIcon(String iconCode) {
        return com.oilquiz.app.util.QWeatherIconMapper.getEmojiIcon(iconCode);
    }

    private String getWeatherIconFromDesc(String desc) {
        if (desc == null) return "🌤️";
        return com.oilquiz.app.util.QWeatherIconMapper.getEmojiIcon("999");
    }

    private static class IndexItem {
        String name;
        String value;

        IndexItem(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    private class IndicesAdapter extends BaseAdapter {
        private List<IndexItem> items;

        IndicesAdapter(List<IndexItem> items) {
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(WeatherDetailActivity.this)
                        .inflate(R.layout.item_index, parent, false);
            }

            IndexItem item = items.get(position);
            TextView tvName = convertView.findViewById(R.id.tv_index_name);
            TextView tvValue = convertView.findViewById(R.id.tv_index_value);

            tvName.setText(item.name);
            tvValue.setText(item.value);

            return convertView;
        }
    }
}
