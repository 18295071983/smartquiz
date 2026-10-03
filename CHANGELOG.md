# 变更日志

## [2026-10-03] AI 新增 ssh_exec 工具（JSch 纯 Java 版）+ edge-to-edge 全面屏适配

### ssh_exec 工具（agent 连接任意 SSH 主机）
1. 新增 AI 工具 `ssh_exec`：SSH 连接**任意远程主机**（电脑/云服务器/路由器/NAS——任何开 sshd 的机器）执行单条命令；非交互、25 秒超时；返回 stdout/stderr/退出码；连接失败按错误类型给排查提示。
2. 实现演进（本窗口）：内置 OpenSSH 二进制 + sshpass/SSH_ASKPASS 取巧 → **JSch 纯 Java 实现**（`com.github.mwiede:jsch:2.27.7`）：
   - 密码认证：**JSch 原生**，开箱即用，彻底不依赖 Termux/sshpass/askpass；
   - 密钥认证：key_file 指定，缺省用 App 内 `~/.ssh/id_ed25519`，不存在时返回引导（shell_command 一键生成 + 公钥放目标机 authorized_keys）；
   - 8s 连接超时 + 25s 总超时，读线程 + channel 轮询，stdout/stderr 分离。
3. 全链路接入：@Action/@Param 描述、AIToolManager 定义、必填预检（host/command/user）、对话引导流程（新增"SSH 连接远程主机"选项 + 主机/用户名/端口/命令四步输入）、结果展示。
4. **依赖坑（必读）**：jsch 2.28.7/2.27.7 的 jar 内含 **Java 24 专用后量子 ML-KEM class**（`META-INF/versions/24/`，字节码 major 68）——Jetifier 扫描整个 jar 报 `Unsupported class file major version 68`。解法：`gradle.properties` 加 `android.jetifier.ignorelist=jsch` 跳过转换（纯 Java 库无需 AndroidX 转换；Kotlin/D8 只读 Java 8 基础类）。**Java 24 整体升级评估结论：不建议**——AGP 8.x 的 D8/R8 不支持 major 68、Gradle 8.13 需升 8.14+、ML-KEM 手机 SSH 用不上，收益≈0。
5. 内置 SSH 终端此前已删除（10-01：外部 SSH 工具正常、内置页面连上卡/断），ssh_exec 是工具化命令执行，与终端无关。

### edge-to-edge 全面屏适配（小米底部小白条）
1. 按官方文档（targetSdk 35+ 强制 e2e、Dialog 同样强制）重构 `EdgeToEdgeHelper`：apply() 改 `systemBars()|displayCutout()` 组合；applyInsets() 底部取 `max(navigationBars, systemGestures)`（防个别 MIUI 手势条 inset 报 0）；新增 `applyDialog(Dialog)`（透明双栏 + 根 padding + 深浅图标采样）。
2. 全项目核查底部贴边弹窗仅两个：SpeechModelSelectorDialog / OCRModelSelectorDialog（均 Gravity.BOTTOM）→ 接入 applyDialog()；CitySearchDialog 等居中弹窗不触导航栏未改；Material BottomSheetDialog 官方自动兼容。
3. 闪烁根因修复：普通界面在 e2e 下自绘了与系统栏重叠的背景、又随系统栏 inset 反复重绘 → 与白条抢显示；统一改为系统栏透明 + 根容器按 insets padding（状态栏/手势条/导航栏不再重叠）。

## [2026-10-01 ~ 10-02] VNC/图形界面整体删除 + Termux 一键准备收敛 + 内置包签名修复

1. **VNC/图形界面功能整体删除**（代码+UI+依赖）：远程 VNC 不稳、容器内 apt/图形依赖反复失败、用户拍板不再要；`com.oilquiz.app.vnc` 客户端、启动器、`~/ubuntu-gui` 脚本、相关 UI 与文档段落全部移除。
2. **Termux 一键准备收敛为 6 步**：通道 → 存储 → proot → 容器 → 收尾（原 8 步删掉 python3 自动安装与图形界面两步）；容器内 python3 改为需要时手动 `apt-get install python3`。
3. **dpkg 中断修复**：收尾步 apt 前自动 `dpkg --configure -a`（幂等，修复此前安装被打断的遗留——不修后面所有 apt 都会拒绝干活）。
4. **挖出的真 bug**：step4 生成安装脚本时 heredoc 换行符变成字面 `\n`（源替换转义写错），导致 apt 源没写进去、命令错乱；该步删除后坏脚本随之消失（step5 是正确写法）。
5. **内置包签名修复**：内置 Termux:API/Boot 原为 GitHub debug 签名版，与 F-Droid 签名的 Termux 主应用不兼容被系统拒绝安装；换 F-Droid 官方签名版；proot/内置包同步更新。
6. **内置 SSH 终端删除**：用户实测外部 SSH 工具连接正常、App 内置 SSH 页面连上卡/断，通道不稳定；内置页面整体删除（含 JSch 依赖），后续由 AI `ssh_exec` 工具承担远程命令能力。
7. **proot/rootfs 内置**：proot-distro 与 ubuntu-base 内置仍偶发失败，最终走联网+清华源方案跑通；python3 不再自动装（用户拍板）。

## [2026-09-30] Termux 一键准备向导化 + Termux:API/Boot 内置 + MediaStore 存储迁移 + 主题适配

1. **一键准备向导化重构**：从"一股脑传 setup.sh"改为向导状态机（TermuxEnvSetupActivity）：分步检测（Termux 是否安装/授权/allow-external-apps → 存储 → proot → 容器 → 收尾），每步独立检测、不满足引导用户点按钮复制代码到 Termux，前面未完成不自动执行后面；支持监控日志容器、会话切换提示（toast）。
2. **Termux:API / Termux:Boot 内置 + 选装入口**：两个配套应用 APK 内置到 assets，一键准备界面可选安装；Boot 用于开机自启 Termux 会话。
3. **统一 StorageWriter（MediaStore 优先）**：所有工具类存储迁移到 MediaStore API（/sdcard/OilQuiz 根目录工作区绕不开时引导授权）；解决 targetSdk 36 下公共目录写入问题。
4. **文件同步与残留清理**：私有目录 → 公共目录迁移同步；AI 对话页面的日志/对话记录/使用记录可管理清理（防 App 占用存储越来越大）。
5. **主题适配**：agent 管理界面文字增加边框/底色（防壁纸导致看不清）；深色模式适配；硬编码颜色清理。

## [2026-09-29] 面板没用的插件 + websockify 归属（并记录一个未解的偶发问题）

用户问「目前 xfce 有什么问题」，体检后修了两条：

1. **面板里的 pulseaudio 插件换成 systray** ✓
   容器里**没装 pulseaudio**，但面板配置里有该插件 → 最近 400 行日志里 **77 次**
   「Disconnected from the PulseAudio server. Attempting to reconnect in 5 seconds...」纯刷屏；
   同时配置里**没有 systray** → WPS 之类的托盘图标不显示。
   做法：把 `plugin-8` 就地由 `pulseaudio` 改成 `systray`（plugin-ids 里的位置原样保留），
   并写进外壳脚本、**在 xfce4-panel 启动之前**执行（面板退出时会把自己的配置写回去覆盖）。
   实测：`panel plugin-8=value="systray"` ✓，重启后最近 200 行 **PulseAudio 刷屏 = 0** ✓。

2. **websockify 的归属问题** ✓（这条是修 1 的过程中撞出来的真 bug）
   `kill_stale` 每次启动/重启都会把 websockify 清掉，而 **「桌面已就绪（GUI_ALREADY_UP）」那条分支
   不会再把它拉起来** → 真机现象：Xvnc/桌面都在跑，但 **6080 没人听、noVNC 网页 http=000**，手机上看不到画面。
   修了三处：
   - 启动器加 `ensure_websockify()`：**直接探 6080**（比 pgrep 可靠），不通才拉起；
   - `kill_stale` 的匹配放宽：原来只认 `argv[1] 以 /websockify 结尾`，**裸命令 `websockify` 漏网** →
     残留实例占着 6080，新起的 bind 失败（日志 `OSError: [Errno 98] Address already in use`）；
   - 外壳会话里加 **websockify 看门狗**（5 秒一轮：页面不通才拉起，通了就只探活），
     让它归那条长活会话所有、能自愈。
   实测：杀 websockify → `000` → 跑一次启动器 → **`200`** ✓。

3. **未解问题（如实记录，别当成已修）**：真机上偶发**整条图形会话消失**
   （Xvnc/外壳/组件一起没，Xvnc 日志里最后只有正常客户端断开，Termux 进程本身还活着 1 小时以上，
   没有 OOM/被杀记录）。今天遇到 3 次。另外 6080 释放有竞态，看门狗要等下一轮（5 秒）才能抢到端口。
   缓解：启动器 `UP()` 会判"没有活着的 Xvnc"从而整条重建 ✓（实测能自动恢复）；
   根因还没定，下次要抓的是"谁把 Xvnc 收走的"（需要在消失瞬间采样 `/proc/*/stat` + logcat `am_kill`）。

