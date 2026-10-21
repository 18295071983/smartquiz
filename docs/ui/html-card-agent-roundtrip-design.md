# 设计：让本地 HTML 卡把用户操作回传 Agent（js 桥 submit）

状态：**待批准**（未开工）。日期 2026-10-21。分支 `feat/chat-parts-renderer`。
关联：`component-system-review.md §7`（可操作三档清单）、`parts-architecture-upgrade.md`（内置组件靠 `ComponentRegistry` 自包含）。
安全前提：记忆 `webview-jsbridge-security`——桥只挂低危方法、不暴露 execTool/queryDb；本方案不改这条。

## 1. 背景与现状（都核到 file:line）

聊天流 `html` 卡 = `HtmlCardView` → `HtmlWebView`，内容信任级由 `HtmlSandbox.Trust` 四档决定，**能力由档位决定、不由 props 说了算**（`HtmlSandbox.java:18-30`、`:57-66`）：

| 档位 | 谁的 HTML | JS | 桥 | 文件访问 |
|---|---|---|---|---|
| `INTERNAL_TEMPLATE` | 应用内置模板（mermaid / katex，靠 `ComponentData.internalTemplate` 标记） | 开 | **无** | 关 |
| `LOCAL_FILE` | Agent 生成在工作区白名单内的本地 HTML（报表） | 开 | **有** | 仅该文件自身 |
| `REMOTE_PAGE` | https 网页（`web` 组件） | 开 | **无** | 关 |
| `INLINE_UNTRUSTED` | **模型直接给的 HTML 字符串** | **关** | **无** | 关，+CSP/大小上限 |

现有桥 = `HtmlCardView.HtmlJsBridge`（挂名 `Android`，`HtmlWebView.BRIDGE_NAME:23`；挂载在 `HtmlCardView:113-114`，闸门 `HtmlSandbox.allowsBridge`＝`trust == LOCAL_FILE`，`:444-445`），只暴露 4 个方法：`showToast:609` / `copy:619` / `openLink:633`（外链放行、本地路径必须在工作区内否则 Toast 拒）/ `previewImage:661`。

既有回传通道（本方案**复用、不新造**）：`ComponentActions.dispatchResult(context, componentId, value):144` → `notifyResult:164`（先查按 id 注册的 `componentCallbacks:169`，否则回退全局 `resultCallback` 并 `ensureResultCallback:109-131` 自动补注册）→ `PythonToolManager.notifyChatComponentResult:330` → `setComponentResult:4784` → 唤醒 `ui_component(action=get_result)`（`SystemUIComponentTool:291/:305`）。

**缺什么**：桥里没有 `submit/sendResult/postMessage` 这类通往上述通道的方法，所以 HTML 页面内的按钮/表单**点得动、值回不来**。富桥 `app/webview/js/*`（题目库/TTS/share/request）同样零命中，也没有现成实现可搬。结论与 `component-system-review §7` 一致：`html` 卡现在只能待在"档二 仅本地交互"。

**注意已有的部分能力**：若卡创建时带 `props.actions`，`ComponentRegistry.render:195-221` 会给 html 卡**也**附一条 footer 按钮行，那条按钮是能回传的（走 `perform:249-273`）。所以本方案补的是**页面内部**控件的回传，不是"从零才有往返"。

## 2. 目标 / 非目标

目标
- `LOCAL_FILE` 档的 HTML 报表能把用户操作（选中的点、表单值、图表 drill-down）送进**同一个** `get_result` 回收口。
- 不新增第二套通道、不新增第二种 id 约定。

非目标（明确不做）
- **不给 `INLINE_UNTRUSTED` 开任何回传**：模型直给的 HTML 连 JS 都是关的，这是提示注入面，开了就等于把"任意字符串→写入 Agent 结果"的通路交给模型输出。
- 不给 `INTERNAL_TEMPLATE`（mermaid/katex 不需要）和 `REMOTE_PAGE`（远程页拿不到桥，维持现状）开。
- 不暴露 `execTool/queryDb/数据库写入/任意 Intent`（见记忆 `webview-jsbridge-security`）。
- 不改 JS→Java 之外的能力（不做 Java→JS 主动推送）。

