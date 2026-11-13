# DSH 远程 UI · 单测与验收

- 版本：v2.0（2026-11-13）
- 上位文档：`00-overall.md`、`10-modules.md`

---

## 1. 测试基线

| 项 | 当前值 |
|---|---|
| 单测套件 | 273 |
| 用例数 | 1959（含本轮新增 5 条） |
| 失败 | 0 |
| 运行命令 | `.\gradlew.bat testDebugUnitTest --console=plain *> log`，结果看 `build/test-results/testDebugUnitTest/*.xml` |

**注意**：Gradle 偶发 `Failed to execute ... AsyncCacheAccessDecoratedCache` 噪音，退出码仍为 0 时以 XML 计数为准。

---

## 2. 本轮已交付的单测

| 类 | 用例 | 覆盖 |
|---|---|---|
| `DshTimelineToolCallFieldTest` | `acceptsCamelCaseCallId` / `acceptsSnakeCaseCallId` / `acceptsToolCallId` / `acceptsSnakeCaseToolCallId` / `missingCallIdProducesNoCard` | 转换器字段容错（B 类缺陷的前置保障） |

已有且相关：`DockSlotBusTest`(6)、`RemoteStatusSnapshotTest`(8)、`DshTimelineTest`(4)、`PromptAssemblerTest`(13)、`OnlinePromptBuilderAssemblyTest`(11)、`LocalAgentCancelPathTest`(13)、`LocalHistoryTrimWatermarkTest`(6)、`OnlineAgentEngineHistoryTrimWatermarkTest`(7)、`LocalRoundMetricsTest`(5)。

---

## 3. 待补单测（按模块，实现时同步写）

### M1 状态甲板
| 用例 | 断言 |
|---|---|
| `d1VisibleCapsD3ToOne` | D1 可见 + 3 条插槽 → 只渲染 1 条 |
| `d1HiddenAllowsTwo` | D1 隐藏 → 渲染 2 条 |
| `emptyDeckIsGone` | 三档全空 → `visible=false` 且未回调 viewport |
| `visibilityAlwaysNotifiesHost` | 任一档变化 → `onDeckVisibilityChanged` 恰好一次 |
| `endGenerationClearsSlots` | `clear()` 后插槽列表空 |

### M2 动态卡
| 用例 | 断言 |
|---|---|
| `propsKeyOrderDoesNotChangeFingerprint` | 两种 key 顺序的 props → 指纹相同（B3） |
| `componentIdIsPrefixedByTurnId` | 同一 card.key 在 T3/T8 下得到不同 id（B2） |
| `unknownKindFallsBackToDynamicCard` | 未注册 type → 渲染不抛、非 null |
| `layoutPropsListedInComponentData` | `type=layout` 的 `ComponentData` 可落盘并读回 props |

### M4 交互上行
| 用例 | 断言 |
|---|---|
| `dshSourceGoesToRemote` | `Source.DSH` → 调用 `sendComponentAction` |
| `localSourceGoesToPython` | `Source.LOCAL` → 调用 `notifyChatComponentResult` |
| `emptyActionIdIsIgnored` | 空 action → 两条路都不调 |

### M3 配置表
| 用例 | 断言 |
|---|---|
| `olderOverrideIsIgnored` | 覆盖档版本旧于随包 → 用随包 |
| `corruptJsonFallsBack` | 坏 JSON → 内置兜底，不抛 |
| `invalidateNotifiesOnce` | `invalidate()` → 监听器各回调一次 |

### M7 落盘恢复
| 用例 | 断言 |
|---|---|
| `remoteCardViewSurvivesSave` | 保存副本里 `remoteCardView` 非空（B9） |
| `dsnBubbleIdEqualsSubId` | DSH 气泡 `id == subId` 且形如 `T{n}-A{k}`（B6） |

---

## 4. 真机验收清单

| # | 场景 | 步骤 | 期望 | 证据 |
|---|---|---|---|---|
| A1 | 甲板不挤聊天区 | 固定设备脚本，记录 `status_deck.getHeight()` 与 `messageList.getHeight()`；分别在"本机 agent"与"DSH 远程"两种基线下测 | deck ≤72dp；视口减少 ≤12%；D3 ≤1 条 | 脚本输出（不用截图） |
| A2 | 插槽无残留 | `StatusDeckControllerTest(FakeHost)`：注入 3 slot → DSH `turn/end` → 断言 `DockSlotBus.list()` 为空并输出 reason | 清空且只清 transient | 单测 |
| A3 | 贴底不裁切 | pinned 状态下断言 `onViewportChanged(true)` 恰好一次、`lastChild.bottom≈height-padding`、`!canScrollVertically(1)` | 不裁切 | 单测 + 截图各一张 |
| A4 | 动态卡渲染 | 服务端下发一个 `layout` 卡片帧 | 手机显示对应界面，无需发版 | 截图 + `/v2/view` 返回 |
| A5 | 表单回传 | 点动态卡里的提交按钮 | 电脑端会话出现 user 消息 | 电脑端时间线截图 |
| A6 | 审批回传 | 手机点允许/拒绝 | 电脑端生效；失败则回滚成待处理 | 两端截图 |
| A7 | 配置生效 | 推一份新 `dsh_ui_catalog.json` 到 filesDir | 重进会话后版式变化；损坏则回退 | `AILogger.audit` |
| A8 | 落盘恢复 | 发一条带动态卡的对话后杀进程重进 | 动态卡仍在、id 不变；远程卡不空 | 重启前后截图 + 会话 JSON |
| A9 | caps 一致 | 用**只声明 `events.stream`** 的 caps 请求 GET 与 SSE 两条通道（全 caps 时 `filterViewCards` 是恒等映射，会假阳性） | 两者都等于 `filterViewCards(cards, reduced)` | 抓包对比 + caps 矩阵（每个 cap 必须有构造点+渲染器） |

