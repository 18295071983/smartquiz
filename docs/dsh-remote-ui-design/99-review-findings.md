# DSH 远程 UI · 交叉审查结论（v2.1 修订依据）

- 日期：2026-11-13
- 方法：5 个子代理并行审查三份设计文档，角度为 事实 / 接口 / 约束 / 计划 / 规范；结论均附 file:line
- 处置：**本文优先于** `00-overall.md`、`10-modules.md`、`90-tests-acceptance.md` 与 `../dsh-remote-ui-architecture-v2-2026-11-13.md` 中被本文更正的部分

---

## 1. 总体结论

| 维度 | 结论 |
|---|---|
| 事实准确性 | **中**：核心机制与代码吻合，但有 9 处引用错误 + 1 处"声称已改其实没改" |
| 接口可实现性 | **低**：三个新接口全部需要调整；`ActionSink` 在现有代码上连触发条件都不成立 |
| 约束完整性 | **不足**：7 条约束里 5 条不够，另有 4 条遗漏；"唯一入口"在现有代码里本就不成立 |
| 计划可行性 | **偏低**：每阶段工期低估约 2 倍；缺一条会丢数据的 B11 |
| 与规范一致性 | **有 13 处冲突/重复**，其中 3 处需要改文档结构 |

一句话：**方向对（接入既有设施），切片错（先建控制器、先做甲板）、数字错（行数/图元/页大小）、漏项（B11）。**

---

## 2. 已修代码（审查直接促成）

| # | 问题 | 处置 |
|---|---|---|
| F1 | **分页 50 从未生效**：提交 `63ad2225` 说明写了 100→50，但该文件在 ID 回滚时被 `git checkout` 连带还原，常量仍是 100 | 已恢复为 50，提交 `3fca3917`，注释说明防再犯 |

---

## 3. 新增缺陷（按严重度）

| # | 缺陷 | 证据 | 影响 | 归属 |
|---|---|---|---|---|
| **B11** | `deepCopyForSave` 与 `ChatMessage.clone()` **都漏拷 `approvals` / `questions`** | `ChatHistoryManager.java:292-341`、`ChatMessage.java:1713-1739` | 重启后 pending 审批直接消失 → "审批可重试"是假验收 | **P0** |
| **B12** | `/v2/view` 远程卡是死路：不可交互、`lastRemoteCardSig` 只在进程内、重启/重放重复插卡、保存后变空条目 | `AIChatActivity.java:13107-13113,13151-13153,13795`；`RemoteCardView` 无 actions | 重复且不可用的第二条渲染路径 | **P0（建议整条删除，B7/B9 随之消失）** |
| **B13** | DSH 位置索引与归档冲突：`maybeArchiveAfterSave` 会从头部删、`safeNotifyItemChanged` 只查边界 | `AIChatActivity.java:8622-8627,14090-14097` | 流式刷新可能刷错行 | P2 |

---

## 4. 缺陷归属修正

| # | 原归属 | 改为 | 理由 |
|---|---|---|---|
| B1 `DockSlotBus.clear()` | P0 | **P1** | 仅被测试调用；且 DSH `turn/end` **不走 `endGeneration()`**（`applyDshEvent:13496-13513` 只标 COMPLETED），在那里补 clear 对 DSH 无效，还会误删仍有效的跨回合 job/goal |
| B2 组件 id 跨回合撞车 | P1 | **P2（低风险）** | 每次新建镜像气泡即 `clear`，key 自带 `tool:<callId>` → **不可复现**，影响被高估 |
| B3 props 指纹不排序 | P1 | **P2** | 属动态卡自身设计前置，非线上缺陷 |
| B4 回传前缀嗅探 | P1 | **P2** | DSH 卡片当前根本不产 `actions` → 不是线上缺陷，是动态卡的前置 |
| B5 `pushView` 绕过 caps | P4 | **P1（半天）** | 单点修复 |
| B6 DSH 气泡 id≠subId | P1 | **P2/P3** | 不是丢数据（UUID 已稳定，`ensureRecoveredIds` 只补空 id）；是命名约定 |
| B7 远程卡审批点击 | P1 | **随 B12 一起消失** | 整条路径删除 |
| B9 `remoteCardView` 未补拷 | P1 | **随 B12 一起消失**（若保留则 P0） | 同上 |
| B10 会话文件无 fsync/`.bak` | 可选 | **不是可选，但 2 天内不碰** | `delete→rename` 有整文件丢失窗口（`ChatHistoryManager.java:658-663`） |

---

## 5. 计划重排（替代 00-overall §9 与 v2 §9）

