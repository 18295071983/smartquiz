# 答题宝 (SmartQuiz) 开发路线图

> 版本: 2.1 | 更新日期: 2026-05-26 | 基于代码库全面分析

---

## 已完成更新 (v2.0.0 - v2.0.1)

### ✅ Phase 0 - 已完成的紧急优化

| 任务 | 状态 | 详情 |
|------|------|------|
| **API 密钥安全迁移** | ✅ 已完成 | 移除硬编码API Key，使用APIKeyManager统一管理 |
| **模型加载性能优化** | ✅ 已完成 | 全局初始化一次性执行，加载时间从30秒降至5秒（提升83%） |
| **GPU 加速配置优化** | ✅ 已完成 | GPU层数自动计算，推理速度从0.3 t/s提升至12 t/s（提升40x） |
| **热启动保持** | ✅ 已完成 | 模型在内存中保持加载状态，内存紧张时不再释放 |
| **中文编码修复** | ✅ 已完成 | v2.0.1修复JNI字符串传递中文乱码问题 |
| **MCP 功能实现** | ✅ 已完成 | 支持Termux环境运行MCP服务器（filesystem、SQLite、Git） |

**详细变更请参阅** [CHANGELOG.md](CHANGELOG.md)

---

## 一、路线图总览

```
Phase 1 (紧急修复)          Phase 2 (质量提升)           Phase 3 (架构演进)
2-4 周                      4-8 周                       8-16 周
    ↓                           ↓                           ↓
┌──────────────┐    ┌──────────────────────┐    ┌──────────────────────┐
│ 空 catch 修复  │    │ 超大文件拆分          │    │ Kotlin 迁移启动       │
│ 异常处理规范化 │    │ 重复代码消除          │    │ Compose 全面化        │
│ Logger 统一   │    │ 测试覆盖率提升        │    │ 模块化拆分            │
└──────────────┘    └──────────────────────┘    └──────────────────────┘
```

---

## 二、Phase 1 — 紧急修复 (优先级 P0)

> **目标**: 1-2 周内完成，消除代码隐患

### 2.1 修复空 catch 块 🔴

**问题**: 约 **30+ 处** 空 `catch (Exception e) {}`

| 文件 | 空 catch 数量 | 优先级 |
|------|:------------:|:------:|
| `AIChatActivity.java` | 10 | 🔴 高 |
| `AILogger.java` | 7 | 🔴 高 |
| `AILogger2.java` | 4 | 🟡 中 |
| `LlamaHelper.java` | 2 | 🟡 中 |
| `FileContentExtractor.java` | 1 | 🟢 低 |

**修复方案**:
```java
// 改造前
catch (Exception e) {}

// 改造后
catch (Exception e) { 
    AILogger.w(TAG, "操作失败", e); 
}
```

### 2.2 规范泛化异常捕获 🔴

**问题**: **200+ 处** `catch (Exception e)` 需要具体化

| 文件 | 泛化捕获数量 | 优先级 |
|------|:-----------:|:------:|
| `WebViewActivity.java` | 46 | 🔴 高 |
| `AIService.java` | 44 | 🔴 高 |
| `AIChatActivity.java` | 32 | 🔴 高 |
| `DatabaseManager.java` | 32 | 🟡 中 |
| `AppDatabase.java` | 24 | 🟡 中 |

**按模块分批替换为具体异常类**:
- IOException / SQLException / JSONException 等

### 2.3 合并 Logger 三剑客 🔴

**问题**: 三个 Logger 类功能重复

| 当前 | 操作 |
|------|------|
| `AILogger.java` | ✅ 保留为唯一入口 |
| `AILogger2.java` | 🔴 **删除**，合并到 AILogger |
| `AppLogger.java` | 🟡 迁移为 AILogger 的包装 |

### 2.4 消除类名混淆 🔴

| 当前 | 目标 |
|------|------|
| `AIToolsManager.java` | 重命名为 `AIToolRegistry.java` |
| `AIToolManager.java` | 保持现状 |

---

## 三、Phase 2 — 质量提升 (优先级 P1)

> **目标**: 1-2 个月内完成，系统性改善代码质量

### 3.1 超大文件拆分 🟡

**需拆分的 10 个超大文件**:

| 优先级 | 文件 | 行数 | 拆分方案 |
|:---:|------|:---:|------|
| 🔴 | `AIChatActivity.java` | 3,592 | 拆为 `ChatInputHandler` + `ChatOutputRenderer` + `ChatSessionCoordinator` |
| 🔴 | `WebViewActivity.java` | 3,440 | 拆为 `WebViewLifecycleDelegate` + `WebViewConfigManager` + `WebViewNavigationHandler` |
| 🔴 | `AIService.java` | 3,271 | 拆为 `ChatPipeline` + `CompletionPipeline` + `StreamingPipeline` |
| 🔴 | `QuizActivity.java` | 2,628 | 按答题模式拆分: `ChallengeQuizActivity` / `ExamQuizActivity` / `PracticeQuizActivity` |
| 🔴 | `UnifiedAgentEngine.java` | 2,579 | 抽取 `AgentPipeline` + `AgentStateMachine` |
| 🟠 | `AppToolkitAITool.java` | 1,879 | 按工具类型拆分 |
| 🟠 | `ChatAdapter.java` | 1,808 | 抽取 `MessageViewHolder` + `MessageRenderer` |
| 🟠 | `AIWeatherManager.java` | 1,622 | 按天气API源拆分 + 公共抽象层 |
| 🟠 | `ChatMessage.java` | 1,518 | 抽取消息类型子类 |
| 🟠 | `AgentService.java` | 1,364 | 拆为核心服务 + 生命周期管理器 |

