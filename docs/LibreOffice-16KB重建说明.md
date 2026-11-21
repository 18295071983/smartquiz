# LibreOffice 16KB 页对齐重建说明（2026-11-18）

> 现状：`viewer/src/main/jniLibs/arm64-v8a/` 下 **13 个 LibreOffice 相关 .so 的 ELF LOAD 段对齐都是 0x1000（4KB）**。
> 4KB 页设备（当前测试机 `PAGE_SIZE=4096`）可正常加载；**16KB 页设备会 `System.loadLibrary` 失败**，表现为 Office 预览打不开。
> Google Play 已对 targetSdk 35+ 要求 16KB 兼容，项目 targetSdk 36，必须处理。

## 1. 怎么确认（命令）

```powershell
$re = "$env:ANDROID_HOME\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
Get-ChildItem viewer\src\main\jniLibs\arm64-v8a\*.so | ForEach-Object {
  $a = & $re -l $_.FullName | Select-String '^\s*LOAD' |
       ForEach-Object { [regex]::Match($_.Line.Trim(), '0x([0-9a-f]+)\s*$').Groups[1].Value } |
       Select-Object -Unique
  "{0,-28} {1}" -f $_.Name, ($a -join ',')
}
```

- 期望（16KB 兼容）：`4000`（0x4000 = 16384）
- 当前：`1000`（4KB）→ 不兼容

## 2. 要重建哪些库

`viewer/src/main/jniLibs/arm64-v8a/` 下全部 13 个：

```
liblo-native-code.so   （171 MB，LibreOffice 引擎本体）
libfreebl3.so  libnspr4.so  libnss3.so  libnssckbi.so  libnssdbm3.so
libnssutil3.so  libplc4.so  libplds4.so  libsmime3.so  libsoftokn3.so
libssl3.so  libsqlite3.so
```

> 这些库来自 LibreOffice Android 构建产物（不是本项目 CMake 编的）；本项目 `src/main/cpp/CMakeLists.txt`
> 里那句 `add_link_options(-Wl,-z,max-page-size=16384)` 只覆盖 llama-jni / ggml-probe，不覆盖它们。

## 3. 重建要点

1. 在 LibreOffice 源码树里做 Android 构建（官方 `android/` 目录，`LibreOfficeViewer` 工程）。
2. 给**所有** LO 原生库的链接参数加：
   ```
   -Wl,-z,max-page-size=16384
   ```
   （在 LO 的 `configure.ac` / Android.mk 的 ldflags 里加；nss/sqlite3 是 LO 一起编出来的，也要加）
3. 或者升级到已原生支持 16KB 对齐的 LibreOffice 版本（先查对应 release notes 的 Android/16KB 条目，别只看桌面版）。
4. 重新产出 13 个 `.so`，按上面的命令逐个确认是 `4000`。
5. 替换 `viewer/src/main/jniLibs/arm64-v8a/` 下的文件（`liblo-native-code.so` 被 `.gitignore` 忽略，走 BINARY_ASSETS.md 的 Release 分发流程；其余 12 个入库）。
6. `gradlew assembleDebug` 后真机/模拟器（16KB 页）验证：能打开 docx/xlsx/pptx 且不崩。

## 4. 在 16KB 设备上验证

```powershell
adb shell getconf PAGE_SIZE        # 期望 16384
# 打开一个 docx 触发预览，logcat 里不应出现：
#   dlopen failed: ... not aligned to 16384
adb logcat -s LibreOfficeKit LibreOfficeMainActivity
```

模拟器可用 16KB 页镜像（Android 15+ 的 `-page-size 16384` 配置）验证。

## 5. 相关约定

- **改动 `viewer/src/main/assets/unpack`（字体/rdb）后，必须把 `viewer/build.gradle` 里的
  `UNPACK_VERSION` +1**——`LibreOfficeMainActivity` 用它判断是否需要重新解压到 `dataDir`；
  以前用 `VERSION_CODE`（硬编码 1），升级资源后老用户不会刷新。
- 资源与原生库只保留在 `viewer` 模块一份；app 模块不再重复放 `assets/program|share|unpack` 与 `liblo-native-code.so`。

## 6. 官方进展（2026-11-18 查证）

**有人做过，而且官方已经发版**，所以不必自己打补丁：

