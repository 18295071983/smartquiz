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

## 5. Termux 应用（GPL v3，随包分发的安装包）

- 组件：**Termux 0.118.3**（versionCode 1002，F-Droid 官方签名，非 debuggable）
- 形式：以 APK 文件随包分发（`assets/termux_env/termux-0.118.3-fdroid.apk`，108.6 MB），由「完整 Python 环境」界面引导用户安装
- 用途：提供可执行 proot/apt 的 Linux 环境（Android 只允许 targetSdk<29 的应用执行自己私有目录里的二进制，本 App targetSdk 35，无法在自己进程内提供该能力）
- 来源：F-Droid 官方仓库（分发镜像 https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo/com.termux_1002.apk ）
- 许可：**GNU General Public License v3.0**
- 源码与许可全文：https://github.com/termux/termux-app （LICENSE.md）
- 对应源码获取方式：按 GPL v3，用户可通过上述仓库获取对应版本完整源码；本项目未对 Termux 做任何修改（原样分发官方 APK）

## 6. Ubuntu Base 根文件系统（多许可，随包分发的系统镜像）

- 组件：**ubuntu-base 24.04.5（arm64）**（`assets/termux_env/ubuntu-base-24.04.5-base-arm64.tar.gz`，28.5 MB）
- 用途：proot-distro 从本地文件创建 Ubuntu 容器（省一次下载）
- 来源：Ubuntu 官方 ubuntu-cdimage（分发镜像 https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ ）
- 许可：镜像内各组件的许可与版权声明见容器内 `/usr/share/doc/*/copyright`；Ubuntu 名称与镜像使用遵循 Canonical 的商标与再分发政策
- 本项目未修改该镜像内容（原样分发官方压缩包）

## 7. ExoPlayer / AndroidX Media3（Apache License 2.0）

- 组件：`androidx.media3` 的 `media3-exoplayer`、`media3-exoplayer-hls`、`media3-exoplayer-dash`、
  `media3-ui`、`media3-common`、`media3-session`、`media3-transformer`、`media3-effect`（版本 1.3.1）
- 用途：聊天流音视频播放（HLS / DASH / 本地文件 / 网络直链）、媒体通知与前台播放服务、本地转码与特效
- 许可：**Apache License 2.0**
- 版权：Copyright (C) The Android Open Source Project
- 许可全文：https://www.apache.org/licenses/LICENSE-2.0
- 说明：按 Apache-2.0 第 4 条随包保留版权、许可与 NOTICE；各组件的完整 NOTICE 见对应
  Maven 制品内的 `NOTICE` 文件，本文件只做汇总。播放优先走系统 MediaCodec 硬件解码，
  不随包分发软件编解码器（FFmpeg 仅用于 `media_toolkit` 转码，见第 1 节）。

## 8. 其它开源依赖（Maven，摘要）

APK 还链接了下列开源库，按各自许可保留版权与许可声明（完整依赖树可用
`./gradlew dependencies` 导出）：

| 组件 | 许可 |
|---|---|
| AndroidX / Jetpack、Kotlin 标准库 | Apache-2.0 |
| OkHttp、Retrofit、Gson、Coil、Markwon、Guava | Apache-2.0 |
| Glide | BSD-2-Clause（部分 MIT / Apache-2.0） |
| jsoup | MIT |
| flexmark-java | BSD-2-Clause |

各库的许可全文随 Maven 制品分发（`META-INF/LICENSE` 等），此处不重复粘贴全文。

