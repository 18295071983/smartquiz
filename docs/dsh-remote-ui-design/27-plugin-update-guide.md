# 远程插件更新指南（手机端不改版）

> 适用：给 SmartQuiz 手机端加界面/功能，**只改远程插件**（`tools/dsh-plugin-smartquiz-remote/index.js`），
> 手机不用重新发版。前提是手机已装含"通用视图渲染器"的版本（C1/C2，2026-11-14 起）。
>
> 端到端数据流：
> ```
> 插件 ──推视图 JSON──▶ 手机 ViewRenderer 画出来
>        ◀──invoke(action, value)──  用户点按
> 插件 ──回 effects[]──▶ 手机 EffectExecutor 白名单校验后执行
> ```

---

## 1. 推一个界面

### 1.1 最简单的两种推法

| 目的 | 帧 | 说明 |
|---|---|---|
| 消息流里出一块界面 | `{type:'ui_component', data:{id, props:{…}}}` | props 里放视图树，见下 |
| 输入框上方出一行状态 | `{type:'ui/slot', data:{id, title, detail, action, priority, warn}}` | 甲板槽位，`action` 是动作 id |

推送方式（插件里）：

```js
streamBroadcast(sid, { type: 'ui_component', data: { id: 'deploy-card', props: view } });
// 或直接在 /cmd 队列里排一条（会被 WS 直推到手机）
```

### 1.2 视图语法（组件树）

根节点必须是 `stack`（或任意单组件）：

```json
{ "type": "stack", "children": [ … ] }
```

| 组件 | 写法 | 限额 |
|---|---|---|
| `stack` | `{type:'stack', children:[…]}` 竖排 | 子 ≤16 |
| `row` | `{type:'row', children:[…]}` 横排 | 子 ≤16 |
| `text` | `{type:'text', text:'…', tone?:'muted'}` | 2000 字 |
| `callout` | `{type:'callout', text:'…', tone:'info'\|'good'\|'warn'\|'bad'}` | 2000 字 |
| `steps` | `{type:'steps', items:[{label, status:'done'\|'current'\|'todo'\|'failed', detail?}]}` | ≤12 项 |
| `list` | `{type:'list', items:[{title, subtitle?, trailing?, tone?, action?:{id,args}}]}` | ≤50 行 |
| `actions` | `{type:'actions', items:[{label, action:{id,args}, style?:'primary'\|'plain'}]}` | ≤4 个 |
| `options` | `{type:'options', id:'pick', options:[{label}]}` | ≤8 个 |
| `confirm` | `{type:'confirm', id:'clear', confirmLabel?, cancelLabel?}` | — |
| `keyValue` | `{type:'keyValue', items:[{label, value}]}` | ≤24 项 |

**预算（手机侧硬校验）**：组件 ≤120、层级 ≤8；超限**降级**（截断/灰占位），不会整块崩。
未知组件 → 灰色「这部分无法显示」，其余照画。

### 1.3 例子：一个部署卡

```js
const view = {
  type: 'stack',
  children: [
    { type: 'callout', text: '生产环境 · 3 个实例', tone: 'warn' },
    { type: 'steps', items: [
        { label: '构建', status: 'done' },
        { label: '部署', status: 'current' },
        { label: '健康检查', status: 'todo' } ] },
    { type: 'list', items: [
        { title: 'api-1', trailing: '12ms', action: { id: 'open_log', args: { pod: 'api-1' } } },
        { title: 'api-2', trailing: '9ms',  action: { id: 'open_log', args: { pod: 'api-2' } } } ] },
    { type: 'actions', items: [
        { label: '查看日志', action: { id: 'open_log' } },
        { label: '重新部署', action: { id: 'redeploy' }, style: 'primary' } ] },
  ],
};
streamBroadcast(sid, { type: 'ui_component', data: { id: 'deploy', props: view } });
```

---

## 2. 处理点按：动作 → 效果

### 2.1 手机发来的帧

```json
{ "type": "invoke", "action": "redeploy", "value": { "pod": "api-1" } }
```

插件在 `/ws/ui` 的 `message` 里接（现有 `handleWsConn` 已有 `ws.on('message')` 分发骨架）：

