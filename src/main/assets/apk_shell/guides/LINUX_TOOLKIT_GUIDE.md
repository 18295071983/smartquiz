# 内置 Linux 命令行工具箱指南（linux_shell）

> 版本：v1.1 · 随 App 打包，**无需安装 Termux、无需任何权限**
> v1.1 变更：结果口径改为结构化（`exit_code` / `status` / `timed_out` / `truncated`），
> 超时与输出超限改为**连同子进程一起终止**，`readonly` 档按"防误操作黑名单"重新定位。
> 相关文件：`files/使用速查表.md`（速查）、`files/核心工具速查.md`（工具清单）

## 一、为什么要有它

Android 对应用执行外部程序有两条硬限制，这个工具箱就是绕开它们的产物：

1. **不能执行 App 数据目录里的文件**（实测 `Permission denied`，exit 126）。
   所以「下载一个二进制再运行」**永远走不通**；只有随 APK 打包、被系统解压到
   `nativeLibraryDir` 的文件才能执行。
   例外：**脚本可以**（`sh /路径/脚本.sh` 由解释器读取文件，不是 execve）。
2. **静态 musl 二进制会被 seccomp 杀**（SIGSYS，`Bad system call`）。
   所以内置的都是 Termux 的 **bionic** 构建（与系统自带 toybox 同类）。

## 二、怎么调用

| 入口 | 用法 |
|---|---|
| 独立工具（推荐） | `linux_shell(action=exec, command="curl -sI https://example.com")` |
| 列出工具与版本 | `linux_shell(action=tools)` |
| 下载 URL 到文件 | `linux_shell(action=download, url=..., path=...)`（https 走系统证书校验） |
| 改命令路由 | `linux_shell(action=route, command=jq, order=b)`（`reset` 恢复默认） |
| 兼容入口 | `system_resource(action=shell_command, command=...)` |
| Termux（需另装） | `system_resource(action=termux_exec, command=...)` |

## 三、内置工具清单

| 类别 | 命令 |
|---|---|
| Shell / 核心 | `busybox`（280+ applet：ash、awk、vi、telnet、tar、gzip、nc、httpd…）、`gawk` |
| 加密 / 网络 | `openssl`（真 TLS，含 CA 包）、`curl`（https）、`ssh` `scp` `sftp` `ssh-keygen` `ssh-keyscan` `ssh-add`、`aria2c`（多线程下载/断点续传） |
| 文本 / 数据 | `jq`、`rg`(ripgrep)、`sqlite3`、`file`、`tree` |
| 压缩 | `zstd`、`zip`、`unzip`（tar/gzip 由 busybox 提供） |
| 系统 / 终端 | `htop`、`ps`、`free`、`ncdu`、`tmux`、`nano` |
| 系统自带（直接可用） | toybox：sed / grep / find / sort / head / tail / wc / md5sum / base64 / xargs / diff / du / df … |

## 四、环境（已自动配好）

- `PATH`：先内置目录，再 /system/bin 等
- `HOME` = App 私有目录；`TMPDIR` = App 缓存目录
- `SSL_CERT_FILE` / `CURL_CA_BUNDLE` = 内置 CA 包
- 需要库的工具有自己的启动器设置库路径；**不会**污染系统命令

## 五、命令路由（重要）

每个命令按顺序找可用实现，**execv 失败自动换下一个**，不会因为某个实现不可用把命令搞挂：

```
内置(lib<name>_bin.so) → /system/bin/<name> → busybox applet → toybox <name>
```

- 查看/修改：`linux_shell(action=route)`、`linux_shell(action=route, command=curl, order=stkb)`
- 顺序字符：`b`=内置、`s`=系统、`k`=busybox、`t`=toybox
- 配置落在工作区外的 `files/bin/.route`（每行 `命令=顺序`），改完立即生效；
  重装 App 会自动保留

## 六、常见用法示例

```sh
# JSON 处理
curl -s https://api.example.com/data.json | jq '.items[] | select(.ok) | .name'
# 全局搜索（比 grep 快）
rg -n "TODO|FIXME" /sdcard/Download --glob '*.md'
# 数据库
sqlite3 $HOME/test.db "create table t(a); insert into t values(1); select * from t;"
# 压缩
tar -czf $HOME/backup.tar.gz /sdcard/Download/notes && zstd -19 $HOME/backup.tar.gz
# 多线程下载 + 断点续传
aria2c -x8 -s8 -d $HOME -o big.zip "https://example.com/big.zip"
# TLS 调试
echo | openssl s_client -connect www.baidu.com:443 -servername www.baidu.com 2>&1 | head -5
# SSH（注意 Termux 默认 ~/.ssh 路径不可写，用 -o 指定）
ssh -o UserKnownHostsFile=$HOME/.ssh/known_hosts -o IdentityFile=$HOME/.ssh/id_ed25519 -p 443 git@ssh.github.com
# 长任务放 tmux：tmux server 会脱离本命令的进程树，超时强杀杀不到它
tmux new-session -d -s job 'aria2c -d $HOME -o f.iso URL'
```

