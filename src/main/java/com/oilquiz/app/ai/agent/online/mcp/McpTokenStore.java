package com.oilquiz.app.ai.agent.online.mcp;

import android.content.Context;
import android.content.SharedPreferences;

import com.oilquiz.app.ai.util.ApiKeyCipher;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth 授权记录的落盘（按 serverId 一条，access/refresh 令牌字段用 Keystore 加密）。
 *
 * <p>与 {@link McpConfigStore} 分开一张表：配置是"用户声明我要连哪个端点"，令牌是"服务商issued的凭证"，
 * 生命周期、清理路径、泄露后果都不一样——删配置要顺带清令牌，清令牌不该删配置。</p>
 *
 * <p>写入一律 {@code commit()} 不用 {@code apply()}：上一轮真机 E2E 就是 {@code apply()} 没来得及刷盘，
 * 进程被杀后磁盘上留着一条已删除的服务。令牌表更要防这种"写丢了但内存里以为有"。</p>
 */
public class McpTokenStore {

    private static final String TAG = "McpTokenStore";
    private static final String PREF_NAME = "smartquiz_mcp_oauth";
    private static final String KEY_RECORDS = "oauth_records_json";
    private static final String ENC_PREFIX = "enc:";

    private final SharedPreferences prefs;

    public McpTokenStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ==================== 读 ====================

    /** 取某服务的授权记录（令牌已解密）；没有返回 null。 */
    public synchronized McpOAuthRecord get(String serverId) {
        if (serverId == null || serverId.isEmpty()) return null;
        Map<String, JSONObject> raw = readRaw();
        JSONObject o = raw.get(serverId);
        if (o == null) return null;
        return decrypt(o);
    }

    /** 全部授权记录（serverId → 记录），列表页展示状态用。 */
    public synchronized List<McpOAuthRecord> getAll() {
        List<McpOAuthRecord> out = new ArrayList<>();
        for (JSONObject o : readRaw().values()) {
            McpOAuthRecord rec = decrypt(o);
            if (rec != null) out.add(rec);
        }
        return out;
    }

    public synchronized boolean has(String serverId) {
        return get(serverId) != null;
    }

    // ==================== 写 ====================

    /** 保存/覆盖一条授权记录。返回 false = 没落盘（调用方必须把"已授权"当成没成功）。 */
    public synchronized boolean save(McpOAuthRecord record) {
        if (record == null || record.serverId.isEmpty()) return false;
        Map<String, JSONObject> raw = readRaw();
        try {
            raw.put(record.serverId, encrypt(record));
            boolean ok = prefs.edit()
                    .putString(KEY_RECORDS, new JSONArray(raw.values()).toString())
                    .commit();
            if (!ok) {
                AILogger.e(TAG, "save failed: commit 返回 false，授权记录没落盘: " + record.serverId);
            } else {
                AILogger.audit(TAG, "保存 OAuth 授权记录: " + record.serverId
                        + "（client_id 已登记，令牌密文存储）");
            }
            return ok;
        } catch (Exception e) {
            AILogger.e(TAG, "save failed: " + e.getMessage(), e);
            return false;
        }
    }

    /** 删除某服务的授权记录（服务被删、或用户主动撤销授权）。 */
    public synchronized boolean remove(String serverId) {
        if (serverId == null || serverId.isEmpty()) return false;
        Map<String, JSONObject> raw = readRaw();
        if (!raw.containsKey(serverId)) return false;
        raw.remove(serverId);
        boolean ok = prefs.edit()
                .putString(KEY_RECORDS, new JSONArray(raw.values()).toString())
                .commit();
        if (ok) {
            AILogger.audit(TAG, "清除 OAuth 授权记录: " + serverId);
        }
        return ok;
    }

    // ==================== 内部 ====================

    private Map<String, JSONObject> readRaw() {
        Map<String, JSONObject> out = new LinkedHashMap<>();
        String raw = prefs.getString(KEY_RECORDS, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("serverId", "");
                if (id.isEmpty()) continue;
                out.put(id, o);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "读取授权记录失败，按空表处理: " + e.getMessage());
        }
        return out;
    }

    private JSONObject encrypt(McpOAuthRecord record) throws Exception {
        JSONObject o = record.toJson();
        // ApiKeyCipher 自己加 enc: 前缀；失败时返回原文（日志里已警告），不能因此丢掉授权
        o.put("accessToken", ApiKeyCipher.encrypt(record.accessToken));
        o.put("refreshToken", ApiKeyCipher.encrypt(record.refreshToken));
        return o;
    }

    private McpOAuthRecord decrypt(JSONObject stored) {
        try {
            JSONObject o = new JSONObject(stored.toString());
            o.put("accessToken", maybeDecrypt(o.optString("accessToken", "")));
            o.put("refreshToken", maybeDecrypt(o.optString("refreshToken", "")));
            return McpOAuthRecord.fromJson(o);
        } catch (Exception e) {
            AILogger.e(TAG, "授权记录解密失败: " + e.getMessage());
            return null;
        }
    }

    private String maybeDecrypt(String value) {
        if (value == null || value.isEmpty()) return "";
        if (!value.startsWith(ENC_PREFIX)) return value;   // 明文历史数据（兼容）
        String plain = ApiKeyCipher.decrypt(value);
        // 解不出来时 ApiKeyCipher 会原样返回密文串；那种值当令牌发出去必然 401，按无令牌处理
        return plain != null && plain.startsWith(ENC_PREFIX) ? "" : plain;
    }
}
