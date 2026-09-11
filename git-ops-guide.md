# SmartQuiz Git 操作指南（详细版）

> **文档目的**：为 AI Agent 和开发者提供完整的 Git 操作规范，避免重复踩坑。
> **最后更新**：2026-09-11
> **维护者**：SmartQuiz Developer

> **2026-09-11 修订说明**：§1/§2/§3 里描述的远程与分支配置**与实际不符**，已按
> `git remote -v` / `git branch -vv` 的实测结果重写。原文档写的双远程
> （`gitee` HTTPS+Token 为主、`origin` GitHub 为备）和四个开发分支
> （`main` 等）**均已不存在**，照抄旧命令会失败。
> 新增 §6.3 记录 `src/main/cpp/llama.cpp` 嵌套仓库的踩坑与正确做法。

---

## 目录

1. [仓库架构总览](#1-仓库架构总览)
2. [远程仓库配置](#2-远程仓库配置)
3. [分支管理策略](#3-分支管理策略)
4. [标准操作流程（SOP）](#4-标准操作流程sop)
5. [PowerShell 环境专项](#5-powershell-环境专项)
6. [.gitignore 管理策略](#6-gitignore-管理策略)
7. [提交规范](#7-提交规范)
8. [故障排查手册](#8-故障排查手册)
9. [版本回退与灾难恢复](#9-版本回退与灾难恢复)
10. [最佳实践 DO / DON'T](#10-最佳实践-do--dont)
11. [快速参考卡](#11-快速参考卡)

---

## 1. 仓库架构总览

```
                    ┌──────────────────────────────────────────┐
                    │        本地仓库 (d:\qzq\smartquiz)         │
                    │                                          │
                    │  main ★                  主干, 当前检出   │
                    │  backup-ai-import-20260911-1641  导入备份 │
                    │  feature/agent-local      本地 Agent 分支 │
                    │  feature/agent-online     在线 Agent 分支 │
                    │  _dsh_probe_branch        探测残留分支    │
                    └────────────────────┬─────────────────────┘
                                         │ fetch/push
                                         ▼
                              ┌────────────────────────┐
                              │        Gitee           │
                              │       (origin)         │
                              │   SSH (git@gitee.com)  │
                              │      唯一远程仓库        │
                              └────────────────────────┘

  另有嵌套独立仓库(不在外层版本控制内):
    src/main/cpp/llama.cpp  ->  https://gitcode.com/gh_mirrors/ll/llama.cpp.git (master)
```

### 仓库关系说明

- **Gitee（`origin`）**：唯一远程仓库，走 SSH（`git@gitee.com:xiaocongcong495863994/smartquiz.git`）。
  **注意**：本节此前写的"`gitee` 走 HTTPS+Token、`origin` 是 GitHub 备用"已不成立 ——
  实际 `git remote -v` 只有一个 `origin`，且指向 Gitee。
- **`main` 的上游处于 `gone` 状态**：`git status -sb` 显示 `## main...origin/main [gone]`，
  且本地 `refs/remotes/` 下为空（`git branch -r` 无输出），所以 `origin/main` 这类名字
  **暂时解析不了**。**注意这并不代表远程有问题** —— 实测 `git ls-remote --heads origin`
  返回 0，远程是通的，只是本地缺 remote-tracking 引用（从未 fetch 或引用被清过）。
  用 `origin/*` 之前先 `git fetch origin`，详见 [3.4](#34-远程分支)。
- **`src/main/cpp/llama.cpp` 是嵌套的独立 git 仓库**，被外层 `.gitignore` 整目录排除，
  见 [6.3](#63-嵌套仓库陷阱llamacpp)。对它的改动**不会**随外层仓库提交。

---

## 2. 远程仓库配置

### 2.1 远程地址

| 远程名 | 平台 | 协议 | URL |
|---|---|---|---|
| `origin` | Gitee | SSH | `git@gitee.com:xiaocongcong495863994/smartquiz.git` |

> **已变更**：本表此前写的是 `gitee`（HTTPS + Token）为主、`origin`（GitHub SSH）为备。
> 实际 `git remote -v` 只有一个 `origin` 且指向 Gitee，GitHub 远程
> （`git@github.com:18295071983/smartquiz.git`）已不存在。

推送/拉取前先验证连通性：

```powershell
git remote -v
git ls-remote origin 2>&1 | Select-Object -First 3
```

### 2.2 Gitee 认证信息

| 项目 | 值 |
|---|---|
| Gitee Login（用户名） | `xiaocongcong495863994` |
| Gitee 用户 ID | `8432792` |
| 仓库名 | `smartquiz` |
| 仓库可见性 | Public |
| 认证方式 | **SSH 公钥**（当前实际使用，不涉及令牌） |
| 令牌存储方式 | 不适用。仅当把远程切回 HTTPS 时才需要把令牌嵌入 URL |

> **关键**：Gitee 的 login 是 `xiaocongcong495863994`，不是手机号 `18295071983`。
> 手机号只用于登录 Gitee 网站，不是 git URL 中的用户名路径。

> **注意**：当前远程走 SSH，所以下面的 2.3 令牌刷新流程**平时用不到** ——
> 只有当你把远程改回 `https://gitee.com/...` 且 push 报 `404 not found` 时才需要。

### 2.3 令牌刷新流程（仅在改用 HTTPS 远程时需要）

当 Gitee push/fetch 返回 `404 not found` 时，按以下步骤刷新令牌：

```powershell
# 第 1 步：验证令牌是否有效（调用 Gitee API）
$r = Invoke-RestMethod -Uri "https://gitee.com/api/v5/user?access_token=<新令牌>" -Method Get -TimeoutSec 30
# 如果返回用户信息 → 令牌有效
# 如果报错 → 令牌无效，需到 Gitee 设置 → 私人令牌 重新生成

# 第 2 步：更新 remote URL（替换 <新令牌>）
git remote set-url origin "https://xiaocongcong495863994:<新令牌>@gitee.com/xiaocongcong495863994/smartquiz.git"

# 第 3 步：验证连通性
git fetch origin --prune
# 输出 "From https://gitee.com/..." 表示成功
```

### 2.4 查看当前远程配置

```powershell
git remote -v              # 查看所有远程
git remote get-url origin   # 查看指定远程 URL
git branch -vv             # 查看分支跟踪关系
```

---

## 3. 分支管理策略

### 3.1 分支模型

```
main ★ ────────────────────────────────────────── 稳定主干（当前检出）
  │
  ├─ backup-ai-import-20260911-1641 ───────────── 导入功能备份（只读）
  ├─ feature/agent-local ──────────────────────── 本地 Agent 分支
  ├─ feature/agent-online ─────────────────────── 在线 Agent 分支
  └─ _dsh_probe_branch ────────────────────────── 探测残留，可删
```

### 3.2 分支清单

> 下表为 `git branch -vv` 的实测结果。此前文档里列的
> `main`（主开发）、`feat-check-agent-function-SwdOZa`、
> `feature/agent-v3-iteration` **均已不存在**。

| 分支名 | 用途 | 跟踪的远程 | 当前 HEAD | 备注 |
|---|---|---|---|---|
| `main` ★ | 稳定主干 | `origin/main` **[gone]** | `e466b84` | **当前检出**；上游已消失，见下 |
| `backup-ai-import-20260911-1641` | 导入功能备份 | 无 | `145826a` | 只读快照 |
| `feature/agent-local` | 本地 Agent | 无 | `9901da6` | |
| `feature/agent-online` | 在线 Agent | `origin/feature/agent-online` **[gone]** | `93db8bb` | 上游同样已消失 |
| `_dsh_probe_branch` | 探测残留 | 无 | `4de2429` | 可删 |

**上游 gone 的处理**（`git status` 会提示 `[gone]`）：

```powershell
# 方式 A：解除上游绑定，之后手动指定推送目标（推荐先做这个，避免误判同步状态）
git branch --unset-upstream

# 方式 B：重新绑定并推送（确认远程确实需要这个分支时）
git push -u origin main
```

### 3.3 分支命名规范

| 前缀 | 用途 | 示例 |
|---|---|---|
| `feat-` | 功能开发 | `feat-weather-ui-redesign` |
| `feature/` | 功能模块（带子路径） | `feature/agent-v3-iteration` |
| `fix-` | Bug 修复 | `fix-chat-adapter-crash` |
| `hotfix-` | 紧急修复 | `hotfix-api-key-leak` |
| `docs-` | 文档更新 | `docs-update-readme` |

### 3.4 远程分支

**当前 `git branch -r` 为空**：本地没有任何 remote-tracking 引用，因此 `origin/main`
这类名字**暂时解析不了**（`git rev-parse origin/main` 会报 `Needed a single revision`）。
所以 §4 及之后所有命令里出现 `origin/main` 的地方，都要先跑一次：

```powershell
git fetch origin --prune    # --prune 清掉远程已删除分支的本地引用
git branch -r               # 之后 origin/* 才会出现
```

远程实际存在的分支（2026-09-11 由 `git ls-remote --heads origin` 实测）：

| 远程分支 | 远程 HEAD | 本地对应 | 备注 |
|---|---|---|---|
| `origin/main` | `9502f07` | `main` = `e466b84` | 两边不一致，推送前必须先 fetch 确认 |
| `origin/feature/agent-local` | `4355aef9` | `feature/agent-local` = `9901da6` | 不一致 |
| `origin/feature/agent-online` | `93db8bbf` | `feature/agent-online` = `93db8bb` | 一致 |

> **远程是通的**：`git ls-remote` 返回 0，所以 `[gone]` 既不是网络问题也不是认证问题，
> 纯粹是本地缺少 remote-tracking 引用（`refs/remotes/` 下为空）。
>
> 此表此前列的 `gitee/feature/ai-chat-fix`、`gitee/feature/secure-api-keys`
> 属于已不存在的 `gitee` 远程，**不要再照抄**。
>
> 跟踪某个远程分支：
> ```powershell
> git checkout -b feature/xxx origin/feature/xxx
> ```


---

## 4. 标准操作流程（SOP）

### 4.1 SOP-1：开始工作前同步远程

```powershell
cd d:\qzq\smartquiz

# 确认当前分支
git branch

# 拉取远程最新引用
git fetch origin --prune

# 如果远程有新提交，重置到远程最新
git reset --hard origin/main

# 验证工作区干净
git status -sb
# 预期输出：## main...origin/main
# 如果有 ?? 文件，确认是已知的未跟踪临时文件
```

### 4.2 SOP-2：提交并推送变更

```powershell
# ── 第 1 步：检查改动 ──
git status -sb                    # 查看改动文件列表
git diff -- <file>                # 查看具体改动内容
git log --oneline -5              # 查看最近提交风格

# ── 第 2 步：暂存（三选一或组合）──
git add <file1> <file2>            # 方式 A：指定文件
git add -u                         # 方式 B：暂存所有已跟踪文件的修改和删除
git add src/                       # 方式 C：暂存某目录下所有新文件
# ⚠️ 禁止 git add -A / git add .（会混入临时文件）

# ── 第 3 步：验证暂存内容 ──
git status --porcelain
# 确认列表中只有源码/资源文件，没有截图/日志/脚本

# ── 第 4 步：提交 ──
# PowerShell 不支持 HEREDOC，用多个 -m 参数
git commit -m "类型: 简短描述" -m "详情行1" -m "详情行2"
# ⚠️ commit message 中避免英文 ()，用中文括号或去掉

# ── 第 5 步：推送 ──
git push origin main

# ── 第 6 步：验证 ──
git for-each-ref --format='%(refname:short) -> %(objectname:short)' refs/heads/main refs/remotes/origin/main
# 两个哈希应一致
```

### 4.3 SOP-3：全量推送所有分支

```powershell
git push origin --all
# 输出中 [new branch] 表示新推送的分支
# 无输出或 Everything up-to-date 表示已是最新
```

### 4.4 SOP-4：从远程恢复单个文件

```powershell
# 恢复到远程版本（覆盖本地修改）
git checkout origin/main -- <file-path>

# 恢复被删除的文件
git checkout origin/main -- <deleted-file-path>
```

### 4.5 SOP-5：全量恢复到远程最新状态

> **警告**：此操作丢弃所有本地改动和未推送的提交，不可逆。

```powershell
# 第 1 步：确认当前分支（避免误动其他分支）
git branch
# 预期：* main

# 第 2 步：拉取远程最新
git fetch origin --prune

# 第 3 步：硬重置
git reset --hard origin/main

# 第 4 步：验证所有分支 HEAD 未被误动
git for-each-ref --sort=-committerdate --format='%(refname:short) -> %(objectname:short)  %(authordate:format:%Y-%m-%d %H:%M)' refs/heads refs/remotes
```

### 4.6 SOP-6：检查同步状态

```powershell
# 查看所有分支最新提交（按时间排序）
git for-each-ref --sort=-committerdate --format='%(refname:short) -> %(objectname:short)  %(authordate:format:%Y-%m-%d %H:%M)  %(subject)' refs/heads refs/remotes

# 查看本地与远程的 ahead/behind
git branch -vv

# 精确对比某个分支
$ahead = git rev-list --count "origin/main..HEAD"
$behind = git rev-list --count "HEAD..origin/main"
# ahead=0 behind=0 → 完全同步
```

---

## 5. PowerShell 环境专项

### 5.1 命令串联

| 操作 | 错误写法 | 正确写法 |
|---|---|---|
| 顺序执行 | `cmd1 && cmd2` | `cmd1; cmd2` |
| 条件执行 | `cmd1 \|\| cmd2` | `if ($LASTEXITCODE -ne 0) { cmd2 }` |
| 换行分隔 | 多行命令 | 用 `;` 分号在同一行串联 |

### 5.2 Git Commit Message

```powershell
# ❌ 错误：HEREDOC 语法（PowerShell 不支持）
git commit -m "$(cat <<'EOF'
标题
详情
EOF
)"

# ✅ 正确：多个 -m 参数（每个 -m 是一个段落）
git commit -m "标题" -m "详情行1" -m "详情行2"

# ⚠️ 注意：commit message 中的英文 () 会被 PowerShell 解析
# ❌ git commit -m "fix: 修复 foo() 方法"
# ✅ git commit -m "fix: 修复 foo 方法"
# ✅ git commit -m "fix: 修复 foo（）方法"  # 中文括号
```

### 5.3 输出处理

```powershell
# git 命令的 stderr 在 PowerShell 中可能被当作错误流
# 用 2>&1 合并输出流
git push origin --all 2>&1

# PowerShell 的 CLIXML 格式可能干扰输出
# 如需纯净输出，用 --porcelain 或 --format 参数
git status --porcelain
git for-each-ref --format='...'
```

### 5.4 脚本执行策略

如果遇到 `cannot be loaded because running scripts is disabled`：

```powershell
# 这是 PowerShell 执行策略限制，不影响 git 命令本身
# git 命令仍可正常执行，只是 profile 脚本加载失败
# 可忽略此错误
```

---

## 6. .gitignore 管理策略

### 6.1 已配置的忽略规则

| 类别 | 规则 | 说明 |
|---|---|---|
| 截图 | `screen*.png` `screenshot*.png` `test_screenshot.png` | 调试截图 |
| 构建日志 | `bo*.txt` `install_output.log` `build*.txt` `build*.log` | 构建输出 |
| 临时脚本 | `fix_chat_adapter.ps1` `*.py`（根目录修复脚本） | 临时修复脚本 |
| Worktree 副本 | `/feat-check-agent-function-SwdOZa/` | 项目副本目录 |
| 反编译 class | `libs/com/` | SDK 反编译的 .class 文件 |
| 构建产物 | `**/build/` `.gradle/` `.cxx/` | Gradle/CMake 构建目录 |
| IDE | `.idea/` `*.iml` `.trae/` | IDE 配置 |
| 密钥 | `*.pem` `*.key` `*.p12` `local.properties` | 敏感文件 |
| 二进制 | `*.apk` `*.aar` `*.7z` `*.zip` `*.msi` | 二进制文件 |

### 6.2 添加新忽略规则的流程

1. 确认文件不属于项目源码/资源
2. 在 `.gitignore` 中添加规则（按类别分区，带注释）
3. 提交 `.gitignore` 更新

```powershell
# 如果文件已被 git 跟踪，需先从索引中移除
git rm --cached <file>
# 然后添加 .gitignore 规则并提交
```

### 6.3 嵌套仓库陷阱（llama.cpp）

`.gitignore:131` 整目录排除了 `src/main/cpp/llama.cpp/`。但**它本身是一个独立 clone 的
git 仓库**（`https://gitcode.com/gh_mirrors/ll/llama.cpp.git`，branch `master`），
所以对它的改动有两层坑：

**坑 1：`git add` 只会加一个 gitlink，内容不会入库**

```powershell
git add src/main/cpp/llama.cpp
# warning: adding embedded git repository: src/main/cpp/llama.cpp
# hint: Clones of the outer repository will not contain the contents of
#       the embedded repository
```

**坑 2：用 `!` 反包含"救"不回来**

直觉上会想加一条例外：

```gitignore
!src/main/cpp/llama.cpp/ggml/.../embed_kernel.py   # ❌ 无效
```

两个原因，缺一不可地让它失败：

1. git 规定 **不能在被排除的父目录内部反包含文件**，必须逐层放行父目录；
2. 即便逐层放行，`git add` 也只会把整个目录当成 embedded repository 加 gitlink，
   拿不到文件内容。

验证方法（不修改任何东西，看"到底会加进去什么"）：

```powershell
git check-ignore -v <file>          # 看最后命中的是哪条规则
git add --dry-run src/main/cpp/llama.cpp
# 输出 `add 'src/main/cpp/llama.cpp/'` = gitlink，不是文件
```

**正确做法**

| 场景 | 做法 |
|---|---|
| 只是本地要改 llama.cpp 源码 | 直接在嵌套仓库里改，并在**嵌套仓库内**提交 |
| 改动必须随外层仓库走（换机器 clone 后仍生效） | 在外层 `build.gradle` 加自愈守卫，构建时自动修 |

本项目用的是第二种。实例：`embed_kernel.py` 在中文 Windows 上会用 GBK 解码 `.cl`
文件导致 `UnicodeDecodeError`，修复方式写在外层 `build.gradle` 的
`dshFixEmbedKernel` 守卫里（幂等，重新 clone llama.cpp 后依然自动生效），
而不是去改那个无法入库的文件。

查看嵌套仓库状态：

```powershell
git -C src/main/cpp/llama.cpp status --porcelain
git -C src/main/cpp/llama.cpp log --oneline -1
```

---

## 7. 提交规范

### 7.1 Commit Message 格式

```
<类型>: <简短描述>

<详情行1>
<详情行2>
```

### 7.2 类型枚举

| 类型 | 用途 | 示例 |
|---|---|---|
| `feat` | 新功能 | `feat: AI导入引擎、天气UI重构` |
| `fix` | Bug 修复 | `fix: 补全自定义View属性声明` |
| `refactor` | 重构 | `refactor: 重命名 AIToolsManager 为 AIToolRegistry` |
| `docs` | 文档 | `docs: 更新设计文档和变更日志` |
| `chore` | 杂项 | `chore: auto-commit worktree changes` |
| `merge` | 合并 | `merge: 合并 Agent 软件层架构升级` |

### 7.3 暂存规则

| 场景 | 命令 | 说明 |
|---|---|---|
| 指定文件 | `git add <file1> <file2>` | 最精确 |
| 已跟踪文件修改+删除 | `git add -u` | 不会包含新文件 |
| 某目录下新文件 | `git add src/` | 暂存 src 下所有新增 |
| **禁止** | ~~`git add -A`~~ | 会混入临时文件 |
| **禁止** | ~~`git add .`~~ | 会混入临时文件 |

---

## 8. 故障排查手册

### 8.1 Gitee push/fetch 返回 404 not found

```
fatal: repository 'https://gitee.com/18295071983/smartquiz.git/' not found
```

**诊断决策树**：

```
404 not found
  │
  ├─ URL 中用户名是否为 xiaocongcong495863994？
  │    ├─ 否 → git remote set-url 修正（见 2.3 节）
  │    └─ 是 → 继续排查
  │
  ├─ 令牌是否有效？
  │    ├─ 调用 API 验证：Invoke-RestMethod "https://gitee.com/api/v5/user?access_token=<token>"
  │    ├─ API 报错 → 令牌过期，到 Gitee 重新生成
  │    └─ API 成功 → 继续排查
  │
  └─ 仓库是否存在？
       ├─ 浏览器访问 https://gitee.com/xiaocongcong495863994/smartquiz
       └─ 确认仓库未被删除/改名
```

**修复命令**：

```powershell
# 更新 remote URL（替换令牌）
git remote set-url origin "https://xiaocongcong495863994:<新令牌>@gitee.com/xiaocongcong495863994/smartquiz.git"

# 验证
git fetch origin --prune
```

### 8.2 reset --hard 误动其他分支

**原因**：`git reset --hard` 影响的是**当前检出分支**，不是命令中指定的远程分支。

**预防**：执行前务必 `git branch` 确认当前检出的分支（带 `*` 标记）。

**修复**：

```powershell
# 1. 先切出被误动的分支
git checkout <其他分支>

# 2. 强制恢复误动分支到原哈希
git branch -f <误动分支> <原哈希>

# 3. 切回工作分支
git checkout main
```

### 8.3 push 被拒绝（non-fast-forward）

```
! [rejected]  main -> main (fetch first)
```

**处理**：

```powershell
# 方式 A：先拉取合并（推荐）
git fetch origin
git merge origin/main
git push origin main

# 方式 B：确认本地是正确版本后，强制推送（需用户确认）
# ⚠️ 这会覆盖远程历史，仅在没有他人协作时使用
git push origin main --force-with-lease
```

### 8.4 工作区有大量未跟踪文件

**排查**：

```powershell
git status --porcelain | Select-String "^\?\?"
```

**处理**：

| 文件类型 | 处理方式 |
|---|---|
| 源码/资源（.java/.xml） | `git add` 提交 |
| 截图（.png） | 加入 .gitignore |
| 日志（.txt/.log） | 加入 .gitignore |
| 临时脚本（.ps1/.py） | 加入 .gitignore |
| 项目副本目录 | 加入 .gitignore |
| 反编译文件（.class） | 加入 .gitignore |

### 8.5 git fetch 返回但数据不更新

```powershell
# 可能是本地缓存问题，强制刷新
git fetch origin --prune --force
# --prune：清除远程已删除的分支引用
# --force：强制更新本地引用
```

---

## 9. 版本回退与灾难恢复

### 9.1 回退到某个提交

```powershell
# 查看提交历史
git log --oneline -20

# 方式 A：创建回退提交（保留历史，推荐）
git revert <commit-hash>
git push origin main

# 方式 B：硬重置（丢弃历史，需用户确认）
git reset --hard <commit-hash>
git push origin main --force-with-lease
```

### 9.2 恢复误删的本地分支

```powershell
# 查看 reflog 找到丢失的提交
git reflog

# 从 reflog 恢复
git branch <分支名> <reflog中的哈希>
```

### 9.3 从远程完全恢复本地仓库

```powershell
# ⚠️ 最后手段：删除本地仓库重新克隆
cd d:\
Rename-Item d:\qzq\smartquiz d:\qzq\smartquiz.bak
git clone git@gitee.com:xiaocongcong495863994/smartquiz.git d:\qzq\smartquiz
cd d:\qzq\smartquiz
git checkout main
```

### 9.4 备份当前工作区（不提交到远程）

```powershell
# 创建临时备份分支
git checkout -b backup-$(Get-Date -Format "yyyyMMdd-HHmm")
git add -A
git commit -m "chore: backup worktree before operation"
# 如需恢复：git checkout backup-xxx -- .
```

---

## 10. 最佳实践 DO / DON'T

### DO ✅

- [x] 操作前先 `git branch` 确认当前检出分支
- [x] `git add` 只指定文件/目录，不用 `-A` 或 `.`
- [x] commit message 用多个 `-m` 参数（PowerShell）
- [x] push 后验证本地与远程哈希一致
- [x] reset --hard 后检查所有分支 HEAD
- [x] 临时文件及时加入 .gitignore
- [x] Gitee URL 用户名用 `xiaocongcong495863994`

### DON'T ❌

- [ ] 不要用 `git add -A` / `git add .`（会混入临时文件）
- [ ] 不要用 bash HEREDOC `$(cat <<'EOF')`（PowerShell 不支持）
- [ ] 不要用 `&&` / `||` 串联 PowerShell 命令
- [ ] 不要在 commit message 中使用英文 `()`
- [ ] 不要在未确认当前分支时执行 `git reset --hard`
- [ ] 不要用 `git push --force`（用 `--force-with-lease` 代替）
- [ ] 不要在 Gitee URL 中用手机号 `18295071983` 作为用户名
- [ ] 不要擅自修改 `.git/config` 中的全局配置（user.name / user.email）
- [ ] 不要提交 `.env` / `*.pem` / `local.properties` 等敏感文件

---

## 11. 快速参考卡

### 日常操作速查

```powershell
# 同步
git fetch origin --prune; git reset --hard origin/main

# 提交推送
git add -u; git add src/; git commit -m "类型: 描述"; git push origin main

# 全量推送
git push origin --all

# 检查状态
git status -sb
git for-each-ref --sort=-committerdate --format='%(refname:short) -> %(objectname:short)' refs/heads refs/remotes
```

### 关键信息速查

| 项目 | 值 |
|---|---|
| 工作目录 | `d:\qzq\smartquiz` |
| 主开发分支 | `main` |
| 主远程 | `origin` |
| Gitee 用户名 | `xiaocongcong495863994` |
| Gitee 仓库名 | `smartquiz` |
| Gitee 仓库 URL | `git@gitee.com:xiaocongcong495863994/smartquiz.git`（SSH） |
| GitHub 远程 | `origin` (SSH) |
| Shell 类型 | PowerShell 5 |
| 命令分隔符 | `;`（分号） |
