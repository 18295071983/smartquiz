# SmartQuiz Git 操作指南（详细版）

> **文档目的**：为 AI Agent 和开发者提供完整的 Git 操作规范，避免重复踩坑。
> **最后更新**：2026-07-31
> **维护者**：SmartQuiz Developer

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
                    ┌─────────────────────────────────┐
                    │         本地仓库 (d:\qzq\smartquiz)          │
                    │                                 │
                    │  feat-push-to-remote-project-BZZBBM ★  主开发 │
                    │  main                               主干     │
                    │  feat-check-agent-function-SwdOZa   备份快照 │
                    │  feature/agent-v3-iteration         设计分支 │
                    └──────────┬──────────┬─────────────┘
                               │          │
                    fetch/push │          │ fetch/push
                               ▼          ▼
                    ┌──────────────┐   ┌──────────────────┐
                    │    Gitee     │   │     GitHub       │
                    │  (gitee)     │   │    (origin)      │
                    │  HTTPS+Token │   │  SSH (git@)      │
                    │  主远程仓库   │   │  备用远程仓库     │
                    └──────────────┘   └──────────────────┘
```

### 仓库关系说明

- **Gitee（`gitee`）**：主远程仓库，日常推送/拉取的首选目标
- **GitHub（`origin`）**：备用远程仓库，使用 SSH 协议，需配置 SSH key
- 本地 4 个分支已全部推送到 Gitee；GitHub 同步状态待确认（SSH 连通性未验证）

---

## 2. 远程仓库配置

### 2.1 远程地址

| 远程名 | 平台 | 协议 | URL |
|---|---|---|---|
| `gitee` | Gitee | HTTPS + Token | `https://gitee.com/xiaocongcong495863994/smartquiz.git` |
| `origin` | GitHub | SSH | `git@github.com:18295071983/smartquiz.git` |

### 2.2 Gitee 认证信息

| 项目 | 值 |
|---|---|
| Gitee Login（用户名） | `xiaocongcong495863994` |
| Gitee 用户 ID | `8432792` |
| 仓库名 | `smartquiz` |
| 仓库可见性 | Public |
| 认证方式 | HTTPS Basic Auth（用户名 + 个人访问令牌） |
| 令牌存储方式 | 嵌入 remote URL（`https://<user>:<token>@gitee.com/...`） |

> **关键**：Gitee 的 login 是 `xiaocongcong495863994`，不是手机号 `18295071983`。
> 手机号只用于登录 Gitee 网站，不是 git URL 中的用户名路径。

### 2.3 令牌刷新流程

当 Gitee push/fetch 返回 `404 not found` 时，按以下步骤刷新令牌：

```powershell
# 第 1 步：验证令牌是否有效（调用 Gitee API）
$r = Invoke-RestMethod -Uri "https://gitee.com/api/v5/user?access_token=<新令牌>" -Method Get -TimeoutSec 30
# 如果返回用户信息 → 令牌有效
# 如果报错 → 令牌无效，需到 Gitee 设置 → 私人令牌 重新生成

# 第 2 步：更新 remote URL（替换 <新令牌>）
git remote set-url gitee "https://xiaocongcong495863994:<新令牌>@gitee.com/xiaocongcong495863994/smartquiz.git"

# 第 3 步：验证连通性
git fetch gitee --prune
# 输出 "From https://gitee.com/..." 表示成功
```

### 2.4 查看当前远程配置

```powershell
git remote -v              # 查看所有远程
git remote get-url gitee   # 查看指定远程 URL
git branch -vv             # 查看分支跟踪关系
```

---

## 3. 分支管理策略

### 3.1 分支模型

```
main ─────────────────────────────────────────── 稳定主干
  │
  └─ feat-push-to-remote-project-BZZBBM ──────── 主开发分支（日常在此工作）
       │
       ├─ feat-check-agent-function-SwdOZa ───── 功能备份快照（只读）
       │
       └─ feature/agent-v3-iteration ──────────── 设计迭代分支
```

### 3.2 分支清单

| 分支名 | 用途 | 跟踪的远程 | 当前 HEAD | 备注 |
|---|---|---|---|---|
| `feat-push-to-remote-project-BZZBBM` ★ | 日常开发 | `gitee/feat-push-to-remote-project-BZZBBM` | `20538fa` | **当前检出** |
| `main` | 稳定主干 | `gitee/main` | `20538fa` | 与开发分支同步 |
| `feat-check-agent-function-SwdOZa` | Agent 重构前备份 | 无（已推送） | `d53e2c6` | 只读快照，勿修改 |
| `feature/agent-v3-iteration` | Agent v3 设计文档 | 无（已推送） | `41acc47` | 设计阶段分支 |

### 3.3 分支命名规范

| 前缀 | 用途 | 示例 |
|---|---|---|
| `feat-` | 功能开发 | `feat-weather-ui-redesign` |
| `feature/` | 功能模块（带子路径） | `feature/agent-v3-iteration` |
| `fix-` | Bug 修复 | `fix-chat-adapter-crash` |
| `hotfix-` | 紧急修复 | `hotfix-api-key-leak` |
| `docs-` | 文档更新 | `docs-update-readme` |

### 3.4 远程独有分支

