# 答题宝 (SmartQuiz) — AI 编码助手规则

> **版本: 2.0 | 更新日期: 2026-05-16**

---

## 一、概述

本文档定义了 AI 编码助手（如 GitHub Copilot、Cursor、Claude、GPT 系列等）在参与「答题宝」项目开发时必须遵循的全部规则。AI 助手在生成、修改或审查代码时，必须将本文档作为最高优先级的上下文规则。

---

## 二、项目文档清单

AI 助手在生成代码之前必须了解以下文档的存在和用途：

| 文档路径 | 用途 | 关键内容 |
|------|------|------|
| `docs/development/development_standards.md` | 开发规范 | 架构、命名、状态管理、线程、DI、测试 |
| `docs/ai_rules.md` | AI 编码规则（本文档） | 包使用规则、编码约束、模块特定规则 |
| `docs/system/deployment_guide.md` | 部署指南 | 构建命令、签名配置、环境依赖 |
| `build.gradle.kts` (app) | 应用构建脚本 | 依赖版本、SDK 版本、插件配置 |
| `build.gradle.kts` (project) | 项目构建脚本 | Gradle 版本、仓库配置 |

**规则**：AI 助手在不确定某个配置时，应先查阅对应文档，而非凭空假设。

---

## 三、架构约束（强制执行）

### 3.1 MVVM 架构必须严格遵循

以下规则 **绝对不可违反**：

| 规则 | 说明 | 违规示例 |
|------|------|------|
| **Activity 不得直接访问 Database** | 必须通过 ViewModel → Repository → DAO | `questionDao.getByCategory()` 写在 Activity 中 |
| **ViewModel 不得持有 Context 引用** | 使用 `AndroidViewModel` 仅限 `Application` 上下文 | `viewModel.context` |
| **Repository 是数据访问的唯一入口** | 所有数据操作必须经过 Repository | `Room.databaseBuilder()` 在 Fragment 中 |
| **View 层不包含业务逻辑** | Activity/Fragment/Compose 只做 UI 渲染 | 在 Activity 中计算答题得分逻辑 |

### 3.2 依赖注入规则

| 规则 | 说明 |
|------|------|
| **所有依赖通过 Hilt 注入** | 禁止手动创建依赖对象（除非工厂模式） |
| **Module 必须标注 @InstallIn** | 指定模块安装的 Component |
| **接口必须有 @Binds 绑定** | 抽象类/接口需要通过 Module 绑定实现类 |
| **禁止在 Application.onCreate 中手动初始化** | 使用 Hilt 的 `@Singleton @Provides` 替代 |

---

## 四、包目录使用规则

AI 助手生成的代码必须放入正确的包路径。以下是完整的包用途说明：

### 4.1 核心模块包

| 包路径 | 允许的内容 | 禁止的内容 |
|------|------|------|
| `ai/agent/` | AI Agent 引擎（如 `AIAgentEngine`、`QuizAgent`） | AI 工具、模型加载代码 |
| `ai/tool/` | AI 工具类（继承 `BaseAITool` 的类） | Agent 调度逻辑、UI 代码 |
| `ai/gpu/` | GPU 加速相关代码（OpenCL、Vulkan 封装） | 模型下载、模型推理逻辑 |
| `ai/model/` | 模型管理（下载、加载、卸载、版本控制） | GPU 初始化、工具调度 |
| `database/entity/` | Room `@Entity` 数据类 | DAO 接口、业务逻辑 |
| `database/dao/` | Room `@Dao` 接口 | 数据库创建逻辑、迁移代码 |
| `database/migration/` | 数据库迁移（`Migration` 对象） | DAO 接口、实体定义 |
| `di/` | Hilt `@Module` 和 `@Provides` / `@Binds` | 业务逻辑、工具类 |
| `infra/network/` | 网络请求（Retrofit、OkHttp） | 数据解析、UI 线程调用 |
| `infra/logging/` | 日志封装（Logger 类） | 业务日志内容判断 |
| `infra/crash/` | 崩溃上报封装 | UI 弹窗、数据统计 |
| `manager/` | 业务逻辑管理器 | 数据持久化、直接网络请求 |
| `model/` | 纯数据模型（非 Room Entity） | 数据库操作、网络请求 |
| `repository/` | 数据访问抽象层 | UI 逻辑、ViewModel 依赖 |
| `resource/` | 资源管理（文件、图片、音频） | UI 渲染、网络请求 |
| `toolkit/` | 通用工具集（加密、压缩、格式转换） | 业务逻辑、特定功能 |
| `ui/activity/` | Activity 类 | ViewModel、Repository、直接数据操作 |
| `ui/fragment/` | Fragment 类 | 同 Activity 限制 |
| `ui/compose/` | `@Composable` 函数 | ViewModel 定义、Repository 调用 |
| `ui/widget/` | 自定义 View/控件 | Activity/Fragment 引用 |
| `ui/adapter/` | RecyclerView Adapter | 点击事件中的业务逻辑 |
| `util/` | **纯静态工具方法** | 有状态的工具类、UI 相关方法 |
| `viewmodel/` | ViewModel 类 | Context、View 引用、Repository 以外的数据源 |
| `weather/` | 天气模块全部代码 | 非天气相关的通用逻辑 |
| `webview/` | WebView 封装和 JS Bridge | 业务逻辑（应委托给 ViewModel） |

