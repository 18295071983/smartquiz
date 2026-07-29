package com.oilquiz.app.ai.tool;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.ai.tool.annotation.ToolDependencies;
import com.oilquiz.app.ai.tool.annotation.Dependency;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.CancellationSignal;

import androidx.core.app.ActivityCompat;

import com.oilquiz.app.resource.PermissionResourceProvider;
import com.oilquiz.app.util.AILogger;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

@Tool(
    value = "location",
    description = "定位工具，获取用户当前位置信息（经纬度、城市名等）",
    category = "location",
    aliases = {"get_location", "get_current_location", "get_city", "get_coordinates"},
    actions = {
        @Action(name = "get_current", description = "获取当前位置经纬度",
            params = {}),
        @Action(name = "get_city", description = "获取当前城市名称",
            params = {}),
        @Action(name = "get_coordinates", description = "获取经纬度坐标",
            params = {})
    }
)
@ToolDependencies({
    @Dependency(
        tool = "permission_manager",
        action = "request_and_wait",
        condition = "permission_not_granted",
        conditionParam = "位置",
        argsTemplate = "{\"permission\": \"位置\"}",
        blockOnFailure = false,
        description = "定位需要位置权限，未授予时自动请求"
    )
})
public class LocationTool implements AITool {
    private static final String TAG = "LocationTool";
    private static final long LOCATION_TIMEOUT_MS = 8000;
    private static final long RETRY_DELAY_MS = 300;
    private static final int MAX_RETRY_ATTEMPTS = 1;
    private final Context context;
    private final Handler mainHandler;

    public static class LocationProviderType {
        public static final String GPS = "gps";
        public static final String NETWORK = "network";
        public static final String PASSIVE = "passive";
        public static final String LAST_KNOWN = "last_known";
        public static final String GEO_API = "geo_api";
    }

    public static class SmartLocationResult {
        public boolean success;
        public LocationInfo location;
        public String providerUsed;
        public int attempt;
        public String errorMessage;
        public java.util.List<String> attemptsLog;

        public SmartLocationResult(boolean success, LocationInfo location, String providerUsed, 
                                   int attempt, String errorMessage, java.util.List<String> attemptsLog) {
            this.success = success;
            this.location = location;
            this.providerUsed = providerUsed;
            this.attempt = attempt;
            this.errorMessage = errorMessage;
            this.attemptsLog = attemptsLog != null ? attemptsLog : new java.util.ArrayList<>();
        }
    }

