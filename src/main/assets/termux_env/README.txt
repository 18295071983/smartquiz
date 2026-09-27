答题宝 · 完整 Python 环境内置包（termux_env）
=================================================

本目录是「完整 Python 环境（Termux + Ubuntu）」一键准备所需的两个官方产物，
随 App 一起分发，用户不需要自己去网上找（网上大量旧包/不可信包）。

1) termux-0.118.3-fdroid.apk            108.6 MB
   来源：F-Droid 官方仓库（清华镜像 https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo/com.termux_1002.apk）
   版本：Termux 0.118.3（versionCode 1002），F-Droid 官方签名，非 debuggable
   许可：GPLv3（源码 https://github.com/termux/termux-app）
   为什么必须内置而且要独立安装：
     Android 只允许 targetSdk < 29 的应用执行自己私有目录里的二进制；Termux 正是靠这一点
     才能跑 proot/apt。答题宝 targetSdk 35，无法把该能力合并进自己的进程，因此只能引导安装 Termux。

2) ubuntu-base-24.04.5-base-arm64.targz.bin  28.5 MB（资产用中性后缀：AGP 会自动解包 .gz 结尾的资产，
   打包后名字会变成 .tar；导出给用户时会还原成 ubuntu-base-24.04.5-base-arm64.tar.gz）
   来源：Ubuntu 官方 ubuntu-base 24.04.5（清华镜像 ubuntu-cdimage）
   用途：proot-distro 从本地文件安装 Ubuntu 容器（省掉一次下载）
   许可：Ubuntu 镜像内各组件的许可见容器内 /usr/share/doc/*/copyright

用户侧流程（App「完整 Python 环境」界面）：
   ① 安装 Termux（本目录的 APK，系统安装器确认一次）
   ② 导出本目录的 Ubuntu 根文件系统到 Download/OilQuiz/termux_env/
   ③ 在 Termux 里执行一行命令：装 proot-distro → 用导出的 tar.gz 建容器 → apt 装 python3-full + python3-tk
      （授予一次 RUN_COMMAND 权限后，第 ③ 步可由 App 自动完成）
