# AI 组件与布局控件系统设计文档

> 版本: 1.0 | 覆盖: 原生 layout 控件框架 / 内置卡片 / 动态画布 / 插件与类型注册 / 控件查询 / 连续输入表单
> 关联实现: `ai/python/NativeLayoutRenderer.java`, `ai/chat/component/*`, `ai/tool/SystemUIComponentTool.java`, `ai/tool/UIComponentPlugin*`, `ai/tool/LayoutEditorTool.java`, `ai/tool/ControlLookupTool.java`, `ai/python/LayoutTemplateRegistry.java`

## 一、系统概览

答题宝 AI 组件的 UI 体系分**两条互相配合的路**，均以 JSON 声明驱动，Agent 无需写 Java 代码即可构建任意原生界面：

1. **内置卡片（ComponentRegistry 聊天流卡片）**：如 `table_card`/`chart`/`info_card` 等，渲染为聊天消息块，Agent 用 `ui_component(action=create, component_type=卡片类型, props={...})` 创建。
2. **原生 layout 控件框架（NativeLayoutRenderer）**：JSON 控件树 → 真实原生 View（弹窗/画布/聊天卡片内），支持容器、展示、数据、图表、输入、选择、交互等 **~60 种控件**，可任意嵌套。

```
Agent 决策
   │
   ├─ ui_component(action=create, component_type=内置卡片) ──→ 聊天流卡片
   ├─ ui_component(action=create, component_type=xxx, layout={控件树}) ──→ 原生弹窗/画布
   ├─ ui_component(action=create, component_type=layout_canvas, layout={...}) ──→ 常驻画布(可编辑)
   ├─ ui_component_plugin(action=create, name=插件名, render={layout}) ──→ 注册复用插件
   ├─ ui_component(action=register_type, name=类型名, render={layout}) ──→ 注册复用类型
   ├─ layout_editor(action=set/add/patch/get, component_id=画布id) ──→ 编辑画布
   └─ control_lookup(action=search, keyword=控件名) ──→ 查询低频控件参数
```

## 二、原生 layout 控件框架（NativeLayoutRenderer）

### 2.1 数据结构

一个 layout 是 JSON 控件树，顶层可为 `{"root": {...}}` 或直接节点（自动包 column）：

```json
{
  "root": {
    "type": "column",
    "spacing": 12,
    "children": [
      {"type": "text", "text": "标题", "bold": true, "size": 18},
      {"type": "input", "hint": "输入姓名", "key": "name"},
      {"type": "row", "justify": "space_between", "children": [
        {"type": "button", "text": "提交", "action": "submit"},
        {"type": "button", "text": "取消", "action": "cancel"}
      ]}
    ]
  }
}
```

### 2.2 控件分类

| 分类 | 控件 |
|---|---|
| **容器** | `column`(纵向) `row`(横向) `scroll`(滚动) `card`(圆角卡片,title) `wrap`(流式换行) `grid`(网格,columns) `space`(弹性空白) `tabs`(标签页) `stack`(层叠,gravity) `accordion`(折叠面板) `carousel`(轮播) |
| **展示** | `text`(bold/size/color/align) `image`(url) `marquee`(跑马灯,speed 0~3) `badge`(徽章) `avatar`(头像) `avatar_group`(头像组) `quote`(引用) `code`(代码块) `icon`(图标) |
| **数据** | `table`(headers/rows) `steps`(步骤条) `timeline`(时间线) `alert`(提示条,样式用 alert_type/variant) `stat`(指标卡) `empty`(空态) `notice`(通知条) `progress_ring`(环形进度) |
| **图表** | `line_chart`(折线) `bar_chart`(柱状) `pie_chart`(饼图) `sparkline`(迷你趋势) |
| **工具** | `qrcode`(二维码) `barcode`(条形码) `countdown`(倒计时) `calendar`(日历) `breadcrumb`(面包屑) |
| **媒体** | `video`(url/src, ExoPlayer) `audio`(播放条) `html`(富文本, WebView) |
| **输入** | `input` `number` `password` `multiline` `otp(length)` `email` `tel` `url` `search` `search_bar` `tag_input(tags)` |
| **选择** | `select` `switch` `checkbox` `checkbox_group` `radio` `radio_group` `date` `time` `datetime` `color` `rating(1~5)` `toggle`(胶囊开关) `dropdown` `stepper` `slider_range` |
| **交互** | `button`(text/action) `link`(url/action) `slider` `progress` `spinner` |
| **文件** | `file`(key/label) |
| **装饰** | `divider` `divider_v` `separator` |

