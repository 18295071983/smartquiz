# 答题宝 (SmartQuiz) — 开发规范

> **版本: 2.0 | 更新日期: 2026-05-16**

---

## 一、概述

本文档定义了「答题宝」Android 项目的全部开发规范，涵盖架构设计、代码风格、资源命名、线程管理、依赖注入、Compose 最佳实践、AI 模块开发、WebView 安全、数据库迁移、测试要求以及性能指标。所有团队成员和 AI 编码助手必须严格遵守。

---

## 二、架构规范

### 2.1 整体架构

项目采用 **MVVM (Model-View-ViewModel)** 架构，结合 **Repository 模式** 实现数据层抽象，使用 **Hilt** 进行依赖注入。

```
┌─────────────────────────────────────────────────┐
│  View Layer (Activity / Fragment / Compose)     │
│  ├─ ui/activity/   ├─ ui/fragment/              │
│  └─ ui/compose/    └─ ui/widget/                │
├─────────────────────────────────────────────────┤
│  ViewModel Layer                                │
│  └─ viewmodel/                                  │
├─────────────────────────────────────────────────┤
│  Repository Layer                               │
│  └─ repository/                                 │
├─────────────────────────────────────────────────┤
│  Data Layer                                     │
│  ├─ database/ (Room)                            │
│  ├─ infra/ (Network, Logging)                   │
│  └─ manager/ (Business Logic Managers)          │
└─────────────────────────────────────────────────┘
```

### 2.2 分层职责

| 层级 | 目录 | 职责 |
|------|------|------|
| View | `ui/` | UI 渲染、用户交互、不包含业务逻辑 |
| ViewModel | `viewmodel/` | 持有 UI 状态，调用 Repository，暴露 LiveData/StateFlow |
| Repository | `repository/` | 数据访问抽象层，协调多个数据源 |
| Data | `database/`, `infra/`, `manager/` | 数据持久化、网络通信、业务逻辑管理 |

### 2.3 依赖方向

```
Activity/Fragment → ViewModel → Repository → Database / Manager / Network
```

**严禁反向依赖**：ViewModel 不持有 Activity 引用，Repository 不持有 ViewModel 引用。

---

## 三、项目结构

```
d:\quzp\app\
├── src/main/java/com/smartquiz/app/
│   ├── ai/                    # AI 模块
│   │   ├── agent/             # AI Agent 引擎
│   │   ├── tool/              # AI 工具集
│   │   ├── gpu/               # GPU 加速
│   │   └── model/             # 模型管理
│   ├── database/              # Room 数据库
│   │   ├── entity/            # 实体类
│   │   ├── dao/               # DAO 接口
│   │   └── migration/         # 数据库迁移
│   ├── di/                    # Hilt 依赖注入模块
│   ├── infra/                 # 基础设施层
│   │   ├── network/           # 网络请求
│   │   ├── logging/           # 日志系统
│   │   └── crash/             # 崩溃上报
│   ├── manager/               # 业务逻辑管理器
│   ├── model/                 # 数据模型（非数据库实体）
│   ├── repository/            # 仓库层
│   ├── resource/              # 资源管理
│   ├── toolkit/               # 工具集
│   ├── ui/                    # 界面层
│   │   ├── activity/          # Activity
│   │   ├── fragment/          # Fragment
│   │   ├── compose/           # Jetpack Compose 组件
│   │   ├── widget/            # 自定义控件
│   │   └── adapter/           # RecyclerView Adapter
│   ├── util/                  # 静态工具类
│   ├── viewmodel/             # ViewModel
│   ├── weather/               # 天气模块
│   └── webview/               # WebView 模块
└── src/test/                  # 单元测试
```

---

## 四、命名规范

### 4.1 Java 命名规范

| 类型 | 规则 | 示例 |
|------|------|------|
| 类名 | PascalCase（大驼峰） | `QuizActivity`, `QuestionViewModel` |
| 方法名 | camelCase（小驼峰） | `loadQuestions()`, `onSubmitAnswer()` |
| 常量 | UPPER_SNAKE_CASE | `MAX_QUESTION_COUNT`, `DEFAULT_TIMEOUT_MS` |
| 变量名 | camelCase | `questionList`, `isLoading` |
| 接口 | PascalCase，以 `I` 开头或直接描述 | `IOnAnswerListener`, `QuestionDao` |
| 抽象类 | PascalCase，以 `Base` 或 `Abstract` 开头 | `BaseActivity`, `BaseFragment`, `BaseAITool` |
| 枚举 | PascalCase | `QuestionType`, `DifficultyLevel` |
| 包名 | 全小写，单词直接拼接 | `com.smartquiz.app.viewmodel` |

