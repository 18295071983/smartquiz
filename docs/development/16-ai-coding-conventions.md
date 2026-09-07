# AI 工具编码约定

> 版本: 1.0 | 更新日期: 2026-08-29 | 分支: feature/agent-local
> 本文件定义 AI 编程工具生成代码时的**编码约定**（风格/命名/结构/注释/日志）。是 `ai_rules.md` 编码规则的细化，可直接作为 AI 工具代码生成约束。

## 一、语言与格式

1. **纯 Java 17**，禁止 Kotlin/Compose/Coroutines。
2. 缩进 4 空格；大括号同行（K&R 风格）。
3. 行宽 ≤ 100 字符，超长换行（方法链/参数 4 空格续行）。
4. 静态 import 不滥用；用完整类名或合理 import。

## 二、命名约定

| 元素 | 规则 | 示例 |
|------|------|------|
| 类/接口 | 大驼峰 | `AIChatCoordinator` |
| 方法/变量 | 小驼峰 | `getName()` / `chatAdapter` |
| 常量 | 大写下划线 | `MAX_TOOL_ROUNDS` / `BATCH_INTERVAL_MS` |
| **日志 TAG** | `private static final String TAG = "类名"` | `"AgentChatHandler"` |
| 包 | 全小写 | `ai.chat.coordination` |
| Boolean 变量 | `is`/`has` 前缀 | `isGenerating` / `hasImage` |
| DAO 方法 | `insert`/`update`/`delete`/`get`/`getBy` | `getQuestionsByCategory` |
| 布尔 getter | `isXxx` 而非 `getIsXxx` | `isActive()` |

**命名检查**：AI 工具生成代码后，核对类/方法/变量/常量命名是否符合上述。

## 三、结构约定

### 3.1 Activity

```java
@AndroidEntryPoint
public class AIChatActivity extends BaseActivity {
    private static final String TAG = "AIChatActivity";

    @Override protected int getLayoutId() { return R.layout.xxx; }
    @Override protected void initView() { ... }   // 绑定控件
    @Override protected void initData() { ... }   // 装配模块/初始化
    @Override protected void initListener() { ... } // 监听器
}
```

规则：
- Activity 是**薄壳**，不写业务逻辑。
- `findViewById` 绑定控件（项目不用 ViewBinding）。
- 模块通过 `new XxxManager(this, callback)` 装配。

### 3.2 AI 工具

真实工具类**全部直接 `implements AITool`**（项目里 0 个 extends BaseAITool，BaseAITool 是抽象骨架但未被工具继承）：

```java
// 项目真实方式：implements AITool（全部工具都是）
public class MyTool implements AITool {
    public String getName() { return "my_tool"; }
    public String getDescription() { return "工具描述"; }
    public AIToolResult execute(Map<String, Object> parameters) { ... }
}

// 抽象骨架 BaseAITool（implements AITool，提供 canHandle 等默认实现，但项目工具未继承）
public abstract class BaseAITool implements AITool {
    public BaseAITool(String toolName, String description) { ... }
    public abstract AIToolResult execute(Map<String, Object> parameters);
    public boolean canHandle(String input) { ... }
}
```

规则：
- 必须实现 `AITool` 接口（`getName()`/`getDescription()`/`execute(Map<String,Object>)` 返回 `AIToolResult`）。
- 项目工具类**直接 implements AITool**（不要臆造 extends BaseAITool）。
- 在 `AIToolManager` 注册（`registerDynamicTool` 或加入工具列表）。

### 3.3 ViewModel

```java
@HiltViewModel
public class ChatViewModel extends AndroidViewModel {
    @Inject AIService aiService;
    private final MutableLiveData<State> state = new MutableLiveData<>();
}
```

## 四、注释约定

| 场景 | 规则 |
|------|------|
| 代码注释 | **英文** |
| Javadoc 类/方法 | 英文，说明职责/参数/返回 |
| 复杂逻辑 | 写注释说明"为什么" |
| **TODO/FIXME** | 中文或英文均可，标注位置：`// TODO: reason` |
| 中文注释 | 仅在 UI 字符串/特殊说明时 |

**注意**：UI 面向用户的文本用**中文**（`strings.xml`），代码注释用**英文**。

## 五、日志约定

使用 `com.oilquiz.app.util.AILogger`（AI 层）或 `com.oilquiz.app.infra.AppLogger`（基础设施）。

```java
AILogger.i(TAG, "message");     // info
AILogger.w(TAG, "warning");     // warn
AILogger.e(TAG, "error", e);    // error + throwable
AILogger.d(TAG, "debug");       // debug
```

规则：
- 每条日志带 TAG（类名常量）。
- 关键路径打 info；异常打 e（带异常对象）。
- 不打印敏感信息（API Key/模型路径）。

## 六、注解约定

| 注解 | 用途 | 必加位置 |
|------|------|---------|
| `@AndroidEntryPoint` | Activity/Fragment | 所有 Activity |
| `@HiltViewModel` | ViewModel | 所有 ViewModel |
| `@Inject` | 依赖注入 | 构造器/字段 |
| `@Module` / `@InstallIn` | Hilt DI 模块 | `di` 包 |
| `@Entity`/`@Dao`/`@Database` | Room | `database`/`model` 包 |
| `@PrimaryKey`/`@ColumnInfo` | 实体字段 | Room Entity |
| `@Ignore` | 非数据库字段 | 构造器/字段 |
| `@Singleton` | 单例 | Service |

## 七、资源约定

- 字符串在 `strings.xml`，颜色在 `colors.xml`，尺寸在 `dimens.xml`。
- 控件 id：`btn_`/`tv_`/`rv_`/`list_` 前缀（如 `btn_send`/`tv_title`）。
- 布局文件 `activity_xxx.xml` / `item_xxx.xml` / `view_xxx.xml` / `dialog_xxx.xml`。

## 八、AI 工具代码生成清单（可勾选）

| # | 检查项 |
|---|------|
| 1 | 纯 Java，无 Kotlin/Compose |
| 2 | 类在正确包目录 |
| 3 | Activity extends BaseActivity + @AndroidEntryPoint |
| 4 | 常量命名全部大写下划线 |
| 5 | TAG = 类名常量 |
| 6 | 注释英文，UI 字符串中文 |
| 7 | 依赖 @Inject，不手动 new |
| 8 | 日志用 AILogger，带 TAG |
| 9 | 不走多轮 LLM 编排（Agent 单循环） |
| 10 | 编译通过（`gradlew compileDebugJavaWithJavac --offline`） |

## 九、规避反模式

| 反模式 | 正确做法 |
|--------|---------|
| Activity 直接操作 DB | 走 ViewModel/Manager → Repository → DAO |
| 多轮 LLM 调用（意图/分解/思考链） | Agent 单循环 `AgentLoopEngine` |
| 手动 `new` 服务/AITool | `@Inject` / `AIToolManager` 注册 |
| 硬编码中文 UI 字符串 | `strings.xml` 资源 |
| 主线程做耗时/IO | `ExecutorService` 放后台 + `runOnUiThread` 更新 |
| 引入 Kotlin/Compose/Coroutines | 项目纯 Java，禁止 |
| 修改构建配置/迁移 | 禁止（除非明确） |

## 相关文档

- [AI 编码助手规则](../ai_rules.md)
- [开发规范](09-development-guide.md)
- [模块清单](08-module-inventory.md)