| 阶段 | 内容 | 工期 | 依赖 |
|---|---|---|---|
| **P0** | **B11**（补拷 approvals/questions/remoteCardView + clone + 单测）→ **B12**（删 `/v2/view` 远程卡整条路径） | 2-3 天 | — |
| **P1** | **B5**（`pushView` 补 caps）+ **B8**（caps 声明与构造点矩阵对齐）+ B1（DSH ended / 切会话 / 断连时清 transient slot） | 2-3 天 | — |
| **P2** | 动态卡：本地 fixture 验证 `layout` 渲染 → B3/B2/B4（含 source 注入）→ B13（索引改按 message 查找） | 2-3 天 | P1 的 B5 |
| **P3** | B6（id 命名）+ B10（原子写加固）+ 配置表（仅给甲板 maxRows 提供值） | 2-3 天 | — |
| **P4** | 插件动态卡（走 `/events` 事件，**不走 `/v2/view`**）+ 拆模块（ESM `import`） | 2-3 天 | P2 |

**总计 10-14 天**（原估 6-9 天被低估约 2 倍）。

**更早验证的顺序**：P2 用本地 fixture 先证明 `layout` 能渲染（`ComponentRegistry.java:107` 已注册 `LayoutCardView`），再 P4 做服务端卡，最后 P3。

---

## 6. 两天最小一刀（建议先做这个）

> 不建 `StatusDeckController`、不删布局 id。

1. 在现有 `renderDockSlots` 里把 **D3 压到 1 条**；
2. 在 `renderAgentProgress` 里**收起 msg/meta 两行**（保留标题+进度条）；
3. 两处显隐各补一次 `onViewportChanged(改前 pinned)`；
4. DSH `ended` / 切会话 / 断连时**清 transient slot**（不走 `endGeneration`）；
5. 补 `approvals` / `questions` / `remoteCardView` **三行深拷贝** + 一条单测。

预期：聊天区立即多出约 2 行高度、审批不再因重启丢失；改动集中、可单独 commit（`fix(chat): ...`），能 `git revert`。

---

## 7. 验收改法（替代 90-tests 的 A1/A2/A9）

| 原验收 | 问题 | 改为 |
|---|---|---|
| A1 "聊天区损失 <5%" | 与"3 行"数学冲突（3×11sp+padding≈57dp > 5%×640dp=32dp）；且未区分本机/DSH 基线 | 固定设备脚本记录 `status_deck.getHeight()` 与 `messageList.getHeight()`：断言 deck ≤72dp 且视口减少 ≤12%；D1 对 DSH 不适用（DSH 不写 `AgentProgressBus`），需单列 |
| A2 插槽无残留 | 无触发手段、无日志 | `StatusDeckControllerTest(FakeHost)` 断言显隐各回调一次；注入 3 slot → DSH `turn/end` → 断言 `DockSlotBus.list()` 为空 |
| A3 贴底不裁切 | 靠截图 | pinned 时断言 `onViewportChanged(true)` 恰好一次、`lastChild.bottom≈height-padding`、`!canScrollVertically(1)` |
| A9 caps 一致 | 全 caps 客户端下 `filterViewCards` 是恒等映射 → **假阳性** | 用只声明 `events.stream` 的 caps 请求两条通道，断言都等于 `filterViewCards(cards, reduced)`；再加 caps 矩阵（每个 cap 必须有构造点+渲染器） |

---

## 8. 事实更正表（文档引用错误）

| 文档位置 | 错误 | 正确 |
|---|---|---|
| v2 §15.3 | 分页 100→50 已落地 | **实际未落地**（已修，见 F1）|
| v2 §1.1 / §5.2 / 10-modules M2 | `ComponentActions.collectValues` | 该类**无此方法**（只有 `collectInputs(String)`）；整树取值在 `NativeLayoutRenderer.collectValues(Map)` |
| v2 §4.1 / §13 | `dock_slot_host` 403-410、`agent_progress_slot` 415-425 | **397-404 / 408-471** |
| v2 §14.4 | B9 补拷点在 `ChatHistoryManager:324-335` | **301-323** |
| v2 §15.1 | 14,4xx / 6,387 / ~3,100 | **14,588 / 5,584 / 4,694**（前三是 ROADMAP 旧值）|
| v2 §1.1 | ~70 图元 | **64**（容器 11 个正确）|
| v2 §1.1 | ≤200KB | **200,000 字符** |
| v2 §1.3 | `ProductCards.mount` 唯一挂载口 | 还有 `mountAll` |
| v1 稿 | 67 个卡片组件 | **38 个 ChatComponent 实现 / 70 个注册实例** |

---

## 9. 接口修订（替代 10-modules 的接口签名）

### M1 StatusDeckController
- **不要 `refresh()` 无主**：`statusPhraseTicker` 每 1.2s 直调 `refreshProgressSlotMeta`，须改为调 `refresh()`；触发集合写死：slot 变化 / 进度变化 / queue 变化 / ticker
- **D1 数据源**：DSH → `live.inferenceProgress` / `setDshPhaseText`；本机 → `AgentProgressBus`（DSH 从不写它）
- **D2 数据源**：服务端 `/status.text_lines`（不本地拼文案）
- **回调改签名**：`onDeckHeightChanged(wasVisible, nowVisible)`；Host 内**先取 pinned** 再改可见性，post 后调 `onViewportChanged`
- **注册时机**：不要放 `:824`（早于 `stickToBottom` 创建与 `setAdapter`，回放会 NPE）；放 `onResume` 或把回放 post 到下一帧
- **清理**：`clear()` 不转调 `AgentProgressBus.clear()`（其 `AILogger` 静态 Handler 会让 JVM 测试崩），由 Host 注入

