# v1 聊天消息 UI（S5 删除前快照，2026-10-25 捞回）

**来源**：`7b9c6843`（2026-10-20 22:14，`refactor(chat-ui): S5 删掉 v1 消息树/旧布局/旧资源与界面开关，只剩一套 v2 渲染`）的**父提交** `7b9c6843^`。
当前分支仍能访问该提交，所以这里只是把被删文件按原路径摘出来存档，方便查设计/以后按需恢复；
不是运行时资源（不在 `src/main/res`，不参与编译）。

## 被 S5 删掉的文件（本目录的 10 个）

| 原路径 | 本目录 | 用途 |
|---|---|---|
| `res/layout/item_user_message.xml` | `layout/` | v1 用户气泡：紫色渐变底 + 时间戳 + 附件列表 |
| `res/layout/item_thinking_message.xml` | `layout/` | v1 独立思考条目（灰卡 + 进度条 + 折叠/展开） |
| `res/layout/item_agent_step_message.xml` | `layout/` | v1 Agent 步骤卡（图标 + 标签 + 内容 + 步骤号） |
| `res/layout/view_chat_input_bar.xml` | `layout/` | v1 输入栏（胶囊输入 + 发送/停止 + 附加按钮） |
| `res/layout/view_chat_v2_tool_line.xml` | `layout/` | 过渡期工具行（早于最终 `view_chat_v2_tool_line` 收敛） |
| `res/drawable/user_message_background.xml` | `drawable/` | 用户气泡渐变底 |
| `res/drawable/message_card_thinking.xml` | `drawable/` | 思考卡底 |
| `res/drawable/agent_step_tag_background.xml` | `drawable/` | Agent 步骤标签底 |
| `java/.../ai/chat/ui/ChatUiFlags.java` | `java/` | v1/v2 开关（`chat_ui_prefs` / `chat_ui_v2`，默认 true） |
| `java/.../ai/chat/ui/ChatUiSetting.java` | `java/` | 抽屉里"聊天页：新版/旧版"入口（切完重建页面） |

## 完整 v1 渲染代码在哪

v1 的消息渲染不是独立类，而是 `ChatAdapter.java` 里 `if (ChatUiFlags.isUiV2Enabled(...)) { v2 } else { v1 }` 的分支；
S5 把 v2 分支收敛后删掉了 else 分支（**1359 行删除 / 130 行新增**，见 `git show --numstat 7b9c6843`）。
完整 v1 版 `ChatAdapter.java`（7004 行）在该父提交里：

```bash
git show 7b9c6843^:src/main/java/com/oilquiz/app/ai/chat/ChatAdapter.java > /tmp/ChatAdapter-v1.java
```

v1 涉及的渲染点（在父提交里搜这些名字可定位）：
- `createAiMessageItem`：AI 气泡（`ai_message_background` + 12/10dp）、`blockLabel`、状态行、模型信息行、时间戳；
- v1 动作胶囊（复制/朗读/点赞/点踩/重新生成/详情/新对话，7 颗 `createActionButton`）；
- `updateThinkingSection`（v1 思考区三段裸视图 + `expand/collapseThinkingContent` 高度动画）；
- `bindUserMessage` 的 v1 分支（`item_user_message` + 时间戳）；
- `ChatInputBar` 的 v1 分支（`view_chat_input_bar`）。

## 为什么不能直接 `git revert 7b9c6843`

S5 之后 `ChatAdapter.java` 又经过 parts renderer / 思考流式 / 工具行收敛等大量改动，revert 会产生
大面积冲突，且会把已修掉的 v1 时代 bug（见 `docs/ui/v1-v2-merge-report.md` §3）一起带回来。
要恢复成"能跑"的旧版，推荐按需回接：预览页 → 运行时开关，两步走。

## 历史依据

- `docs/ui/v1-v2-merge-report.md`：v1/v2 逐项差异、合并取舍、当时发现的 3 个真 bug；
- `docs/ui/HANDOVER.md` §4.10–§4.12 与 S0–S5 记录：为什么最后选择"只留一套 v2"。