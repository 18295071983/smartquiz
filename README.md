# 答题宝 (SmartQuiz) - 智能学习助手

> 集成本地大语言模型推理与 Agent 智能代理的 Android 综合学习平台

[![Version](https://img.shields.io/badge/Version-2.0.0-brightgreen)](CHANGELOG.md)
[![Android](https://img.shields.io/badge/Android-12%2B-brightgreen)](https://developer.android.com)
[![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.org/projects/jdk/17/)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

## 简介

答题宝是一款功能丰富的 Android 学习应用，集成了**本地大语言模型（LLM）推理引擎**和 **Agent 智能代理系统**，为用户提供智能化的学习体验。从题库管理、智能答题、AI 解题分析到多格式文件处理，答题宝致力于成为一站式的学习工具平台。

### 核心亮点

- **完全离线 AI 推理** - 基于 llama.cpp 的 C++ JNI 推理引擎，无需网络即可运行本地 LLM
- **Agent 智能代理** - 完整的状态机、三层记忆架构、任务分解与工具调用系统
- **多模式推理** - 支持直接回答、思维链（Chain-of-Thought）、ReAct、计划执行等多种推理模式
- **多格式文件支持** - 支持 Excel、Word、PDF、CSV、JSON、Markdown 等格式的导入导出与预览

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
| 本地 LLM 推理 | 基于 llama.cpp，支持 Qwen2-0.5B 等模型 |
| Agent 系统 | 状态机驱动的智能代理，支持 9 种执行状态 |
| AI 对话 | 自然语言交互，智能问答 |
| 题目生成 | AI 根据知识点自动生成题目 |
| 题目分析 | 智能分析题目考点与解题思路 |
| 学习助手 | 个性化学习建议与辅导 |
| 翻译服务 | 多语言翻译支持 |

### 工具功能
| 功能 | 描述 |
|------|------|
| OCR 文字识别 | 基于 Google ML Kit，支持中日韩文字识别 |
| 文件预览 | 支持 Word/Excel/PPT/PDF 等格式预览 |
| 文件渲染 | 多格式文件渲染引擎 |
| 数据备份 | 本地数据备份与恢复 |
| 二维码扫描 | 集成 ZXing 扫码功能 |
| 语音识别 | 语音输入支持 |
| 天气查询 | 实时天气信息查询 |

## 技术架构

### 技术栈

```
┌─────────────────────────────────────────────┐
│                   UI Layer                   │
│  Jetpack Compose / Material Design 3        │
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
│              Native Layer (C++ JNI)         │
│  llama.cpp / Agent Inference Engine        │
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
| 文件处理 | Apache POI, iText7, Pdfium | 多格式文件读写 |
| 文档预览 | TBS SDK, Markwon | 文档渲染与预览 |

### 项目结构

```
app/
├── src/main/
│   ├── java/com/oilquiz/app/
│   │   ├── ai/           # AI 引擎与 Agent 系统
│   │   ├── database/     # 数据库层
│   │   ├── infra/        # 基础设施层
│   │   ├── manager/      # 业务管理器
│   │   ├── repository/   # 数据仓库
│   │   ├── ui/           # UI 层
│   │   ├── util/         # 工具类
│   │   └── viewmodel/    # ViewModel 层
│   ├── cpp/              # C++ JNI 本地代码
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

## 许可证

本项目采用 MIT 许可证。详见 [LICENSE](LICENSE) 文件。