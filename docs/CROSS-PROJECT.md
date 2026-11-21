# 两个项目的互查索引（smartquiz ⇄ oilquiz）

> 本仓库（答题宝 / smartquiz）2026-11-22 起冻结，新功能都在 oilquiz。
> **完整版（新项目视角）在 `D:\qzq\oilquiz\docs\CROSS-PROJECT.md`**，本页只保留从老仓库反查所需的入口。

## 1. 一张表分清两个项目

| | **smartquiz**（本仓库 / 老） | **oilquiz**（新） |
|---|---|---|
| 定位 | 存量产品，旧 Java UI | 新架构，UI 重写，DSH 是重点 |
| 路径 | `D:\qzq\smartquiz` | `D:\qzq\oilquiz` |
| 包名 | `com.oilquiz.app` | `com.oilquiz.v2` |
| 主分支 | `main` | `master` |
| Gradle | 8.13 | 9.8.2 |
| Gradle User Home | `D:\Gradle\Home` | `D:\gradle-cache` |
| git 身份 | `SmartQuiz Developer <developer@smartquiz.com>` | `OilQuiz Developer <developer@oilquiz.com>` |
| 远端 | `github` / `origin` → smartquiz | `github` / `gitee` → oilquiz（私有） |
| 状态 | 冻结，只修 bug | 活跃开发 |

## 2. 哈希不能跨仓库

```powershell
git -C D:\qzq\smartquiz show <hash>   # 本仓库
git -C D:\qzq\oilquiz   show <hash>   # 新项目
```

文档里的哈希默认指所在仓库；跨仓库要写 `oilquiz@<hash>`。

## 3. 想查新项目，去这几个文件

| 想知道 | 看哪 |
|---|---|
| 新项目交接、技术栈、构建命令 | `D:\qzq\oilquiz\docs\SESSION-BRIEF.md` |
| 2026-11-22 的决策、插件差异、远端与历史重写 | `D:\qzq\oilquiz\docs\SESSION-LOG-2026-11-22.md` |
| 两边路径/包名/哈希规则/常用对照命令 | `D:\qzq\oilquiz\docs\CROSS-PROJECT.md` |
| 老 App 怎么接新插件的新能力 | `D:\qzq\oilquiz\docs\HANDOFF-OLD-APP-PLUGIN-UPGRADE.md` |
| 插件协议（WS/SSE/pull/ack/caps） | `D:\qzq\oilquiz\docs\DSH-CLIENT-PLUGIN.md` |

## 4. 边界

- 本仓库**冻结**：只修 bug、补文档，不再加新功能
- 新功能一律去 oilquiz 仓库做；两边**不要跨仓库提交**
- 插件真源目前仍是两份（新项目那份是超集），同步规则见 oilquiz 的 `SESSION-BRIEF.md` §6
