# 答题宝 (SmartQuiz) 开发路线图与迭代建议

> 版本: 2.4 | 更新日期: 2026-10-19 | 基于代码库实测
> （2.2 停在 2026-08-10；2.3 现状校正；2.4 取消新功能规划）

> **范围决策（2026-10-19）**：本轮迭代**不做新功能**，原「新功能规划」章节已整体删除。
> 后续只做三件事：安全/数据边界收口、既有功能修复、代码与构建质量。

---

## 〇、现状校正（2026-10-19）

> 上一版停在 2026-08-10，此后 CHANGELOG 新增 **58 条**记录，路线图未同步：
> 旧版写的 `AIChatActivity 1,449 行`、`AIService 2,113 行`、`UnifiedAgentEngine 842 行`
> 都已与代码不符（前者实际 11,691 行，中间的已 5,100 行，后者类已删除）。
> 本节按 2026-10-19 工作区实测重算，**与后文冲突时以本节为准**；后文保留原文以便对照，
> 但 §2.1 / §2.5 / §3.1 / §3.2 已就地更新。

### 0.1 实测基线

| 指标 | 旧版（2026-08-10） | 实测（2026-10-19） |
|---|---:|---:|
| 源文件 | 600+ | 896 Java + 2 Kotlin（app 侧） |
| 最大文件 | 3,559 行（WebViewActivity） | **11,691 行**（AIChatActivity） |
| > 2,000 行的文件 | 未统计 | **至少 12 个**（本次抽查，见 §2.1） |
| 测试类 | 21 | 30+（条数与基线见 §2.5） |
| CI | 无 | **仍无**（§5 的建议仍有效） |
| DI | Hilt 已集成、未全面使用 | 全仓 **7 个 `@Inject`**，其余仍是 `getInstance` |
| 裸 `new Thread` | 未统计 | **190 处** |
| 单行空 catch | 20+ | **182 处** |

### 0.2 2026-08-10 之后已交付（按 CHANGELOG 归并，只列能力，不列细节）

- **推理引擎**：GenieX/QNN/Hexagon NPU 接入，与 llama.cpp 可无缝切换（10-03 ~ 10-09）；
  预设全面 Q4 化（10-04）；NPU Agent 引擎与回调链修复（10-05）。
- **Agent**：引擎对齐 dsh 架构（09-23~24）；在线工具链独立；本地 Agent 核心工具收敛为 4 个（10-11）；
  Responses API 按官方文档收口并放量 DeepSeek（10-17~18）。
- **模型与密钥**：模型中心统一注册与用量（10-18）；API Key 改 Keystore AES-256-GCM（10-17）；
  密钥页与语音勾选口径收敛（10-19）。
- **多模态**：图片附件链路（多图 / MIME 按真实字节 / 预算内压缩，10-18）；语音勾选真正生效（10-19）。
- **聊天 UI**：Markdown 走 WebView 渲染、动态画布（尺寸/固定/约束）、工具结果自动挂卡片、
  历史抽屉悬浮球（10-12 ~ 10-13）；ChatAdapter 深度审查与组件复用（10-12）。
- **工具箱**：内置命令路由 + 媒体工具箱 + Python 文档库（09-26）；SSH（10-03）；
  Termux + Ubuntu 一键准备与向导化（09-27 ~ 10-02）；remote_dsh v5（10-03）。
- **其他**：天气模块重构 + 极光时钟（09-21）；VNC 曾上线，已在 10-01~02 整体删除（不要再按"有 VNC"规划）。

### 0.3 与旧版冲突、已就地更正的条目

| 旧版表述 | 实际 | 处理 |
|---|---|---|
| `AIChatActivity` 1,449 行，按 400 行拆分 | 11,691 行 | §2.1 重排，拆分方案改为按 presenter 切 |
| `AIService` 2,113 行 | 5,100 行 | §2.1 更新 |
| `UnifiedAgentEngine` 842 行 | 类已删除，能力并入 `OnlineAgentEngine` | §2.1 移除该行 |
| `DeviceCapabilityDetector` 待删除 | 已不存在 | §2.2 该项可标完成 |
| "21 个测试文件" | 30+ 个测试类 | §2.5 更新 |
| "Kotlin < 1%（3 个 Compose 示例）" | app 侧 2 个 `.kt`；Java 源码 0 处 `androidx.compose` | §3.1 / §3.2 更新 |
| `AILogger2` 待合并 | 仍存在，`AILogger` 已迁入部分能力 | §2.2 保持待办 |

