package com.oilquiz.app.util;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;

import java.io.Serializable;

/**
 * Intent/Bundle 取值兼容层（2026-11-06）。
 *
 * <p>API 33 起 {@code getSerializableExtra/getParcelableExtra(String)} 已废弃，需要传 Class；
 * minSdk 31 还要覆盖 31/32。收成一处，33+ 走类型化版本，31/32 走旧接口并 suppress。</p>
 */
public final class IntentExtrasCompat {

    private IntentExtrasCompat() {
    }

    @SuppressWarnings("deprecation") // API 31/32 没有 typed getSerializableExtra
    public static Serializable getSerializable(Intent intent, String key) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getSerializableExtra(key, Serializable.class);
        }
        return intent.getSerializableExtra(key);
    }

    @SuppressWarnings("deprecation") // API 31/32 没有 typed getSerializableExtra
    public static Serializable getSerializable(Bundle bundle, String key) {
        if (bundle == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return bundle.getSerializable(key, Serializable.class);
        }
        return bundle.getSerializable(key);
    }

    @SuppressWarnings("deprecation") // API 31/32 没有 typed getParcelableExtra
    public static <T extends Parcelable> T getParcelable(Intent intent, String key, Class<T> clazz) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(key, clazz);
        }
        Object value = intent.getParcelableExtra(key);
        return clazz.isInstance(value) ? clazz.cast(value) : null;
    }
}
