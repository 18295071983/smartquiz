# 工具引导流程 JSON 化：schema 草稿与校验器（2026-10-28，设计阶段）

## 0. 这份文档是什么

回答两个问题：**`tool_guide_flow.json` 该长什么样、怎么保证写错能被发现**，
以及**界面上的原始字段名怎么翻成中文**（§7 翻译器）。

接线状态分两层，别混起来看：

- `terms` 段**已接线**：装载器 + 翻译器已接进 `ToolGuideFlow.buildDynamicFlow`（显示名层）。
- `tools` 段**已接线但默认关**：`ToolGuideFlowFlag`（`ai_chat_prefs/guide_config_json`，出厂 false）
  打开后，配置里列出的工具（现在只有 `ai_weather`/`network_search`/`database`）改由配置供给，
  其余一律维持硬编码 builder。见 §8。
- `composites` 段**还没有消费者**：`CompositeGuideFlow` 本轮一个字没动。

因此"出厂即产品行为零变化"这句仍然成立——不是因为没接线，而是因为**开关默认关**。

规模判定：**不挡任何现有功能**。翻译层作用在一条目前点不到的路径上（§7.1），接管层有 flag 且出厂关
（§8.1）。已验的是数据形状能 1:1 承载现有引导、翻译器三条安全线、以及配置产物与 builder 的
逐字段 + 逐 action 过滤等价（§4、§8.3），没验的是真机（§8.4 六条一条没跑）。

## 1. 为什么要校验器

引导现在写在 Java builder 里，`paramKey` 打错编译就红。搬进 JSON 后这层保护消失：
`"cit y"` 会静默通过解析，运行时变成一步填不进参数的死步骤——用户只看到"点了没反应"，
而配置里看不出所以然。校验器就是补上的编译期等价物，ERROR 时装载方应拒绝整份配置并回退。

## 2. 文件形状

一个文件装两块，因为聚合方案的 chip 入口和单工具引导是同一份数据的不同视角（§5 会说到 chip 现在有第三份副本）：

```
{ "version": "2026-10-28-v2",
  "terms":      { params:{paramKey→中文}, values:{value→中文}, byTool:{工具:{参数:{值→中文}}} },
  "tools":      [ { toolName, toolDisplayName, toolDescription, contextDependencies, steps[] } ],
  "composites": [ { flowId, displayName, description, icon, steps[] } ] }
```

`Step` 字段（**刻意与 `ToolGuideFlow.GuideStep` 逐字同名**，映射层就不需要字段对照表——
对照表正是这类迁移里最容易悄悄写错的地方）：

| 字段 | 适用 type | 说明 |
|---|---|---|
| `type` | 全部 | 取 `GuideStep.StepType` 枚举名原文：`OPTION`/`INPUT`/`CONFIRM`/`FILE_PICKER`/`IMAGE_PICKER`/`DIRECTORY_PICKER`。不接受小写别名，报错能直接钉回代码里的枚举 |
| `title` / `description` | 全部 | 步骤标题与副标题 |
| `paramKey` | 除 CONFIRM | 对应工具参数名，必须存在于模型可见 schema（§3 第 3 档） |
| `options[]` | OPTION | `{label, value}` |
| `hint` / `required` / `multiline` | INPUT | 输入框提示、是否必填、是否多行 |
| `mimeTypes[]` / `allowMultiple` | PICKER | MIME 过滤、多选 |
| `condition` | 可选 | `{key, values[]}`，见下 |
| `dynamicOptions` | INPUT | `{toolName, action, listKey, itemField, allOptionLabel}`，渲染时异步拉列表供点选 |

`CompositeStep` 字段：`toolName` / `actionDescription` / `icon` / `autoExecute` /
`fixedParams{}` / `paramRefs{}`（值形如 `$prev.lat`）/ `guideSteps[]`。

### 2.1 两处刻意偏离 POJO

**条件分支改结构化。** POJO 侧是 `conditionKey` + `conditionValue`，多值靠 `"search|ask"` 竖线分隔。
JSON 里改成 `{"key":"action","values":["search","ask"]}`。语义等价，装载时 `join("|")` 回填即可，
`getActiveSteps()` 一个字不用动。理由：把"值里不能出现的分隔符"写进人手编辑的配置文件是埋雷。

**`paramValue` 不进 schema。** 它是运行时槽位——用户选完后由 `AIChatActivity` 写入
（`:2300-2304`、`:2363`），配置侧没有它的生产者。放进 schema 会让人以为能预填默认值。

