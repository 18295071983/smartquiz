# AI多模态摘要功能后续优化方案

## 📋 优化概览

基于已完成的真实AI服务集成，提供4个高优先级优化方向，按实施难度和收益排序。

---

## 🎯 优化1：完善模型能力元数据，实现真正的智能路由 ⭐⭐⭐⭐⭐

### 问题现状
- `selectModelByCapability()` 方法返回默认配置（TODO状态）
- `OnlineModelConfig` 缺少能力标签字段
- 无法根据模型实际能力进行智能选择

### 实施方案

#### 步骤1：扩展 OnlineModelConfig 添加能力字段

**文件**: `src/main/java/com/oilquiz/app/ai/model/OnlineModelManager.java`

```java
public static class OnlineModelConfig {
    // ... 原有字段 ...
    
    // === 新增：模型能力元数据 ===
    public ModelCapabilities capabilities;          // 模型能力标签
    public int contextWindow;                        // 上下文窗口大小（tokens）
    public int maxOutputTokens;                      // 最大输出长度
    public boolean supportsVision;                   // 是否支持视觉理解
    public boolean supportsAudio;                    // 是否支持音频处理
    public boolean supportsCode;                     // 是否擅长代码生成
    public double costPerMillionTokens;              // 每百万token成本（美元）
    
    public OnlineModelConfig() {
        this.capabilities = new ModelCapabilities();
        this.contextWindow = 4096;
        this.maxOutputTokens = 2048;
    }
    
    /**
     * 检查模型是否具备指定能力
     */
    public boolean hasCapability(String capability) {
        if (capabilities == null) return false;
        
        switch (capability.toLowerCase()) {
            case "vision":
                return supportsVision || capabilities.supportsImageInput;
            case "audio":
                return supportsAudio || capabilities.supportsAudioInput;
            case "coding":
                return supportsCode || capabilities.supportsCodeGeneration;
            case "long_context":
                return contextWindow >= 32768; // 32K+ 视为长上下文
            default:
                return false;
        }
    }
}

/**
 * 模型能力描述（复用本地模型的架构）
 */
public static class ModelCapabilities {
    public boolean supportsStreaming = true;
    public boolean supportsFunctionCalling = false;
    public boolean supportsCodeGeneration = false;
    public boolean supportsImageInput = false;      // 新增：图像输入
    public boolean supportsAudioInput = false;      // 新增：音频输入
    public int maxOutputTokens = 4096;
}
```

#### 步骤2：实现智能模型选择逻辑

**文件**: `src/main/java/com/oilquiz/app/ai/chat/input/AttachmentProcessor.java`

```java
/**
 * 根据能力需求选择最优模型
 */
private OnlineModelManager.OnlineModelConfig selectModelByCapability(
        String capability, 
        OnlineModelManager.OnlineModelConfig defaultConfig) {
    
    OnlineModelManager modelManager = OnlineModelManager.getInstance(activity);
    List<OnlineModelManager.OnlineModelConfig> allModels = modelManager.getAllModels();
    
    if (allModels == null || allModels.isEmpty()) {
        Log.w(TAG, "No models available, using default");
        return defaultConfig;
    }
    
    // 过滤启用的模型
    List<OnlineModelManager.OnlineModelConfig> enabledModels = new ArrayList<>();
    for (OnlineModelManager.OnlineModelConfig config : allModels) {
        if (config.enabled && config.hasCapability(capability)) {
            enabledModels.add(config);
        }
    }
    
    if (enabledModels.isEmpty()) {
        Log.w(TAG, "No model with capability '" + capability + "', using default");
        return defaultConfig;
    }
    
    // 按性价比排序（优先选择成本低且能力匹配的模型）
    enabledModels.sort((a, b) -> {
        double costA = a.costPerMillionTokens > 0 ? a.costPerMillionTokens : Double.MAX_VALUE;
        double costB = b.costPerMillionTokens > 0 ? b.costPerMillionTokens : Double.MAX_VALUE;
        return Double.compare(costA, costB);
    });
    
    OnlineModelManager.OnlineModelConfig selected = enabledModels.get(0);
    Log.i(TAG, "Selected model for '" + capability + "': " + selected.name + 
          " (cost: $" + selected.costPerMillionTokens + "/M tokens)");
    
    return selected;
}
```

