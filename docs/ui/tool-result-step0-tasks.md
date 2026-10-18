# 工具结果 Step 0：5 个止血改动（Qoder 任务单）

> 目标：**不改架构**，先把"重复 + 卡顿 + 慢 + 难读"四件事压下去。
> 预计：0.5–1 天（含单测）；4 个代码提交 + 1 个验证提交。
> 前置：B0 已落地（`CardShell` / `HtmlSandbox` / 6 卡迁移），**不要动 B0 的文件**。
> 配套：`docs/ui/tool-result-ui-review.md`、`docs/ui/tool-result-modification-directions.md`

---

## 0. 范围与红线

**只做这 5 件事：**

1. 关掉工具结果的自动 AI 解读，删掉重复的 AI 主气泡。
2. 工具卡结果限长（8 行 / 2,000 字），加"查看全部"弹层。
3. 删掉"✅ 工具执行完成"这类逐工具成功系统消息。
4. 补全工具中文名 + 状态去 emoji。
5. 单测与手动回归。

**不要做：**

- 不要动 `ToolResultCardBinder` 的映射逻辑（留给 Step 1）。
- 不要动 `ToolResultStore` 的存储结构（留给 Step 1）。
- 不要动 `ToolResultInterpreter` 的模型调用（只删自动触发，保留手动入口）。
- 不要改 `ChatMessage` 的持久化字段。
- 不要改 B0 的 `CardShell` / `HtmlSandbox` / `HtmlWebView` / `ComponentLifecycle`。
- 不要整体重写 `ChatAdapter` / `AIChatActivity`。

---

## T1 · 关掉自动 AI 解读，删掉重复 AI 气泡

**为什么**：每个成功工具都自动调一次 LLM（在线 60s 超时），然后把同一段摘要写进工具卡、再加一条 AI 主气泡。用户看到两遍，还要等。

**位置**：

- `ui/activity/AIChatActivity.java`
  - 主链路：L2529–L2568（`addSystemMessage("💡 正在AI解读结果...")` + `ToolResultInterpreter.interpret(...)` 整块）
  - 聚合流程 A：L3131–L3167
  - 聚合流程 B：L3274–L3300
- **保留**：手动入口 `handleAiInterpretAction` 里的 `ToolResultInterpreter.interpret(...)`（L9900），这是用户点"AI 深度解读"才触发的，不改。

**改法**：

主链路在 `ToolResultStore.save(...)` 之后直接收尾：

```java
// 删除：addSystemMessage("💡 正在AI解读结果...");
// 删除：ToolResultInterpreter.interpret(...) 整块
scrollToBottom();
if (onComplete != null) onComplete.run();
```

聚合流程 A / B 同理，把整块 `interpret(...)` 换成：

```java
executeCompositeFlow(flow, nextIndex);
```

同时删除不再使用的局部变量：`progressMsgPos` / `progressMsgPos2` / `fallback` / `targetMsgPos*` / `doContinue`，避免编译告警。`canInterpret = true` 与 `rawResult` 保留，工具卡内的"AI 深度解读"按钮仍会出现。

**验收**：

- 执行一个成功工具（如天气）：只有"工具卡 + 模型最终回答"，没有第二条 AI 摘要气泡。
- 没有"💡 正在AI解读结果..."系统消息。
- 工具卡内"🔍 点击使用AI深度解读此结果"仍可见，点击后能正常出结果（手动路径）。
- 失败分支与 `onComplete` / `executeCompositeFlow` 的后续步骤不丢。

---

## T2 · 工具卡结果限长 + 查看全部

**为什么**：`formatForUi` 不做截断，几十 KB 文本直接进 TextView；`item_tool_call_message` 的结果区没有 `maxHeight`。

**新增**：`ai/chat/util/ToolResultText.java`

```java
package com.oilquiz.app.ai.chat.util;

public final class ToolResultText {
    public static final int MAX_PREVIEW_CHARS = 2000;
    public static final int MAX_PREVIEW_LINES = 8;

    public static final class Preview {
        public final String text;
        public final boolean truncated;
        public final int totalChars;
        public Preview(String text, boolean truncated, int totalChars) { ... }
    }

    public static Preview preview(String raw) {
        if (raw == null) return new Preview("", false, 0);
        int end = Math.min(raw.length(), MAX_PREVIEW_CHARS - 1);
        int lines = 0;
        for (int i = 0; i < end; i++) {
            if (raw.charAt(i) == '\n' && ++lines >= MAX_PREVIEW_LINES) { end = i; break; }
        }
        // 不要切在代理对 / CRLF 中间
        if (end > 1 && Character.isHighSurrogate(raw.charAt(end - 1))) end--;
        if (end > 0 && raw.charAt(end - 1) == '\r') end--;
        boolean truncated = end < raw.length();
        String text = truncated ? raw.substring(0, end) + "…" : raw;
        return new Preview(text, truncated, raw.length());
    }
}
```