### 4.2 Kotlin 命名规范

| 类型 | 规则 | 示例 |
|------|------|------|
| 类名 | PascalCase | `QuizRepository`, `AnswerValidator` |
| 函数名 | camelCase | `loadQuizById()`, `validateAnswer()` |
| 属性名 | camelCase | `questionCount`, `selectedAnswer` |
| 伴生对象常量 | UPPER_SNAKE_CASE | `TAG`, `DEFAULT_PAGE_SIZE` |
| 扩展函数 | camelCase | `String.isValidAnswer()`, `View.showOrHide()` |
| 数据类 | PascalCase | `data class UserProfile(...)` |
| 密封类 | PascalCase | `sealed class Result<T>` |

### 4.3 资源文件命名

| 资源类型 | 命名规则 | 示例 |
|------|------|------|
| 图标 | `ic_` 前缀 | `ic_quiz_start.xml`, `ic_timer_24.xml` |
| 背景 | `bg_` 前缀 | `bg_card_rounded.xml`, `bg_gradient_primary.xml` |
| Activity 布局 | `activity_` 前缀 | `activity_main.xml`, `activity_quiz_detail.xml` |
| Fragment 布局 | `fragment_` 前缀 | `fragment_question_list.xml` |
| 列表项布局 | `item_` 前缀 | `item_question_card.xml`, `item_option_radio.xml` |
| Compose 组件布局 | `layout_` 前缀 | `layout_question_screen.xml` |
| 对话框布局 | `dialog_` 前缀 | `dialog_result_summary.xml` |
| 字符串 | 模块_描述 | `quiz_submit_button`, `error_network_timeout` |
| 颜色 | 语义化命名 | `color_primary`, `color_surface`, `color_error` |
| 尺寸 | `dimen_` 前缀 + 用途 | `dimen_card_elevation`, `dimen_text_title` |
| 主题 | `theme_` 前缀 | `style/Theme.SmartQuiz` |

---

## 五、基类规范

### 5.1 BaseActivity

所有 Activity **必须** 继承 `BaseActivity`：

```java
public class QuizDetailActivity extends BaseActivity {
    @Override
    protected int getLayoutResId() {
        return R.layout.activity_quiz_detail;
    }

    @Override
    protected void initView() {
        // 初始化视图
    }

    @Override
    protected void initData() {
        // 初始化数据
    }
}
```

`BaseActivity` 提供以下通用能力：
- 统一的 Toolbar 管理
- 通用的 Loading / Error / Empty 状态处理
- Toast / Snackbar 工具方法
- 生命周期感知的日志记录
- 权限请求封装

### 5.2 BaseFragment

所有 Fragment **必须** 继承 `BaseFragment`：

```java
public class QuestionListFragment extends BaseFragment {
    @Override
    protected int getLayoutResId() {
        return R.layout.fragment_question_list;
    }

    @Override
    protected void initView(View rootView) {
        // 初始化视图
    }

    @Override
    protected void initData() {
        // 初始化数据
    }
}
```

---

## 六、状态管理规范

### 6.1 View 层状态

| 使用场景 | 推荐方式 | 说明 |
|------|------|------|
| Fragment / Activity (传统 View) | `LiveData` | 生命周期感知，自动管理订阅 |
| Jetpack Compose | `StateFlow` | 支持 Compose 的 `collectAsState()` |
| 一次性事件（Toast、导航） | `SingleLiveEvent` / `Channel` | 避免事件被重复消费 |

```kotlin
// ViewModel 中使用 StateFlow
class QuizViewModel @Inject constructor(
    private val quizRepository: QuizRepository
) : ViewModel() {

    private val _questions = MutableStateFlow<List<Question>>(emptyList())
    val questions: StateFlow<List<Question>> = _questions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadQuestions(categoryId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _questions.value = quizRepository.getQuestionsByCategory(categoryId)
            _isLoading.value = false
        }
    }
}
```

### 6.2 不可变性

