package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.os.Build;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Geocoder 兼容层（2026-11-06）。
 *
 * <p>API 33 起同步的 {@code getFromLocation/getFromLocationName} 已废弃，改成
 * GeocodeListener 异步回调；但 minSdk 31 还要覆盖 31/32。这里收成一处：
 * 33+ 走异步 + 8 秒超时（保持调用方"同步拿结果"的形态），31/32 走旧同步接口并
 * suppress（没有替代可用）。调用方不再各自处理版本分支。</p>
 */
public final class GeocodeCompat {

    private static final long TIMEOUT_SECONDS = 8L;

    private GeocodeCompat() {
    }

    /** 经纬度 → 地址；失败/超时返回空列表（与原同步接口异常语义对齐，调用方自行降级）。 */
    @SuppressWarnings("deprecation") // API 31/32 没有异步接口
    public static List<Address> fromLocation(Context context, double lat, double lon, int maxResults)
            throws IOException {
        Geocoder geocoder = new Geocoder(context, Locale.CHINA);
        if (Build.VERSION.SDK_INT >= 33) {
            final CompletableFuture<List<Address>> future = new CompletableFuture<>();
            geocoder.getFromLocation(lat, lon, maxResults, new Geocoder.GeocodeListener() {
                @Override
                public void onGeocode(List<Address> addresses) {
                    future.complete(addresses != null ? addresses : Collections.emptyList());
                }

                @Override
                public void onError(String errorMessage) {
                    future.complete(Collections.emptyList());
                }
            });
            return await(future);
        }
        List<Address> result = geocoder.getFromLocation(lat, lon, maxResults);
        return result != null ? result : Collections.emptyList();
    }

    /** 地名 → 地址；失败/超时返回空列表。 */
    @SuppressWarnings("deprecation") // API 31/32 没有异步接口
    public static List<Address> fromLocationName(Context context, String locationName, int maxResults)
            throws IOException {
        Geocoder geocoder = new Geocoder(context, Locale.CHINA);
        if (Build.VERSION.SDK_INT >= 33) {
            final CompletableFuture<List<Address>> future = new CompletableFuture<>();
            geocoder.getFromLocationName(locationName, maxResults, new Geocoder.GeocodeListener() {
                @Override
                public void onGeocode(List<Address> addresses) {
                    future.complete(addresses != null ? addresses : Collections.emptyList());
                }

                @Override
                public void onError(String errorMessage) {
                    future.complete(Collections.emptyList());
                }
            });
            return await(future);
        }
        List<Address> result = geocoder.getFromLocationName(locationName, maxResults);
        return result != null ? result : Collections.emptyList();
    }

    private static List<Address> await(CompletableFuture<List<Address>> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
