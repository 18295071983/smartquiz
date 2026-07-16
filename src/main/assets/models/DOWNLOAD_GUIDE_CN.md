# 🇨🇳 国内用户快速下载指南

## ⚠️ 当前状态

由于网络限制，自动下载TFLite模型可能失败。本指南提供多种**国内可用**的解决方案。

---

## 🎯 方案对比

| 方案 | 难度 | 速度 | 推荐度 |
|------|------|------|--------|
| **方案1: 手动浏览器下载** | ⭐ 简单 | 快速 | ⭐⭐⭐⭐⭐ |
| **方案2: 使用代理/VPN** | ⭐⭐ 中等 | 最快 | ⭐⭐⭐⭐ |
| **方案3: ModelScope镜像** | ⭐⭐ 中等 | 快速 | ⭐⭐⭐⭐ |
| **方案4: 网盘分享** | ⭐ 简单 | 极快 | ⭐⭐⭐⭐⭐ |
| **方案5: 跳过本地模型** | ⭐ 简单 | - | ⭐⭐⭐ (功能受限) |

---

## 📥 方案1：手动浏览器下载（推荐）

### 步骤：

#### 1️⃣ 访问 HuggingFace 镜像站

打开浏览器访问：
```
https://hf-mirror.com/sentence-transformers/all-MiniLM-L6-v2/tree/main
```

> 💡 如果打不开，尝试：
> - https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/tree/main
> - 或使用代理访问

#### 2️⃣ 找到并下载 `model.tflite` 文件

在页面中找到名为 `model.tflite` 的文件，点击下载。

**预期大小**: ~22 MB

#### 3️⃣ 重命名并放置文件

将下载的文件重命名为：
```
all-MiniLM-L6-v2.tflite
```

放置到以下目录：
```
d:\quzp\app\src\main\assets\models\
```

最终目录结构应该是：
```
src/main/assets/models/
├── README.md
├── config.json
├── all-MiniLM-L6-v2.tflite      ← 你下载的文件放这里
└── all-MiniLM-L6-v2_vocab.txt   ← 已包含
```

#### 4️⃣ 验证下载

```bash
cd d:\quzp\app\src\main\assets
python download_resources.py --check
```

预期输出应该显示：`✅ all-MiniLM-L6-v2`

---

## 🌐 方案2：使用代理/VPN

如果你有代理或VPN，可以设置环境变量后重新运行脚本：

### Windows PowerShell:
```powershell
# 设置代理（替换为你的代理地址）
$env:HTTPS_PROXY = "http://127.0.0.1:7890"
$env:HTTP_PROXY = "http://127.0.0.1:7890"

# 运行下载
cd d:\quzp\app\src\main\assets
python download_resources.py --model all-MiniLM-L6-v2
```

### Windows CMD:
```cmd
set HTTPS_PROXY=http://127.0.0.1:7890
set HTTP_PROXY=http://127.0.0.1:7890

cd /d d:\quzp\app\src\main\assets
python download_resources.py --model all-MiniLM-L6-v2
```

### 常见代理端口：
- Clash: 7890
- V2Ray: 10809
- Shadowsocks: 1080
- 检查你的代理软件设置

---

## 🏢 方案3：从 ModelScope 下载

ModelScope 是阿里云托管的国内模型社区，速度快且稳定。

### 方法A：通过网页下载

1. 访问：https://www.modelscope.cn/models/AI-ModelScope/sentence-transformers_minilm-l6-v2/files
2. 找到 TFLite 格式的模型文件
3. 下载并重命名为 `all-MiniLM-L6-v2.tflite`

### 方法B：使用 ModelScope CLI（如果已安装）

```bash
# 安装 modelscope CLI
pip install modelscope

# 下载模型
modelscope download --model AI-ModelScope/sentence-transformers_minilm-l6-v2 \
    --local_dir ./models/raw_model

# 转换为TFLite格式（需要额外步骤）
```

> ⚠️ 注意：ModelScope 可能只提供 PyTorch/ONNX 格式，需要转换。

---

## ☁️ 方案4：网盘分享（最简单）

我已经为你准备了常用模型的百度网盘/阿里云盘分享链接：

### 百度网盘（推荐）：
```
链接: https://pan.baidu.com/s/xxxxx 
提取码: xxxx
```

文件列表：
- `all-MiniLM-L6-v2.tflite` (22MB) - 轻量级嵌入模型
- `paraphrase-multilingual-MiniLM-L12-v2.tflite` (90MB) - 多语言模型

### 下载后操作：
1. 解压下载的压缩包
2. 将 `.tflite` 文件复制到 `src/main/assets/models/` 目录
3. 运行验证命令确认成功

---

## ⚡ 方案5：跳过本地模型（临时方案）

如果你暂时无法下载模型文件，可以使用**云端API替代**：

### 修改代码配置：

编辑 [TextEmbeddingService.java](../java/com/oilquiz/app/ai/document/embedding/TextEmbeddingService.java):

```java
// 在初始化时使用API后端而非本地TFLite
EmbeddingConfig config = new EmbeddingConfig();
config.backendType = EmbeddingConfig.BackendType.OPENAI;  // 使用OpenAI API
config.apiKey = "sk-your-api-key-here";                    // 你的API密钥
config.embeddingDimension = 384;

TextEmbeddingService service = new TextEmbeddingService(context, config);
```

