# 26 · 客户端基础设施：让"只改插件"成为可能（方案 · 待确认）

> 用户口径：「先把客户端的基础设施做全了，以后我只需改远程插件就行了，
> 远程插件可以给客户端插件自动更新。」
>
> 我理解的目标（若理解错请纠正）：
> **手机 = 通用渲染器 + 通用执行器；所有功能与界面定义都在远程插件里。**
> 以后加功能只改插件（推一份新的视图定义/动作表），手机不重新发版就能用上。

---

## 1. 为什么现在的架构做不到"只改插件"

| 现状 | 问题 |
|---|---|
| 卡片是手机里的 Java 类（`DshCardViews`、`DshCards` 映射） | 新增一种卡必须发 APK |
| 甲板槽位是手机里的 `DockSlotBus.Slot` 构造 | 新增一类槽位必须发 APK |
| 点击动作是 `onDockSlotClick(String)` 里的 if-else | 新增一个动作必须发 APK |
| 专用卡按事件硬编码 | 事件变成卡片的规则写死在手机 |

也就是说：**手机里写的是"业务"，插件只是数据源**。要"只改插件"，必须把"业务"搬到插件，手机只留"能力"。

---

## 2. 目标架构（三层）

```
插件（远程，唯一改的地方）
  ├─ 视图定义 JSON（v1 schema）：stack/row/text/callout/steps/list/actions/options/confirm/keyValue/progress/table
  ├─ 动作表：action id → 插件 handler → 返回 effects
  └─ 版本号 + 更新推送（/ws/ui 推 view_updated）
        │
        ▼
手机（通用，几乎不改）
  ├─ ViewRenderer：JSON → 原生 View（组件白名单 + 预算校验）
  ├─ EffectExecutor：effects 白名单（insert/command/open/link/toast/copy/refresh/patch/open_file/notification）
  └─ 传输层：多端点 WS（events/ui/alert/file/ctl）+ 健康灯 + 自愈
```

**手机只认"组件类型"和"效果类型"，不认业务。**
新功能 = 插件推一份新的视图定义 + 一组 action handler，手机端零改动。

---

## 3. 客户端要补的基础设施（按依赖排序）

### C1 · 视图渲染器 `ViewRenderer`（地基）

- 输入：JSON 组件树（`{v:1, type:'stack', children:[…]}`）；
- 输出：原生 View；
- 组件白名单（第一批 10 个）：`stack/row/text/callout/steps/list/actions/options/confirm/keyValue`；
- **预算**：JSON ≤32KiB、组件 ≤120、层级 ≤8、`list` ≤50 行、`actions` ≤4 个；超限降级（截断/灰占位），不整块崩；
- 未知组件 → 灰色「这部分无法显示」，其余照画。

### C2 · 效果执行器 `EffectExecutor`

- 白名单 9 种：`insert` / `command` / `open`（页面或文件）/ `link`（只 https，弹确认）/ `toast` / `copy` / `refresh` / `patch` / `open_file{path}`；
- 一次 ≤4 个、**点按后 3 秒内有效**；
- 未知效果丢弃并记日志；
- `open_file` 走 `/ws/file` 拉流 → 缓存 → `FileRenderActivity`。

### C3 · 动作通道 `invoke`

- 手机：点按 → `/ws/ui` 发 `{type:'invoke', action, value}`；
- 插件：handler 返回 effects（或 `pending` + 结果）；
- 复用现有 `ComponentActions` 的本地执行逻辑，但**执行权在手机**（插件只建议）。

### C4 · 甲板也变成"插件定义的插槽"

- 现在 `DockSlotBus.Slot` 由手机代码构造；
- 改成：插件推 `{type:'slot', id, kind, board, title, detail, action, warn, count}`，手机只做校验+渲染；
- 手机保留的能力：`kind` 语义（toggle/action/display/list）、`board` 分组、固定/动态规则。

### C5 · 插件热更新（"自动更新"）

| 层 | 内容 |
|---|---|
| 视图定义 | 插件启动时推 `{type:'view_def', version, views:{…}}`；手机缓存到本地，下次冷启动先用缓存再等新版本 |
| 版本协商 | `hello` 里带 `client_caps`（支持哪些组件/效果）+ 插件带 `view_version`；不匹配时插件降级推送（只推手机认识的组件） |
| **APK 自更新** | 插件 `/status` 里带 `app_release {version, apk_url, sha256}`；手机检查到更高版本时提示「有新版」→ 下载 → 校验 → 安装。这条能让你"只改插件"就发新客户端 |

> C5 的 APK 自更新是"客户端也能被更新"的兜底：视图渲染器本身要大改时，仍需要一个新 APK；
> 但日常的界面/功能变化，走 C1–C4 就够，不必发版。

---

## 4. 与已有设计的关系

| 已有 | 关系 |
|---|---|
| 23 号专用卡 | **保留为"高频、要极致性能"的特例**；其余走 `ViewRenderer`。不是全部重写 |
| 22 号甲板四类 | `kind/board` 语义保留在手机，槽位内容改由插件推 |
| 21 号 WS 端点/动作 | C3 的 invoke 就是它；白名单加 `open_file` |
| 25 号通道健康 | 独立，不冲突 |

---

## 5. 分阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| **C1** | `ViewRenderer` + 10 个组件 + 预算校验 + 单测 | 单测：10 组件的 JSON→View 快照；超预算降级 |
| **C2** | `EffectExecutor` + 9 种效果 + 3 秒窗口 | 单测：白名单/越权/超时丢弃 |
| **C3** | `invoke` 往返（手机→插件→effects→手机执行） | 真机：点一个插件推的按钮，效果真的执行 |
| **C4** | 甲板槽位改由插件推（手机只校验渲染） | 真机：插件推一个新 slot，手机显示；删掉后消失 |
| **C5** | `view_def` 下发 + 本地缓存 + `client_caps` 协商 | 真机：插件改一份视图定义，手机重启后生效，无需换 APK |
| **C6** | APK 自更新（可选，兜底） | 真机：插件报新版本 → 手机提示并可安装 |

---

## 6. 风险

| 风险 | 处置 |
|---|---|
| JSON 视图把手机拖慢 | 预算上限 + 组件白名单 + 默认折叠；高频卡仍走 23 号专用卡 |
| 插件推的视图有错 | 逐组件降级（灰占位），不整块崩；未知组件忽略 |
| 版本不匹配 | `client_caps` 协商；插件只推手机认识的组件 |
| 视图定义被篡改 | 只在已配对/已认证连接上接收；本地缓存带校验和 |