## [2026-09-28] 修「桌面双击图标报 Launch Error」：文件管理器服务没人应答

用户让我看他手机里 App 的 AI 对话记录，记录里有张截图 —— 双击桌面上的 `WPS表格.desktop` 报
`This feature requires a file manager service to be present (such as the one supplied by thunar)`。
App 自带 AI 那句诊断（外壳脚本没起 thunar 守护）**是对的，而且当时确实还没修** ✗。真机核实：

1. `thunar` 进程数 = **0**；日志里是
   `Activating service 'org.freedesktop.FileManager1' requested by xfdesktop` →
   `Activated service ... failed: Process ... exited with status 1`。
2. 根因跟当天 notifyd 那次是**同一个**：**D-Bus 按需激活出来的实例拿不到 `DISPLAY`** ——
   激活环境用的是总线自己的环境，里面没有 DISPLAY，实例起来就 `cannot open display:` 退 1。
3. 修法两条：总线起来后 `dbus-update-activation-environment DISPLAY XAUTHORITY LANG LC_ALL`
   （让所有激活实例都拿到 DISPLAY）；再显式起 `thunar --daemon`（不依赖激活）。
   脚本还会在**同一条会话内**用 `GetNameOwner` 自查两个服务名有没有注册并写进日志备查；
   清场时把旧 thunar 守护一起清掉（它挂在**上一条**总线上，新会话的客户端找不到它）。
4. 真机验证（App 走一遍启动流程后）：`thunar=1`、外壳与面板同一条总线、日志出现
   `OILQUIZ_SHELL 文件管理器服务已注册 ✓ org.xfce.FileManager` 与
   `... ✓ org.freedesktop.FileManager1`，日志末尾**没有** FileManager 激活失败，noVNC 200 ✓
5. 顺带把那个"startxfce4 计数 = 2"的疑点查清：`pgrep -af startxfce4` 的**真实列表是空的** ——
   前几次都是我的计数被外层 proot 命令行里的字面量污染出来的**假阳性** ✗（也说明独占兜底一直在生效）。

## [2026-09-27] 「菜单里点没反应」根因：两套桌面会话抢屏幕（外加更正几次"总线已死"的误判）

用户报「我菜单里点没反应啊，你检查下菜单」。查下来是两个独立问题，外加我自己几次误判：

1. **同一个 X 上跑着两套 XFCE 会话** ✗：`ensure_desktop()` 另起了一条
   `dbus-run-session -- startxfce4`，`ensure_shell()` 又起了 `.quiz_shell.sh` 那条 ——
   于是出现**两个面板**（`xfce4-panel` 的单实例检测走会话总线，两条总线互相看不见，
   各自都认为自己唯一），而**窗口管理器只有 `.quiz_shell.sh` 那条有**
   （`startxfce4` 在 proot 里退回 Failsafe，不拉 xfwm4）。两个面板都贴屏幕顶部重叠，
   用户点到哪一份全看谁在上面。
   修法：`ensure_shell()` 改成按「外壳脚本在不在」判断（原来按 `xfce4-panel` 判断，
   面板还没起来时就会重复起一条）；`ensure_desktop()` 在外壳已运行时**只清理**抢屏幕的
   `startxfce4` / `xfce4-session`（含被 D-Bus 激活起来的）；调用顺序改成先 `ensure_shell` 再 `ensure_desktop`。
   实测组件数：`xfwm4=1 xfce4-notifyd=1 xfce4-panel=1 xfdesktop=1 xfce4-session=0` ✓
2. **更正我前面几次"总线已死"的误判** ✗✗（真机隔离实验）：
   **同一条会话里** `dbus-send --session … ListNames` **✓ 通**；换一个
   `proot-distro login` 会话去连**同一条**总线 **✗ 不通**（socket 文件明明在、`-S` 也判真）。
   原因是 proot 下 D-Bus 鉴权要用 `SO_PEERCRED` 读对端身份，另一个 proot 实例拿不到，
   客户端会一直卡在 AUTH 直到超时（报的就是 `Did not receive a reply … the reply timeout expired`）。
   X 之所以能跨会话用，是因为 Xvnc 带了 `-ac`（根本不查授权）。
   **结论：总线 + 全部客户端必须待在同一个 proot 会话里**，不许再从别的会话去"测"总线。
3. **外壳改由 `dbus-run-session` 持有总线**（`GUI_INNER_COMMAND` 与 `ensure_shell` 都改成
   `dbus-run-session -- bash .quiz_shell.sh`），脚本内保留一个**同会话验活**的兜底：
   连不通就自己 `--nofork` 重开一条 —— GTK 拿不到可用总线时会自己 autolaunch 一条 `--fork` 的
   临时总线，那种 daemon 随一次性会话被 `--kill-on-exit` 回收 → 面板就挂在死总线上。
   脚本把结果写进 `/tmp/quiz_shell.log`：`OILQUIZ_SHELL bus=… 应答=活` ✓
4. **顺手查清"点第一项没反应"**：Whisker 菜单第一项是「网络浏览器」、第二项「邮件阅读器」，
   容器里**既没有浏览器也没有邮件客户端** —— 日志里直接写着 `Couldn't find a suitable web browser!`。
   真机用 xdotool 发**真鼠标事件**点第 4 项「使用命令行」，Thunar 窗口随即出现（OCR 确认）✓，
   说明菜单的启动通路本身是好的，不好使的是那两项没有对应程序。
5. **踩到的一个坑**：把 `>> /tmp/quiz_shell.log` 写在 **Termux 侧**那条命令上会直接
   `Permission denied`（Android 的 `/tmp` 不可写）→ 整条外壳会话根本没起来（组件数全 0）。
   重定向必须写在**容器内**执行的那条命令里。
9. **外壳脚本里再加一道"独占兜底"**：连 `startxfce4` 那条会话也一起清掉
   （`pkill -9 -f 'startxfce[4]'`）。原因是真机上仍偶发出现"外壳会话 + startxfce4 会话"并存，
   两边的面板都贴屏幕顶部，用户点到的那份可能挂在另一条总线上。这条脚本每次启动都由 App
   用 base64 重写（`ensure_shell_b64`），所以兜底永远是最新版本，不依赖"一键准备"。
10. **终态真机验证**（当前这台机器上）：
    - `startxfce4` 进程 **0 个**；`Xvnc=1 xfwm4=1 xfce4-panel=1 xfce4-notifyd=1 xfdesktop=1 xfce4-session=0`；
    - **面板与外壳脚本在同一条总线上**（`/tmp/dbus-x0KkmNkdhz` 两边一致）；
    - noVNC 网页 `http://127.0.0.1:6080/vnc.html` → **200**；
    - 用 xdotool 发**真鼠标事件**逐行点菜单：`y=230` → **Thunar 起来了** ✓；
      `y=130` 只拉起 `xfce4-mime-helper`（那一项是「网络浏览器」，容器里没装浏览器，
      所以看起来"点了没反应"——这正是用户最初那个观感的一部分）。

6. **启动器只在「一键准备」时写过一次** ✗ —— App 升级后 `ensure_shell` / `ensure_desktop` 的改动
   **根本没到设备上**（设备上那份 41 KB 的 `~/ubuntu-gui` 还是老逻辑，依旧会另起 `startxfce4`）。
   现在 `startGuiInTermux` / `restartGuiInTermux` 每次都会把**当前版本**的启动器写到公共下载目录
   （`Download/OilQuiz/termux_env/ubuntu-gui.sh`，跟 `setup.sh` 同一条通道），再用一条短命令
   `cp` 进 `$HOME` 并执行（/sdcard 是 noexec，必须先拷出来）。启动器从此跟 App 同版本。
7. **外壳加了单实例锁**（`/tmp/oilquiz_shell.pid`）：真机上出现过多条外壳会话，它们会互相
   `pkill` 组件、把面板重新挂到新总线上，最后"谁在屏幕上看运气"。后起的会话现在直接退出。
8. **`xfce4-notifyd` 的裸命令是错的** ✗：Ubuntu 24.04 把可执行文件放在
   `/usr/lib/<多架构>/xfce4/notifyd/xfce4-notifyd`，**不在 PATH 里** —— 脚本里写 `xfce4-notifyd &`
   会 `command not found` 静默失败（真机查到的就是"通知守护一直没起来"）。
   现在按真实路径启动（带 `command -v` 兜底）。同理 `gui_pkgs_ok()` 里的
   `command -v xfce4-notifyd` 判定**永远为假**，会导致老环境每次重跑「一键准备」都白装一遍 —— 一并改成查真实路径。

## [2026-09-27] 换源到清华 ports + 装图形包管理器 Synaptic（并把两件事固化进一键准备）

用户问「有包管理器吗，有包商店吗」，随后「那就换 顺便安装 synaptic」。

