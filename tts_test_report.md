# 📋 WebSocket TTS 真机测试结果

## ✅ 修复的问题

**问题**: OkHttpClient 初始化失败
```
E/CosyVoiceWebSocket: Failed to initialize OkHttpClient: trustManager.acceptedIssuers must not be null
```

**解决方案**: 修改 `CosyVoiceWebSocketClient.java` 第 103 行
```java
// 修复前
public X509Certificate[] getAcceptedIssuers() { return null; }

// 修复后  
public X509Certificate[] getAcceptedIssuers() { 
    return new X509Certificate[0];  // 返回空数组而非 null
}
```

---

## ⚠️ 当前问题：模型配置错误

### 错误信息
```
I/TTSService: 使用 CosyVoice WebSocket TTS: workspace=ws-z60bk733531un9rr, region=cn-beijing, model=fun-asr-flash-2026-06-15
E/TTSService: DashScope 原生TTS失败: HTTP 400 - url error, please check url
```

### 问题分析
**您当前配置的模型是 `fun-asr-flash-2026-06-15`，这是一个语音识别（ASR）模型，不是语音合成（TTS）模型！**

百炼平台的语音模型区分：
- **ASR 模型**（语音识别）: `fun-asr-flash`, `qwen3-asr-flash` ← 不能用于 TTS
- **TTS 模型**（语音合成）: `cosyvoice-v1`, `qwen3-tts-flash` ← 必须用这些

---

## 🔧 正确配置步骤

### 方法 1：在 App 中配置（推荐）

1. **打开 smartquiz app**
2. **进入「我的」→ 「模型管理」**
3. **找到「语音合成(TTS)」部分**
4. **点击选择模型**，选择以下之一：
   - ✅ `cosyvoice-v1` （推荐，质量最高）
   - ✅ `qwen3-tts-flash` （快速，成本低）
   - ✅ `cherry-tts` （标准音色）

5. **启用该模型**（开关打开）

### 方法 2：检查业务空间设置

您当前使用的业务空间：`ws-z60bk733531un9rr` (北京区域)

在这个业务空间中，您需要：
1. 登录百炼控制台：https://dashscope.console.aliyun.com/
2. 进入「模型服务」→ 「API Key 管理」
3. 确保您的 API Key 有权限调用 TTS 模型
4. 推荐使用专门的 TTS 模型：`cosyvoice-v1`

---

## 📝 支持的 TTS 模型列表

| 模型名称 | 特点 | 推荐音色 | 适用场景 |
|---------|------|---------|---------|
| **cosyvoice-v1** | 高质量，24kHz | Cherry, Serena | 默认推荐 |
| **qwen3-tts-flash** | 快速，低成本 | Serena, Ethan | 实时对话 |
| **cherry-tts** | 标准质量 | Cherry, Momo | 通用场景 |

---

## 🧪 测试流程

配置完成后，请按以下步骤测试：

### 1. 重启 App
```powershell
adb shell am force-stop com.oilquiz.app
adb shell am start -n com.oilquiz.app/.MainActivity
```

### 2. 清空日志
```powershell
adb logcat -c
```

### 3. 发送 AI 消息并朗读
- 在对话中输入："你好，请介绍一下自己"
- 等待 AI 回复
- 点击消息旁的 🔊 朗读按钮

### 4. 查看日志验证
```powershell
# 监听 TTS 相关日志
adb logcat -s TTSService:I CosyVoiceWebSocket:I SpeechManager:I
```

### ✅ 成功的标志
```
I/TTSService: Using dedicated TTS model: cosyvoice-v1
I/TTSService: 使用 CosyVoice WebSocket TTS: workspace=ws-z60bk733531un9rr, region=cn-beijing, model=cosyvoice-v1
I/CosyVoiceWebSocket: Connecting to: wss://ws-z60bk733531un9rr.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference
I/CosyVoiceWebSocket: WebSocket 连接成功
I/TTSService: 收到音频数据块, size=19876
I/TTSService: WebSocket 合成成功, 音频时长 2.3s, 文件大小 19876B
D/AIAgent: 💬 TTS ✅ WebSocket 节省 xxx ms
```

---

## 🆘 如果仍然失败

请提供以下信息：

1. **当前配置的模型名称**
   - 路径：我的 → 模型管理 → 语音合成(TTS)
   
2. **完整的日志输出**
   ```powershell
   adb logcat -t "100" | clip
   # 然后粘贴到这里
   ```

3. **API Key 是否有效**
   - 确认 API Key 没有过期
   - 确认业务空间中有 TTS 模型权限

---

## 📞 技术支持

如果遇到问题，可以：
1. 查看百炼官方文档：https://help.aliyun.com/zh/model-studio/cosyvoice-api
2. 检查 API Key 配额：https://dashscope.console.aliyun.com/
3. 联系技术支持