### 2.2 `value` 的空串语义

`Option("📍 自动定位", "")` 的空串是**有意的**，表示"不传该参数"。所以校验器区分
`value: null`（ERROR，写漏了）与 `value: ""`（合法），且同一步骤里两个空串算重复值报错。

## 3. 校验规则

ERROR = 装载方必须拒绝该配置；WARN = 不挡运行但要说明。

**第 1 档 结构完整性**

- 缺 `version`；`tools` 与 `composites` 全空（这份配置不会接管任何东西）
- `toolName` 空或重复；`steps` 空；步骤为 null
- `type` 缺失或不是枚举名
- 非 CONFIRM 步骤缺 `paramKey`（用户填的值无处可放）
- OPTION 无 `options`；`label` 空；`value` 为 null；同步骤内 `value` 重复
- `dynamicOptions` 的 `toolName`/`action`/`listKey` 缺任一

**第 2 档 内部引用一致性**（这类错误 Java 里靠人肉看，JSON 里必须机器查）

- `condition.key` 必须指向**更早**的 OPTION 步骤的 `paramKey`——`getActiveSteps()` 只按已选参数过滤，引用不存在的前序选项会让该步永不展示
- `condition.key` 不能是本步骤自己的 `paramKey`
- `condition.values` 为空（没有值能命中 = 死步骤）；含空串
- 同一 `paramKey` 被两个步骤产出时，两者条件值集**必须不相交**（无条件视为通配，与任何条件相交）。相交 = 同一次运行出现两个输入框抢一个参数
- 末步不是 CONFIRM（WARN，现行 13 个 builder 全部以确认步收尾）
- CONFIRM 带 `paramKey`/`options`、非 OPTION 带 `options`、非 INPUT 带 `dynamicOptions`（WARN）
- `paramRefs` 值不匹配 `^\$prev\.[A-Za-z_][A-Za-z0-9_.]*$`
- `autoExecute=true` 却带 `guideSteps`（WARN，自动步不停下来问用户 = 死配置）
- 聚合步 `fixedParams` 与 `guideSteps` 撞同一个 `paramKey`（WARN：**谁覆盖谁我没实测过**，`buildCompositeParams` 的优先级待验，先按可疑报而不假装知道）

**第 3 档 跨 schema 一致性**（注入 `SchemaProbe` 时执行，读的是模型真正收到的 `AIToolManager.getToolDefinition()`）

- `paramKey` 不在该工具 schema 参数里 → ERROR（工具会当未知参数丢掉）
- OPTION 的 `value` 不在该参数声明的枚举里 → ERROR
- schema 里查不到该工具 → WARN，并明确说"本轮 paramKey 与枚举未经校验"（不静默放行）
- INPUT 步骤标 `required=false` 但 schema 说必填 → WARN
- schema 里是枚举却配成 INPUT 手输 → WARN（建议改 OPTION 让人点选）

## 4. 已验证 / 未验证

**已验（62 条用例：`ToolGuideFlowValidatorTest` 37 + `ToolGuideLabelsTest` 9 +
`ToolGuideFlowConverterTest` 12 + `ToolGuideFlowFlagTest` 4，
`skipped=0 failures=0 errors=0`，`--offline` 跑的，XML 时间已核；
`:compileDebugJavaWithJavac` 那次是真执行、不是 UP-TO-DATE，所以主源码含接线改动确实编译过）**

1. 随包那份真文件（经装载器读 `assets/tool_guide_flow.json`，不是测试目录里的副本）过校验器
   ERROR=0，且 WARN 也为 0（字段没退化）。
2. 草稿与现有 builder **逐字段相等**：`ai_weather`/`network_search`/`database` 三个流程，
   加 `go_out`/`study`/`research` 三个聚合方案，比到每个 option 的 label/value、每个 condition 值集、
   `dynamicOptions` 五个字段、`fixedParams`/`paramRefs`/`autoExecute`/`icon`。
3. 校验器每条拦截规则都用坏配置证过会响（仪器不响，前面两条的绿没意义）；术语表三条
   （中文名为空=ERROR、key 是空串=ERROR、中文名与原文相同=WARN）也各证一遍。