### 4.2 包选择决策流程

当 AI 助手不确定代码应放在哪个包时，按以下流程判断：

1. 是否是 UI？ → `ui/` 子目录
2. 是否管理 UI 状态？ → `viewmodel/`
3. 是否是数据访问？ → `repository/`
4. 是否是数据持久化？ → `database/`
5. 是否是依赖注入配置？ → `di/`
6. 是否是纯工具函数？ → `util/`
7. 是否是核心业务逻辑？ → `manager/`
8. 是否是 AI 相关？ → `ai/` 子目录
9. 是否是基础设施？ → `infra/` 子目录

---

## 五、编码规则（强制执行）

### 5.1 语言与注释规范

| 场景 | 语言要求 |
|------|------|
| **用户界面的字符串** | **必须使用中文** |
| **代码注释** | **必须使用英文** |
| **变量名、方法名、类名** | **必须使用英文** |
| **Git 提交信息** | 中文（推荐） |
| **日志输出** | 英文或中英混合（关键信息用英文） |

```java
// Correct: English comments
public class QuizScoringManager {
    // Calculates the total score based on correct answers
    public int calculateScore(List<Answer> answers) {
        int score = 0;
        for (Answer answer : answers) {
            if (answer.isCorrect()) {
                score += answer.getPoints();
            }
        }
        return score;
    }
}
```

```xml
<!-- Correct: Chinese user-facing strings -->
<string name="quiz_result_title">答题结果</string>
<string name="quiz_score_format">得分：%d 分</string>
```

### 5.2 基类继承规则

| 规则 | 说明 |
|------|------|
| Activity 必须继承 `BaseActivity` | 无一例外，包括第三方库 Activity（需封装） |
| Fragment 必须继承 `BaseFragment` | 无一例外 |
| AI 工具必须继承 `BaseAITool` | 新增 AI 能力时强制执行 |
| WebViewClient 必须使用 `SecurityWebViewClient` | 安全基线 |
| ViewModel 必须使用 `@HiltViewModel` | 依赖注入要求 |

### 5.3 异步与线程规则

| 规则 | 说明 |
|------|------|
| **禁止使用 AsyncTask** | 已被完全弃用 |
| **禁止使用 Handler + Thread 手动线程管理** | 统一使用 Coroutines |
| **禁止在主线程执行 IO 操作** | 必须 `withContext(Dispatchers.IO)` |
| **禁止使用 GlobalScope** | 使用 `viewModelScope` 或 `lifecycleScope` |
| **禁止 runBlocking 在主线程** | 会阻塞 UI |

### 5.4 状态管理规则

| 场景 | 必须使用的 API |
|------|------|
| Activity/Fragment 观察数据 | `LiveData` + `observe(viewLifecycleOwner)` |
| Compose 观察数据 | `StateFlow` + `collectAsState()` |
| 一次性事件 | `SingleLiveEvent` 或 `Channel` |
| 跨组件共享状态 | `SharedFlow` 或 `StateFlow`（通过 DI 共享） |

