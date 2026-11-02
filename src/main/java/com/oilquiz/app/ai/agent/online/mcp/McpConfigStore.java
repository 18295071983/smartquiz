package com.oilquiz.app.ai.agent.online.mcp;

import android.content.Context;
import android.content.SharedPreferences;

import com.oilquiz.app.ai.util.ApiKeyCipher;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP server 配置持久化（SharedPreferences + JSON）。
 *
 * <p>首次启动写入内置模板（官方托管端点，全部默认禁用、令牌留空），
 * 用户启用/填令牌后保存回存储。管理接口（增删改、启停）都经此存储。</p>
 *
 * <p>令牌（token）落盘走 {@link ApiKeyCipher}（Android Keystore AES-GCM，enc: 前缀），
 * 与 APIKeyManager 同一套加密体系：保存时加密、读取时解密；明文历史数据兼容，
 * Keystore 不可用时降级明文并记警告（不阻塞 MCP 功能）。</p>
 */
public class McpConfigStore {

    private static final String TAG = "McpConfigStore";
    private static final String ENC_PREFIX = "enc:";

    private static final String PREF_NAME = "smartquiz_mcp_config";
    private static final String KEY_SERVERS = "mcp_servers_json";
    private static final String KEY_FIRST_RUN = "mcp_first_run_done";

    private final SharedPreferences prefs;

    public McpConfigStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        ensureFirstRun();
    }

    /** 首次启动补齐内置模板（不覆盖已有配置）。 */
    private void ensureFirstRun() {
        try {
            if (prefs.getBoolean(KEY_FIRST_RUN, false)) return;
            if (!prefs.contains(KEY_SERVERS) || load().isEmpty()) {
                save(builtinTemplates());
            }
            prefs.edit().putBoolean(KEY_FIRST_RUN, true).apply();
        } catch (Exception e) {
            AILogger.e(TAG, "ensureFirstRun failed: " + e.getMessage(), e);
        }
    }

    // ==================== 读写 ====================

    public synchronized List<McpServerConfig> load() {
        List<McpServerConfig> result = new ArrayList<>();
        try {
            String raw = prefs.getString(KEY_SERVERS, null);
            if (raw == null || raw.isEmpty()) return result;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                // 令牌解密：enc: 前缀才解，明文历史数据原样返回（兼容）
                String t = o.optString("token", "");
                if (t.startsWith(ENC_PREFIX)) {
                    o.put("token", ApiKeyCipher.decrypt(t));
                }
                McpServerConfig cfg = McpServerConfig.fromJson(o);
                if (cfg != null) result.add(cfg);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "load failed: " + e.getMessage(), e);
        }
        return result;
    }

    public synchronized void save(List<McpServerConfig> servers) {
        try {
            JSONArray arr = new JSONArray();
            if (servers != null) {
                for (McpServerConfig cfg : servers) {
                    if (cfg == null) continue;
                    JSONObject o = cfg.toJson();
                    // 令牌加密：enc: 前缀跳过（避免对已加密串二次加密），空令牌不落字段
                    String t = o.optString("token", "");
                    if (!t.isEmpty() && !t.startsWith(ENC_PREFIX)) {
                        o.put("token", ApiKeyCipher.encrypt(t));
                    }
                    arr.put(o);
                }
            }
            prefs.edit().putString(KEY_SERVERS, arr.toString()).apply();
        } catch (Exception e) {
            AILogger.e(TAG, "save failed: " + e.getMessage(), e);
        }
    }

    // ==================== 管理接口 ====================

    /** 新增或更新（id 相同视为更新）。 */
    public synchronized McpServerConfig upsert(McpServerConfig cfg) {
        List<McpServerConfig> all = load();
        List<McpServerConfig> next = new ArrayList<>();
        boolean updated = false;
        for (McpServerConfig c : all) {
            if (c.id.equals(cfg.id)) {
                next.add(cfg);
                updated = true;
            } else {
                next.add(c);
            }
        }
        if (!updated) next.add(cfg);
        save(next);
        return cfg;
    }

    /** 删除指定 id 的配置。 */
    public synchronized boolean remove(String id) {
        List<McpServerConfig> all = load();
        List<McpServerConfig> next = new ArrayList<>();
        boolean removed = false;
        for (McpServerConfig c : all) {
            if (c.id.equals(id)) {
                removed = true;
            } else {
                next.add(c);
            }
        }
        if (removed) save(next);
        return removed;
    }

    // ==================== 内置模板 ====================

    /**
     * 内置模板（国内官方 MCP 端点，2026-11 快照）。
     * 全部默认禁用、令牌留空；启用时需在管理页「🔑 令牌」填入对应密钥
     * （阿里云 AccessKey / DASHSCOPE_API_KEY 等）。
     */
    private List<McpServerConfig> builtinTemplates() {
        List<McpServerConfig> list = new ArrayList<>();
        list.add(new McpServerConfig("aliyun-maxcompute", "阿里云 MaxCompute",
                "https://mcp.cn-hangzhou.maxcompute.aliyun.com/mcp",
                McpServerConfig.Transport.STREAMABLE_HTTP,
                "", false, true));
        list.add(new McpServerConfig("aliyun-cms", "阿里云云监控",
                "https://openapi-mcp.cn-hangzhou.aliyuncs.com/s/Cms/cms/9fe79ff0bf5748e6923530c5097ec359/mcp",
                McpServerConfig.Transport.STREAMABLE_HTTP,
                "", false, true));
        return list;
    }
}