1. **换源**：容器里原来是官方 `http://ports.ubuntu.com/ubuntu-ports`（arm64 专用源）——
   而一键准备里的换源 sed 只匹配 `archive/security.ubuntu.com`，所以**一直没换动** ✗。
   现已改为 `https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports`，实测 `apt update` 拉
   **36.7 MB / 16 秒（≈2.2 MB/s）** ✓（这次是全量重拉，看得出速度）。原文件备份为 `*.bak-oilquiz`。
2. **装了 synaptic**（图形包管理器）：`/usr/sbin/synaptic` ✓、菜单项 ✓；在桌面上实测能起来，
   而且**界面是中文** ✓（OCR：`您应该定期刷新软件包信息…共列出 912 个软件包，已安装 622 个，已破坏 0 个`）。
   已装包数 618 → 622。
3. **固化进一键准备**：两处换源 sed（`ZH_FIX_SH` 与 step 4）都补上 `ports.ubuntu.com/ubuntu-ports` 映射；
   step 5 的 apt 列表与 `gui_pkgs_ok()` 前置检查都加上 `synaptic`（否则老环境重跑会跳过、永远补不上）。
4. **限制说明**：proot 容器里 **snap / flatpak 都用不了**（无 systemd、无 bubblewrap），
   所以"软件商店"只能基于 apt 仓库（可装 **91,290** 个包）。

## [2026-09-27] 「一键准备」查漏补缺：删掉 step 6 里残留的旧脚本块 + 把新组件纳入前置检查

用户问「一键配置这个功能都配置好了吗」。逐段核对后发现两处真问题，已修：

1. **step 6 里混进了一段旧脚本**（一个没有 heredoc 开头的 `case "$1" in … esac`，外加一行孤立的 `QUIZ_GUI_EOF`）——
   它会被当成正常脚本执行，里面用的 `PORTUP` / `$INNER` / `$LOG` 在该上下文里都没定义；
   `$INNER` 为空时还会拿空命令去起会话。已删除并在原位留了说明。
2. **`gui_pkgs_ok()` 只检查旧的 5 项**（Xvnc / startxfce4 / 中文字体 / websockify / noVNC 网页）——
   老环境重跑「一键准备」会**跳过 step 5**，永远补不上新增的
   `xfce4-notifyd / xfce4-taskmanager / xfce4-screenshooter / ristretto / xarchiver`。已补进检查项。
3. 现在「一键准备」完整覆盖：（0）allow-external-apps（1）存储权限（2）proot-distro（3）Ubuntu 容器
   （3.5）容器中文化 + 北京时间 + 面板开始菜单（4）容器内完整 Python（5）TigerVNC / XFCE / 中文字体 /
   noVNC+websockify / 六个新组件（6）写 `~/ubuntu`、`~/ubuntu-gui` 并做最终验证。
   另有 `.quiz_shell.sh`、`.quiz_kill_stale.py` 两个助手由图形界面启动流程写出
   （`ensure_shell_b64` / `kill_stale`），一键准备不必重复写。
4. **门禁**：把 SCRIPT_TEMPLATE 从 Java 源码抽出来跑 `bash -n` → **退出码 0** ✓
   （这种"残留块"就是靠它才发现得了）；并抽查 13 个关键点全部命中 ✓
   （中文相关字面量如 locale-gen / Asia/Shanghai / whiskermenu 在 ZH_FIX_SH 里，经 `__ZH_FIX_B64__` 注入 ✓）。

## [2026-09-27] 桌面外壳改用「共用常驻 D-Bus 总线」启动（顺带更正一次误判）

用户问「基本功能够用了吧」。核对时我一度以为面板没画出来（`xwininfo ... | grep xfce4-panel` 只看到 10x10），
后来发现**是我的检查方式错了**：真正的面板窗口**没有名字**，要按 class 用
`xdotool search --class xfce4-panel` 才看得到 —— 实测 **1280x27 @ 0,0** ✓
（桌面 1280x720、整屏截图 28100 色、启动器菜单正常）。

不过排查中确实抓到一个真问题并修掉了：

1. **`xfce4-panel` 会 fork 到后台**，而 `dbus-run-session -- xfce4-panel` 的直接子进程一退出就会**把总线拆掉** ——
   真机报 `xfce4-panel: Name org.xfce.Panel lost on the message dbus, exiting` 加
   `There is already a running instance`，面板因此会时好时坏。
   （`xfwm4` / `xfce4-notifyd` / `xfdesktop` 不 fork，所以它们一直没问题。）
2. **修法**：新增容器侧脚本 `~/.quiz_shell.sh`（App 用 base64 写出）：用 `dbus-daemon --session --fork`
   起一条**独立常驻**的总线（地址存 `/tmp/oilquiz_bus_addr`），四个组件（wm / notifyd / panel / desktop）
   都挂它；`ensure_shell` 改成 `bash ~/.quiz_shell.sh <角色>`，顺序 **WM → notifyd → 面板 → 桌面**，
   面板每次重建（避免旧实例挂在已被拆掉的总线上互相顶掉）。
3. **验证**：冷启动（杀掉全部外壳进程）→ `~/ubuntu-gui restart` →
   `xfwm4=1 xfce4-notifyd=1 xfce4-panel=1 xfdesktop=1`；面板真窗口 **1280x27**；
   整屏截图 **28100 色**；点左上角启动器弹出中文菜单
   （使用命令行 / 收藏夹 / 最近使用 / 全部应用程序 / 互联网 / 开发 / 设置 / 图形 / 系统）✓。

## [2026-09-27] 补齐远程桌面缺的组件 + 通知区域（systray）回归面板

用户问「看看还有哪些组件漏了」，随后「装上」。

1. **审计结果**：面板配置里用到的插件 `.so` **一个不缺** ✓；缺的是软件层组件 ——
   `xfce4-notifyd`（通知守护）、`xfce4-taskmanager`、`xfce4-screenshooter`、`ristretto`（看图）、
   `xarchiver` + `thunar-archive-plugin`（解压/压缩）、`catfish`（搜索）、`parole`（播放器）、
   `xfce4-clipman`（剪贴板）、`xfce4-power-manager`（容器里意义不大）。
   当时在跑的守护只有 `xfwm4 / xfce4-panel / xfdesktop / xfsettingsd / xfconfd`。
2. **已装**（6 个，全部带中文词典 ✓，菜单项 40 → 45）：
   `xfce4-notifyd xfce4-taskmanager xfce4-screenshooter ristretto xarchiver thunar-archive-plugin`。
3. **通知区域（systray）加回面板** ✓：当初它崩溃正是**因为没有通知守护**；装上 `xfce4-notifyd` 后
   把 `plugin-6`（systray）加回 `panel-1` 的 `plugin-ids`，实测**通知区域 applet 正常加载、面板稳定不崩** ✓。
4. **做成持久**：`ensure_shell` 增加"缺就补 `xfce4-notifyd`"，并**排在面板之前**
   （否则面板启动时通知区域仍会因为找不到守护而崩）；一键准备的 apt 列表也补上这 6 个包。
5. **真机验证**：把四样全杀掉（`xfwm4=0 xfce4-notifyd=0 xfce4-panel=0 xfdesktop=0`）→
   `~/ubuntu-gui restart` → **`xfwm4=1 xfce4-notifyd=1 xfce4-panel=1 xfdesktop=1`，通知区域=2** ✓；
   启动器菜单可开、中文项正常 ✓。

## [2026-09-27] 远程桌面「启动器点不到」真因：**没有窗口管理器**（已修，并做成每次启动自动补齐）

用户让检查「远程 ubuntu GUI 启动器的功能是否正常」。结论：**不正常**，但根因不在启动器，而在**桌面外壳没被拉起来**。

1. **现象**：`xfce4-panel=0 / xfwm4=0 / xfdesktop=0`；X 里**所有窗口都是 `10x10+10+10`** ——
   这是**没有窗口管理器**的典型症状：XFCE 组件建了窗口但没人给它们 map/定位，于是面板看不见、也点不到。
2. **为什么会没有**：proot 里 XFCE 的会话管理器（`xfce4-session`）拉不起客户端 ——
   用户级会话文件只剩 `Failsafe` 且 `Client0..4_Command` 全为 `empty`；而 `xfwm4` 又必须要有 xfconf
   （D-Bus 自动激活在 proot 下不稳，拿不到就 `Xfconf could not be initialized` 直接退出）。
   这条链任一环断了就退回"什么都不启动"。
3. **修法（已验证）**：不再指望会话管理器。启动脚本新增 `ensure_shell`：启动后检查
   `xfwm4` / `xfce4-panel` / `xfdesktop`，**缺哪样补哪样**，顺序 WM → 面板 → 桌面，
   各自用**独立的 `dbus-run-session`**（proot 下这条最稳；`xfwm4` 关合成 `--compositor=off`，VNC 无 GL）。
   会话自己能起来时它是空操作，不会重复启动。