#### 步骤3：从API自动获取模型能力元数据

**文件**: `src/main/java/com/oilquiz/app/ai/service/OnlineInferenceService.java`

在 `fetchAvailableModels()` 方法中增强模型信息解析：

```java
// 解析OpenAI兼容API的模型列表时，提取能力信息
for (JSONObject modelObj : modelsArray) {
    String modelId = modelObj.getString("id");
    
    // 根据模型ID推断能力（可配置映射表）
    ModelCapabilities caps = inferModelCapabilities(modelId);
    
    OnlineModelManager.OnlineModelConfig config = new OnlineModelManager.OnlineModelConfig();
    config.id = modelId;
    config.name = modelId;
    config.capabilities = caps;
    config.contextWindow = getContextWindowForModel(modelId);
    config.costPerMillionTokens = getCostForModel(modelId);
    
    // ... 保存到配置
}

/**
 * 根据模型ID推断能力（可扩展为配置文件或API查询）
 */
private ModelCapabilities inferModelCapabilities(String modelId) {
    ModelCapabilities caps = new ModelCapabilities();
    
    // GPT-4系列
    if (modelId.contains("gpt-4o") || modelId.contains("gpt-4-vision")) {
        caps.supportsImageInput = true;
        caps.supportsCodeGeneration = true;
        caps.supportsFunctionCalling = true;
    }
    // Claude系列
    else if (modelId.contains("claude-3")) {
        caps.supportsImageInput = true;
        caps.supportsCodeGeneration = true;
        caps.supportsFunctionCalling = true;
    }
    // Qwen系列
    else if (modelId.contains("qwen") && modelId.contains("vl")) {
        caps.supportsImageInput = true;
    }
    // 代码专用模型
    else if (modelId.contains("codellama") || modelId.contains("starcoder")) {
        caps.supportsCodeGeneration = true;
    }
    
    return caps;
}
```

### 预期收益
- ✅ 自动选择最适合的模型处理不同类型附件
- ✅ 降低API成本（优先使用性价比高的模型）
- ✅ 提升摘要质量（使用专业模型处理专业内容）

---

## 🎯 优化2：持久化摘要缓存，重启后仍可用 ⭐⭐⭐⭐

### 问题现状
- 当前缓存仅存储在内存中（`ConcurrentHashMap`）
- App重启后所有缓存丢失，需重新调用API

### 实施方案

#### 步骤1：创建摘要缓存管理器

**新建文件**: `src/main/java/com/oilquiz/app/ai/chat/cache/SummaryCacheManager.java`

