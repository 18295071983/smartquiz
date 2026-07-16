package com.oilquiz.app.util;

import android.content.Context;
import android.util.Log;

public class WeatherIconInitializer {

    private static final String TAG = "WeatherIconInitializer";
    private static boolean initialized = false;

    public static void initialize(Context context) {
        if (initialized) {
            return;
        }

        WeatherIconManager iconManager = WeatherIconManager.getInstance(context);
        
        iconManager.downloadAllIcons(new WeatherIconManager.OnDownloadCompleteListener() {
            @Override
            public void onProgress(int downloaded, int total) {
                Log.d(TAG, "Downloading icons: " + downloaded + "/" + total);
            }

            @Override
            public void onComplete(boolean success) {
                initialized = success;
                Log.d(TAG, "Icon download " + (success ? "completed" : "failed"));
            }
        });
    }

    public static boolean isInitialized() {
        return initialized;
    }
}