4. **真机验证**：
   · 手动杀掉三件套（`xfwm4=0 panel=0 xfdesktop=0`）→ `~/ubuntu-gui restart` →
     **`xfwm4=1 xfce4-panel=1 xfdesktop=1`，`_NET_WM_NAME = "Xfwm4"`** ✓ 自动补齐成立；
   · 面板回来后可点开启动器，中文菜单：`收藏夹 / 最近使用 / 全部应用程序 / 附件 / 互联网 / 开发 / 设置 / 图形 / 系统` ✓；
   · 从菜单点「终端模拟器」真的起来了（`xfce4-terminal` 进程出现）✓。
5. 过程说明：期间我误改坏过设备脚本一次（`ensure_wm` 引号转义），已修回并 `bash -n` 通过；
   用户要求"改回去"时也把那次提交 `git revert` 了（`2d52c318`），本次是在干净基础上重做的。

## [2026-09-27] 「输入指针没有捕获」真因：noVNC 把自己的「只读模式」记在了 localStorage

用户报「输入指针没有捕获啊，审查下代码」，并怀疑是浮层吞了操作。逐段查完后，真因是 **noVNC 自己的设置持久化**：

1. **真因（有实据）**：翻 WebView 的 localStorage（`app_webview/Default/Local Storage/leveldb`）看到
   `http://127.0.0.1:6080` 下面存着 **`view_only = "true"`**。而 noVNC 读设置的过程**不做布尔转换**：
   ```js
   initSetting(name, defVal) {
       let val = WebUtil.getConfigVar(name);                        // query 参数，原始字符串
       if (val === null) val = WebUtil.readSetting(name, defVal);   // localStorage，也是原始字符串
   }
   ```
   于是字符串 `"true"` 被直接赋给 `UI.rfb.viewOnly` → **noVNC 静默丢弃全部指针与键盘输入**。
   （传 `view_only=false` 也没用：JS 里字符串 `"false"` 同样是真值。）
2. **修法（最小改动）**：`onPageFinished` 里检查一次，发现这条记忆就 `localStorage.removeItem('view_only')`
   并重载，让默认的布尔 `false` 生效；每次进页面都会自愈。
   实测：leveldb 里该键**最后一条记录已变成删除**（此前紧跟 `true`），状态条也从 `err` 变成
   `noVNC 已连接 127.0.0.1:5900`。
3. 顺带修：状态条那个读 noVNC 状态的小探针原来写的是 `window.UI`，但 noVNC 1.3 的 `app/ui.js` 是
   **ES 模块**（`const UI = …; export default UI`），**没有 window.UI** → 一直返回 `err`。
   改用**动态 import 取同一个模块实例**（ES 模块按 URL 单例）。
4. **不是浮层吞的**（回答用户最初怀疑）：浮层只有 `wrap_content` 状态条与 44dp 的 ≡，触摸监听也只在它们自己身上；
   `dumpsys`/MIUIInput 都能看到触摸到达 Activity 窗口。之前那次「本页 5 / 画面 0」的测量很可能是戳在状态条区域造成的，
   不足以下结论 —— 所以按用户要求把那些诊断代码全部回滚，只留这个有实据的最小修复。
5. 回滚说明：`git checkout -- .` 清掉了诊断代码；顺手 `git clean -fd` 删掉的都是未跟踪的生成物/空目录
   （`src/jniLibs`、`src/main/assets/weather`、`src/main/cpp/-p` 等），`assembleDebug` 验证 **BUILD SUCCESSFUL**，不影响构建。

## [2026-09-27] 审查「输入指针没有捕获」：三个真 bug（其中一个把 6080 彻底搞死）

用户报「输入指针没有捕获啊，审查下代码」。按链路逐段查，结论是**服务端没问题，App 侧有三个 bug**。

1. **先证明服务端是好的**（避免误判方向）：容器里起 `xev`，用裸 RFB 客户端（握手 → SetPixelFormat → PointerEvent）
   点它的窗口，xev 收到 **ButtonPress=11 / ButtonRelease=10 / MotionNotify=34**，事件是 `button 1, same_screen YES`
   —— Xvnc 的指针输入链路完全正常。
   （顺带踩坑：RFB 的 SetPixelFormat 是 **20 字节** = 1 类型 + **3 填充** + 16 格式，我第一次漏了填充，服务端直接断我连接。）
2. **真 bug ①：每次启动/重启都新起 websockify、旧的从不清理**。实测堆到 **9 个**，最后监听进程 accept 卡死 ——
   **5900/6080 双双 timeout（不是 refused）**，手机端画面停在最后一帧、点哪儿都没反应，
   这正是用户看到的「输入指针没有捕获」。新增容器内 `kill_stale`：按 `/proc/<pid>/cmdline` 的
   **argv[1] 是否以 /websockify 结尾**精确识别（避免 `pkill -f` 把执行它的会话一起杀掉），启动与重启路径都先清一遍。
3. **真 bug ②：我自己的心跳在打 websockify**。`VncWebActivity` 原来每 4 秒开一个裸 TCP 连接探 6080，
   而 websockify **每个连接 fork 一个子进程**（日志里的 `new handler Process`）—— 这些「连上但不发请求」的子进程
   全挂着不退（实测存活 7 个），最后把监听拖死。改成页面加载后**只读 noVNC 自己的 JS 状态**
   （`UI.rfb._rfbConnectionState`），一个 socket 都不开。
4. **真 bug ③：状态条的拖动监听被覆盖**。`FloatingDrag.attach(bar…)` 之后又调 `resetTimerOnTouch(bar)`，
   两者都是 `setOnTouchListener`、后设的生效 → 状态条拖不动（只有 ≡ 能拖）。调整为先 resetTimer 再 attach。
5. 顺手补：WebView 加 `setFocusable/setFocusableInTouchMode/requestFocus`（键盘输入需要焦点），
   以及 `onPageFinished` / `onReceivedError` 回调（页面加载失败会直接说清楚，不再只是黑屏）。
6. **修完实测**：存活 websockify = **1**、5900 出 `RFB 003.008`、6080 监听、`vnc.html` HTTP 200；
   手机端 noVNC 正常显示 XFCE 桌面（OCR：`文件(F) 编辑(E) 视图(V) 转到(G) 书签(B) 帮助(H)`、
   `警告：您正在使用超级帐户…`）。

## [2026-09-27] 按用户选择：留在 XFCE，卸载 MATE

用户回「4 把 mate 删了」（选「留在 XFCE」，并删掉 MATE）。

1. 容器里 `apt-get purge -y 'mate-*' caja caja-common marco` + `autoremove`：剩余 mate/caja/marco 包 **0 个**，
   `/usr/share/xsessions/` 只剩 `xfce.desktop`，容器释放约 **1GB**（已用 177G → 176G）。
2. App 侧把 MATE 相关内容全摘掉：一键准备的 apt 列表、`gui_pkgs_ok` 的 mate-session 检查、
   `desktop` 子命令的 mate 分支、`ensure_desktop` 的 mate 检测与 pkill（源码里 `grep mate` = **0**）。
   默认会话保持 `startxfce4`；切换能力保留（`bash ~/ubuntu-gui desktop xfce4|lxqt`）。
3. 设备上的 `~/ubuntu-gui` 同步（mate 引用 13 → 2），删掉 `~/.quiz_desktop`（走默认 XFCE）。
4. 卸载后回归验证：`xfce4-session=2 / xfce4-panel=2 / lxqt=0`、桌面 `1920x881` 截图 **4926 色**（内容正常）、
   noVNC 网页 `HTTP 200`、新 APK 安装 `Success`。

## [2026-09-27] 桌面换成 MATE，并做成可切换（mate / xfce4 / lxqt 一条命令）

用户说「把 ubuntu 的桌面换一下，看看有没有合适的，大点也没事」。

1. 装了 **MATE 1.26**（mate-desktop-environment + mate-terminal + caja）。实测进程齐活：mate-session / marco /
   mate-panel ×5 / caja；中文词典 10 个（caja、mate-panel、mate-session-manager…）；面板标题已经是
   「顶部面板 / 底部面板」。
2. 踩坑与修法（都是真机踩出来的）：
   · `ubuntu-mate-default-settings` 解包卡在 `orca.desktop.dpkg-new: Permission denied`（proot 下 dpkg 建临时文件被拒）
     → 先 `rm` 掉冲突文件再 `dpkg -i` 就过了；
   · 我一度用 `--force-remove-reinstreq` 把它拔了 → `mate-session-manager` 依赖不满足，3 个包卡在 `iU`
     → 按上面重装后 `dpkg --configure -a` 全部配置完成；
   · **在容器里 setsid 起的桌面会话会随那条 login 退出被杀**（我一开始截到的"空桌面"就是这个原因），
     必须像 App 那样从 Termux 侧 `setsid nohup proot-distro login ... exec dbus-run-session -- $SESSION`。