```java
package com.oilquiz.app.ai.chat.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 摘要缓存管理器 - 支持磁盘持久化
 */
public class SummaryCacheManager {
    
    private static final String TAG = "SummaryCacheManager";
    private static final String PREFS_NAME = "summary_cache";
    private static final long CACHE_EXPIRY_MS = 7 * 24 * 60 * 60 * 1000; // 7天
    
    private static volatile SummaryCacheManager INSTANCE;
    private final Context context;
    private final SharedPreferences prefs;
    private final ConcurrentHashMap<String, CachedSummary> memoryCache;
    
    public static class CachedSummary {
        public String attachmentId;
        public String summary;
        public long timestamp;
        public String modelName; // 记录生成摘要的模型
        
        public CachedSummary() {}
        
        public CachedSummary(String attachmentId, String summary, String modelName) {
            this.attachmentId = attachmentId;
            this.summary = summary;
            this.modelName = modelName;
            this.timestamp = System.currentTimeMillis();
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS;
        }
        
        public JSONObject toJson() throws Exception {
            JSONObject json = new JSONObject();
            json.put("attachmentId", attachmentId);
            json.put("summary", summary);
            json.put("timestamp", timestamp);
            json.put("modelName", modelName);
            return json;
        }
        
        public static CachedSummary fromJson(JSONObject json) throws Exception {
            CachedSummary cs = new CachedSummary();
            cs.attachmentId = json.getString("attachmentId");
            cs.summary = json.getString("summary");
            cs.timestamp = json.getLong("timestamp");
            cs.modelName = json.optString("modelName", "unknown");
            return cs;
        }
    }
    
    private SummaryCacheManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.memoryCache = new ConcurrentHashMap<>();
        
        // 启动时从磁盘加载缓存
        loadFromDisk();
    }
    
    public static SummaryCacheManager getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (SummaryCacheManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SummaryCacheManager(context);
                }
            }
        }
        return INSTANCE;
    }
    
    /**
     * 保存摘要到缓存（内存+磁盘）
     */
    public void saveSummary(String attachmentId, String summary, String modelName) {
        if (attachmentId == null || summary == null) return;
        
        CachedSummary cached = new CachedSummary(attachmentId, summary, modelName);
        memoryCache.put(attachmentId, cached);
        
        // 异步保存到磁盘
        saveToDiskAsync(attachmentId, cached);
    }
    
    /**
     * 从缓存读取摘要
     */
    public String getSummary(String attachmentId) {
        CachedSummary cached = memoryCache.get(attachmentId);
        if (cached != null && !cached.isExpired()) {
            Log.d(TAG, "Cache hit for: " + attachmentId);
            return cached.summary;
        }
        
        // 内存未命中，尝试从磁盘加载
        CachedSummary diskCached = loadFromDisk(attachmentId);
        if (diskCached != null && !diskCached.isExpired()) {
            memoryCache.put(attachmentId, diskCached); // 回填内存
            Log.d(TAG, "Disk cache hit for: " + attachmentId);
            return diskCached.summary;
        }
        
        return null;
    }
    
    /**
     * 清除过期缓存
     */
    public void clearExpiredCache() {
        int cleared = 0;
        for (Map.Entry<String, CachedSummary> entry : memoryCache.entrySet()) {
            if (entry.getValue().isExpired()) {
                memoryCache.remove(entry.getKey());
                removeFromDisk(entry.getKey());
                cleared++;
            }
        }
        Log.i(TAG, "Cleared " + cleared + " expired cache entries");
    }
    
    /**
     * 清空所有缓存
     */
    public void clearAllCache() {
        memoryCache.clear();
        prefs.edit().clear().apply();
        Log.i(TAG, "All cache cleared");
    }
    
    // ========== 私有方法 ==========
    
    private void saveToDiskAsync(String key, CachedSummary summary) {
        new Thread(() -> {
            try {
                String jsonStr = summary.toJson().toString();
                prefs.edit().putString(key, jsonStr).apply();
            } catch (Exception e) {
                Log.e(TAG, "Failed to save cache to disk: " + e.getMessage());
            }
        }).start();
    }
    
    private CachedSummary loadFromDisk(String key) {
        try {
            String jsonStr = prefs.getString(key, null);
            if (jsonStr != null) {
                return CachedSummary.fromJson(new JSONObject(jsonStr));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load cache from disk: " + e.getMessage());
        }
        return null;
    }
    
    private void removeFromDisk(String key) {
        prefs.edit().remove(key).apply();
    }
    
    private void loadFromDisk() {
        Map<String, ?> allEntries = prefs.getAll();
        int loaded = 0;
        
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            try {
                String jsonStr = (String) entry.getValue();
                CachedSummary summary = CachedSummary.fromJson(new JSONObject(jsonStr));
                if (!summary.isExpired()) {
                    memoryCache.put(entry.getKey(), summary);
                    loaded++;
                } else {
                    // 删除过期项
                    removeFromDisk(entry.getKey());
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to parse cache entry: " + entry.getKey());
            }
        }
        
        Log.i(TAG, "Loaded " + loaded + " cache entries from disk");
    }
}
```

