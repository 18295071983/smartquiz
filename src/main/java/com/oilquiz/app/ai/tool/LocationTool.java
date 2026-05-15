package com.oilquiz.app.ai.tool;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;

import com.oilquiz.app.util.AILogger;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class LocationTool implements AITool {
    private static final String TAG = "LocationTool";
    private static final long LOCATION_TIMEOUT_MS = 10000;
    private final Context context;
    private final Handler mainHandler;

    public LocationTool(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public String getName() {
        return "location";
    }

    @Override
    public String getDescription() {
        return "定位工具，获取用户当前位置信息（经纬度、城市名等）";
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = (String) parameters.getOrDefault("action", "get_current");
            switch (action) {
                case "get_current":
                    return getCurrentLocation();
                case "get_city":
                    return getCurrentCity();
                case "get_coordinates":
                    return getCoordinates();
                default:
                    return new AIToolResult("未知操作: " + action, parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "定位工具执行出错: " + e.getMessage(), e);
            return new AIToolResult("定位失败: " + e.getMessage(), parameters);
        }
    }

    private AIToolResult getCurrentLocation() {
        LocationInfo locationInfo = requestLocation();
        if (locationInfo == null) {
            Map<String, Object> info = new HashMap<>();
            info.put("error", "无法获取位置信息，请检查定位权限是否已授予");
            info.put("permission_required", true);
            return new AIToolResult("无法获取位置信息", info);
        }

        String cityName = getCityName(locationInfo.latitude, locationInfo.longitude);

        Map<String, Object> result = new HashMap<>();
        result.put("latitude", locationInfo.latitude);
        result.put("longitude", locationInfo.longitude);
        result.put("accuracy", locationInfo.accuracy);
        result.put("city", cityName != null ? cityName : "未知");
        result.put("provider", locationInfo.provider);
        result.put("timestamp", locationInfo.timestamp);

        return new AIToolResult(result, new HashMap<>());
    }

    private AIToolResult getCurrentCity() {
        LocationInfo locationInfo = requestLocation();
        if (locationInfo == null) {
            return new AIToolResult("无法获取位置信息", new HashMap<>());
        }

        String cityName = getCityName(locationInfo.latitude, locationInfo.longitude);
        Map<String, Object> result = new HashMap<>();
        result.put("city", cityName != null ? cityName : "未知");
        result.put("latitude", locationInfo.latitude);
        result.put("longitude", locationInfo.longitude);

        return new AIToolResult(result, new HashMap<>());
    }

    private AIToolResult getCoordinates() {
        LocationInfo locationInfo = requestLocation();
        if (locationInfo == null) {
            return new AIToolResult("无法获取位置信息", new HashMap<>());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("latitude", locationInfo.latitude);
        result.put("longitude", locationInfo.longitude);
        result.put("accuracy", locationInfo.accuracy);

        return new AIToolResult(result, new HashMap<>());
    }

    private LocationInfo requestLocation() {
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            AILogger.e(TAG, "LocationManager is null");
            return null;
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            AILogger.w(TAG, "Location permission not granted");
            return getLastKnownLocation(locationManager);
        }

        LocationInfo lastKnown = getLastKnownLocation(locationManager);
        if (lastKnown != null && (System.currentTimeMillis() - lastKnown.timestamp) < 300000) {
            return lastKnown;
        }

        AtomicReference<LocationInfo> resultRef = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);

        LocationListener locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                resultRef.set(new LocationInfo(location.getLatitude(), location.getLongitude(),
                        location.getAccuracy(), location.getProvider(), location.getTime()));
                latch.countDown();
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {
                latch.countDown();
            }
        };

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, Looper.getMainLooper());
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, locationListener, Looper.getMainLooper());
            }

            boolean received = latch.await(LOCATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            locationManager.removeUpdates(locationListener);

            if (received && resultRef.get() != null) {
                return resultRef.get();
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Request location error: " + e.getMessage(), e);
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        }

        return getLastKnownLocation(locationManager);
    }

    private LocationInfo getLastKnownLocation(LocationManager locationManager) {
        try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return null;
            }

            Location bestLocation = null;
            List<String> providers = locationManager.getProviders(true);
            for (String provider : providers) {
                Location location = locationManager.getLastKnownLocation(provider);
                if (location != null) {
                    if (bestLocation == null || location.getAccuracy() < bestLocation.getAccuracy()) {
                        bestLocation = location;
                    }
                }
            }

            if (bestLocation != null) {
                return new LocationInfo(bestLocation.getLatitude(), bestLocation.getLongitude(),
                        bestLocation.getAccuracy(), bestLocation.getProvider(), bestLocation.getTime());
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Get last known location error: " + e.getMessage(), e);
        }
        return null;
    }

    private String getCityName(double latitude, double longitude) {
        if (android.location.Geocoder.isPresent()) {
            try {
                Geocoder geocoder = new Geocoder(context, Locale.CHINA);
                List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    String city = address.getLocality();
                    if (city == null) city = address.getSubLocality();
                    if (city == null) city = address.getAdminArea();
                    if (city != null) return city;
                }
            } catch (Exception e) {
                AILogger.w(TAG, "Geocoder failed, falling back to GeoAPI: " + e.getMessage());
            }
        }

        return getCityNameFromGeoAPI(latitude, longitude);
    }

    private String getCityNameFromGeoAPI(double latitude, double longitude) {
        try {
            com.oilquiz.app.ai.util.APIKeyManager apiKeyManager = com.oilquiz.app.ai.util.APIKeyManager.getInstance(context);
            String apiKey = apiKeyManager.getAPIKey(com.oilquiz.app.ai.util.APIKeyManager.Service.HEFENG_WEATHER);
            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = "be2af1f8490344feb8a7125ab46608dd";
            }

            String location = String.format(java.util.Locale.US, "%.2f,%.2f", longitude, latitude);
            String urlString = "https://m278m2y7ak.re.qweatherapi.com/v2/city/lookup?location=" + location;
            java.net.URL url = new java.net.URL(urlString);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("X-QW-Api-Key", apiKey);
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            java.io.InputStream inputStream;
            String encoding = connection.getContentEncoding();
            if ("gzip".equalsIgnoreCase(encoding)) {
                inputStream = new java.util.zip.GZIPInputStream(connection.getInputStream());
            } else if ("deflate".equalsIgnoreCase(encoding)) {
                inputStream = new java.util.zip.InflaterInputStream(connection.getInputStream());
            } else {
                inputStream = connection.getInputStream();
            }

            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            org.json.JSONObject jsonObject = new org.json.JSONObject(response.toString());
            if ("200".equals(jsonObject.optString("code"))) {
                org.json.JSONArray locationArray = jsonObject.optJSONArray("location");
                if (locationArray != null && locationArray.length() > 0) {
                    return locationArray.getJSONObject(0).optString("name", null);
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "GeoAPI city lookup failed: " + e.getMessage());
        }
        return null;
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: get_current(获取完整位置), get_city(获取城市), get_coordinates(获取坐标)");
        return descriptions;
    }

    public static boolean hasLocationPermission(Context context) {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static class LocationInfo {
        final double latitude;
        final double longitude;
        final float accuracy;
        final String provider;
        final long timestamp;

        LocationInfo(double latitude, double longitude, float accuracy, String provider, long timestamp) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracy = accuracy;
            this.provider = provider;
            this.timestamp = timestamp;
        }
    }
}