### M2 DshUiCatalog
- 拆 `load(Context)` + `parse(String)`（否则只能 Robolectric）
- 版本闸函数在 `ai/usage` 是包私有 → **抽公共 util**，不要复制
- `isCacheTrusted` 在"无法比较"时返回 true，与"覆盖档必须严格新于随包"相反 → 非法/缺版本判拒绝
- `versionOf` 取第一个 `"version"` → 嵌套 catalog 会读错，改为顶层键
- 补 `removeListener`；`forwards` 改三态（allow / deny / unregistered）
- `maxRows` 与甲板上限取 **min**，权威归甲板

### M3 ActionSink（前置最多）
- **`Source` 必须补到数据层**：`ComponentData.source` + 投影置值 + `deepCopyForSave`/`clone` + `focusClone` + 6 个渲染调用点
- **保持现行为**：发送失败要**回落本机回显**（现状如此），不要严格分派
- `sendComponentAction` 是**同步 HTTP（30s）**，必须移出 UI 线程并给失败回调
- `Source`/`Port` 放 `ai/chat/component` 或 `ai/remote`，实现注入（避免 `ai/chat/component→ai/tool` 新依赖边）
- **前提**：`DshProjection` 现在根本没注入 `actions`/`component_id` → M4 的验收前提不成立，先补

---

## 10. 约束修订（补充第 8–11 条）

在 00-overall §7 七条之外追加：

8. **输入区任何高度变化**都要走同一入口（`thinking_indicator`、`attachment_list`、chip 行、`queue_bar`、键盘 insets 都在改高度）。
9. **`approvals`/`questions`/`remoteCardView` 必须进 `deepCopyForSave` + `clone()`**（B11；比"新增字段同步"更具体）。
10. **`ComponentData.id` 必须非空**（空则落盘省略、恢复换号、`data#<idx>` 错位）；props 禁放二进制/大整数/大字符串（`byte[]`→`"[B@…"`、>2^53 失真、无大小上限）。
11. **`ActionSink` 先于动态卡落地**（现有路由靠 `dsh-` 嗅探，与 `T{n}` 前缀互斥，过渡期两条都要留）。

---

## 11. 规范冲突处置（择要）

| 冲突 | 处置 |
|---|---|
| 手机端仍在本地拼状态文案 | D1/D2 改读 `/status.text_lines`，Controller 只管档位/截断/显隐 |
| `/v2/view` 与事件投影两个视图源 | 明确边界：会话时间线=事件投影；`/v2/view` 只保留连接/自检静态卡 |
| `dsh_ui_catalog.json` 与 caps 双声明 | 配置表只做映射，**一律受 caps 门禁**；`ui/slot` 不在 `EVENT_CAP` 里，新 cap 名客户端未声明会被判 false → 加门会切断现网 slot，需先加 cap 声明 |
| `ActionSink` 放 `ai/tool` | 改放 `ai/chat/component` 或 `ai/remote` |
| `index.js` 拆分归属 | 不是 ROADMAP §2.1（那张表只有 Java 文件），改引架构评审 §5；且 `index.js` 是 ESM，拆文件用 `import` |
| 文档重复 | v2 降级为"调查与决策附录"，删与三份重复的章节；v1 标 superseded |
| 提交前缀 | 指南是 7 个前缀（含 `chore:`/`style:`）+ 功能分支；scope 写法视为补充 |

---

## 12. 未采信 / 存疑

| 项 | 理由 |
|---|---|
| "130dp ≈ 23% 损失" | 无测量产物，只有布局推算；改为按实际 `getHeight()` 测 |
| "56 个未提交文件" | 当时快照，现已提交 |
| 在线/本地 agent 的 `.round`/`AtomicFileText` 细节 | 未逐条核对，保留但不作为设计依据 |

---

## 13. 回滚现实

- **P0 必须单独 commit**：要动 `activity_ai_chat.xml:397-471`、`AIChatActivity`(176-178/8243/12946-12977/14383)，与 P1 共用 Activity 时 `revert` 会冲突
- P1 不是"单文件回退"：B2/B3 在 `DshProjection`/`ChatAdapter`，B4 涉及 `ActionSink`+`ComponentActions`+`ChatMessage`+Activity，B5 在插件，至少 6 个文件
- 插件回滚要**先留副本 + 重启 DSH**；仓库无版本化插件目录，新增 `ui_cards.js`/`ui_catalog.js` 要列进回滚清单
- P3 配置表：删覆盖档是唯一干净回滚
