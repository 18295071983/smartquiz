# libs/ — 本地依赖说明

本目录放**不走 Maven 的本地依赖**（`build.gradle` 里用 `implementation files(...)` 引用）。

> **新克隆本仓库时请先读这里**：下面标注「未入库」的文件不在版本库里，
> 缺失时 Gradle 会在对应的 `implementation files(...)` 行报「找不到文件」而**直接构建失败**
> （不会静默降级到别的版本）。

## 清单一览

| 文件 | 大小 | 是否入库 | 用途 |
| --- | --- | --- | --- |
| `QWeather_Public_Android_V5.3.0.jar` | 0.11 MB | ✅ 已入库 | 和风天气 Android SDK |
| `apksig-8.0.2.jar` | 0.46 MB | ✅ 已入库 | App 内导出 APK 的签名（v1+v2+v3，签名时自动对齐 STORED 条目） |

**本目录只有上面两个文件是需要入库的本地依赖**，且都已入库 —— 新克隆的仓库可直接构建。
（GenieX 原先也是一个 78 MB 的本地 AAR，现已改回 Maven 坐标，见下文。）

## 逐个说明

### QWeather_Public_Android_V5.3.0.jar —— 已入库

- **用途**：和风天气（QWeather）Android SDK。`QWeatherSdkManager` 通过它访问天气接口。
- **获取方式**：官方 GitHub 仓库的 **源码归档里直接附带 jar**
  （[releases](https://github.com/qwd/qweather-android-sdk/releases) → 对应 tag → `Source code (zip)`，
  解压后在根目录即为 `QWeather_Public_Android_V<版本>.jar`）。
  注意：**release 的 Assets 是空的**，jar 不在附件里，必须走源码归档。
- **版本历史与现状**：
  - 长期使用 **5.2.2**（v7 接口，SDK 的 location 参数可直接接受城市编码/坐标/城市名）。
  - 已升级到 **5.3.0**（2026-08-24，**当前官方最新**）：**新增 v1 天气**
    （`weatherCurrent` / `weatherForecastDaily` / `weatherForecastHourly`），
    并**移除了 13 个本仓库未使用的方法**（`airNow`/`air5d`/`airStation`/`gridNow`/`grid3d`/
    `grid7d`/`grid24h`/`grid72h`/`historicalAir`/`oceanCurrents`/`warningNow`/
    `warningList`/`qwSDK1`）。
  - 天气三接口已迁移到 **v1**。注意 v1 的参数类**只接受经纬度**，因此
    `QWeatherSdkManager.resolveLatLon()` 负责把 LocationID / 城市名（中英文）/ 坐标串
    统一换算成坐标。
- **升级时注意**：换 jar 必须**同时改 `build.gradle` 里的文件名**，否则 Gradle 仍按旧文件名查找，
  会出现「classpath 里是 A 版本、代码以为在写 B 版本」的静默错配。

### apksig-8.0.2.jar —— 已入库

- **用途**：`WebViewAPKExporter` 等在设备端导出 APK 时做签名。
- **获取方式**：Maven `com.android.tools.build:apksig:8.0.2`
  （<https://maven.google.com/web/index.html#com.android.tools.build:apksig>）。
- 既然已经以 `files()` 方式引用，直接入库比改回 Maven 坐标更省事。

### GenieX —— 已改回 Maven 坐标（不需要本地 AAR）

- **用途**：Qualcomm GenieX Android SDK——NPU 端侧 LLM 推理。自带 arm64 native 库与模型
  下载/管理，无需 NDK/CMake；支持 SM8750/SM8850。`NpuLlmChat` / `NpuEngineRouter` /
  `GenieXAdapter` 依赖它。
- **坐标**：`com.qualcomm.qti:geniex-android:0.8.0`
  （`build.gradle` 里为 `implementation "com.qualcomm.qti:geniex-android:0.8.0"`）
- **上游**：<https://github.com/qualcomm/GenieX> —— Qualcomm 官方发布，
  BSD 3-Clause + [Qualcomm Terms of Use](https://www.qualcomm.com/site/terms-of-use)。
  **POM 无任何传递依赖**，故改回坐标不会引入额外库。
- **镜像**：Maven Central 与阿里云 `public` 镜像**均已同步**（2026-10-11 实测），
  国内构建可直接拉到。
- **为什么曾经是本地 AAR**：迁入时 0.8.0 刚发布、阿里云镜像尚未同步、官方源国内慢，
  故临时改为 `implementation files('libs/geniex-android-0.8.0.aar')` 以求构建稳定。
  该理由现已不成立，故改回坐标 —— 收益是新克隆的仓库**开箱即可构建**
  （此前缺这个 78 MB 文件会直接构建失败）。
- **注意**：本地 `libs/geniex-android-0.8.0.aar` 已删除；`.gitignore` 的 `*.aar` 规则**保留**
  （防止将来误提交大体积二进制）。

## 相关背景

- 这三个依赖此前都不在版本库里，但原因不同：QWeather/apksig 的 jar 是 `*.jar` 未被忽略、
  却**从未 `git add`**（属于遗漏）；GenieX 的 aar 是被 `.gitignore` 的 `*.aar` 规则刻意排除。
  现在前两者已入库、第三者已改为 Maven 坐标，**遗留缺口清零**。
- 本目录除上述文件外，还可能有临时产物；**判断构建真正需要什么，
  以 `build.gradle` 里的 `files(...)`/`fileTree(...)` 为准**。
- 另外这些路径也由 `.gitignore` 排除、同样需要本地存在才能构建：
  `src/main/jniLibs/`、`libs/com/`、`viewer/src/main/jniLibs/**/liblo-native-code.so`。
