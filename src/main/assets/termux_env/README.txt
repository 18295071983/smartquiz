答题宝 · 完整 Python 环境内置包（termux_env）
=================================================

本目录是「完整 Python 环境（Termux + Ubuntu）」一键准备所需的官方产物（三个 APK + 一个根文件系统；
CLI 离线 deb 在姊妹目录 ../termux_pkgs/），
随 App 一起分发，用户不需要自己去网上找（网上大量旧包/不可信包）。

1) termux-0.118.3-fdroid.apk            108.6 MB
   来源：F-Droid 官方仓库（清华镜像 https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo/com.termux_1002.apk）
   版本：Termux 0.118.3（versionCode 1002），F-Droid 官方签名，非 debuggable
   许可：GPLv3（源码 https://github.com/termux/termux-app）
   为什么必须内置而且要独立安装：
     Android 只允许 targetSdk < 29 的应用执行自己私有目录里的二进制；Termux 正是靠这一点
     才能跑 proot/apt。答题宝 targetSdk 36，无法把该能力合并进自己的进程，因此只能引导安装 Termux。

2) ubuntu-base-24.04.5-base-arm64.targz.bin  28.5 MB（资产用中性后缀：AGP 会自动解包 .gz 结尾的资产，
   打包后名字会变成 .tar；导出给用户时会还原成 ubuntu-base-24.04.5-base-arm64.tar.gz）
   来源：Ubuntu 官方 ubuntu-base 24.04.5（清华镜像 ubuntu-cdimage）
   用途：proot-distro 从本地文件安装 Ubuntu 容器（省掉一次下载）
   许可：Ubuntu 镜像内各组件的许可见容器内 /usr/share/doc/*/copyright

3) com.termux.api.apk                          3.8 MB   （Termux:API 插件，选装）
   com.termux.boot.apk                           26 KB   （Termux:Boot 插件，SSH 开机自启要用）
   来源：F-Droid 官方仓库；安装入口在配置页「更多工具 → 安装 Termux:API / Termux:Boot」（释放内置 APK 唤起系统安装器）。
   许可：GPLv3（termux-api、termux-boot 各自仓库）。

姊妹目录 assets/termux_pkgs/：35 个 Termux 离线 .deb，由 exportTermuxPkgs() 导出到
   Download/OilQuiz/termux_env/pkgs/，准备脚本 step2 用 dpkg 零联网安装（proot / proot-distro /
   python + pip / openssh 全家桶）。
   2026-10-28 新增 3 个（step2b「补装 CLI 工具」也可单独装，配置页有按钮）：
     tmux_3.8_aarch64.deb          449268 B  sha256 4f5c1c64fe3d99e0faec9aaa42ea17030509a29467c869032bafb8767c7aa88e
     libevent_2.1.13_aarch64.deb   202848 B  sha256 cf99e8e6cd1218c0e4956d2ee69efbb26520211b3e03407463eac9016fe55f25
     utf8proc_2.12.0_aarch64.deb    60756 B  sha256 2b5886b04030e957e1b6522faa10a8170ab203d40b60b06cf8c7f9a1ff2c24d1
   来源：https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main （下载后与该仓库 stable/main/binary-aarch64/Packages
   索引里的 SHA256、Size 逐字节校验过）。tmux 的其余依赖 ncurses / libandroid-support / libandroid-glob 已在原 32 包里。
   为什么要装 tmux：termux_submit 一直是「有 tmux 才用 tmux，否则退回 nohup」，而此前全仓库没有任何地方装 tmux，
   所以长任务实际全走 nohup —— Termux 进程被系统杀掉时作业会一起没了，tmux 会话才能撑过去。

排障（重要）
---------
· 准备脚本**可以反复运行**：已装的部分（proot-distro / 容器）会自动跳过，不会报
  "container 'ubuntu' already exists"（这是 2026-09-27 修掉的 bug：proot-distro 5.9 的 `list` 把列表
  打到 stderr，旧脚本用 `list 2>/dev/null | grep` 判定，必然误判成"没装"）。
· 容器内 Python 需要时手动装：~/ubuntu 里 apt-get install python3-full（App 的 AI 脚本用 Termux 内置 python，无需容器内装）。
· 全程日志：Termux 的 ~/.quiz_env_setup.log（每次运行会覆盖），状态：~/.quiz_env_setup.status（末行 fail=0/1）。
  出问题先 `cat ~/.quiz_env_setup.log`。
· 外部存储权限：Termux 未授予时读不到 /sdcard 上的本地包（~ 下也不会有 storage 软链），
  脚本会提示执行 `termux-setup-storage` 并点「允许」；未授权则自动联网下载 30MB（清华镜像）。

用户侧流程（App「完整 Python 环境」界面）：
   ① 安装 Termux（本目录的 APK，系统安装器确认一次）
   ② 导出本目录的 Ubuntu 根文件系统到 Download/OilQuiz/termux_env/
   ③ 在 Termux 里执行一行命令：装 proot-distro → 用导出的 tar.gz 建容器（Termux 内置 python 3.14 已随 22 包离线装好）
      （授予一次 RUN_COMMAND 权限后，第 ③ 步可由 App 自动完成）
