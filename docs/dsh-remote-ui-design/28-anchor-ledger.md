# 28 · 锚点对账：服务端入账，下发带锚点（方案）

> 用户口径：「就不能加一个服务器与历史系统对账吗，服务器也入账做锚点，加分支，
> 服务器下发时把锚点取出来，入账。」

## 1. 现在为什么对不上

两套账各记各的：

| | 记什么 | 存在哪 |
|---|---|---|
| 手机 | 本地会话 JSON + `transport_seq_*` / `attached_session` | `files/conversations/*.json`、`remote_dsh_config.xml` |
| 服务端 | 会话事件日志（seq 单调） | 电脑上的 DSH 会话存储 |

清历史只清手机那半边 → 两边游标不一致 → `diverged` → 服务端发 snapshot →
手机整段重建 → **重建走"新建"分支** → 又多一个本地会话文件 → 越用越多（实测：进出 3 次 = 3 个文件）。

## 2. 改成：锚点记账（anchor ledger）

**服务端**为每个客户端（device_id）维护一条锚点记录：

```json
{ "device_id": "dev-79ef3762",
  "local_session_id": "3ae6d7fa-…",     // 手机上的会话 id
  "remote_session_id": "session-e570…", // 电脑上的会话 id
  "acked_seq": 9774,                    // 已确认落屏的位置
  "updated_at": 1794642000000 }
```

**下发时带锚点**：服务端每帧事件（或每批 `batch` / `snapshot`）附上：

```json
{ "type":"event", "event":{…}, "anchor":{ "local_session_id":"3ae6d7fa-…", "seq":9775 } }
```

**手机收到锚点后入账**（按锚点**查或建**，而不是"新建"）：

```
有 local_session_id → 找到那条本地会话，把事件追加进去（幂等：seq 已存在就跳过）
没有 / 找不到     → 用锚点里的 id 建一条**同 id** 的会话（不是随机新 id）
```

**手机回报锚点**（复用 `/ws/ctl` 的 diag 通道）：

```json
{ "type":"anchor", "local_session_id":"3ae6d7fa-…", "acked_seq":9774 }
```

服务端据此更新账本；对不上就以**服务端的账**为准下发 snapshot，并附锚点让手机原地重建。

## 3. 三条规则（解决现有症状）

| 症状 | 规则 |
|---|---|
| 进出对话页多出历史 | **锚点没变就复用同一会话**；只有首次配对（没有锚点）才新建，不落空会话 |
| 清历史后连不上 | 手机清历史时**同时清自己那半账**并上报 `anchor: null`；服务端收到就重新签发锚点（不是拒绝连接） |
| 反复重建 | 重建**复用锚点里的 local_session_id**（覆盖内容），不新建 |

## 3.5 锚点表是**动态的**（一设备多会话，绝不参合）

电脑端同时可能有很多条会话，锚点不能做成"一台设备一条"，否则切会话就串了。改成**二维键**：

```
anchors[device_id][remote_session_id] = {
  local_session_id, remote_session_id, acked_seq, created_at, updated_at, deleted_at?
}
```

| 规则 | 说明 |
|---|---|
| 自动建账 | 第一次把某条电脑会话同步到手机时，按 (device, remote_session) 建一条，`local_session_id` 由服务端签发 |
| 互不参合 | 每条 `remote_session_id` 一条锚点；A 会话的 seq 不会影响 B 会话 |
| 动态回收 | 电脑端会话归档/删除时，对应锚点标 `deleted_at`（进回收站），不立即物理删 |
| `acked_seq` 按会话 | 各算各的，切回来还是原来那个位置 |

## 3.6 服务端：清除历史 / 回收站 / 恢复 / 删除

| 操作 | 语义 | API |
|---|---|---|
| **清除历史** | 把当前会话的锚点游标清零（消息保留在电脑端，下次按 snapshot 重放） | `POST /v2/anchor/clear {session_id}` |
| **回收站** | 删除的锚点进回收站（`deleted_at` 有值），保留 30 天 | 删除即软删 |
| **恢复** | 从回收站恢复，原 `acked_seq` 还在 | `POST /v2/anchor/restore {session_id}` |
| **永久删除** | 物理移除该锚点，手机端下次同步会当作全新会话重建 | `DELETE /v2/anchor {session_id}` |
| **列出回收站** | `GET /v2/anchor?deleted=1` | 面板/诊断页展示 |

**软删不碰电脑端的会话数据**——删的只是"这台手机与那条会话的同步关系"，不影响电脑上的对话本身。



| 端 | 改哪里 |
|---|---|
| 插件 | `ANCHORS` 表（device_id → 记录，落盘 `anchors.json`）；事件帧附 `anchor`；`/status` 暴露；`/ws/ctl` 收 `anchor` 上报 |
| 手机 | `RemoteAnchorStore`（存当前锚点）；事件入库时按锚点查/建会话；清历史时清锚点并上报 |

## 5. 分步

1. **A1** 插件加锚点表 + 帧里带 anchor + `/status` 可见（不改手机也能观察）；
2. **A2** 手机按锚点查/建会话（空会话不落盘）；
3. **A3** 清历史时清锚点 + 上报 `anchor:null`，服务端重签；
4. **A4** 重建复用锚点 id（覆盖而非新建）。