```js
if (t === 'invoke') {
  const effects = await myHandlers[m.action]?.(m.value) ?? [];
  sendWs(ws, { type: 'effects', id: m.action, effects });
  return;
}
```

### 2.2 可以返回的效果（白名单 9 种）

| 效果 | 写法 | 手机行为 |
|---|---|---|
| 放进输入框 | `{type:'insert', text}` | 插入不发送（1–2000 字） |
| 斜杠命令 | `{type:'command', name, args?}` | 执行命令（name 不带 `/`，args ≤500） |
| 打开页面 | `{type:'open', page}` | 打开 App 内页面 |
| 打开链接 | `{type:'link', url}` | **只收 https** |
| 提示 | `{type:'toast', text}` | 一行轻提示（≤40 字，超长截断） |
| 复制 | `{type:'copy', text}` | 写剪贴板（1–8000 字） |
| 刷新 | `{type:'refresh'}` | 重新取视图 |
| 改数据 | `{type:'patch', data}` | JSON 合并（≤16 KiB） |
| **打开文件** | `{type:'open_file', path}` | 走 `/ws/file` 拉电脑上的文件 → 应用内渲染 |

**硬规则**：一次 ≤4 个效果；**点按后 3 秒内到达才执行**（迟到静默丢弃）；不在白名单的直接丢。
所以：能当场算出来的就当场返回，不要拖。

### 2.3 例子

```js
const myHandlers = {
  redeploy: async (v) => [{ type: 'toast', text: '已开始重新部署' }],
  open_log: async (v) => [{ type: 'open_file', path: '/var/log/api.log' }],
  ask: async (v) => [{ type: 'insert', text: '帮我看看这个报错：' }],
};
```

---

## 3. 用哪条通道推什么

| 端点 | 推什么 | 别推 |
|---|---|---|
| `/ws/events` | DSH 事件、pulse | 别推 UI |
| `/ws/alert` | 审批、提问 | 别推普通状态 |
| `/ws/ui` | `ui_component`、`ui/slot`、`cmd`、`view` | 别推大文件 |
| `/ws/file` | `file_begin/chunk/end` | 别推业务帧 |
| `/ws/ctl` | `batch`/`pong`/`diag`/`repair` | 别推 UI |

推错通道 → 手机会按端点白名单拒收并回 `{type:'error', code:'frame_not_allowed'}`。

---

## 4. 状态槽位（甲板）

```js
streamBroadcast(sid, {
  type: 'ui/slot',
  data: {
    id: 'build', priority: 40, kind: 'list', board: 'task',
    title: '构建', detail: '3 个任务在跑', count: 3,
    action: 'open_builds', warn: false, pinned: true,
  },
});
```

- `kind`：`toggle`（开关）/ `action`（一次动作）/ `display`（只读）/ `list`（点开列表）；
- `board`：`alert` / `task` / `asset` / `state`（一条 board 一行）；
- `pinned: true` → 连上就占位（空值显示"—"）；
- 同 `id` 覆盖更新，`{type:'ui/slot', data:{id, remove:true}}` 移除。

---

## 5. 版本与自更新

| 机制 | 说明 |
|---|---|
| `hello.features` | 每条通道握手时告知该端点能收什么帧 |
| `client_caps`（手机 → 插件） | 手机上报"支持哪些组件/效果"；插件据此降级（只推手机认识的） |
| `view_def`（插件 → 手机） | 推一份视图定义 + 版本号；手机缓存，冷启动先用缓存 |
| `app_release`（插件 → 手机） | 需要新 APK 时，`/status` 里带 `{version, apk_url, sha256}`，手机提示更新 |

日常改界面**不需要** `app_release`，走第 1–4 节即可。

---

## 6. 自检清单（改完插件跑一遍）

1. `GET /status` → `ws_channels` 里 `ui` 有 1 条连接（不是 0、不是 2+）；
2. 手机上看视图是否出现；点一个按钮，看效果是否执行；
3. 故意推一个不存在的组件类型 → 应该只有那一块显示"这部分无法显示"，其余正常；
4. 推 5 个效果 → 只执行前 4 个；
5. 点按后 4 秒才返回 → 效果被丢弃（这是预期）；
6. 看 `/status.ws_stats.<通道>.frames` 是否在涨（证明帧真的送到了）。
