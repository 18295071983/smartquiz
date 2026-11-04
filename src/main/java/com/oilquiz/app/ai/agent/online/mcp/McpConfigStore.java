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

    /**
     * 首次启动不再预置任何服务，只落"已首启"标记。
     *
     * <p><b>为什么撤掉内置模板</b>：原来两条模板都连不上——
     * {@code aliyun-maxcompute} 的 Remote MCP 要浏览器 OAuth（App 没实现），
     * {@code aliyun-cms} 的端点是控制台为某个账号下发的分享路径，不是公共入口。
     * 开机就在管理页躺两条"启用即报错"的服务，比空列表更误导人；
     * 服务由用户从市场清单（{@link McpMarketCatalog}）按需安装，装的时候先探测再启用。</p>
     */
    private void ensureFirstRun() {
        try {
            if (prefs.getBoolean(KEY_FIRST_RUN, false)) return;
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
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                // 令牌解密：enc: 前缀才解，明文历史数据原样返回（兼容）
                String t = o.optString("token", "");
                if (t.startsWith(ENC_PREFIX)) {
                    o.put("token", ApiKeyCipher.decrypt(t));
                }
                McpServerConfig cfg = McpServerConfig.fromJson(o);
                if (cfg == null) continue;
                // 防御：历史/手工编辑可能留下重复 id，后面的会被 UI 当成两条同一服务
                if (!seen.add(cfg.id)) {
                    AILogger.w(TAG, "忽略重复的 MCP id: " + cfg.id);
                    continue;
                }
                result.add(cfg);
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
            // 配置变更必须落盘后再返回：apply() 是异步的，进程刚被测试/系统杀掉时
            // 会出现“内存里已删、磁盘上还在”（真机 E2E 已复现测试配置残留）。
            boolean ok = prefs.edit().putString(KEY_SERVERS, arr.toString()).commit();
            if (!ok) {
                AILogger.e(TAG, "save failed: SharedPreferences.commit returned false");
            }
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
}
