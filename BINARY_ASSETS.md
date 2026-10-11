# 无法入库的大体积二进制资源

本仓库有**三类**体积较大的二进制资源。它们的功能都是必需的，但**因为超过代码托管平台的
单文件上限而无法纳入版本库**，所以新克隆的仓库里没有它们，需要自行获取。

> **背景（来自历史提交 `acd989e3`）**：
> 「从版本控制移除超 Gitee 100MB 限制的大文件（本地 SenseVoice asr 模型 239MB /
> LibreOffice native 库 179MB）及日志、构建产物；.gitignore 防再入库」
>
> 平台限制：**Gitee 单文件 100 MB**；GitHub 同样 **100 MB 硬限**（50 MB 起警告）。
> 这不是"忘了提交"，靠补提交解决不了。Git LFS 也不适用 —— 需远端支持且配额通常装不下 400 MB+。

## 快速自查

构建时 **`build.gradle` 的 `BINARY-PRECHECK` 会先校验**，缺文件会直接列出：
缺哪个、多大、用途、放哪、怎么获取。所以**直接开始构建**即可，它会告诉你缺什么。

也可以手动核对：

```powershell
# 逐项检查是否存在
foreach ($p in @(
  'viewer/src/main/jniLibs/arm64-v8a/liblo-native-code.so',
  'src/main/assets/asr/model.int8.onnx',
  'src/main/assets/asr/tokens.txt')) {
  "{0,-6} {1}" -f (Test-Path $p), $p
}
```

---

## 一、`liblo-native-code.so` —— LibreOffice 引擎（约 171 MB）

| 项 | 内容 |
| --- | --- |
| 路径 | `viewer/src/main/jniLibs/arm64-v8a/liblo-native-code.so` |
| 体积 | 171.3 MB |
| 用途 | Office（Word/Excel/PPT）文档高保真离线预览，即 `org.libreoffice` 查看器模块的引擎 |
| 许可 | **MPL-2.0**（见 `viewer/src/main/res/raw/license.txt`、`notice.txt`） |
| 来源 | **LibreOffice Android Viewer 源码构建**（非预编译发布产物） |
| 版本 | `viewer/src/main/assets/program/bootstraprc` → **LibreOffice 25.2**；<br>`versionrc` 记录 `buildid=f84b2f238b188b7fccdc19e4e6dd94530b3b616d`（上游源码 commit，可用于对齐版本） |
| 获取方式 | 见下 |

### 为什么没有现成下载