### 0.4 当前真正该先做的

不是继续按旧版"拆 400 行"，而是先补三类边界（安全 / 数据 / 回归），再拆大文件。完整清单见
**[设计走查：边界问题清单（2026-10-19）](docs/design-review-2026-10-19.md)**（D1–D15，含优先级与验收）。
其中 P0 三条：导出组件与 WebView 桥（D1/D2）、明文口令与默认账号（D3）、破坏性迁移（D11）。

---

## 已完成更新（≤ v2.1.0；2026-08-10 之后的交付见 §0.2）

### ✅ v2.1.0 (2026-07-29) - 已完成

| 任务 | 状态 | 详情 |
|------|------|------|
| **Agent 软件层架构** | ✅ 已完成 | 意图识别→复杂度分析→任务分解→执行引擎→思考链→结果整合 六模块 |
| **在线 Agent 系统** | ✅ 已完成 | 支持 OpenAI 兼容 API 的在线 Agent + 工具调用 |
| **语音系统全面升级** | ✅ 已完成 | TTS/ASR 多引擎支持（百度/讯飞/阿里云/火山/OpenAI） |
| **天气系统重构** | ✅ 已完成 | 三级回退机制 + 空气质量 + 分钟降水 + 预警 |
| **天气 Banner 组件** | ✅ 已完成 | 可定制天气横幅组件 |
| **AI 聊天界面升级** | ✅ 已完成 | 思考链可视化 + 工具调用渲染 + 流式展示 |
| **创意写作引擎** | ✅ 已完成 | AI 辅助创意写作 |

### ✅ v2.0.1 (2026-05-26) - 已完成

| 任务 | 状态 | 详情 |
|------|------|------|
| **JNI 中文编码修复** | ✅ 已完成 | 标准 UTF-8 编解码，解决中文乱码 |
| **主界面优化** | ✅ 已完成 | 按钮功能调整 + Emoji 图标统一 |
| **在线模型支持** | ✅ 已完成 | 完善在线模型配置和管理 |

### ✅ v2.0.0 (2026-05-20) - 已完成

| 任务 | 状态 | 详情 |
|------|------|------|
| **API 密钥安全迁移** | ✅ 已完成 | 移除硬编码API Key，使用APIKeyManager统一管理 |
| **模型加载性能优化** | ✅ 已完成 | 全局初始化一次性执行，加载时间从30秒降至5秒（提升83%） |
| **GPU 加速配置优化** | ✅ 已完成 | GPU层数自动计算，推理速度从0.3 t/s提升至12 t/s（提升40x） |
| **Batch Size 动态计算** | ✅ 已完成 | 根据设备内存和GPU/CPU模式动态调整批处理大小 |
| **热启动保持** | ✅ 已完成 | 模型在内存中保持加载状态，内存紧张时不再释放 |
| **AI 聊天界面修复** | ✅ 已完成 | 修复消息不显示、流式更新字段丢失、生成超时等问题 |

**详细变更请参阅** [CHANGELOG.md](CHANGELOG.md)

---

## 一、路线图总览

```
Phase 4 (语音与Agent)     Phase 5 (质量与架构)
已完成 2.1.0              进行中
    ↓                           ↓
┌──────────────┐    ┌──────────────────────┐
│ 语音系统升级   │    │ 测试覆盖率提升        │
│ Agent软件层   │    │ 错误处理规范化        │
│ 天气系统重构   │    │ 超大文件拆分          │
│ 在线Agent     │    │ Kotlin 迁移启动       │
│ 创意写作      │    │ Compose 全面化        │
└──────────────┘    └──────────────────────┘
```

> 原总览里并列的"生态与体验"一列（题库市场 / 多人对战 / 学习数据分析 / 自适应出题 / 知识图谱）
> 已按 2026-10-19 决策**整体取消**，不再排期；相关功能条目已从本文件删除。
> 注意：本文件的 Phase 6 专指"架构演进"（见 §三），与已取消的那一列不是同一件事。

