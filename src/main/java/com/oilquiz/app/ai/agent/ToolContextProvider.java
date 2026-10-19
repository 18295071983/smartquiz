package com.oilquiz.app.ai.agent;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具环境上下文提供者。
 *
 * 自动获取当前时间、当前位置等环境上下文，供工具执行前注入，
 * 实现"留空自动定位"等智能化能力。同时提供上下文缺失检测，
 * 用于在执行工具前判断是否需要补充环境信息。
 *
 * 设计要点：
 * - 时间相关方法同步返回，开销极小
 * - 定位方法异步执行，5秒超时保护，避免阻塞调用线程
 * - Geocoder 反查城市名为同步调用，放在后台线程执行
 * - {@link #getMissingContext(ToolGuideFlow, Map)} 根据 flow 的 contextDependencies
 *   与已有参数对比，返回缺失的上下文名列表
 */
public class ToolContextProvider {

    private static final String TAG = "ToolContextProvider";
    /** 定位超时时间（毫秒） */
    private static final long LOCATION_TIMEOUT_MS = 5000L;

    private ToolContextProvider() {}

    /**
     * 获取当前日期时间字符串。
     *
     * @return 格式为 yyyy-MM-dd HH:mm:ss 的当前时间
     */
    public static String getCurrentDateTime() {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date());
        } catch (Exception e) {
            Log.e(TAG, "格式化当前时间失败: " + e.getMessage(), e);
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        }
    }

    /**
     * 获取当前日期字符串。
     *
     * @return 格式为 yyyy-MM-dd 的当前日期
     */
    public static String getCurrentDate() {
        try {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(new Date());
        } catch (Exception e) {
            Log.e(TAG, "格式化当前日期失败: " + e.getMessage(), e);
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        }
    }

    /**
     * 异步获取当前位置。
     *
     * 策略：优先使用 LocationManager 的 getLastKnownLocation（GPS / NETWORK 提供者），
     * 若无缓存位置则 requestLocationUpdates 等待新定位。5秒超时后回调 onLocationFailed。
     * 获取坐标后通过 Geocoder 反查城市名（同步），Geocoder 失败则城市名为"当前位置"。
     *
     * @param ctx      上下文
     * @param callback 定位回调
     */
    public static void getCurrentLocation(Context ctx, LocationCallback callback) {
        if (ctx == null || callback == null) {
            return;
        }
        // 在后台线程执行定位，避免阻塞主线程
        new Thread(() -> {
            final Handler mainHandler = new Handler(Looper.getMainLooper());
            final LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                postFailed(mainHandler, callback, "无法获取定位服务");
                return;
            }
            // 超时保护标志
            final boolean[] finished = {false};

            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    synchronized (finished) {
                        if (finished[0]) {
                            return;
                        }
                        finished[0] = true;
                    }
                    try {
                        lm.removeUpdates(this);
                    } catch (SecurityException se) {
                        Log.w(TAG, "移除定位监听失败: " + se.getMessage());
                    }
                    handleLocation(ctx, mainHandler, callback, location);
                }

                @Override
                public void onProviderDisabled(String provider) {}

                @Override
                public void onProviderEnabled(String provider) {}

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}
            };

            // 5秒超时
            mainHandler.postDelayed(() -> {
                synchronized (finished) {
                    if (finished[0]) {
                        return;
                    }
                    finished[0] = true;
                }
                try {
                    lm.removeUpdates(listener);
                } catch (SecurityException se) {
                    Log.w(TAG, "超时后移除定位监听失败: " + se.getMessage());
                }
                postFailed(mainHandler, callback, "定位超时");
            }, LOCATION_TIMEOUT_MS);

            try {
                // 优先使用缓存位置
                Location cached = getLastKnown(lm);
                if (cached != null) {
                    synchronized (finished) {
                        if (finished[0]) {
                            return;
                        }
                        finished[0] = true;
                    }
                    mainHandler.removeCallbacksAndMessages(null);
                    handleLocation(ctx, mainHandler, callback, cached);
                    return;
                }
                // 无缓存位置，请求新定位
                String provider = pickProvider(lm);
                if (provider == null) {
                    synchronized (finished) {
                        if (finished[0]) {
                            return;
                        }
                        finished[0] = true;
                    }
                    mainHandler.removeCallbacksAndMessages(null);
                    postFailed(mainHandler, callback, "没有可用的定位提供者");
                    return;
                }
                lm.requestLocationUpdates(provider, 0, 0, listener, Looper.getMainLooper());
            } catch (SecurityException se) {
                Log.e(TAG, "缺少定位权限: " + se.getMessage(), se);
                synchronized (finished) {
                    if (finished[0]) {
                        return;
                    }
                    finished[0] = true;
                }
                mainHandler.removeCallbacksAndMessages(null);
                postFailed(mainHandler, callback, "缺少定位权限");
            } catch (Exception e) {
                Log.e(TAG, "定位异常: " + e.getMessage(), e);
                synchronized (finished) {
                    if (finished[0]) {
                        return;
                    }
                    finished[0] = true;
                }
                mainHandler.removeCallbacksAndMessages(null);
                postFailed(mainHandler, callback, "定位失败:" + e.getMessage());
            }
        }, "ToolContextProvider-Location").start();
    }

    /** 获取最近一次已知位置（优先 GPS，其次 NETWORK） */
    private static Location getLastKnown(LocationManager lm) {
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (l != null) {
                    return l;
                }
            }
        } catch (SecurityException se) {
            Log.w(TAG, "获取GPS缓存位置失败: " + se.getMessage());
        }
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
        } catch (SecurityException se) {
            Log.w(TAG, "获取NETWORK缓存位置失败: " + se.getMessage());
        }
        return null;
    }

    /** 选择一个可用的定位提供者（优先 GPS） */
    private static String pickProvider(LocationManager lm) {
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return LocationManager.GPS_PROVIDER;
            }
        } catch (SecurityException ignored) {}
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
        } catch (SecurityException ignored) {}
        return null;
    }

    /** 处理定位结果：反查城市名后回调主线程 */
    private static void handleLocation(Context ctx, Handler mainHandler,
                                       LocationCallback callback, Location location) {
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        String city = reverseGeocode(ctx, lat, lon);
        mainHandler.post(() -> {
            try {
                callback.onLocationReady(city, lat, lon);
            } catch (Exception e) {
                Log.e(TAG, "定位回调异常: " + e.getMessage(), e);
            }
        });
    }

    /** 通过 Geocoder 反查城市名（同步），失败返回"当前位置" */
    private static String reverseGeocode(Context ctx, double lat, double lon) {
        try {
            Geocoder geocoder = new Geocoder(ctx, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address addr = addresses.get(0);
                // 优先使用 locality（城市），其次 subAdminArea / adminArea
                String city = addr.getLocality();
                if (city == null || city.isEmpty()) {
                    city = addr.getSubAdminArea();
                }
                if (city == null || city.isEmpty()) {
                    city = addr.getAdminArea();
                }
                if (city != null && !city.isEmpty()) {
                    return city;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Geocoder反查城市失败: " + e.getMessage());
        }
        return "当前位置";
    }

    /** 在主线程回调定位失败 */
    private static void postFailed(Handler mainHandler, LocationCallback callback, String error) {
        mainHandler.post(() -> {
            try {
                callback.onLocationFailed(error);
            } catch (Exception e) {
                Log.e(TAG, "定位失败回调异常: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 检查工具执行前缺失的环境上下文。
     *
     * 规则：遍历 flow.contextDependencies，若依赖 "location" 但 params 中
     * 没有 city / lat / lon 任何一个，则视为缺失。
     *
     * @param flow   工具引导流程
     * @param params 当前已有的参数
     * @return 缺失的上下文依赖列表；无缺失时返回空列表
     */
    public static List<String> getMissingContext(ToolGuideFlow flow, Map<String, Object> params) {
        List<String> missing = new ArrayList<>();
        if (flow == null || flow.contextDependencies == null || flow.contextDependencies.isEmpty()) {
            return missing;
        }
        for (String dep : flow.contextDependencies) {
            if (dep == null) {
                continue;
            }
            if ("location".equals(dep)) {
                if (!hasKey(params, "city") && !hasKey(params, "lat") && !hasKey(params, "lon")) {
                    missing.add("location");
                }
            }
        }
        return missing;
    }

    /** 判断 params 中是否存在指定 key 且值非空 */
    private static boolean hasKey(Map<String, Object> params, String key) {
        if (params == null) {
            return false;
        }
        Object v = params.get(key);
        if (v == null) {
            return false;
        }
        if (v instanceof String) {
            return !((String) v).isEmpty();
        }
        return true;
    }

    /**
     * 定位回调接口。
     */
    public interface LocationCallback {
        /** 定位成功 */
        void onLocationReady(String city, double lat, double lon);

        /** 定位失败 */
        void onLocationFailed(String error);
    }
}
