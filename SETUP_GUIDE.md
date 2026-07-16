# 答题宝 (SmartQuiz) 环境搭建指南

## 一、系统要求

| 组件 | 最低版本 | 推荐版本 |
|------|---------|---------|
| 操作系统 | Windows 10 / macOS 11 / Ubuntu 20.04 | Windows 11 |
| JDK | 17 | 17 |
| Android Studio | Hedgehog (2023.1.1) | Ladybug (2024.2) |
| Android SDK | API 34 | API 34 |
| NDK | 26.1.10909125（仅构建本地库时需要） | 26.1.10909125 |
| 磁盘空间 | 10 GB | 20 GB 以上 |

## 二、安装 Android Studio

### Windows

1. 下载 Android Studio：https://developer.android.com/studio
2. 运行安装程序，使用默认选项安装
3. 首次启动时选择 **Standard** 安装类型，这会自动安装：
   - Android SDK
   - Android SDK Platform 34
   - Android SDK Build-Tools 34.0.0
   - Android Emulator

### macOS

```bash
brew install --cask android-studio
```

或从官网下载 DMG 安装包。

### 配置 JDK 17

Android Studio 自带 JDK 17，无需单独安装。如需在命令行使用：

- Windows：JDK 位于 `C:\Program Files\Android\Android Studio\jbr`
- macOS：JDK 位于 `/Applications/Android Studio.app/Contents/jbr/Contents/Home`

可选：添加到系统环境变量 `JAVA_HOME`。

## 三、克隆项目

```bash
git clone https://github.com/18295071983/smartquiz.git
```

## 四、用 Android Studio 打开项目

1. 启动 Android Studio
2. 点击 **Open** → 选择 `smartquiz/` 目录
3. 等待 Gradle 同步完成（首次约 5-15 分钟，取决于网速）
4. 如弹出 SDK 缺失提示，按照向导安装即可

### 同步过程中可能遇到的问题

**问题：Gradle 下载慢**
- 解决方法：等待即可，首次会下载 Gradle 8.13（约 100MB）

**问题：依赖下载失败**
- 解决方法：检查网络连接，可能需要配置代理
- 在 `gradle.properties` 中添加代理配置（如有需要）

**问题：SDK 版本不匹配**
- 解决方法：用 SDK Manager 下载 API 34 和 Build-Tools 34.0.0

## 五、构建 APK

### 方法一：Android Studio（推荐）

1. 菜单栏 → **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**
2. 等待构建完成，点击通知栏的 **locate** 查看 APK
3. 输出路径：`app/build/outputs/apk/debug/答题宝-debug-2.0.apk`

### 方法二：命令行

```bash
# Debug 版本
./gradlew assembleDebug

# Release 版本（需要签名密钥 smartquiz.keystore）
./gradlew assembleRelease
```

### 使用构建脚本

项目根目录提供了多个便捷脚本：

```bash
# Windows - 最简单的构建方式
build_apk_simple.bat

# Windows - 完整构建流程
build_apk.bat

# Linux/macOS
bash build_android.sh
```

## 六、安装到设备

### 模拟器

1. Android Studio → **Device Manager** → **Create Device**
2. 选择设备型号（推荐 Pixel 6 或更高，API 34）
3. 点击 **Run** 按钮（绿色三角）或按 `Shift+F10`

### 真机

1. 开启开发者选项和 USB 调试
2. USB 连接电脑
3. 点击 **Run** 或使用命令行：

```bash
adb install app/build/outputs/apk/debug/答题宝-debug-2.0.apk
```

## 七、构建本地库（可选）

本地 AI 推理引擎基于 llama.cpp（C++ JNI），需要 NDK 和 MSYS2 环境编译。**如果不需要本地 AI 功能，可跳过此步骤。**

### 安装 MSYS2（Windows）

1. 下载：https://www.msys2.org/
2. 安装后打开 **MSYS2 UCRT64** 终端
3. 安装编译工具：

```bash
pacman -Syu
pacman -S mingw-w64-ucrt-x86_64-gcc mingw-w64-ucrt-x86_64-cmake mingw-w64-ucrt-x86_64-ninja mingw-w64-ucrt-x86_64-opencl-icd
```

### 编译 .so 文件

```bash
cd src/main/cpp
bash build_llama_jni_msys2.sh
```

编译产物将输出到 `src/main/jniLibs/` 目录。

## 八、验证安装

成功构建后，应用应能正常启动，包含以下功能：

- 题库管理（导入/导出题目）
- 答题模式（挑战/考试/练习/背诵）
- AI 对话（需下载模型或配置 API）
- 文件预览（Word/Excel/PDF）
- OCR 文字识别

---

**遇到问题？** 请查阅 [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md) 中的常见问题章节。