# 32 · DSH 事件绑定矩阵（2026-11-14）

> 用户口径：「dsh 历史事件你没绑定好。根据你刚刚拉取到的事件类型给我通通绑定。
> 看看我 app 的架构，帮不上的新建组件绑上。」

## 1. 权威清单从哪来

DSH 安装包里有一份**生成**的事件目录（`scripts/gen-persistence-catalog.ts`）：

```
app.asar/dsh/node_modules/@deepseek-ai/dsh-session/src/known-event-types.ts
  KNOWN_SESSION_EVENT_TYPES   —— 本 build 认识的全部 SessionEvent 类型（63 种）
```

用 `build/asar_tool.js` 抽出来对照（工具在仓库里可复现）。插件侧另有一份
`/v2/events/catalog`（带 layer/forward 标记），供运行时诊断。

## 2. 对照结果（改之前）

| | 数量 |
|---|---|
| DSH 已知事件类型 | **63** |
| `DshTimeline.reduce` 直接处理 | 19 |
| `DshCards.handles` 出卡 | 37 |
| 只是塞进 `IGNORED_TYPES`（不渲染、也不报未知） | 13 |
| **连忽略名单都没进**（一直算"未知类型"） | **3**：`subagent/catalog`、`feedback/record`、`session-log-deepseek/delivery-accepted` |

其中 6 类**本该给用户看**却被当成"纯内部"忽略了：
`agent/inbox/spliced`（插话/排队入队）、`subagent/catalog`（子代理启动）、
`tool/ptc-dispatch*`（代码内工具调用）、`hook/invoked|result`（钩子执行）、
`image/offload`（图片移出上下文）、`model/selection`（模型切换）。

## 3. 改之后：63/63 有明确归属

组件全部复用现有渲染器（`dsh_notice` 就是架构里的"生命周期提示"通用卡：
`{title, text|content, level}`，左侧色条 + 标题 + 说明），**没有为绑定硬造新类型**；
新增的两个 helper 只是在 `DshCards` 里把事件翻译成这张卡的 props。

| 归属 | 事件类型 |
|---|---|
| **时间线直接处理**（正文/步骤/工具/审批/反问） | `user/message`、`turn/start`、`turn/end`、`step/start`、`step/end`、`assistant/message`、`tool/call`、`tool/result`、`session/title`、`plan/mode`、`schedule/change`、`approval/asked`、`approval/decided` |
| **`dsh_notice` 提示卡**（原有） | `agent-preset/selected`、`approval/policy`、`assistant/attempt`、`compaction/start|summary|prune|end`、`developer/message`、`feedback/message-put|message-delete`、`goal/change`、`llm/retry`、`llm/retry-started`、`permission/preset`、`sandbox/mode`、`subagent/descriptor`、`system/message`、`team/mode|task|member|message/queued|message/delivered`、`working-directory/change`、`workspace/changes` |
| **`dsh_notice` 提示卡**（2026-11-14 新绑定 7 类） | `agent/inbox/spliced`（"插话已入队 · 下一步生效/本轮结束后"＋内容）、`subagent/catalog`（"子代理 · 标签"）、`tool/ptc-dispatch`、`tool/ptc-dispatch-start`（"代码内调用 · 工具名"）、`hook/invoked`、`hook/result`（"钩子 · 名称"）、`image/offload`（"图片已移出上下文"）、`model/selection`（"模型 · 名称"） |
| **专用卡** | `todo/write`→`todo_card`、`deliverables/presented`→`file_list`、`command/run|done`→`dsh_command`、`job/registered|settled`→`dsh_job`、`tool-workflow/*`→`workflow_card` |
| **内部事件**（不渲染，登记为已识别） | `request/header`、`request/context`、`session/end-seed`、`session/title-llm-request`、`subagent/model-selection-policy`、`web/deepseek-search-llm-request`、`feedback/record`、`session-log-deepseek/delivery-accepted` |

## 4. 单测把关

`src/test/java/com/oilquiz/app/ai/remote/DshEventCoverageTest.java`（6 个用例）：