### 5.5 资源使用规则

| 规则 | 说明 |
|------|------|
| 字符串不得硬编码 | 必须定义在 `strings.xml` 中 |
| 颜色不得硬编码 | 定义在 `colors.xml` 或 MaterialTheme 中 |
| 尺寸使用 `dimen.xml` | 或 Material 尺寸系统 |
| 图片使用 WebP 优先 | 透明图可用 PNG |
| 不得复制资源文件 | 复用已有的图片和字符串 |

---

## 六、依赖管理规则

### 6.1 添加新依赖的流程

**AI 助手不得擅自添加新的第三方依赖。** 如需添加，必须：

1. 在代码注释中明确说明为什么需要新依赖
2. 说明是否已检查项目中已有类似功能的库
3. 等待开发者确认后才能添加

### 6.2 当前核心依赖（参考）

| 依赖 | 用途 | 版本约束 |
|------|------|------|
| Hilt | 依赖注入 | 跟随 AGP |
| Room | 数据库 | 2.6.x |
| Retrofit | 网络请求 | 2.9.x |
| OkHttp | HTTP 客户端 | 4.12.x |
| Glide / Coil | 图片加载 | 已集成 |
| Coroutines | 异步处理 | 1.7.x+ |
| Compose BOM | UI 框架 | 2024.x |
| Material 3 | Compose 设计组件 | 跟随 BOM |
| Navigation Compose | 导航 | 2.7.x |
| Paging 3 | 分页加载 | 3.2.x |
| DataStore | 键值存储 | 1.0.x |

---

## 七、构建配置规则

### 7.1 固定配置（不可变）

| 配置项 | 固定值 | 原因 |
|------|------|------|
| `minSdk` | **31** (Android 12) | 目标用户设备基线 |
| `targetSdk` | **34** (Android 14) | Google Play 要求 |
| `compileSdk` | **34** | 与 targetSdk 一致 |
| `sourceCompatibility` | **JavaVersion.VERSION_17** | JDK 17 |
| `targetCompatibility` | **JavaVersion.VERSION_17** | JDK 17 |
| Gradle | **8.13** | 当前项目配置 |
| AGP (Android Gradle Plugin) | **8.4.0** | 与 Gradle 8.13 兼容 |
| JDK | **17** | 与 AGP 8.4.0 兼容 |

### 7.2 构建特性

| 特性 | 状态 | 说明 |
|------|------|------|
| `buildConfig` | **启用** | 需要 `BuildConfig` 类 |
| `compose` | **启用** | Jetpack Compose 支持 |
| `viewBinding` | **启用** | 传统 View 的绑定 |
| `dataBinding` | **禁用** | 使用 ViewBinding 替代 |
| `aidl` | **禁用** | 项目不使用 AIDL |
| `renderscript` | **禁用** | 项目不使用 RenderScript |

---

## 八、AI 模块专项规则

### 8.1 新增 AI 工具的流程

当需要新增一个 AI 工具时，AI 助手必须：

1. 在 `ai/tool/` 目录下创建新的类，继承 `BaseAITool`
2. 实现 `getToolName()`、`getToolDescription()`、`execute()`、`cancel()`、`release()`
3. 在 `AIToolManager` 中注册该工具
4. 如有 GPU 加速代码，仅放在 `ai/gpu/` 目录下
5. 如有模型管理代码，仅放在 `ai/model/` 目录下

```java
// ai/tool/QuestionVerifyTool.java - 正确的工具创建方式
public class QuestionVerifyTool extends BaseAITool {

    @Override
    public String getToolName() {
        return "question_verify";
    }

    @Override
    public String getToolDescription() {
        return "验证用户答案的正确性并给出解析";
    }

    @Override
    public void execute(String input, Map<String, Object> params) {
        // 通过 AIToolManager 注册的回调通知结果
        // 不要直接在这里调用 UI 更新
        onSuccess("验证完成");
    }

    @Override
    public void cancel() {
        // 取消正在执行的 AI 任务
    }

    @Override
    public void release() {
        // 释放 GPU 资源、卸载模型等
    }
}
```

