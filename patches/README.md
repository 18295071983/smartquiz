# patches/ — 对 vendored llama.cpp 的本地改动

## 为什么有这个目录

`src/main/cpp/llama.cpp` 是一个**独立的嵌套 git 仓库**，且被主仓库的
`.gitignore:131` 忽略。这意味着：

- 对它的改动**不会随主仓库进 Gitee**；
- 主仓库的提交历史里**看不到**这些改动；
- 一旦重装系统、清理目录、或重新 clone llama.cpp，**这些改动就丢了**。

而这些改动是 **NPU（Hexagon/HTP）能用的前提** —— 没有它们，本工程的
llama.cpp 根本编不出 Hexagon 后端，就算编出来也开不了 DSP session。

所以把净差异导出成 patch 放在这里，随主仓库一起备份。

## 文件

| 文件 | 说明 |
|---|---|
| `0001-android-app-compat.patch` | 4 文件、+61 / −4 的净差异（`git apply` 格式） |
| `BASE_REVISION.txt` | patch 所基于的上游提交号与本地 HEAD |
| `restore-llama-cpp.sh` | 一键还原（bash，供 Linux/macOS/CI） |
| `restore-llama-cpp.ps1` | 一键还原（PowerShell，供 Windows） |

## 4 处改动分别解决什么

### 1. `ggml-hexagon/CMakeLists.txt` — `PREBUILT_LIB_DIR` 空值（编译期失败）

SDK 的 `hexagon_fun.cmake` 里有 `string(FIND ${PREBUILT_LIB_DIR} "toolv81" ...)`。
该变量为空时会退化成只传 2 个参数，CMake 直接报
`string sub-command FIND requires 3 or 4 parameters`，配置阶段就中断。

上游没暴露这个问题，是因为 `ExternalProject_Add` 里的
`-DPREBUILT_LIB_DIR=...` **只作用于 HTP 子构建**，主构建该变量是空的。

**改法**：给一个非空默认值 `android_aarch64`（SDK 6.6.0.0 里真实存在的名字）。
这些路径只被拼进 include/link 列表，对 skel 构建并非必需。

### 2. `ggml-hexagon/CMakeLists.txt` — HTP 子工程的 CMake 版本（编译期失败）

HTP 子工程要求 `cmake_minimum_required(VERSION 3.22.2)`，而 Android SDK 随附的是
3.22.1，ExternalProject 沿用宿主 cmake 会直接失败：

```
CMake 3.22.2 or higher is required. You are running version 3.22.1-g37088a8-dirty
```

**改法**：若已知位置存在更新的 cmake（`C:/Program Files/CMake/bin/cmake.exe`），
显式传给子构建；找不到则保持默认行为。

### 3. `ggml-hexagon/CMakeLists.txt` — HTP skel 从不被构建（构建图缺失）

关键差异：Gradle 的 `externalNativeBuild` 调用的是 **`ninja <target>`，不是 `ninja all`**。
上游只在 **Windows + 证书**分支里用 `${HTP_PROJECTS}` 建依赖，Android 分支下这些
ExternalProject 目标**不在默认构建图里**，于是 `htp-v*` 从不被触发。

表现：`Error copying file ... libggml-htp-v73.so`
（即 `libggml-htp-v73/75/79/81.so` 根本不存在）

**改法**：把 HTP 目标收集进 `${HTP_PROJECTS}`，并
`add_dependencies(${TARGET_NAME} ${HTP_PROJECTS})`，
让 ninja 构建 `ggml-hexagon` 时先构建这些 skel。

### 4. `ggml-hexagon/ggml-hexagon.cpp` — `ADSP_LIBRARY_PATH`（**运行时决定性修复**）

DSP skel 随 APK 装进**应用私有目录**，但加载它的 `cdsprpcd`
（`vendor_cdsprpcd` 守护进程）**读不了该目录**（SELinux：目录是 `apk_data_file`，
守护进程是 `vendor_cdsprpcd:s0`）。于是上游那个相对 URI
`file:///libggml-htp-v%u.so?...` 永远解析不到：

