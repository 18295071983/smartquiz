# AgentDebugBridge —— 应用内在线 Agent 外部注入通道（正式 debug 能力）

长期保留的调试/自动化能力：允许外部（adb shell 或任何持 token 的调用方）通过**显式广播**，
向应用内的在线 Agent（`OnlineAgentEngine`，完整工具链）注入 prompt 并驱动其执行一次任务，
结果以文本文件落盘供拉取。用于自动化联动测试、外部编排、诊断、CI 集成。

## 安全模型（四层防护）

| 层级 | 机制 | 说明 |
|---|---|---|
| 1. 授权码 | 随机 16 位 hex token | 首次触发时生成并持久化（SharedPreferences），logcat 打印一次；**所有 action 必须携带**，不匹配直接拒绝 |
| 2. 通道开关 | `enabled`（默认 **OFF**） | 必须先带 token 打开；关闭状态拒绝一切注入；状态持久化（install -r 保留） |
| 3. 显式广播 | `-n` 指定组件 | 满足 Android 8+ 隐式广播限制；避免干扰系统广播 |
| 4. 执行隔离 | 独立引擎实例 + 会话隔离 + 并发锁 + 超时 | 不污染主对话历史；同时仅一个任务；120s 超时自动释放锁；只写结果文件 |

## 用法

### 1) 获取 token（首次任意触发一次，logcat 打印）

```bash
adb shell am broadcast -a com.oilquiz.app.DEBUG.AGENT_ENABLE \
    -n com.oilquiz.app/.infra.AgentDebugBridge --ez enable true
adb logcat -s AgentDebugBridge   # 找行：token=xxxxxxxxxxxxxxxx (首次生成，已持久化)
```

### 2) 打开通道（必须带 token）

```bash
adb shell am broadcast -a com.oilquiz.app.DEBUG.AGENT_ENABLE \
    -n com.oilquiz.app/.infra.AgentDebugBridge \
    --es token <TOKEN> --ez enable true
```

### 3) 注入执行

```bash
adb shell am broadcast -a com.oilquiz.app.DEBUG.AGENT_EXEC \
    -n com.oilquiz.app/.infra.AgentDebugBridge \
    --es token <TOKEN> \
    --es prompt "你的指令" \
    [--es session 会话id]      # 可选：历史隔离 key（不传则按时间戳自动隔离）
    [--ei max_tokens 16384]    # 可选：max_tokens，默认 8192（引擎内部与 16384 取大）
    [--ez thinking true]       # 可选：开启深度思考（DeepSeek reasoning_content）
```

### 4) 读取结果

```
/storage/emulated/0/Android/data/com.oilquiz.app/files/agent_bridge/result_<时间戳>.txt
adb pull /storage/emulated/0/Android/data/com.oilquiz.app/files/agent_bridge/result_<时间戳>.txt .
```

结果内容：`PROMPT` → `[STEP]`（模式/环境感知/轮次）→ `THINK`/`TOKEN`（流式）→
`[TOOL]`/`[TOOL-OK]`（工具调用及结果）→ `=== COMPLETE ===` / `=== ERROR ===`。

### 5) 关闭通道

```bash
adb shell am broadcast -a com.oilquiz.app.DEBUG.AGENT_ENABLE \
    -n com.oilquiz.app/.infra.AgentDebugBridge \
    --es token <TOKEN> --ez enable false
```

## 参数表

| 参数 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `token` | string | ✅ | — | 授权码；不匹配即拒绝 |
| `prompt` | string | ✅（EXEC） | — | 注入的指令 |
| `session` | string | 否 | 时间戳 | 会话历史隔离 key |
| `max_tokens` | int | 否 | 8192 | 输出上限 |
| `thinking` | bool | 否 | false | 深度思考开关 |
| `enable` | bool | ✅（ENABLE） | — | true=开，false=关 |

## 关键行为与注意事项

- **会话隔离**：每次执行使用独立 sessionId（历史文件 `online_agent_history_<id>.json`），
  不污染主应用 AI 对话默认历史；并发互斥（执行中再次注入被拒）。
- **超时**：120s 后仅释放并发锁（不强制中断引擎）；超长任务完成后结果仍会写入文件。
- **token 管理**：token 持久化于 app 私有 SharedPreferences；清除应用数据会重置；
  泄露后可清数据重新生成。`install -r` 升级保留。
- **主应用未运行**：广播会拉起主应用进程（冷启动较慢，注入后耐心等待 logcat `execute:` 出现）。
- **只写结果文件**：不读不写业务数据；工具链（export_apk 等）产物按工具自身逻辑落到 agent 工作区。
- **shell 可见性**：结果目录受 scoped storage 保护，`adb shell ls` 可能看不到，用 `adb pull` 读取。

## 故障排查

| 现象 | 原因 | 处理 |
|---|---|---|
| logcat 无任何日志 | 广播未达/进程冷启动中 | 确认命令带 `-n`；等待 10-20s 再查 |
| `拒绝: token 不匹配` | token 错误或缺失 | 从 logcat 取正确 token |
| `拒绝: 通道未开启` | enabled=false | 先发 ENABLE enable=true |
| `拒绝: 已有任务执行中` | 上一个任务未结束 | 等待 done/error 或 120s 超时 |
| `拒绝: prompt 为空` | 缺 prompt 参数 | 补 `--es prompt` |

实现：`src/main/java/com/oilquiz/app/infra/AgentDebugBridge.java`
注册：`src/main/AndroidManifest.xml`（exported=true，action 见上）
