package com.oilquiz.app.ai.usage;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.usage.db.DatabaseManager;
import com.oilquiz.app.ai.usage.db.UsageDatabase;
import com.oilquiz.app.ai.usage.db.entity.ApiProviderConfig;
import com.oilquiz.app.ai.usage.db.entity.ApiPriceConfig;
import com.oilquiz.app.ai.usage.db.entity.ApiRequestSchema;
import com.oilquiz.app.ai.usage.db.entity.ApiResponseSchema;
import com.oilquiz.app.ai.usage.db.entity.SyncLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 配置同步管理器
 * 管理远程/本地/Assets 三级降级策略
 */
public class ConfigSyncManager {
    
    private static final String TAG = "ConfigSyncManager";
    
    private final Context context;
    private GiteeRemoteDataSource remoteDataSource;
    private final LocalDataSource localDataSource;
    private final AssetDataSource assetDataSource;
    private final UsageDatabase usageDatabase;
    
    private RemoteConfig currentConfig;
    
    /**
     * 构造器 - 不需要传 URL, 从配置文件读取
     */
    public ConfigSyncManager(Context context) {
        this.context = context.getApplicationContext();
        this.remoteDataSource = new GiteeRemoteDataSource();  // 先不传 URL
        this.localDataSource = new LocalDataSource(context);
        this.assetDataSource = new AssetDataSource(context);
        this.usageDatabase = DatabaseManager.getInstance(context);
    }
    
    /**
     * 启动时同步配置 (阻塞式)
     * 流程: 首次启动 → 从 Assets 初始化默认数据 → 远程同步 → 有更新则覆盖
     * @return 同步结果
     */
    public SyncResult syncOnStartup() {
        Log.i(TAG, "=== 开始配置同步 ===");
        
        // 获取上次同步版本
        Integer lastVersion = usageDatabase.syncLogDao().getLastConfigVersion();
        Log.i(TAG, "上次同步版本: " + (lastVersion != null ? lastVersion : 0));
        
        // 首次启动: 从 Assets 初始化默认数据, 确保数据库有数据
        if (lastVersion == null || lastVersion == 0) {
            Log.i(TAG, "首次启动, 初始化默认数据...");
            RemoteConfig defaultConfig = assetDataSource.loadDefaultConfig();
            if (defaultConfig != null) {
                saveConfigToDatabase(defaultConfig, lastVersion);
                currentConfig = defaultConfig;
                // 从默认配置中读取 Gitee URL, 初始化远程数据源
                initRemoteDataSource(defaultConfig);
                Log.i(TAG, "=== 默认数据初始化完成, 版本: " + defaultConfig.getConfigVersion());
            } else {
                Log.e(TAG, "Assets 默认配置加载失败!");
                return new SyncResult.Error(new Exception("默认配置加载失败"));
            }
        } else {
            // 非首次启动: 尝试从本地缓存加载配置获取 Gitee URL
            RemoteConfig cached = localDataSource.loadCachedConfig();
            if (cached != null) {
                initRemoteDataSource(cached);
            }
        }
        
        // 尝试从 Gitee 拉取更新
        try {
            RemoteConfig remote = remoteDataSource.fetchConfigSync();
            Log.i(TAG, "Gitee 远程配置获取成功, 版本: " + remote.getConfigVersion());
            
            // 版本号无变化, 跳过
            if (remote.getConfigVersion() <= lastVersion) {
                Log.i(TAG, "配置版本无变化, 跳过同步");
                currentConfig = remote;
                return new SyncResult.NoChange(remote.getConfigVersion());
            }
            
            // 有更新, 覆盖数据库
            saveConfigToDatabase(remote, lastVersion);
            currentConfig = remote;
            
            Log.i(TAG, "=== 配置同步完成 (远程) ===");
            return new SyncResult.Success(remote.getConfigVersion(), SyncResult.SOURCE.REMOTE);
            
        } catch (Exception e) {
            Log.e(TAG, "Gitee 远程配置获取失败: " + e.getMessage());
            Log.i(TAG, "使用本地已有配置, 版本: " + lastVersion);
        }
        
        // 远程不可用, 返回本地现有配置
        Log.i(TAG, "=== 使用本地缓存, 版本: " + lastVersion);
        return new SyncResult.Success(lastVersion, SyncResult.SOURCE.CACHE);
    }
    
