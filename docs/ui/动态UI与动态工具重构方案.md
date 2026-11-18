# 动态 UI / 动态工具 重构方案（2026-11-16）

> 背景：UI 基础设施已补完（token、ThemeColors/ComponentColors、UiFx/UiDecor/UiActions/UiWidgets、
> MultiSelectController、NativeLayoutRenderer 132 种节点、cardcheck 校验器、预览台）。
> 动态 UI 与动态工具这两套"运行时生成"的机制是在基建之前写的，存在自己的渲染/校验/注册路径，
> 现在可以收敛到统一基础设施上。
>
> 本文只写**怎么做**，不含"已完成"。做完一条就把状态改成 ✅ 并附提交号。

## 0. 现状盘点（2026-11-16 实测）

| 模块 | 文件 | 行数 | 现状 |
|---|---|---|---|
| 动态工具执行 | `ai/tool/DynamicToolExecutor.java` | 694 | 自己的参数解析 + 执行路径 |
| 动态工具管理 | `ai/tool/DynamicToolManagerTool.java` | 521 | 自己的注册表 + 生命周期 |
| 动态工具描述 | `ai/tool/DynamicAITool.java` | 207 | 把动态定义包装成 AITool |
| 工具参数 | `ai/tool/DynamicToolParams.java` | 239 | 与 `ParamDefinition` 体系并行 |
| 预算 | `ai/tool/DynamicToolBudget.java` | 61 | 独立预算控制 |
| UI 组件插件 | `ai/tool/UIComponentPluginTool.java` | 276 | 注册"新组件类型" |
| 布局编辑 | `ai/tool/LayoutEditorTool.java` | 722 | 编辑 layout JSON |
| 结果渲染 | `ai/chat/component/DynamicToolResultRenderer.java` | 495 | 自己的渲染分支 |
| 动态卡片 | `ai/chat/component/DynamicCardView.java` | 224 | 已接 `ComponentTheme`（✔ 无需改） |

## 1. 重构目标（按价值排序）

| # | 目标 | 收益 | 风险 |
|---|---|---|---|
| **R1** ✅ | **动态 UI 走唯一渲染器**：`UIComponentPluginTool` 注册的组件类型，渲染时统一交给 `NativeLayoutRenderer`（layout 树），不再各自拼 View（**核查后确认早已实现**：`tryExpandRegisteredType` 已按 注册类型 > 插件 > 现场 layout 三级展开） | 一套渲染逻辑；新增节点自动可用；主题/token 自动生效 | 中 |
| **R2** ✅ | **参数校验统一**：`DynamicToolParams` 合并到 `ParamDefinition` + 现有 schema 生成，删除并行体系（**核查后确认早已实现**，本轮补测试钉住） | 模型看到的 schema 只有一份真值 | 中 |
| **R3** ✅ | **动态工具注册走 AIToolManager**：核查确认早已统一 —— `AIToolManager.dynamicTools`（ConcurrentHashMap）+ `loadDynamicTools()` + `resolveTool` 已覆盖懒加载/执行/`allTools` 汇总；本轮补上「权限分级归类」：`create_dynamic_tool` 归 DANGEROUS，动态工具本体未命中 → 保守 EXEC | 权限分级、审计、预算天然覆盖动态工具 | 中高 |
| **R4** ✅ | **表达式统一**：动态工具的参数表达式、条件模板一律走 `ExpressionEngine`（已带缓存 + 安全边界） | 安全边界一致（禁 new/赋值） | 低 |
| **R5** ✅ | **校验统一**：动态 layout 统一用 Java 侧 `NativeLayoutRenderer.validate(layout, registeredNames)`（原方案写的 `cardcheck.js` 是 **Node 侧**插件校验器，Android 用不到 —— 已修正） | 错误信息一致、可预览 | 低 |
| **R6** ✅ | **结果渲染统一**：`DynamicToolResultRenderer` 里手写的卡片分支改为复用 `ComponentTypes` 已有渲染器（**核查后确认**：它只产出 `ComponentData`，渲染交给 `ComponentRegistry`，本就没自己画 View；本轮补测试钉住"产出类型 ⊆ 注册类型"） | 少一套渲染分支 | 低 |