3. **桌面可切换**：`~/.quiz_desktop` 存会话命令，`bash ~/ubuntu-gui desktop mate|xfce4|lxqt` 切换，
   `~/ubuntu-gui status` 显示当前桌面。`ensure_desktop` 修了一个真 bug：**当前跑的会话与配置不一致时先杀掉旧会话**
   （原来只判断"有没有会话在跑"，于是切了桌面还是旧的在跑 —— 真机实测 MATE 与 XFCE 同时在跑、两套面板打架）。
   一键准备里也加了 MATE（apt 列表 + `gui_pkgs_ok` 检查 mate-session）。
4. **比 XFCE 差的地方（如实说）**：MATE 的**通知区域 applet 会崩**并在启动时弹一次中文报错框
   （dconf 里是 `applet-iid='NotificationAreaAppletFactory::NotificationArea'`，可以摘掉）；没装壁纸包，桌面是纯色。
   XFCE 这套在 proot 下明显更稳（整个会话都在用）。随时 `bash ~/ubuntu-gui desktop xfce4 && bash ~/ubuntu-gui restart` 切回。

## [2026-09-27] 浮层再进化：松手吸附最近边缘 + 长按收起成小圆点（再长按恢复）

用户回「可以」，同意上一条里提的两个行为。在已有「拖动 + 记忆位置 + 越界 clamp」之上加：

1. **松手吸附到最近的左右边缘**（160ms 动画），不会停在屏幕正中挡着桌面；纵向不吸附、停在手指位置，
   吸附后的坐标同样写进 SharedPreferences。未附着窗口时（测试环境）直接 setX，保证行为可断言。
2. **长按收起**：noVNC 页长按状态条或 ≡ → 浮层收成 **30dp 半透明小圆点**贴着边（状态条隐藏、画面完全干净），
   收起状态也存 prefs；**再长按小圆点恢复**；点一下 ≡ 会自动从小圆点恢复并展开状态条（都有 Toast 提示）。
   原生模式页长按顶部胶囊 = 回到默认位置（新增 `FloatingDrag.reset`）。
3. 真机用例 `FloatingDragDeviceTest` 扩到两个：
   `dragSnapsToNearestEdgeAndPositionIsRemembered`（跟手 + 左/右边缘吸附 + 记忆 + 越界 clamp + 原地算点击）、
   `longPressFiresAndIsNotTreatedAsTap`（按住超过系统长按时长触发长按，且不被误判成点击）—— 真机 `OK (2 tests)`。

## [2026-09-27] VNC 浮层可以拖了：状态条与悬浮 ≡ 跟着手指走，位置记得住

用户问「动态按钮不能拖动啊，为何是固定位置」—— 之前这两个浮层是用 `layout_gravity` 钉在左上角的，
根本没接拖动。

1. 新增 `FloatingDrag`（可复用）：拖动**超过 touchSlop 才算拖**（否则仍按点击处理，按钮不会点不动）、
   位置写进 `SharedPreferences`（`vnc_prefs` 的 `<key>_x/_y`，下次进来还在老地方）、
   拖动中与转屏后都会 **clamp 回屏幕内**（不会甩到看不见的地方）。
2. 接上去的位置：noVNC 外壳页的**状态条**（拖状态文字/空白处，里面的按钮照常点）和**悬浮 ≡**（整个都能拖）；
   原生模式页的**顶部状态胶囊**同样可拖；两个页面转屏后都重新 clamp。
3. 真机用例 `FloatingDragDeviceTest`：直接合成 MotionEvent 派发给 View（这台 MIUI 明确拒绝 shell 注入触摸 ——
   `SecurityException: Injecting input events requires the INJECT_EVENTS permission`，所以 adb 没法模拟拖动），
   断言四件事：**跟手 300/200px**、**位置写进 SharedPreferences**、**越界 clamp 到 800/1900**、
   **原地一下仍算点击**。真机结果 `OK (1 test)`。

## [2026-09-27] 图形界面（VNC）横竖屏适配：resize=remote + 旋转后重协商 + 手动「横屏」按钮

用户要求「进行横竖屏适配」。之前 noVNC 用的是 `resize=scale` —— 只是把固定的 1280x720 桌面**硬缩进**屏幕，
竖屏下只剩中间一条、字还发虚。改成真正的适配：

1. **`resize=remote`**：noVNC 用 SetDesktopSize 请求 Xvnc 把远端桌面改成与手机窗口一致的分辨率，
   XFCE 自动重排。真机实测（同一台手机，靠自动旋转切方向）：
   | 方向 | 手机截图 | Xvnc 桌面分辨率 |
   |---|---|---|
   | 横屏 | 2656x1220 | **817x375** |
   | 竖屏 | 1220x2656 | **375x817** |
   比值都是 2.18 —— 桌面分辨率跟着 WebView 的 CSS 视口走，不是拉伸放大。
2. **旋转后显式重协商**：本页带 `configChanges=orientation|screenSize` 不会重建，WebView 那次 resize
   事件不一定触发重新协商，所以 `onConfigurationChanged` 里延迟 700ms 重载一次页面（本地连接，重连 < 2s），
   并把状态条叫回来显示「横屏/竖屏：正在重新适配远端桌面…」。
3. **手动方向**：状态条新增「横屏/竖屏」按钮（系统自动旋转锁着时也能切）与「系统栏」开关；
   状态条改成两排（上排状态 + 收起，下排 5 个短标签按钮），竖屏也不会挤爆。
4. 原自研客户端（原生模式）本来就有 `syncDesktopSize` + 「横屏」按钮，两条路行为一致。
5. 真机用例同步：`vncWebShellReady` 断言 `resize=remote`，并新增 `btn_vnc_web_rotate` / `btn_vnc_web_bars` 两个控件断言。

## [2026-09-27] VNC 界面改用现成的 noVNC（MPL-2.0）：「外壳式」实现，不再自己画界面

用户问「可以使用别人的源代码吗。你自己设计的不行啊」。先把三家的 LICENSE 拉下来核对：

| 项目 | 协议 | 能否进这个 **MIT** 项目 |
|---|---|---|
| bVNC / aRDP（iiordanov） | **GPLv3**（LICENSE 原文） | ❌ 搬进来整个 App 必须转 GPLv3 并开源 |
| android-vnc-viewer / LibVNC | GPLv2 | ❌ 同上 |
| **noVNC（官方）** | **MPL-2.0**（core 库） | ✅ 可用，保留版权声明即可 |

用户选「内置 noVNC」。实现分工（关键：**noVNC 一行代码都不进我们的 APK**，它由容器里 Ubuntu 的
`novnc` 包提供，我们只是"用"它 —— 连 MPL 的分发义务都不涉及）：

1. **容器侧**：`apt install novnc websockify`（Ubuntu 24.04 是 noVNC 1.3.0）。`GUI_INNER_COMMAND`
   改成 `Xvnc … &` + `exec websockify --web /usr/share/novnc 127.0.0.1:6080 127.0.0.1:5900` ——
   **一个进程同时干两件事**：发布 noVNC 网页 + 把 WebSocket 桥到 Xvnc；没装 websockify 时退回 `wait`
   只跑 Xvnc，不会把图形界面搞死。`gui_pkgs_ok` 也加了 websockify/novnc 检查，老环境再点一次「一键准备」即补上。
2. **App 侧**：新增 `VncWebActivity`（WebView 外壳）+ `activity_vnc_web.xml`，只保留一条中文状态条
   （状态 / 启动图形界面 / 重连 / 原生模式 / 收起），8 秒不动自动收起，只留左上角 ≡。
   协议、渲染、输入、缩放、设置面板、剪贴板**全部由 noVNC 承担**，连中文界面都是它自带的
   （`app/locale/zh_CN.json`，随设备语言生效）。工具入口默认走这个页面，原自研客户端保留为「原生模式」。
3. **真机验证（链路整条打通）**：
   ```
   HTTP 200 15212 bytes（/vnc.html，含 app/ui.js）
   握手: HTTP/1.1 101 Switching Protocols
   RFB 横幅: b'RFB 003.008\n'   服务端海报正常: True
   ```
   另加两条真机用例：`vncWebShellReady`（外壳页布局 + URL 参数 autoconnect/resize/path/port）、
   `novncServedByContainer`（真的去 6080 取 vnc.html 200，并做一次 WebSocket 握手读到 RFB 横幅）。
8. **顺手修掉两个真 bug（都是这轮真机踩出来的）**：
   · **僵尸进程骗过健康检查**：`pgrep -x Xvnc` 会匹配到僵尸（`30739 Z Xvnc`），于是
     `~/ubuntu-gui start` 回 `GUI_ALREADY_UP`，用户那边 5900 根本没监听。`UP()` 现在读
     `/proc/<pid>/status` 的 `State:` 字段，只认 R/S/D/T/t/W/X/I 这些活状态。
   · **websockify 抢不到端口会把整个桌面带死**：原来 `exec websockify` 当会话主进程，若 6080 被
     上一轮的实例占住，它启动即失败 → proot 会话结束 → Xvnc 被 `--kill-on-exit` 带走 →
     5900 没人监听（恰好又触发上面那个僵尸误判）。现在会话寿命只跟随 Xvnc（`wait $XVNC`），
     websockify 降级成后台助手，失败也只写 `/tmp/quiz_websockify.log`，桌面照常活着。