要求：不要切在代理对中间（`Character.isHighSurrogate`），不要切在 `\r\n` 中间；单测覆盖。

**修改**：`ai/chat/ChatAdapter.java` 的 `buildResultWithInterpretChip(...)`（L4122）

- 先把 `interpretedMessage` 与 `result` 按现有逻辑拼成 `String fullText`（保留 `duplicateRaw` 判断）。
- `ToolResultText.Preview pv = ToolResultText.preview(fullText);`
- `mainContent = applyMarkdown ? formatMessageContent(pv.text) : pv.text;`
- 如果 `pv.truncated`，在内容末尾追加可点击的 `查看全部（N 字）`：

```java
SpannableStringBuilder ssb = new SpannableStringBuilder(mainContent);
int start = ssb.length();
ssb.append("\n\n查看全部（" + pv.totalChars + " 字）");
ssb.setSpan(new ClickableSpan() {
    @Override public void onClick(@NonNull View widget) {
        showFullToolResult(widget.getContext(), info.toolDisplayName, fullText);
    }
}, start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
ssb.setSpan(new ForegroundColorSpan(ThemeColors.get(R.color.colorPrimary)),
        start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
mainContent = ssb;
```

- 新增 `showFullToolResult(Context, String title, String fullText)`：
  - `TextView`（12sp、可选中）+ `ScrollView` + `AlertDialog`；
  - 标题：`title + " · 完整结果"`；
  - 按钮："复制"（`ClipboardManager`）、"关闭"；
  - 不截断、不格式化 Markdown，原样展示，便于排查。

**注意**：

- 这一步只改**显示**；`toolCallInfo.result` 仍保留全文，历史里也仍是全文。Step 1 再把全文搬到 `ToolResultStore`。
- `showFullToolResult` 由用户点击触发，允许一次性构建大 TextView。
- 不要给 `item_tool_call_message.xml` 的 `tool_result` 加固定 `maxHeight`，否则短结果也被压扁；预览逻辑已经控制高度。

**验收**：

- 200 KB 的 `python_execute` 输出：工具卡最多 8 行/2,000 字，末尾有"查看全部"。
- 点"查看全部"能打开完整内容，可复制。
- 短结果不出现"查看全部"，内容完整。
- 滚动/展开工具卡不卡顿。

---

## T3 · 删除逐工具成功系统消息

**为什么**：每条工具成功都插一条"✅ 工具执行完成"或"✅ xxx 完成"，和工具卡状态重复。

**位置**：

- `AIChatActivity.java` L2523：`addSystemMessage("✅ 工具执行完成");`
- L3125：`addSystemMessage("✅ " + step.actionDescription + " 完成");`
- L3269：同上。

**保留**：

- L3030 的流程级"✅ xxx 全部完成"（一次流程一条，不重复）。
- 失败消息（`❌ 工具执行失败...`）与需要用户输入/授权的消息。
- 工具卡自身的状态（`ToolCallStatus` + 耗时）。

**验收**：

- 成功执行一个工具：聊天流里没有"✅ ... 完成"的系统气泡；工具卡显示"已完成 · 1234ms"。
- 失败时仍有明确失败提示。
- 聚合流程只在全部结束时出现一条完成消息。

---

## T4 · 工具中文名 + 状态去 emoji

**为什么**：`ChatMessage.formatToolDisplayName` 只映射 9 个工具，其余显示 `python execute`；状态是 `⏳/🔄/✅/❌`，和 B0 统一后的 UI 不一致。

**新增**：`ai/chat/ToolMetaRegistry.java`

```java
package com.oilquiz.app.ai.chat;

import java.util.HashMap;
import java.util.Map;

public final class ToolMetaRegistry {
    private static final Map<String, String> NAMES = new HashMap<>();
    static {
        NAMES.put("ai_weather", "天气查询");
        NAMES.put("network_search", "网络搜索");
        NAMES.put("webpage_reader", "网页阅读");
        NAMES.put("database", "数据库查询");
        NAMES.put("excel_tool", "Excel 处理");
        NAMES.put("file_reader", "文件读取");
        NAMES.put("file_generator", "文件生成");
        NAMES.put("file_analyzer", "文件分析");
        NAMES.put("python_execute", "Python 执行");
        NAMES.put("python_chart", "Python 图表");
        NAMES.put("python_analyze_data", "Python 数据分析");
        NAMES.put("ocr_recognize", "OCR 识别");
        NAMES.put("image_gen", "图片生成");
        NAMES.put("dashscope_media", "媒体生成");
        NAMES.put("speech_synthesis", "语音合成");
        NAMES.put("voice_input", "语音输入");
        NAMES.put("reminder", "提醒");
        NAMES.put("task", "任务");
        NAMES.put("memory", "记忆");
        NAMES.put("chat_history", "聊天记录");
        NAMES.put("knowledge_base", "知识库");
        NAMES.put("location", "定位");
        NAMES.put("system_resource", "系统资源");
        NAMES.put("media_toolkit", "媒体处理");
        NAMES.put("app_operation", "应用操作");
        NAMES.put("export_apk", "导出 APK");
        NAMES.put("ui_component", "UI 组件");
        NAMES.put("permission_manager", "权限管理");
        NAMES.put("tool_registry", "工具列表");
        NAMES.put("time_date", "时间日期");
        NAMES.put("calculator", "计算器");
        NAMES.put("unit_converter", "单位换算");
    }

    public static String displayName(String toolName) {
        if (toolName == null) return "";
        String name = NAMES.get(toolName);
        if (name != null) return name;
        // 兜底：下划线转空格，首字母大写；不要直接展示原始工具名
        String[] parts = toolName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.length() == 0 ? toolName : sb.toString();
    }
}
```

