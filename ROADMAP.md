# 答题宝 (SmartQuiz) 开发路线图与迭代建议

> 版本: 2.0 | 更新日期: 2026-05-16 | 基于代码库全面分析

## 一、路线图总览

```
Phase 1 (紧急修复)          Phase 2 (质量提升)           Phase 3 (架构演进)
2-4 周                      4-8 周                       8-16 周
    ↓                           ↓                           ↓
┌──────────────┐    ┌──────────────────────┐    ┌──────────────────────┐
│ 安全隐患清理   │    │ 测试覆盖率提升        │    │ 超大文件拆分          │
│ 弃用代码删除   │    │ 错误处理规范化        │    │ Kotlin 迁移启动       │
│ API 密钥迁移   │    │ 重复代码消除          │    │ 新功能开发            │
│ 空 catch 修复  │    │ 代码标准化            │    │ Compose 全面化        │
└──────────────┘    └──────────────────────┘    └──────────────────────┘
```

---

## 二、Phase 1 — 紧急修复 (优先级 P0)

> **目标**: 一周内完成，消除安全风险和明显缺陷

### 2.1 移除硬编码 API 密钥 🔴

**问题**: [AIWeatherManager.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/tool/AIWeatherManager.java) 中 3 处明文密钥

| 位置 | 密钥类型 | 操作 |
|------|---------|------|
| L43 | 和风天气 API Key | 迁移到 `APIKeyManager` 加密存储 |
| L144 | OpenWeatherMap Key | 迁移到 `APIKeyManager` 加密存储 |
| L224 | OpenWeatherMap Key（重复） | 合并取单一配置 |

**实现方案**:
```java
// 改造前
private static final String API_KEY = "be2af1f8490344feb8a7125ab46608dd";

// 改造后
private String getApiKey() {
    return APIKeyManager.getInstance().getKey("weather_hefeng");
}
```

### 2.2 修复空 catch 块 🔴

**问题**: [DeepThinkingEngine.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/chat/DeepThinkingEngine.java) 中 **9 处** 空 `catch (Exception e) {}`

| 行号 | 当前 | 建议 |
|------|------|------|
| L54, L85, L126, L166, L196, L222, L281, L326, L361 | `catch (Exception e) {}` | `catch (Exception e) { Log.w(TAG, "操作失败", e); }` |

**额外修复**:
- [AILogger2.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/util/AILogger2.java) 4 处: L369, L388, L408, L425
- [AIChatActivity.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ui/activity/AIChatActivity.java) L1440-L1441

### 2.3 删除弃用代码 🔴

**问题**: `ai/config/` 下 3 个全类标记 `@Deprecated`，占据了约 30 处弃用标注

| 文件 | 弃用标注数 | 操作 |
|------|:---:|------|
| [GpuCompatibilityAdapter.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/config/GpuCompatibilityAdapter.java) | 7 | **删除文件** |
| [GpuAutoAdapter.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/config/GpuAutoAdapter.java) | 8 | **删除文件** |
| [DeviceCapabilityDetector.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/config/DeviceCapabilityDetector.java) | 8 | **删除文件** |
| [DatabaseFieldManager.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/database/DatabaseFieldManager.java) L325, L346 | 2 | 标记为 `@Deprecated(forRemoval = true)` |

---

## 三、Phase 2 — 质量提升 (优先级 P1)

> **目标**: 1-2 个月内完成，系统性改善代码质量

### 3.1 超大文件拆分 🟡

**需拆分的 14 个文件**（按行数排序）:

| 优先级 | 文件 | 行数 | 拆分方案 |
|:---:|------|:---:|------|
| **最高** | [WebViewActivity.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/WebViewActivity.java) | 3,559 | 拆为 `WebViewLifecycleDelegate` + `WebViewConfigManager` + `WebViewNavigationHandler` |
| **最高** | [QuizActivity.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ui/activity/QuizActivity.java) | 2,628 | 按答题模式拆: `ChallengeQuizActivity` / `ExamQuizActivity` / `PracticeQuizActivity` |
| **最高** | [AIService.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/service/AIService.java) | 2,113 | 拆为 `ChatPipeline` / `CompletionPipeline` / `StreamingPipeline` / `ServiceLifecycleDelegate` |
| **高** | [AIChatActivity.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ui/activity/AIChatActivity.java) | 1,449 | 抽取 `ChatInputHandler` / `ChatOutputRenderer` / `ChatSessionCoordinator` |
| **高** | [AIWeatherManager.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/tool/AIWeatherManager.java) | 1,244 | 按天气 API 源拆分 + 公共抽象层 |
| **中** | [UnifiedAgentEngine.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/agent/UnifiedAgentEngine.java) | 842 | 抽取 `AgentPipeline` / `AgentStateMachine` |
| **中** | [AgentService.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/service/AgentService.java) | 807 | 拆为核心服务 + 生命周期管理器 |
| **中** | [SmartIntentRecognizer.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/agent/SmartIntentRecognizer.java) | 711 | 按意图分类拆为多个 Strategy |
| **低** | 其余 6 个文件 | 500-700 | 各自抽取内聚的子功能模块 |

