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
| **R1** | **动态 UI 走唯一渲染器**：`UIComponentPluginTool` 注册的组件类型，渲染时统一交给 `NativeLayoutRenderer`（layout 树），不再各自拼 View | 一套渲染逻辑；新增节点自动可用；主题/token 自动生效 | 中（要保证旧 props 语义兼容） |
| **R2** ✅ | **参数校验统一**：`DynamicToolParams` 合并到 `ParamDefinition` + 现有 schema 生成，删除并行体系（**核查后确认早已实现**，本轮补测试钉住） | 模型看到的 schema 只有一份真值 | 中 |
| **R3** | **动态工具注册走 AIToolManager**：`DynamicToolManagerTool` 不再自建注册表，改为 `registerToolFactory(name, DynamicAITool.class, factory)`，懒加载/超时/活动总线自动继承 | 权限分级、审计、预算天然覆盖动态工具 | 中高（要处理热更新/卸载） |
| **R4** | **表达式统一**：动态工具的参数表达式、条件模板一律走 `ExpressionEngine`（已带缓存 + 安全边界） | 安全边界一致（禁 new/赋值） | 低 |
| **R5** | **校验统一**：动态 layout 用 `cardcheck` 的校验器（422 + 路径诊断），不再自己写检查 | 错误信息一致、可预览 | 低 |
| **R6** | **结果渲染统一**：`DynamicToolResultRenderer` 里手写的卡片分支改为复用 `ComponentTypes` 已有渲染器 | 少一套渲染分支 | 低 |

## 2. 分步实施（每步可独立验收）

### 第 1 步（R5 + R4，低风险，先做）

1. `LayoutEditorTool` / `UIComponentPluginTool` 的"保存前校验"改为调用 `cardcheck` 校验器；
2. 动态工具里所有"对字符串表达式求值"的地方改调 `ExpressionEngine.evalBoolean/eval`。

验收：新增单测——坏的 layout 被 cardcheck 拦下（返回路径级错误）；含 `new File()` 的表达式被拒。

### 第 2 步（R2，中风险）✅ 已完成（2026-11-16）

1. 给 `DynamicToolParams` 加一个转换器：`toParamDefinitions()`；
2. `DynamicAITool.getParameterDefinitions()` 直接返回它；
3. 确认线上 schema 与旧版**逐字段等价**（用现有 `ToolSchemaVisibilityTest` 兜底），再删旧分支。

验收：schema 对比测试（旧 JSON 快照 vs 新），全部一致。\n\n**结果**：核查发现 `DynamicToolParams` 内部本来就用 `List<ParamDefinition>` 承载类型，\n`DynamicAITool.getParameterDefinitions()` 也优先返回它、旧数据才回退全 string —— R2 实质已完成。\n本轮补 `DynamicToolParamsTest`（4 例：类型/必填保留、枚举保留、JSON 往返、旧 map 回退）钉住行为。

### 第 3 步（R1，中风险，收益最大）

1. 给 `UIComponentPluginTool.create` 的产物加"渲染契约"：插件定义里必须能映射成 layout 树
   （或直接声明 `layout` 模板）；
2. 渲染入口从"按 type 找 View 工厂"改为 `NativeLayoutRenderer.render(layoutJson)`；
3. 保留旧路径一个版本作为 fallback（`mode=legacy` 开关），观察一轮再删。

验收：预览台里用插件生成的组件与手写 layout 渲染结果像素级一致（截图对比）；旧插件定义零改动可渲染。

### 第 4 步（R3，中高风险，最后做）

1. `DynamicToolManagerTool` 的注册表改为 `AIToolManager.registerToolFactory`；
2. 动态工具默认权限级别 → `ToolPermissions` 里新增 `DYNAMIC` 归类（保守按 EXEC）；
3. 卸载/热更新通过 `AIToolManager.unregister`（需新增）实现。

验收：动态工具在 Agent 设置页的权限分级里可见；卸载后不再出现在 schema 中。

## 3. 不做的事

- **不重写 `NativeLayoutRenderer`**：它已覆盖 132 种节点，重构它的收益远小于风险；
- **不动 `DynamicCardView`**：它已经走 `ComponentTheme`（进而 `ComponentColors` → 主题 attr），
  属于"已经接好"的样板；
- **不动动态工具的预算机制**：`DynamicToolBudget` 是运行时保护，与新基建无冲突。

## 4. 验收方式（统一）

每步都必须：编译 + 相关单测 + 真机截图（预览台 / 主题自检页）+ 一次 commit。
真机入口沿用：`--es mode ui_kit` / `list_kit` / `compose_gallery` / `theme_settings` / `theme_audit`。