暴露给 UI 层的状态必须是不可变的：
- `LiveData` 对外暴露为只读类型
- `StateFlow` 使用 `asStateFlow()` 转换
- 数据类使用 `val` 属性

---

## 七、错误处理规范

### 7.1 基本原则

- 所有可能抛出异常的代码必须用 `try-catch` 包裹
- 异常信息必须记录到日志系统
- 向用户展示友好的中文错误提示

```java
try {
    List<Question> questions = quizRepository.loadFromNetwork();
    updateUI(questions);
} catch (NetworkException e) {
    Logger.e(TAG, "网络请求失败", e);
    showError(getString(R.string.error_network_timeout));
} catch (Exception e) {
    Logger.e(TAG, "加载题目失败", e);
    showError(getString(R.string.error_unknown));
}
```

### 7.2 异常分类

| 异常类型 | 处理策略 |
|------|------|
| `NetworkException` | 显示网络错误提示，提供重试按钮 |
| `DatabaseException` | 记录日志，回退到缓存数据 |
| `AIModelException` | 降级到本地规则引擎 |
| `IllegalArgumentException` | 开发阶段快速失败，生产环境兜底 |
| `OutOfMemoryError` | 释放缓存，提示用户重启 |

---

## 八、线程管理规范

### 8.1 强制规则

| 规则 | 说明 |
|------|------|
| **禁止使用 AsyncTask** | AsyncTask 已在 API 30 中废弃 |
| **所有异步操作使用 Coroutines** | Kotlin Coroutines 是唯一的异步方案 |
| **UI 操作必须在主线程** | 使用 `withContext(Dispatchers.Main)` 切换 |
| **IO 操作必须在 IO 线程** | 使用 `withContext(Dispatchers.IO)` |
| **CPU 密集型操作在 Default 线程** | 使用 `withContext(Dispatchers.Default)` |

### 8.2 Coroutines 最佳实践

```kotlin
class QuestionRepository @Inject constructor(
    private val questionDao: QuestionDao,
    private val apiService: ApiService
) {
    suspend fun getQuestions(categoryId: String): List<Question> {
        return withContext(Dispatchers.IO) {
            try {
                val remote = apiService.fetchQuestions(categoryId)
                questionDao.insertAll(remote)
                remote
            } catch (e: Exception) {
                questionDao.getByCategory(categoryId)
            }
        }
    }
}
```

### 8.3 作用域管理

| 作用域 | 使用场景 | 生命周期 |
|------|------|------|
| `viewModelScope` | ViewModel 中的操作 | 跟随 ViewModel |
| `lifecycleScope` | Activity/Fragment 中的操作 | 跟随 LifecycleOwner |
| `GlobalScope` | **禁止使用** | — |

---

## 九、依赖注入规范

### 9.1 Hilt 配置

项目使用 Hilt 进行依赖注入，所有依赖关系通过注解声明：

```kotlin
@HiltAndroidApp
class SmartQuizApplication : Application()
```

```kotlin
@AndroidEntryPoint
class QuizDetailActivity : BaseActivity() {
    @Inject lateinit var quizViewModel: QuizViewModel
}
```