1. `everyKnownEventTypeIsBound`：把 63 种类型逐个喂给 `DshTimeline.reduce`，
   断言 `unknownEventKinds() == 0` —— **以后升级 DSH、名单多了新类型，这个测试先红**；
2. 插话入队：`next-step` → "电脑下一步就会看到：…"；`next-turn` → "电脑本轮结束后发送：…"；
3. `subagent/catalog` → "子代理 · 调研"；
4. `tool/ptc-dispatch` → "代码内调用 · pwsh"；
5. `hook/*`、`image/offload`、`model/selection` 都出卡且内容正确。

升级 DSH 后的动作：重抽 `known-event-types.ts` → 更新测试里的 `KNOWN` 数组 →
跑这个测试 → 缺哪个补哪个（出卡 or 加进 `IGNORED_TYPES`）。

## 5. 顺带修掉的"设计问题"

用户说的"转换器设计没设计好"落在两处，这次都收口了：

1. **过滤口径**：实时镜像与历史导入共用 `DshHistoryImporter.isHumanUserMessage`
   （见 30 §10）——不再是两套规则；
2. **未知事件黑洞**：`IGNORED_TYPES` 以前是"没实现就扔进去"，把该显示的也扔了。
   现在分两类：`DshCards.handles()` 里能出卡的一律出卡，`IGNORED_TYPES` 只放真正
   线协议/记账类，且**必须有注释说明为什么不需要渲染**。

## 6. 提示卡的点击交互（2026-11-14）

`dsh_notice` 正文最多 3 行（`maxLines=3`），而这次新绑定的几类事件信息量恰恰在正文里
（插话内容、子代理模式、钩子输出、图片移出说明）。现在的交互：

| 卡片 | 点击 | 长按 |
|---|---|---|
| `agent/inbox/spliced`（`jump=last`） | **滚到末尾那条**"正在电脑上执行 / 排队中"的气泡 | 查看全文 + 复制 |
| `subagent/catalog`（`jump=subagent` + `childId`） | **滚到该子代理的轨迹卡**（`subagent_card`） | 查看全文 + 复制 |
| 其他 `dsh_notice` | 查看全文弹窗（可选中 + 复制） | — |

实现（"组件拿不到页面"这个老问题）：
- 新增 `ComponentHosts`：`Host{scrollToMessage(id), scrollToLast(), openSubagent(childId)}`，
  由聊天页在初始化时 `set(...)`、`onDestroy` 时 `clear()`；
- 用静态挂载而不是改 `ChatComponent.createView(context, data)` 签名：组件是 `ComponentRegistry`
  里的**单例**，给实例塞 host 会串消息；而改签名要动 40 个实现 + 6 处渲染工厂，收益不成比例。
  语义与既有的 `ComponentActions` 静态回调一致；
- 卡片通过 `props.jump`（`last` / `subagent`）声明自己的跳转目标，渲染器只认这两个值，
  没有时就退化成"查看全文"。

## 7. 子代理：一创建就有卡（2026-11-14）

以前只有 `subagent/activity` 会建轨迹条目，于是"子代理刚启动、还没跑工具"这段时间甲板卡槽是空的，
`subagent/catalog` 的提示卡点了也没地方跳。

- `DshTimeline` 新增 `case "subagent/catalog"`：按 `childId` 立刻登记 `Subagent`（label + mode），
  后续 `subagent/activity` 往同一个条目里填步骤；
- `DshProjection` 把 `mode` 也投影进 `subagent_card.props`；没有步骤时卡片显示
  **「已启动 · 模式：continuable」**（而不是空白）；
- `subagent/catalog` 不再产出通用 `dsh_notice`（同一件事不出两张卡），
  `DshCards` 里对应的分支与 `handles()` 条目已删；
- 测试：`subagentCatalogRegistersTraceCardImmediately` —— 只喂一条 catalog 事件，
  断言 `subagents()` 有 1 条、投影后有 `subagent_card`（含 childId/mode）、没有 `dsh_notice`。

## 8. 发送方式三档（2026-11-14，对齐 dsh-Go）

