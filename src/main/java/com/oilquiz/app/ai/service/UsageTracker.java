package com.oilquiz.app.ai.service;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.oilquiz.app.ai.model.UsageInfo;
import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * 使用量跟踪器
 * 获取 API 使用量统计信息
 */
public class UsageTracker {

    public static class UsageInfo extends com.oilquiz.app.ai.model.UsageInfo {}

    private static final String TAG = "UsageTracker";
    private static final int DEFAULT_TIMEOUT_MS = 15000;

    private static volatile UsageTracker INSTANCE;
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final Gson gson;

    private final java.util.Map<String, UsageInfo> usageInfoCache = new java.util.concurrent.ConcurrentHashMap<>();

    private UsageTracker(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Usage-Tracker");
            t.setPriority(Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        });
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.gson = new Gson();
    }

    public static UsageTracker getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (UsageTracker.class) {
                if (INSTANCE == null) {
                    INSTANCE = new UsageTracker(context);
                }
            }
        }
        return INSTANCE;
    }

    public UsageInfo getUsageInfo(String configId) {
        UsageInfo cached = usageInfoCache.get(configId);
        return cached != null ? cached : new UsageInfo();
    }

    public UsageInfo getUsageInfo(long configId) {
        return getUsageInfo(String.valueOf(configId));
    }

    /**
     * 获取使用量信息
     * @param apiUrl API 地址
     * @param apiKey API 密钥
     * @param period 统计周期 (daily, monthly)
     * @return 使用量信息
     */
    public CompletableFuture<UsageInfo> fetchUsage(String apiUrl, String apiKey, String period) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (apiUrl == null || apiUrl.isEmpty()) {
                    throw new IllegalArgumentException("API URL 不能为空");
                }
                if (apiKey == null || apiKey.isEmpty()) {
                    throw new IllegalArgumentException("API Key 不能为空");
                }

                String lowerUrl = apiUrl.toLowerCase();
                // 用量即账户余额的服务商（DeepSeek 官方、小米 MiMo）：它们**没有** OpenAI 式的
                // usage 端点，若落到下面的 fetchOpenAIUsage 就会 404（此前配置对话框显示的
                // "使用量 API 不可用 (HTTP 404)" 即由此而来；对话页因直接用 ApiBalanceChecker 而正常）。
                // 统一委托 ApiBalanceChecker，确保全应用同一数据源。
                if (com.oilquiz.app.ai.util.ApiBalanceChecker.supportsBalanceQuery(apiUrl)) {
                    try {
                        com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig cfg =
                                new com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig();
                        cfg.apiUrl = apiUrl;
                        cfg.apiKey = apiKey;
                        String balanceText = com.oilquiz.app.ai.util.ApiBalanceChecker.queryBalanceText(cfg);
                        UsageInfo info = new UsageInfo();
                        info.supported = true;
                        info.errorMessage = null;
                        // 余额语义（DeepSeek 用量即账户余额，非"已用/总量"）：
                        // 用 note 承载原始文本，由 updateUsageDisplay 直接展示
                        info.note = balanceText;
                        info.lastUpdated = System.currentTimeMillis();
                        return info;
                    } catch (Exception e) {
                        UsageInfo info = new UsageInfo();
                        info.supported = false;
                        info.errorMessage = e.getMessage();
                        return info;
                    }
                }
                if (lowerUrl.contains("anthropic")) {
                    return fetchAnthropicUsage(apiUrl, apiKey, period);
                } else if (lowerUrl.contains("dashscope") || lowerUrl.contains("aliyun")
                        || lowerUrl.contains("maas.aliyuncs.com")) {
                    // 阿里云百炼（公共端点 / 专属空间）：无标准 usage/balance 接口，
                    // 用量只能在阿里云控制台查看。友好提示而非"使用量 API 不可用"报错
                    //（连通性已由 models 接口验证，此处不重复失败）。
                    UsageInfo info = new UsageInfo();
                    info.supported = false;
                    info.errorMessage = "百炼用量请在阿里云控制台查看（应用内不查询余额/用量）";
                    return info;
                } else if (lowerUrl.contains("openai") || lowerUrl.contains("azure")) {
                    return fetchOpenAIUsage(apiUrl, apiKey, period);
                } else {
                    return fetchOpenAIUsage(apiUrl, apiKey, period);
                }
            } catch (Exception e) {
                AILogger.e(TAG, "Fetch usage failed: " + e.getMessage(), e);
                UsageInfo info = new UsageInfo();
                info.supported = false;
                info.errorMessage = e.getMessage();
                return info;
            }
        }, executor);
    }

    /**
     * 获取使用量信息（默认月周期）
     */
    public CompletableFuture<UsageInfo> fetchUsage(String apiUrl, String apiKey) {
        return fetchUsage(apiUrl, apiKey, "monthly");
    }

    /**
     * 构建用量查询 URL，兼容版本路径结尾的 API 地址：
     * 已以 /v1、/v4 等结尾（如智谱 /api/paas/v4）→ 直接拼 pathSuffix；
     * 否则默认补 /v1 + pathSuffix（OpenAI/Anthropic 等）。
     */
    private String buildApiUrl(String apiUrl, String pathSuffix) {
        String base = apiUrl;
        if (base == null || base.isEmpty()) {
            base = "https://api.openai.com";
        }
        base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (base.matches(".*/v\\d+$")) {
            return base + pathSuffix;
        }
        return base + "/v1" + pathSuffix;
    }

    /**
     * 获取 OpenAI 使用量
     */
    private UsageInfo fetchOpenAIUsage(String apiUrl, String apiKey, String period) throws Exception {
        // OpenAI 的使用量 API
        String fullUrl = buildApiUrl(apiUrl, "/usage?aggregate_usage_id=daily_usage");

        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            com.oilquiz.app.ai.model.ProviderConfigManager.get()
                    .applyAuthHeaders(connection, fullUrl, apiKey, null, null);
            connection.setRequestProperty("Content-Type", "application/json");

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                // 使用量 API 可能不可用，返回不支持状态
                UsageInfo info = new UsageInfo();
                info.supported = false;
                info.errorMessage = "使用量 API 不可用 (HTTP " + responseCode + ")";
                return info;
            }

            return parseOpenAIUsageResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 获取 Anthropic 使用量
     */
    private UsageInfo fetchAnthropicUsage(String apiUrl, String apiKey, String period) throws Exception {
        // Anthropic 的成本 API
        String fullUrl = buildApiUrl(apiUrl, "/users/cost");

        URL url = new URL(fullUrl);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            connection.setReadTimeout(DEFAULT_TIMEOUT_MS);
            com.oilquiz.app.ai.model.ProviderConfigManager.get()
                    .applyAuthHeaders(connection, fullUrl, apiKey, null, null);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                UsageInfo info = new UsageInfo();
                info.supported = false;
                info.errorMessage = "使用量 API 不可用 (HTTP " + responseCode + ")";
                return info;
            }

            return parseAnthropicUsageResponse(connection);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 解析 OpenAI 使用量响应
     */
    private UsageInfo parseOpenAIUsageResponse(HttpURLConnection connection) throws Exception {
        InputStream inputStream = connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        } finally {
            reader.close();
        }

        UsageInfo info = new UsageInfo();
        info.supported = true;
        info.period = "monthly";
        info.quotaType = "tokens";

        try {
            JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();

            if (json.has("total_usage")) {
                long totalUsage = json.get("total_usage").getAsLong();
                info.usedQuota = totalUsage;
                // OpenAI API 不直接提供总量，需要从账户信息获取
                // 这里假设总量未知，显示已使用量
                info.totalQuota = 0;
                info.remainingQuota = 0;
            }

            // 尝试从账户信息获取总量
            if (json.has("object") && json.get("object").getAsString().equals("list")) {
                JsonArray data = json.getAsJsonArray("data");
                if (data != null && data.size() > 0) {
                    // 使用每日使用量数据
                    long totalUsage = 0;
                    for (int i = 0; i < data.size(); i++) {
                        JsonObject dayData = data.get(i).getAsJsonObject();
                        if (dayData.has("n_tokens_used")) {
                            totalUsage += dayData.get("n_tokens_used").getAsLong();
                        }
                    }
                    info.usedQuota = totalUsage;
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to parse usage response: " + e.getMessage());
        }

        info.lastUpdated = System.currentTimeMillis();
        return info;
    }

    /**
     * 解析 Anthropic 使用量响应
     */
    private UsageInfo parseAnthropicUsageResponse(HttpURLConnection connection) throws Exception {
        InputStream inputStream = connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();

        try {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        } finally {
            reader.close();
        }

        UsageInfo info = new UsageInfo();
        info.supported = true;
        info.period = "monthly";
        info.quotaType = "credits";
        info.unitName = "USD";

        try {
            JsonObject json = JsonParser.parseString(response.toString()).getAsJsonObject();

            if (json.has("cost")) {
                info.usedCost = json.get("cost").getAsDouble();
                info.usedQuota = (long) (info.usedCost * 1000000); // 转换为 tokens 模拟
            }

            if (json.has("total_budget")) {
                info.totalQuota = (long) (json.get("total_budget").getAsDouble() * 1000000);
                info.remainingQuota = (long) ((json.get("total_budget").getAsDouble() - info.usedCost) * 1000000);
            }
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to parse Anthropic usage response: " + e.getMessage());
        }

        info.lastUpdated = System.currentTimeMillis();
        return info;
    }

    /**
     * 模拟计算剩余额度（当 API 不提供时）
     * 基于 OpenAI 的典型订阅计划
     */
    public UsageInfo calculateEstimatedUsage(long usedTokens, double costPerThousandTokens) {
        UsageInfo info = new UsageInfo();
        info.supported = true;
        info.usedQuota = usedTokens;
        info.quotaType = "tokens";
        info.usedCost = (usedTokens / 1000.0) * costPerThousandTokens;
        info.lastUpdated = System.currentTimeMillis();
        // 这是一个估算值，需要用户实际查看订阅计划
        info.totalQuota = 0; // 未知
        info.remainingQuota = 0; // 未知
        info.errorMessage = "请在 OpenAI 网站查看实际使用量";
        return info;
    }

    /**
     * 关闭服务
     */
    public void shutdown() {
        executor.shutdown();
    }
}