package com.oilquiz.app.ai.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Base64;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.oilquiz.app.ai.model.APIConfig;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class APIKeyManager {

    private static final String TAG = "APIKeyManager";
    private static final String PREF_NAME = "api_keys";
    private static final String KEY_ENCRYPTION_KEY = "encryption_key";
    private static final String IV_KEY = "iv_key";
    private static final String KEY_API_LIST = "api_config_list";
    private static final String KEY_ACTIVE_API_ID = "active_api_id";
    private static final String KEY_ARCHIVE_PATH = "api_archive_path";
    private static final String HOST_SUFFIX = "_host";

    private static APIKeyManager instance;
    private final Context context;
    private final SharedPreferences preferences;
    private SecretKey secretKey;
    private byte[] iv;
    private final Gson gson;
    private boolean initializationFailed = false;

    private APIKeyManager(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            secretKey = getOrCreateSecretKey();
            iv = getOrCreateIV();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize encryption keys, falling back to unencrypted storage", e);
            initializationFailed = true;
            secretKey = null;
            iv = null;
        }
    }

    public static synchronized APIKeyManager getInstance(Context context) {
        if (instance == null) {
            instance = new APIKeyManager(context.getApplicationContext());
        }
        return instance;
    }

    private SecretKey getOrCreateSecretKey() {
        try {
            String keyStr = preferences.getString(KEY_ENCRYPTION_KEY, null);
            if (keyStr != null) {
                byte[] keyBytes = Base64.decode(keyStr, Base64.DEFAULT);
                return new SecretKeySpec(keyBytes, "AES");
            } else {
                KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
                keyGenerator.init(256);
                SecretKey key = keyGenerator.generateKey();
                String encodedKey = Base64.encodeToString(key.getEncoded(), Base64.DEFAULT);
                preferences.edit().putString(KEY_ENCRYPTION_KEY, encodedKey).apply();
                return key;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting or creating secret key", e);
            return null;
        }
    }

    private byte[] getOrCreateIV() {
        try {
            String ivStr = preferences.getString(IV_KEY, null);
            if (ivStr != null) {
                return Base64.decode(ivStr, Base64.DEFAULT);
            } else {
                byte[] newIv = new byte[16];
                new SecureRandom().nextBytes(newIv);
                String encodedIv = Base64.encodeToString(newIv, Base64.DEFAULT);
                preferences.edit().putString(IV_KEY, encodedIv).apply();
                return newIv;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting or creating IV", e);
            return null;
        }
    }

    public void saveAPIKey(String service, String apiKey) {
        try {
            String encryptedKey = encrypt(apiKey);
            preferences.edit().putString(service, encryptedKey).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving API key for service: " + service, e);
        }
    }

    public String getAPIKey(String service) {
        try {
            String encryptedKey = preferences.getString(service, null);
            if (encryptedKey != null) {
                return decrypt(encryptedKey);
            }
            return null;
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
            String encryptedHost = encrypt(apiHost);
            preferences.edit().putString(service + HOST_SUFFIX, encryptedHost).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving API host for service: " + service, e);
        }
    }

    public String getAPIHost(String service) {
        try {
            String encryptedHost = preferences.getString(service + HOST_SUFFIX, null);
            if (encryptedHost != null) {
                return decrypt(encryptedHost);
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error getting API host for service: " + service, e);
            return null;
        }
    }

    private String encrypt(String plaintext) throws Exception {
        if (plaintext == null) {
            plaintext = "";
        }
        if (initializationFailed || secretKey == null || iv == null) {
            return "UNENC:" + Base64.encodeToString(plaintext.getBytes(), Base64.DEFAULT);
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes());
        return Base64.encodeToString(encrypted, Base64.DEFAULT);
    }

    private String decrypt(String ciphertext) throws Exception {
        if (ciphertext == null) {
            return "";
        }
        if (initializationFailed || secretKey == null || iv == null) {
            if (ciphertext.startsWith("UNENC:")) {
                return new String(Base64.decode(ciphertext.substring(6), Base64.DEFAULT));
            }
            throw new IllegalStateException("Cannot decrypt data: encryption not initialized");
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
        byte[] encrypted = Base64.decode(ciphertext, Base64.DEFAULT);
        byte[] decrypted = cipher.doFinal(encrypted);
        return new String(decrypted);
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
            preferences.edit().putString(KEY_API_LIST, encrypted).apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving API configs", e);
        }
    }

    public List<APIConfig> getAllAPIConfigs() {
        try {
            String encrypted = preferences.getString(KEY_API_LIST, null);
            if (encrypted != null) {
                if (initializationFailed && !encrypted.startsWith("UNENC:")) {
                    Log.w(TAG, "Cannot decrypt existing data: encryption keys not available, returning empty list");
                    return new ArrayList<>();
                }
                String json = decrypt(encrypted);
                Type type = new TypeToken<List<APIConfig>>() {}.getType();
                List<APIConfig> configs = gson.fromJson(json, type);
                return configs != null ? configs : new ArrayList<>();
            }
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
                    SSLSocketFactoryUtil.disableSSLCertificateValidation((HttpsURLConnection) connection);
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
            data.put("apiKey", config.getApiKey());
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
                    config.setApiKey(String.valueOf(data.get("apiKey")));
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
                    existing.setApiKey(config.getApiKey());
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
            if (privateKey != null) {
                preferences.edit().putString(KEY_QWEATHER_PRIVATE_KEY, encrypt(privateKey)).apply();
            }
            if (projectId != null) {
                preferences.edit().putString(KEY_QWEATHER_PROJECT_ID, encrypt(projectId)).apply();
            }
            if (kid != null) {
                preferences.edit().putString(KEY_QWEATHER_KID, encrypt(kid)).apply();
            }
            if (apiHost != null) {
                preferences.edit().putString(KEY_QWEATHER_API_HOST, encrypt(apiHost)).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error saving QWeather JWT credentials", e);
        }
    }

    /**
     * Get QWeather JWT private key (PEM format).
     */
    public String getQWeatherPrivateKey() {
        try {
            String encrypted = preferences.getString(KEY_QWEATHER_PRIVATE_KEY, null);
            if (encrypted != null) {
                return decrypt(encrypted);
            }
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
            String encrypted = preferences.getString(KEY_QWEATHER_PROJECT_ID, null);
            if (encrypted != null) {
                return decrypt(encrypted);
            }
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
            String encrypted = preferences.getString(KEY_QWEATHER_KID, null);
            if (encrypted != null) {
                return decrypt(encrypted);
            }
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
            String encrypted = preferences.getString(KEY_QWEATHER_API_HOST, null);
            if (encrypted != null) {
                return decrypt(encrypted);
            }
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