9. **真机截图核对（noVNC 在手机里真的连上了）**：截图前后取 `~/.quiz_gui.log`，服务端明确记录到
   noVNC 的客户端会话：`connecting to: 127.0.0.1:5900` → `Connections: accepted` →
   `Client needs protocol version 3.8` → `Client pixel format depth 24 (32bpp) little-endian bgr888`；
   手机截图顶部 500px 的 OCR 读到桌面内容（`OilQuiz GUI`、`2026-9-27`、`17:xx`）。

## [2026-09-27] 图形界面页控件重排：参考 RealVNC / bVNC，画面优先 + 分组 + 自动收起

用户说「vnc界面的按钮你好好管理下，参考下别人的」。老版是一条常驻的横向滚动按钮栏（15 个按钮挤一行，
非要横滑才能找到），既挡画面又难用。按主流 VNC 客户端的三条惯例重排：

1. **画面优先**：桌面铺满整屏（VncView 从 weight 改成 match_parent），控件全部改成浮层；
   手指一碰画面就自动收起面板（新增 `VncView.OnCanvasTouchListener`），8 秒不动也自动收起。
2. **分组 + 等宽 + 不滚动**：底部面板四组 —— 连接（端口/启动图形界面/连接/断开）、
   输入（键盘/右键/粘贴/Ctrl/Alt/Shift）、显示（适应/缩小/放大/旋转/系统栏/更多）、
   更多（滚轮↑/滚轮↓/Esc/Tab/Enter/帮助）；每组一行、按钮 weight=1 等宽，**删掉了 HorizontalScrollView**。
3. **状态可视 + 可用性跟着连接走**：右键/适应/系统栏/Ctrl/Alt/Shift 用选中态（checkable + 透明度）表示，
   不靠状态文字去猜；没连上时「断开」置灰，连上后「连接/启动图形界面」置灰。
4. 新增交互：Ctrl/Alt/Shift 修饰键开关（开着=一直按住，配合点击可发 Ctrl+点击；发普通键或断开前自动松开）、
   Esc/Tab/Enter 常用键、「帮助」弹窗（手势 + 键盘 + 面板说明，替代原来常驻的一行小字）。
5. 顶部状态胶囊：一行状态 + ≡ 展开/收起（原来状态行和端口框各占一整行高度）。
6. 真机用例 `vncLayoutInflates` 扩展：新增 8 个控件 id 断言，并断言「没有横向滚动容器」「更多行默认收起」
   「面板每行按钮等宽」「至少 3 行分组」。
7. 静态核对已过：布局无 HorizontalScrollView、每行最多 6 个等宽按钮、所有旧 id 保留；
   `BUILD SUCCESSFUL`（含 androidTest 编译）。**设备侧用例与截图待手机空闲时补跑**
   （当时用户正在使用手机，前台是别的 App，未抢前台、未跑 instrumentation）。

## [2026-09-27] 界面为何是英文：Ubuntu 精简镜像把翻译词典整类排除了（时区一并对齐北京时间）

用户问「就问一句为何不是中文，而且时区也不对」。查清后是**容器根文件系统的包管理配置**问题，不是环境变量。

1. **根因（真机证据）**：`/etc/dpkg/dpkg.cfg.d/excludes` 里有一行
   `path-exclude=/usr/share/locale/*/LC_MESSAGES/*.mo` —— 装包时所有程序自带的翻译词典都被跳过、根本没落盘。
   · `dpkg -V xfce4-panel` 报 **65 个 missing**，其中 63 个是各语种 `.mo`；
   · 而 `dpkg -L xfce4-panel` 的语种列表里 `zh_CN` 明明在 —— 不是上游没翻译，是本地被排除了；
   · `ls /usr/share/locale/zh_CN/LC_MESSAGES` = **0 个文件**（`/usr/share/locale-langpack/zh_CN` 有 413 个，
     所以终端/coreutils 是中文，而 XFCE/Thunar 这些「词典只在自己包里」的组件永远英文）。
2. **修复**：解除该排除 → 已装的包 dpkg 不会补写 `.mo`，于是用 `apt-get download + dpkg-deb -x` 把
   xfce4-panel/xfwm4/xfdesktop4/libxfce4ui/xfconf/exo/thunar/appfinder 的中文词典手动抠回 `/usr/share/locale`。
   实测 `zh_CN` 词典 0 → 10 个（`xfce4-panel.mo`、`thunar.mo`、whisker 都在）。
3. **时区**：`/etc/timezone=Asia/Shanghai` + `/etc/localtime` 软链；容器 `date` →
   `2026年 09月 27日 星期日 16:27:21 CST`（中文星期 + 北京时间，与手机一致）。
4. **语言环境**：`locale-gen` 生成 `zh_CN.UTF-8`，写入 `/etc/default/locale`、`/etc/environment`、
   `/etc/profile.d/00-quiz-locale.sh`；XFCE 会话启动行显式带 `LANG/LANGUAGE/LC_ALL=zh_CN.UTF-8`
   （改的是 `ensure_desktop`：之前没带，即使有词典也不会生效）。
5. **用现成的中文启动器**（用户提示「别自己写」）：装上 `xfce4-whiskermenu-plugin`；系统自带的
   `xfce4-appfinder` 与面板「所有应用程序」菜单在词典补回后**自己就变中文** —— 截图 OCR 实测
   `Thunar 文件管理器`、`用文件管理器浏览文件系统`、`Xfce 终端`、`Xfce 设置`。手写的那版已删除。
6. **固化进「一键准备」**：新增容器脚本 `ZH_FIX_SH`（幂等，72 行）与「3.5/6 中文界面与北京时间」步骤；
   图形界面启动/重启脚本新增 `ensure_zh()`（0.2 秒快速守卫，只在缺词典时才修复并重启会话）。
7. **验证**：`bash -n` 通过（`.quiz_zh_fix.sh`、`ubuntu-gui`）；真机 `bash ~/ubuntu-gui start` → `GUI_ALREADY_UP`；
   桌面截图 OCR 中文正常；`BUILD SUCCESSFUL`。
8. **现成的中文开始菜单挂上面板**（用户说「改」）：Whisker Menu 装好后插到顶部面板最左边、顶替原来的菜单。
   踩到两个坑并已解决：① 手动解包只拷了插件、没拷依赖 → 缺 `libgtk-layer-shell.so.0` → 面板弹
   「插件"Whisker 菜单"意外地离开了面板」（60 秒内重启多次后被面板自动从配置里删掉）；改为
   `apt-get install` 并补装 `libgtk-layer-shell0 libgarcon-1-0 libgarcon-gtk3-1-0`，`ldd` 缺库数归零后正常；
   ② 必须在 `startxfce4` 之前改面板配置，否则运行中的 xfconfd 会写回旧配置覆盖 —— `ensure_zh` 现在
   **先停会话再修复**，守卫也加了「Whisker 已挂」这一项（约 0.2 秒）。
   实测点开菜单：`关于 Xfce`、`应用程序查找器 / 查找和启动在您系统上安装的应用程序`、
   `文件管理器 / 浏览文件系统`、`文本编辑器设置 / 配置 Mousepad 文本编辑器` —— 全中文。

## [2026-09-27] 「一键准备」把能自动的全自动：通道自检 + 一行修复 + 自动复检（并讲清哪一步无法自动）

用户问「Termux 连不上了，能在一键配置时自动配置吗」。先把能力边界说清楚，再把能自动的全部自动化。

1. **为什么做不到 100% 自动**：`allow-external-apps`（Termux 允许外部应用调用它）这个开关只能由 Termux 自己
   写进 `~/.termux/termux.properties`：
   · Android 不允许 App A 写 App B 的私有目录（Termux 是另一个 UID）；
   · Termux 的 RUN_COMMAND 会主动拒绝没开这个开关的调用（Termux 的安全设计，不是 bug）；
   · MIUI 又禁掉了 adb 代授（`pm grant` 抛 SecurityException），设备也没有 root/Shizuku；
   · 本项目里的 `AccessibilityHelper` 只是无障碍描述辅助，**没有声明 AccessibilityService**，
     所以 App 也无法用无障碍服务代替用户在 Termux 里打字。
   结论：「首次打开这个开关」必须用户执行一次；App 能做的是把它压到"一行命令 + 粘贴回车"。
2. **本次做成的自动化**：
   · 新增「**自检并修复通道**」按钮：真发一条命令并等 Termux 广播回执（唯一可信判据），失败原因分类明确 ——
     没装 Termux / 没授 RUN_COMMAND 权限 / 系统拒绝 / Termux 20s 没回执（= allow-external-apps 没开或被系统冻结）。
   · 修复命令从"整段 5KB 脚本"压成**一行**：App 把脚本全文写到 `Download/OilQuiz/termux_env/setup.sh`，
     用户只需粘 `bash /sdcard/Download/OilQuiz/termux_env/setup.sh`；以后脚本改了这行命令也不用变
     （设备用例断言"文件内容 == 当前生成脚本"）。
   · 自动复制到剪贴板 + 自动打开 Termux；用户粘完切回本页时 onResume **自动复检**（不用再点）。
   · 通道通了以后「自检」还会打印环境现状：proot-distro 有无、容器列表、上次准备结果 `fail=0/1`、
     `allow-external-apps` 是否已写、`~/ubuntu-gui` 是否存在 —— 排障不用再猜。
