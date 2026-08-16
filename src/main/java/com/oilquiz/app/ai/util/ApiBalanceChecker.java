package com.oilquiz.app.ai.util;

import com.oilquiz.app.ai.model.OnlineModelManager;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 在线 API 余额查询。
 *
 * 支持 DeepSeek 官方 API（https://api.deepseek.com）的余额接口：
 * GET /user/balance → {"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00",...}]}
 * 其他 API（OpenAI 兼容等）无标准余额接口，返回"不支持查询"。
 */
public class ApiBalanceChecker {

    private static final String TAG = "ApiBalanceChecker";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "api-balance-check");
        t.setDaemon(true);
        return t;
    });

    public interface BalanceCallback {
        void onResult(String balanceText, String error);
    }

    /**
     * 异步查询 API 余额。
     *
     * @param config  在线模型配置（apiUrl/apiKey）
     * @param callback 回调（主线程外调用，调用方自行切线程）
     */
    public static void checkAsync(OnlineModelManager.OnlineModelConfig config, BalanceCallback callback) {
        if (config == null || config.apiUrl == null || config.apiKey == null || config.apiKey.isEmpty()) {
            if (callback != null) callback.onResult(null, "未配置 API");
            return;
        }
        executor.execute(() -> {
            try {
                String result = queryBalance(config);
                if (callback != null) callback.onResult(result, null);
            } catch (Exception e) {
                if (callback != null) callback.onResult(null, e.getMessage());
            }
        });
    }

    /**
     * 查询余额（同步）。
     *
     * @return 格式化余额文本；不支持/失败抛异常
     */
    private static String queryBalance(OnlineModelManager.OnlineModelConfig config) throws IOException {
        String apiUrl = config.apiUrl.trim().toLowerCase();

        // DeepSeek 官方：标准 /user/balance 接口
        if (apiUrl.contains("deepseek.com")) {
            return queryDeepSeekBalance(config);
        }
        // 小米 MiMo（Token 计划）：尝试 credits 端点（接口未公开验证）
        if (apiUrl.contains("mimo.mi.com") || (apiUrl.contains("mi.com") && apiUrl.contains("mimo"))) {
            return queryMiMoBalance(config);
        }
        // 阿里云百炼等：无标准余额接口（余额在控制台管理）
        if (apiUrl.contains("dashscope.aliyuncs.com") || apiUrl.contains("aliyun")) {
            throw new IOException("阿里云百炼无余额查询接口（请在控制台查看）");
        }
        throw new IOException("该 API 不支持余额查询");
    }

    /** DeepSeek 余额查询 */
    private static String queryDeepSeekBalance(OnlineModelManager.OnlineModelConfig config) throws IOException {
        String base = stripPath(config.apiUrl.trim());
        String balanceUrl = base + "/user/balance";

        Request request = NetworkUtil.createApiRequestBuilder(balanceUrl)
                .addHeader("Authorization", "Bearer " + config.apiKey)
                .get()
                .build();

        try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("余额查询失败(HTTP " + response.code() + ")");
            }
            String body = response.body() != null ? response.body().string() : "";
            return parseDeepSeekBalance(body);
        }
    }

    /** 小米 MiMo 余额查询（Token 计划；端点基于社区工具，未官方验证） */
    private static String queryMiMoBalance(OnlineModelManager.OnlineModelConfig config) throws IOException {
        String base = stripPath(config.apiUrl.trim());
        // 尝试常见 credits 端点：/api/v1/credits
        String[] candidatePaths = {"/api/v1/credits", "/v1/credits", "/credits", "/api/v1/balance", "/v1/balance"};
        IOException lastError = null;
        for (String path : candidatePaths) {
            try {
                Request request = NetworkUtil.createApiRequestBuilder(base + path)
                        .addHeader("Authorization", "Bearer " + config.apiKey)
                        .get()
                        .build();
                try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        String body = response.body() != null ? response.body().string() : "";
                        String parsed = parseGenericBalance(body);
                        if (parsed != null) return parsed;
                    }
                }
            } catch (Exception e) {
                lastError = e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
            }
        }
        if (lastError != null) throw lastError;
        throw new IOException("小米 MiMo 余额接口不可用");
    }

    /** 去掉 URL 路径（保留 scheme://host） */
    private static String stripPath(String apiUrl) {
        int schemeIdx = apiUrl.indexOf("://");
        if (schemeIdx > 0) {
            String rest = apiUrl.substring(schemeIdx + 3);
            int slashIdx = rest.indexOf('/');
            if (slashIdx >= 0) {
                return apiUrl.substring(0, schemeIdx + 3 + slashIdx);
            }
        }
        return apiUrl;
    }

    /** 解析 DeepSeek 余额响应 */
    private static String parseDeepSeekBalance(String json) throws IOException {
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            boolean available = obj.optBoolean("is_available", true);
            org.json.JSONArray infos = obj.optJSONArray("balance_infos");
            if (infos != null && infos.length() > 0) {
                org.json.JSONObject info = infos.optJSONObject(0);
                if (info != null) {
                    String currency = info.optString("currency", "CNY");
                    String total = info.optString("total_balance", "0");
                    return (available ? "余额 " : "额度 ") + currency + " ¥" + total;
                }
            }
            return "余额 ¥0";
        } catch (Exception e) {
            throw new IOException("余额解析失败: " + e.getMessage());
        }
    }

    /** 通用余额解析（兼容 credits/balance 字段变体） */
    private static String parseGenericBalance(String json) {
        try {
            org.json.JSONObject obj = new org.json.JSONObject(json);
            // 常见字段：balance / credits / total_balance / remaining / quota
            String value = null;
            String[] keys = {"total_balance", "balance", "credits", "remaining", "quota", "amount"};
            for (String key : keys) {
                Object v = obj.opt(key);
                if (v != null && !org.json.JSONObject.NULL.equals(v)) {
                    value = String.valueOf(v);
                    break;
                }
            }
            // 嵌套 balance_infos / data
            if (value == null) {
                org.json.JSONObject data = obj.optJSONObject("data");
                if (data != null) {
                    for (String key : keys) {
                        Object v = data.opt(key);
                        if (v != null && !org.json.JSONObject.NULL.equals(v)) {
                            value = String.valueOf(v);
                            break;
                        }
                    }
                }
            }
            if (value == null) return null;
            // 归一化数字（避免科学计数法）
            try {
                double d = Double.parseDouble(value);
                value = new java.math.BigDecimal(d).stripTrailingZeros().toPlainString();
            } catch (Exception ignored) {
            }
            return "余额 ¥" + value;
        } catch (Exception e) {
            return null;
        }
    }
}