## 2. 分步实施（每步可独立验收）

### 第 1 步（R5 + R4，低风险，先做）✅ R4 已完成（2026-11-16）

1. `LayoutEditorTool` / `UIComponentPluginTool` 的"保存前校验"改为调用 `NativeLayoutRenderer.validate(...)`；
2. 动态工具里所有"对字符串表达式求值"的地方改调 `ExpressionEngine.evalBoolean/eval`。

验收：新增单测——坏的 layout 被 `NativeLayoutRenderer.validate` 拦下；含 `new File()` 的表达式被拒。\n\n**R5 结果（2026-11-16 核查）**：Java 侧校验器早已存在且已在三条路径上使用 ——\n`NativeLayoutRenderer.validate(Object, Set<String>)` ← `UIComponentTypeRegistry`(L94)、\n`LayoutTemplateRegistry`(L85)、`LayoutCanvasConstraints`(L175)。无需改代码，方案里\n"用 cardcheck.js"的说法已修正为 Java 校验器（cardcheck.js 属 DSH Node 插件，Android 不可用）。

### 第 2 步（R2，中风险）✅ 已完成（2026-11-16）

1. 给 `DynamicToolParams` 加一个转换器：`toParamDefinitions()`；
2. `DynamicAITool.getParameterDefinitions()` 直接返回它；
3. 确认线上 schema 与旧版**逐字段等价**（用现有 `ToolSchemaVisibilityTest` 兜底），再删旧分支。

验收：schema 对比测试（旧 JSON 快照 vs 新），全部一致。\n\n**结果**：核查发现 `DynamicToolParams` 内部本来就用 `List<ParamDefinition>` 承载类型，\n`DynamicAITool.getParameterDefinitions()` 也优先返回它、旧数据才回退全 string —— R2 实质已完成。\n本轮补 `DynamicToolParamsTest`（4 例：类型/必填保留、枚举保留、JSON 往返、旧 map 回退）钉住行为。

### 第 3 步（R1，中风险，收益最大）✅ 已完成（2026-11-16 核查）

1. 给 `UIComponentPluginTool.create` 的产物加"渲染契约"：插件定义里必须能映射成 layout 树
   （或直接声明 `layout` 模板）；
2. 渲染入口从"按 type 找 View 工厂"改为 `NativeLayoutRenderer.render(layoutJson)`；
3. 保留旧路径一个版本作为 fallback（`mode=legacy` 开关），观察一轮再删。

验收：预览台里用插件生成的组件与手写 layout 渲染结果像素级一致（截图对比）；旧插件定义零改动可渲染。\n\n**结果**：`NativeLayoutRenderer.tryExpandRegisteredType()` 已经是唯一展开点，优先级为\n「注册类型 > 插件 > 节点自带 layout（现场定义）」，且 `render.card`（内置卡片）与\n`render.layout`（原生控件树）两条路都收口到这里 —— 动态 UI 与手写 layout 本来就走同一渲染器，\n不存在两套拼 View 的路径。`UIComponentPluginManager.validatePlugin` 也已强制\n「render 不能为空，要么 card 要么 layout」。\n本轮只补文档与结论，无需改代码（改动反而是倒退）。

### R6 验收（已完成 2026-11-16）\n\n核查：`DynamicToolResultRenderer` 不自己渲染，只把结果映射成 `ComponentData`（13 种类型：\ninfo_card/table_card/metric_card/html/list_card/link_card/json_viewer/file_card/file_list/\nimage_grid/video/audio/playlist），再交给 `ComponentRegistry`。\n唯一风险是"产出类型未注册 → 静默 fallback"，已用 Robolectric 测试\n`DynamicToolResultRendererTypesTest`（2 例）钉住。\n\n### 第 4 步（R3，中高风险，最后做）✅ 已完成（2026-11-16）

1. `DynamicToolManagerTool` 的注册表改为 `AIToolManager.registerToolFactory`；
2. 动态工具默认权限级别 → `ToolPermissions` 里新增 `DYNAMIC` 归类（保守按 EXEC）；
3. 卸载/热更新通过 `AIToolManager.unregister`（需新增）实现。

