# TFLite 嵌入模型资源配置

> **🇨🇳 国内用户**: 如果下载遇到问题，请查看 [国内专用下载指南](./DOWNLOAD_GUIDE_CN.md)

本目录包含文本嵌入（Text Embedding）所需的模型文件。

## 🚀 快速开始（3种方式）

### 方式A：自动脚本下载
```bash
python download_resources.py --model all-MiniLM-L6-v2
```

### 方式B：手动浏览器下载 ⭐推荐
1. 访问: https://hf-mirror.co/sentence-transformers/all-MiniLM-L6-v2/tree/main
2. 下载 `model.tflite` 文件 (~22MB)
3. 重命名为 `all-MiniLM-L6-v2.tflite`
4. 放到本目录

### 方式C：使用云端API（无需本地模型）
```java
config.backendType = BackendType.OPENAI;
config.apiKey = "your-api-key";
```

详见: [DOWNLOAD_GUIDE_CN.md](./DOWNLOAD_GUIDE_CN.md)

## 📦 推荐模型

### 1. **all-MiniLM-L6-v2** (推荐用于测试)
- **大小**: ~22MB
- **维度**: 384
- **最大长度**: 256 tokens
- **速度**: 快速
- **适用场景**: 语义相似度、文档检索、快速原型开发

### 2. **all-mpnet-base-v2** (高质量)
- **大小**: ~110MB
- **维度**: 768
- **最大长度**: 384 tokens
- **速度**: 中等
- **适用场景**: 高精度语义搜索、RAG系统

### 3. **paraphrase-multilingual-MiniLM-L12-v2** (多语言)
- **大小**: ~90MB
- **维度**: 384
- **最大长度**: 512 tokens
- **速度**: 中等
- **适用场景**: 中英文混合文本、多语言应用

## 📥 下载方法

### 方法一：使用Python脚本自动下载（推荐）

```bash
# 运行下载脚本
python download_models.py --model all-MiniLM-L6-v2
```

### 方法二：手动下载

从 HuggingFace 下载：
1. 访问: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2
2. 下载 `model.tflite` 文件
3. 下载 `vocab.txt` 文件
4. 放置到本目录

## 🔧 转换为TFLite格式

如果原始模型不是TFLite格式，需要转换：

```python
# 使用 TensorFlow Lite Converter
import tensorflow as tf

# 加载SavedModel模型
converter = tf.lite.TFLiteConverter.from_saved_model('path/to/saved_model')

# 设置优化选项
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.target_spec.supported_ops = [
    tf.lite.OpsSet.TFLITE_BUILTINS,
    tf.lite.OpsSet.SELECT_TF_OPS
]

# 转换模型
tflite_model = converter.convert()

# 保存
with open('all-MiniLM-L6-v2.tflite', 'wb') as f:
    f.write(tflite_model)
```

## 📋 文件清单

必需文件：
- `all-MiniLM-L6-v2.tflite` - TFLite模型文件
- `all-MiniLM-L6-v2_vocab.txt` - 词汇表文件

可选文件：
- `config.json` - 模型配置信息

## ⚠️ 注意事项

1. **模型大小**: 确保APK有足够空间，建议在Application中检查可用存储
2. **内存占用**: 模型加载需要额外内存（约模型大小的2-3倍）
3. **GPU加速**: 确保设备支持GPU委托（API 21+）
4. **首次加载**: 首次推理会较慢（模型初始化），后续会加速

## 🚀 使用示例

在代码中使用：

```java
EmbeddingConfig config = new EmbeddingConfig();
config.backendType = BackendType.LOCAL_TFLITE;
config.modelName = "all-MiniLM-L6-v2";
config.embeddingDimension = 384;

TextEmbeddingService service = new TextEmbeddingService(context, config);
EmbeddingResult result = service.embed("Hello, world!").get();
```

## 🔗 相关链接

- Sentence-Transformers: https://www.sbert.net/
- HuggingFace Models: https://huggingface.co/models?library=sentence-transformers
- TensorFlow Lite: https://www.tensorflow.org/lite

---
**最后更新**: 2026-05-10