---

## 二、Phase 5 — 质量与架构 (优先级 P1)

> **目标**: 1-2 个月内完成，系统性改善代码质量和架构

### 2.1 超大文件拆分 🔴（2026-10-19 按实测重排）

> 旧版按"行数排序"，但 9–10 月的实际改动集中在聊天页与协议层，所以这里改按**被改频率 × 回归风险**排序。
> 行数为 2026-10-19 实测；旧版"每个文件 ≤ 400 行"对现状不现实，先以 **≤ 1,500 行**为阶段目标。

| 优先级 | 文件 | 行数 | 拆分方案 |
|:---:|------|:---:|------|
| **P0** | `AIChatActivity.java` | 11,691 | 按 presenter 切：输入/流式渲染/工具编排/会话与历史/权限；先抽可 JVM 测的部分 |
| **P0** | `ChatAdapter.java` | 6,387 | 11 种消息类型 → 每类一个 delegate + 统一 ViewHolder |
| **P0** | `AIService.java` | 5,100 | `ServiceLifecycle` / `InferenceQueue` / `EngineSwitch` 三段 |
| **P1** | `PythonToolManager.java` | 4,959 | 运行时 / 工具注册 / UI 组件 / 播放器 四块 |
| **P1** | `OnlineInferenceService.java` | 4,606 | `ChatTransport` + 4 个 codec + `CapabilityProbe` + `UsageParser`（见走查 D8） |
| **P1** | `NativeLayoutRenderer.java` | 4,189 | 解析 / 布局 / 控件工厂 / 事件绑定 |
| **P1** | `AIWeatherManager.java` | 4,047 | 按数据源拆 + 公共抽象层 |
| **P2** | `WebViewActivity.java` | 3,533 | 生命周期 / 配置 / 导航 / JS 桥（JS 桥的信任边界见走查 D1） |
| **P2** | `OnlineAgentEngine.java` | 2,884 | 循环 / 工具调度 / 流式渲染 / 错误恢复 |
| **P2** | `SystemResourceTool.java` | 2,634 | SSH / Shell / 文件 / 进程 / 媒体 |
| **P2** | `QuizActivity.java` | 2,538 | 按答题模式拆：挑战 / 考试 / 练习 |
| **P2** | `AIToolManager.java` | 2,114 | 工具注册 / schema 生成 / 执行编排 |

**拆分原则**:
- 阶段目标：单文件 **≤ 1,500 行**；400 行作为终态目标保留，不再作为近期门槛
- 每个方法不超过 **50 行**（新增方法一律遵守）
- 使用组合模式代替继承
- 先拆"有明确输入输出、能在 JVM 断言"的部分，再拆需要真机验证的 Activity / 渲染

### 2.2 消除重复代码 🟡

**Logger 三剑客合并**:

| 当前 | 替代方案 |
|------|---------|
| `AILogger.java` | 保留唯一入口 |
| `AILogger2.java` | **删除**，合并到 AILogger |
| `AppLogger.java` | 迁移为 AILogger 的包装 |

**设备检测器合并**:
- `DeviceDetector.java` ← 保留
- `DeviceCapabilityDetector.java` ← **已删除**（2026-10-19 校验：全仓无引用）

**模型参数去重** — 三处相同数据:
- `MultiModelManager.java:L97-L112`
- `ModelSelectionActivity.java:L60-L93`
- `ModelComparisonActivity.java:L57-L90`

→ 统一提取到 `ModelPresetConfig` 或 `models.json` 配置文件。

> 注：`AILogger2` 仍存在（10-19 校验），合并未做；`AppLogger` 也仍在，二者与 `AILogger` 的关系仍是三套。

### 2.3 错误处理规范化 🟡

**2026-10-19 重算**: "替换率 60%"不现实（存量太大），阶段目标改为**停止新增 + 先改会吞数据的路径**。