    /**
     * 从配置中初始化远程数据源
     */
    private void initRemoteDataSource(RemoteConfig config) {
        if (remoteDataSource != null) {
            remoteDataSource.initFromConfig(config);
        }
    }
    
    /**
     * 将配置保存到数据库
     * @param config 远程配置
     * @param lastVersion 上一次同步的版本号, 可为 null
     */
    private void saveConfigToDatabase(RemoteConfig config, Integer lastVersion) {
        Log.i(TAG, "开始保存配置到数据库, 版本: " + config.getConfigVersion());
        
        // 清除旧配置
        usageDatabase.providerDao().clearAll();
        usageDatabase.priceDao().clearAll();
        usageDatabase.schemaDao().clearAll();
        usageDatabase.requestSchemaDao().clearAll();
        
        // 保存服务商
        List<RemoteConfig.ProviderConfig> providers = config.getProviders();
        if (providers != null) {
            for (RemoteConfig.ProviderConfig p : providers) {
                ApiProviderConfig pc = new ApiProviderConfig();
                pc.setProviderId(p.getProviderId());
                pc.setDisplayName(p.getDisplayName());
                pc.setApiBase(p.getApiBase());
                pc.setAuthType(p.getAuthType());
                pc.setAuthHeaderName(p.getAuthHeaderName());
                usageDatabase.providerDao().insertProvider(pc);
            }
            Log.i(TAG, "保存服务商: " + providers.size() + " 个");
        }
        
        // 保存价格
        List<RemoteConfig.PriceConfig> prices = config.getPrices();
        if (prices != null) {
            for (RemoteConfig.PriceConfig price : prices) {
                ApiPriceConfig pc = new ApiPriceConfig();
                pc.setModelId(price.getModelId());
                pc.setProviderId(price.getProviderId());
                pc.setPriceType(price.getPriceType());
                pc.setPricePer1M(price.getPricePer1M());
                pc.setCurrency(price.getCurrency());
                pc.setMinContext(price.getMinContext());
                pc.setMaxContext(price.getMaxContext());
                usageDatabase.priceDao().insertPrice(pc);
            }
            Log.i(TAG, "保存价格: " + prices.size() + " 条");
        }
        
        // 保存响应 Schema
        List<RemoteConfig.ResponseSchema> schemas = config.getResponseSchemas();
        if (schemas != null) {
            for (RemoteConfig.ResponseSchema s : schemas) {
                ApiResponseSchema as = new ApiResponseSchema();
                as.setSchemaId(s.getSchemaId());
                as.setProviderId(s.getProviderId());
                as.setModelId(s.getModelId());
                as.setRootPath(s.getRootPath());
                as.setInputTokenPath(s.getInputTokenPath());
                as.setOutputTokenPath(s.getOutputTokenPath());
                as.setCacheHitTokenPath(s.getCacheHitTokenPath());
                as.setCacheCreateTokenPath(s.getCacheCreateTokenPath());
                usageDatabase.schemaDao().insertSchema(as);
            }
            Log.i(TAG, "保存响应 Schema: " + schemas.size() + " 个");
        }
        
        // 保存请求 Schema
        List<RemoteConfig.RequestSchema> requestSchemas = config.getRequestSchemas();
        if (requestSchemas != null) {
            for (RemoteConfig.RequestSchema s : requestSchemas) {
                ApiRequestSchema ars = new ApiRequestSchema();
                ars.setSchemaId(s.getSchemaId());
                ars.setProviderId(s.getProviderId());
                ars.setModelId(s.getModelId());
                ars.setRequestBodyPath(s.getRequestBodyPath());
                ars.setMessagesPath(s.getMessagesPath());
                ars.setMessageRolePath(s.getMessageRolePath());
                ars.setMessageContentPath(s.getMessageContentPath());
                ars.setSystemMessageRole(s.getSystemMessageRole());
                ars.setUserMessageRole(s.getUserMessageRole());
                ars.setAssistantMessageRole(s.getAssistantMessageRole());
                ars.setTemperaturePath(s.getTemperaturePath());
                ars.setTopPPath(s.getTopPPath());
                ars.setMaxTokensPath(s.getMaxTokensPath());
                ars.setStreamPath(s.getStreamPath());
                ars.setSupportsStream(s.isSupportsStream());
                usageDatabase.requestSchemaDao().insertSchema(ars);
            }
            Log.i(TAG, "保存请求 Schema: " + requestSchemas.size() + " 个");
        }
        
        // 记录同步日志
        SyncLog syncLog = new SyncLog();
        syncLog.setSyncType("startup");
        syncLog.setRemoteVersion(config.getConfigVersion());
        syncLog.setLocalVersion(lastVersion != null ? lastVersion : 0);
        syncLog.setSyncStatus("success");
        usageDatabase.syncLogDao().insert(syncLog);
        
        Log.i(TAG, "配置保存完成");
    }
    