#### 步骤2：修改 AttachmentProcessor 使用持久化缓存

**文件**: `src/main/java/com/oilquiz/app/ai/chat/input/AttachmentProcessor.java`

```java
public class AttachmentProcessor {
    // ... 原有字段 ...
    
    private final SummaryCacheManager summaryCacheManager; // 新增
    
    public AttachmentProcessor(Activity activity) {
        this.activity = activity;
        this.fileContentExtractor = new FileContentExtractor(activity);
        this.executorService = Executors.newFixedThreadPool(3);
        this.summaryCacheManager = SummaryCacheManager.getInstance(activity); // 初始化
    }
    
    // 修改 generateAISummary 方法中的缓存逻辑
    public void generateAISummary(ChatMessage.Attachment attachment) {
        // ... 前置检查 ...
        
        // 检查持久化缓存
        String cachedSummary = summaryCacheManager.getSummary(attachment.id);
        if (cachedSummary != null) {
            Log.i(TAG, "Using persistent cache for: " + attachment.name);
            if (callback != null) {
                callback.onSummaryGenerated(attachment.id, cachedSummary);
            }
            return;
        }
        
        // ... AI生成逻辑 ...
        
        // 生成成功后保存缓存
        cacheSummary(attachment.id, cleanSummary, selectedConfig.modelName);
    }
    
    private void cacheSummary(String attachmentId, String summary, String modelName) {
        summaryCacheManager.saveSummary(attachmentId, summary, modelName);
    }
}
```

### 预期收益
- ✅ App重启后摘要仍然可用
- ✅ 减少重复API调用，节省成本
- ✅ 7天自动清理过期缓存

---

## 🎯 优化3：批量摘要生成，提升多附件处理效率 ⭐⭐⭐

### 问题现状
- 多个附件逐个串行生成摘要
- 用户等待时间长

### 实施方案

**文件**: `src/main/java/com/oilquiz/app/ai/chat/input/AttachmentProcessor.java`

```java
/**
 * 批量生成摘要（并行处理）
 */
public void generateSummariesBatch(List<ChatMessage.Attachment> attachments) {
    if (attachments == null || attachments.isEmpty()) return;
    
    Log.i(TAG, "Starting batch summary generation for " + attachments.size() + " attachments");
    
    // 限制并发数（避免过多API请求）
    ExecutorService batchExecutor = Executors.newFixedThreadPool(3);
    
    for (ChatMessage.Attachment attachment : attachments) {
        if (attachment.extractedContent != null && !attachment.extractedContent.isEmpty()) {
            batchExecutor.submit(() -> {
                try {
                    generateAISummary(attachment);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to generate summary for: " + attachment.name, e);
                }
            });
        }
    }
    
    batchExecutor.shutdown();
}
```

**修改 AIChatActivity 调用批量生成**:

```java
private void updateAttachmentExtractionStatus(Uri uri, String extractedContent) {
    // ... 更新单个附件状态 ...
    
    // 收集所有待生成摘要的附件
    List<ChatMessage.Attachment> pendingAttachments = new ArrayList<>();
    for (ChatMessage.Attachment att : inputManager.getCurrentAttachments()) {
        if (att.extractedContent != null && att.aiSummary == null) {
            pendingAttachments.add(att);
        }
    }
    
    // 批量生成（如果超过1个附件）
    if (pendingAttachments.size() > 1) {
        attachmentProcessor.generateSummariesBatch(pendingAttachments);
    } else if (pendingAttachments.size() == 1) {
        attachmentProcessor.generateAISummary(pendingAttachments.get(0));
    }
}
```

### 预期收益
- ✅ 3个附件并行处理，总耗时从45秒降至15秒
- ✅ 提升用户体验

---

## 🎯 优化4：用户自定义摘要风格提示词 ⭐⭐