| 当前（实测） | 阶段目标 | 方法 |
|------|------|------|
| ≈2,468 处 `catch (Exception…)`（grep 计数，含多行） | 新增代码 0 处；存量按模块收敛 | 先只改 DB / 迁移 / 配置 / 网络这类"失败会丢数据"的路径 |
| 182 处单行空 catch | 新增 0；存量加日志与计数 | 至少 `AILogger.w` + 关键上下文 |
| 无统一异常体系 | 在新 codec / repository 落地 Result 模式 | 不全局改造，随拆分逐步替换 |

**按"失败会丢数据"排序（2026-10-19 调整）**:
1. `database/`：迁移与写入路径的先修（见走查 D11），这里的静默失败代价最高
2. `ai/service/`：流式解析、错误回落、usage 统计
3. `WebViewActivity` / `AIChatActivity`：最后再动，先靠 D1 的边界收口降低风险

### 2.4 代码标准化 🟡

| 项 | 现状（2026-10-19） | 目标 |
|------|------|------|
| 文件行数 | 至少 12 个文件 > 2,000 行（最大 11,691） | 阶段 ≤ 1,500，终态 ≤ 400 |
| 方法行数 | 部分超过 100 行 | ≤ 50 行（新增方法） |
| 注释规范 | 代码中有大量无注释区域 | 所有公共 API 有 JavaDoc |
| 命名一致性 | ✅ `AIToolsManager` 已删除，统一使用 `AIToolManager` | 统一命名规范 |
| 重复类名 | `DatabaseManager` / `ChatHistoryManager` 等在多包下同名 | 重名类改名或合并 |

### 2.5 补充测试 🟠

**当前（2026-10-19）**: 30+ 个测试类，对 896 个 Java 源文件。旧版写的"21 个测试文件"已过时。

> **基线口径待对齐**：`docs/ui/HANDOVER.md`（10-19）记录 JVM 单测 **407 条全绿**；
> `CHANGELOG.md`（10-18）记录 **342 tests / 25 failed**。两者相差一天且结论相反，
> 说明至少有一方没复跑。**先跑一次 `testDebugUnitTest` 定基线**，再谈下面的覆盖率目标；
> 在基线确定前，"25 个失败是既有基线"不应继续当作正常状态。

**分阶段目标**:

| 阶段 | 目标模块 | 新增测试 | 理由 |
|------|---------|:---:|------|
| 第1周 | `ai/agent/` | 12 个 | 核心 Agent 引擎必须有测试保障 |
| 第2周 | `ai/gpu/` + `ai/model/` | 10 个 | GPU 子系统是性能关键 |
| 第3周 | `database/` DAO 层 | 15 个 | 数据库正确性是数据基础 |
| 第4周 | `repository/` | 10 个 | 数据仓库层的业务逻辑 |
| 第5周 | `ai/feature/` + `ai/skill/` | 8 个 | AI 功能模块 |
| 第6周 | `manager/` (Backup/Theme/Language) | 10 个 | 业务管理器 |

**测试策略**:
```
DAO 测试      → Room In-Memory Database (快速, 隔离)
Repository    → Mock DAO + LiveData 测试
ViewModel     → Mock Repository + Coroutine 测试
AI 引擎       → Mock JNI 层 + 异步测试
GPU 子系统    → Robolectric + 设备模拟
语音引擎      → Mock HTTP + 状态机测试
```

---

## 三、Phase 6 — 架构演进 (优先级 P2)

> **目标**: 2-4 个月内完成，提升系统可维护性和可扩展性

### 3.1 Kotlin 迁移 🟠

**当前（2026-10-19）**: app 侧只有 2 个 `.kt`（`compose/ComposeTheme.kt`、`ai/engine/NpuLlmChat.kt`）；
Java 源码中 0 处 `androidx.compose`。旧版"新代码 Kotlin 优先"在 9–10 月未执行——新增类仍是 Java。

**迁移策略 — 「新代码 Kotlin 优先」**:

> 现实检查（10-19）：9–10 月新增的类仍然是 Java，"Kotlin 优先"没有执行。
> 要么从下个新模块开始真正执行，要么明确放弃并移除 Kotlin/Compose 依赖；
> 不要保持"计划迁移但从不执行"的中间态——它只增加构建约束，不产生收益。