### 8.2 AI 模块目录边界

| 目录 | 可以包含 | 不能包含 |
|------|------|------|
| `ai/agent/` | Agent 引擎、编排逻辑 | 任何 UI 代码 |
| `ai/tool/` | 继承 BaseAITool 的工具类 | Agent 调度逻辑 |
| `ai/gpu/` | OpenCL/Vulkan 封装、Shader | 非 GPU 的计算逻辑 |
| `ai/model/` | 模型下载/加载/缓存/卸载 | GPU 初始化代码 |

### 8.3 AI 工具注册

所有新增 AI 工具必须在 `AIToolManager` 中注册：

```java
public class AIToolManager {
    private final Map<String, BaseAITool> toolRegistry = new HashMap<>();

    public void registerTool(BaseAITool tool) {
        toolRegistry.put(tool.getToolName(), tool);
    }

    public BaseAITool getTool(String toolName) {
        return toolRegistry.get(toolName);
    }
}
```

---

## 九、WebView 模块专项规则

### 9.1 安全红线（不可违反）

| 规则 | 严重程度 | 说明 |
|------|------|------|
| JS 接口必须校验输入 | **阻断性** | 不校验直接拒绝代码合并 |
| 必须使用 `SecurityWebViewClient` | **阻断性** | 替代 `WebViewClient` |
| 禁止 `setAllowFileAccess(true)` | **阻断性** | 默认已关闭 |
| 禁止 `setAllowUniversalAccessFromFileURLs(true)` | **阻断性** | 安全漏洞 |
| 文件访问必须通过 `FileRedirectManager` | **阻断性** | 防止路径遍历 |
| `shouldOverrideUrlLoading` 必须校验 URL | **严重** | 防止任意 URL 跳转 |

### 9.2 JS Bridge 正确示例

```java
public class SecureJsBridge {
    private static final Set<String> ALLOWED_ACTIONS = new HashSet<>(Arrays.asList(
        "share_result",
        "save_answer",
        "play_audio"
    ));

    @JavascriptInterface
    public void postMessage(String action, String payload) {
        // Validate action
        if (!ALLOWED_ACTIONS.contains(action)) {
            Logger.w("SecureJsBridge", "Blocked unauthorized action: " + action);
            return;
        }

        // Validate payload
        if (payload == null || payload.length() > 10240) {
            Logger.w("SecureJsBridge", "Invalid payload for action: " + action);
            return;
        }

        // Dispatch to handler on main thread
        mainHandler.post(() -> handleAction(action, payload));
    }
}
```

---

## 十、测试专项规则

### 10.1 测试编写规则

| 规则 | 说明 |
|------|------|
| **新功能必须编写测试** | 无测试的 PR 不予合并 |
| **测试覆盖率 > 60%** | 按行代码覆盖率计算 |
| **使用 JUnit 4** | 不使用 JUnit 5（与 Robolectric 兼容性） |
| **ViewModel 必须使用 Mockito** | 模拟 Repository |
| **Repository 测试使用 Robolectric** | 模拟 SQLite 环境 |
| **不测试框架代码** | 不测试 Activity 生命周期、RecyclerView 滑动 |

### 10.2 测试命名规范

| 格式 | 示例 |
|------|------|
| `方法名_条件_预期结果` | `loadQuestions_empty_database_returnsEmptyList` |
| 或使用反引号（Kotlin） | `` `loadQuestions should return empty list when database is empty` `` |

### 10.3 必须测试的场景

| 场景 | 是否必须 |
|------|------|
| 正常数据流 | ✅ 必须 |
| 空数据 / 空列表 | ✅ 必须 |
| 网络错误 | ✅ 必须 |
| 数据库错误 | ✅ 必须 |
| 边界值（最大值、最小值） | ✅ 必须 |
| 并发操作 | ⚠️ 推荐 |
| UI 渲染验证 | ⚠️ 推荐 |

---

## 十一、常见违规示例

以下是 AI 助手容易犯的错误及正确做法对比：

### 11.1 违反 MVVM 架构