4. 草稿的三个工具用**真 schema 当探针**跑通 paramKey 校验。
5. 翻译器三条安全线（§7.2）：翻得出来、**value 一个字节都不变**、已有中文 label 的手写选项绝不被动。
6. 装载器 fail-safe：往 `filesDir` 塞一份带 ERROR 的配置 → 整份拒绝 + 术语表退回空词典 +
   界面维持现状；正常情况读得到随包档并解析出 version/terms/tools/composites。
7. `knowledge_base` 的 12 个 action 枚举值**全部**有中文词条（不变量测试，以后加动作忘加词条会红）。
8. PoC 接管层的等价性：配置产物与 builder 逐字段 diff 为 0，且每个 `action` 取值喂进
   `getActiveSteps` 后两侧出来的步骤序列一致（§8.3）。

**未验**

- 真机：一个都没跑。尤其 §7.3 那条接线（`buildDynamicFlow` 里取 context 再读 assets）在 JVM 里
  验不了——Robolectric 的 Application 被 `robolectric.properties` 换成裸 `android.app.Application`，
  `SmartQuizApplication.getAppContext()` 返回 null。
- 枚举值合法性只到 WARN 级，原因见 §4.1。
- `mimeTypes` 在手写 builder 里全是 null，这个字段实际生效与否没验过。
- 外部覆盖档只验了"有 ERROR 就拒绝"，没验真机推文件的路径与权限。
- JSON 里重复 key（`{"list":"列索引","list":"列出"}`）Gson 静默取最后一个，校验器查不到；
  要查得在装载前用 `JsonReader` 逐 token 扫，本阶段没做。

### 4.1 一个顺带查出来的现状（已用执行证实，非推断）

`ai_weather` / `network_search` / `database` / `location` 这四个工具，`action` 参数在模型可见 schema 里
**没有结构化枚举值**（`ParamDefinition.getEnumValues()` 返回 null）。枚举只写在 description 文案里
（`AIToolManager:1278`/`1306`/`1374`/`1423` 的"操作类型: a/b/c"）。原因：这四个都命中
`getToolDefinition` 的硬编码 switch 分支，而那些分支用的是四参 `addParameter`。

影响：校验器第 3 档的枚举校验对它们只能降级为 WARN。给这几段补上六参
`addParameter(..., Arrays.asList(...))` 后，**校验器不用改一行就自动升级为硬校验**。
这条现状被写成了一条特征化断言（`actionParamEnumsAreNotDeclaredOnTheHardcodedSwitchPath`），
补枚举时它会红，提醒同步把 WARN 分支和这条断言改掉。

两个附带发现：

- `PARAM_ENUM_OVERRIDES`（`AIToolManager:2081`）只在注解路径
  `createToolDefinitionFromAITool:2056` 生效，**覆盖不到 switch 分支**，所以里面对 `ai_weather`/`location`
  补的枚举对这四个工具是空转。
- `AIToolManager:2089` 注释说"location 是本工程无 switch 定义、走注解路径的核心工具"——
  与 `:1417` 存在的 `case "location":` 矛盾，**该注释已过期**。这决定了不能靠那张表来给引导提供枚举，
  必须补 switch 分支。

## 5. 迁移时会带出的三处重复

- `QuickToolChipModule.addQuickChips`（`:92-94`）硬编码"🚗 出行准备/📚 学习查询/🔍 网页研究"三个字面量，
  而 `CompositeGuideFlow.getAllFlows()` 已有对应的 `icon`+`displayName`+`flowId`。
  收编 JSON 时这段必须改成从 `getAllFlows()` 生成 chip，否则同一份数据三处副本。
  这也是 `displayName` 为空被定为 ERROR 的原因——chip 文案在收编后没有别的来源。
- `deducePickerType` 有两份逐字重复：`ToolParamResolver:66`（public static）与
  `AIChatActivity:3042`（private，被 `:2887` 用）。迁移时只留一处。
- `fillQuickInput`（`AIChatActivity:1918-1929`）只填充+聚焦不发送，而 `view_chat_empty_v2.xml:6`
  注释写"填入并发送"。与本迁移无关，顺手记一下。

## 6. 产出物

