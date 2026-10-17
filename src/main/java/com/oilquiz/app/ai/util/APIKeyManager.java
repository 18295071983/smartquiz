package com.oilquiz.app.ai.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.oilquiz.app.ai.model.APIConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 旧密钥体系（{@code api_keys} SharedPreferences）的读写入口。
 *
 * <p>落盘只有一种格式：<b>Android Keystore AES-256-GCM</b>（主密钥不出芯片、每次随机 IV，
 * 见 {@link SecretCodec} 与 {@link ApiKeyCipher}）。首次使用时由 {@link SecretMigrator}
 * 把存量的 AES-CBC 固定 IV 数据<b>一次性</b>搬过去，然后删除同文件内存放的旧主密钥与 IV ——
 * 此后系统里不存在能解旧格式的代码路径。</p>
 *
 * <p><b>旧格式不做兼容</b>（明确要求）：读取只认 {@code enc:}，别的值一律视为"未配置"；
 * 写入只产 {@code enc:}，Keystore 不可用时<b>整笔放弃</b>而不是降级写明文 ——
 * 一条读不出的条目只是"用户需要重填密钥"，而一条明文密钥是谁都能读的。</p>
 */
public class APIKeyManager {

    private static final String TAG = "APIKeyManager";
    private static final String PREF_NAME = "api_keys";
    /** 旧格式主密钥（迁移后删除；不再新建） */
    private static final String KEY_ENCRYPTION_KEY = "encryption_key";
    /** 旧格式固定 IV（迁移后删除；不再新建） */
    private static final String IV_KEY = "iv_key";
    private static final String KEY_API_LIST = "api_config_list";
    private static final String KEY_ACTIVE_API_ID = "active_api_id";
    private static final String KEY_ARCHIVE_PATH = "api_archive_path";
    private static final String HOST_SUFFIX = "_host";