### 2.3 通用属性

- `width`/`height`：`match`/`fill`(铺满)、`wrap`(自适应)、数字(dp)、`"50%"`(仅 wrap/grid 内)
- `margin`：数字或 `{top,left,bottom,right}`
- `weight`/`flex`：弹性比例（row/column 用 weight，wrap/grid 用 flex）
- `align`：`start`/`center`/`end`；容器 `spacing`(子项间距)/`alignItems`(对齐)/`justify`(flex_start/flex_end/center/space_between)
- 任意 `text` 支持 `{key}` 占位符：从 props 替换

### 2.4 值收集与回调

- **带 `key` 的控件值**（input/number/password/multiline/otp/email/tel/url/search/file/select/switch/checkbox/radio/slider/date/time/datetime/color/rating/progress）在布局内 `button` 提交时统一收集为 JSON
- `button` 的 `action` 作为点击结果回传；`link` 的 `action` 同 button
- `button` 支持 `{action, tool, tool_params}`：`tool`=后端工具名，点击直接调后端，`tool_params` 中 `{key}` 占位符替换为控件值

### 2.5 自定义控件模板（define/use）

layout 顶层 `define={模板名: 节点树}` 可定义可复用控件，树内 `{"use": "模板名", "props": {...}}` 引用，模板内 `{key}` 由 props 替换：

```json
{
  "define": {
    "stat_item": {"type": "card", "title": "{label}", "children": [{"type": "text", "text": "{value}", "bold": true}]}
  },
  "root": {"type": "column", "children": [
    {"use": "stat_item", "props": {"label": "收入", "value": "¥1000"}},
    {"use": "stat_item", "props": {"label": "支出", "value": "¥200"}}
  ]}
}
```

### 2.6 类型解析与嵌套优先级（实测可用）

渲染 layout 节点时，type 按以下优先级解析：

1. **内置控件**（上表 2.2）
2. **已注册组件类型名**（`register_type`/插件/模板）→ 作节点 type 嵌套，自动展开其 `render.layout`，节点 props 覆盖模板占位：
   `{"type": "online_music_player"}`
3. **未注册类型但节点自带 layout**：`{"type":"my_widget","layout":{...}}` 或 `{"type":"x","render":{"layout":{...}}}` → 现场展开（等效临时注册）
4. **临时 layout**：`create(component_type=任意未注册名, layout=完整树)`（顶层/props/render 三传法等效）→ 不注册即用，仅本次有效
5. 都没有 → 显示"⚠ 未知控件"提示

## 三、内置组件卡片（ComponentRegistry）

聊天流卡片，Agent 用 `ui_component(action=create, component_type=卡片类型, props={字段})` 创建，直接渲染进聊天消息。

常见卡片：`chart`(bar/line/pie) `table_card` `list_card` `grid_card` `metric_card` `info_card` `alert_card` `steps_card` `todo_card` `note_card` `json_viewer` `code_card` `link_card` `image_grid` `file_card` `file_list` `contact_card` `quiz_card` `weather_card` `progress_card` `html` `markdown_card`；别名 `web`=网页卡片、`image`=图片卡片。

卡片可加 `actions=[{"label":"文字","value":"回传值","action":"callback"}]` 收集用户点击，`get_result` 取回。

## 四、注册复用体系（插件 / 类型 / 模板）

三类注册，均 `persist=true` 默认落盘跨重启保留：

| 注册 | 工具/动作 | 定义 | 复用方式 |
|---|---|---|---|
| **组件插件** | `ui_component_plugin(action=create)` | name+description+params(参数schema)+render(card/layout)+monitor+persist | `create(component_type=插件名)`；参数校验/类型转换/监控自动 |
| **组件类型** | `ui_component(action=register_type)` | name+description+render(card/layout)+monitor+persist | 轻量别名，`create(component_type=类型名)` 或 layout 节点 type 嵌套 |
| **layout 模板** | `ui_component_plugin(action=register_layout)` | name+layout+description+persist | 任意 layout 内 `{"use":"模板名","props":{...}}` 引用 |

### 持久化文件
- `files/ui_component_plugins.json` — 插件
- `files/ui_component_types.json` — 类型
- `files/layout_templates.json` — layout 模板
- `files/dynamic_tools.json` — 动态工具

### 生命周期
- `persist=true`（默认）：长久落盘可复用
- `persist=false`：临时仅内存，任务结束消失（`clear_temporary`/`clear_temporary_types`/`layout_clear_temporary` 清理）

## 五、动态画布（layout_canvas + layout_editor）

