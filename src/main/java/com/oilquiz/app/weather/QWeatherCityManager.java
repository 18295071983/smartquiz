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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class QWeatherCityManager {

    private static final String TAG = "QWeatherCityManager";
    /** CSV 加载完成信号。CSV 约 460KB / 3500+ 城市，实测加载远快于该上限。
     *  LOAD-RACE(2026-10-11)：见 ensureLoaded 说明。 */
    private static final long LOAD_WAIT_MS = 3000;
    private static final String CITY_CSV_PATH = "China-City-List-latest.csv";

    private static QWeatherCityManager sInstance;

    private final List<CityEntry> allCities = new ArrayList<>();
    private final Map<String, CityEntry> cityById = new HashMap<>();
    private final Map<String, CityEntry> cityByName = new HashMap<>();
    private volatile boolean initialized = false;
    /** CSV 加载完成信号；读取方用 ensureLoaded() 等待，避免"未加载完就查表 → 空结果" */
    private CountDownLatch loadLatch;

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
        loadLatch = new CountDownLatch(1);

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
                        synchronized (allCities) {
                            allCities.add(entry);
                            cityById.put(entry.locationId, entry);
                            cityByName.put(entry.nameZh, entry);
                        }
                        count++;
                    }
                }
                reader.close();
                Log.i(TAG, "Loaded " + count + " cities from CSV");
            } catch (Exception e) {
                Log.w(TAG, "Failed to load city list: " + e.getMessage());
            } finally {
                // 无论成功失败都必须放行，否则等待方会白等到超时
                initialized = true;
                CountDownLatch latch = loadLatch;
                if (latch != null) latch.countDown();
            }
        }).start();
    }

    /**
     * 等待 CSV 加载完成（最多 {@link #LOAD_WAIT_MS}），供所有查表方法在读取前调用。
     *
     * <p><b>LOAD-RACE(2026-10-11) 修复的 bug</b>：CSV 由后台线程加载，而
     * {@code getCityById} / {@code getCityByName} / {@code getHotCities} 原先直接读 map、
     * 既不检查 {@code initialized} 也不等待；{@code searchCities} / {@code findCityNamesInMessage}
     * 则静默返回空列表。于是在加载完成前发起的天气查询会查不到城市，
     * 沿 {@code AIWeatherManager.resolveHefengLocationParam} 一路回落到和风 geo API
     * （该端点无 JWT 权限时 403）→ 表现为"天气详情页正常、Agent 偶尔查不到"。
     * 本方法让"查表"具备确定性：要么拿到完整表，要么明确超时（日志可见）。
     */
    private void ensureLoaded() {
        CountDownLatch latch = loadLatch;
        if (latch == null || initialized) return;
        try {
            if (!latch.await(LOAD_WAIT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "City list not loaded within " + LOAD_WAIT_MS + "ms, lookups may miss");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
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
        ensureLoaded();
        return cityById.get(locationId);
    }

    public CityEntry getCityByName(String cityName) {
        if (cityName == null) return null;
        ensureLoaded();
        return cityByName.get(cityName);
    }

    public List<CityEntry> searchCities(String keyword) {
        List<CityEntry> result = new ArrayList<>();
        if (keyword == null || keyword.isEmpty()) return result;
        ensureLoaded();

        String lower = keyword.toLowerCase();
        // 快照：与加载线程的写入解耦，避免边加载边迭代
        List<CityEntry> snapshot;
        synchronized (allCities) {
            snapshot = new ArrayList<>(allCities);
        }
        for (CityEntry city : snapshot) {
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

    /**
     * 在用户消息中查找命中的城市名（按长度降序，最长匹配优先）。
     * 用于天气意图提取：直接把用户消息与内置城市表比对，避免正则提取把
     * "查询下/看一下/今天"等动词·时间词吞进地名（如"查询下五台县的天气"→"五台县"）。
     */
    public List<String> findCityNamesInMessage(String message) {
        List<String> result = new ArrayList<>();
        if (message == null || message.isEmpty()) return result;
        // 原实现检查 !initialized 后静默返回空 → 加载期间"用户明说了城市"也会被漏掉，
        // 进而回落到位置兜底查了**用户所在城市**。改为等加载完成（LOAD-RACE 修复）。
        ensureLoaded();
        List<CityEntry> snapshot;
        synchronized (allCities) {
            snapshot = new ArrayList<>(allCities);
        }
        for (CityEntry city : snapshot) {
            String name = city.nameZh;
            if (name != null && name.length() >= 2 && message.contains(name)) {
                result.add(name);
            }
        }
        result.sort((a, b) -> b.length() - a.length());
        return result;
    }

    public List<CityEntry> getHotCities() {
        String[] hotCityNames = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "西安",
                "南京", "重庆", "天津", "苏州", "长沙", "郑州", "青岛", "大连"};
        List<CityEntry> result = new ArrayList<>();
        ensureLoaded();
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