**拆分原则**:
- 每个文件不超过 **400 行**
- 每个方法不超过 **50 行**
- 使用组合模式代替继承

### 3.2 消除重复代码 🟡

**Logger 三剑客合并**:

| 当前 | 替代方案 |
|------|---------|
| [AILogger.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/util/AILogger.java) | 保留唯一入口 |
| [AILogger2.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/util/AILogger2.java) | **删除**，合并到 AILogger |
| [AppLogger.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/infra/AppLogger.java) | 迁移为 AILogger 的包装 |

**设备检测器合并**:
- [DeviceDetector.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/optimization/DeviceDetector.java) ← 保留
- [DeviceCapabilityDetector.java](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/config/DeviceCapabilityDetector.java) ← **删除**（已弃用）

**近乎重复的类名去重**:
- `AIToolsManager.java` vs `AIToolManager.java` → 重命名`AIToolsManager` 为 `AIToolRegistry`

**模型参数去重** — 三处相同数据:

当前这三处完全重复的模型元数据:
- [MultiModelManager.java:L97-L112](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/model/MultiModelManager.java#L97-L112)
- [ModelSelectionActivity.java:L60-L93](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/model/ModelSelectionActivity.java)
- [ModelComparisonActivity.java:L57-L90](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ai/model/ModelComparisonActivity.java)

→ 统一提取到 `ModelPresetConfig` 或 `models.json` 配置文件。

### 3.3 错误处理规范化 🟡

**Phase 2 目标**: 将 `catch (Exception e)` 替换率提升到 60%+

| 当前 | 目标 | 方法 |
|------|------|------|
| 60+ 处泛化 `catch (Exception)` | ≤ 20 处 | 按模块分批替换为具体异常类 |
| 20 处空/ignore catch | 0 处 | 至少添加日志记录 |
| 无统一异常体系 | 建立 [Result] 模式 | 引入 `sealed class AiResult<T> { Success / Error }` |

**按模块优先级替换**:
1. `ai/service/` — AgentService (10处)、AIService
2. `WebViewActivity` — 21 处
3. `AIChatActivity` — 16 处

### 3.4 代码标准化 🟡

| 项 | 现状 | 目标 |
|------|------|------|
| 文件行数 | 14 个文件 > 500 行 | 所有文件 ≤ 400 行 |
| 方法行数 | 部分超过 100 行 | ≤ 50 行 |
| 注释规范 | 代码中有大量无注释区域 | 所有公共 API 有 JavaDoc |
| 命名一致性 | `AIToolsManager` / `AIToolManager` 混淆 | 统一命名规范 |

---

## 四、Phase 3 — 架构演进 (优先级 P2)

> **目标**: 2-4 个月内完成，提升系统可维护性和可扩展性

### 4.1 补充测试 🟠

**当前覆盖率**: 极低。21 个测试文件 vs 300+ 源码文件。

**分阶段目标**:

| 阶段 | 目标模块 | 新增测试 | 理由 |
|------|---------|:---:|------|
| 第1周 | `ai/agent/` | 9 个 | 核心 Agent 引擎必须有测试保障 |
| 第2周 | `ai/gpu/` + `ai/model/` | 8 个 | GPU 子系统是性能关键 |
| 第3周 | `database/` DAO 层 | 11 个 | 数据库正确性是数据基础 |
| 第4周 | `repository/` | 8 个 | 数据仓库层的业务逻辑 |
| 第5周 | `ai/feature/` + `ai/skill/` | 7 个 | AI 功能模块 |
| 第6周 | `manager/` (Backup/Theme/Language) | 8 个 | 业务管理器 |

**测试策略**:
```
DAO 测试      → Room In-Memory Database (快速, 隔离)
Repository    → Mock DAO + LiveData 测试
ViewModel     → Mock Repository + Coroutine 测试
AI 引擎       → Mock JNI 层 + 异步测试
GPU 子系统    → Robolectric + 设备模拟
```

### 4.2 Kotlin 迁移 🟠

**当前**: `< 1% Kotlin`（3个 Compose 示例文件，无生产代码）

**迁移策略 — 「新代码 Kotlin 优先」**:

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

### 4.3 Compose 全面化 🟠

**当前**: Compose 仅用于 3 个示例页面，主力仍是 XML Layout

**迁移路线**:
1. **新页面**: 全部用 Compose 编写
2. **简单页面优先迁移**: Settings, About, Category Selection
3. **中等页面**: Question Detail, Note Editor, Study Plan
4. **复杂页面最后**: AIChatActivity, QuizActivity, ImportActivity

**Compose vs XML 决策矩阵**:
- 新功能 → 一律 Compose
- 修改现有页面 → 评估改造工作量，优先 Compose
- 不改动的页面 → 保持 XML，不强制迁移

### 4.4 架构提升 🟠

| 项 | 现状 | 改进方向 |
|------|------|---------|
| **状态管理** | LiveData + 直接回调 | 统一到 StateFlow / SharedFlow |
| **导航** | 隐式 Intent + 手动管理 | 引入 Navigation Component (Compose + XML) |
| **模块化** | 单模块 (app) | 按功能拆分为 `:core`, `:feature-quiz`, `:feature-ai`, `:feature-export` |
| **构建优化** | 全量构建 | 增量编译 + Gradle Build Cache + 并行构建 |
| **依赖注入** | Hilt 已集成但未全面使用 | 扩大 Hilt 覆盖范围，减少 `new` 和手动单例 |

---

## 五、新功能建议

### 5.1 高优先级（可直接提升用户体验）

| 功能 | 说明 | 难度 | 收益 |
|------|------|:---:|:---:|
| **AI 对话搜索** | 唯一标记的 TODO: [ChatHistoryAdapter:L293](file:///d:/quzp/app/src/main/java/com/oilquiz/app/ui/adapter/ChatHistoryAdapter.java#L293)，实现历史消息全文搜索 | ⭐⭐ | 高 |
| **题目分享** | 将题目+解析生成卡片分享到微信/QQ | ⭐⭐ | 高 |
| **错题智能复习** | 基于艾宾浩斯遗忘曲线自动推送复习提醒 | ⭐⭐⭐ | 极高 |
| **语音答题** | 语音输入题目答案，解放双手 | ⭐⭐⭐ | 高 |
| **AI 批改简答题** | 利用 LLM 自动评判主观题 | ⭐⭐ | 极高 |
| **学习数据分析** | 可视化学习趋势、知识点图谱、薄弱项分析 | ⭐⭐⭐⭐ | 高 |

### 5.2 中等优先级（差异化竞争力）

| 功能 | 说明 | 难度 | 收益 |
|------|------|:---:|:---:|
| **多人对战模式** | 局域网或在线实时 PK 答题 | ⭐⭐⭐⭐ | 高 |
| **题库市场** | 用户上传/下载共享题库 | ⭐⭐⭐⭐⭐ | 极高 |
| **Anki 集成** | 导入 Anki 牌组，或导出为 Anki 格式 | ⭐⭐⭐ | 中 |
| **PDF 题库识别** | 拍照/扫描 PDF → OCR → 自动生成题目 | ⭐⭐⭐⭐ | 高 |
| **学习小组** | 多人共享题库和学习计划 | ⭐⭐⭐⭐⭐ | 中 |
| **AI 题目解释** | 每道题附带 AI 生成的详细解析 | ⭐⭐ | 极高 |

### 5.3 低优先级（长期愿景）

| 功能 | 说明 |
|------|------|
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
| 空 catch 块 | 20+ | 0 |
| 文件最大行数 | 3,559 | ≤ 400 |
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
│  🔴 API 密钥迁移    │  🟡 文件拆分(50%)   │  🟠 测试覆盖(ai/)   │  ⭐ 错题智能复习     │
│  🔴 空catch修复     │  🟡 重复代码消除    │  🟠 Kotlin 迁移启动 │  ⭐ AI 批改简答题    │
│  🔴 弃用代码删除    │  🟡 错误处理规范化   │  🟠 Compose 全面化   │  ⭐ 题目分享         │
│  🔴 Logger 统一    │  🟡 代码标准化      │  🟠 模块化拆分      │  ⭐ 学习数据分析     │
│                    │  🟡 文件拆分(剩余)   │                     │                    │
│                    │  🟡 模型配置提取    │                     │                    │
└────────────────────┴────────────────────┴────────────────────┴────────────────────┘
```

---

## 十、总结

### 当下建议的迭代顺序（Top 5）

| # | 行动 | 原因 |
|:---:|------|------|
| 1 | **移除硬编码 API 密钥** | 代码已公开在 GitHub，密钥泄露风险 |
| 2 | **修复空 catch 块** | 线上 bug 无法被发现和定位 |
| 3 | **拆分超大文件** | 3559 行的 Activity 是维护灾难 |
| 4 | **补充 Agent/Gpu 测试** | 最复杂的模块完全没有测试 |
| 5 | **启动 Kotlin 迁移** | 长期降低空指针/样板代码成本 |

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