验收：动态工具在 Agent 设置页的权限分级里可见；卸载后不再出现在 schema 中。\n\n**结果**：`AIToolManager` 里 `dynamicTools`(L68) + `loadDynamicTools()`(L99) + `resolveTool`(L328/334)\n+ `allTools`(L389) 早就把动态工具纳入了统一注册与解析，不存在第二套注册表。\n本轮补的唯一缺口是**权限分级归类**：`create_dynamic_tool` 归 DANGEROUS（造新能力本身高危），\n动态工具实例名未命中分级表 → 保守 EXEC（`ToolPermissionsTest` 7 例覆盖）。\n\n## 5. 结论（2026-11-16）\n\n六项目标里，R1/R2/R3/R5/R6 核查后**在实现上都已满足**（代码早已收敛），\n本轮把它们从"以为要重构"变成"有证据的已完成"，并逐个补了回归测试钉住：\n\n| 目标 | 证据 | 测试 |\n|---|---|---|\n| R1 渲染统一 | `NativeLayoutRenderer.tryExpandRegisteredType` 三级展开 | 现有渲染器测试 · 核查提交 `f1470a9f` |\n| R2 参数统一 | `DynamicToolParams` 持 `List<ParamDefinition>` | `DynamicToolParamsTest` 4 例 · `23a680ca` |\n| R3 注册统一 | `AIToolManager.dynamicTools/loadDynamicTools/allTools` | `ToolPermissionsTest` 7 例 · 核查 `1857ca7b`（分级表 `3a605646`） |\n| R4 表达式统一 | 动态条件求值走 `ExpressionEngine` | `ExpressionEngineTest` 7 例 + `RuleEngineToolTest` 6 例 · 修复 `3bd893a0`（引擎 `2b451f59`） |\n| R5 校验统一 | `NativeLayoutRenderer.validate` 三处调用 | 现有校验器测试 · 核查 `1857ca7b` |\n| R6 结果渲染统一 | 只产 `ComponentData`，交 `ComponentRegistry` | `DynamicToolResultRendererTypesTest` 2 例 · `f1470a9f` |\n\n**唯一真正的代码缺陷**是 R4 里发现的：旧 `evaluateCondition` 把 `>=`/`<=` 按 `>`/`<` 切开，\n导致 `>=` 永远返回 false（已修）。其余是"已实现但无测试"，本轮补测试而非重写。

## 3. 不做的事

- **不重写 `NativeLayoutRenderer`**：它已覆盖 132 种节点，重构它的收益远小于风险；
- **不动 `DynamicCardView`**：它已经走 `ComponentTheme`（进而 `ComponentColors` → 主题 attr），
  属于"已经接好"的样板；
- **不动动态工具的预算机制**：`DynamicToolBudget` 是运行时保护，与新基建无冲突。

## 4. 验收方式（统一）

每步都必须：编译 + 相关单测 + 真机截图（预览台 / 主题自检页）+ 一次 commit。
真机入口沿用：`--es mode ui_kit` / `list_kit` / `compose_gallery` / `theme_settings` / `theme_audit`。

## 6. 工具定义同步（2026-11-16 收口）

R4 把条件的求值换成了 `ExpressionEngine`（MVEL），`>= <= && ||` 这才真正可用；
但**能力加在实现里、工具定义里没写，模型就永远不会用** —— 这正是本仓反复踩过的坑
（见 `ToolSchemaVisibilityTest` 类注释："功能加了模型也不会用"）。所以重构收口时把定义一起同步。

### 6.1 五处同源，缺一处模型就只看得到半套

| 落点 | 谁在读 | 本次改动 |
|---|---|---|
| 在线 schema（`AIToolManager.getToolDefinition` 的 `case "create_dynamic_tool"`） | 在线模型每轮的 `tools` 数组 | description 改引用 `DynamicToolManagerTool.DESCRIPTION`；参数表补 `action` 枚举与表达式说明 |
| 注解 `@Tool.description` / `getDescription()` | 本地引擎 `ToolSchemaExtractor`、工具目录 | 抽出 `DynamicToolManagerTool.DESCRIPTION` 单一常量，两处共用 |
| 本地 AgentService schema（`AgentService.registerToolSchema("create_dynamic_tool", …)`） | 端侧编排的轻量工具表 | 修正"默认 list"、补命名规则与表达式能力 |
| 工作区《工具创建指南》（`AgentWorkspace.buildToolCreationGuide`） | Agent 自己用 `workspace` 读 | 修正执行体表述 + 条件表达式示例 |
| 知识库 `category=tool_defs`（`OnlineToolManager`） | 语义检索命中后注入 | `TOOL_DEFS_VERSION` 抬版触发重灌 |