| 文件 | 状态 |
|---|---|
| `src/main/assets/tool_guide_flow.json` | 随包档：terms 段已被消费；tools 段在 flag 打开时被消费；composites 段暂无消费者 |
| `src/main/java/com/oilquiz/app/ai/agent/ToolGuideFlowConfig.java` | 配置态 DTO（Gson 反序列化目标），无行为 |
| `src/main/java/com/oilquiz/app/ai/agent/ToolGuideFlowValidator.java` | 校验器，纯 JVM 无 Android 依赖 |
| `src/main/java/com/oilquiz/app/ai/agent/ToolGuideTerms.java` | 词典查找器（值对象，三层查找，查不到返回 null） |
| `src/main/java/com/oilquiz/app/ai/agent/ToolGuideLabels.java` | 应用器：把词典套到已构造的流程上，只改 label |
| `src/main/java/com/oilquiz/app/ai/agent/ToolGuideConfigLoader.java` | 只读装载器：filesDir → assets，有 ERROR 整份拒绝 |
| `src/main/java/com/oilquiz/app/ai/agent/ToolGuideFlowFlag.java` | PoC 开关（`ai_chat_prefs/guide_config_json`，出厂关） |
| `src/main/java/com/oilquiz/app/ai/agent/ToolGuideFlowConverter.java` | 配置态 → 运行时 `ToolGuideFlow`，每次新建对象 |
| `src/main/java/com/oilquiz/app/ai/agent/ToolGuideFlow.java` | 改动两处：`getFlow` 前置配置档 + `buildDynamicFlow` 末尾接翻译 |
| `src/test/java/com/oilquiz/app/ai/agent/ToolGuideFlowValidatorTest.java` | 37 条（Robolectric，读随包真文件） |
| `src/test/java/com/oilquiz/app/ai/agent/ToolGuideLabelsTest.java` | 9 条（纯 JVM，翻译器口径） |
| `src/test/java/com/oilquiz/app/ai/agent/ToolGuideFlowConverterTest.java` | 12 条（配置 vs builder 的 A/B） |
| `src/test/java/com/oilquiz/app/ai/agent/ToolGuideFlowFlagTest.java` | 4 条（开关三条语义 + 命名空间） |
| `src/test/java/com/oilquiz/app/ai/agent/GuideFlowDiff.java` | 测试辅助：两个运行时流程的逐字段 diff |

改到 `src/main/assets` 是因为 `terms` 段现在有了真实的运行时装载方
（`ToolGuideConfigLoader`），不再是一份没人读的文件；放 `src/test/resources` 会变成
"测的是副本、跑的是另一份"。`tools`/`composites` 两段随包但暂无消费者，
迁移落地即接上——文件 11KB。

## 7. 翻译器（terms 段）

### 7.1 它到底在防什么——以及今天的可达性

`ToolGuideFlow.getFlow(toolName)` 的 default 分支走 `buildDynamicFlow()`，从模型可见 schema
自动生成步骤。那里有两个地方把原始字段名当显示文字用：

- `ToolGuideFlow:155` —— `new GuideStep.Option(enumVal, enumVal)`，label 直接等于枚举原文。
  选项卡显示的是 `opt.label`（`AIChatActivity:2150` 之后那段"右侧 label 文字"），
  所以用户会看到 `list`／`get`／`air_quality`。
- `ToolGuideFlow:150` —— 参数没描述时标题退化成 `"填写 " + p.getName()`，于是标题是
  "填写 file_path"。

**但这条路今天点不到**（2026-10-28 实测口径，不是推断）：`getFlow` 全仓只有一个调用方
`AIChatActivity:1940`，而它的入口是 `QuickToolChipModule.bindStaticToolChips`（`:61-66`）
绑死的 6 个 chip —— `ai_weather`/`network_search`/`database`/`file_reader`/`location`/`app_operation`，
**六个全都有手写 builder**；聚合工具包走 `CompositeGuideFlow`，也是手写的。
所以 `buildDynamicFlow` 目前是**潜伏代码**，不是线上可见的缺陷。

结论要说白：**翻译器是预防性的**，它让"抽屉扩到全部工具/新增 chip"这件事不必再欠一遍债
（`knowledge_base` 就是现成的例子：它是唯一在 switch 分支里声明了 `action` 枚举、
又没有手写引导的工具，一旦给它开入口，12 个原始值会立刻涌到界面上）。
但也别把它记成线上缺陷：`knowledge_base` 今天没有 chip 入口，界面上还看不到这些原始值。

### 7.2 三层查找与三条安全线

查找顺序：`byTool[工具][参数][值]` → `values[值]` → 都没有返回 `null`（调用方保持原文）。
工具级存在的理由就是同值不同义：`list` 在知识库里是"列索引"、在 memory 里是"列出"。
Java 侧刻意**不内置**任何兜底词条——再塞一份硬编码词典就又成两个权威。

