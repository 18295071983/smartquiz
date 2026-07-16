# 🚀 AI资源快速配置指南

## 📋 快速开始（3步搞定）

### 第1️⃣ 步：下载模型和测试图片

**Windows用户（推荐）：**
```bash
# 双击运行
download_resources.bat

# 或在命令行中
cd src\main\assets
download_resources.bat
```

**Linux/Mac用户：**
```bash
cd src/main/assets
chmod +x download_resources.sh
./download_resources.sh
```

**手动运行Python脚本：**
```bash
pip install requests tqdm pillow numpy

# 下载所有资源
python download_resources.py --all

# 或单独下载
python download_resources.py --model all-MiniLM-L6-v2  # 只下载模型
python download_resources.py --images                   # 只生成测试图片
```

### 第2️⃣ 步：验证下载结果

```bash
# 检查文件完整性
python download_resources.py --check
```

预期输出：
```
📦 模型文件:
   ✅ all-MiniLM-L6-v2

🖼️ 测试图片:
   ✅ 图片资源
```

### 第3️⃣ 步：运行测试

在你的Android应用中启动测试：

```java
// 启动GPU测试
Intent intent = new Intent(this, GPUTestActivity.class);
startActivity(intent);
```

---

## 📁 文件结构说明

下载完成后，你的 `assets` 目录应该如下：

```
src/main/assets/
├── models/                          # TFLite嵌入模型
│   ├── README.md                    # 模型使用说明
│   ├── config.json                  # 模型配置信息
│   ├── all-MiniLM-L6-v2.tflite     # ⬇️ 模型文件 (22MB)
│   └── all-MiniLM-L6-v2_vocab.txt  # ⬇️ 词汇表文件
├── images/                          # 测试图片
│   ├── README.md                    # 图片说明文档
│   ├── test_image.jpg               # ⬇️ 图像标签测试图
│   ├── test_objects.jpg             # ⬇️ 目标检测测试图
│   └── test_text.png                # ⬇️ OCR文字识别测试图
├── download_resources.py            # Python下载脚本
└── download_resources.bat           # Windows快捷方式
```

---

## 🔧 可选：自定义配置

### 使用不同的嵌入模型

编辑 `TextEmbeddingService.java` 的配置：

```java
EmbeddingConfig config = new EmbeddingConfig();

// 方案A: 轻量级模型（快速，适合移动端）
config.modelName = "all-MiniLM-L6-v2";
config.embeddingDimension = 384;

// 方案B: 高质量模型（更准确，但更大）
config.modelName = "paraphrase-multilingual-MiniLM-L12-v2";
config.embeddingDimension = 384;
config.maxTokensPerChunk = 512;

TextEmbeddingService service = new TextEmbeddingService(context, config);
```

### 使用云端API（无需本地模型）

如果不想下载本地模型，可以使用API后端：

```java
EmbeddingConfig config = new EmbeddingConfig();
config.backendType = BackendType.OPENAI;        // OpenAI API
config.apiKey = "sk-your-api-key-here";

// 或者使用其他服务
config.backendType = BackendType.COHERE;         // Cohere API
config.backendType = BackendType.HUGGINGFACE;    // HuggingFace API
```

### 替换测试图片

你可以用自己的图片替换自动生成的测试图片：

1. 准备你的图片：
   - `test_image.jpg`: 单物体照片（用于图像标签识别）
   - `test_objects.jpg`: 多物体场景照片（用于目标检测）
   - `test_text.png`: 包含清晰文字的图片（用于OCR）

2. 将图片复制到 `src/main/assets/images/` 目录

3. **建议规格**：
   - 格式: JPEG (`.jpg`) 或 PNG (`.png`)
   - 尺寸: ≥ 640x480 像素
   - 大小: < 5MB（避免APK过大）

---

## ⚡ 性能优化建议

### 1. 模型选择策略

| 场景 | 推荐模型 | 原因 |
|------|---------|------|
| 快速原型开发 | all-MiniLM-L6-v2 | 体积小、速度快 |
| 生产环境-中文为主 | paraphrase-multilingual | 支持多语言 |
| 高精度需求 | all-mpnet-base-v2 | 效果最佳 |
| 低端设备 | all-MiniLM-L6-v2 + 量化 | 内存占用低 |

### 2. GPU加速配置

确保在 `AIServiceInitializer.java` 中启用GPU：

```java
// 自动检测并启用Vulkan GPU加速
GPUCapabilityDetector detector = new GPUCapabilityDetector(context);
CapabilityReport report = detector.detectCapabilities().get();

if (report.vulkanInfo.isVulkanSupported) {
    Log.i("AI", "✅ Vulkan GPU加速已启用");
    // 系统会自动使用GPU进行推理
}
```

### 3. 内存管理

对于大文档处理，建议启用内存管理：

```java
GPUMemoryManager memoryManager = new GPUMemoryManager(context);
memoryManager.startMonitoring();

// 设置内存压力监听
memoryManager.setPressureListener(new MemoryPressureListener() {
    @Override
    public void onPressureChanged(MemoryPressureLevel oldLevel, 
                                  MemoryPressureLevel newLevel,
                                  double usagePercent) {
        if (newLevel == MemoryPressureLevel.CRITICAL) {
            Log.w("Memory", "⚠️ 显存紧张！清理缓存...");
            documentVectorizer.clearCache();
        }
    }
});
```

