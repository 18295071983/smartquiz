# 答题宝 (SmartQuiz) — Android APK 部署指南

> **版本: 2.0 | 更新日期: 2026-05-16**

---

## 一、概述

本文档详细说明「答题宝」Android 应用的 APK 构建、签名、测试与部署全流程。适用于开发人员将应用从源码编译为可安装的 APK 文件，并进行真机测试和发布。

> **注意**：本文档仅覆盖 Android APK 构建部署，不涉及后端服务器部署。

---

## 二、前置条件

### 2.1 硬件要求

| 项目 | 最低配置 | 推荐配置 |
|------|------|------|
| CPU | 4 核处理器 | 8 核及以上 |
| 内存 | 8 GB RAM | 16 GB RAM 及以上 |
| 磁盘空间 | 20 GB 可用空间 | 50 GB SSD |
| 操作系统 | Windows 10 / macOS 12 / Ubuntu 20.04 | Windows 11 / macOS 14 / Ubuntu 22.04 |

### 2.2 必需软件

| 软件 | 版本 | 用途 | 下载地址 |
|------|------|------|------|
| **JDK** | 17 (LTS) | 编译 Java/Kotlin 代码 | [Adoptium](https://adoptium.net/) 或 [Oracle JDK](https://www.oracle.com/java/technologies/downloads/#java17) |
| **Android Studio** | Hedgehog (2023.1.1) 或更新 | IDE / SDK 管理 / 模拟器 | [developer.android.com](https://developer.android.com/studio) |
| **Android SDK** | Platform 34 + Build Tools 34.0.0 | 编译目标 API | 通过 Android Studio SDK Manager 安装 |
| **Git** | 2.40+ | 源码版本管理 | [git-scm.com](https://git-scm.com/) |

### 2.3 可选软件

| 软件 | 版本 | 用途 | 说明 |
|------|------|------|------|
| **NDK** | 26.1.10909125 | 编译 C/C++ 原生库（llama.cpp） | 仅 AI 本地推理功能需要 |
| **MSYS2** | 最新版 | Windows 下编译原生库的环境 | 仅 Windows 平台编译 so 库时需要 |
| **CMake** | 3.22+ | 构建原生库 | NDK 自带或通过 MSYS2 安装 |
| **Ninja** | 1.10+ | 原生库构建加速 | 通过 MSYS2 安装 |

---

## 三、环境配置

### 3.1 安装 JDK 17

**Windows：**
1. 下载 JDK 17 安装包（`.msi` 或 `.zip`）
2. 运行安装程序，勾选"设置 JAVA_HOME 环境变量"
3. 验证安装：

```powershell
java -version
# 输出示例：
# openjdk version "17.0.10" 2024-01-16
# OpenJDK Runtime Environment (build 17.0.10+7)
```

```powershell
javac -version
# 输出示例：javac 17.0.10
```

### 3.2 安装 Android Studio

1. 下载并安装 Android Studio
2. 首次启动时，选择"Standard"安装模式
3. 在 SDK Manager 中确认以下组件已安装：

| SDK 组件 | 版本 |
|------|------|
| Android SDK Platform | 34 |
| Android SDK Build-Tools | 34.0.0 |
| Android SDK Platform-Tools | 最新版 |
| Android SDK Command-line Tools | 最新版 |
| Android Emulator | 最新版 |
| Intel HAXM / Hypervisor | 最新版（可选） |

### 3.3 配置环境变量（Windows）

确保以下环境变量已正确配置：

| 变量名 | 示例值 |
|------|------|
| `JAVA_HOME` | `C:\Program Files\Eclipse Adoptium\jdk-17.0.10.7-hotspot\` |
| `ANDROID_HOME` | `C:\Users\用户名\AppData\Local\Android\Sdk` |
| `PATH` | 追加 `%ANDROID_HOME%\platform-tools` 和 `%ANDROID_HOME%\cmdline-tools\latest\bin` |

验证：

```powershell
echo $env:JAVA_HOME
echo $env:ANDROID_HOME
adb --version
```

---

## 四、获取源码

### 4.1 克隆仓库

```bash
git clone https://github.com/18295071983/smartquiz.git
cd smartquiz
```

### 4.2 切换分支

```bash
# 开发分支
git checkout develop

# 发布分支（用于构建正式版 APK）
git checkout release/2.0
```

### 4.3 项目结构确认

克隆完成后，确认目录结构包含以下关键文件：

```
smartquiz/
├── app/
│   ├── build.gradle.kts          # 应用构建脚本
│   ├── proguard-rules.pro        # 代码混淆规则
│   └── src/
├── build.gradle.kts              # 项目构建脚本
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── gradlew                        # Gradle Wrapper (Unix)
├── gradlew.bat                    # Gradle Wrapper (Windows)
├── build_apk_simple.bat           # 简单构建脚本 (Windows)
├── build_apk.bat                  # 完整构建脚本 (Windows)
├── build_android.sh               # 构建脚本 (Unix/macOS)
├── smartquiz.keystore             # 签名密钥库（需从团队获取）
├── settings.gradle.kts
└── local.properties               # 本地 SDK 路径（需手动创建）
```

---

## 五、打开项目并同步

### 5.1 使用 Android Studio 打开

1. 启动 Android Studio
2. 选择 **File → Open**
3. 导航到 `smartquiz` 项目根目录
4. 点击 **OK**，等待项目加载

### 5.2 Gradle 同步

- Android Studio 会自动提示同步 Gradle，点击 **Sync Now**
- 或手动：**File → Sync Project with Gradle Files**

### 5.3 创建 local.properties（如不存在）

如果 `local.properties` 文件不存在，在项目根目录手动创建：

```properties
# Windows 示例
sdk.dir=C\:\\Users\\用户名\\AppData\\Local\\Android\\Sdk

# macOS 示例
# sdk.dir=/Users/用户名/Library/Android/sdk
```

---

## 六、构建 APK

### 6.1 Debug APK（开发测试用）

**通过命令行构建（推荐）：**

```powershell
# Windows
.\gradlew assembleDebug
```

```bash
# macOS / Linux
./gradlew assembleDebug
```

**输出位置：**

```
app/build/outputs/apk/debug/答题宝-debug-2.0.apk
```

**Debug APK 特点：**
- 使用系统默认 Debug 签名
- 包含调试信息（可调试）
- 未进行 ProGuard 混淆
- 适用于开发测试和内部调试

### 6.2 Release APK（正式发布用）

**通过命令行构建：**

```powershell
# Windows
.\gradlew assembleRelease
```

```bash
# macOS / Linux
./gradlew assembleRelease
```

**输出位置：**

```
app/build/outputs/apk/release/答题宝-release-2.0.apk
```

**Release APK 特点：**
- 使用 `smartquiz.keystore` 签名
- 已进行 ProGuard 代码混淆
- 不包含调试信息
- 适用于正式发布

### 6.3 使用便捷构建脚本

项目提供了预配置的构建脚本，简化构建流程：

| 脚本文件 | 平台 | 用途 |
|------|------|------|
| `build_apk_simple.bat` | Windows | 简易构建，自动检测环境并构建 Debug APK |
| `build_apk.bat` | Windows | 完整构建，含环境检测、清理、构建 Release |
| `build_android.sh` | Unix/macOS | 完整构建，含环境检测、清理、构建 Release |

**Windows 构建示例：**

```powershell
# 简易构建（Debug）
.\build_apk_simple.bat

# 完整构建（Release，需要 keystore）
.\build_apk.bat
```

### 6.4 构建命令速查表

| 命令 | 用途 |
|------|------|
| `./gradlew assembleDebug` | 构建 Debug APK |
| `./gradlew assembleRelease` | 构建 Release APK |
| `./gradlew clean` | 清理构建缓存 |
| `./gradlew build` | 完整构建（Debug + Release） |
| `./gradlew test` | 运行所有单元测试 |
| `./gradlew connectedAndroidTest` | 在连接的设备上运行测试 |
| `./gradlew --refresh-dependencies` | 强制刷新依赖 |
| `./gradlew --stop` | 停止 Gradle Daemon |

---

## 七、原生库构建（可选）

AI 本地推理功能依赖 llama.cpp 原生库。如果不需要本地推理功能，可跳过此步骤。

### 7.1 Windows 平台（MSYS2 环境）

**第一步：安装 MSYS2**

从 [msys2.org](https://www.msys2.org/) 下载并安装 MSYS2。

**第二步：安装编译工具链**

打开 **MSYS2 UCRT64** 终端，执行：

```bash
pacman -Syu
pacman -S mingw-w64-ucrt-x86_64-gcc mingw-w64-ucrt-x86_64-cmake mingw-w64-ucrt-x86_64-ninja
```

**第三步：编译原生库**

```bash
cd src/main/cpp
bash build_llama_jni_msys2.sh
```

**第四步：验证产物**

编译成功后在以下目录生成 `.so` 文件：

```
src/main/jniLibs/
├── arm64-v8a/
│   └── libllama_jni.so
├── armeabi-v7a/
│   └── libllama_jni.so
└── x86_64/
    └── libllama_jni.so
```

### 7.2 跳过原生库构建

如果不需要 AI 本地推理功能，在 `build.gradle.kts` 中排除 native 相关依赖即可，APK 体积可减少约 50 MB。

---

## 八、APK 签名配置

### 8.1 签名密钥库

Release APK 必须使用 `smartquiz.keystore` 签名。该文件由团队管理员保管，需从私密渠道获取。

### 8.2 配置签名（build.gradle.kts）

在 `app/build.gradle.kts` 中配置签名信息：

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("../smartquiz.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "默认密码"
            keyAlias = System.getenv("KEY_ALIAS") ?: "smartquiz"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "默认密码"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-poi-rules.pro"
            )
        }
    }
}
```

**安全提示**：密码不要硬编码在 `build.gradle.kts` 中，应通过环境变量或 `local.properties`（已加入 `.gitignore`）传递：

```properties
# local.properties（不纳入版本管理）
KEYSTORE_PASSWORD=your_secure_password
KEY_ALIAS=smartquiz
KEY_PASSWORD=your_key_password
```

### 8.3 验证签名

构建完成后，验证 APK 签名：

```powershell
# 使用 keytool 验证
keytool -printcert -jarfile app\build\outputs\apk\release\答题宝-release-2.0.apk

# 或使用 apksigner（Android SDK 自带）
%ANDROID_HOME%\build-tools\34.0.0\apksigner.bat verify --print-certs app\build\outputs\apk\release\答题宝-release-2.0.apk
```

---

## 九、ProGuard 代码混淆

### 9.1 混淆规则文件

| 文件 | 用途 |
|------|------|
| `proguard-rules.pro` | 应用自定义混淆规则 |
| `proguard-poi-rules.pro` | Apache POI 库专用混淆规则 |
| `proguard-android-optimize.txt` | Android 默认优化规则（SDK 提供） |

### 9.2 常见需要排除混淆的类

```pro
# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }

# Gson
-keep class com.smartquiz.app.model.** { *; }
-keepclassmembers class com.smartquiz.app.model.** { *; }

# WebView JS Bridge
-keepclassmembers class com.smartquiz.app.webview.** {
    @android.webkit.JavascriptInterface <methods>;
}
```

---

## 十、发布前检查清单

### 10.1 代码与配置检查

| # | 检查项 | 状态 | 说明 |
|---|------|------|------|
| 1 | 更新 `versionCode` | ☐ | 整数，每次发布递增（如 1→2→3） |
| 2 | 更新 `versionName` | ☐ | 语义化版本（如 "2.0.0"） |
| 3 | 检查 `minSdk` = 31 | ☐ | 不得降低 |
| 4 | 检查 `targetSdk` = 34 | ☐ | Google Play 要求 |
| 5 | 所有用户字符串使用中文 | ☐ | 检查 `strings.xml` |
| 6 | 移除测试代码和调试日志 | ☐ | 不包括 `Logger.w`/`Logger.e` |
| 7 | 确认签名密钥库可用 | ☐ | `smartquiz.keystore` |

### 10.2 测试检查

| # | 检查项 | 状态 | 说明 |
|---|------|------|------|
| 1 | 运行所有单元测试通过 | ☐ | `./gradlew test` |
| 2 | 核心功能手动测试通过 | ☐ | 登录、答题、结果、分享 |
| 3 | 真机安装测试通过 | ☐ | 至少覆盖 3 种设备 |
| 4 | 冷启动时间 < 3 秒 | ☐ | 首次安装后测试 |
| 5 | 内存占用 < 200 MB | ☐ | Android Studio Profiler |
| 6 | 无 Crash | ☐ | 覆盖主要使用场景 |
| 7 | 深色模式兼容 | ☐ | 切换主题后 UI 正常 |

### 10.3 构建检查

| # | 检查项 | 状态 | 说明 |
|---|------|------|------|
| 1 | 执行 `./gradlew clean` | ☐ | 清理旧构建产物 |
| 2 | 执行 `./gradlew assembleRelease` | ☐ | Release 构建无错误 |
| 3 | APK 体积 < 80 MB | ☐ | APK Analyzer 检查 |
| 4 | 签名验证通过 | ☐ | `apksigner verify` |
| 5 | ProGuard 映射文件保存 | ☐ | `mapping.txt` 用于 Crash 堆栈还原 |

---

## 十一、APK 安装与测试

### 11.1 通过 ADB 安装

```powershell
# 安装到已连接的设备
adb install app\build\outputs\apk\release\答题宝-release-2.0.apk

# 覆盖安装（保留数据）
adb install -r app\build\outputs\apk\release\答题宝-release-2.0.apk

# 降级安装（测试数据库迁移时使用）
adb install -d app\build\outputs\apk\release\答题宝-release-2.0.apk
```

### 11.2 通过 USB 传输安装

1. 将 APK 文件复制到 Android 设备
2. 在设备上打开文件管理器，找到 APK
3. 点击安装（需开启"允许安装未知来源应用"）

### 11.3 测试设备要求

| 项目 | 最低要求 |
|------|------|
| Android 版本 | 12.0 (API 31) 及以上 |
| 屏幕分辨率 | 1080 x 1920 (FHD) 及以上 |
| 内存 (RAM) | 4 GB 及以上 |
| 存储空间 | 200 MB 以上可用 |

---

## 十二、常见问题排查

### 12.1 SDK 未找到

**错误信息：**
```
SDK location not found. Define location with sdk.dir in the local.properties file
```

**解决方案：**

在项目根目录创建 `local.properties` 文件：

```properties
sdk.dir=C\:\\Users\\用户名\\AppData\\Local\\Android\\Sdk
```

或设置 `ANDROID_HOME` 环境变量。

### 12.2 NDK 未找到

**错误信息：**
```
NDK is not configured
```

**解决方案：**

1. 在 Android Studio 中打开 **SDK Manager**
2. 切换到 **SDK Tools** 标签
3. 勾选 **NDK (Side by side)**，选择版本 **26.1.10909125**
4. 点击 **Apply** 安装

或在 `build.gradle.kts` 的 `defaultConfig` 中指定：

```kotlin
ndk {
    abiFilters += listOf("arm64-v8a", "armeabi-v7a")
}
```

### 12.3 依赖下载失败

**错误信息：**
```
Could not resolve all dependencies
```

**解决方案：**

```powershell
# 方案一：刷新依赖
.\gradlew --refresh-dependencies

# 方案二：清理 Gradle 缓存后重试
Remove-Item -Recurse -Force $env:USERPROFILE\.gradle\caches
.\gradlew assembleRelease
```

**方案三：配置镜像仓库**（国内网络环境）

在 `build.gradle.kts` (project) 中添加阿里云镜像：

```kotlin
repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    google()
    mavenCentral()
}
```

### 12.4 构建内存不足 (OutOfMemory)

**错误信息：**
```
Java heap space / GC overhead limit exceeded
```

**解决方案：**

在 `gradle.properties` 中增加堆内存：

```properties
org.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=512m -XX:+HeapDumpOnOutOfMemoryError
org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.caching=true
```

### 12.5 签名密钥库问题

**错误信息：**
```
Keystore file not set for signing config release
```

**解决方案：**

1. 确认 `smartquiz.keystore` 文件存在于项目根目录
2. 确认 `local.properties` 或环境变量中的密码正确
3. 如果是子模块开发者，联系管理员获取签名密钥库

### 12.6 编译版本不匹配

**错误信息：**
```
Android Gradle plugin requires Java 17 to run
```

**解决方案：**

在 Android Studio 中设置 JDK 版本：
**File → Project Structure → SDK Location → JDK Location**，选择 JDK 17 路径。

---

## 十三、CI/CD 集成建议

### 13.1 GitHub Actions 工作流示例

```yaml
# .github/workflows/android-build.yml
name: Android Build

on:
  push:
    branches: [develop, 'release/**']
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Cache Gradle
        uses: actions/cache@v3
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*') }}

      - name: Run unit tests
        run: ./gradlew test

      - name: Build Debug APK
        run: ./gradlew assembleDebug

      - name: Upload APK artifact
        uses: actions/upload-artifact@v4
        with:
          name: 答题宝-debug
          path: app/build/outputs/apk/debug/答题宝-debug-*.apk
```

### 13.2 Release 构建工作流

在 release 分支推送时触发 Release 构建，需要提前在 GitHub Secrets 中配置：

| Secret 名称 | 内容 |
|------|------|
| `KEYSTORE_BASE64` | `smartquiz.keystore` 的 Base64 编码 |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥密码 |

---

## 十四、版本管理

### 14.1 版本号规则

| 属性 | 规则 | 示例 |
|------|------|------|
| `versionCode` | 整数，每次发布 +1 | `1` → `2` → `3` |
| `versionName` | 语义化版本 x.y.z | `2.0.0` → `2.0.1` → `2.1.0` |

### 14.2 版本对应关系

| 版本名称 | versionCode | versionName | 说明 |
|------|------|------|------|
| 答题宝 1.0 | 1 | 1.0.0 | 初始版本 |
| 答题宝 2.0 | 2 | 2.0.0 | AI 功能集成、Compose 重构 |

### 14.3 构建产物命名

```
答题宝-{variant}-{versionName}.apk

示例：
  答题宝-debug-2.0.apk     → Debug 版本
  答题宝-release-2.0.apk   → Release 版本
```

---

## 十五、快速参考

### 15.1 一键构建命令 (Windows PowerShell)

```powershell
# 进入项目目录
Set-Location D:\quzp\app

# 检测环境
java -version
.\gradlew --version

# 清理
.\gradlew clean

# 测试
.\gradlew test

# 构建 Debug
.\gradlew assembleDebug

# 构建 Release（需要 keystore）
.\gradlew assembleRelease

# 安装到设备
adb install app\build\outputs\apk\debug\答题宝-debug-2.0.apk
```

### 15.2 关键路径速查

| 用途 | 路径 |
|------|------|
| Debug APK 输出 | `app/build/outputs/apk/debug/答题宝-debug-2.0.apk` |
| Release APK 输出 | `app/build/outputs/apk/release/答题宝-release-2.0.apk` |
| ProGuard 映射文件 | `app/build/outputs/mapping/release/mapping.txt` |
| 应用级构建脚本 | `app/build.gradle.kts` |
| 项目级构建脚本 | `build.gradle.kts` |
| 签名密钥库 | `smartquiz.keystore`（项目根目录） |
| 混淆规则 | `app/proguard-rules.pro` |
| Gradle 属性 | `gradle.properties` |

---

## 十六、版本历史

| 版本 | 日期 | 变更内容 |
|------|------|------|
| 1.0 | 2025-10-01 | 初始版本（基础 APK 构建流程） |
| 2.0 | 2026-05-16 | 新增原生库构建指南、签名配置详解、CI/CD 建议、ProGuard 配置、完整发布清单 |

---

*本文档由「答题宝」技术团队维护，如有疑问请联系项目负责人。*