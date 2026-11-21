# LibreOffice 16 KB 构建手册（NDK 28）

> 目标：把 `liblo-native-code.so` 与 NSS/NSPR/SQLite（当前由 NDK **r25b** 构建、
> 4 KB 对齐）重编为 **NDK 28 / 16 KB 对齐**，同时保留 LibreOffice 功能。

## 0. 可行性结论（源码已确认）

`libreoffice-core/configure.ac`（第 875–886 行）：

```
11.1.*|12.1.*|…|25.*|26.*)  → ERROR: Building for Android requires NDK version >= 27.*
27.*|28.*|29.*)             → OK
```

所以 **NDK 28 是官方支持范围**；NDK 28 默认按 16 KB 对齐 LOAD 段，无需额外参数
（加 `-Wl,-z,max-page-size=16384` 做双保险也不冲突）。

## 1. 环境要求（必须 Linux）

| 项 | 要求 | 备注 |
|---|---|---|
| 系统 | Linux x86_64（Ubuntu 22.04+ / WSL2）| **Windows 不支持构建 LO Android 版** |
| 磁盘 | ≥ 20 GB 可用（源码 1.3GB + workdir + instdir）| 本机 D 盘仅 ~5GB，需先腾空间 |
| 内存 | ≥ 8 GB | 建议 16 GB |
| JDK | 17 | `--with-jdk-home` |
| NDK | `28.2.13676358`（仓库已用同版本）| 目录内需有 `source.properties` |
| SDK | Android SDK（API 36）| `--with-android-sdk` |
| 构建工具 | autoconf / automake / libtool / gperf / python3 / pkg-config | `apt install` |

## 2. 构建命令

```bash
cd /path/to/libreoffice-core

# NDK 28 默认 16KB 对齐；下面两个 flag 是显式声明，便于以后换回 r27 时仍生效
export LDFLAGS="-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

./autogen.sh \
    --with-distro=LibreOfficeAndroid \
    --with-android-ndk=/opt/android-ndk-r28 \
    --with-android-sdk=/opt/android-sdk \
    --with-jdk-home=/usr/lib/jvm/java-17-openjdk-amd64 \
    --with-android-api-level=31 \
    --disable-dependency-tracking

make -j"$(nproc)"          # 首次全量：1~3 小时（视机器）
make -C android install    # 产出 Android 侧库/资源（按实际 makefile 目标）
```

> `--with-distro` 取值以 `./autogen.sh --help | grep -i android` 为准；
> 若该版本没有 Android distro 预设，去掉此项，保留 NDK/SDK 参数即可（configure 会自动进入 Android 模式）。

## 3. 产出物位置

| 文件 | 典型路径 |
|---|---|
| `liblo-native-code.so` | `instdir/program/liblo-native-code.so` |
| `libnspr4 / libnss3 / …` | `instdir/program/` 或 `workdir/LinkTarget/...` |
| `libsqlite3.so` | `instdir/program/` |

用下面命令找齐：

```bash
find instdir workdir -name "liblo-native-code.so" -o -name "libnspr4.so" \
     -o -name "libnss3.so" -o -name "libsqlite3.so" | sort
```

## 4. 验证（必须在替换前做）

```powershell
# 1) 对齐：13 个库应全部 LOAD_align=0x4000
python tools/check_elf_16k.py  /path/to/new/libs

# 2) 构建版本：应显示 r28，而不是 r25b
python tools/inspect_so_origin.py /path/to/new/libs/liblo-native-code.so
#   期望：.note.android.ident: Android r28 …
```

## 5. 替换回仓库

```powershell
# 备份旧库（r25b，4KB）
Copy-Item src\main\jniLibs\arm64-v8a\liblo-native-code.so `
          src\main\jniLibs\arm64-v8a\liblo-native-code.so.r25b.bak

# 覆盖 13 个库（liblo-native-code + NSS/NSPR 11 个 + sqlite3）
# 同时更新 viewer\src\main\jniLibs\arm64-v8a\ 下的同名库（若有）
```

然后：

```powershell
python tools/check_elf_16k.py            # BAD 应为 0（Hexagon 白名单除外）
python tools/check_apk_so_align.py       # STORE 且未对齐项 = 0
.\gradlew.bat assembleDebug
adb install -r build\outputs\apk\debug\答题宝-debug-2.0.apk
```

## 6. 回归检查（LO 功能）

1. App 内打开一个 `.odt/.docx/.xlsx`（文档查看器）
2. 正常渲染、翻页、缩放；`logcat | Select-String "libreoffice|liblo"` 无 `dlopen` 失败
3. 16 KB 设备/模拟器上再跑一次（4 KB 设备只能验证兼容性）

## 7. 常见坑

| 现象 | 原因 / 处理 |
|---|---|
| `Unrecognized Android NDK` | NDK 目录缺 `source.properties`（不是完整 NDK）|
| `requires NDK version >= 27.*` | 用了 r25b/r26，换 r28 |
| 链接 OOM | 降 `-j` 到 4~8 |
| `make` 找不到 Android distro | 去掉 `--with-distro`，只保留 `--with-android-ndk/sdk` |
| 替换后崩溃 | 新库与 `viewer` Java 层版本不一致；需同版本源码构建，不能混用第三方 APK 的库 |
| Play 审核仍报 4 KB | 检查是否漏了 `libsqlite3`/`libnss*`，或 `viewer` 目录下还有旧副本 |