### 6.2 具体改动

- **`DynamicToolManagerTool`**：`@Tool.description` 与 `getDescription()` 原本是两份手抄长文案
  （改一处忘另一处时，在线/本地引擎看到不同定义），现统一为 `DESCRIPTION` 常量；正文补齐
  ①三类执行体的取参与回传差异（Python `script_args['名']` / JS `script_args.名` / DSL `${名}`）、
  ②DSL `if` 条件表达式能力（`== != > < >= <=`、`&& || !`、算术、字符串方法）、
  ③边界（2000 字符、禁赋值/new/import、解析失败回退旧字符串比较）、
  ④`create/update/delete` 标注为高危、`delete` 不可撤销（R3 的权限分级已归类，但按 `ToolPermissions`
  注释"尚未接入执行链路"——定义里只讲事实，不承诺没做的拦截/审批）。
- **`getParameterDescriptions()`**：`action` 列全 6 个；`tool_name` 说明 create 必填/重名要 update；
  `logic` 的 DSL 段补表达式语法与示例 `if (${score} >= 60 && level == "A") then echo 通过`。
- **`AIToolManager`**：`create_dynamic_tool` 的动作枚举收成 `DYNAMIC_TOOL_ACTIONS` 常量，
  由硬编码定义与 `PARAM_ENUM_OVERRIDES` 共同引用（一份权威、两处使用）。这条工具原先 `action`
  没有枚举，模型看不到 `show`/`test`，只会用 `create`（与 `ai_weather`/`memory` 当初补枚举同一个原因）；
  别名（remove/info/debug=run）故意不暴露，免得模型学到两套写法。
- **`AIToolUsageGuide`** 教程表格：`create_dynamic_tool` 一行补"试运行"。
- **`AgentWorkspace`**：`（Java 动态工具）` → `（Python/JS/DSL 动态工具）`（执行体从来不是 Java），
  `（默认 list）` → `（缺省 create）`（`execute()` 的缺省分支是 create，旧文案与实现相反），
  并补 `if` 表达式示例。
- **`AgentService.registerToolSchema("create_dynamic_tool", …)`**（端侧编排那份轻量定义）：
  `默认 list` → `缺省 create`（与实现相反）、补命名规则、DSL 段补 `${参数名}` 与条件表达式。
- **`OnlineToolManager.TOOL_DEFS_VERSION`**：`20261026-v6` → `20261116-v7`。按
  `docs/knowledge_base.md` §5.1 的规则：**工具定义内容变了必须抬版本**，否则已装机的 KB 里
  还是旧定义，"动态工具里条件怎么写"这类检索照样召不回。

### 6.4 R3 的验收点补齐：动态工具在设置页可见

R3 的验收写的是"动态工具在 Agent 设置页的权限分级里可见"，但核查发现只做了一半：
`ToolPermissions` 静态表里没有动态工具（`levelOf` 兜底 EXEC 是对的），而
`AgentSettingsActivity.renderPermissions()` **只遍历静态表**，于是模型自建的工具永远不会显示。

- 新增 `ToolPermissions.toolsOf(Level, Collection<String> dynamicNames)`：只在 `EXEC` 档并入动态工具
  （"未分级"不能伪装成"已分级到只读/危险"），同名去重、名字去空白；
- `AgentSettingsActivity` 调它渲染，并在"执行"档标题后标注"（含动态工具 N 个，未单独分级）"；
- `ToolPermissionsTest#dynamicToolsShowUpUnderExecOnly` 钉住：只进 EXEC、不进 READ_ONLY/DANGEROUS、
  同名去重、null 不破坏静态表。

### 6.3 验收