**拆分原则**:
- 每个文件不超过 **500 行**
- 每个方法不超过 **50 行**
- 使用组合模式代替继承

### 3.2 消除重复代码 🟡

**模型配置去重**:

当前三处重复的模型元数据:
- `MultiModelManager.java:L97-L112`
- `ModelSelectionActivity.java:L60-L93`
- `ModelComparisonActivity.java:L57-L90`

→ 统一提取到 `ModelPresetConfig.java` 或 `models.json` 配置文件

### 3.3 补充单元测试 �

**当前状态**: 16 个测试文件 vs 460+ 源码文件，覆盖率 **< 5%**

| 阶段 | 目标模块 | 新增测试数 |
|------|---------|:---------:|
| 第1周 | `ai/agent/` | 9 个 |
| 第2周 | `ai/service/` + `ai/model/` | 8 个 |
| 第3周 | `database/` DAO 层 | 11 个 |
| 第4周 | `repository/` | 8 个 |
| 第5周 | `ai/tool/` + `ai/skill/` | 7 个 |
| 第6周 | `manager/` | 8 个 |

**目标**: 核心模块测试覆盖率达到 **60%**

---

## 四、Phase 3 — 架构演进 (优先级 P2)

> **目标**: 2-4 个月内完成，提升系统可维护性

### 4.1 Kotlin 迁移 🟠

**当前**: < 1% Kotlin（仅3个 Compose 示例文件）

**迁移策略 — 「新代码 Kotlin 优先」**:

| 阶段 | 内容 | 预计时间 |
|------|------|:---:|
| 第1步 | 所有新类强制 Kotlin | 立即开始 |
| 第2步 | Model 类 → Kotlin data class | 1 周 |
| 第3步 | ViewModel 类 → Kotlin | 2 周 |
| 第4步 | Repository 类 → Kotlin | 2 周 |
| 第5步 | Manager 类 → Kotlin | 3 周 |
| 第6步 | Activity（分批）→ Kotlin | 逐步进行 |

### 4.2 Compose 全面化 🟠

**当前**: Compose 仅用于3个示例页面，主力仍是 XML Layout

**迁移路线**:
1. **新页面**: 全部用 Compose 编写
2. **简单页面优先迁移**: Settings, About, Category Selection
3. **中等页面**: Question Detail, Note Editor, Study Plan
4. **复杂页面最后**: AIChatActivity, QuizActivity

### 4.3 架构提升 🟠

| 项 | 现状 | 改进方向 |
|------|------|---------|
| **状态管理** | LiveData + 直接回调 | 统一到 StateFlow / SharedFlow |
| **导航** | 隐式 Intent + 手动管理 | 引入 Navigation Component |
| **模块化** | 单模块 (app) | 按功能拆分 `:core`, `:feature-quiz`, `:feature-ai` |
| **依赖注入** | Hilt 已集成但未全面使用 | 扩大 Hilt 覆盖范围 |

---

## 五、新功能建议

### 5.1 高优先级（可直接提升用户体验）

| 功能 | 说明 | 难度 | 收益 |
|------|------|:---:|:---:|
| **AI 对话搜索** | 历史消息全文搜索（已有 TODO: ChatHistoryAdapter:L293） | ⭐⭐ | 高 |
| **AI 批改简答题** | 利用 LLM 自动评判主观题 | ⭐⭐ | 极高 |
| **错题智能复习** | 基于艾宾浩斯遗忘曲线自动推送复习提醒 | ⭐⭐⭐ | 极高 |
| **题目分享** | 将题目+解析生成卡片分享到微信/QQ | ⭐⭐ | 高 |
| **语音答题** | 语音输入题目答案，解放双手 | ⭐⭐⭐ | 高 |
| **学习数据分析** | 可视化学习趋势、知识点图谱、薄弱项分析 | ⭐⭐⭐⭐ | 高 |

### 5.2 中等优先级（差异化竞争力）

| 功能 | 说明 | 难度 | 收益 |
|------|------|:---:|:---:|
| **AI 题目解释** | 每道题附带 AI 生成的详细解析 | ⭐⭐ | 极高 |
| **PDF 题库识别** | 拍照/扫描 PDF → OCR → 自动生成题目 | ⭐⭐⭐⭐ | 高 |
| **多人对战模式** | 局域网或在线实时 PK 答题 | ⭐⭐⭐⭐ | 高 |
| **题库市场** | 用户上传/下载共享题库 | ⭐⭐⭐⭐⭐ | 极高 |
| **Anki 集成** | 导入 Anki 牌组，或导出为 Anki 格式 | ⭐⭐⭐ | 中 |
| **学习小组** | 多人共享题库和学习计划 | ⭐⭐⭐⭐⭐ | 中 |