### 9.2 Module 定义

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "smartquiz.db")
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}
```

### 9.3 作用域注解

| 注解 | 作用域 | 使用场景 |
|------|------|------|
| `@Singleton` | 应用全局单例 | Database, Retrofit, SharedPreferences |
| `@ViewModelScoped` | ViewModel 范围内单例 | 与单个 ViewModel 绑定的依赖 |
| `@ActivityScoped` | Activity 范围内单例 | 与单个 Activity 绑定的依赖 |
| `@FragmentScoped` | Fragment 范围内单例 | 与单个 Fragment 绑定的依赖 |
| 无作用域 | 每次注入都创建新实例 | 无状态工具类 |

---

## 十、Jetpack Compose 最佳实践

### 10.1 基本规范

- 使用 **Material 3** 设计组件
- 遵循 **状态提升 (State Hoisting)** 模式
- 使用 `@Preview` 注解预览组件
- 避免在 Composable 函数中执行副作用，使用 `LaunchedEffect` / `SideEffect`
- 使用 `remember` 和 `rememberSaveable` 管理局部状态

```kotlin
@Composable
fun QuestionCard(
    question: Question,
    onAnswerSelected: (Answer) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = question.title,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))
            question.options.forEach { option ->
                AnswerOption(
                    option = option,
                    isSelected = option.isSelected,
                    onClick = { onAnswerSelected(option) }
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun QuestionCardPreview() {
    SmartQuizTheme {
        QuestionCard(
            question = Question.mock(),
            onAnswerSelected = {}
        )
    }
}
```

### 10.2 主题与样式

- 使用 `MaterialTheme` 定义颜色、排版、形状
- 支持深色模式：在 `Theme.kt` 中配置 `darkColorScheme`
- 不得在 Composable 中硬编码颜色值

---

## 十一、AI 模块开发规范

### 11.1 AI Agent 引擎

AI 模块的核心是 `AIAgentEngine`，负责协调 AI 工具调用和模型推理：

```java
public class AIAgentEngine {
    private final AIToolManager toolManager;
    private final ModelManager modelManager;

    public void execute(String input, AIAgentCallback callback) {
        // 1. 解析输入意图
        // 2. 选择合适的 AI Tool
        // 3. 执行推理
        // 4. 返回结果
    }
}
```

### 11.2 BaseAITool 模式

所有 AI 工具 **必须** 继承 `BaseAITool`：

```java
public abstract class BaseAITool {
    protected Context context;
    protected AIToolCallback callback;

    public abstract String getToolName();
    public abstract String getToolDescription();
    public abstract void execute(String input, Map<String, Object> params);
    public abstract void cancel();
    public abstract void release();

    protected void onSuccess(String result) {
        if (callback != null) {
            callback.onToolSuccess(getToolName(), result);
        }
    }

    protected void onError(String error) {
        if (callback != null) {
            callback.onToolError(getToolName(), error);
        }
    }
}
```

### 11.3 Callback 处理

```java
public interface AIToolCallback {
    void onToolStart(String toolName);
    void onToolProgress(String toolName, int progress);
    void onToolSuccess(String toolName, String result);
    void onToolError(String toolName, String error);
    void onToolCancelled(String toolName);
}
```

### 11.4 AI 模块目录规范

| 目录 | 用途 | 内容示例 |
|------|------|------|
| `ai/agent/` | Agent 引擎 | `AIAgentEngine`, `QuizAgent`, `SearchAgent` |
| `ai/tool/` | AI 工具集 | `QuestionGenerateTool`, `AnswerVerifyTool` |
| `ai/gpu/` | GPU 加速代码 | `GpuDelegate`, `OpenCLHelper` |
| `ai/model/` | 模型管理 | `ModelManager`, `ModelDownloader` |

---

## 十二、WebView 安全规范

### 12.1 基础安全要求

- 所有 WebView 必须使用 `SecurityWebViewClient`
- 禁止启用 `allowFileAccess`（默认已是 false）
- 禁止启用 `allowUniversalAccessFromFileURLs`
- JS Bridge 必须对输入参数进行合法性校验

### 12.2 JS Bridge 规范

```java
@JavascriptInterface
public void onJsCall(String action, String data) {
    // 1. 校验 action 白名单
    if (!isValidAction(action)) {
        Logger.w(TAG, "非法的 JS Bridge 调用: " + action);
        return;
    }

    // 2. 校验 data 格式
    try {
        JSONObject json = new JSONObject(data);
        // 3. 字段级校验
        if (!json.has("required_field")) {
            return;
        }
        // 4. 执行业务逻辑
        handleJsAction(action, json);
    } catch (JSONException e) {
        Logger.e(TAG, "JS Bridge 数据解析失败", e);
    }
}
```

### 12.3 FileRedirectManager

所有本地文件访问必须通过 `FileRedirectManager` 进行路径白名单校验，防止路径遍历攻击。

---

## 十三、数据库规范

### 13.1 Room 实体定义

```kotlin
@Entity(tableName = "questions")
data class QuestionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "category_id") val categoryId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
```

### 13.2 DAO 接口

```kotlin
@Dao
interface QuestionDao {
    @Query("SELECT * FROM questions WHERE category_id = :categoryId")
    fun getByCategory(categoryId: String): Flow<List<QuestionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(questions: List<QuestionEntity>)

    @Delete
    suspend fun delete(question: QuestionEntity)
}
```

### 13.3 数据库迁移规则

| 规则 | 说明 |
|------|------|
| **禁止使用 `fallbackToDestructiveMigration`** | 生产环境绝不允许破坏性迁移 |
| 每个迁移必须有对应的测试 | 验证迁移前后的数据完整性 |
| 迁移命名：`MIGRATION_X_Y` | X = 旧版本号，Y = 新版本号 |
| 复杂迁移必须有注释 | 解释每个 SQL 语句的目的 |

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE questions ADD COLUMN difficulty TEXT NOT NULL DEFAULT 'EASY'")
    }
}
```

---

## 十四、测试规范

### 14.1 测试框架

| 测试类型 | 框架 | 用途 |
|------|------|------|
| 单元测试 | JUnit 4 | 业务逻辑验证 |
| Mock | Mockito | 模拟依赖对象 |
| Android 测试 | Robolectric | 模拟 Android 环境运行测试 |
| UI 测试 | Espresso / Compose Testing | 界面交互测试 |

### 14.2 测试示例

```kotlin
@RunWith(RobolectricTestRunner::class)
class QuestionViewModelTest {

    @Mock
    private lateinit var repository: QuizRepository

    private lateinit var viewModel: QuestionViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        viewModel = QuestionViewModel(repository)
    }

    @Test
    fun `loadQuestions should update questions LiveData`() = runTest {
        val mockQuestions = listOf(Question("1", "测试题目", "SINGLE"))
        Mockito.`when`(repository.getQuestionsByCategory("1")).thenReturn(mockQuestions)

        viewModel.loadQuestions("1")

        assertEquals(mockQuestions, viewModel.questions.value)
    }
}
```

---

## 十五、代码审查清单

每次代码审查必须确认以下项目：

| # | 检查项 | 状态 |
|---|------|------|
| 1 | Activity 继承 BaseActivity | ☐ |
| 2 | Fragment 继承 BaseFragment | ☐ |
| 3 | ViewModel 使用 @HiltViewModel | ☐ |
| 4 | 没有直接数据库访问（通过 Repository） | ☐ |
| 5 | 异常被 try-catch 包裹 | ☐ |
| 6 | 没有使用 AsyncTask | ☐ |
| 7 | 异步操作使用 Coroutines | ☐ |
| 8 | 用户可见字符串使用中文资源 | ☐ |
| 9 | 代码注释使用英文 | ☐ |
| 10 | 新功能有对应的单元测试 | ☐ |
| 11 | 数据库变更包含 Migration | ☐ |
| 12 | WebView JS Bridge 有输入校验 | ☐ |
| 13 | 日志级别正确（e/w/i/d） | ☐ |
| 14 | 没有引入新的第三方依赖（未经讨论） | ☐ |
| 15 | 资源命名符合前缀规范 | ☐ |

---

## 十六、性能指标

### 16.1 关键指标

| 指标 | 目标值 | 测量方法 |
|------|------|------|
| 冷启动时间 | < 3 秒 | Android Vitals / 自定义打点 |
| 热启动时间 | < 1 秒 | Android Vitals |
| 内存占用 | < 200 MB | Android Profiler |
| APK 体积 | < 80 MB | APK Analyzer |
| 帧率 | ≥ 60 FPS | GPU 渲染分析 |
| ANR 率 | < 0.1% | Google Play Console |
| 崩溃率 | < 0.5% | Firebase Crashlytics |

### 16.2 优化建议

- 使用 App Startup 库优化初始化流程
- 图片使用 WebP 格式，大图使用 Coil/Glide 加载
- 列表使用 RecyclerView + Paging 3 分页加载
- LargeHeap 仅在充分评估后启用
- 使用 Baseline Profile 提升首次启动性能

### 16.3 构建配置

| 配置项 | 值 |
|------|------|
| minSdk | 31 (Android 12) |
| targetSdk | 34 (Android 14) |
| compileSdk | 34 |
| Gradle | 8.13 |
| AGP | 8.4.0 |
| JDK | 17 |
| Kotlin | 1.9.x |

---

## 十七、版本历史

| 版本 | 日期 | 变更内容 |
|------|------|------|
| 1.0 | 2025-10-01 | 初始版本 |
| 2.0 | 2026-05-16 | 增加 AI 模块规范、Compose 最佳实践、性能指标 |

---

*本文档由「答题宝」技术团队维护，任何修订需经过 Code Review 流程。*