**修改**：

- `ChatMessage.formatToolDisplayName`（L490–503）改为 `return ToolMetaRegistry.displayName(name);`，删除 9 个 case。
- `ChatMessage.getStatusText`（L480–488）改为纯文本：`等待执行 / 执行中 / 已完成 / 失败`，删除 emoji。
- `ChatAdapter.bindToolCallMessage`（L3840 附近）给 `holder.toolStatus` 按状态设色：
  - `COMPLETED` → `R.color.success`
  - `FAILED` → `R.color.error`
  - 其他 → `R.color.text_secondary`
  - 用 `ThemeColors.get(...)`。

**暂不做**：`toolIcon` 仍是 emoji，矢量图标留给 Step 1（要改布局里的 `TextView` 为 `ImageView`）。在提交说明里写清楚。

**验收**：

- `python_execute` 显示"Python 执行"，`ocr_recognize` 显示"OCR 识别"，未知工具显示 `Foo Bar` 而不是 `foo_bar`。
- 状态文字没有 emoji；完成/失败/进行中颜色不同。
- 单测：已知、未知、空工具名。

---

## T5 · 单测与手动回归

**新增单测**：

- `src/test/java/com/oilquiz/app/ai/chat/util/ToolResultTextTest.java`
  - 短文本不截断；
  - 超 2,000 字截断并 `truncated=true`；
  - 超 8 行截断；
  - emoji/代理对不被切半；
  - `\r\n` 不被切半。
- `src/test/java/com/oilquiz/app/ai/chat/ToolMetaRegistryTest.java`
  - 已知工具中文名；
  - 未知工具 humanize；
  - null/空串。

**手动回归矩阵**：

| 场景 | 期望 |
|------|------|
| 天气工具成功 | 一张工具卡 + 模型回答；无重复 AI 气泡；无"✅ 完成" |
| `python_execute` 200 KB 输出 | 结果区 8 行/2,000 字 + "查看全部"；弹层可复制 |
| 工具失败 | 失败卡 + 错误 + 重试；不自动解读 |
| 点"AI 深度解读" | 手动解读仍可用，出结果 |
| 聚合流程 3 步 | 每步不插成功消息；最后一条"全部完成" |
| 旋转/恢复历史 | 旧消息仍能渲染（没有新字段依赖） |
| 浅色/深色 | 状态颜色、查看全部链接可读 |

**构建命令**：

```powershell
.\gradlew.bat --offline testDebugUnitTest --tests "*ToolResultTextTest*" --tests "*ToolMetaRegistryTest*"
.\gradlew.bat --offline assembleDebug
```

> 全量单测有存量 25 个失败（7 个类），不要把它们算到本次改动；对比改动前后的失败列表即可。

---

## 提交建议

```
1. refactor(tool-result): stop auto AI interpretation and duplicate AI bubble
2. feat(tool-result): preview long result with full-result dialog
3. chore(tool-result): remove per-tool success system messages
4. feat(tool-result): add ToolMetaRegistry and plain status text
5. test(tool-result): add preview and tool-name unit tests
```

---

## 给 Qoder 的启动 Prompt（可直接粘贴）

```text
继续 D:\qzq\smartquiz 的工具结果 Step 0。先读：
- docs/ui/tool-result-step0-tasks.md
- docs/ui/tool-result-ui-review.md
- docs/ui/tool-result-modification-directions.md
- docs/ui/HANDOVER.md（B0 已完成，别动 CardShell/HtmlSandbox）

约束：
- 不改持久化结构；不动 ToolResultCardBinder 映射与 ToolResultStore。
- 保留手动 AI 解读入口（AIChatActivity.handleAiInterpretAction）。
- 不整体重写 ChatAdapter/AIChatActivity。
- 仓库有未提交改动，不要 git add -A。

按 T1→T2→T3→T4→T5 做，每个任务单独提交；T1/T2 先做，做完给我看 diff 和手动验证结果。
```
