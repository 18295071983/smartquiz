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
