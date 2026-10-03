# 开发规范

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件描述本项目的开发规范、代码组织约定、构建与测试。

## 一、代码组织约定

### 1.1 分层

```
UI 层（ui.*）          → 只做显示、事件、装配
ViewModel 层（viewmodel）→ 状态管理、LiveData
Repository 层（repository）→ 数据访问
Database 层（database）  → Room Entity/DAO
AI 能力层（ai.*）       → 推理/Agent/工具/语音/GPU
```

### 1.2 Activity 薄壳原则

`AIChatActivity` 只做：
- `initView()` — 绑定布局控件
- `initData()` — 装配模块、初始化服务
- `initListener()` — 绑定监听器

业务逻辑在独立模块类（Manager），通过 **Callback 接口** 与 Activity 解耦。

## 二、AI 模块约定

1. **Agent 单循环**：任何逻辑不得引入"多轮 LLM 编排"（意图识别/任务分解/思考链/结果整合的多次 LLM 调用）。这些会破坏 chat context 的 KV cache 导致 SIGSEGV。
2. **本地/在线双路由**：推理入口统一走 `AgentChatHandler.startAgentLoop()` 的 `useLocalAgent` 判断。
3. **工具按需注入**：工具描述不常驻 prompt，关键词命中 + tool_registry + control_lookup 动态注入。
4. **KV 增量缓存**：多轮迭代复用 `AgentKvCache`，账目 = prompt + 生成输出。

## 三、推理参数约定

- Agent 轮次上限：`MAX_ITERATIONS_BASE=12`、`MAX_TOOL_ROUNDS=6`
- 时间预算：`TOTAL_TIME_BUDGET_MS=180000`
- 输出上限：`FINAL_RESPONSE_MAX_TOKENS=4000`
- 上下文：由 `AIConfig.OptimizationMode` 决定（BALANCED 默认 12288）

## 四、构建

```bash
# 编译 Java（快速验证）
.\gradlew.bat compileDebugJavaWithJavac --offline

# 完整打包 APK
.\gradlew.bat assembleDebug --offline

# 安装到设备
adb install -r build/app/outputs/apk/debug/答题宝-debug-2.0.apk
```

**注意**：Windows 下 gradle 即使 BUILD SUCCESSFUL 也可能返回 exit code 1（警告处理怪癖），以 "BUILD SUCCESSFUL" 文本为准。

## 五、测试

- 单元测试：模块类（Manager）可独立单测。
- 集成测试：端到端对话（发送→生成→完成）、模式切换、流式更新。
- 手工测试：`adb shell am start -n com.oilquiz.app/.ui.activity.AIChatActivity`（注意该 Activity `exported=false`，需经 App LAUNCHER 进入）。

## 六、Git 说明

- 当前分支：`feature/agent-local`
- 工作区改动集中在后端性能优化（native-lib/AgentLoopEngine/AIConfig/AIWeatherManager）+ 设计文档（docs/development）。
- 重构注意：改 AIChatActivity 前先 `git status` 确认；编译失败用 `git checkout -- <file>` 回滚。

## 2026-09/10 更新（构建要点）

- **版本**：AGP 8.9.2、Gradle 8.13、compileSdk/targetSdk 36、buildTools 36、NDK 26.1（固定）；JDK 17。
- **edge-to-edge**：targetSdk 35+ 强制 e2e；不要再加 windowOptOutEdgeToEdgeEnforcement（API 36 已移除该属性）；新增界面统一经 EdgeToEdgeHelper 适配（根容器 insets padding）。
- **Jetifier 注意**：ndroid.enableJetifier=true 全局生效；新增纯 Java 依赖若内含高版本字节码类（如 jsch 2.27+ 的 Java 24 ML-KEM class），须在 gradle.properties 加 ndroid.jetifier.ignorelist=<依赖名> 跳过转换，否则报 Unsupported class file major version 68。
- **快速编译校验**：小改动优先用 	ools/tests/javac_check.ps1（秒级，不跑 Gradle），全量用 gradlew assembleDebug。

## 相关文档

- [项目架构总览](01-project-overview.md)
- [模块清单](08-module-inventory.md)
- [数据层设计](06-data-layer.md)