```java
// ❌ 错误：Activity 直接访问数据库
public class QuizActivity extends BaseActivity {
    @Inject QuestionDao questionDao; // 不应在 Activity 中注入 DAO

    private void loadQuestions() {
        List<Question> list = questionDao.getAll(); // 直接数据库访问
        updateUI(list);
    }
}
```

```java
// ✅ 正确：通过 ViewModel → Repository → DAO
@AndroidEntryPoint
public class QuizActivity extends BaseActivity {
    @Inject QuizViewModel viewModel; // 注入 ViewModel

    private void loadQuestions() {
        viewModel.getQuestions().observe(this, this::updateUI);
        viewModel.loadQuestions();
    }
}
```

### 11.2 字符串硬编码

```kotlin
// ❌ 错误：硬编码中文
@Composable
fun SubmitButton(onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text("提交答案") // 不应硬编码
    }
}
```

```kotlin
// ✅ 正确：使用字符串资源
@Composable
fun SubmitButton(onClick: () -> Unit) {
    Button(onClick = onClick) {
        Text(stringResource(R.string.quiz_submit_button))
    }
}
```

### 11.3 使用 AsyncTask

```java
// ❌ 错误：使用已废弃的 AsyncTask
private class LoadQuestionsTask extends AsyncTask<Void, Void, List<Question>> {
    @Override
    protected List<Question> doInBackground(Void... voids) {
        return apiService.fetchQuestions();
    }
}
```

```kotlin
// ✅ 正确：使用 Coroutines
viewModelScope.launch(Dispatchers.IO) {
    val questions = apiService.fetchQuestions()
    withContext(Dispatchers.Main) {
        _questions.value = questions
    }
}
```

### 11.4 在错误的包中创建文件

```
// ❌ 错误：将 AI 工具放在 ui/ 下
ui/compose/QuestionGenerateTool.kt  // 错误位置

// ✅ 正确：AI 工具必须放在 ai/tool/ 下
ai/tool/QuestionGenerateTool.kt  // 正确位置
```

---

## 十二、AI 助手行为规范

### 12.1 代码生成前的检查清单

AI 助手在生成任何代码前必须确认：

| # | 检查项 |
|---|------|
| 1 | 目标文件放在正确的包目录下 |
| 2 | Activity/Fragment 继承了 BaseActivity/BaseFragment |
| 3 | ViewModel 使用了 `@HiltViewModel` 注解 |
| 4 | 依赖通过 `@Inject` 注入，不是手动 `new` |
| 5 | 没有直接访问 DAO/Database（通过 Repository） |
| 6 | 异步操作用了 Coroutines，不是 AsyncTask |
| 7 | UI 字符串使用了中文资源，不是硬编码 |
| 8 | 代码注释使用了英文 |
| 9 | 没有引入新的第三方依赖 |
| 10 | 新增 AI 工具继承了 BaseAITool |

### 12.2 不确定时的处理

当 AI 助手对某项规则不确定时：
1. 先查阅 `docs/development/development_standards.md`
2. 再查阅本文档
3. 如果仍然不确定，在生成的代码中用注释标注 `// TODO: Verify with team`，并说明不确定的内容
4. 优先选择更严格的规则（如安全相关规则一律从严）

### 12.3 禁止的操作

| 操作 | 原因 |
|------|------|
| 删除或修改构建配置文件 | 可能破坏构建 |
| 修改 ProGuard/R8 规则 | 可能影响混淆和 APK 体积 |
| 降级依赖版本 | 可能引入已修复的 Bug |
| 修改 minSdk / targetSdk | 影响兼容性和上架要求 |
| 删除现有测试 | 即使测试失败也应修复而非删除 |

---

## 十三、版本历史

| 版本 | 日期 | 变更内容 |
|------|------|------|
| 1.0 | 2025-10-01 | 初始版本 |
| 2.0 | 2026-05-16 | 新增包选择决策流程、AI 模块注册规则、WebView 安全红线、测试命名规范、违规示例 |

---

*本文档由「答题宝」技术团队维护。AI 助手在参与本项目时，本文档的优先级高于默认行为。*