### 5.3 低优先级（长期愿景）

| 功能 | 说明 |
|------|------|
| **Java 版 MCP 客户端** | 实现原生 Java MCP 客户端，直接通过 Socket/stdio 与服务器通信，消除对外部命令（Node.js/npx）的依赖 |
| **iOS 版本** | 使用 Kotlin Multiplatform 或 Flutter 跨平台 |
| **Web 管理后台** | 通过浏览器管理题库、分析数据 |
| **插件系统** | 开放的题目导入插件接口 |
| **知识图谱** | 知识点关联网络，发现知识点间的联系 |
| **自适应出题** | AI 根据用户水平动态调整题目难度 |

---

## 六、性能优化路线

### 6.1 启动优化

| 优化项 | 方法 | 预期收益 |
|------|------|:---:|
| Application.onCreate 延迟加载 | 将非必要初始化移到 IdleHandler | -30% 启动时间 |
| 布局优化 | 减少嵌套层级，使用 ViewStub/Compose 懒加载 | -15% 布局时间 |
| Splash 页优化 | 合并 Splash 和 MainActivity 初始化 | -200ms |

### 6.2 内存优化

| 优化项 | 方法 |
|------|------|
| 图片缓存 | Glide 内存上限调优，大规模列表用 Coil |
| WebView 回收 | 后台 Activity 的 WebView 主动释放 |
| AI 模型内存 | 推理完成后释放中间张量，降低 peak memory |

### 6.3 构建优化

```properties
# gradle.properties 推荐配置
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g
android.enableR8.fullMode=true
```

---

## 七、DevOps 与 CI/CD

### 7.1 推荐的 GitHub Actions 流水线

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

### 7.2 代码质量门禁

| 指标 | 当前 | 门禁值 |
|------|:---:|:---:|
| 测试覆盖率 | < 5% | ≥ 60% (核心模块) |
| 空 catch 块 | 30+ | 0 |
| 文件最大行数 | 3,592 | ≤ 500 |
| 方法最大行数 | 200+ | ≤ 50 |
| 重复代码率 | 中 | < 3% |

### 7.3 发布流程标准化

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

## 八、风险与依赖

| 风险 | 影响 | 缓解措施 |
|------|:---:|------|
| llama.cpp 上游 API 变更 | C++ JNI 接口失效 | 固定 llama.cpp 版本，定期测试兼容性 |
| TBS SDK 授权问题 | 文件预览功能不可用 | 准备 Pdfium 作为降级方案 |
| 第三方 API 服务中断 | 天气、翻译等功能不可用 | 本地缓存 + 错误降级 |
| Android API 行为变更 | 编译/运行兼容性 | 每季度适配新 API 版本 |
| 签名密钥丢失 | 无法发布更新 | keystore 必须备份到安全位置 |

---

## 九、迭代排期建议

```
│  Week 1-2          │  Week 3-6          │  Week 7-12         │  Week 13-16        │
│  Phase 1 紧急修复   │  Phase 2 质量提升   │  Phase 3 架构演进   │  新功能开发         │
│                    │                    │                    │                    │
│  🔴 空catch修复     │  🟡 文件拆分(50%)   │  🟠 测试覆盖(ai/)   │  ⭐ AI对话搜索       │
│  🔴 异常处理规范化   │  🟡 重复代码消除    │  🟠 Kotlin 迁移启动 │  ⭐ AI批改简答题     │
│  🔴 Logger 统一    │  🟡 测试覆盖提升    │  🟠 Compose 全面化   │  ⭐ 错题智能复习     │
│  🔴 类名去重       │  🟡 代码标准化      │  🟠 模块化拆分      │  ⭐ 题目分享         │
│                    │  🟡 文件拆分(剩余)   │                     │                    │
│                    │  🟡 模型配置提取    │                     │                    │
└────────────────────┴────────────────────┴────────────────────┴────────────────────┘
```

---

## 十、总结

### 当下建议的迭代顺序（Top 5）

| # | 行动 | 原因 |
|:---:|------|------|
| 1 | **修复空 catch 块** | 线上 bug 无法被发现和定位 |
| 2 | **规范异常处理** | 200+ 处泛化捕获隐藏潜在问题 |
| 3 | **拆分超大文件** | 3592 行的 Activity 是维护灾难 |
| 4 | **合并 Logger** | 消除重复代码，统一日志入口 |
| 5 | **补充核心模块测试** | 最复杂的 AI 模块完全没有测试 |

### 长远愿景

将答题宝从一个「功能丰富的题库 App」升级为：
**「以 AI 驱动的智能学习平台」** — 让每位用户都有专属的 AI 学习助手。

---

## 相关文档

- [系统架构设计](docs/system/system_architecture.md)
- [Agent 架构设计](docs/AGENT_ARCHITECTURE.md)
- [开发指南](DEVELOPMENT_GUIDE.md)
- [环境搭建指南](SETUP_GUIDE.md)
- [开发标准](docs/development/development_standards.md)
- [变更日志](CHANGELOG.md)
