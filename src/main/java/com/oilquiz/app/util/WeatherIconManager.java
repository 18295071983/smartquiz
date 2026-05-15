package com.oilquiz.app.util;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.util.Log;

import com.oilquiz.app.R;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;

public class WeatherIconManager {

    private static final String TAG = "WeatherIconManager";
    private static final String BASE_URL = "https://assets.qweather.com/icons/";
    private static final String CACHE_DIR = "weather_icons";

    private static WeatherIconManager instance;
    private final Context context;
    private final Map<String, Integer> localIconMap = new HashMap<>();
    private final Map<String, Drawable> cachedDrawables = new HashMap<>();

    private WeatherIconManager(Context context) {
        this.context = context.getApplicationContext();
        initLocalIconMap();
        ensureCacheDir();
    }

    public static synchronized WeatherIconManager getInstance(Context context) {
        if (instance == null) {
            instance = new WeatherIconManager(context);
        }
        return instance;
    }

    private void initLocalIconMap() {
    }

    private void ensureCacheDir() {
        File dir = getCacheDir();
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private File getCacheDir() {
        return new File(context.getCacheDir(), CACHE_DIR);
    }

    public int getIconResource(String iconCode) {
        Integer resId = localIconMap.get(iconCode);
        return resId != null ? resId : R.drawable.wi_unknown;
    }

    public Drawable getIconDrawable(String iconCode) {
        if (iconCode == null || iconCode.isEmpty()) {
            return context.getDrawable(R.drawable.wi_unknown);
        }

        if (cachedDrawables.containsKey(iconCode)) {
            return cachedDrawables.get(iconCode);
        }

        Drawable cachedDrawable = getCachedIcon(iconCode);
        if (cachedDrawable != null) {
            cachedDrawables.put(iconCode, cachedDrawable);
            return cachedDrawable;
        }

        Integer resId = localIconMap.get(iconCode);
        if (resId != null) {
            Drawable drawable = context.getDrawable(resId);
            cachedDrawables.put(iconCode, drawable);
            return drawable;
        }

        return context.getDrawable(R.drawable.wi_unknown);
    }

    private Drawable getCachedIcon(String iconCode) {
        File localFile = new File(getCacheDir(), iconCode + ".png");
        if (localFile.exists()) {
            try {
                return Drawable.createFromPath(localFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Failed to load cached icon " + iconCode + ": " + e.getMessage());
            }
        }
        return null;
    }

    public void downloadAllIcons(OnDownloadCompleteListener listener) {
        new DownloadAllTask(listener).execute();
    }

    private class DownloadAllTask extends AsyncTask<Void, Integer, Boolean> {
        private final OnDownloadCompleteListener listener;
        private int downloaded = 0;

        DownloadAllTask(OnDownloadCompleteListener listener) {
            this.listener = listener;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            String[] iconCodes = {
                "100", "101", "102", "103", "104",
                "200", "201", "202", "203", "204", "205", "206", "207", "208", "209", "210", "211", "212", "213",
                "300", "301", "302", "303", "304", "305", "306", "307", "308", "309", "310", "311", "312", "313", "314", "315", "316", "317", "318", "399",
                "400", "401", "402", "403", "404", "405", "406", "407", "408", "409", "410", "499",
                "500", "501", "502", "503", "504", "507", "508", "509", "510", "511", "512", "513", "514", "515",
                "800", "801", "802", "803", "804",
                "900", "999"
            };

            for (String code : iconCodes) {
                downloadIcon(code);
                downloaded++;
                publishProgress(downloaded, iconCodes.length);
            }
            return true;
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            if (listener != null) {
                listener.onProgress(values[0], values[1]);
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (listener != null) {
                listener.onComplete(success);
            }
        }
    }

    private void downloadIcon(String iconCode) {
        String urlStr = BASE_URL + iconCode + ".png";
        String fileName = iconCode + ".png";
        File localFile = new File(getCacheDir(), fileName);

        if (localFile.exists()) {
            return;
        }

        try {
            URL url = new URL(urlStr);
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            try (InputStream is = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream fos = new FileOutputStream(localFile)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }

        } catch (IOException e) {
            Log.e(TAG, "Failed to download icon " + iconCode + ": " + e.getMessage());
        }
    }

    public void clearCache() {
        cachedDrawables.clear();
        File dir = getCacheDir();
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
    }

    public interface OnDownloadCompleteListener {
        void onProgress(int downloaded, int total);
        void onComplete(boolean success);
    }
}