3. **新增签名冲突预警**（很可能是"连不上"的真因）：内置包是 **F-Droid 官方签名**，早期装的是 **GitHub debug 包**，
   签名不同 → 系统会拒绝覆盖安装（表现为"装了没反应/安装失败"）。现在安装前会比对已装 Termux 的签名指纹
   （SHA-256 与内置包常量），不一致时明确给两条路：继续用现有 Termux（推荐，只需一次粘贴）/ 卸载重装内置包
   （**容器与数据会一起没**）。
4. **无设备也能验的部分（已验）**：从 Java 源码机械抽取全部 Termux 侧脚本，用真 bash（Git Bash）做语法门禁：
   `setup.sh`(179 行) / `gui_start.sh` / `gui_stop.sh` / `gui_status.sh` / `diag.sh` **全部 `bash -n` 退出 0**。
5. 新增 3 个真机用例（等设备回来跑）：通道自检必须通并拿到诊断、短命令文件内容==当前脚本全文、签名指纹格式与结论。
6. 待设备回归复验：上述 3 个新用例 + 之前两项（准备脚本 `ash -n`、VNC 帧内容非全黑）。
## [2026-09-27] 新增「图形界面（VNC）」：内置 RFB 客户端，在答题宝里显示 Linux 桌面

用户问「vnc 可以内置到 app 中吗」。结论：**客户端能内置，服务端进不了 App 进程**（targetSdk 35 不能 execve
私有目录二进制，与 Termux 同理），但服务端可以装在已有的 Ubuntu 容器里 —— APK 基本不涨（+几十 KB 代码），
服务端体积（约 78MB deb）落在容器里。

1. **App 侧（全自研，不引第三方库）**：
   · `com.oilquiz.app.vnc.VncClient`：RFB 3.8 客户端（版本/安全类型握手 → ServerInit → SetPixelFormat
     32bpp/depth24/小端 → SetEncodings [CopyRect, Hextile, Raw, DesktopSize] → 收帧循环；
     Raw/Hextile/CopyRect/DesktopSize 解码；PointerEvent/KeyEvent/ClientCutText/ServerCutText）。
     不引 android-vnc-viewer / LibVNC —— 它们都是 GPL，链接会把整个 App 拖成 GPL。
   · `VncView`：绘制帧缓冲 + 缩放/平移；单指=左键拖动（轻点即单击）、双指滑=滚轮、双指捏合=缩放、
     「右键」按钮后下一次点击=右键；软键盘经 `onCreateInputConnection` 逐字翻译成 keysym。
   · `VncKeysym`：Android KeyEvent/字符 → X11 keysym（Latin-1 就是字符码；特殊键查表；Ctrl/Alt 单独发送）。
   · `VncActivity` + `activity_vnc.xml`：状态栏（连接状态/端口/重连计数）+ 工具栏（启动图形界面/连接/断开/
     键盘/右键/适应/放大/缩小/粘贴到远端）+ 使用说明。入口：工具集 → 设置与数据 → **图形界面（VNC）**；
     环境准备页也加了「启动图形界面（VNC）」按钮（起服务端后直接开页面）。
2. **容器侧**：一键准备的第 5 步新增 `apt-get install -y --no-install-recommends xvfb x11vnc x11-utils
   x11-apps procps xdotool imagemagick`（实测 78MB/73 个 deb，arm64 全部可得），并写出 `~/ubuntu-gui`
   启停脚本（start/stop/status）。App 也能直接下发同样的启动命令（与脚本共用同一份容器内命令，
   由 `GUI_INNER_COMMAND` 单点定义 + 单元断言保证不漂移）。
3. **AI 侧**：`system_resource` 新增 `gui` 动作（start/stop/status）；更细的 GUI 操作可让 AI 用 `termux_exec`
   跑容器里的 `xdotool`（点击/输入）与 `import`（截图）。
4. **真机踩出来的五个坑（都已修，且都有对应断言）**：
   · **`-encodings` 不能传给 x11vnc**：Ubuntu 的 x11vnc 0.9.16 报 `*** unrecognized option(s) ***` 直接退出；
     编码本来就由客户端 SetEncodings 决定，客户端只报 copyrect/hextile/raw 即可。
   · **proot 会话一退出就带走所有子进程**：早先 `x11vnc -bg` 一挂后台，Xvfb 立刻跟着死；
     改成 x11vnc **前台常驻**（`exec x11vnc …`）+ Termux 侧 `setsid nohup` 挂住整段 proot 会话。
   · **`-noshm` 必须带**：proot 下 `shmget(scanline)` 被拒（Permission denied）。
   · **`-threads` 不能用**：0.9.16 线程模式下实测 x11vnc 会空转（34% CPU）且**不再监听端口**；
     改回单线程 + `-timeout 10` 防僵尸客户端。
   · **不要用裸 TCP 连接探测就绪**：连上就断会留下半开连接把单线程 x11vnc 堵死（现象：端口开着但
     永远不发版本横幅）；就绪判断改成「容器里有没有 x11vnc 进程」（`pgrep -x x11vnc`）。
   · （客户端侧）**`NetworkOnMainThreadException`**：触摸事件在 UI 线程直接 write/flush socket 会当场打死 App；
     改成输入事件入队 + 独立写线程，另给握手加 8 秒读超时，并对 x11vnc 偶发的不发横幅自动重连。
5. **真机验证**：
   · `VncClientDeviceTest`（真连 127.0.0.1:5900）：`第 1 次握手=true 尺寸=1280x720 name=localhost:1`，
     收到 FramebufferUpdate；连跑 3 次单测 + 全类 **5/5 OK**（Rfb 用例内置 5 次自动重连）。
   · 容器侧独立取证：ImageMagick `import -window root` 抓 X 根窗口 = **1280x720 / 204 色**（说明桌面真有窗口，
     不是黑屏）；python 探针收到 Raw 全帧 3,686,400 字节（=1280x720x4，与协议一致）。
   · 工具集入口的真实性用截图 OCR 取证：列表里出现「图形界面 (VNC)」。
## [2026-09-27] 修复「完整 Python 环境」一键准备在"已装过的机器"上必然失败（幂等 + 全程日志 + 真实取证）

用户报「Termux 有个问题」。Termux 自身没有 crash_logs、logcat 无 FATAL，终端文字既不在 logcat 也不在无障碍树，
于是**截图 + 电脑端 Windows OCR** 取证（本模型不能看图，OCR 是唯一读屏通道），拿到真实报错：

```
⚠️  未授予存储权限：读不到本地包 /sdcard/Download/OilQuiz/termux_env/ubuntu-base-24.04.5-base-arm64.tar.gz
Error: container 'ubuntu' already exists. Specify a different name with --name NAME
[Process completed (code 1)]
```

1. **根因（实测钉死）**：`proot-distro 5.9.0` 的 `list` 把人类可读列表打到 **stderr**（实测 stdout 0 字节 / stderr 76 字节）。
   旧守卫 `proot-distro list 2>/dev/null | grep -q ubuntu` 丢掉 stderr → 永远判成"没装" → 再 `install` →
   `container already exists` → `set -e` 当场退出 1。同机实测 `proot-distro list -q` 输出 7 字节在 stdout，
   这才是 5.9 唯一机器可读的形态；另实测 5.9 的容器真实路径是 `$PREFIX/var/lib/proot-distro/containers/<name>`
   （旧代码猜的 `installed-rootfs/` 在新版已不存在）。
2. **三处修正**：
   · **幂等判定三重**：容器目录 + `list -q` + `list 2>&1`，命中即跳过下载与安装（不再撞 already exists）；
   · **去掉 `set -e`**：逐步骤记 `❌`，结尾统一 `exit $FAIL`；容器内 Python 先探测、已完整就跳过 apt（省 2~4 分钟）；
   · 容器内安装用 `-n ubuntu` 显式命名，`~/ubuntu` 入口改用 heredoc 写入（避免嵌套引号）。
3. **可排障**：整段输出 `tee` 到 Termux 的 `~/.quiz_env_setup.log`，并写 `~/.quiz_env_setup.status`（末行 `fail=0/1`）。
   以后"看看 Termux 日志"就有确定文件可看。
4. **存储权限如实说明**：实测 Termux 未授权时 `/sdcard` 读拒绝、`~/storage` 不存在，本地 29MB 包用不上；
   界面状态区新增「Termux 存储权限」一行，脚本明确提示"执行 `termux-setup-storage` 并点允许，再点一次一键准备"，
   本次则联网下 30MB（清华镜像）。
