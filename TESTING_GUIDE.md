# CosyVoice WebSocket TTS 真机测试指南

## ✅ 完成情况总结

### ws4 - StreamingTtsSpeaker 适配（已完成）

**架构链路**：
```
StreamingTtsSpeaker.feed(text)
    ↓ (按句切分)
SpeechManager.synthesizeSpeech(sentence, forceOnline=true)
    ↓
TTSService.synthesizeAsync(text, voice, forceOnline)
    ↓
TTSService.callSpeechAPI(config, modelName, text, voiceId)
    ↓
├─ tryCosyVoiceWebSocketSynthesis() ← 优先尝试 WebSocket ✅
│   ├─ 建立 WebSocket 连接（wss://workspace.region.maas.aliyuncs.com）
│   ├─ 发送 run-task → continue-task → finish-task
│   ├─ 接收音频流数据
│   └─ 返回 MP3/PCM/WAV 格式文件
│
└─ callDashScopeTtsAPI() ← WebSocket 失败时降级 HTTP ✅
```

**关键特性**：
1. ✅ **自动选择引擎**：`StreamingTtsSpeaker.pumpSynthesis()` 设置 `onlineOnly = sessionOnline`
2. ✅ **音色一致性**：整轮朗读锁定同一引擎（在线或系统），避免句间音色切换
3. ✅ **优雅降级**：WebSocket 失败自动回退到 HTTP API，不会中断服务
4. ✅ **超时保护**：`synthesizeWithTimeout(text, voice, 120000)` 支持长文本合成
5. ✅ **错误处理**：所有异常都被捕获并记录日志

---

## 📱 真机测试准备（ws6_pre）

### 前置条件检查清单

#### 1. 硬件要求
- [ ] Android 设备（minSdk 31 = Android 12+）
- [ ] 稳定的 Wi-Fi 网络连接
- [ ] 至少 100MB 可用存储空间

#### 2. 账号与配置
- [ ] 阿里云百炼平台账号
- [ ] 有效的 API Key
- [ ] Workspace ID（从控制台获取）
- [ ] 已启用 CosyVoice/TTS 服务权限

#### 3. 应用配置
- [ ] 在 SmartQuiz 应用的"模型管理"页面配置：
  - ✅ 语音合成模型（如 cosyvoice-v2, qwen-tts 等）
  - ✅ API URL（包含 workspace id）
  - ✅ API Key
  - ✅ 音色选择（如 Cherry, longxiaochun 等）

#### 4. 权限检查
- [ ] 存储权限（READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE）
- [ ] 网络权限（INTERNET - 通常在 AndroidManifest.xml 中已声明）
- [ ] 麦克风权限（如需测试语音识别）

---

## 🧪 测试场景与步骤

### 场景 1：基础 WebSocket 连接测试

**目标**：验证 WebSocket 连接是否能成功建立

**步骤**：
1. 启动 SmartQuiz 应用
2. 进入"AI 对话"页面
3. 输入一段测试文本（如 "你好，请自我介绍一下"）
4. 开启"自动朗读"开关
5. 观察 Logcat 日志

**预期日志**：
```
D/CosyVoiceWebSocket: Connecting to: wss://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference
I/CosyVoiceWebSocket: WebSocket connected successfully
I/CosyVoiceWebSocket: Task started, ready to send text
D/CosyVoiceWebSocket: Received event: task-started
D/CosyVoiceWebSocket: Sending run-task: {"event_type":"run-task",...}
D/CosyVoiceWebSocket: Sending continue-task: 15 chars
I/CosyVoiceWebSocket: Task finished
I/TTSService: WebSocket TTS 合成完成
I/TTSService: WebSocket TTS 成功: 音频大小=XXXX bytes
```

**可能的失败情况**：
| 错误信息 | 原因 | 解决方案 |
|---------|------|---------|
| WebSocket 连接失败 | API URL 错误或网络不通 | 检查 Workspace ID 和网络连接 |
| Connection timeout | 防火墙阻止 wss:// 协议 | 检查网络安全策略 |
| HTTP 401/403 | API Key 无效或过期 | 重新获取 API Key |
| 任务超时 | 服务端处理缓慢 | 增加 `DEFAULT_TIMEOUT_MS` |

---

### 场景 2：流式朗读性能测试

**目标**：验证连续多句朗读的延迟和流畅度

**步骤**：
1. 进入 AI 对话页面
2. 输入长文本提示词（触发 AI 生成多句回复）
3. 开启自动朗读
4. 记录从第一句话开始朗读到最后一句话结束的时间
5. 注意是否有以下问题：
   - ❌ 首句延迟过长（应 < 1 秒）
   - ❌ 句间停顿明显（应 < 200ms）
   - ❌ 中途断档或卡顿
   - ❌ 音色不一致

**性能指标参考**：
- **首句出声时间**：< 800ms（WebSocket 握手后）
- **后续句子延迟**：< 200ms/句（复用连接时）
- **单句合成时间**：约文本长度（字符）× 50ms

---

### 场景 3：网络异常恢复测试

**目标**：验证网络断开时的降级机制