常驻布局画布，Agent 可动态编辑控件树。

### 5.1 创建画布

```
ui_component(action=create, component_type=layout_canvas, layout={完整布局JSON}, title=标题)
→ 返回 component_id
```

**重要**：create 时就要带**完整含输入控件的 layout**（每个 input/select/switch/date/number 必须带 `key`，button 必须带 `action`），否则控件无法收集值。

### 5.2 编辑画布（layout_editor 工具）

| 动作 | 说明 | 参数 |
|---|---|---|
| `set` | 整体替换布局 | `layout`=完整树 |
| `add` | 追加**单个控件节点**（勿传含 children 容器） | `node`=控件JSON（兼容 layout/item/child），`container`=路径(默认root)，`index` |
| `patch` | 修改/删除节点 | `key` 或 `path` 定位；`remove=true` 删除；`props` 合并属性 |
| `get` | 查看当前结构 | - |
| `rebuild` | 强制重渲染 | - |

**注意**：全程用**同一个 `component_id`**，不要反复重建画布；`add` 的 node 必须是单个控件。

### 5.3 会话与渲染

- `LayoutCanvasManager` 注册画布会话（component_id → layout 状态 + 重渲染回调）
- 每次编辑后画布从最新 layout **整树重渲染**，输入控件值**自动回填**（`NativeLayoutRenderer.applyValues`）
- 画布弹窗内 Button 点击时（`collectButtons` 收集）回传 `{action, values}` 到 result
- 弹窗关闭时 unregister 会话

## 六、连续输入表单（custom + rounds）

`custom` 类型 props 内加 `rounds=N(N>1)` 进入多轮连续输入：

```
ui_component(action=create, component_type=custom,
  props={fields:[{key:姓名,type:text,required:true},{key:金额,type:number}], rounds:3})
```

- 弹窗含「添加下一条」（收集本轮并清空重建继续）+「完成」（收集本轮并结束）
- `get_result` 返回 `{"rounds":[{第1轮值},...],"total":N}`

## 七、控件参数查询（control_lookup）

系统提示词已列出高频控件完整清单。低频控件（视频/音频/图表/二维码/日期等）可用 `control_lookup` 按关键词查询参数，避免凭记忆猜字段：

```
control_lookup(action=search, keyword=video)   → 返回该控件 type/name/params
control_lookup(action=list)                     → 列出全部低频控件索引
```

内置 35+ 种低频控件词库（video/audio/html/marquee/qrcode/stepper/slider_range/dropdown/stack/accordion/carousel/calendar/breadcrumb/progress_ring 等）。

## 八、Token 与工具注入策略

- **系统提示词**：完整控件清单常驻（每轮随消息发送，但前缀一致 → 命中 prompt cache 按折扣计费，不差 token 时无需拆分）
- **工具定义**：核心工具集（`ui_component`/`file_generator`/`workspace`/`memory`/`tool_registry`/`permission_manager`/`ai_weather`/`network_search`/`calculator`/`control_lookup`）固定注入 + 按用户消息意图追加低频工具
- **历史摘要压缩**：超预算把旧消息压成摘要（`HISTORY_TOKEN_BUDGET`）
- **工具结果截断**：超 16KB 截断

## 九、关键文件索引

| 文件 | 职责 |
|---|---|
| `NativeLayoutRenderer.java` | layout 控件树 → 原生 View；值收集/回填/模板/嵌套展开 |
| `ComponentRegistry.java` | 内置卡片注册与渲染 |
| `LayoutCardView.java` | layout 树渲染为聊天流卡片 |
| `LayoutPrimitiveCardView.java` | 单控件布局卡片（聊天流） |
| `SystemUIComponentTool.java` | `ui_component` 工具（create/update/close/get_result/register_type/list_types/remove_type） |
| `UIComponentPluginTool.java` | `ui_component_plugin` 工具（插件/layout 模板管理） |
| `UIComponentPluginManager.java` | 插件注册表（持久化/校验/生命周期） |
| `UIComponentTypeRegistry.java` | 类型注册表（持久化/reload） |
| `LayoutTemplateRegistry.java` | layout 模板库（合并 defines/reload） |
| `LayoutCanvasManager.java` | 画布会话注册表 |
| `LayoutEditorTool.java` | `layout_editor` 工具（set/add/patch/get/rebuild） |
| `ControlLookupTool.java` | `control_lookup` 工具（低频控件参数查询） |
| `PythonToolManager.java` | 组件弹窗展示层（custom 表单/画布/插件弹窗/值收集） |