## 6. 验证

- 进出对话页 5 次 → `files/conversations` 仍是 1 个文件；
- 清历史 → 重新连接 → 自动出现一条重建的会话（不是连不上）；
- `/status.anchors` 里 device_id 对应一条记录，`acked_seq` 随消息推进。

---

## 7. 落地记录：A3-接（2026-11-14）

### 7.1 手机：三处（交接文档第 3 节那一组）

| # | 改哪 | 做什么 |
|---|---|---|
| 1 | `RemoteDshTool.saveAnchorFromFrame` | WS `hello` / SSE `ready`、`snapshot` 帧里的 `anchor` 一律交给 `RemoteAnchorStore.save()` 入账（存不下只打日志，不影响连接） |
| 2 | `RemoteAnchorStore.localSessionIdForRemote(c, remote)` + `AIChatActivity.sessionIdForSave()` | 建本地会话前先按（设备 × 电脑会话）**查**锚点：查得到就用它的 `local_session_id`（文件在=复用、不在=用同 id 建），查不到才 random 新建。四个保存点都走它：`saveCurrentHistory` / `saveHistoryAsync` / `onStop` / `startNewConversation` |
| 3 | `ChatHistoryManager.saveConversationSession` | `messages` 为空直接 return（空壳不落盘） |

`startNewConversation` 有个顺序坑：必须在 `startNewRemoteConversation()` **之前**取会话 id。
那一步会把电脑端换成新会话、锚点跟着变；之后再查，旧对话就被写进新锚点的文件里了。

### 7.2 插件：两处（不改插件，A3 在真机上跑不起来）

| 改哪 | 为什么 |
|---|---|
| `/events` 的 `ready` / `snapshot` 帧补 `anchor` | WS 的 `hello` 本来就带；但隧道掐长连接时 App 会退回 SSE，这条路上原来拿不到锚点 → 又变成随机新建。按 `Authorization` 里的设备令牌认设备，两条通道口径一致 |
| `loadAnchors()` 从文件前部挪到 `PLUGIN_DIR` 之后 | **这是真机实测到的坑**：`ANCHOR_FILE()` 依赖 `PLUGIN_DIR`，而 `PLUGIN_DIR` 定义在文件更后面。原来在顶层就调 `loadAnchors()` → `ReferenceError` 被 `catch` 吞掉 → 账本永远从空开始，下一次 `saveAnchors()` 把磁盘上整本覆盖。表现就是：**插件每重载一次，`local_session_id` 就换一个**，手机侧于是又多一条本地会话 |

### 7.3 已验证（2026-11-14 17:14 真机）

```
I RemoteDshTool: [anchor] 入账 local=94a27de2e65bac63 remote=session-9c2075d0…
I AIChatActivity: [anchor] 保存挂到锚点会话 94a27de2e65bac63（remote=session-9c2075d0…）
```

- `shared_prefs/remote_anchors.xml` 出现 `current` + `by_remote.<会话>` 两条；
- 清空历史后（旧文件进 `conversations_trash/`），新对话落在 `conversations/94a27de2e65bac63.json`
  —— **用锚点 id，不是随机 uuid**，即"重建复用锚点 id"；
- 插件重载两次，`local_session_id` 保持 `94a27de2e65bac63` 不变（7.2 那条修好后）；
- 单测：`RemoteAnchorStoreTest` 4 条、`ChatHistoryManagerSessionTest` 20 条、`RemoteDshStreamParseTest` 4 条全绿；
- 进出 5 次（`am force-stop` → 冷启动 → 点「AI聊天」，5 轮）：`files/conversations` 一直是 2 个
  （一条修复前的随机 id 遗留 + 一条锚点会话），**没有每进一次多一条**；
  每轮的 `[anchor] 入账` 都是同一个 `local_session_id`。
  注意这 5 轮页面是空的（没消息 → 不触发保存），所以"带消息进出"仍建议手点复核一次。

### 7.4 还没验 / 遗留

- 「带消息进出对话页 5 次 → 仍只有 1 个文件」还没人工复核：用 adb 连续点按会叠出第二个
  `AIChatActivity` 实例（`ChatNavigation` 只管走应用内入口的那几条路），两个实例各存各的，
  反而多出文件（本次已踩到，`7cc58af5-…` 那条就是这么来的）。人工点 5 次最可信。
- 修复前留下的随机 id 会话不会自动并到锚点会话里：用户在历史里会看到"旧的那条 + 锚点那条"
  两条。要么手删旧的那条（进了回收站还能恢复），要么以后做一次"迁移：把锚点指向的电脑会话
  与旧的本地会话对齐"。
- 同一现象还有个来源：`DshHistoryImporter`（"把电脑这条对话的历史导入本机"）用的是
  `remote_dsh/import_sid_<电脑会话>` 这套自己的映射，跟锚点是两本账。A4 之后应统一（让导入也认锚点）。
- 清历史（A4）：手机侧还没清锚点 / 还没上报 `anchor:null`，服务端也就没重签。
- 事件帧带 `anchor`：还没做（现在只有握手帧 + SSE ready/snapshot）。

