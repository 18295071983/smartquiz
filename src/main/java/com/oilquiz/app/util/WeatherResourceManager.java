package com.oilquiz.app.util;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;

public class WeatherResourceManager {

    private static final String TAG = "WeatherResourceManager";
    private static final String RESOURCE_DIR = "weather_resources";
    private static final String BASE_URL = "https://assets.qweather.com/icons/";

    private static WeatherResourceManager instance;
    private final Context context;
    private final Map<String, Drawable> cachedDrawables = new HashMap<>();

    private WeatherResourceManager(Context context) {
        this.context = context.getApplicationContext();
        ensureResourceDir();
    }

    public static synchronized WeatherResourceManager getInstance(Context context) {
        if (instance == null) {
            instance = new WeatherResourceManager(context);
        }
        return instance;
    }

    private void ensureResourceDir() {
        File dir = getResourceDir();
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private File getResourceDir() {
        return new File(context.getCacheDir(), RESOURCE_DIR);
    }

    public void loadWeatherIcon(String iconCode, OnIconLoadedListener listener) {
        String fileName = iconCode + ".png";
        File localFile = new File(getResourceDir(), fileName);

        if (cachedDrawables.containsKey(iconCode)) {
            listener.onLoaded(cachedDrawables.get(iconCode));
            return;
        }

        if (localFile.exists()) {
            try {
                Drawable drawable = Drawable.createFromPath(localFile.getAbsolutePath());
                if (drawable != null) {
                    cachedDrawables.put(iconCode, drawable);
                    listener.onLoaded(drawable);
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load local icon: " + e.getMessage());
            }
        }

        downloadIcon(iconCode, listener);
    }

    private void downloadIcon(String iconCode, OnIconLoadedListener listener) {
        new DownloadTask(iconCode, listener).execute();
    }

    private class DownloadTask extends AsyncTask<Void, Void, Drawable> {
        private final String iconCode;
        private final OnIconLoadedListener listener;

        DownloadTask(String iconCode, OnIconLoadedListener listener) {
            this.iconCode = iconCode;
            this.listener = listener;
        }

        @Override
        protected Drawable doInBackground(Void... voids) {
            String urlStr = BASE_URL + iconCode + ".png";
            String fileName = iconCode + ".png";
            File localFile = new File(getResourceDir(), fileName);

            try {
                URL url = new URL(urlStr);
                URLConnection connection = url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                try (InputStream is = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream fos = new FileOutputStream(localFile)) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }

                Drawable drawable = Drawable.createFromPath(localFile.getAbsolutePath());
                if (drawable != null) {
                    cachedDrawables.put(iconCode, drawable);
                    return drawable;
                }

            } catch (IOException e) {
                Log.e(TAG, "Failed to download icon " + iconCode + ": " + e.getMessage());
            }

            return null;
        }

        @Override
        protected void onPostExecute(Drawable drawable) {
            if (drawable != null) {
                listener.onLoaded(drawable);
            } else {
                listener.onError();
            }
        }
    }

    public interface OnIconLoadedListener {
        void onLoaded(Drawable drawable);
        void onError();
    }

    public void clearCache() {
        cachedDrawables.clear();
        File dir = getResourceDir();
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
    }

    public long getCacheSize() {
        long size = 0;
        File dir = getResourceDir();
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    size += file.length();
                }
            }
        }
        return size;
    }
}