```
apps_std_fopen_with_env failed ... (No such file or directory)
HTP0 failed to open session ... error 0x80000406
_rtld_map_object_ex: cannot open ... errno 2
```

**改法**：把 FastRPC 的搜索路径指向应用的 native 库目录 ——
这正是 GenieX 的做法（它的 AAR 用同一个相对 URI 且能工作，就是靠这条查找路径）：

```cpp
const char * lib_dir = getenv("GGML_HEXAGON_LIB_DIR");
if (lib_dir != nullptr && lib_dir[0] != '\0') {
    setenv("ADSP_LIBRARY_PATH", lib_dir, 1);
}
```

> 备注：把 URI 改成绝对路径（`/data/app/.../lib/arm64/...`）**同样失败**
> （`dlopen_ex failed`）。保留上游相对 URI + 设 `ADSP_LIBRARY_PATH` 才成功。
> 因此 Java 侧必须调 `LlamaHelper.initBackendPreference()` →
> `nativeSetDspLibDir(<nativeLibraryDir>)` 把该环境变量喂进来。

### 另外两个既有小修（同在 patch 内，都必要）

| 文件 | 改动 | 为什么必要 |
|---|---|---|
| `ggml-opencl/kernels/embed_kernel.py` | `open(...)` 加 `encoding="utf-8"` | Windows 默认 GBK，读 kernel 源码会崩 —— 没有它**在 Windows 上编不出**带 kernel 的 OpenCL 后端 |
| `src/llama-grammar.cpp` | `fprintf(stderr, ...)` → `LLAMA_LOG_ERROR(...)` | grammar 解析失败原本只写 stderr，Android 上看不到；改走 `LLAMA_LOG_ERROR` 才**能在 logcat 里看到** —— 这是排查工具调用/grammar 问题的前提 |

## 效果（真机验证）

| 项 | 打补丁前 | 打补丁后 |
|---|---|---|
| `ggml-hexagon` 编译 | 配置即报错 | 通过 |
| `libggml-htp-v73/75/79/81.so` | 不存在 | 生成并打包（869/817/837/865 KB） |
| `HTP0` 设备 | 无法开 session | `hwinfo: threads 8, hvx 8, hmx 1, vtcm 8 MB` |
| 层卸载 | — | `offloaded 25/25`（Qwen3.5-2B）/ `43/43`（MiniCPM5） |
| prefill / decode | OpenCL ~205 / ~15.6 tok/s | **~1500 / 29-34 tok/s** |

## 如何还原

一键（Windows）：

```powershell
powershell -ExecutionPolicy Bypass -File patches/restore-llama-cpp.ps1
```

一键（Linux/macOS/CI）：

```bash
bash patches/restore-llama-cpp.sh
```

脚本会：clone 上游 → checkout 到 `BASE_REVISION.txt` 里的 `base_commit` →
`git apply` 本 patch → 打印结果。

手动：

```bash
cd src/main/cpp
git clone https://gitcode.com/gh_mirrors/ll/llama.cpp.git
cd llama.cpp
git checkout <BASE_REVISION.txt 里的 base_commit>
git apply ../../../patches/0001-android-app-compat.patch
```

## 升级上游 llama.cpp 之后

patch 基于 `BASE_REVISION.txt` 记录的提交。上游更新后请**重新生成**，不要硬套：

```bash
cd src/main/cpp/llama.cpp
git fetch origin
git diff origin/master..master > ../../../patches/0001-android-app-compat.patch
```

若新上游改了这几个文件，`git apply` 会**明确报错**（而不是静默走偏）——
这时按报错逐个 rebase 本地的 4 处改动，再重新生成 patch。

## 注意

- **不要**用 `git format-patch` 导出提交序列。本地历史里有个提交
  （`8159a90`，OpenCL 计时探针）在时间上**早于**那次把上游并进来的合并
  （`2c36e81`），它的内容**已经被上游包含**。导出提交序列会把这段冗余
  一起带上，`git apply` 时必然失败。**只导出
  `git diff origin/master..master` 的净差异**。
- patch 只包含**净差异**（4 文件），不含上游内容，因此与上游版本无关地可读。