## 七、限制与坑

| 项 | 说明 |
|---|---|
| 单条命令超时 | **25 秒**（框架默认 30s，这里留 5s 余量让自己的超时信息先到）。超时返回已产生的输出并带 `timed_out: true`，同时**连同该 shell 的子进程一起终止** —— 用 `&` 丢在后面的任务也会结束，要跨调用长跑请用 `tmux`（server 已脱离本命令的进程树，杀不到）。超时那次取不到退出码，结果里**没有** `exit_code` |
| 输出上限 | **2 万字符**；超限立即终止进程树并标 `truncated: true`（不是继续读到超时）。请改用 `head -n` / `rg --max-count` 限流，或先写文件再用 `file_reader` 读 |
| 结果字段 | `status`（`success`/`failed`/`timeout`）、`exit_code`（取不到时省略该键，不填 -1 冒充）、`timed_out` / `truncated`（只在真发生时出现）、`output`、`hint` |
| 退出码非零 | 判为 `status: "failed"`，但其中一部分是**正常语义**不是工具坏了：`grep`/`rg` 无匹配=1、`diff` 有差异=1、`test` 条件不成立=1、`curl` 拒绝=22、文件不存在=2、命令名不在 PATH=127。请按 `output` 决定下一步，别默认重试同一条命令 |
| 命令拦截 | 默认 `full`＝不拦截（用户自己的设备）。`system_resource(action=shell_mode, mode=readonly)` 开的是**防误操作黑名单**：按命令文本匹配，变量替换/别名/改名都能绕；而且这个开关本身也能被工具切换，App 里没有设置页能锁住它——别把它当权限边界依赖 |
| 脚本 vs 二进制 | 脚本放工作区用 `sh 路径/脚本.sh` 跑没问题；**二进制不能**（见第一节） |
| 公共目录 | `/sdcard/Download/OilQuiz/...` 是 FUSE **noexec**，只能当数据/脚本仓库 |
| 同名命令 | 路由默认先内置；若某命令在系统里有更好实现，用 `route` 调成 `stkb` |
| 权限 | 系统能力（存储全盘/定位/相机）仍需用户授权；`/data/data/其他应用` 读不到 |
| 许可 | busybox 等为 GPL 系许可，随 App 分发需按各自许可提供源码 |
| 未内置 | git（依赖不可执行的 libexec 辅助程序）、ffmpeg（Termux 依赖图有空壳库 + 与系统库同名冲突，详见提交说明）。**音视频/图片请用 media_toolkit**（系统硬解硬编，见《MEDIA_TOOLKIT_GUIDE.md》） |

## 八、在 Python 里用同一套工具

```python
import android_shell
print(android_shell.available())                      # 列出内置工具与版本
r = android_shell.run("jq -r '.name' data.json")      # 返回 dict(exit_code/stdout/stderr)
print(r["stdout"])
r2 = android_shell.run_argv(["rg", "-n", "TODO", "/sdcard/Download"])   # 不经 shell
print(android_shell.tool_path("sqlite3"))             # 取绝对路径
```

## 九、怎么往里加工具（开发者）

```bash
# 1) 编辑 TOOLS 列表（工具名, 包名, 包内路径）
#    文件：tools/tests/bundle_termux_bins.py
# 2) 重新打包（会自动解析包依赖闭包、生成 lib<name>_bin.so 与清单）
python tools/tests/bundle_termux_bins.py
# 3) ffmpeg：脚本里保留了 --with-ffmpeg（依赖约 90MB），但**实测装了也跑不起来**
#    （Termux 依赖图里有空壳 .so，且 libc++/libz/libssl 与系统库同名同 ABI 冲突；
#      详见提交 56a8821 的记录），所以当前不要开。音视频需求走 media_toolkit。
```

Java 侧不用改：运行时按 `libtoolkit_manifest.so` 清单把工具软链接进 `bin/`，
再由 `liblauncher.so`（路由器）按顺序执行。

## 十、排错

1. `linux_shell(action=tools)` 看不到某工具 → 看 `files/lib`、`files/toolkit_lib` 是否解包成功
   （应用日志里搜「通用工具包就绪」「解包」）
2. 某命令行为不对 → `linux_shell(action=route)` 看路由，必要时 `order=stkb` 强制走系统
3. 报 `没有可用实现` → 该名字内置/系统/busybox/toybox 都没有；先用 `action=tools` 确认名字
4. 报 `Permission denied` → 多半在执行工作区里的二进制；改成 `sh 脚本` 或用内置命令