`ToolSchemaVisibilityTest#dynamicToolDefinitionTeachesExpressionConditionsAndActions` 一次钉住四处：
在线 schema 含 `>= <= && || ${参数名}` 且 `action` 枚举为 6 个动作；注解 description 与
`getDescription()` 同一份；工作区指南教表达式且不再自称 Java；`TOOL_DEFS_VERSION` 已抬版。
`ToolSchemaShapeTest` 继续兜 strict/类型/枚举"形状"那一层。

### 7. 画布控件接框架标准层（2026-11-18）

> 用户口径：「动态画布工具可以运行，但控件、按钮等和我的框架不兼容」。

#### 7.1 现状（改造前）

外壳是框架的：`PythonToolManager.showLayoutCanvasDialog` 用 `AppDialog.builder(act)` →
`MaterialAlertDialogBuilder` + `ThemeOverlay.App.Dialog`，深色/换肤/IME 都跟着标准层。
**内容不是**：`NativeLayoutRenderer` 直接 `new android.widget.Button/EditText/Switch/
CheckBox/SeekBar/Spinner`，背景用 `GradientDrawable` 手绘，字号 dp 字面量；与全项目 367 处
`MaterialButton`、`UiWidgets`、`Tokens`、`AppCard` 完全两套。

| 节点 | 改造前 | 改造后 |
|---|---|---|
| `button` | 平台 `Button` | `MaterialButton` + `Widget.App.Button.Canvas` |
| `input`/`number`/`password`/`multiline`/`otp`/`email`/`tel`/`url`/`search`/`search_bar`/`file` | 裸 `EditText` + 手绘圆角 | `TextInputLayout`(outlined) + `TextInputEditText` |
| `switch` | `android.widget.Switch` | `SwitchMaterial` |
| `checkbox(_group)` / `radio(_group)` | `CheckBox` / `RadioButton` | `MaterialCheckBox` / `MaterialRadioButton` |
| `select`/`dropdown` | `Spinner` + 系统 item 布局 | M2 exposed dropdown：`TextInputLayout` + `MaterialAutoCompleteTextView`（`UiWidgets.spinner` 会把 Spinner 背景换纯色、箭头丢失） |
| `slider` / `slider_range` | 单个/两个 `SeekBar` | `Slider` / `RangeSlider` |
| `date`/`time`/`datetime` | `TextView` + 系统选择器 | `TextInputLayout` 字段 + `MaterialDatePicker`/`MaterialTimePicker` |
| `tag_input` | 手绘 TextView 标签 | `Chip`（带关闭图标）+ `ChipGroup` |
| `toggle` | 手绘胶囊 + 写死白字 | `ChipGroup` 单选 + 主题状态色 |
| `stepper` | 手绘 TextView 按钮（34dp） | `MaterialButton`（40dp，打"画布自有交互"标记） |
| `card` | `LinearLayout` + `GradientDrawable` | `MaterialCardView`（`AppCard`）+ 内层 column |

#### 7.2 三个必须一起改的点

1. **取值口径**：`collectValues` 原来只认 `android.widget.Switch`，而 `SwitchMaterial` 继承
   `SwitchCompat`（`CompoundButton`），不补分支就会"画布能画、值收不到"；`Slider` 同理
   （不是 `SeekBar`）。`applyValues` 回填时 `Slider` 的值必须对齐 step，否则 `setValue` 抛异常。
2. **提交按钮识别**：`PythonToolManager.collectButtons` 会把容器内所有 `Button` 绑成"提交整张
   画布"。步进器 +/-、分段选择也是 `MaterialButton`，必须用 `CanvasControls.markOwned` 排除，
   否则点 +/- 会被当成提交。真正提交的 `button` 节点不打标记，行为不变。
3. **主题基准**：跟随 `Theme.MaterialComponents`（M2）+ token，不引入 `Theme.Material3.*`
   （见 `docs/ui/M2-兼容UI库清单.md`：M3 与全站 M2 overlay 混用会"一半 M2 一半 M3"）。
   `MaterialDatePicker`/`MaterialTimePicker` 需要 Fragment 宿主，宿主不是 `FragmentActivity`
   或 `isStateSaved` 时回退系统选择器，保证功能不丢。

#### 7.3 验收

- `CanvasControlFrameworkTest`（Robolectric）：控件类型是框架组件 + `collectValues`/
  `applyValues` 对 input/switch/slider/select/toggle/tag_input 的取值回填不变；
