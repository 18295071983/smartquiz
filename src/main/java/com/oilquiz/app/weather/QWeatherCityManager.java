package com.oilquiz.app.weather;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QWeatherCityManager {

    private static final String TAG = "QWeatherCityManager";
    private static final String CITY_CSV_PATH = "China-City-List-latest.csv";

    private static QWeatherCityManager sInstance;

    private final List<CityEntry> allCities = new ArrayList<>();
    private final Map<String, CityEntry> cityById = new HashMap<>();
    private final Map<String, CityEntry> cityByName = new HashMap<>();
    private boolean initialized = false;

    public static class CityEntry {
        public String locationId;
        public String nameEn;
        public String nameZh;
        public String countryEn;
        public String countryZh;
        public String adm1En;
        public String adm1Zh;
        public String adm2En;
        public String adm2Zh;
        public double latitude;
        public double longitude;
        public String adCode;

        public String getFullName() {
            StringBuilder sb = new StringBuilder();
            if (adm1Zh != null && !adm1Zh.isEmpty() && !adm1Zh.equals(adm2Zh)) {
                sb.append(adm1Zh);
            }
            if (adm2Zh != null && !adm2Zh.isEmpty() && !adm2Zh.equals(nameZh)) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(adm2Zh);
            }
            if (nameZh != null && !nameZh.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(nameZh);
            }
            return sb.length() > 0 ? sb.toString() : nameZh;
        }
    }

    private QWeatherCityManager() {}

    public static synchronized QWeatherCityManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new QWeatherCityManager();
            sInstance.init(context.getApplicationContext());
        }
        return sInstance;
    }

    private void init(Context context) {
        if (initialized) return;

        new Thread(() -> {
            try {
                InputStream is = context.getAssets().open(CITY_CSV_PATH);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                String line;
                boolean firstLine = true;
                int count = 0;

                while ((line = reader.readLine()) != null) {
                    if (firstLine) {
                        firstLine = false;
                        if (line.startsWith("China-City-List")) {
                            firstLine = true;
                        }
                        continue;
                    }

                    CityEntry entry = parseCsvLine(line);
                    if (entry != null && entry.locationId != null) {
                        allCities.add(entry);
                        cityById.put(entry.locationId, entry);
                        cityByName.put(entry.nameZh, entry);
                        count++;
                    }
                }
                reader.close();
                initialized = true;
                Log.i(TAG, "Loaded " + count + " cities from CSV");
            } catch (Exception e) {
                Log.w(TAG, "Failed to load city list: " + e.getMessage());
                initialized = true;
            }
        }).start();
    }

    private CityEntry parseCsvLine(String line) {
        if (line == null || line.trim().isEmpty()) return null;

        String[] parts = line.split(",");
        if (parts.length < 14) return null;

        try {
            CityEntry entry = new CityEntry();
            entry.locationId = parts[0].trim();
            entry.nameEn = parts[1].trim();
            entry.nameZh = parts[2].trim();
            entry.countryEn = parts[4].trim();
            entry.countryZh = parts[5].trim();
            entry.adm1En = parts[6].trim();
            entry.adm1Zh = parts[7].trim();
            entry.adm2En = parts[8].trim();
            entry.adm2Zh = parts[9].trim();
            entry.latitude = parseDouble(parts[11]);
            entry.longitude = parseDouble(parts[12]);
            entry.adCode = parts.length > 13 ? parts[13].trim() : "";
            return entry;
        } catch (Exception e) {
            return null;
        }
    }

    private double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public CityEntry getCityById(String locationId) {
        if (locationId == null) return null;
        return cityById.get(locationId);
    }

    public CityEntry getCityByName(String cityName) {
        if (cityName == null) return null;
        return cityByName.get(cityName);
    }

    public List<CityEntry> searchCities(String keyword) {
        List<CityEntry> result = new ArrayList<>();
        if (keyword == null || keyword.isEmpty()) return result;

        String lower = keyword.toLowerCase();
        for (CityEntry city : allCities) {
            if (city.nameZh.contains(keyword) ||
                city.nameEn.toLowerCase().contains(lower) ||
                city.adm1Zh.contains(keyword) ||
                city.adm2Zh.contains(keyword)) {
                result.add(city);
                if (result.size() >= 20) break;
            }
        }
        return result;
    }

    public List<CityEntry> getHotCities() {
        String[] hotCityNames = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安",
                "南京", "重庆", "天津", "苏州", "长沙", "郑州", "青岛", "大连"};
        List<CityEntry> result = new ArrayList<>();
        for (String name : hotCityNames) {
            CityEntry city = cityByName.get(name);
            if (city != null) result.add(city);
        }
        return result;
    }

    public boolean isReady() {
        return initialized;
    }
}