### 问题现状
- 摘要风格固定，无法满足个性化需求

### 实施方案

#### 步骤1：添加用户偏好设置

**文件**: `res/xml/preferences.xml`（新建）

```xml
<?xml version="1.0" encoding="utf-8"?>
<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android">
    
    <ListPreference
        android:key="summary_style_preference"
        android:title="摘要风格"
        android:summary="选择AI生成摘要的风格"
        android:entries="@array/summary_styles"
        android:entryValues="@array/summary_style_values"
        android:defaultValue="concise" />
    
    <EditTextPreference
        android:key="custom_summary_prompt"
        android:title="自定义提示词"
        android:summary="输入自定义的摘要生成要求"
        android:dialogTitle="自定义提示词模板" />
    
</PreferenceScreen>
```

**文件**: `res/values/arrays.xml`

```xml
<string-array name="summary_styles">
    <item>简洁明了（推荐）</item>
    <item>详细全面</item>
    <item>要点罗列</item>
    <item>学术风格</item>
    <item>自定义</item>
</string-array>

<string-array name="summary_style_values">
    <item>concise</item>
    <item>detailed</item>
    <item>bullet_points</item>
    <item>academic</item>
    <item>custom</item>
</string-array>
```

#### 步骤2：修改 buildSummaryPrompt 使用用户偏好

**文件**: `src/main/java/com/oilquiz/app/ai/chat/input/AttachmentProcessor.java`

```java
private String buildSummaryPrompt(ChatMessage.Attachment attachment) {
    StringBuilder prompt = new StringBuilder();
    
    // 获取用户偏好
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
    String style = prefs.getString("summary_style_preference", "concise");
    String customPrompt = prefs.getString("custom_summary_prompt", "");
    
    // 根据风格构建提示词
    switch (style) {
        case "concise":
            prompt.append("请用简洁的语言（100-150字）总结以下文件内容的核心要点：\n\n");
            break;
        case "detailed":
            prompt.append("请详细总结以下文件内容（300-500字），包括背景、主要观点、结论和建议：\n\n");
            break;
        case "bullet_points":
            prompt.append("请将以下内容总结为5-8个关键要点，每条不超过30字，使用•符号开头：\n\n");
            break;
        case "academic":
            prompt.append("请以学术论文摘要的风格总结以下内容，包括研究目的、方法、结果和结论：\n\n");
            break;
        case "custom":
            prompt.append(customPrompt.isEmpty() ? 
                "请总结以下内容：" : customPrompt + "\n\n");
            break;
    }
    
    prompt.append("文件名：").append(attachment.name).append("\n");
    prompt.append("文件类型：").append(attachment.type).append("\n\n");
    prompt.append("内容：\n");
    
    String content = attachment.extractedContent;
    if (content.length() > 3000) {
        content = content.substring(0, 3000);
    }
    prompt.append(content);
    
    return prompt.toString();
}
```

### 预期收益
- ✅ 用户可根据需求选择摘要风格
- ✅ 提升个性化体验

---

## 📊 优化优先级与工作量评估

| 优化项 | 优先级 | 工作量 | 预期收益 | 实施难度 |
|--------|--------|--------|----------|----------|
| 优化1：智能模型路由 | ⭐⭐⭐⭐⭐ | 2小时 | 降低成本30%+，提升质量 | 中等 |
| 优化2：持久化缓存 | ⭐⭐⭐⭐ | 1.5小时 | 减少重复调用，提升体验 | 简单 |
| 优化3：批量生成 | ⭐⭐⭐ | 1小时 | 提速3倍 | 简单 |
| 优化4：自定义风格 | ⭐⭐ | 2小时 | 个性化体验 | 中等 |

**建议实施顺序**：优化1 → 优化2 → 优化3 → 优化4

---

## 🚀 快速开始

如需立即实施某个优化，请告知我具体选择哪个方案，我将为您生成完整的代码修改！