`ToolGuideLabels.applyTo(flow, terms)` 钉三条：

1. **只改 `label`，永不改 `value`。** 改错 value 工具侧直接报"未知操作"。测试逐条比对改写前后的
   value 序列完全相同。
2. **只在 `label` 与 `value` 全等（= 原始值直接露出）时改写。** 这条守卫让手写中文 label
   绝不会被词典覆盖：`("搜一下","search")` 即使全局档有 `values["search"]="搜索"` 也不动。
   用真种子词典跑 `ai_weather`/`network_search`/`database` 三个手写流程，label 序列零改动。
3. **空串值永不参与翻译。** `""` 是"不传该参数"的有意语义（自动定位），不是可译的 token；
   即使有人把 `""` 写进词条也不生效。

查不到的保持原文并计数（`untranslatedOptions`），装载时落一条 `Log.i`——宁可露原始值，
也不编一个可能错的名字，同时让"还差多少词条"可见。

### 7.3 装载与失败档

`ToolGuideConfigLoader`：`filesDir/tool_guide_flow.json`（真机可直接推一份改词条）→
`assets/tool_guide_flow.json`，UTF-8 显式解码，进程内缓存，`invalidate()` 重读。
照 `ProviderConfigManager` 的查找顺序，但**只读不写**——没有 SharedPreferences 双保险，
也不回写文件：落盘属于显式选择，读一个显示名词典不该带写盘副作用。

失败档是**整份拒绝**：文件缺失 / Gson 解析失败 / 校验报任何 ERROR → `terms()` 返回
`EMPTY`，界面完全维持今天的行为。（已用 `filesDir` 塞坏配置的用例证过这条路。）

接线点只有一处：`ToolGuideFlow.buildDynamicFlow` 末尾
`ToolGuideLabels.applyTo(flow, ToolGuideConfigLoader.terms(appContext))`。
这一段 JVM 验不了（Robolectric 下 `SmartQuizApplication.getAppContext()` 是 null），属真机项。

## 8. PoC 档（flag + 三工具接管，2026-10-28 第三步）

### 8.1 形状

开关：`ToolGuideFlowFlag`，prefs 命名空间 `ai_chat_prefs`、键 `guide_config_json`、
**出厂档 = 关**。写法照 `PartsRendererFlag`/`HtmlSubmitFlag`（含"读不到 Context 按默认档"）。
产品里没有设置页入口——PoC 阶段刻意不给它一个显眼的按钮，开关只能显式写 prefs。

接管点：`ToolGuideFlow.getFlow(toolName)` 在进 builder switch 之前先问
`tryFlowFromConfig(toolName)`：开关关 / 拿不到 Context / 配置缺失或有 ERROR 被装载器整份拒绝 /
配置里没列这个工具——四种情况一律返回 null 继续走 builder。**失败模式是"维持现状"，不是"引导打不开"。**

装载器运行时只跑**第 1、2 档 + 术语表规则**（`ToolGuideFlowValidator.validate(config)` 不注入 probe）。
刻意不在运行时查跨 schema 那一档：那要把工具注册表拉起来，一旦"注册表还没就绪"就变成
"整份配置被拒"，那才是真风险；而 paramKey 拼错这种问题在 CI/测试里已经能钉住（§4 第 4 条）。

PoC 覆盖面 = 配置文件里列了什么，Java 侧没有白名单：现在 `tools` 段是
`ai_weather`/`network_search`/`database` 三个，所以打开开关也只接管这三个，
`location`/`file_reader`/`app_operation` 等有 chip 入口但没列进配置的照旧走 builder。
**聚合方案（`composites` 段）本轮不接管**，`CompositeGuideFlow` 一个字没动。

### 8.2 转换层的两条硬约束

**每次调用新建对象。** `GuideStep.paramValue` 是运行时槽位，用户每选一步 AIChatActivity 就往
当前步骤对象上写值（`:2300-2304`、`:2363`）。配置可以缓存，流程不能缓存——缓存实例等于
让两次引导共享同一批步骤对象，后开的那个会看见前一个的已选值。已用测试钉住。

