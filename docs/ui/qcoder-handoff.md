# qcoder 交接卡：Chat Parts 渲染架构升级

- 交接时间：2026-10-21 05:32 (+08:00)
- 基线分支：`main`
- 基线提交：`37f2b581`
- 基线状态：工作区干净；全量单测 486 绿；`assembleDebug` 通过
- 详细设计：`docs/ui/parts-architecture-upgrade.md`

## 你的目标

把 AI 消息的 UI 从“parts 派生投影 + 旧 renderRoundAssembled”升级为
“parts 类型注册表 + 稳定 id + 增量复用”的渲染器。

不是让你重写所有聊天页，只迁 AI 消息正文的渲染路径。

## 第一件事

```powershell
cd D:\qzq\smartquiz
git status --short
git switch -c feat/chat-parts-renderer
.\gradlew.bat --offline testDebugUnitTest
```

只做 P1：

> 给 `AssistantTurn.Part` 增加稳定 `id` 与 `fingerprint()`，
> `fromMessage` 构建时生成；补单测。不要改 ChatAdapter，不要删
> `contentRoundBounds`。

## 禁止

- 禁止每次 bind `removeAllViews()` + 裸 `for (Part)` 全量重建；
- 禁止 parts 模式与 `bindComponents` 同时渲染 `tool_call`（会双卡）；
- 禁止删除 `contentRoundBounds` / `content`，直到 P5；
- 禁止 `git add -A`；
- 禁止改 `build/`、`_archive/`、`libs/`；
- 禁止把流式 token 写进 `ChatMessage.content`。

## 必读代码

- `src/main/java/com/oilquiz/app/ai/chat/turn/AssistantTurn.java`
- `src/main/java/com/oilquiz/app/ai/chat/ChatMessage.java`
- `src/main/java/com/oilquiz/app/ai/chat/ChatAdapter.java`：`bindMessageContent`、
  `renderRoundAssembled`、`bindComponents`
- `src/main/java/com/oilquiz/app/ui/activity/AIChatActivity.java`：`completeGeneration`
- `src/main/java/com/oilquiz/app/ai/chat/ui/MarkdownWebView.java`

## 完成标准

见详细文档第 9 节。最小可交付：

- flag 关闭时行为完全不变；
- flag 开启时纯文本/列表/表格/代码块视觉与旧路径一致；
- 无重复工具卡；
- 滚动/折叠/播放状态不丢；
- 单测全绿并新增 parts 测试。

## 报告格式

完成后回报：

1. 分支与提交列表；
2. 改动文件；
3. 测试数量与失败数；
4. flag 开/关验证结果；
5. 未解决风险。