| 阶段 | 内容 | 预计时间 |
|------|------|:---:|
| 第1步 | 所有新类强制 Kotlin | 立即开始 |
| 第2步 | Model 类 → Kotlin data class | 1 周 |
| 第3步 | ViewModel 类 → Kotlin | 2 周 |
| 第4步 | Repository 类 → Kotlin | 2 周 |
| 第5步 | Manager 类 → Kotlin | 3 周 |
| 第6步 | Activity（分批）→ Kotlin | 逐步进行 |

**Kotlin 带来的收益**:
- 减少空指针: 编译期 null-safety
- 减少样板: data class、扩展函数、协程
- 更好的 Compose 支持

### 3.2 Compose 全面化 🟠

**当前（2026-10-19）**: Compose 仅 2 个文件、无生产页面；`build.gradle` 已开 `compose true`，
但 Java 侧零使用。建议二选一：要么立项做第一屏试点（如设置/关于），要么先移除 Compose 依赖，
减少构建面与版本约束（Kotlin 1.9.22 / compiler 1.5.8 目前只服务于这 2 个文件）。

**迁移路线**:
1. **新页面**: 全部用 Compose 编写
2. **简单页面优先迁移**: Settings, About, Category Selection
3. **中等页面**: Question Detail, Note Editor, Study Plan
4. **复杂页面最后**: AIChatActivity, QuizActivity, ImportActivity

**Compose vs XML 决策矩阵**:
- 新增页面（含重构拆出的页面）→ 一律 Compose
- 修改现有页面 → 评估改造工作量，优先 Compose
- 不改动的页面 → 保持 XML，不强制迁移

### 3.3 架构提升 🟠

| 项 | 现状 | 改进方向 |
|------|------|---------|
| **状态管理** | LiveData + 直接回调 | 统一到 StateFlow / SharedFlow |
| **导航** | 隐式 Intent + 手动管理 | 引入 Navigation Component (Compose + XML) |
| **模块化** | 单模块 (app) | 按功能拆分为 `:core`, `:feature-quiz`, `:feature-ai`, `:feature-export` |
| **构建优化** | 全量构建 | 增量编译 + Gradle Build Cache + 并行构建 |
| **依赖注入** | Hilt 已集成但未全面使用 | 扩大 Hilt 覆盖范围，减少 `new` 和手动单例 |

---

## 四、性能优化路线

### 4.1 启动优化

| 优化项 | 方法 | 预期收益 |
|------|------|:---:|
| Application.onCreate 延迟加载 | 将非必要初始化移到 IdleHandler | -30% 启动时间 |
| 布局优化 | 减少嵌套层级，使用 ViewStub/Compose 懒加载 | -15% 布局时间 |
| Splash 页优化 | 合并 Splash 和 MainActivity 初始化 | -200ms |

### 4.2 内存优化

| 优化项 | 方法 |
|------|------|
| 图片缓存 | Glide 内存上限调优，大规模列表用 Coil |
| WebView 回收 | 后台 Activity 的 WebView 主动释放 |
| AI 模型内存 | 推理完成后释放中间张量，降低 peak memory |
| 语音缓存 | TTS 结果缓存，避免重复合成 |

### 4.3 构建优化

```properties
# gradle.properties 推荐配置
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g
android.enableR8.fullMode=true
```

---

## 五、DevOps 与 CI/CD

### 5.1 推荐的 GitHub Actions 流水线

```yaml
# .github/workflows/ci.yml
on: [push, pull_request]
jobs:
  build:
    - Lint check (ktlint + spotless)
    - Unit test (./gradlew test)
    - Build debug APK
    - APK size analysis

  release:
    on: tag v*
    - Build release APK
    - Sign APK
    - Upload to GitHub Release
```

### 5.2 代码质量门禁

| 指标 | 2026-10-19 实测 | 阶段门禁 | 终态 |
|------|:---:|:---:|:---:|
| 测试覆盖率 | 未测（基线待定，见 §2.5） | 每个 PR 必须跑单测 | 核心模块 ≥ 60% |
| 单行空 catch | 182 | 新增 0 | 0 |
| 文件最大行数 | 11,691 | ≤ 1,500 | ≤ 400 |
| 方法最大行数 | 200+（未逐个数） | 新增 ≤ 50 | ≤ 50 |
| 重复代码率 | 未测 | 先靠 review 拦截 | < 3% |