## 3. 方案

### 3.1 id 从哪来（这里纠正一次早前的口头说法）

回传地址**不是** `ComponentData.id`（那是 COMPONENT 子 id，如 `T1-C1`，用于会话内定位/更新组件），而是工具注入的 `props.actions[].component_id`：
`SystemUIComponentTool` 生成 `componentId = "chat_<ts>_<rand>"`（`:632`），**仅当 `props.has("actions")`（`interactive`，`:630`）** 才注册回调（`:636`）并把 id 注入到每个 action（`:643-653`）；`LayoutCardView` 就是按"第一个 action 的 `component_id`"取址（`:60-70`）。

→ **本方案沿用同一条约定**：把这段取址抽成一处公共方法（建议 `ComponentActions.resolveComponentId(ComponentData)`，`LayoutCardView` 改为调用它），避免长出第二套口径。

推论（需要你确认的行为边界）：**只有被当作交互组件创建的 html 卡（带 `actions`）才能 submit**。没有 `actions` 的 html 卡没有可寻址的 component_id，也没有注册回调，`submit` 直接 no-op。
- 方案 A（推荐）：接受这个边界，不改生产者。模型要可交互的 HTML，就按现有规矩带上 `actions`。
- 方案 B：让工具无条件把 `component_id` 写进 `props` 顶层，使任意 html 卡都可回传。**这会改生产者在册行为、并让"没打算交互的页面"也获得写入口**，不推荐现在做。

### 3.2 桥扩一个方法

```
HtmlJsBridge(HtmlJsBridge(Context ctx, String componentId))   // 现只收 context（:604）
@android.webkit.JavascriptInterface
public void submit(String payload)
```
JS 侧用法：`Android.submit(JSON.stringify({action:'pick', point:'A3'}))`。

硬性约束（安全与一致性）
1. **JS 不能指定 componentId**——只由 native 在构造时绑定它自己那张卡的 id。否则任意页面可往别的组件结果槽里写。
2. `componentId == null || 空` → 直接 return（不 Toast 骚扰）。
3. `payload` 当作**不透明字符串**：不解析、不执行、不进 `openLink`；仅做长度校验后转发。**上限建议 16 KB**，超限 Toast 拒（与 `MAX_INLINE_HTML_CHARS` 同思路，档位上限见 `HtmlSandbox:54`）。
4. 转发只走 `ComponentActions.dispatchResult(context, componentId, payload)`（`:144`）。payload 形状沿用 `perform` 的既有约定：纯值，或 `{"action":..., "inputs":{...}}`（`:260-269`），这样 Agent 侧 `get_result` 的解析不用分两套。
5. 只在 `HtmlSandbox.allowsBridge(trust)` 为真时桥才存在（`:113-114` 不动）；挂载条件本身不变，所以档位面没有扩大。
6. 与 footer 按钮一致的点击反馈：`Toast`（`perform:257` 已有先例），避免"点了没反应"。

### 3.3 改动清单（预估很小，但都在共享生产路径上）

| 文件 | 动作 | 规模 |
|---|---|---|
| `component/HtmlCardView.java` | `HtmlJsBridge` 加 `componentId` 字段 + `submit`；构造处 `:113-114` 传入取到的 id | ~25 行 |
| `component/ComponentActions.java` | 新增 `resolveComponentId(ComponentData)`（把 `LayoutCardView:60-70` 的取址逻辑收成一个家） | ~15 行 |
| `component/LayoutCardView.java` | 改调公共取址（行为不变） | ~8 行改写 |
| `html` 类型的模型侧说明 | 目录/工具文案补一句"带 `actions` 的 html 卡内可用 `Android.submit(payload)` 回传"（`ComponentTypes.SCHEMA` 的 html 行、`SystemUIComponentTool` props 说明） | 文案 |