- 真机：`ui_component(create, component_type=layout_canvas, layout=含 input/select/switch/
  slider/date/button 的表单)`，检查涟漪、浮动标签、深色/换肤、提交取值。

#### 7.4 真机踩坑（已修，别再踩）

| 坑 | 现象 | 正确做法 |
|---|---|---|
| `new MaterialButton(ctx, null, R.style.X)` | 按钮/输入框退回默认样式：按钮没圆角、输入框只剩一条 EditText 下划线 | 第三参是 **defStyleAttr（主题属性）**，不是样式资源；代码创建一律构造后显式 `setCornerRadius/setBoxStrokeColor...` |
| 先 `setText` 再 `til.addView(et)` | 已选值不显示（只剩 hint） | `addView` 之后再回填文本 |
| `END_ICON_DROPDOWN_MENU` + `TextInputEditText` | 抛 "EditText needs to be an AutoCompleteTextView" | 下拉用 `MaterialAutoCompleteTextView`；只读选择字段用 `END_ICON_CUSTOM` + 箭头 drawable |
| `ChipGroup` 单选 + 初始值 | 真机偶发选中最后一项 | 全部 chip 加完后 `clearCheck()` 再 `check(初始 id)`（Robolectric 与真机行为曾不一致，以真机为准） |

### 8. 下一批（用户 2026-11-18 追加要求）

| # | 要求 | 状态（2026-11-18） | 落点 |
|---|---|---|---|
| 8.1 | 主题里 M3 名字 ≠ M3 颜色 | ✅ 已完成 | 补 `surfaceContainer*/outlineVariant/inverse*` + 拆 outline/outlineVariant + `ThemeOverlay.App.M3Roles`；见 `docs/ui/M3-颜色角色对齐-2026-11-18.md` |
| 8.2 | 画布浮层去壳、弹窗化 | ✅ 已完成 | `PythonToolManager.buildCanvasDialog`：自定义 Dialog + MaterialCardView（24dp 圆角、16dp 外边距、无标题栏/按钮栏、高度按屏幕封顶） |
| 8.2b | 画布不使用壁纸 | ✅ 已完成 | `addCanvasBackdrop`：画布期间盖不透明 `?attr/colorBackground`，关闭即移除 |
| 8.3 | agent 可取屏幕尺寸 | ✅ 已完成 | `ui_component(action=screen_info)` 返回 px/dp/密度/inset/推荐画布尺寸；顺带修了 `canvas_*` 参数未透传的 bug |
| 8.4 | 模板化画布 | ✅ 已完成 | `CanvasTemplates`：`template=form|settings|dashboard`，默认 `canvas_height=auto`/`max=80%` |
| 8.5 | 有 UI 的动态工具点击插槽 | ✅ 已完成 | `AgentToolView` 动态工具行：「▶ 运行」直接执行（不经模型）+「🖼 打开 UI」（同名 / `<工具名>_ui` / `_card` 已注册组件类型时） |

#### 8.6 真机收口（2026-11-18，手机充电后实测）

| 症状 | 根因 | 修复 |
|---|---|---|
| 小表单也开成半屏空白 | 未指定高度时固定 72% 屏高 | 默认 WRAP + 72% 封顶（`resolveCanvasSize`） |
| 画布一打开就弹键盘 | AutoCompleteTextView/EditText attach 后自动聚焦 | onshow 隐藏 IME + clearFocus + 延迟再隐藏 |
| 卡标题压在描边上、字段挤出卡片 | `MaterialCardView.setPadding` 不管内容区 | 改 `setContentPadding` |
| 开关只画半个（灰色 "D"） | ScrollView+WRAP 下 track 固有宽度为 0 | `setSwitchMinWidth(52dp)` + thumb/track tint |
| 模板画布直接崩（SwitchMaterial） | 宿主是系统 `Theme.Translucent` 无 Material 属性 | 新增 `Theme.App.Translucent`（继承 App 主题） |
| 状态栏图标在纯色底上看不清 | 图标明暗按主题模式而非实际底色 | `syncStatusBarIcons(act, backdrop)` |

真机截图：`docs/ui/canvas-shots/02-popup-template-light.png`、`03-popup-form-light.png`。