**长按发送键** → 「发送方式」对话框：

| 选项 | 落点 | 说明 |
|---|---|---|
| 插话 · 不打断电脑，下一步就看到 | `inject mode=steer` → DSH `next-step` | 补充要求、纠偏 |
| 中断并发送 · 先停下电脑当前任务 | `cancelGeneration()` 后按 `steer` 发 | 方向错了要立刻转向 |
| 排队 · 电脑本轮结束后发送 | `inject mode=queue` → `next-turn` | 不急，等当前回复说完 |

- 选择会写进 `busy_send_mode`（下次忙时沿用），与 dsh-Go 的"生成中直接发送=插话"一致；
- `RemoteDshTool.injectDetailed(ctx, target, text, mode)` 新增 mode 参数（默认 `auto`）；
- `sendToComputer(text)` 保留（=auto），新增 `sendToComputer(text, mode)`。

## 9. dsh-Go 桥接的鉴权（结论）

`dsh-go-bridge` 的所有路由（含 `/dsh-go/v1/*` 与 WS）都先走 DSH 自己的
`connection.requestRejection(req)`：

```
admit(request):
  1) isTrusted(request)            —— Host/Origin 围栏（防 DNS rebinding / 跨站）
  2) browserAuth.isAuthenticated   —— 浏览器登录令牌 cookie
  3) browserAuth.authenticateApi   —— API 凭据（x-dsh-auth-token / Bearer）
```

也就是说：**它不额外发配对码**，用的是 DSH Web 同一把锁。所以手机上的 dsh-Go App 要连这台电脑，
需要填 `http://<电脑局域网IP>:61294` 并带上 DSH 的登录令牌/API 凭据，而不是我们在
`smartquiz-remote` 里做的那套设备令牌（`/pair/code` → `/pair/claim`，走 8218）。

两条路互不干扰：我们的 App 走 8218 + 设备令牌；dsh-Go App 走 61294 + DSH 凭据。

## 10. 手机端"出不出卡"的口径（2026-11-16 订正）

判据只有一条：**插件 `EVENT_CATALOG` 的 `forward` 标记**（桥是唯一传输权威）。
app 侧只给"桥真的会转发、且用户该在聊天里看到"的类型保留出卡分支；其余一律不出卡。

| 类别 | 事件 | 手机侧处理 |
|---|---|---|
| durable 内容 | `todo/write`、`deliverables/presented`、`workspace/changes`、`command/*`、`tool-workflow/*`、`subagent/descriptor`、`working-directory/change`、`system/message`、`developer/message`、`team/*`、`tool/ptc-dispatch*` | 出卡（`code_card`/`alert_card`/`tool_call`/`todo_card`/`file_list`/`steps`） |
| 临时状态（插槽） | `job/registered`、`compaction/*`、`llm/retry*`、`goal/*`、`permission/preset`、`sandbox/mode`、`approval/policy`、`agent-preset/selected`、`agent/inbox/spliced` | 不出卡，登记 `IGNORED_TYPES`；由插件 `ui/slot` 驱动甲板 |
| 任务收尾 | `job/settled` | **仅失败/取消**出 `job` 耐久卡；成功只走插槽（完成即消失） |
| 内部事件（桥不转发） | `hook/invoked`、`hook/result`、`feedback/message-put`、`feedback/message-delete`、`model/selection`、`assistant/attempt` | 不出卡，登记 `IGNORED_TYPES`（也不计未识别） |
| 提示类 | `image/offload` | 2026-11-16 起插件改 `forward:true`，手机出一张 `alert_card` |

为什么这样定：此前 app 给 `hook/*`、`feedback/*`、`model/selection`、`assistant/attempt`
留了出卡分支，但桥标的是 `forward:false` —— 手机永远收不到，属于"假能力"；
`DshEventCoverageTest` 当时还反过来要求它们可见。另一头，`job` 的"完成即消失"把
**失败**也一起吞了，任务挂了用户看不到。两处都按上表收口，回归用例在
`DshEventCoverageTest` / `DshEventWiringTest` / `DshTimelineTest`。