5. **真机验证（机械抽取源码里的模板去真跑，不是字符串断言）**：
   ```
   ---- 3/5 Ubuntu 容器 ----
   ✅ 容器 ubuntu 已存在，跳过下载与安装（重复运行不会破坏已有环境）
   ---- 4/5 容器内完整 Python ----
   ✅ 容器内 Python 已完整，跳过 apt（省 2~4 分钟）
   ===== 全部完成 ✅ =====
   EXITCODE=0   （.quiz_env_setup.status: 2026-09-27 12:00:15 fail=0）
   ```
   同一场景旧脚本退出 1、新脚本退出 0。`TermuxEnvSetupDeviceTest` 增 9 条回归断言（含"不得只看 stdout""不得用 set -e"）
   + 1 个真实下发用例。
6. 附带澄清：`locks/ubuntu.lock` 残留**无害**（实测并发第二个 `proot-distro login` 正常成功、退出 0）。
## [2026-09-27] 新增「完整 Python 环境（Termux + Ubuntu）」一键准备（内置官方安装包）
1. 用户要求把完整 Python 环境**内置**。已实现：App 内置两个**官方产物**（`assets/termux_env/`），
   并提供「工具集 → 设置与数据 → 完整 Python 环境」界面三步走：
   · ① 安装 Termux（内置 APK，系统安装器确认一次）；
   · ② 导出 Ubuntu 根文件系统到 `Download/OilQuiz/termux_env/`；
   · ③ 在 Termux 里准备容器（装 proot-distro → 用导出的本地 tar.gz 建容器 → 容器内换清华源并 apt 装
     `python3-full python3-tk python3-venv python3-pip python3-setuptools` → 建 `~/ubuntu` 入口并打印验证）。
2. **内置的是官方包，不是网上随便找的**（用户此前自己下载的是 2020 年的旧包，安装被拒：`INSTALL_FAILED_VERSION_DOWNGRADE: 101 older than current 1002`）：
   · `termux-0.118.3-fdroid.apk` **108.6 MB**，来自 F-Droid 官方仓库（清华镜像 com.termux_1002.apk），**官方签名、非 debuggable**，GPLv3；
   · `ubuntu-base-24.04.5-base-arm64.tar.gz` **28.5 MB**，Ubuntu 官方 ubuntu-cdimage。
   我原先提案用 33.5MB 的 GitHub arm64 debug 包，最终改用 108.6MB 官方包——理由：debug 包是 debug 签名且 debuggable，
   用户以后**无法从 F-Droid 正常升级**（签名不同）且安全性更低。APK 因此从 617.7MB 增至约 755MB；
   若需要瘦身，删掉这两个 asset 即可，界面会提示「未内置」（代码仍可走下载通道扩展）。
3. **为什么不把 Termux 合并进 App**：Android 只允许 `targetSdk < 29` 的应用执行自己私有目录里的二进制，
   proot/apt 正是靠这一点才能跑（Termux 故意把 targetSdk 钉在 28）；答题宝是 `targetSdk 35`（build.gradle 里就有这条注释），
   所以只能「内置安装包 + 引导安装」。Manifest 里 `REQUEST_INSTALL_PACKAGES` 与 `com.termux.permission.RUN_COMMAND` 早已声明，FileProvider 也已配好。
4. 自动化程度：第 ③ 步优先用 Termux 的 `RUN_COMMAND` 自动下发（打开可见会话显示进度）；
   需要用户**手点一次**「允许」（小米禁止 adb 代授 `pm grant`，实测抛 SecurityException），
   且 Termux 侧 `allow-external-apps=true` 默认关闭——脚本第 0 步会自己写这个配置，
   但**首次**必须由用户手动跑一次（界面提供「复制手动命令」兜底，粘进 Termux 即可，脚本一次性把两件事都做掉）。
5. 许可合规：两份产物均非本项目代码，已在 `docs/THIRD_PARTY_NOTICES.md` 与资产副本中新增声明
   （Termux GPLv3 + 源码地址、Ubuntu 镜像许可与来源），并在 assets 里附 `README.txt` 说明来源/版本/用途。
6. 真机用例 `TermuxEnvSetupDeviceTest`（5 例）：内置包大小校验（>100MB / >25MB）、导出到公共目录、
   **准备脚本用 App 自带 busybox 的 `ash -n` 做真语法检查**、未授权时下发返回明确原因、布局控件齐全。
7. 实现过程中踩到/自查出的三件事（都已修）：
   · **AGP 会自动解包 assets 里 `.gz` 结尾的文件**：实测打包后条目从 `ubuntu-base-….tar.gz` 变成 `ubuntu-base-….tar`（101.8MB），
     按原名读资产直接失败（导出 0 字节）。改法：资产用中性后缀 `.targz.bin`，**导出给用户时还原成标准 `.tar.gz`**（proot-distro 按扩展名识别归档格式）。
   · **又抓到一次假绿**：`PythonEnvAuditDeviceTest` 里我加的断言被写成 `""ssl""`（双引号，Java 语法错）→ 该文件其实一直编译不过，
     而上一次「OK (1 test)」跑的是旧测试包。已修正，并在后续构建里**明确核对 `BUILD SUCCESSFUL` 字样**。
   · 准备脚本加了健壮性分支：Termux 若没有存储权限、读不到导出的本地包，**自动回退清华镜像下载**（实测下载仅 4.8 秒）。
8. 真机验证（`TermuxEnvSetupDeviceTest` **6/6 通过**）：
   · 内置包：`[EXP] 内置 Termux APK = 108 MB, Ubuntu rootfs = 28 MB`；
   · 导出：`[EXP] 导出: /storage/emulated/0/Download/OilQuiz/termux_env/ubuntu-base-24.04.5-base-arm64.tar.gz  29936675 字节`；
   · 脚本合法性：`[EXP] ash -n exit=0`（用 App 自带 busybox 做真语法检查，不是字符串断言之）；
   · **App→Termux 下发链路（一键准备的命脉）**：下发被接受 → adb 侧核对 Termux 私有目录 `_quiz_push.txt` 内容 = `PUSH_OK` ✓；
   · 界面布局 7 个控件齐全；未授权时下发会明确返回「需授予权限」。
9. APK 体积：617.7MB → **754.3MB**（+136.6MB = Termux 108.6 + rootfs 28.5，压缩后约 +137MB）。
   要瘦身只需删掉 `src/main/assets/termux_env/` 里两个大文件，界面会显示「未内置」（脚本仍有镜像回退分支，功能不受影响，只是回到联网下载）。

## [2026-09-27] 在真机上走通「完整 Python」路线：Termux + proot-distro Ubuntu（含自动化方法与三处坑）
1. 用户问「怎么走通」。目标：拿到带 tkinter/curses/readline 的真 Linux Python（App 内置的是 Chaquopy，Android 上永远没有这几样）。
2. 真机执行步骤（全部已在本机完成并验证）：
   · 装 **Termux 0.118.3（GitHub debug 包，targetSdk 28）**：选 debug 包是因为它 `android:debuggable=true`，
     可用 `adb shell run-as com.termux` **直接驱动**，绕开小米封掉的 `pm grant` 与输入注入（实测 `pm grant com.oilquiz.app com.termux.permission.RUN_COMMAND` 抛 SecurityException）；
     首次启动后 bootstrap 解压到 `files/usr`，实测 `run-as .../bash -lc 'echo EXEC_OK'` → EXEC_OK，证明 **Android 16 下 targetSdk 28 应用仍可执行私有目录二进制**（这是整条路线的前提）。
   · `pkg update`（自动选清华镜像）→ `pkg install -y proot-distro`（5.9.0，带 proot/clang/llvm）。
   · **坑①**：proot-distro 5.9 默认从 **Docker Hub** 拉镜像（`install ubuntu`）→ 国内卡死（进程挂在设备上、容器名被占，登录会报 `container is busy (PID: install)`）。
     解法：杀掉进程 + 清 `containers/ubuntu` 与 `locks`，改用 **URL 装根文件系统**：`proot-distro install -n ubuntu https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz` → **4.8 秒**（28.5MiB）。
   · 容器内换清华源 → `apt-get update && apt-get install -y --no-install-recommends python3-full python3-tk python3-venv ca-certificates python3-pip python3-setuptools`。
   · **坑②**：Ubuntu 24.04 是 **Python 3.12**，`distutils` 已被移除（想 import 它必失败，别把它当「缺失」）；用 `setuptools` 替代。
   · **坑③**：给容器写脚本时**不要嵌套引号**（我从 PowerShell 拼 `proot-distro login ubuntu -- bash -c '...'` 踩了两次：JSON 转义把换行变成字面 `\n`）。
     可靠做法：**本地写好脚本 → base64 → 设备端 `base64 -d` 落盘 → 让容器 `bash /path/script.sh` 执行**。
3. 结果（真机实测）：Ubuntu **24.04.5 LTS**，Python **3.12.3**，