    /**
     * 迁移扫描必须跳过的键：明文配置项（活动 ID、存档路径 —— 它们不是密钥，一旦被"升级"成密文，
     * 读取方就拿不到原值了）、旧主密钥材料自身（迁移器最后删除它俩）、完成标志。
     *
     * <p>{@code api_config_list} <b>不在</b>此列 —— 它是整份配置列表的密文，正是迁移的主体；
     * QWeather JWT 四项同理。</p>
     */
    private static final Set<String> MIGRATION_SKIP_KEYS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(KEY_ENCRYPTION_KEY, IV_KEY,
                    KEY_ACTIVE_API_ID, KEY_ARCHIVE_PATH, SecretMigrator.DEFAULT_FLAG_KEY)));

    private static APIKeyManager instance;
    private final Context context;
    private final SharedPreferences preferences;
    private final Gson gson;
    /** Keystore GCM 主密钥（生产实现 = {@link ApiKeyCipher}） */
    private final SecretCodec.GcmCipher gcmCipher = new KeystoreGcm();
    /** 旧 CBC 主密钥材料：只读，仅供一次性搬运；迁移后从磁盘删除 */
    private volatile SecretMigrator.LegacyCbcKey legacyKey;
    /** 后台搬运线程：一次性，跑完即关，不留常驻 */
    private final ExecutorService migrationExecutor = Executors.newSingleThreadExecutor();
    /** 本进程是否已真跑过搬运（成功与否都算跑过，避免每次读取都重试） */
    private final AtomicBoolean migrationAttempted = new AtomicBoolean(false);

    private APIKeyManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new GsonBuilder().setPrettyPrinting().create();
        // Keystore 首次生成主密钥 + 逐条加解密在低端机上可达数百毫秒，不该压在构造线程上；
        // 稳态读取若碰到旧格式残留会就地同步补跑（见 migrateSecretsOnce）
        try {
            migrationExecutor.execute(() -> {
                migrateSecretsOnce();
                migrationExecutor.shutdown();
            });
        } catch (Throwable t) {
            Log.e(TAG, "无法启动密钥搬运任务（读取路径会就地补跑）: " + t.getMessage(), t);
        }
    }

    public static synchronized APIKeyManager getInstance(Context context) {
        if (instance == null) {
            instance = new APIKeyManager(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * 懒加载旧 CBC 主密钥材料（只读，仅服务一次性搬运）。
     *
     * <p>新装应用没有这两项 → {@link SecretMigrator.LegacyCbcKey#none()}。搬运完成后磁盘上的
     * 旧主密钥与固定 IV 被删除，本方法此后返回 none；内存里可能仍留着已删除的副本 —— 无害：
     * 稳态读取只认 {@code enc:}，不再调用它。</p>
     */
    private SecretMigrator.LegacyCbcKey legacyKey() {
        SecretMigrator.LegacyCbcKey local = legacyKey;
        if (local == null) {
            local = new SecretMigrator.LegacyCbcKey(
                    preferences.getString(KEY_ENCRYPTION_KEY, null),
                    preferences.getString(IV_KEY, null));
            legacyKey = local;
        }
        return local;
    }

    /**
     * 一次性把存量旧格式搬到 Keystore-GCM（进程内至多真跑一次，幂等）。
     *
     * <p>两个入口共用本方法：构造期后台先跑（让正常启动不为密码学付费）；稳态读取若碰到
     * 旧格式残留（后台那趟还没跑完、或跑的时候 Keystore 还没解锁），就地同步补跑一次再读，
     * 避免用户看到"未配置密钥"这种假象。跑完（无论成败）就置位，不再反复重试 ——
     * 下一次进程启动会再试。</p>
     */
    private void migrateSecretsOnce() {
        if (migrationAttempted.get()) return;
        synchronized (this) {
            if (migrationAttempted.get()) return;
            try {
                SecretMigrator.Result result = SecretMigrator.run(
                        new PrefsSecretKv(preferences), gcmCipher, legacyKey(),
                        MIGRATION_SKIP_KEYS,
                        Arrays.asList(KEY_ENCRYPTION_KEY, IV_KEY),
                        SecretMigrator.DEFAULT_FLAG_KEY);
                if (result.complete) {
                    Log.i(TAG, "密钥存储已升级为 Keystore-GCM：" + result
                            + "（旧主密钥与固定 IV 已从磁盘删除）");
                } else {
                    Log.w(TAG, "密钥存储迁移未完成（Keystore 不可用或与并发写冲突），"
                            + "本次不改动磁盘，下次启动再试：" + result);
                }
            } catch (Throwable t) {
                Log.e(TAG, "密钥存储迁移异常（数据保持原样）: " + t, t);
            } finally {
                migrationAttempted.set(true);
            }
        }
    }

    /** SharedPreferences → 迁移器所需的最小 KV（CAS 用同步块 + 提交结果保证）。 */
    private static final class PrefsSecretKv implements SecretMigrator.SecretKv {
        private final SharedPreferences prefs;

        PrefsSecretKv(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        @Override
        public Set<String> keys() {
            return prefs.getAll().keySet();
        }

        @Override
        public String get(String key) {
            try {
                return prefs.getString(key, null);
            } catch (ClassCastException notAString) {
                // 该文件里混有非字符串条目时（历史遗留或别处写入）不参与迁移：
                // 迁移只负责字符串形态的密钥值，其他类型的语义我们不猜
                return null;
            }
        }

        @Override
        public boolean replaceIfUnchanged(String key, String expectedCurrent, String newValue) {
            synchronized (this) {
                String current;
                try {
                    current = prefs.getString(key, null);
                } catch (ClassCastException e) {
                    return false;
                }
                if (expectedCurrent == null ? current != null : !expectedCurrent.equals(current)) {
                    return false;
                }
                return prefs.edit().putString(key, newValue).commit();
            }
        }

        @Override
        public void markMigrated(String flagKey) {
            prefs.edit().putString(flagKey, "true").commit();
        }

        @Override
        public boolean isMigrated(String flagKey) {
            return "true".equals(prefs.getString(flagKey, null));
        }

        @Override
        public void delete(String key) {
            prefs.edit().remove(key).commit();
        }
    }

    /** 生产 GCM 实现：委托 {@link ApiKeyCipher}（Android Keystore，主密钥不可导出）。 */
    private static final class KeystoreGcm implements SecretCodec.GcmCipher {

        /**
         * "已确认可用"缓存。
         *
         * <p>每次解密都开一次 KeyStore 是不必要的开销（KeyStore.getInstance + load）；
         * 而"当前不可用"不能缓存 —— Keystore 的瞬时失败（解锁后首次访问、进程重启竞态）
         * 必须能自愈，所以只有 true 会被记住。</p>
         */
        private volatile boolean availableConfirmed;

        @Override
        public String tryEncrypt(String plaintext) {
            try {
                return ApiKeyCipher.encrypt(plaintext);
            } catch (Throwable t) {
                Log.w(TAG, "Keystore 加密不可用: " + t.getMessage());
                availableConfirmed = false;
                return null;
            }
        }

        @Override
        public String tryDecrypt(String storedWithPrefix) {
            try {
                return ApiKeyCipher.decrypt(storedWithPrefix);
            } catch (Throwable t) {
                Log.w(TAG, "Keystore 解密不可用: " + t.getMessage());
                availableConfirmed = false;
                return null;
            }
        }

        @Override
        public boolean isAvailable() {
            if (availableConfirmed) return true;
            boolean ok = ApiKeyCipher.keystoreAvailable();
            availableConfirmed = ok;
            return ok;
        }
    }

    public void saveAPIKey(String service, String apiKey) {
        try {
            if (apiKey == null || apiKey.isEmpty()) {
                // 空值语义就是"清除"：删条目，而不是存一份解出来是空的密文
                // （新格式写入空串会被判定为"没加密"而放弃，留着旧值更糟）。
                removeAPIKey(service);
                return;
            }
            String encryptedKey = encrypt(apiKey);
            if (encryptedKey == null) {
                // 写不出密文就什么都不写：putString(key, null) 会把条目删掉，
                // 那等于"保存失败顺手清了旧数据"；退化成明文更没有加密可言。
                Log.e(TAG, "Keystore 不可用，密钥未保存（既有值保持原样）: " + service);
                return;
            }
            preferences.edit().putString(service, encryptedKey).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving API key for service: " + service, e);
        }
    }

    public String getAPIKey(String service) {
        try {
            return readSecret(service);
        } catch (Exception e) {
            Log.e(TAG, "Error getting API key for service: " + service, e);
            return null;
        }
    }

    public void removeAPIKey(String service) {
        preferences.edit().remove(service).apply();
    }

    public void saveAPIHost(String service, String apiHost) {
        try {
            if (apiHost == null || apiHost.isEmpty()) {
                preferences.edit().remove(service + HOST_SUFFIX).apply();
                return;
            }
            String encryptedHost = encrypt(apiHost);
            if (encryptedHost == null) {
                Log.e(TAG, "Keystore 不可用，API 地址未保存（既有值保持原样）: " + service);
                return;
            }
            preferences.edit().putString(service + HOST_SUFFIX, encryptedHost).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving API host for service: " + service, e);
        }
    }

    public String getAPIHost(String service) {
        try {
            return readSecret(service + HOST_SUFFIX);
        } catch (Exception e) {
            Log.e(TAG, "Error getting API host for service: " + service, e);
            return null;
        }
    }

    /**
     * 稳态读取的唯一入口：取某条密钥 → 只按 {@code enc:} 格式解。
     *
     * <p>碰到非 {@code enc:} 的残留值，说明构造期那趟后台搬运还没落地（进程刚起、
     * 或当时 Keystore 还没解锁）：就地同步补跑一次再读，别把"未配置"糊弄给调用方。
     * {@link #migrateSecretsOnce()} 进程内至多真跑一次，所以这里不会退化成反复加解密。</p>
     *
     * @return 明文；{@code null} = 没有该条目，或有值但解不出（此时日志已说明原因）
     */
    private String readSecret(String key) {
        String stored = preferences.getString(key, null);
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        if (!SecretCodec.isTargetFormat(stored)) {
            migrateSecretsOnce();
            stored = preferences.getString(key, null);
            if (stored == null || stored.isEmpty()) {
                // 迁移判定这条已不可用（解不出/空值）→ 已作废删除
                return null;
            }
        }
        String plain = decrypt(stored);
        if (plain == null) {
            Log.w(TAG, "读不出密钥 " + key + "：非 Keystore-GCM 格式，或主密钥已丢失（换机/恢复备份）"
                    + "，需在设置中重新填写");
        }
        return plain;
    }

    /**
     * 落盘编码：只产出 Keystore-GCM 一种格式。
     *
     * @return 密文；{@code null} = 本次写不成（调用方必须放弃写入，见各 save 方法）
     */
    private String encrypt(String plaintext) {
        return SecretCodec.encode(plaintext, gcmCipher);
    }

    /**
     * 落盘解码：只认 {@code enc:} 格式（旧格式不做兼容，见 {@link SecretCodec}）。
     *
     * @return 明文；{@code null} = 这条不算数据（旧格式残留或主密钥已丢），按"未配置"处理
     */
    private String decrypt(String ciphertext) {
        return SecretCodec.decode(ciphertext, gcmCipher);
    }

    public void saveAPIConfig(APIConfig config) {
        if (config == null) {
            Log.w(TAG, "Cannot save null APIConfig");
            return;
        }
        List<APIConfig> configs = getAllAPIConfigs();
        int index = findConfigIndex(configs, config.getId());
        if (index >= 0) {
            configs.set(index, config);
        } else {
            configs.add(config);
        }
        saveAPIConfigs(configs);
    }

    public void saveAPIConfigs(List<APIConfig> configs) {
        try {
            if (configs == null) {
                configs = new ArrayList<>();
            }
            String json = gson.toJson(configs);
            String encrypted = encrypt(json);
            if (encrypted == null) {
                // 关键护栏：getAllAPIConfigs() 在读不出时返回空表，若此时照常写入，
                // "读-改-写"会把用户全部在线配置抹成只剩本次这一条。写不出密文就整笔放弃，
                // 磁盘保持原样（而不是写明文，也不是 putString(key, null) 顺手删掉）。
                Log.e(TAG, "Keystore 不可用，配置列表写入已放弃（既有数据原样保留，未清空）");
                return;
            }
            preferences.edit().putString(KEY_API_LIST, encrypted).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving API configs", e);
        }
    }

    /**
     * 已解出的 JSON → 配置列表。
     *
     * @return 列表；{@code null} 表示"内容不是合法 JSON"（与"没有值/空列表"区分开，
     *         便于日志定位；写入路径只依赖 Keystore 是否可用，见 {@link #saveAPIConfigs(List)}）
     */
    private List<APIConfig> parseConfigListJson(String json) {
        try {
            Type type = new TypeToken<List<APIConfig>>() {
            }.getType();
            List<APIConfig> configs = gson.fromJson(json, type);
            return configs != null ? configs : new ArrayList<>();
        } catch (JsonSyntaxException e) {
            Log.e(TAG, "api_config_list 内容不是合法 JSON", e);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "api_config_list 解析失败", e);
            return null;
        }
    }

    public List<APIConfig> getAllAPIConfigs() {
        try {
            // 走 readSecret：列表若是旧格式，会先就地补跑搬运再读
            String json = readSecret(KEY_API_LIST);
            if (json == null || json.isEmpty()) {
                return new ArrayList<>();
            }
            List<APIConfig> configs = parseConfigListJson(json);
            return configs != null ? configs : new ArrayList<>();
        } catch (Exception e) {
            Log.e(TAG, "Error getting all API configs", e);
        }
        return new ArrayList<>();
    }

    public APIConfig getAPIConfigById(String id) {
        List<APIConfig> configs = getAllAPIConfigs();
        for (APIConfig config : configs) {
            if (config.getId().equals(id)) {
                return config;
            }
        }
        return null;
    }

    public APIConfig getAPIConfigByServiceType(String serviceType) {
        List<APIConfig> configs = getAllAPIConfigs();
        for (APIConfig config : configs) {
            if (serviceType.equals(config.getServiceType()) && config.isActive()) {
                return config;
            }
        }
        return null;
    }

    public void deleteAPIConfig(String id) {
        List<APIConfig> configs = getAllAPIConfigs();
        int index = findConfigIndex(configs, id);
        if (index >= 0) {
            configs.remove(index);
            saveAPIConfigs(configs);
            
            String activeId = getActiveAPIId();
            if (id.equals(activeId) && !configs.isEmpty()) {
                setActiveAPI(configs.get(0).getId());
            }
        }
    }

    public void toggleAPIConfigActive(String id) {
        List<APIConfig> configs = getAllAPIConfigs();
        for (APIConfig config : configs) {
            if (config.getId().equals(id)) {
                config.setActive(!config.isActive());
                break;
            }
        }
        saveAPIConfigs(configs);
    }

    private int findConfigIndex(List<APIConfig> configs, String id) {
        for (int i = 0; i < configs.size(); i++) {
            if (configs.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    public void setActiveAPI(String id) {
        preferences.edit().putString(KEY_ACTIVE_API_ID, id).apply();
    }

    public String getActiveAPIId() {
        return preferences.getString(KEY_ACTIVE_API_ID, null);
    }

    public APIConfig getActiveAPIConfig() {
        String activeId = getActiveAPIId();
        if (activeId != null) {
            return getAPIConfigById(activeId);
        }
        return null;
    }

    public String getDefaultArchivePath() {
        try {
            File externalDir = context.getExternalFilesDir(null);
            if (externalDir != null) {
                return externalDir.getAbsolutePath() + File.separator + "api_archive";
            }
        } catch (Exception e) {
            Log.w(TAG, "无法获取外部存储路径，尝试使用内部存储", e);
        }
        
        File internalDir = context.getFilesDir();
        return internalDir.getAbsolutePath() + File.separator + "api_archive";
    }

    public String getArchivePath() {
        String savedPath = preferences.getString(KEY_ARCHIVE_PATH, null);
        if (savedPath != null && !savedPath.isEmpty()) {
            return savedPath;
        }
        return getDefaultArchivePath();
    }

    public void setArchivePath(String path) {
        preferences.edit().putString(KEY_ARCHIVE_PATH, path).apply();
    }

    public boolean ensureArchiveDirectoryExists() {
        String path = getArchivePath();
        try {
            File dir = new File(path);
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                if (created) {
                    Log.i(TAG, "存档目录已创建: " + path);
                }
                return created;
            }
            return dir.isDirectory();
        } catch (Exception e) {
            Log.e(TAG, "创建存档目录失败: " + path, e);
            return false;
        }
    }

    public boolean isUsingDefaultArchivePath() {
        String savedPath = preferences.getString(KEY_ARCHIVE_PATH, null);
        return savedPath == null || savedPath.isEmpty();
    }

    public void resetToDefaultArchivePath() {
        preferences.edit().remove(KEY_ARCHIVE_PATH).apply();
    }

    public List<APIConfig> getAPIConfigsByCategory(String category) {
        List<APIConfig> result = new ArrayList<>();
        for (APIConfig config : getAllAPIConfigs()) {
            if (category.equals(config.getCategory())) {
                result.add(config);
            }
        }
        return result;
    }

    public List<APIConfig> getActiveAPIConfigs() {
        List<APIConfig> result = new ArrayList<>();
        for (APIConfig config : getAllAPIConfigs()) {
            if (config.isActive()) {
                result.add(config);
            }
        }
        return result;
    }

    public CompletableFuture<TestResult> testAPIConnection(APIConfig config) {
        return CompletableFuture.supplyAsync(() -> {
            TestResult result = new TestResult();
            
            if (config == null) {
                result.success = false;
                result.message = "API配置不能为空";
                return result;
            }
            
            result.configId = config.getId();
            result.configName = config.getName();
            result.startTime = System.currentTimeMillis();

            try {
                // METASO-TEST(2026-10-11)：秘塔搜索必须走专用校验，不能走下面的通用 GET 流程。
                // 原因（已联网核实）：秘塔只提供 POST /api/v1/search 这类业务端点，
                // 没有账号/余额/校验类的轻量 GET 端点，而通用流程是"GET 后看 200" → 必然误判
                // （实测该端点对 GET 直接返回 405 Method Not Allowed，连鉴权都没走到）。
                // 专用校验复用 NetworkSearchTool.testApiKey（与运行时同一套协议与错误解析，
                // 避免"测试通过但实际不能用"）。代价：秘塔按次计费，一次测试消耗一次额度。
                if (APIConfig.ServiceType.METASO_SEARCH.equals(config.getServiceType())
                        || APIConfig.ServiceType.BING_SEARCH.equals(config.getServiceType())) {
                    try {
                        com.oilquiz.app.ai.tool.NetworkSearchTool metaso =
                                new com.oilquiz.app.ai.tool.NetworkSearchTool(context);
                        String r = metaso.testApiKey(config.getApiKey());
                        result.latency = System.currentTimeMillis() - result.startTime;
                        if (r != null && r.startsWith("OK|")) {
                            result.success = true;
                            result.responseCode = 200;
                            try {
                                result.latency = Long.parseLong(r.substring(3));
                            } catch (NumberFormatException ignored) {
                            }
                            result.message = "API连接测试成功（已消耗 1 次搜索额度）";
                            config.setStatus(APIConfig.Status.VALID);
                        } else {
                            result.success = false;
                            String reason = r != null && r.startsWith("FAIL|") ? r.substring(5) : "未知错误";
                            result.message = reason;
                            config.setStatus(reason.contains("限流")
                                    ? APIConfig.Status.RATE_LIMITED : APIConfig.Status.INVALID);
                        }
                        saveAPIConfig(config);
                        return result;
                    } catch (Throwable t) {
                        // 进入专用分支后任何异常都直接返回，避免落到下面的通用 GET 流程误报
                        Log.e(TAG, "Metaso key test failed: " + t.getMessage(), t);
                        result.success = false;
                        result.message = "秘塔搜索测试失败: " + t.getMessage();
                        result.latency = System.currentTimeMillis() - result.startTime;
                        saveAPIConfig(config);
                        return result;
                    }
                }

                String testUrl = getTestUrl(config);
                if (testUrl == null) {
                    result.success = false;
                    result.message = "无法确定测试URL，请手动配置或使用自定义测试";
                    return result;
                }

                // query-key 型服务商（Gemini 等）：密钥走 URL ?key=，在 openConnection 前拼好
                testUrl = com.oilquiz.app.ai.model.ProviderConfigManager.get()
                        .withAuthQuery(testUrl, config.getApiKey());

                URL url = new URL(testUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                
                // 如果是HTTPS连接，禁用SSL证书验证以支持阿里云百炼等服务
                if (connection instanceof HttpsURLConnection) {
                    if (com.oilquiz.app.ai.model.ProviderConfigManager.get().needsTrustAllCerts(testUrl)) {
                        SSLSocketFactoryUtil.disableSSLCertificateValidation((HttpsURLConnection) connection);
                    }
                }
                
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(config.getTimeout() * 1000);
                connection.setReadTimeout(config.getTimeout() * 1000);

                // 统一鉴权：按服务商配置表 auth 类型设置 header
                // （Anthropic 用 x-api-key+anthropic-version；query-key/none 不设 Authorization；
                //   未识别服务商默认 Bearer）
                com.oilquiz.app.ai.model.ProviderConfigManager pcm =
                        com.oilquiz.app.ai.model.ProviderConfigManager.get();
                String authUrl = config.getApiHost() != null && !config.getApiHost().isEmpty()
                        ? config.getApiHost() : testUrl;
                pcm.applyAuthHeaders(connection, authUrl, config.getApiKey(), null, null);
                // 旧体系特殊服务：Bing 用 Ocp-Apim-Subscription-Key
                if (APIConfig.ServiceType.BING_SEARCH.equals(config.getServiceType())) {
                    connection.setRequestProperty("Ocp-Apim-Subscription-Key", config.getApiKey());
                }

                for (Map.Entry<String, String> entry : config.getCustomHeaders().entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }

                int responseCode = connection.getResponseCode();
                result.responseCode = responseCode;
                result.latency = System.currentTimeMillis() - result.startTime;

                if (responseCode == 200) {
                    result.success = true;
                    result.message = "API连接测试成功";
                    config.setStatus(APIConfig.Status.VALID);
                } else if (responseCode == 401) {
                    result.success = false;
                    result.message = "API密钥无效（401 Unauthorized）";
                    config.setStatus(APIConfig.Status.INVALID);
                } else if (responseCode == 429) {
                    result.success = false;
                    result.message = "请求频率超限（429 Too Many Requests）";
                    config.setStatus(APIConfig.Status.RATE_LIMITED);
                } else {
                    result.success = false;
                    result.message = "API返回错误码: " + responseCode;
                }

                connection.disconnect();
            } catch (Exception e) {
                result.success = false;
                result.message = "连接失败: " + e.getMessage();
                result.latency = System.currentTimeMillis() - result.startTime;
            }

            saveAPIConfig(config);
            return result;
        });
    }

    private String getTestUrl(APIConfig config) {
        String serviceType = config.getServiceType();
        String apiHost = config.getApiHost();

        switch (serviceType) {
            case APIConfig.ServiceType.OPENAI:
                return buildOpenAIUrl(apiHost, "https://api.openai.com", "/models");
            case APIConfig.ServiceType.ANTHROPIC:
                return buildOpenAIUrl(apiHost, "https://api.anthropic.com", "/models");
            case APIConfig.ServiceType.GOOGLE:
                return "https://generativelanguage.googleapis.com/v1/models?key=" + config.getApiKey();
            case APIConfig.ServiceType.HEFENG_WEATHER:
                return (apiHost != null ? apiHost : "https://m278m2y7ak.re.qweatherapi.com") + 
                       "/v7/weather/now?location=101010100&key=" + config.getApiKey();
            case APIConfig.ServiceType.BING_SEARCH:
                return "https://api.bing.microsoft.com/v7.0/search?q=test";
            case APIConfig.ServiceType.CUSTOM:
            default:
                // CUSTOM（及自定义 OpenAI 兼容端点）：按 apiHost 测 /models。
                // 从模型选择界面添加的在线模型（智谱/DeepSeek/Kimi/百炼等）同步到
                // 旧体系时 serviceType 均为 CUSTOM——此前落 default 返回 null 导致
                // API 配置管理界面测试全部报"无法确定测试URL"。
                if (apiHost != null && !apiHost.isEmpty()) {
                    return buildOpenAIUrl(apiHost, "https://api.openai.com", "/models");
                }
                return null;
        }
    }

    private String buildOpenAIUrl(String apiHost, String defaultHost, String endpoint) {
        String baseUrl = (apiHost != null && !apiHost.isEmpty()) ? apiHost : defaultHost;
        return com.oilquiz.app.ai.model.ProviderConfigManager.get().buildUrl(baseUrl, endpoint);
    }

    public String exportToJson() {
        List<APIConfig> configs = getAllAPIConfigs();
        List<Map<String, Object>> exportData = new ArrayList<>();
        
        for (APIConfig config : configs) {
            Map<String, Object> data = new HashMap<>();
            data.put("name", config.getName());
            data.put("serviceType", config.getServiceType());
            // 安全：导出文件写入的是公共下载目录（MediaStore Downloads，其他 App 可读），
            // 绝不能含明文密钥。默认脱敏，只保留可辨识的首尾片段用于对照；
            // 完整密钥仅留在应用内的加密存储中。
            data.put("apiKey", maskSecret(config.getApiKey()));
            data.put("apiKeyMasked", true);
            data.put("apiHost", config.getApiHost());
            data.put("modelName", config.getModelName());
            data.put("description", config.getDescription());
            data.put("category", config.getCategory());
            data.put("isActive", config.isActive());
            data.put("timeout", config.getTimeout());
            data.put("customHeaders", config.getCustomHeaders());
            data.put("additionalParams", config.getAdditionalParams());
            exportData.add(data);
        }
        
        return gson.toJson(exportData);
    }

    /**
     * 密钥脱敏：保留前 4 后 4 用于人工对照，中段以 **** 代替；短密钥整体以 **** 覆盖。
     * 明文密钥永不进入公共导出文件。
     */
    private static String maskSecret(String key) {
        if (key == null || key.isEmpty()) return "";
        int n = key.length();
        if (n <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(n - 4);
    }

    public int importFromJson(String json) {
        try {
            Type type = new TypeToken<List<Map<String, Object>>>() {}.getType();
            List<Map<String, Object>> importData = gson.fromJson(json, type);
            
            if (importData == null || importData.isEmpty()) {
                return 0;
            }

            List<APIConfig> existingConfigs = getAllAPIConfigs();
            Map<String, APIConfig> existingMap = new HashMap<>();
            for (APIConfig config : existingConfigs) {
                existingMap.put(config.getName() + "_" + config.getServiceType(), config);
            }

            int imported = 0;
            for (Map<String, Object> data : importData) {
                APIConfig config = new APIConfig();
                
                if (data.containsKey("name")) {
                    config.setName(String.valueOf(data.get("name")));
                }
                if (data.containsKey("serviceType")) {
                    config.setServiceType(String.valueOf(data.get("serviceType")));
                }
                if (data.containsKey("apiKey")) {
                    // 导出文件默认脱敏（见 exportToJson / apiKeyMasked）。导入时若密钥是被脱敏的，
                    // 绝不能把 "sk-a****mnop" 这类假值写回、覆盖已有真密钥——保留原密钥不动，
                    // 用户需重新填入真实密钥才能激活该配置。
                    boolean masked = Boolean.parseBoolean(String.valueOf(data.get("apiKeyMasked")));
                    if (!masked) {
                        config.setApiKey(String.valueOf(data.get("apiKey")));
                    }
                }
                if (data.containsKey("apiHost")) {
                    config.setApiHost(String.valueOf(data.get("apiHost")));
                }
                if (data.containsKey("modelName")) {
                    config.setModelName(String.valueOf(data.get("modelName")));
                }
                if (data.containsKey("description")) {
                    config.setDescription(String.valueOf(data.get("description")));
                }
                if (data.containsKey("category")) {
                    config.setCategory(String.valueOf(data.get("category")));
                }
                if (data.containsKey("isActive")) {
                    config.setActive(Boolean.parseBoolean(String.valueOf(data.get("isActive"))));
                }
                if (data.containsKey("timeout")) {
                    try {
                        config.setTimeout(Integer.parseInt(String.valueOf(data.get("timeout"))));
                    } catch (Exception ignored) {}
                }

                String key = config.getName() + "_" + config.getServiceType();
                if (existingMap.containsKey(key)) {
                    APIConfig existing = existingMap.get(key);
                    // 仅当导入的密钥非脱敏占位（config.apiKey 未被填充）时才覆盖，
                    // 否则保留既有真密钥，避免被 masked 导出回填成空/null。
                    if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
                        existing.setApiKey(config.getApiKey());
                    }
                    existing.setApiHost(config.getApiHost());
                    existing.setModelName(config.getModelName());
                    existing.setDescription(config.getDescription());
                    existing.setActive(config.isActive());
                    existing.setTimeout(config.getTimeout());
                } else {
                    existingConfigs.add(config);
                    imported++;
                }
            }

            saveAPIConfigs(existingConfigs);
            return imported;
        } catch (Exception e) {
            Log.e(TAG, "Error importing from JSON", e);
            return 0;
        }
    }

    public List<APIConfig> scanArchiveDirectory(String directoryPath) {
        List<APIConfig> foundConfigs = new ArrayList<>();
        try {
            File dir = new File(directoryPath);
            if (!dir.exists() || !dir.isDirectory()) {
                return foundConfigs;
            }

            File[] files = dir.listFiles();
            if (files == null) {
                return foundConfigs;
            }

            for (File file : files) {
                if (file.isFile()) {
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".json") || name.endsWith(".api") || name.endsWith(".txt")) {
                        List<APIConfig> configs = parseApiFile(file);
                        foundConfigs.addAll(configs);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error scanning archive directory", e);
        }
        return foundConfigs;
    }

    private List<APIConfig> parseApiFile(File file) {
        List<APIConfig> configs = new ArrayList<>();
        try {
            StringBuilder content = new StringBuilder();
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();

            String jsonStr = content.toString().trim();
            
            if (jsonStr.startsWith("[") || jsonStr.startsWith("{")) {
                try {
                    if (jsonStr.startsWith("[")) {
                        Type type = new TypeToken<List<Map<String, Object>>>() {}.getType();
                        List<Map<String, Object>> list = gson.fromJson(jsonStr, type);
                        if (list != null) {
                            for (Map<String, Object> item : list) {
                                APIConfig config = mapToApiConfig(item);
                                if (config != null) {
                                    configs.add(config);
                                }
                            }
                        }
                    } else {
                        Type type = new TypeToken<Map<String, Object>>() {}.getType();
                        Map<String, Object> map = gson.fromJson(jsonStr, type);
                        if (map != null) {
                            APIConfig config = mapToApiConfig(map);
                            if (config != null) {
                                configs.add(config);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "JSON parse failed, trying key-value format", e);
                }
            }

            if (configs.isEmpty()) {
                APIConfig config = parseKeyValueFormat(jsonStr, file.getName());
                if (config != null) {
                    configs.add(config);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing API file: " + file.getName(), e);
        }
        return configs;
    }

    private APIConfig mapToApiConfig(Map<String, Object> map) {
        if (map == null) return null;

        String apiKey = null;
        String name = null;
        String serviceType = APIConfig.ServiceType.CUSTOM;

        for (String key : map.keySet()) {
            String lowerKey = key.toLowerCase();
            Object value = map.get(key);
            if (value == null) continue;

            if (lowerKey.contains("apikey") || lowerKey.contains("api_key") || 
                lowerKey.contains("key") && lowerKey.contains("api")) {
                apiKey = String.valueOf(value);
            } else if (lowerKey.equals("name") || lowerKey.equals("title")) {
                name = String.valueOf(value);
            } else if (lowerKey.equals("servicetype") || lowerKey.equals("service_type") || 
                       lowerKey.equals("type")) {
                serviceType = String.valueOf(value);
            }
        }

        if (apiKey == null) {
            return null;
        }

        APIConfig config = new APIConfig();
        config.setApiKey(apiKey);
        config.setName(name != null ? name : "导入的API配置");
        config.setServiceType(serviceType);

        if (map.containsKey("apiHost") || map.containsKey("api_host") || map.containsKey("host")) {
            Object host = map.getOrDefault("apiHost", map.getOrDefault("api_host", map.get("host")));
            if (host != null) config.setApiHost(String.valueOf(host));
        }
        if (map.containsKey("modelName") || map.containsKey("model")) {
            Object model = map.getOrDefault("modelName", map.get("model"));
            if (model != null) config.setModelName(String.valueOf(model));
        }
        if (map.containsKey("description")) {
            config.setDescription(String.valueOf(map.get("description")));
        }
        if (map.containsKey("category")) {
            config.setCategory(String.valueOf(map.get("category")));
        }

        return config;
    }

    private APIConfig parseKeyValueFormat(String content, String fileName) {
        String apiKey = null;
        String apiHost = null;
        String modelName = null;

        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }

            int eqIndex = line.indexOf('=');
            if (eqIndex > 0) {
                String key = line.substring(0, eqIndex).trim().toLowerCase();
                String value = line.substring(eqIndex + 1).trim();

                if (key.contains("apikey") || key.contains("api_key") || 
                    (key.contains("key") && key.contains("api"))) {
                    apiKey = value;
                } else if (key.contains("host") || key.contains("url") || key.contains("endpoint")) {
                    apiHost = value;
                } else if (key.contains("model")) {
                    modelName = value;
                }
            }
        }

        if (apiKey == null) {
            return null;
        }

        APIConfig config = new APIConfig();
        config.setApiKey(apiKey);
        config.setName(fileName.replaceAll("\\.[^.]+$", ""));
        config.setServiceType(detectServiceType(apiKey, apiHost));
        if (apiHost != null) config.setApiHost(apiHost);
        if (modelName != null) config.setModelName(modelName);

        return config;
    }

    private String detectServiceType(String apiKey, String apiHost) {
        if (apiKey != null) {
            if (apiKey.startsWith("sk-")) {
                return APIConfig.ServiceType.OPENAI;
            }
            if (apiKey.startsWith("sk-ant-")) {
                return APIConfig.ServiceType.ANTHROPIC;
            }
        }
        if (apiHost != null) {
            String lowerHost = apiHost.toLowerCase();
            if (lowerHost.contains("openai")) return APIConfig.ServiceType.OPENAI;
            if (lowerHost.contains("anthropic")) return APIConfig.ServiceType.ANTHROPIC;
            if (lowerHost.contains("google")) return APIConfig.ServiceType.GOOGLE;
            if (lowerHost.contains("qweather") || lowerHost.contains("hefeng")) return APIConfig.ServiceType.HEFENG_WEATHER;
            if (lowerHost.contains("bing")) return APIConfig.ServiceType.BING_SEARCH;
        }
        return APIConfig.ServiceType.CUSTOM;
    }

    public String generateAIImportPrompt(String content) {
        return "请分析以下文本内容，提取其中可能的API配置信息。\n\n" +
               "文本内容：\n" + content + "\n\n" +
               "请按照以下JSON格式输出（只输出JSON，不要其他内容）：\n" +
               "{\n" +
               "  \"apiConfigs\": [\n" +
               "    {\n" +
               "      \"name\": \"配置名称\",\n" +
               "      \"serviceType\": \"openai|anthropic|google|custom\",\n" +
               "      \"apiKey\": \"API密钥\",\n" +
               "      \"apiHost\": \"API主机地址(可选)\",\n" +
               "      \"modelName\": \"模型名称(可选)\",\n" +
               "      \"description\": \"描述(可选)\"\n" +
               "    }\n" +
               "  ]\n" +
               "}\n\n" +
               "如果没有找到API配置，请返回空的apiConfigs数组。";
    }

    public List<APIConfig> parseAIImportResult(String aiResponse) {
        List<APIConfig> configs = new ArrayList<>();
        try {
            int jsonStart = aiResponse.indexOf("{");
            int jsonEnd = aiResponse.lastIndexOf("}");
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String jsonStr = aiResponse.substring(jsonStart, jsonEnd + 1);
                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> result = gson.fromJson(jsonStr, type);
                
                if (result != null && result.containsKey("apiConfigs")) {
                    Object configsObj = result.get("apiConfigs");
                    if (configsObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> configList = (List<Map<String, Object>>) configsObj;
                        for (Map<String, Object> item : configList) {
                            APIConfig config = mapToApiConfig(item);
                            if (config != null) {
                                configs.add(config);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing AI import result", e);
        }
        return configs;
    }

    public static class TestResult {
        public String configId;
        public String configName;
        public boolean success;
        public String message;
        public int responseCode;
        public long latency;
        public long startTime;
    }

    // QWeather JWT credential keys
    private static final String KEY_QWEATHER_PRIVATE_KEY = "qweather_jwt_private_key";
    private static final String KEY_QWEATHER_PROJECT_ID = "qweather_jwt_project_id";
    private static final String KEY_QWEATHER_KID = "qweather_jwt_kid";
    private static final String KEY_QWEATHER_API_HOST = "qweather_jwt_api_host";

    /**
     * Save QWeather JWT credentials.
     */
    public void saveQWeatherJwtCredentials(String privateKey, String projectId, String kid, String apiHost) {
        try {
            // 四项合并进同一个 Editor 一次提交：避免"改了一半"的凭据组合。
            SharedPreferences.Editor editor = preferences.edit();
            int a = stageSecret(editor, KEY_QWEATHER_PRIVATE_KEY, privateKey);
            int b = stageSecret(editor, KEY_QWEATHER_PROJECT_ID, projectId);
            int c = stageSecret(editor, KEY_QWEATHER_KID, kid);
            int d = stageSecret(editor, KEY_QWEATHER_API_HOST, apiHost);
            if (a == SECRET_FAILED || b == SECRET_FAILED || c == SECRET_FAILED || d == SECRET_FAILED) {
                // 有一项加密失败就整笔放弃：既不全存也不全删，磁盘保持原样
                Log.e(TAG, "Keystore 不可用，QWeather 凭据整笔未保存（既有值保持原样）");
                return;
            }
            if (a == SECRET_CHANGED || b == SECRET_CHANGED || c == SECRET_CHANGED || d == SECRET_CHANGED) {
                editor.apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving QWeather JWT credentials", e);
        }
    }

    /** 一项密钥在 Editor 里的处置结果 */
    private static final int SECRET_UNCHANGED = 0;
    private static final int SECRET_CHANGED = 1;
    private static final int SECRET_FAILED = 2;

    /**
     * 把一项密钥按语义暂存进 Editor：{@code null} 不动、空串清除、有值加密写入。
     *
     * <p>加密失败（Keystore 不可用）返回 {@link #SECRET_FAILED} 且<b>不</b>往 Editor 里放任何
     * 东西 —— 调用方据此放弃整笔提交。这一步很关键：`putString(key, null)` 的实际语义是
     * 删除条目，若在此处图省事直接写入，"保存失败"就会顺手抹掉用户已有的密钥。</p>
     */
    private int stageSecret(SharedPreferences.Editor editor, String key, String value) {
        if (value == null) return SECRET_UNCHANGED;
        if (value.isEmpty()) {
            editor.remove(key);
            return SECRET_CHANGED;
        }
        String enc = encrypt(value);
        if (enc == null) return SECRET_FAILED;
        editor.putString(key, enc);
        return SECRET_CHANGED;
    }

    /**
     * Get QWeather JWT private key (PEM format).
     */
    public String getQWeatherPrivateKey() {
        try {
            return readSecret(KEY_QWEATHER_PRIVATE_KEY);
        } catch (Exception e) {
            Log.e(TAG, "Error getting QWeather private key", e);
        }
        return null;
    }

    /**
     * Get QWeather project ID.
     */
    public String getQWeatherProjectId() {
        try {
            return readSecret(KEY_QWEATHER_PROJECT_ID);
        } catch (Exception e) {
            Log.e(TAG, "Error getting QWeather project ID", e);
        }
        return null;
    }

    /**
     * Get QWeather key ID (kid).
     */
    public String getQWeatherKid() {
        try {
            return readSecret(KEY_QWEATHER_KID);
        } catch (Exception e) {
            Log.e(TAG, "Error getting QWeather kid", e);
        }
        return null;
    }

    /**
     * Get QWeather API host.
     */
    public String getQWeatherApiHost() {
        try {
            return readSecret(KEY_QWEATHER_API_HOST);
        } catch (Exception e) {
            Log.e(TAG, "Error getting QWeather API host", e);
        }
        return null;
    }

    /**
     * Check if QWeather JWT credentials are configured.
     */
    public boolean isQWeatherJwtConfigured() {
        return getQWeatherPrivateKey() != null && getQWeatherProjectId() != null && getQWeatherKid() != null;
    }

    /**
     * Clear QWeather JWT credentials.
     */
    public void clearQWeatherJwtCredentials() {
        preferences.edit()
            .remove(KEY_QWEATHER_PRIVATE_KEY)
            .remove(KEY_QWEATHER_PROJECT_ID)
            .remove(KEY_QWEATHER_KID)
            .remove(KEY_QWEATHER_API_HOST)
            .apply();
    }

    public static class Service {
        public static final String HEFENG_WEATHER = "hefeng_weather";
        public static final String OPENAI = "openai";
        public static final String GOOGLE_MAPS = "google_maps";
        public static final String BING_SEARCH = "bing_search";
        public static final String METASO_SEARCH = "metaso_search";
        public static final String CUSTOM = "custom";
    }
}