LibreOffice 官方**不发布**该 `.so` 的预编译包（Android Viewer 的 native 部分需自行编译）。
社区虽有第三方整包 APK 源码（如 [gurecn/LibreOffice-android](https://github.com/gurecn/LibreOffice-android)），
但**不是官方渠道**，是否与本地版本一致无法保证。

### 获取途径

1. **从 LibreOffice 官方源码编译**（正统做法）
   - 源码：<https://cgit.freedesktop.org/libreoffice/core/>（`android/` 目录）
   - 架构说明：<https://blog.documentfoundation.org/wp-content/uploads/2018/09/loandroid_droidcon.pdf>
   - 建议 checkout 与 `versionrc` 的 buildid 或 25.2 分支一致的版本
   - 代价：**编译成本很高**（需要完整 LO 构建环境、NDK 与大量时间）
2. **向本仓库维护者索取**该预编译文件（体积大，不适合走 Git）

### 注意：这个模块的其余部分**都在库里**

`viewer` 模块 99% 已纳入版本控制，**只差这一个 `.so`**：

| viewer 子目录 | 本地 | 已入库 |
| --- | --- | --- |
| `src/main/assets`（LO 配置/资产） | 86 文件 / 17.8 MB | 85 ✅ |
| `src/main/java`（LO 官方源码） | 95 文件 | 95 ✅ |
| `src/main/res` | 161 文件 | 161 ✅ |
| `src/main/jniLibs` | 14 文件 / 178.3 MB | 13（NSS/NSPR 等全在）✅ |
| ↑ 其中 | | ❌ **只缺 `liblo-native-code.so`** |

即：早期的"源码集成"是**完整且真实**的（提交 `c843472f`：官方源码+资源+native+资产）。

### 缺失时的表现 ⚠️ 报错信息会误导你

**构建期**（实测，2026-10-11）：缺这个 `.so` 会让构建在**配置阶段**直接失败，
但 Gradle 报出的错误**与缺文件毫无关系**：

```
* What went wrong:
Failed to query the value of property 'buildFlowServiceProperty'.
> A problem occurred configuring root project 'smartquiz'.
   > Android Gradle Plugin has been applied at the root build file. ...
     To continue with the plugin in the resolved and applied state,
     specify `compileSdk` in build.gradle (D:\...\smartquiz\build.gradle).
```

**`compileSdk 36` 明明已经配好了**（`build.gradle` 的 `android { compileSdk 36 }`）。
真实原因是 `defaultConfig.ndk.abiFilters "arm64-v8a"`（其注释写着"仅 arm64-v8a：
LibreOffice 引擎只有 arm64 版"）在缺少该库时把配置阶段搞崩，而错误被 Kotlin 插件
包装后指向了无关的 `compileSdk`。

> **如果你看到 `specify compileSdk` 这类报错，先查这个 `.so` 是否存在** ——
> 不要去改 `compileSdk`、也不要怀疑 AGP 版本，方向是错的。
> `build.gradle` 顶部的 `BINARY-PRECHECK` 就是为了替代这条误导信息而加的。

实测对照（用于排除"偶发失败"的误判）：

| 条件 | 结果 |
| --- | --- |
| 干净 daemon + 缺 `.so` | **稳定 `BUILD FAILED`** |
| 文件在位，连跑 6 次 | 0 失败 |
| 缺 `.so` 但 daemon 仍持有上一次的配置状态 | 时成时败（会造成"缺文件也能构建"的**假象**） |

即：**判断"是否缺文件会导致失败"必须清掉 daemon 状态复测**，否则容易被缓存假象误导。

**运行期**：`viewer` 模块的 Java 代码仍在，但引擎库缺失 → **Office 文档预览不可用**；
`LibreOfficeKit` 相关调用会走"不可用"分支而**不会崩溃**（见提交 `30ec2e87` 的安全化处理）。

### 为什么不能"随主程序一起编译"

| 检查项 | 结果 |
| --- | --- |
| `viewer` 模块有 native 源码吗 | ❌ **零个** `.c/.cpp/CMakeLists.txt`，只有 Java（`org/libreoffice/kit` 等 JNI 绑定） |
| `viewer/build.gradle` 有 native 构建配置吗 | ❌ 无 `externalNativeBuild` / `ndk` / `cmake`，**只消费预置的 `jniLibs`** |
| 主工程 CMake 产出 | 只有 `llama-jni`、`ggml-probe`，**无任何 `liblo*` 目标** |

`liblo-native-code.so` 是**整个 LibreOffice 办公引擎**（Writer/Calc/Impress/VCL/UNO/Skia/字体等）
由 **LibreOffice 自己的构建系统**（上游源码 `android/` 目录 + gbuild/autotools）交叉编译出的产物，
源码不在本仓库中，本工程的 CMake/NDK 从未参与也无从参与。

即便把 LO 源码引入，也**无法合到主程序构建里**：它是百万行级 C++ 办公套件，Android 交叉编译
以**小时**计，且产物的文件名与 strip 规则由 LO 的 gbuild 决定，不是我们的 CMake 能复现的。

**可行的自动化途径**：在服务器/CI 上编译一次，把 `.so` 作为**代码托管平台的 Release 附件**
发布（而不是提交进 Git），他人即可用一条命令下载。需要有能跑 LO 构建的机器。

---

## 二、SenseVoice 语音识别模型（约 228 MB，**可自动获取**）

| 项 | 内容 |
| --- | --- |
| 路径 | `src/main/assets/asr/model.int8.onnx`（228.15 MB）<br>`src/main/assets/asr/tokens.txt`（0.3 MB） |
| 用途 | 本地离线语音识别（`SenseVoiceAsr` / `LocalAsrRecognizer`，基于 onnxruntime） |
| 模型 | **SenseVoice-Small**（sherpa-onnx 生态的 int8 ONNX） |
| 上游 | FunAudioLLM/SenseVoice → ModelScope `iic/SenseVoiceSmall`，经 sherpa-onnx `export-onnx.py` 转换 |
| 来源（官方文档） | <https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html> |
| 许可 | 见压缩包内 `LICENSE`（注意商用条款） |

### 自动获取（推荐）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/fetch-asr-model.ps1
# 若已在 pwsh 会话内且执行策略为 RemoteSigned 等，也可直接：
# pwsh -File scripts/fetch-asr-model.ps1
```

> **为什么要带 `-ExecutionPolicy Bypass`**：默认执行策略可能拒绝运行未签名的本地脚本
> （报"未对文件进行数字签名"）。脚本本身无需管理员权限。
>
> **脚本刻意只用 ASCII 输出**：Windows PowerShell 5.1 会按 ANSI 代码页读取**无 BOM 的
> UTF-8 脚本**，非 ASCII 字符串字面量会被解成乱码并导致语法错误（本项目已两次踩到）。
> 修改该脚本时请保持纯 ASCII。

脚本会从 sherpa-onnx **官方 release** 下载并解包，落地上面两个文件：

```
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/
  sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2        （约 155 MB）
    └── model.int8.onnx  228M   ← 与本地 228.15 MB 吻合（已核对）
        tokens.txt       308K   ← 与本地 0.3 MB 吻合（已核对）
```

> 该直链已于 2026-10-11 实测 `HTTP 200`、`155.5 MB`。
> 另有一个 **2025-09-09** 的新版本（粤语微调，226 MB），若要用新模型改脚本里的 `-Url` 与 `-InnerDir` 即可。

### `silero_vad.onnx` **已入库**（无需下载）

`src/main/assets/asr/silero_vad.onnx`（1.72 MB）在 `.gitignore` 里被显式例外保留：

```gitignore
src/main/assets/asr/*.onnx
!src/main/assets/asr/silero_vad.onnx
```

### 缺失时的表现

本地离线语音识别不可用（`LocalAsrRecognizer` 打开 assets 失败）；
在线 ASR 不受影响。

---

## 三、`src/main/jniLibs/` 下的 native 库（合计约 527 MB）

| 项 | 内容 |
| --- | --- |
| 路径 | `src/main/jniLibs/arm64-v8a/*.so`（60 个文件，合计 527.2 MB） |
| 用途 | 内置命令行工具箱（Termux 系）：busybox、aria2c、curl、gawk、ssh、ssl 等；<br>另有 `libtoolkit_libs.so`（42 MB，**已入库**）、`libllama*.so` 等 |
| 入库情况 | **部分入库**。`.gitignore` 忽略整个 `src/main/jniLibs/`，但历史上已跟踪的文件仍在版本库里 |
| 获取方式 | 这些是从 Termux 及各上游项目构建/提取的产物；如需完整重建，参考 `src/main/assets/apk_shell/` 下的说明与该目录历史提交 |

> 注：`.gitignore` 写的是整个目录，但**已被 Git 跟踪的文件不受 ignore 影响** —— 所以
> `libtoolkit_libs.so`、`libssh_libs.so` 等仍在库中，而后来新增的则不会自动入库。
> 这是"忽略规则与实际跟踪状态不一致"的历史遗留，判断标准应以
> **`git ls-files src/main/jniLibs`** 为准。

---

## 相关文件

- `libs/README.md` —— `libs/` 下的本地依赖（QWeather jar / apksig）说明，**均已入库**
- `build.gradle` —— 顶部 `BINARY-PRECHECK` 校验清单（缺文件时的报错来源）
- `scripts/fetch-asr-model.ps1` —— SenseVoice 模型自动获取
- `.gitignore` —— 相关忽略规则（`src/main/jniLibs/`、`src/main/assets/asr/*.onnx`、`viewer/src/main/jniLibs/**/liblo-native-code.so`）
