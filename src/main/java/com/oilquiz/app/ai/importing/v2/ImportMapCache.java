package com.oilquiz.app.ai.importing.v2;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 字段映射缓存（map_cache.json）。
 * <p>
 * 缓存 key = 表结构指纹 + 文件表头指纹。同表结构 + 同表头文件直接复用，跳过 AI 推理。
 */
public final class ImportMapCache {

    private static final String TAG = "ImportMapCache";
    /** 缓存条目上限，超出后丢弃最旧条目 */
    private static final int MAX_ENTRIES = 64;

    /** 一条缓存记录 */
    public static class Entry {
        public String cacheKey;
        /** 标准字段 → 源列名 */
        public Map<String, String> mapping = new LinkedHashMap<>();
        public long savedAt;
    }

    private ImportMapCache() {
    }

    /** 生成缓存唯一 key：表结构指纹 + 表头指纹 */
    public static String buildCacheKey(String tableFinger, String headerFinger) {
        return (tableFinger == null ? "" : tableFinger) + "|"
                + (headerFinger == null ? "" : headerFinger);
    }

    /** 表头指纹：表头规范化后取哈希 */
    public static String headerFingerprint(java.util.List<String> headers) {
        if (headers == null || headers.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String h : headers) {
            sb.append(h == null ? "" : h.trim().toLowerCase()).append('\u0001');
        }
        return Integer.toHexString(sb.toString().hashCode()) + "_" + headers.size();
    }

    /** 查找缓存映射，未命中返回 null */
    public static Map<String, String> find(String cacheKey) {
        try {
            JSONObject root = readAll();
            if (root == null) return null;
            JSONArray arr = root.optJSONArray("entries");
            if (arr == null) return null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e == null) continue;
                if (cacheKey.equals(e.optString("key"))) {
                    JSONObject m = e.optJSONObject("mapping");
                    if (m == null) continue;
                    Map<String, String> map = new LinkedHashMap<>();
                    java.util.Iterator<String> it = m.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        map.put(k, m.optString(k, ""));
                    }
                    return map;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "读取映射缓存失败: " + e.getMessage());
        }
        return null;
    }

    /** 保存映射规则 */
    public static void save(String cacheKey, Map<String, String> mapping) {
        if (cacheKey == null || mapping == null || mapping.isEmpty()) return;
        try {
            JSONObject root = readAll();
            if (root == null) root = new JSONObject();
            JSONArray arr = root.optJSONArray("entries");
            if (arr == null) arr = new JSONArray();

            // 去重：移除同 key 旧条目
            JSONArray fresh = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject e = arr.optJSONObject(i);
                if (e != null && !cacheKey.equals(e.optString("key"))) {
                    fresh.put(e);
                }
            }
            // 容量控制
            while (fresh.length() >= MAX_ENTRIES) {
                fresh.remove(0);
            }

            JSONObject entry = new JSONObject();
            entry.put("key", cacheKey);
            entry.put("savedAt", System.currentTimeMillis());
            entry.put("mapping", new JSONObject(mapping));
            fresh.put(entry);

            root.put("entries", fresh);
            writeAll(root);
            Log.i(TAG, "映射缓存已保存: " + cacheKey);
        } catch (Exception e) {
            Log.w(TAG, "保存映射缓存失败: " + e.getMessage());
        }
    }

    private static JSONObject readAll() {
        File f = ImportDirs.mapCacheFile();
        if (!f.exists()) return null;
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int read = 0;
            while (read < buf.length) {
                int n = fis.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            return new JSONObject(new String(buf, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "读取 map_cache.json 失败: " + e.getMessage());
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void writeAll(JSONObject root) throws Exception {
        File f = ImportDirs.mapCacheFile();
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