---

## ❓ 常见问题

### Q1: 下载速度慢或失败？

**解决方案：**
1. 检查网络连接
2. 使用代理（如果在中国大陆）：
   ```bash
   set HTTPS_PROXY=http://127.0.0.1:7890
   python download_resources.py --model all-MiniLM-L6-v2
   ```
3. 手动下载：从HuggingFace网站直接下载模型文件

### Q2: 模型文件损坏？

**症状：** 运行时报错 "Invalid model file"

**解决方法：**
```bash
# 删除损坏的文件
del src\main\assets\models\all-MiniLM-L6-v2.tflite

# 重新下载
python download_resources.py --model all-MiniLM-L6-v2
```

### Q3: APK体积过大？

**原因：** 模型和测试图片会显著增加APK大小

**优化方案：**
1. **使用动态下载**：首次运行时从服务器下载模型
2. **移除测试图片**：发布版本不包含 `images/` 目录
3. **使用量化模型**：将FP32模型转换为INT8格式（减小4倍）
4. **按需加载**：只在需要时加载模型，用完立即释放

示例代码：
```java
public class ModelManager {
    private static TextEmbeddingService embeddingService;
    
    public static void loadModel(Context context) {
        if (embeddingService == null) {
            embeddingService = new TextEmbeddingService(context);
        }
    }
    
    public static void releaseModel() {
        if (embeddingService != null) {
            embeddingService.close();
            embeddingService = null;
            System.gc(); // 建议垃圾回收
        }
    }
}
```

### Q4: OCR识别效果差？

**可能原因：**
- 图片模糊或分辨率过低
- 光线不足或过曝
- 文字太小或字体特殊

**改进建议：**
1. 提高拍摄质量（使用三脚架、良好光线）
2. 预处理图片（增强对比度、去噪）
3. 使用更高分辨率的图片（建议 > 1920x1080）

### Q5: 如何添加新的测试场景？

在 `MLKitVisionTester.java` 中添加新测试：

```java
private CompletableFuture<TestResult> testCustomScenario() {
    return CompletableFuture.supplyAsync(() -> {
        TestResult result = new TestResult("自定义测试");
        
        try {
            Bitmap image = loadTestImage("your_custom_image.jpg");
            
            long startTime = System.currentTimeMillis();
            
            // 你的自定义测试逻辑...
            
            result.success = true;
            result.latencyMs = System.currentTimeMillis() - startTime;
            
        } catch (Exception e) {
            result.success = false;
            result.errorMessage = e.getMessage();
        }
        
        return result;
    });
}
```

---

## 📊 测试报告解读

运行 `GPUTestActivity` 后，你会看到类似这样的输出：

```
========================================
   📊 最终测试结果
========================================
   总体状态: ✅ 通过
   通过率: 100% (5/5)
   总耗时: 2847ms

--- 初始化结果 ---
状态: 成功
耗时: 1250ms
GPU可用: 是

--- 文本生成测试 ---
状态: ✅ 通过
延迟: 156ms
生成tokens: 42

--- 批量推理测试 ---
状态: ✅ 通过
延迟: 892ms
成功率: 100% (10/10)
平均延迟: 85ms

--- 长上下文测试 ---
状态: ✅ 通过
延迟: 342ms
输入tokens: 856

--- 压力测试 ---
状态: ✅ 通过
延迟: 4521ms
成功率: 98% (49/50)
P50=82ms, P95=156ms, P99=234ms
```

**关键指标解释：**

| 指标 | 含义 | 理想范围 |
|------|------|---------|
| P50延迟 | 50%请求的响应时间 | < 100ms |
| P95延迟 | 95%请求的响应时间 | < 200ms |
| P99延迟 | 99%请求的响应时间 | < 500ms |
| 成功率 | 请求成功比例 | > 95% |
| GPU利用率 | GPU使用效率 | 60-85% |

---

## 🔗 相关文档

- [GPU测试工具使用指南](../java/com/oilquiz/app/ai/testing/GPUModelTester.java)
- [ML Kit视觉功能说明](../java/com/oilquiz/app/ai/multimodal/vision/MLKitVisionTester.java)
- [文本嵌入服务文档](../java/com/oilquiz/app/ai/document/embedding/TextEmbeddingService.java)
- [性能监控指南](../java/com/oilquiz/app/ai/gpu/GPUMonitor.java)

---

## 💡 最佳实践清单

- [ ] 下载并验证所有必需的资源文件
- [ ] 在真机上测试（不要只用模拟器）
- [ ] 监控内存使用情况（避免OOM）
- [ ] 根据设备能力调整offload层数
- [ ] 实现优雅的错误处理和降级机制
- [ ] 在发布前移除测试代码和资源
- [ ] 定期更新模型以获得更好的性能
- [ ] 收集用户反馈以持续优化

---

## 📞 获取帮助

如果遇到问题：

1. **查看日志**: 使用 `Logcat` 过滤 "GPU", "AI", "Test" 标签
2. **检查设备**: 运行 `DeviceInfoActivity` 查看硬件信息
3. **参考文档**: 阅读 `models/README.md` 和 `images/README.md`
4. **社区支持**: 提交Issue到项目仓库

---

**最后更新**: 2026-05-10  
**适用版本**: v1.0.0+