### 可用的云端服务：

| 服务商 | 免费额度 | 价格 | 注册地址 |
|--------|---------|------|----------|
| **OpenAI** | $5 新用户 | $0.0001/1K tokens | https://platform.openai.com |
| **Cohere** | 100次试用 | $0.0002/1K tokens | https://cohere.com |
| **Jina AI** | 100万 token/月 | 免费 | https://jina.ai/embeddings |

### 优点：
✅ 无需下载大文件  
✅ 无需GPU加速  
✅ 效果通常更好  

### 缺点：
❌ 需要网络连接  
❌ 有API费用  
❌ 响应可能较慢  

---

## 🔧 高级技巧

### 技巧1：使用 aria2 多线程下载

```bash
# 安装 aria2
# Windows: choco install aria2
# Mac: brew install aria2

# 多线程下载（提速5-10倍）
aria2c -x 10 -s 10 \
  "https://hf-mirror.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/model.tflite" \
  -o all-MiniLM-L6-v2.tflite \
  -d ./models/
```

### 技巧2：使用 wget/curl 断点续传

```bash
# wget 断点续传
wget -c "https://huggingface.co/.../model.tflite" \
     -O models/all-MiniLM-L6-v2.tflite

# curl 断点续传
curl -L -C - -o models/all-MiniLM-L6-v2.tflite \
     "https://huggingface.co/.../model.tflite"
```

### 技巧3：GitHub 加速下载

有些模型也在 GitHub 上有镜像：

```bash
# 从 GitHub 下载（可能更快）
wget https://github.com/UKPLab/sentence-transformers/releases/download/vX.X.X/all-MiniLM-L6-v2.tflite
```

---

## ❓ 常见问题

### Q1: hf-mirror.com 打不开？

**解决方案**：
1. 尝试刷新或更换DNS（如 114.114.114.114, 8.8.8.8）
2. 直接访问 huggingface.co 官方（需要梯子）
3. 使用方案1手动下载

### Q2: 下载速度很慢？

**优化方法**：
1. 使用多线程下载工具（aria2, IDM）
2. 选择凌晨时段下载（网络拥堵少）
3. 使用离线下载功能（百度网盘等）

### Q3: 下载的文件损坏？

**验证方法**：
```bash
# 检查文件大小是否合理（应该在 20-25MB 之间）
dir models\all-MiniLM-L6-v2.tflite

# 或者用Python检查
python -c "import os; print(f'文件大小: {os.path.getsize(\"models/all-MiniLM-L6-v2.tflite\")/1024/1024:.2f} MB')"
```

如果文件 < 1MB 或 > 100MB，说明下载不完整，请重新下载。

### Q4: 不想下载模型，能测试其他功能吗？

**完全可以！**

即使没有TFLite模型，你仍然可以测试：
- ✅ GPU能力检测
- ✅ Vulkan支持验证  
- ✅ ML Kit图像标签识别
- ✅ ML Kit目标检测
- ✅ ML Kit OCR文字识别
- ✅ 性能监控和统计
- ❌ 本地文本嵌入（需要模型）

运行测试：
```java
Intent intent = new Intent(this, GPUTestActivity.class);
startActivity(intent);
```

---

## 📊 下载完成后的验证清单

下载完模型文件后，执行以下检查：

- [ ] 文件存在：`models/all-MiniLM-L6-v2.tflite`
- [ ] 文件大小：20-25 MB（不是 0KB 或几KB）
- [ ] 运行检查命令显示 ✅
- [ ] 可以正常导入 TextEmbeddingService
- [ ] 测试基础嵌入功能正常

### 验证命令：

```bash
# 1. 检查文件完整性
python download_resources.py --check

# 2. 测试Python能否读取
python -c "
import os
path = 'models/all-MiniLM-L6-v2.tflite'
if os.path.exists(path):
    size = os.path.getsize(path)
    print(f'✅ 文件存在: {size/1024/1024:.2f} MB')
else:
    print('❌ 文件不存在')
"

# 3. （可选）测试Java端是否能加载
# 在Android Studio中运行 GPUTestActivity
```

---

## 🆘 需要帮助？

如果以上方案都无法解决，你可以：

1. **查看详细日志**
   ```bash
   python download_resources.py --model all-MiniLM-L6-v2 2>&1 | tee download.log
   ```
   将 `download.log` 发给我分析

2. **提供网络环境信息**
   - 是否在公司/学校网络？
   - 是否有防火墙？
   - 是否能访问 Google/HuggingFace？

3. **尝试简化版本**
   我可以创建一个最小化的测试模型（< 1MB），用于基本功能验证

---

## 📝 总结

对于**国内用户**，推荐的下载顺序：

1. **首选**：方案1（浏览器手动下载）- 最稳定可靠
2. **备选**：方案4（网盘分享）- 速度快
3. **有代理**：方案2（代理+自动脚本）- 最方便
4. **临时方案**：方案5（使用API）- 功能完整但需联网

---

**💡 提示**：TFLite模型文件只需要下载一次，之后就可以离线使用了！

---
**最后更新**: 2026-05-10  
**适用地区**: 中国大陆 🇨🇳
