package com.oilquiz.app.weather;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.locks.ReentrantLock;

public class WeatherCacheManager {

    private static final String TAG = "WeatherCacheManager";
    private static final String CACHE_DIR = "weather_cache";
    private static final long MAX_CACHE_SIZE = 5 * 1024 * 1024;
    private static final int MAX_CACHE_ENTRIES = 100;

    private final Context context;
    private final File cacheDir;
    private final ReentrantLock lock = new ReentrantLock();

    private static WeatherCacheManager instance;

    private WeatherCacheManager(Context context) {
        this.context = context.getApplicationContext();
        this.cacheDir = new File(this.context.getCacheDir(), CACHE_DIR);
        ensureCacheDir();
    }

    public static synchronized WeatherCacheManager getInstance(Context context) {
        if (instance == null) {
            instance = new WeatherCacheManager(context);
        }
        return instance;
    }

    private void ensureCacheDir() {
        if (!cacheDir.exists()) {
            boolean created = cacheDir.mkdirs();
            Log.d(TAG, "Cache directory created: " + created);
        }
    }

    public void saveCache(String key, String data) {
        lock.lock();
        try {
            String fileName = hashKey(key);
            File cacheFile = new File(cacheDir, fileName);

            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(cacheFile))) {
                long timestamp = System.currentTimeMillis();
                writer.write(timestamp + "\n");
                writer.write(data);
            }

            cleanupIfNeeded();
        } catch (Exception e) {
            Log.e(TAG, "Error saving cache for key: " + key, e);
        } finally {
            lock.unlock();
        }
    }

    public CacheEntry getCache(String key) {
        lock.lock();
        try {
            String fileName = hashKey(key);
            File cacheFile = new File(cacheDir, fileName);

            if (!cacheFile.exists()) {
                return null;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(cacheFile)))) {
                String timestampLine = reader.readLine();
                if (timestampLine == null) {
                    cacheFile.delete();
                    return null;
                }

                long timestamp = Long.parseLong(timestampLine);
                StringBuilder dataBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    dataBuilder.append(line).append("\n");
                }

                String data = dataBuilder.toString();
                if (data.endsWith("\n")) {
                    data = data.substring(0, data.length() - 1);
                }

                return new CacheEntry(timestamp, data);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading cache for key: " + key, e);
            return null;
        } finally {
            lock.unlock();
        }
    }

    public void removeCache(String key) {
        lock.lock();
        try {
            String fileName = hashKey(key);
            File cacheFile = new File(cacheDir, fileName);
            if (cacheFile.exists()) {
                cacheFile.delete();
            }
        } finally {
            lock.unlock();
        }
    }

    public void clearAllCache() {
        lock.lock();
        try {
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public long getCacheSize() {
        lock.lock();
        try {
            long size = 0;
            File[] files = cacheDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    size += file.length();
                }
            }
            return size;
        } finally {
            lock.unlock();
        }
    }

    private void cleanupIfNeeded() {
        long currentSize = getCacheSize();
        int entryCount = getEntryCount();

        if (currentSize > MAX_CACHE_SIZE || entryCount > MAX_CACHE_ENTRIES) {
            Log.d(TAG, "Cache cleanup triggered. Size: " + currentSize + ", Entries: " + entryCount);
            cleanupOldestEntries();
        }
    }

    private int getEntryCount() {
        File[] files = cacheDir.listFiles();
        return files != null ? files.length : 0;
    }

    private void cleanupOldestEntries() {
        File[] files = cacheDir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }

        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));

        int toDelete = files.length / 3;
        for (int i = 0; i < toDelete && i < files.length; i++) {
            files[i].delete();
        }
    }

    private String hashKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(key.getBytes());
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(key.hashCode());
        }
    }

    public static class CacheEntry {
        private final long timestamp;
        private final String data;

        public CacheEntry(long timestamp, String data) {
            this.timestamp = timestamp;
            this.data = data;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getData() {
            return data;
        }

        public boolean isExpired(long maxAgeMs) {
            return System.currentTimeMillis() - timestamp > maxAgeMs;
        }
    }
}