    /**
     * 定时同步 (非阻塞)
     * @param listener 结果监听器
     */
    public void syncScheduled(ConfigSyncListener listener) {
        new Thread(() -> {
            SyncResult result = syncOnStartup();
            
            if (listener != null) {
                listener.onSyncComplete(result);
            }
        }).start();
    }
    
    /**
     * 手动触发同步
     * @param listener 结果监听器
     */
    public void syncManual(ConfigSyncListener listener) {
        Log.i(TAG, "手动触发配置同步");
        syncScheduled(listener);
    }
    
    /**
     * 获取当前配置
     */
    public RemoteConfig getCurrentConfig() {
        return currentConfig;
    }
    
    /**
     * 同步监听器
     */
    public interface ConfigSyncListener {
        void onSyncComplete(SyncResult result);
    }
    
    /**
     * 同步结果
     */
    public static class SyncResult {
        public enum SOURCE {
            REMOTE,    // Gitee 远程
            CACHE,     // 本地缓存
            ASSETS     // Assets 内置
        }
        
        private final boolean success;
        private final SOURCE source;
        private final int version;
        private final Exception error;
        
        private SyncResult(boolean success, SOURCE source, int version, Exception error) {
            this.success = success;
            this.source = source;
            this.version = version;
            this.error = error;
        }
        
        /**
         * 同步成功
         */
        public static class Success extends SyncResult {
            public Success(int version, SOURCE source) {
                super(true, source, version, null);
            }
            
            @Override
            public String toString() {
                return "SyncResult{success=true, source=" + getSource() + ", version=" + getVersion() + "}";
            }
        }
        
        /**
         * 同步失败 (版本无变化)
         */
        public static class NoChange extends SyncResult {
            public NoChange(int localVersion) {
                super(true, SOURCE.REMOTE, localVersion, null);
            }
            
            @Override
            public String toString() {
                return "SyncResult{success=true, noChange=true, version=" + getVersion() + "}";
            }
        }
        
        /**
         * 同步失败
         */
        public static class Error extends SyncResult {
            public Error(Exception error) {
                super(false, null, 0, error);
            }
            
            @Override
            public String toString() {
                return "SyncResult{success=false, error=" + getError().getMessage() + "}";
            }
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public SOURCE getSource() {
            return source;
        }
        
        public int getVersion() {
            return version;
        }
        
        public Exception getError() {
            return error;
        }
    }
}
