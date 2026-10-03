# 答题宝 (SmartQuiz) - 智能学习助手

> 集成本地大语言模型推理与 Agent 智能代理的 Android 综合学习平台

[![Version](https://img.shields.io/badge/Version-2.1.0-brightgreen)](CHANGELOG.md)
[![Android](https://img.shields.io/badge/Android-12%2B-brightgreen)](https://developer.android.com)
[![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/projects/jdk/17/)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

## 简介

答题宝是一款功能丰富的 Android 学习应用，集成了**本地大语言模型（LLM）推理引擎**和 **Agent 智能代理系统**，为用户提供智能化的学习体验。从题库管理、智能答题、AI 解题分析到多格式文件处理，答题宝致力于成为一站式的学习工具平台。

### 核心亮点

- **完全离线 AI 推理** - 基于 llama.cpp 的 C++ JNI 推理引擎，无需网络即可运行本地 LLM
- **双轨 Agent 系统** - 本地 Agent + 在线 Agent 双架构，支持软件层思考链与工具调用
- **AI 工具集** - shell_command（内置 busybox 400+ 命令）/ termux_exec（Termux 完整 Linux 环境）/ **ssh_exec（JSch 纯 Java，连接任意 SSH 主机）** 等 30+ 工具
- **多模式推理** - 支持直接回答、思维链（Chain-of-Thought）、ReAct、计划执行等多种推理模式
- **全栈语音能力** - 集成 TTS 语音合成与 ASR 语音识别，支持百度/讯飞/阿里云/火山引擎等多服务商
- **多格式文件支持** - 支持 Excel、Word、PDF、CSV、JSON、Markdown 等格式的导入导出与预览
- **GPU 双后端** - Vulkan / OpenCL 双后端推理（llama.cpp），设备支持哪个用哪个
- **Termux 完整环境** - 一键准备向导：安装 Termux + Ubuntu 容器（proot），容器内 Python/apt/git 全可用

### 性能指标 (v2.1.0+)

| 指标 | 优化前 | 优化后 | 提升 |
|------|--------|--------|------|
| 模型加载时间 | ~30 秒 | ~5 秒 | **83%** |
| 全局初始化 | 每次重复执行 | 只执行一次 | **100%** |
| GPU 推理速度 | 0.3 t/s | 12 t/s | **40x** |
| 热启动恢复 | 重新加载 | 即时可用 | **即时** |
| 中文编码处理 | Modified UTF-8 (有问题) | 标准 UTF-8 | **修复** |

**详细优化内容请参阅** [CHANGELOG.md](CHANGELOG.md) 和 [LLM服务设计文档](docs/development/ai_modules/llm_service_design.md)。

## 功能概览

### 学习功能
| 功能 | 描述 |
|------|------|
| 题库管理 | 题目导入导出（多格式支持）、分类管理、智能搜索 |
| 答题系统 | 挑战模式、考试模式、练习模式、背诵模式 |
| 错题本 | 错题自动收集、分类复习、进度跟踪 |
| 学习计划 | 学习计划创建、进度跟踪、智能推荐 |
| 笔记系统 | 学习笔记创建与管理 |
| 成绩统计 | 答题成绩记录与可视化分析 |

### AI 智能功能
| 功能 | 描述 |
|------|------|
| 本地 LLM 推理 | 基于 llama.cpp，支持 Qwen2-0.5B 等模型，完整中文支持 |
| GPU 双后端 | Vulkan / OpenCL 双后端（llama.cpp），AI 服务界面可切换 |
| 本地 Agent 系统 | 状态机驱动的智能代理，支持 9 种执行状态 |
| 在线 Agent 系统 | 支持 OpenAI 兼容 API 的在线 Agent，工具调用更强大 |
| Agent 软件层 | 意图识别 → 复杂度分析 → 任务分解 → 执行引擎 → 思考链 → 结果整合 |
| AI 对话 | 自然语言交互，智能问答，支持流式输出 |
| AI 工具集 | shell_command（内置 busybox 400+ 命令、openssl/ssh/scp）、termux_exec（Termux 完整 Linux 环境）、ssh_exec（JSch 连接任意 SSH 主机）等 |
| 题目生成 | AI 根据知识点自动生成题目 |
| 题目分析 | 智能分析题目考点与解题思路 |
| 学习助手 | 个性化学习建议与辅导 |
| 翻译服务 | 多语言翻译支持 |
| 在线模型 | 支持配置 OpenAI 兼容 API 的在线模型 |
| 创意写作 | AI 辅助创意写作引擎 |

### 语音功能
| 功能 | 描述 |
|------|------|
| TTS 语音合成 | 支持系统/百度/讯飞/阿里云/火山引擎/OpenAI 多引擎 |
| ASR 语音识别 | 支持系统/百度/讯飞/阿里云/火山引擎/OpenAI 多引擎 |
| 流式语音 | 支持流式 TTS 播放，边生成边播放 |
| 语音模型管理 | 统一的语音模型注册与选择器 |

### 工具功能
| 功能 | 描述 |
|------|------|
| OCR 文字识别 | 基于 Google ML Kit，支持中日韩文字识别 |
| 文件预览 | 支持 Word/Excel/PPT/PDF 等格式预览 |
| 文件渲染 | 多格式文件渲染引擎 |
| 数据备份 | 本地数据备份与恢复 |
| 二维码扫描 | 集成 ZXing 扫码功能 |
| 语音识别 | 语音输入支持 |
| 天气查询 | 实时天气信息查询（三级回退机制） |
| 空气质量 | 空气质量指数查询 |
| 天气预警 | 灾害天气预警信息 |
| 生活指数 | 穿衣、紫外线、运动等生活指数 |

## 技术架构

### 技术栈

```
┌─────────────────────────────────────────────┐
│                   UI Layer                   │
│  Material Design 3 / Jetpack Compose        │
├─────────────────────────────────────────────┤
│               ViewModel Layer               │
│  MVVM Architecture / Hilt DI               │
├─────────────────────────────────────────────┤
│                Domain Layer                  │
│  Repository / UseCase / Model              │
├─────────────────────────────────────────────┤
│                 Data Layer                   │
│  Room DB / Retrofit / DataStore            │
├─────────────────────────────────────────────┤
│              AI Service Layer               │
│  Agent / LLM / Speech / OCR                │
├─────────────────────────────────────────────┤
│              Native Layer (C++ JNI)         │
│  llama.cpp / UTF-8 编码处理 / Vulkan GPU    │
└─────────────────────────────────────────────┘
```

### 主要依赖

| 类别 | 技术 | 用途 |
|------|------|------|
| UI | Jetpack Compose, Material Design 3 | 现代化 UI 构建 |
| 架构 | MVVM, Hilt DI, Navigation | 应用架构与依赖注入 |
| 数据库 | Room | 本地数据持久化 |
| 网络 | Retrofit, OkHttp | HTTP 网络请求 |
| AI/ML | llama.cpp (JNI), TensorFlow Lite, ML Kit | 本地 AI 推理与 OCR |
| 语音 | 阿里云百炼 SDK, 百度/讯飞 SDK | TTS/ASR 语音服务 |
| Python | Chaquopy | 嵌入式 Python 运行环境 |
| 文件处理 | Apache POI, iText7, Pdfium | 多格式文件读写 |
| 天气 | 和风天气 SDK v5.2 | 天气数据服务 |
| 文档预览 | TBS SDK, Markwon | 文档渲染与预览 |
| SSH | com.github.mwiede:jsch 2.27.7 | AI ssh_exec 工具（连接任意 SSH 主机，JSch 原生密码/密钥认证） |
| Termux 环境 | 内置 Termux 0.118.3 / API / Boot APK | 一键准备向导：完整 Linux 环境（Ubuntu 容器 + proot） |

### 项目结构

```
app/
├── src/main/
│   ├── java/com/oilquiz/app/
│   │   ├── ai/           # AI 引擎与 Agent 系统
│   │   │   ├── agent/    # Agent 引擎（本地+在线+软件层）
│   │   │   ├── speech/   # 语音服务（TTS/ASR）
│   │   │   ├── chat/     # AI 聊天系统
│   │   │   ├── engine/   # 推理引擎
│   │   │   ├── service/  # AI 服务层
│   │   │   ├── model/    # 模型管理
│   │   │   ├── gpu/      # GPU 加速管理
│   │   │   └── tool/     # AI 工具集
│   │   ├── database/     # 数据库层
│   │   ├── infra/        # 基础设施层
│   │   ├── manager/      # 业务管理器
│   │   ├── repository/   # 数据仓库
│   │   ├── ui/           # UI 层
│   │   ├── util/         # 工具类
│   │   ├── viewmodel/    # ViewModel 层
│   │   ├── weather/      # 天气模块
│   │   └── webview/      # WebView 相关
│   ├── cpp/              # C++ JNI 本地代码
│   ├── python/           # Python 脚本（Chaquopy）
│   ├── res/              # Android 资源文件
│   ├── assets/           # 应用资源（WebView页面、模型等）
│   └── jniLibs/          # 预编译本地库
├── docs/                 # 项目文档
│   ├── database/         # 数据库设计文档
│   ├── development/      # 开发文档
│   └── system/           # 系统架构文档
├── build.gradle          # 构建配置
└── settings.gradle       # 项目设置
```

## 最近更新

### v2.2.0 (2026-10)
- **AI ssh_exec 工具**：JSch 纯 Java 实现，连接任意远程 SSH 主机（电脑/服务器/路由器/NAS），密码/密钥双认证，不依赖 Termux
- **edge-to-edge 全面屏适配**：小米全面屏底部小白条、Dialog 弹窗、闪烁问题全修复
- **Termux 一键准备向导化**：6 步分步检测引导（通道→存储→proot→容器→收尾），不再一股脑传脚本；内置 Termux/API/Boot APK 可选安装
- **VNC/图形界面功能整体删除**：远程桌面不稳，用户拍板移除
- **存储迁移 MediaStore**：统一 StorageWriter，解决 targetSdk 36 公共目录写入；私有目录残留清理管理
- **llama.cpp 升级 + GPU 双后端**：合并官方最新 master，Vulkan/OpenCL 双后端可切换（高通 Adreno 实测）
- **主题适配**：agent 管理界面文字边框/底色、深色模式适配、硬编码颜色清理

### v2.1.0 (2026-07-29)
- **Agent 软件层架构升级**：新增意图识别、复杂度分析、任务分解、执行引擎、思考链、结果整合六模块
- **天气系统全面重构**：三级回退机制（SDK → HTTP API → APISpace），支持空气质量、分钟降水、预警
- **天气 Banner 组件**：新增可定制的天气横幅组件
- **AI 聊天界面升级**：支持思考链可视化、工具调用消息渲染、流式内容展示
- **语音系统完善**：TTS/ASR 多引擎支持（百度/讯飞/阿里云/火山引擎/OpenAI）

### v2.0.1 (2026-05-26)
- **JNI 中文编码修复**：解决中文显示乱码问题，实现标准 UTF-8 编解码
- **主界面优化**：按钮功能调整，图标统一为 Emoji 风格
- **在线模型支持**：完善在线模型配置和管理功能

### v2.0.0 (2026-05-20)
- **模型加载性能优化**：加载时间从 30 秒降至 5 秒
- **GPU 加速**：推理速度提升 40 倍（Vulkan 后端）
- **热启动保持**：模型在内存中保持加载状态
- **Batch Size 动态计算**：根据设备内存自动调整

**详细变更请参阅** [CHANGELOG.md](CHANGELOG.md)

## 快速开始

> **详细指南请参阅：**
> - [**SETUP_GUIDE.md**](SETUP_GUIDE.md) — 环境搭建、构建、安装到设备的完整步骤
> - [**DEVELOPMENT_GUIDE.md**](DEVELOPMENT_GUIDE.md) — 架构说明、功能开发、调试、Git 协作、问题排查

### 环境要求

- **Android Studio**: Hedgehog (2023.1.1) 或更高版本
- **JDK**: 17
- **Android SDK**: API 34
- **NDK**: 26.1.10909125（如需构建本地库）
- **构建工具**: Gradle 8.13, Android Gradle Plugin 8.4.0

### 构建步骤

1. 克隆仓库
```bash
git clone https://github.com/18295071983/smartquiz.git
```

2. 使用 Android Studio 打开项目

3. 同步 Gradle 依赖

4. 构建 APK
```bash
./gradlew assembleDebug
```

### 本地 LLM 推理引擎构建（可选）

如需使用本地 AI 推理功能，需要构建 llama.cpp JNI 库：

```bash
# 在 MSYS2 环境中运行
cd src/main/cpp
bash build_llama_jni_msys2.sh
```

预编译的 `.so` 文件将输出到 `src/main/jniLibs/` 目录。

## 文档

详细文档请参阅 [docs/](docs/) 目录：

- [系统架构设计](docs/system/system_architecture.md)
- [Agent 架构设计](docs/AGENT_ARCHITECTURE.md)
- [数据库结构设计](docs/database/database_structure.md)
- [AI 功能设计](docs/development/ai_feature_design.md)
- [开发标准规范](docs/development/development_standards.md)
- [技术栈文档](docs/development/tech_stack.md)
- [测试策略](docs/development/testing_strategy.md)
- [路线图](ROADMAP.md)

## 许可证

本项目采用 MIT 许可证。详见 [LICENSE](LICENSE) 文件。