**步骤**：
1. 开启自动朗读
2. 在 AI 生成过程中关闭 Wi-Fi
3. 观察是否降级到 HTTP API 或系统 TTS
4. 恢复网络，继续生成

**预期行为**：
```
W/TTSService: WebSocket TTS 失败: Connection lost, 回退到 HTTP 方式
I/TTSService: 使用系统语音合成  // 如果 HTTP 也失败
```

---

### 场景 4：不同音色测试

**目标**：验证所有配置的音色都能正常工作

**测试音色列表**（根据注册表）：
| 音色 ID | 特点 | 测试状态 |
|--------|------|---------|
| Cherry | 甜美女声 | ⬜ 待测试 |
| longanyang | 阳光男声 | ⬜ 待测试 |
| longxiaochun | 清新男声 | ⬜ 待测试 |
| longmei | 活力女声 | ⬜ 待测试 |
| Reno | 成熟男声 | ⬜ 待测试 |
| Vera | 知性女声 | ⬜ 待测试 |

**步骤**：
1. 进入"模型管理" → 切换 TTS 音色
2. 每次切换后朗读一段文本
3. 确认音色有明显变化

---

### 场景 5：长时间稳定性测试

**目标**：验证长时间运行不崩溃

**步骤**：
1. 连续触发 10+ 次 AI 对话
2. 每次对话都开启自动朗读
3. 总朗读时长 > 5 分钟
4. 检查是否有内存泄漏或崩溃

**监控项**：
- 内存占用增长（Android Studio Profiler）
- CPU 使用率
- WebSocket 连接数（不应持续增长）

---

## 🔍 调试工具与命令

### 1. Logcat 过滤
```bash
# Windows PowerShell
adb logcat | Select-String "CosyVoiceWebSocket|TTSService|StreamingTtsSpeaker"

# Linux/Mac
adb logcat | grep -E "CosyVoiceWebSocket|TTSService|StreamingTtsSpeaker"
```

### 2. 查看缓存的音频文件
```bash
# 进入设备内部存储
adb shell

# 进入应用缓存目录
cd /data/data/com.oilquiz.app/cache

# 列出 TTS 音频文件
ls -la tts_ws_*

# 拉取文件到 PC
adb pull /data/data/com.oilquiz.app/cache/tts_ws_*.mp3
```

### 3. 网络抓包（可选）
使用 Charles 或 Fiddler 抓取 WebSocket 流量：
1. 安装代理证书到设备
2. 设置代理指向 PC
3. 过滤 `maas.aliyuncs.com` 域名

---

## 📊 测试结果记录模板

### 测试人员：___________
### 测试日期：___________
### 设备型号：___________
### Android 版本：___________

| 测试场景 | 预期结果 | 实际结果 | 通过？ | 备注 |
|---------|---------|---------|-------|------|
| 基础连接 | WebSocket 成功 | _________ | ☑/☐ | |
| 首句延迟 | < 800ms | _________ | ☑/☐ | _________ms |
| 句间流畅 | < 200ms | _________ | ☑/☐ | |
| 音色切换 | 明显差异 | _________ | ☑/☐ | |
| 异常降级 | 正常回退 | _________ | ☑/☐ | |
| 长稳测试 | 无崩溃 | _________ | ☑/☐ | |

---

## 🐛 常见问题排查

### Q1: WebSocket 一直连接失败
**检查顺序**：
1. 网络是否通畅？→ 浏览器访问 `https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com`
2. API Key 是否正确？→ 在百炼控制台检查
3. Workspace ID 提取是否正确？→ Logcat 查看 `extractWorkspaceId` 日志
4. SSL 证书是否信任？→ 开发环境已临时禁用验证

### Q2: 没有声音输出
**检查顺序**：
1. 设备音量是否打开？
2. 是否选择了正确的音色（非 sys: 开头的系统 TTS）？
3. Logcat 是否有 "WebSocket TTS 成功" 日志？
4. 音频文件是否生成？→ 检查 cache 目录

### Q3: 合成速度很慢
**可能原因**：
1. WebSocket 每次建新连接 → 优化建议：实现连接池
2. 文本太长 → 阿里云限制单次 ~500 字符
3. 网络延迟大 → 切换到北京区域节点

### Q4: 音质不佳
**调整选项**：
1. 切换音频格式（MP3 > WAV > PCM）
2. 更换音色（不同音色音质感知的差异）
3. 增加采样率（当前默认 24kHz for MP3）

---

## 📝 改进建议收集

测试过程中请记录：
1. 用户体验反馈（音色自然度、语速等）
2. 性能瓶颈（延迟最高的是哪一步）
3. 异常情况（崩溃、死锁、内存泄漏）
4. 功能建议（是否需要实时打断、语速调节等）

---

## 🚀 下一步计划

根据测试结果决定：
- [ ] 优化 WebSocket 连接复用（如果需要）
- [ ] 添加更多音色支持
- [ ] 支持自定义参数（语速、音调等）
- [ ] 集成其他 TTS 模型（如通义千问 TTS）
- [ ] 实现语音打断功能

---

**文档版本**: v1.0  
**最后更新**: 2026-08-08  
**维护者**: SmartQuiz Team
