# 第三方组件与许可声明（THIRD PARTY NOTICES）

> 本文件随 APK 打包（assets/apk_shell/guides/），应用启动时自动恢复到工作区 `files/` 并受删除保护；
> 仓库内另有一份同内容副本：`docs/THIRD_PARTY_NOTICES.md`（两处改动需同步）。

本项目随 APK 分发以下第三方二进制/库，按各自许可要求在此声明来源与许可。

> 依赖准入规则（哪类许可能进进程）与抓取内容合规见 [`LICENSING_AND_COMPLIANCE.md`](LICENSING_AND_COMPLIANCE.md)。

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

---

> 以下为 **2026-10-24 按 APK 实际内容逐项核对**补充的条目：扫描了 `lib/arm64-v8a` 下
> 133 个原生库（合计约 560 MB）、`assets/` 全部大文件与 Maven 依赖树。

## 9. LibreOffice 引擎（MPL-2.0）及其内置第三方库

- 组件：**LibreOffice 25.2 Android 引擎**
  （`viewer/src/main/jniLibs/arm64-v8a/liblo-native-code.so`，171.3 MB），
  以及 `assets/share`、`assets/unpack` 下的运行期资源
- 用途：Office 文档（Word / Excel / PPT）高保真离线预览（`org.libreoffice` 查看器模块）
- 许可：**Mozilla Public License 2.0（MPL-2.0）**
- 来源：LibreOffice 官方源码构建（非预编译发布产物）；版本记录见
  `viewer/src/main/assets/program/bootstraprc` 与 `versionrc`
- 许可全文：https://www.libreoffice.org/download/license/
- 源码获取：按 MPL-2.0 第 3 条，可向维护者索取对应版本的引擎源码或构建说明
- **引擎内聚合的第三方库**（随 `liblo-native-code.so` 一起分发）：
  PDFium（BSD-3-Clause）、FreeType（FTL / GPLv2 双许可）、libpng（libpng-2.0）、
  NSS / NSPR（MPL-2.0）、SQLite（Public Domain）

## 10. PDF 预览（平台 PdfRenderer，无随包原生库）

- 组件：Android 平台 `android.graphics.pdf.PdfRenderer`（API 21+）
- 用途：PDF 预览（`PdfiumPreviewActivity` / `PdfiumPreviewManager`）
- 说明：2026-11-20 移除 PdfiumAndroid 1.9.0（其 `.so` 为 4 KB 对齐，16 KB 页设备加载失败）。
  改用平台渲染器后不再随包分发 PDFium 原生库，也无需单独声明其许可。

## 11. Qualcomm GenieX / QNN / QAIRT（已移除）

- 原 `com.qualcomm.qti:geniex-android:0.8.0` 及其随包原生库（`libgeniex*`、`libQnn*`、
  `libhta_hexagon_runtime_*`、`libPlatformValidatorShared.so`、`libcalculator*.so`）已于 2026-11 移除，
  当前版本不再随包分发 Qualcomm 专有运行时。
- 骁龙 Hexagon 后端改为用 Hexagon SDK 工具链自行构建（`libggml-hexagon.a` +
  `libggml-htp-v73..v85.so`），源码来自本项目维护的 llama.cpp 分支（MIT），SDK 本身不随包分发。

## 12. 端侧推理运行时与视觉库

| 组件 | 许可 | 用途 |
|---|---|---|
| llama.cpp / ggml（`libllama*`、`libggml*`、`libmtmd`） | MIT | 本地 GGUF 大模型推理 |
| ONNX Runtime 1.32（`libonnxruntime*`） | MIT | OCR / 语音识别 / 向量模型 / 图像标签 |
| OpenCV 4.14（`libopencv_java4.so`） | Apache-2.0 | 图像预处理 |
| RapidOCR4j 1.0.0（`io.github.hzkitty:rapidocr4j-android`） | Apache-2.0 | 中文 OCR |
| libomp（LLVM OpenMP） | Apache-2.0（LLVM 例外） | 多线程运行时 |
| `libggml-hexagon.a` / `libggml-htp-v73..v85.so` | MIT（本项目维护的 llama.cpp 分支；Hexagon SDK 工具链构建） | 骁龙 Hexagon NPU 后端 |

模型文件：`assets/models/mobilenetv2-7.onnx`（Apache-2.0，图像分类）、
`assets/models/ssd-12-int8.onnx`（Apache-2.0，目标检测）、
`assets/models/all-MiniLM-L6-v2_vocab.txt`（Apache-2.0）。

> TensorFlow Lite 2.15 已于 2026-11-20 移除：代码对 `org.tensorflow` 零引用，
> 且其 `.so` 为 NDK r25 构建的 4 KB 对齐库。

## 13. Google ML Kit（已移除）