以下分支仅存在于 Gitee 远程，本地未跟踪：

| 远程分支 | 最新提交 | 内容 |
|---|---|---|
| `gitee/feature/ai-chat-fix` | `5f7f97c` | AILogger 修复 + AIToolRegistry 重命名 |
| `gitee/feature/secure-api-keys` | `4f536f4` | 安全密钥管理（移除硬编码 API Key） |

如需跟踪：
```powershell
git checkout -b feature/ai-chat-fix gitee/feature/ai-chat-fix
git checkout -b feature/secure-api-keys gitee/feature/secure-api-keys
```

---

## 4. 标准操作流程（SOP）

### 4.1 SOP-1：开始工作前同步远程

```powershell
cd d:\qzq\smartquiz

# 确认当前分支
git branch

# 拉取远程最新引用
git fetch gitee --prune

# 如果远程有新提交，重置到远程最新
git reset --hard gitee/feat-push-to-remote-project-BZZBBM

# 验证工作区干净
git status -sb
# 预期输出：## feat-push-to-remote-project-BZZBBM...gitee/feat-push-to-remote-project-BZZBBM
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
git push gitee feat-push-to-remote-project-BZZBBM

# ── 第 6 步：验证 ──
git for-each-ref --format='%(refname:short) -> %(objectname:short)' refs/heads/feat-push-to-remote-project-BZZBBM refs/remotes/gitee/feat-push-to-remote-project-BZZBBM
# 两个哈希应一致
```

### 4.3 SOP-3：全量推送所有分支

```powershell
git push gitee --all
# 输出中 [new branch] 表示新推送的分支
# 无输出或 Everything up-to-date 表示已是最新
```

### 4.4 SOP-4：从远程恢复单个文件

```powershell
# 恢复到远程版本（覆盖本地修改）
git checkout gitee/feat-push-to-remote-project-BZZBBM -- <file-path>

# 恢复被删除的文件
git checkout gitee/feat-push-to-remote-project-BZZBBM -- <deleted-file-path>
```

### 4.5 SOP-5：全量恢复到远程最新状态

> **警告**：此操作丢弃所有本地改动和未推送的提交，不可逆。

```powershell
# 第 1 步：确认当前分支（避免误动其他分支）
git branch
# 预期：* feat-push-to-remote-project-BZZBBM

# 第 2 步：拉取远程最新
git fetch gitee --prune

# 第 3 步：硬重置
git reset --hard gitee/feat-push-to-remote-project-BZZBBM

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
$ahead = git rev-list --count "gitee/feat-push-to-remote-project-BZZBBM..HEAD"
$behind = git rev-list --count "HEAD..gitee/feat-push-to-remote-project-BZZBBM"
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
git push gitee --all 2>&1

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
git remote set-url gitee "https://xiaocongcong495863994:<新令牌>@gitee.com/xiaocongcong495863994/smartquiz.git"

# 验证
git fetch gitee --prune
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
git checkout feat-push-to-remote-project-BZZBBM
```

### 8.3 push 被拒绝（non-fast-forward）

```
! [rejected]  feat-push-to-remote-project-BZZBBM -> feat-push-to-remote-project-BZZBBM (fetch first)
```

**处理**：

```powershell
# 方式 A：先拉取合并（推荐）
git fetch gitee
git merge gitee/feat-push-to-remote-project-BZZBBM
git push gitee feat-push-to-remote-project-BZZBBM

# 方式 B：确认本地是正确版本后，强制推送（需用户确认）
# ⚠️ 这会覆盖远程历史，仅在没有他人协作时使用
git push gitee feat-push-to-remote-project-BZZBBM --force-with-lease
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
git fetch gitee --prune --force
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
git push gitee feat-push-to-remote-project-BZZBBM

# 方式 B：硬重置（丢弃历史，需用户确认）
git reset --hard <commit-hash>
git push gitee feat-push-to-remote-project-BZZBBM --force-with-lease
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
git clone https://xiaocongcong495863994:<token>@gitee.com/xiaocongcong495863994/smartquiz.git d:\qzq\smartquiz
cd d:\qzq\smartquiz
git checkout feat-push-to-remote-project-BZZBBM
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
git fetch gitee --prune; git reset --hard gitee/feat-push-to-remote-project-BZZBBM

# 提交推送
git add -u; git add src/; git commit -m "类型: 描述"; git push gitee feat-push-to-remote-project-BZZBBM

# 全量推送
git push gitee --all

# 检查状态
git status -sb
git for-each-ref --sort=-committerdate --format='%(refname:short) -> %(objectname:short)' refs/heads refs/remotes
```

### 关键信息速查

| 项目 | 值 |
|---|---|
| 工作目录 | `d:\qzq\smartquiz` |
| 主开发分支 | `feat-push-to-remote-project-BZZBBM` |
| 主远程 | `gitee` |
| Gitee 用户名 | `xiaocongcong495863994` |
| Gitee 仓库名 | `smartquiz` |
| Gitee 仓库 URL | `https://gitee.com/xiaocongcong495863994/smartquiz.git` |
| GitHub 远程 | `origin` (SSH) |
| Shell 类型 | PowerShell 5 |
| 命令分隔符 | `;`（分号） |