**各类型只填 `GuideStep` 工厂方法本来会填的字段。** 工厂之间字段覆盖不一致：
`imagePickerStep` 强制 `mimeTypes={"image/*"}`（配置写了 `application/pdf` 也不生效）、
`directoryPickerStep` 根本不设 `mimeTypes`/`allowMultiple`、`optionStep` 根本不设 `hint`/`required`。
转换器"顺手都填上"就会产出现行 builder 不会产出的字段组合，而渲染层是按这些字段分支的。

### 8.3 A/B 证据（62 条用例，`skipped=0 failures=0 errors=0`）

`ToolGuideFlowConverterTest` 拿随包真文件对 builder 做逐字段 diff（`GuideFlowDiff`）：
三个工具的 `type/title/description/paramKey/hint/required/multiline/allowMultiple/mimeTypes/
conditionKey/conditionValue/每个 option 的 label+value/dynamicOptions 五个字段/paramValue 必须为 null`
全部相等。**不止比数据形状，还比过滤行为**：把每个工具的每个 `action` 取值喂进
`getActiveSteps`，两侧出来的步骤序列必须一致——"|" 回填只要差一点，界面上就是某一步莫名不见了。

`ToolGuideFlowFlagTest` 钉开关三条语义（无键=关、显式 true 才进、null Context 按默认关）。
另有源码文本钉（`getFlow` 里必须有 flag 判定 + 转换器调用 + builder switch 仍在 ≥13 个 case），
风格同 `LocalAgentCancelPathTest`：结构不能回退。

### 8.4 真机待验清单

下面六条我一条都没跑，都是真机项。

JVM 里验不了接线那一段：Robolectric 的 Application 被 `robolectric.properties` 换成裸
`android.app.Application`，`SmartQuizApplication.getAppContext()` 返回 null，flag 走默认档=关。

1. 出厂态先确认一次：点天气/搜索/数据库 chip，引导面板与今天完全一致（flag 没写就是关）。
2. 打开开关（需先 force-stop，否则 app 内存里的 prefs 会把改动盖回去）：
   `adb shell am force-stop com.oilquiz.app`
   `adb shell "run-as com.oilquiz.app sh -c \"grep -c ai_chat_prefs /data/data/com.oilquiz.app/shared_prefs/ai_chat_prefs.xml\""`
   确认文件存在后，在里面加一行 `<boolean name="guide_config_json" value="true" />`（放在 `</map>` 前）。
   这条命令序列我**没有实机跑过**，引号与 `sed` 写法要现场按设备调整；更稳的做法是你熟的那套
   run-as 读写流程。
3. 逐个点天气 / 联网搜索 / 题库三个 chip，走完一遍引导：选项文案、条件分支（搜索选"问一问"该出
   `想问什么?`+`要多详细?`，选"读网页"该出 `网页地址是?`）、执行结果是否与改前一致。
4. 读日志确认走的是哪条路：配置路成功时没有 `ToolGuideFlow` 的 WARN；
   若出现 `配置化引导取用失败，回退硬编码 builder` 或
   `引导配置有 N 个 ERROR，整份拒绝`，说明回退生效（界面仍可用，只是没走配置）。
5. 关掉开关再来一遍，确认第 3 步与第 1 步一致（可回退性）。
6. 顺带核一下 `terms`：给 `knowledge_base` 开一个入口（比如临时加只 chip 绑它）才能验到翻译层，
   目前它没有入口，所以 §7.1 说的原始值暴露今天是看不到的。

## 9. 待拍板

1. **`condition` 用结构化 `{key, values[]}` 还是照抄 POJO 的 `conditionKey`+`"a|b"`？**
   我选了前者（§2.1 的理由）。要零风险就选后者。
2. **是否先给 switch 分支补 `enumValues`？** 这是让枚举校验从 WARN 转 ERROR 的唯一办法，
   但它改的是模型可见 schema（顺带也改善模型对可选值的感知），影响面比纯引导大。
3. **聚合方案与单工具引导放一个文件还是两个？** 我按一个写（chip 与流程同源）。
4. **术语表要不要现在就扩到全部工具？** 现在只种了 `knowledge_base`(12) + `memory`(5) +
   全局 3 + 参数名 10。`memory`/`python_chart` 的枚举来自 `PARAM_ENUM_OVERRIDES`，
   那条路能不能被 `getToolDefinition` 取到还没实测（§4.1），所以我先按"能露出来就种上"处理。
   要一份完整清单，得先让抽屉能开到那些工具——即 §7.1 说的入口 widening，那是另一个决定。
