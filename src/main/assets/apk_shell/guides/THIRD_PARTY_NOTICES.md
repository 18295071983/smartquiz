# 第三方组件与许可声明（THIRD PARTY NOTICES）

> 本文件随 APK 打包（assets/apk_shell/guides/），应用启动时自动恢复到工作区 `files/` 并受删除保护；
> 仓库内另有一份同内容副本：`docs/THIRD_PARTY_NOTICES.md`（两处改动需同步）。

本项目随 APK 分发以下第三方二进制/库，按各自许可要求在此声明来源与许可。

## 1. FFmpeg / FFmpegKit（LGPL v3）

- 组件：ffmpeg-kit（min 变体，Android 库形式）
- 坐标：`dev.ffmpegkit-maintained:ffmpeg-kit-min:8.1.9`（Maven Central）
- 说明：原 `com.arthenica:ffmpeg-kit-*` 已于 2025-04 停止维护，此为社区活跃维护的 drop-in 分叉（同一套 `com.arthenica.ffmpegkit` API）
- 进程内 ffmpeg 版本：n8.1.3（min 构建：不含 GPL 组件、不含 x264/mp3lame/libopus 等外部编码库）
- 许可：**GNU Lesser General Public License v3.0**
- 源码与许可全文：https://github.com/ffmpegkit-maintained/ffmpeg （LICENSE 文件）
- 上游 FFmpeg：https://ffmpeg.org/ （LGPL v2.1+，本项目所用构建为 LGPL 配置）
- 对应源码获取方式：按 LGPL v3 第 4 条，用户可通过上述仓库地址获取对应版本的完整源码；如需离线副本可向维护者索取

## 2. BusyBox（GPL v2）

- 组件：BusyBox 1.38（Termux 官方 bionic 构建，作为内置 Linux 工具箱的可执行文件随包分发）
- 用途：`linux_shell` 工具的 400+ 个常用命令
- 许可：**GNU General Public License v2**
- 源码：https://busybox.net/ 与 https://github.com/termux/termux-packages

## 3. OpenSSL / OpenSSH（各自许可）

- 组件：OpenSSL 3.x（Apache-2.0 风格许可）、OpenSSH（BSD 风格许可）
- 用途：`linux_shell` 的 https/ssh/scp/sftp 能力
- 源码：https://www.openssl.org/source/ 、https://www.openssh.com/

## 4. 其它随包工具

jq（MIT）、ripgrep（MIT/Unlicense）、sqlite3（Public Domain）、zstd（BSD/GPLv2 双许可）、curl（curl 许可）、
aria2c（GPLv2+）、zip/unzip（Info-ZIP）、file（BSD）、tree（GPLv2）、ncdu（MIT）、htop（GPLv2）、tmux（ISC）、nano（GPLv3）、gawk（GPLv3）。

以上均为 Termux 官方仓库构建，源码见 https://github.com/termux/termux-packages 。