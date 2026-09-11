# ChatKit — AI 对话能力全局组件包

答题宝 AI 助手能力改进中沉淀的**全局可复用组件**分发快照。
从 `AIChatActivity`（约 1 万行）逐块抽取/新建，逻辑与渲染完全独立于页面，
**页面零改动**（AIChatActivity 保持稳定基线）。

> ⚠️ 权威源说明：本包是**分发快照**（复制件）。工程内的权威源码位于
> `D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\`（及
> `src\main\res\layout\view_chat_input_bar.xml`）。修改请改权威源，再同步本包。

## 包内容

```
chatkit/
├── README.md                  ← 本文件
├── COMPONENT_INDEX.md         ← 32 组件清单（分层/职责/关键 API）
├── DEPENDENCIES.md            ← 依赖清单（内部类 / R.string / 第三方库）
└── src/
    ├── com/oilquiz/app/ai/    ← 33 个 Java 组件源码（含 ChatInputBar/ChatMessagesView）
    └── res/layout/            ← view_chat_input_bar.xml（ChatInputBar 布局）
```

## 架构分层

| 层 | 组件数 | 职责 |
|---|---|---|
| 逻辑层 | 22 | 决策（路由/状态）、数据操作（构建/文件/统计）、流程编排（发送/流式/工具/语音/视觉）——零 UI |
| 渲染层 | 8 | 状态条/统计条/空态/芯片组/弹窗壳/附件条/录音指示/步骤流 |
| 装配层 | 1 | `ChatShellView` 整页壳：状态区+消息流+输入栏一键装配 |
| 接入件 | 2+1 | `ChatInputBar`（输入栏组件）、`ChatMessagesView`（消息容器）+ 布局 |

## 快速接入（ChatShellView 全页壳）

```java
// 1. XML 里放壳
// <com.oilquiz.app.ai.chat.ui.ChatShellView android:id="@+id/chat_shell" .../>

ChatShellView shell = findViewById(R.id.chat_shell);
shell.bindSources(nativeSource, statsSource);   // 状态条/统计条数据源（接口注入）
shell.setAdapter(chatAdapter);                  // 消息流（ChatAdapter 或任意 Adapter）
ChatInputManager input = shell.attachInput(activity, callback, null); // 输入栏接线
shell.onResume();                                // 启动状态条轮询（onResume）
shell.onStreamingToken(total, tps);              // 流式统计（流式回调）
shell.onTokenStats(stats);                       // 完成态统计
shell.onDestroy();                               // 停止轮询（onDestroy）
```

或逐件使用（组件接口均为 `Host`/`DataSource` 注入，不持有页面引用，见
`COMPONENT_INDEX.md` 各组件用法示例）。

## 设计原则

1. **接口注入，零页面引用**：每个组件通过 `Host` / `Source` / `Callback`
   接口与宿主解耦；组件内部不 import Activity。
2. **逐项等价**：从 AIChatActivity 抽取时保持行为等价；新建组件为通用设计。
3. **扩展点注释**：接口 Javadoc 写明替换/扩展方式（如 `ModeChipGroup.setChipStyle`
   即主题系统接入点；`ChatShellView.bindSources` 可换任何数据实现）。
4. **可移植**：拷入新工程后，补齐 `DEPENDENCIES.md` 中的依赖即可编译。

## 版本记录（git 提交链）

- `501dd1f` 早期 2 组件（ChatContextBuilder / MessageRouteDecider）
- `a4d4cc2` ①② 7 组件　`50384da` ③ UI 壳 3 组件　`726e85f` ③ 逻辑层 5 组件
- `0f570bc` A 档 5 编排组件（逻辑层收官，22 个）
- `144921d` 渲染层 3（GenerationStatusBar / TokenStatsBar / ChatStateOverlay）
- `0d48909` 新建 UI 5（ModeChipGroup / ChatBottomSheet / AttachmentChipsView / RecordingIndicatorView / GuideStepFlowView）
- `176f4fa` ChatShellView 整页壳　`6c77387` 验证页 demo（装机通过）