    public LocationTool(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    private android.content.Intent buildAppSettingsIntent() {
        android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(android.net.Uri.fromParts("package", context.getPackageName(), null));
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private boolean isLocationServiceEnabled() {
        android.location.LocationManager locationManager = 
            (android.location.LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return false;
        }
        try {
            return locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            AILogger.w(TAG, "Error checking location service: " + e.getMessage());
            return false;
        }
    }

    private void showToast(final String message) {
        mainHandler.post(() -> 
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show());
    }

    @Override
    public String getName() {
        return "location";
    }

    @Override
    public String getDescription() {
        return "定位工具，获取用户当前位置信息（经纬度、城市名等）";
    }
    
    private void normalizeParameters(Map<String, Object> parameters) {
        if (parameters == null) return;
        
        // action别名
        if (parameters.containsKey("operation") && !parameters.containsKey("action")) {
            parameters.put("action", parameters.get("operation"));
        }
        if (parameters.containsKey("type") && !parameters.containsKey("action")) {
            parameters.put("action", parameters.get("type"));
        }
        
        // get_location -> get_current
        Object action = parameters.get("action");
        if (action != null) {
            String actionStr = action.toString();
            if ("get_location".equals(actionStr) || "location".equals(actionStr)) {
                parameters.put("action", "get_current");
            } else if ("get_position".equals(actionStr)) {
                parameters.put("action", "get_coordinates");
            }
        }
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            normalizeParameters(parameters);
            
            if (!isLocationServiceEnabled()) {
                AILogger.w(TAG, "Location service is disabled");
                showToast("请先在系统设置中开启位置服务");
                
                try {
                    android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    AILogger.w(TAG, "Cannot open location settings: " + e.getMessage());
                }
                
                Map<String, Object> info = new HashMap<>();
                info.put("location_service_disabled", true);
                info.put("error", "位置服务未开启");
                return new AIToolResult("请先在系统设置中开启位置服务", info);
            }
            
            PermissionResourceProvider.PermissionRequestResult permResult = 
                PermissionResourceProvider.getInstance(context).ensureLocationPermission(30000);
            
            if (!permResult.granted) {
                AILogger.w(TAG, "Location permission check failed: " + permResult.errorMessage);
                
                if (!permResult.hasActivity) {
                    AILogger.i(TAG, "No activity available, trying to open app settings");
                    showToast("需要位置权限才能获取定位，请在应用设置中授权");
                    
                    try {
                        context.startActivity(buildAppSettingsIntent());
                    } catch (Exception e) {
                        AILogger.w(TAG, "Cannot open app settings: " + e.getMessage());
                    }
                }
                
                Map<String, Object> info = new HashMap<>();
                info.put("permission_required", true);
                info.put("permission_granted", false);
                info.put("permission_error", permResult.errorMessage);
                info.put("permission_timeout", permResult.timeout);
                info.put("has_activity", permResult.hasActivity);
                info.put("granted_permissions", permResult.grantedPermissions);
                info.put("denied_permissions", permResult.deniedPermissions);
                
                String errorMsg = "无法获取位置权限";
                if (permResult.errorMessage != null) {
                    errorMsg = permResult.errorMessage;
                } else if (!permResult.hasActivity) {
                    errorMsg = "请在应用设置中开启位置权限";
                } else if (permResult.timeout) {
                    errorMsg = "权限请求超时，请在系统设置中手动授权位置权限";
                } else {
                    errorMsg = "位置权限被拒绝，请在系统设置中手动授权";
                }
                
                return new AIToolResult(errorMsg, info);
            }
            
            AILogger.i(TAG, "Location permission check passed, executing location operation");
            
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
        SmartLocationResult smartResult = getSmartLocation();
        
        if (!smartResult.success || smartResult.location == null) {
            Map<String, Object> info = new HashMap<>();
            info.put("status", "error");
            info.put("error", smartResult.errorMessage != null ? smartResult.errorMessage : "无法获取位置信息，请检查定位权限是否已授予");
            info.put("permission_required", !hasLocationPermission(context));
            return new AIToolResult(info, new HashMap<>());
        }

        LocationInfo locationInfo = smartResult.location;
        String cityName = getCityName(locationInfo.latitude, locationInfo.longitude);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("latitude", locationInfo.latitude);
        result.put("longitude", locationInfo.longitude);
        result.put("accuracy", locationInfo.accuracy);
        result.put("city", cityName != null ? cityName : "未知");
        result.put("provider", locationInfo.provider);
        result.put("timestamp", locationInfo.timestamp);
        result.put("address", getAddressString(locationInfo.latitude, locationInfo.longitude));

        return new AIToolResult(result, new HashMap<>());
    }

    private AIToolResult getCurrentCity() {
        SmartLocationResult smartResult = getSmartLocation();
        
        if (!smartResult.success || smartResult.location == null) {
            Map<String, Object> info = new HashMap<>();
            info.put("status", "error");
            info.put("error", "无法获取位置信息");
            info.put("permission_required", !hasLocationPermission(context));
            return new AIToolResult(info, new HashMap<>());
        }

        LocationInfo locationInfo = smartResult.location;
        String cityName = getCityName(locationInfo.latitude, locationInfo.longitude);
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("city", cityName != null ? cityName : "未知");
        result.put("latitude", locationInfo.latitude);
        result.put("longitude", locationInfo.longitude);

        return new AIToolResult(result, new HashMap<>());
    }

    private AIToolResult getCoordinates() {
        SmartLocationResult smartResult = getSmartLocation();
        
        if (!smartResult.success || smartResult.location == null) {
            Map<String, Object> info = new HashMap<>();
            info.put("status", "error");
            info.put("error", "无法获取位置信息");
            info.put("permission_required", !hasLocationPermission(context));
            return new AIToolResult(info, new HashMap<>());
        }

        LocationInfo locationInfo = smartResult.location;
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
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

        final AtomicReference<LocationInfo> resultRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        final LocationListener locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location != null && callbackInvoked.compareAndSet(false, true)) {
                    resultRef.set(new LocationInfo(location.getLatitude(), location.getLongitude(),
                            location.getAccuracy(), location.getProvider(), location.getTime()));
                    latch.countDown();
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {
                if (callbackInvoked.compareAndSet(false, true)) {
                    latch.countDown();
                }
            }
        };

        try {
            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                CancellationSignal cancellationSignal = new CancellationSignal();
                
                if (gpsEnabled) {
                    locationManager.getCurrentLocation(
                            LocationManager.GPS_PROVIDER, 
                            cancellationSignal, 
                            Executors.newSingleThreadExecutor(),
                            location -> {
                                if (location != null && callbackInvoked.compareAndSet(false, true)) {
                                    resultRef.set(new LocationInfo(location.getLatitude(), location.getLongitude(),
                                            location.getAccuracy(), location.getProvider(), location.getTime()));
                                    latch.countDown();
                                }
                            }
                    );
                }
                if (networkEnabled) {
                    locationManager.getCurrentLocation(
                            LocationManager.NETWORK_PROVIDER, 
                            cancellationSignal, 
                            Executors.newSingleThreadExecutor(),
                            location -> {
                                if (location != null && callbackInvoked.compareAndSet(false, true)) {
                                    resultRef.set(new LocationInfo(location.getLatitude(), location.getLongitude(),
                                            location.getAccuracy(), location.getProvider(), location.getTime()));
                                    latch.countDown();
                                }
                            }
                    );
                }
            } else {
                if (gpsEnabled) {
                    try {
                        locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, Looper.getMainLooper());
                    } catch (Exception e) {
                        AILogger.w(TAG, "GPS single update failed: " + e.getMessage());
                    }
                }
                if (networkEnabled) {
                    try {
                        locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, locationListener, Looper.getMainLooper());
                    } catch (Exception e) {
                        AILogger.w(TAG, "Network single update failed: " + e.getMessage());
                    }
                }
            }