### 5.3 发布流程标准化

```
开发 → 代码审查 → 合并 main → CI 通过 → 版本号 bump → git tag v2.x
                                                        ↓
                                              构建 Release APK
                                                        ↓
                                              内部测试 (3天)
                                                        ↓
                                              GitHub Release 发布
```

---

## 六、风险与依赖

| 风险 | 影响 | 缓解措施 |
|------|:---:|------|
| llama.cpp 上游 API 变更 | C++ JNI 接口失效 | 固定 llama.cpp 版本，定期测试兼容性 |
| TBS SDK 授权问题 | 文件预览功能不可用 | **TBS 未集成**（X5 已停维护），现走 Pdfium + LibreOffice；若重启 TBS 需重新评估授权与内核 so |
| 第三方 API 服务中断 | 天气、翻译、语音等功能不可用 | 本地缓存 + 错误降级 + 多引擎切换 |
| Android API 行为变更 | 编译/运行兼容性 | 每季度适配新 API 版本 |
| 签名密钥丢失 | 无法发布更新 | keystore 必须备份到安全位置 |
| Vulkan 驱动兼容性 | 部分设备 GPU 加速失效 | CPU 回退机制 + 自动检测 |

> **2026-10-19 新增的最高风险不在上表**：导出组件与 WebView 桥（任意应用可拉起读文件/题库）、
> 明文口令与默认账号、破坏性迁移、全局明文流量。详见
> [设计走查 D1–D15](docs/design-review-2026-10-19.md)。

### 6.1 `/responses` 路径：缺口、补齐过程与当前放量状态（2026-10-17 记录，2026-10-18 更新）

**最初的缺口（已核对代码）**

`callResponsesAPI()` 的请求体只有 `model / input / max_output_tokens / temperature / web_search`，
不含客户端 `tools`；`readResponsesStream()` 不聚合 `function_call*` 事件、不落 `usage`。
在线 Agent 的工具循环因此一直走 `/chat/completions`（功能可用）。当时判断"补齐需要连真实
端点验证流语义，离线交付等于埋一条没跑过的路径"，先记为缺口。

**为什么那个判断是对的，但不够**

后来读代码证实：那条已存在的 `/responses` 路径本身就是坏的 —— `delta` 被当成 JsonObject 取
（OpenAI 与 DeepSeek 都下发字符串）导致正文一个片段都渲染不出；读完 `response.completed`
只 `return`、从不投递 `onComplete`，界面停在"生成中"；非流式的 `readResponsesFull` 遇到
`JsonNull` 还会抛异常。也就是说"未验证的路径"早就在主干上，只是没人走进去过。

**现在的形态**

- 事件语义集中在 `ai.service.ResponsesStreamParser`（不依赖 Android，JVM 可测）：
  文本/思考增量、`function_call` 按 `item_id → item.id → output_index` 别名归槽、
  终态与取消、`usage` 四字段。`ResponsesStreamParserTest` 36 例。
- 请求侧集中在 `ai.service.ResponsesPayloadBuilder`：chat 方言 → `input` 条目
  （`function_call` / `function_call_output` 按 `call_id` 配对）、`tools` 扁平化、
  系统提示走顶层 `instructions`、思考走嵌套 `reasoning:{effort}`。
  `ResponsesPayloadBuilderTest` 14 例。
- Agent 循环（`OnlineAgentEngine`）**一行未改**：`callResponsesStreamWithTools` 桥回既有
  `NativeToolStreamCallback`。安全网两条：还没吐出任何字时失败自动回落 chat（文本流与工具流
  都有）；取消时把未收口的 call 整批定稿，不留"参数残缺且永不完成"的死态。
- 聚合规则不是照文档空想，而是按两份真实实现校正：仓库打包的 `llama.cpp` server
  （`item.arguments` 初值为空串、只发 `output_item.done` 不发 `arguments.done`、只带 `item_id`）
  与参考实现 `OpenAIProvider.kt`（存在"先 `output_item.done`、后 `arguments.delta`"的供应商，
  故 done 不能收口、快照不得与增量混拼）。