- **LibreOffice Viewer 25.8.3.2**（2025-11-13 发布）更新说明明确写了
  “support for 16 KB memory page sizes”：
  <https://www.apkmirror.com/apk/the-document-foundation/libreoffice-viewer/libreoffice-viewer-25-8-3-2-release/libreoffice-viewer-25-8-3-2-android-apk-download/>
  （版本列表：<https://apkpure.net/tw/libreoffice-viewer/org.documentfoundation.libreoffice/versions>）
- **Collabora Office 25.04.6-mobile**（2025-11-06）：“Bump the android ndk version, enabling support for 16kb memory pages”：
  <https://newreleases.io/project/github/CollaboraOnline/online/release/25.04.6-mobile>
- 社区踩坑记录（CollaboraOnline issue #13019）确认：**必须链接期加
  `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`**，
  事后用 objcopy 改 `p_align` 无效（Program Headers 不会变）：
  <https://github.com/CollaboraOnline/online/issues/13019>

### 结论

本项目当前基于 LibreOffice **25.2**（见 `BINARY_ASSETS.md`）。最省事的路径是：
把 Android viewer 源码升级到 **25.8.3+**（或 Collabora 对应 tag）后重编，
天然得到 16KB 对齐的 `liblo-native-code.so` 与 NSS/SQLite 库，不需要逐库 patch 链接参数。

升级时注意连带变化：
- `assets/program|share|unpack` 的目录结构/文件清单按新版本重新导出（并 +1 `UNPACK_VERSION`）；
- `org.libreoffice.kit` 的 JNI 方法签名/包名用新源码对一遍；
- 新 APK 的 `lib/arm64-v8a` 里 13 个库逐个用第 1 节命令确认是 `4000`。

## 7. 已落地：从官方 APK 提取（2026-11-18）

不用自己编 —— 直接取 **F-Droid 官方构建的 LibreOffice Viewer 26.2.7.3（arm64）** 里的文件：

```powershell
# 1) 下载（国内可用清华镜像；APK 87.5 MB）
#    https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo/org.documentfoundation.libreoffice_135.apk
#    SHA256 = e921bfb14170824572eb1e33643538803681226427948c245084f5e6356c3775

# 2) 解压 lib/arm64-v8a/*.so 与 assets/{program,share,unpack} 到临时目录

# 3) 替换
#    viewer/src/main/jniLibs/arm64-v8a/*.so        （12 个入库 + liblo 本地）
#    viewer/src/main/assets/{program,share,unpack}  （86 个文件）
#    viewer/build.gradle 里 UNPACK_VERSION +1

# 4) 验证对齐（期望 4000）
llvm-readelf -l viewer/src/main/jniLibs/arm64-v8a/liblo-native-code.so | findstr LOAD
```

### 本次替换结果

| 项 | 值 |
|---|---|
| 来源 | F-Droid `org.documentfoundation.libreoffice_135.apk`（26.2.7.3，arm64，targetSdk 35） |
| Release | <https://github.com/18295071983/smartquiz/releases/tag/binary-assets-v2>（旧 `binary-assets-v1` 已删除） |
| APK SHA256 | `e921bfb14170824572eb1e33643538803681226427948c245084f5e6356c3775` |
| `liblo-native-code.so` | 196,067,888 字节，SHA256 `96a80640c82d36c867c213844ec39e764a168129a669223991640fbd37ae5917` |
| 对齐 | 14 个 `.so` 全部 `LOAD 0x4000`（含 libc++_shared） |
| JNI 兼容 | `liblo` 导出 `Java_org_libreoffice_*` 符号 **42 个，与旧 25.2 完全一致**（无新增/缺失），现有 Java 源码可直接用 |
| 资源 | `program` 6 / `share` 42 / `unpack` 38，与旧版文件名一一对应（内容按 26.2 更新） |
| `UNPACK_VERSION` | 2 → 3（强制老用户重新解压字体/rdb） |
| 真机验证 | 安装后 `am start` 打开测试 docx：标题/正文/表格/中文渲染全部正常（当时截图存放在 `.tmp-verify/`，已随临时目录清理） |

> 许可：LibreOffice 为 MPL-2.0；F-Droid 构建对应其公开源码 tarball，提取再分发时按 MPL 要求保留声明
> （项目 `THIRD_PARTY_NOTICES` 已列 LibreOffice）。