---

## 5. 缺陷回归对照（B1–B13，归属以 `99-review-findings.md` 为准）

| 缺陷 | 回归手段 |
|---|---|
| **B11（P0，丢数据）** | `approvals`/`questions` 未进 `deepCopyForSave`/`clone` → 保存副本里为空，重启后 pending 审批消失 | 新单测断言 `approvals.size()` 保存前后不变 |
| **B12（P0）** | `/v2/view` 远程卡重复且不可交互（信号只在进程内） | 删除后重放不再重复插卡、无空条目 |
| **B13（P2）** | 位置索引与归档冲突（头部删除后 `notifyItemChanged(pos)` 可能刷错行） | 插入用户消息后再刷新，断言刷的是正确行 |
| B1 `DockSlotBus.clear()` 无调用方 | M1 `endGenerationClearsSlots` + A2 |
| B2 `dshComponentId` key 无 turnId | M2 `componentIdIsPrefixedByTurnId` |
| B3 props 指纹不排序 | M2 `propsKeyOrderDoesNotChangeFingerprint` |
| B4 回传前缀嗅探 | M4 `dshSourceGoesToRemote` + A5 |
| B5 `pushView` 绕过 caps | A9 |
| B6 DSH 气泡 id≠subId | M7 `dsnBubbleIdEqualsSubId` |
| B7 `RemoteCardView` 审批点击未接线 | A6（若改为废弃该卡，则删用例） |
| B8 caps 声明无实现 | A9 + 代码审查（声明与构造点一一对应） |
| B9 `remoteCardView` 未补拷 | M7 `remoteCardViewSurvivesSave` + A8 |
| B10 会话文件无 fsync/.bak | 可选：崩溃注入测试（手动 kill -9 后校验 JSON 可解析） |

---

## 6. 回归门禁

1. `testDebugUnitTest` 全绿（0 failures / 0 errors）。
2. 新增/修改的每个模块至少 1 条 JVM 单测（甲板控制器、ActionSink、DshUiCatalog 均为纯逻辑，可测）。
3. 每次迭代提交前跑一次全量，并附 XML 计数作为证据。
4. 涉及布局的改动必须附真机截图（推理中 + 空闲各一张）。

---

## 7. 尚未覆盖（如实记录）

- `AIChatActivity` 内的折叠/窗口逻辑目前**无单测**（逻辑在 Activity，需先抽类才能测）——M1 抽 `StatusDeckController` 时会顺带解决甲板部分。
- 插件侧（`index.js`）无自动化测试，只能做语法检查（`node --check`）+ 真机验收。
- 布局组件的渲染（`NativeLayoutRenderer`）依赖 Android 视图，暂不做 JVM 单测，靠真机 A4 兜。

---

## 4. 已落地（2026-11-13 晚）

| 用例 | 落地方式 | 备注 |
|---|---|---|
| A1 | DeckMetrics 埋点 + 	ools/verify_deck.ps1 | 真机实测 deck=42dp / chat=489dp / share=8%（阈值 12%）|
| A2 | StatusDeckControllerTest（7 条） | 进度可见 1 条 / 否则 2 条 / 排序稳定 / 空 id / 零上限 / clear / 配置覆盖 |
| A3 | StickToBottomA3Test（2 条，Robolectric） | pinned 视口变化后贴底；pinnedBefore=false 时位置不动 |
| A4（部分） | DshProjectionLayoutTest（4 条） | meta.layout → layout 组件，children/actions 原样透传；**未真机验证** |
| A7（部分） | DshUiCatalogLoaderTest（5 条） | 默认/合法/坏 JSON/越界/缺 deck |
| A8（部分） | ChatHistoryManagerAtomicSaveTest + ChatHistoryManagerDeepCopyTest | 原子写与补拷；动态卡恢复未验 |
| A9（插件侧） | oute_selftest（22 条） | 目录三层 / UI 目录 / 作用域 / 重连补发 / 裁剪 |

### 仍未落地

- A4/A5/A7/A8 的**真机**验证（需要一份真实带 meta.layout 的工具结果 / 覆盖配置文件）
- A6 审批回传专门用例
- B 表：componentIdIsPrefixedByTurnId（B2 复核未加断言）、dsnBubbleIdEqualsSubId（B6）、
  B10 崩溃注入（kill -9）
### 子代理 durable 卡（B15，2026-11-13 晚）

| 用例 | 断言 |
|---|---|
| DshSubagentDurableTest（6 条） | 调用+结果合成一步、失败态、多子代理隔离、12 步上限、无 callId 归并、**换气泡继承轨迹** |
| DshProjectionSubagentCardTest（3 条） | props 契约（label/steps/source=dsh/id 稳定）、持久化往返、无子代理不产卡 |
| DshSubagentCardRenderTest（2 条，Robolectric） | 注册表能拿到实现（不掉 JSON 兜底）、标题/步数/「+N 步」/lastText 兜底 |

真机证据：iles/ai_chat_history.json 内出现 {"id":"T478-C1","type":"subagent_card",...}；
插件侧 subagent/activity 帧数从 **0 → 44**（子会话日志轮询合成）。