**当前放量状态**：唯一开关是配置表 `services.agent.protocol`，文本流与工具流共用它
（避免出现"一半在 Responses、一半在 chat"）。

| 服务商 | protocol | 依据 |
|---|---|---|
| deepseek | **responses** | 官方 Responses 指南逐条对齐：`function` 工具与 `function_call_output`+`call_id` 回填；`usage` 字段名与解析器读的四条路径一致；以 `completed\|incomplete\|failed` 收尾且无 `[DONE]`；不支持 `previous_response_id` / `store` / `parallel_tool_calls` / `stream_options`（我们本来就不发） |
| openai / minimax / moonshot | chat | 兼容层细节未逐家核实；有 `/responses` 端点声明，端点探测可用 |

**仍未做的**：DeepSeek 的**真实一次调用**（本机无 key、无设备）—— 放量靠"文档逐条对齐 +
未吐字即回落 chat"两层，而不是"应该没问题"。接入时的观察点是文档没写的三件事：增量与
`output_item.done` 快照的先后、`arguments.done` 是否总带完整值、`reasoning.effort` 取值域。
跑通后录一份原始事件流冻成 fixture，再决定把 openai/minimax/moonshot 一起翻过来还是留逐家开关。

---

## 七、迭代排期建议

> ⚠️ 原 16 周排期写于 2026-08，且第 13–16 周的"新功能开发"已整体取消。按现状重排如下
> （不含已取消的新功能；P0 项见 §0.4）：

| 阶段 | 时长 | 内容 |
|---|---|---|
| **P0 止血** | 1 周 | D1/D2 导出面收口、D3 登录、D11 迁移、D12 CI 基线 |
| **协议层** | 2 周 | `OnlineInferenceService` 拆 4 个 codec（D8），补 fixture 测试 |
| **质量** | 3 周 | 重复代码、错误处理（先改会丢数据的路径）、剩余文件拆分、模型配置提取 |
| **架构** | 6 周 | 测试覆盖、Kotlin / Compose 二选一、模块化 |

---

## 八、总结

### 当下建议的迭代顺序（Top 5）

| # | 行动 | 原因 |
|:---:|------|------|
| 1 | **收口导出组件 + JS 桥加来源校验**（D1/D2） | 公开仓库 + 现成攻击面，任意应用可拉起 WebView 读文件/题库 |
| 2 | **删掉登录，或改成真凭据**（D3） | 明文口令、默认 `admin/123456`、备份含数据库 |
| 3 | **迁移可测 + 去掉静默清库**（D11） | 用户错题/成绩不可再生，一次版本错配就没了 |
| 4 | **CI + 定测试基线**（D12） | 没有回归信号，后面所有拆分都无法验收 |
| 5 | **拆 `OnlineInferenceService` 的 4 个 codec**（D8） | 每接一家服务商都改同一个 4,606 行文件，成本最高 |

> 第 6 位才是"拆 `AIChatActivity`"（D6）——它最大，但不是最先该动的；先有回归信号和协议层边界，再动 UI。

### 长远愿景

将答题宝从一个「功能丰富的题库 App」升级为：
**「以 AI 驱动的智能学习平台」** — 让每位用户都有专属的 AI 学习助手。

---

## 相关文档

- [设计走查：边界问题清单（2026-10-19）](docs/design-review-2026-10-19.md) — 安全/模块/并发/数据四类边界问题，D1–D15，含优先级与验收
- [变更日志](CHANGELOG.md)
- UI 改版：[方案](docs/ui/ai-chat-redesign.md) · [组件系统审查](docs/ui/component-system-review.md) · [工具结果审查](docs/ui/tool-result-ui-review.md) · [交接](docs/ui/HANDOVER.md)

> 旧版这里链的 `docs/system/system_architecture.md`、`docs/development/02-ai-agent-architecture.md`、
> `DEVELOPMENT_GUIDE.md`、`SETUP_GUIDE.md` 在本仓库**均不存在**（只在 `_archive/` 里有旧副本）。
> 已移除死链；需要的话应重新补写，而不是继续引用归档路径。