            boolean received = latch.await(LOCATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            
            try {
                locationManager.removeUpdates(locationListener);
            } catch (Exception ignored) {}

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
            AIWeatherManager weatherManager = new AIWeatherManager(context);
            return weatherManager.getHefengCityNameByLocation(latitude, longitude);
        } catch (Exception e) {
            AILogger.w(TAG, "GeoAPI city lookup failed: " + e.getMessage());
        }
        return null;
    }

    private String getAddressString(double latitude, double longitude) {
        if (android.location.Geocoder.isPresent()) {
            try {
                Geocoder geocoder = new Geocoder(context, Locale.CHINA);
                List<Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    StringBuilder sb = new StringBuilder();
                    String country = address.getCountryName();
                    String adminArea = address.getAdminArea();
                    String locality = address.getLocality();
                    String subLocality = address.getSubLocality();
                    String thoroughfare = address.getThoroughfare();
                    
                    if (country != null) sb.append(country);
                    if (adminArea != null) sb.append(" ").append(adminArea);
                    if (locality != null) sb.append(" ").append(locality);
                    if (subLocality != null) sb.append(" ").append(subLocality);
                    if (thoroughfare != null) sb.append(" ").append(thoroughfare);
                    
                    return sb.toString().trim();
                }
            } catch (Exception e) {
                AILogger.w(TAG, "Geocoder address lookup failed: " + e.getMessage());
            }
        }
        return null;
    }

    private void sleepRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private LocationInfo requestLocationByProvider(String providerType) {
        AILogger.i(TAG, "Requesting location using provider: " + providerType);
        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            AILogger.e(TAG, "LocationManager is null");
            return null;
        }

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            AILogger.w(TAG, "Location permission not granted, trying last known location");
            return getLastKnownLocation(locationManager);
        }

        final AtomicReference<LocationInfo> resultRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean callbackInvoked = new AtomicBoolean(false);

        final LocationListener locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location != null && callbackInvoked.compareAndSet(false, true)) {
                    resultRef.set(new LocationInfo(location.getLatitude(), location.getLongitude(),
                            location.getAccuracy(), location.getProvider(), location.getTime()));
                    latch.countDown();
                }
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {}

            @Override
            public void onProviderEnabled(String provider) {}

            @Override
            public void onProviderDisabled(String provider) {
                if (callbackInvoked.compareAndSet(false, true)) {
                    latch.countDown();
                }
            }
        };

        try {
            String provider = null;
            switch (providerType) {
                case LocationProviderType.GPS:
                    provider = LocationManager.GPS_PROVIDER;
                    break;
                case LocationProviderType.NETWORK:
                    provider = LocationManager.NETWORK_PROVIDER;
                    break;
                case LocationProviderType.PASSIVE:
                    provider = LocationManager.PASSIVE_PROVIDER;
                    break;
                case LocationProviderType.LAST_KNOWN:
                    return getLastKnownLocation(locationManager);
                default:
                    AILogger.w(TAG, "Unknown provider type: " + providerType);
                    return null;
            }

            if (provider != null && locationManager.isProviderEnabled(provider)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    CancellationSignal cancellationSignal = new CancellationSignal();
                    locationManager.getCurrentLocation(
                            provider,
                            cancellationSignal,
                            Executors.newSingleThreadExecutor(),
                            location -> {
                                if (location != null && callbackInvoked.compareAndSet(false, true)) {
                                    resultRef.set(new LocationInfo(location.getLatitude(), location.getLongitude(),
                                            location.getAccuracy(), location.getProvider(), location.getTime()));
                                    latch.countDown();
                                }
                            }
                    );
                } else {
                    try {
                        locationManager.requestSingleUpdate(provider, locationListener, Looper.getMainLooper());
                    } catch (Exception e) {
                        AILogger.w(TAG, "Single update failed for " + provider + ": " + e.getMessage());
                    }
                }

                boolean received = latch.await(LOCATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                
                try {
                    locationManager.removeUpdates(locationListener);
                } catch (Exception ignored) {}

                if (received && resultRef.get() != null) {
                    AILogger.i(TAG, "Successfully got location from " + providerType);
                    return resultRef.get();
                }
            } else {
                AILogger.w(TAG, "Provider " + providerType + " not available or disabled");
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Request location by " + providerType + " error: " + e.getMessage(), e);
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        }

        return null;
    }

    public SmartLocationResult getSmartLocation() {
        AILogger.i(TAG, "Starting smart location retrieval");
        java.util.List<String> attemptsLog = new java.util.ArrayList<>();
        int attempt = 0;
        String lastError = null;

        LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        
        // 1. 优先返回上次已知位置（快速路径）
        if (locationManager != null) {
            LocationInfo lastKnown = getLastKnownLocation(locationManager);
            if (lastKnown != null) {
                long age = System.currentTimeMillis() - lastKnown.timestamp;
                // 如果上次位置在30分钟内，直接返回
                if (age < 30 * 60 * 1000) {
                    AILogger.i(TAG, "Returning cached location, age=" + age + "ms");
                    attemptsLog.add("Returned cached location");
                    return new SmartLocationResult(true, lastKnown, LocationProviderType.LAST_KNOWN, 1, null, attemptsLog);
                }
            }
        }

        // 2. 并行尝试网络定位（通常比GPS快）
        attempt++;
        try {
            AILogger.i(TAG, "Attempt " + attempt + ": Trying network location");
            attemptsLog.add("Attempt " + attempt + ": Provider=network");

            if (locationManager != null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                LocationInfo location = requestLocationByProvider(LocationProviderType.NETWORK);
                if (location != null) {
                    AILogger.i(TAG, "Success with network provider");
                    attemptsLog.add("Success: Provider=network");
                    return new SmartLocationResult(true, location, LocationProviderType.NETWORK, attempt, null, attemptsLog);
                }
            }
            lastError = "Network location failed";
        } catch (Exception e) {
            lastError = e.getMessage();
            attemptsLog.add("Attempt " + attempt + ": Error - " + e.getMessage());
        }

        // 3. 尝试GPS定位
        attempt++;
        try {
            AILogger.i(TAG, "Attempt " + attempt + ": Trying GPS location");
            attemptsLog.add("Attempt " + attempt + ": Provider=gps");

            if (locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                LocationInfo location = requestLocationByProvider(LocationProviderType.GPS);
                if (location != null) {
                    AILogger.i(TAG, "Success with GPS provider");
                    attemptsLog.add("Success: Provider=gps");
                    return new SmartLocationResult(true, location, LocationProviderType.GPS, attempt, null, attemptsLog);
                }
            }
            lastError = "GPS location failed";
        } catch (Exception e) {
            lastError = e.getMessage();
            attemptsLog.add("Attempt " + attempt + ": Error - " + e.getMessage());
        }

        // 4. 最后回退到上次已知位置（即使过期）
        if (locationManager != null) {
            LocationInfo lastKnown = getLastKnownLocation(locationManager);
            if (lastKnown != null) {
                AILogger.i(TAG, "Falling back to last known location");
                attemptsLog.add("Fallback to last known");
                return new SmartLocationResult(true, lastKnown, LocationProviderType.LAST_KNOWN, attempt + 1, "位置可能过时", attemptsLog);
            }
        }

        String errorMsg = "所有定位方式均失败\n尝试次数: " + attempt + "\n最后错误: " + lastError;
        AILogger.e(TAG, "Smart location retrieval failed: " + errorMsg);
        attemptsLog.add("Failed: " + errorMsg);
        return new SmartLocationResult(false, null, null, attempt, errorMsg, attemptsLog);
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

    public static class LocationInfo {
        public final double latitude;
        public final double longitude;
        public final float accuracy;
        public final String provider;
        public final long timestamp;

        LocationInfo(double latitude, double longitude, float accuracy, String provider, long timestamp) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracy = accuracy;
            this.provider = provider;
            this.timestamp = timestamp;
        }
    }
}
