# AI 对话改版 Demo

单文件、零依赖的高保真交互原型。

## 打开

直接用浏览器打开 `ai-chat-redesign.html`，或：

```powershell
start .\demo\ai-chat-redesign.html
```

## 可以试什么

- 主题：浅色 / 深色 / 沉浸蓝（保留现有深蓝品牌色）
- 界面：对话 / Agent / 控制台 / 空状态
- Agent：运行中（计划 + 授权 + 工具 + 日志）/ 已完成（计划 + 反思 + 汇总）；点「允许一次」看授权后的执行态
- 控制台：概览 / 会话 / 记忆 / 工具 / 文件 / 统计 6 个 Tab；记忆可搜索/删除，文件可授权公共目录
- 面板：模型与参数、会话信息、历史抽屉、附件面板、消息操作
- 点标题切换模型，点「＋」加附件，点「⋯」看会话统计
- 点思考行、工具行、日志行展开/收起
- 点题目选项答题，答完看解析
- 点「▶ 播放演示」看「思考 → 工具 → 流式正文 → 卡片 → 操作」全过程
- 点「设计标注」显示 5 个关键改动点

## URL 参数（方便截图 / 演示）

| 参数 | 值 | 说明 |
|------|----|------|
| `theme` | `light` / `dark` / `blue` | 主题 |
| `view` | `chat` / `agent` / `console` / `empty` | 界面 |
| `agent` | `run` / `done` | Agent 状态（配合 `view=agent`） |
| `tab` | `overview` / `session` / `memory` / `tools` / `files` / `stats` | 控制台 Tab（配合 `view=console`） |
| `grant` | `1` | 自动点「允许一次」，展示授权后的执行态 |
| `scroll` | `bottom` | Agent 视图滚动到底部（截图用） |
| `sheet` | `model` / `session` / `attach` / `message` | 直接打开面板 |
| `drawer` | `1` | 直接打开历史抽屉 |
| `notes` | `1` | 显示设计标注 |
| `demo` | `1` | 自动播演示 |
| `embed` | `1` | 隐藏页面外框，只留手机（截图用） |

示例：

```
ai-chat-redesign.html?theme=blue&embed=1
ai-chat-redesign.html?theme=dark&view=empty
ai-chat-redesign.html?view=agent&agent=done
ai-chat-redesign.html?view=console&tab=memory
```

截图都在 `screenshots/` 下。

> 这是视觉与交互原型，不含真实模型调用；Android 落地基线见 `../docs/ui/ai-chat-redesign.md`。