不动：`HtmlSandbox` 档位判定、`allowsBridge`、`ChatAdapter` legacy 挂载、`parts` 侧任何渲染器（html 卡经 `DataPartRenderer`/`ProductCards` 委派到同一个 `ComponentRegistry.render`，桥自包含挂载，且 `ChatPartsHost.context()` 已是 Activity → **接管侧零改动**）。

## 4. 安全评估（要一起点头的部分）

- 攻击面变化：给"Agent 产在工作区内的本地 HTML"增加一条**写入自己那张组件结果槽**的能力。可达性仍受 `LOCAL_FILE` 判定约束（模型直给的字符串拿不到桥）。
- 最坏情况推演：一段被提示词污染的工具生成 HTML，可以对本卡 component_id 反复 `submit` 伪造值，或塞入长文本。它**不能**指定别的 component_id、不能执行原生方法、不能获得新的文件/剪贴板权限。
- **诚实说明这是能力增量**：今天 footer 按钮回传的是创建时写死在 `props.actions[].value` 里的静态值（`perform:260`）；本方案让页面 JS 在**运行时**提交任意字符串。差别是"值由谁在何时决定"从工具作者一侧移到了页面内容一侧。后果限于"这张卡的 `get_result` 值可能来自被污染的页面内容"，不外溢到执行/数据写入。
- 缓解：id native 绑定、payload 不透明 + 16 KB 上限 + 不派发进任何执行路径、Toast 让"已提交"对用户可见、只有 `LOCAL_FILE` 档存在桥。
- 残余风险：`get_result` 拿到的仍是模型可控字符串，Agent 若据此执行高影响动作需上层自证——这条今天就成立（工具输出本身也可被污染），但 `submit` 让"页面内容"也成为值的来源之一，值得在工具描述里提醒模型对该来源做校验。

## 5. 验证计划

JVM/Robolectric 能钉死的（占大部分）
1. `resolveComponentId`：有/无 `actions`、多个 action 取第一个、`component_id` 为空的退化。
2. `submit` 路由：先 `ComponentActions.registerComponentCallback(fakeId, capture)`，再直接调 bridge.submit → 断言捕获到 payload 且 id 一致；空 id 不派发。
3. `submit` 上限：>16 KB 被拒（不派发）；边界值通过。
4. 档位不扩大：`HtmlSandbox` 对四档的 `allowsBridge` 断言只有 `LOCAL_FILE` 为真（若已有此测则复用）。
5. 回归：`LayoutCardView` 改调公共取址后，其输入收集行为不变（现有测试 + 新增断言）。

必须真机的（JVM 证不了）
6. 真实 WebView 里 `Android.submit(...)` 能被调用、值到 `get_result`（JS↔native 边界 + 主线程投递）。
7. 长 HTML 报表内滚与聊天列表手势不抢；提交后卡不重建、不丢滚动位置。
8. 与 parts 接管共存：flag=true 时 html 卡（`display=inline/peek` 两档）仍能提交。

## 6. 落地与回滚

- 顺序：先 `resolveComponentId` 抽取 + 测试（纯重构，可单独提交）→ 再 `submit` + 测试 → 再文案。各一个提交，遵本项目"逐阶段一提交"纪律（不 `git add -A`，同步 `CHANGELOG.md` 与 `HANDOVER.md`）。
- 开关：建议加 prefs 开关 `ai_chat_prefs/html_component_submit`，**默认 false**；真机验通后再翻默认。理由：动的是 legacy 与 parts 共用的生产路径，且手机当前离线无法端到端验；关掉时 `submit` 不挂载，行为与今天逐字节一致。
- 回滚：关开关即回到现状；重构部分（取址公共方法）无行为变更，可独立保留。

## 7. 需要你点的四个决策

1. **id 来源取 A 还是 B**（推荐 A：只带 `actions` 的 html 卡可回传，不改生产者）。
2. **payload 上限 16 KB** 是否可以（或按你期望的图表数据规模调）。
3. **默认 false 的 prefs 开关**要不要（推荐要；不要的话就必须等真机才能合）。
4. **是否把"页面内可提交"写进模型可见说明**（推荐要，否则模型不知道能用，只会继续退回 layout 卡）。