- 2026-11-20 移除 `com.google.mlkit:text-recognition*`、`image-labeling*`、`object-detection*`：
  模型质量不如 RapidOCR，且随包的 `libmlkit_google_ocr_pipeline.so` /
  `libmlkitcommonpipeline.so` 为 NDK r25 构建的 4 KB 对齐库，16 KB 页设备加载失败。
- 图像标签 / 目标检测现改用 ONNX Runtime + `mobilenetv2-7.onnx` / `ssd-12-int8.onnx`（见第 12、17 节）。

## 14. Python 运行时（Chaquopy + CPython + 科学计算包）

- **Chaquopy 17**（`com.chaquo.python`）：MIT
- **CPython 3.10**：PSF-2.0
- 随包 Python 依赖（`assets/chaquopy`，74 MB）：numpy / pandas / matplotlib / openpyxl /
  python-docx / pypdf 等，许可分别为 BSD / MIT / Apache-2.0 / PSF；原生依赖 libjpeg、
  freetype、libpng、libxml2、libxslt、libyaml、openblas、libgfortran 各自许可见 wheel 内
  `METADATA`
- 源码：https://github.com/chaquo/chaquopy 、https://www.python.org/

## 15. 文档处理

| 组件 | 许可 | 用途 |
|---|---|---|
| Apache POI 5.2.3（poi / poi-ooxml / poi-scratchpad） | Apache-2.0 | Excel / Word 读写 |
| PDFBox-Android 2.0.27（`com.tom-roush:pdfbox-android`） | Apache-2.0 | PDF 生成与文本抽取 |

> **已移除 iText 7**：iText 7 Core 是 AGPL-3.0，分发会要求整个 App 按 AGPL 开源。
> 2026-10-24 已迁移到 Apache-2.0 的 PDFBox-Android，功能等价；BouncyCastle 统一用
> `jdk18on:1.76`（Apache-2.0 风格 / MIT）。

## 16. 字体

| 字体 | 许可 / 来源 | 备注 |
|---|---|---|
| `assets/fonts/NotoSansSC-Regular.ttf` | **SIL Open Font License 1.1（OFL-1.1）**，Noto Sans SC | PDF 导出嵌入字体，可自由分发/嵌入 |
| `assets/fonts/qweather-icons.ttf` | 和风天气 SDK 资产 | 随 SDK 使用 |

> 原 `simhei.ttf`（中易专有黑体）已移除，改为 OFL 的 Noto Sans SC。
> OFL-1.1 要求随字体附许可文本：`assets/fonts/OFL.txt` 与字体同目录打包。
> 该字体与许可文本均已纳入版本库（17.7 MB，低于托管平台单文件限制），新克隆无需额外下载。

## 17. 模型权重

| 模型 | 来源 | 许可 |
|---|---|---|
| SenseVoice-Small int8（`assets/asr/model.int8.onnx`，228 MB） | FunAudioLLM/SenseVoice（经 sherpa-onnx 转换） | 以模型包内 LICENSE 为准，商用需自行确认 |
| PaddleOCR 系模型（`assets/ch_PP-OCRv4_*`、`assets/ocr/*`） | PaddleOCR | Apache-2.0 |
| MobileNetV2-7（`assets/models/mobilenetv2-7.onnx`，13.6 MB） | ONNX Model Zoo（commit 4c46cd00） | Apache-2.0 |
| SSD-12 int8（`assets/models/ssd-12-int8.onnx`，19.5 MB） | ONNX Model Zoo（commit 4c46cd00） | Apache-2.0 |

## 18. 其它 SDK 与前端库

- **和风天气 Android SDK 5.3.0**（`libs/QWeather_Public_Android_V5.3.0.jar`）：
  和风天气开发者条款（专有 SDK）
- **apksig 8.0.2**（`libs/apksig-8.0.2.jar`）：Apache-2.0
- 前端库：mermaid（MIT）、KaTeX（MIT）、highlight.js（BSD-3-Clause）
- Markdown 文档渲染（2026-10-25，`assets/js/markdown-it*.js`）：markdown-it 14.1（MIT）、
  markdown-it-footnote 4.0（MIT）、markdown-it-deflist 3.0（MIT）、
  markdown-it-task-lists 2.1（MIT）

## 19. proot（GPL v2，随包分发的 Linux 环境引导器）

- 组件：`libproot.so`、`libproot_loader.so`（AArch64）与 `libproot_loader32.so`（ARM 32 位 loader）
- 来源：Termux 维护的 proot 分支
- 用途：用内置 rootfs 运行 Linux 命令与内置 DSH 服务；**纯用户态**（ptrace 注入 loader），
  不需要 root，不修改系统分区，不绕过系统安全机制
- 许可：**GNU General Public License v2**
- 说明：GPLv2 只覆盖 proot 本身；App 以独立进程调用它，不与其它自有代码静态链接，
  源码与构建说明可从下述地址获取
- 源码：https://github.com/termux/proot
