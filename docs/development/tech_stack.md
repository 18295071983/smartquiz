# 技术栈文档

> 版本: 2.1 | 更新日期: 2026-05-20 | 构建: Gradle 8.13 + AGP 8.4.0

## 一、编程语言

| 语言 | 版本 | 用途 |
|------|------|------|
| **Java** | 17 | 主要业务逻辑（~95% 代码） |
| **Kotlin** | 1.8.22 | Compose UI 组件，扩展函数 |
| **C/C++** | C++17 | AI 推理引擎 (llama.cpp JNI) |
| **HTML/JS/CSS** | - | WebView 混合界面 |

## 二、Android 平台

| 组件 | 版本 |
|------|------|
| **compileSdk** | 34 |
| **minSdk** | 31 |
| **targetSdk** | 34 |
| **Build Tools** | 34.0.0 |
| **NDK** | 26.1.10909125（可选） |

## 三、构建工具

| 工具 | 版本 | 说明 |
|------|------|------|
| **Gradle** | 8.13 | 构建系统 |
| **AGP** (Android Gradle Plugin) | 8.4.0 | Android 构建插件 |
| **CMake** | 3.22.1 | C++ 本地库构建（可选） |
| **ProGuard** | 内置 | 代码混淆与优化 |

## 四、UI 框架

| 技术 | 版本 | 用途 |
|------|------|------|
| **Jetpack Compose** | BOM 2024.02.02 | 声明式 UI |
| **Compose Material 3** | BOM 管理 | Material Design 3 |
| **Material Icons Extended** | BOM 管理 | 扩展图标集 |
| **Activity Compose** | 1.8.2 | Compose Activity 集成 |
| **Compose Navigation** | 2.7.7 | Compose 导航 |
| **ViewBinding** | AGP 内置 | XML Layout 绑定 |
| **AppCompat** | 1.7.0 | 向后兼容支持 |
| **ConstraintLayout** | 2.1.4 | 约束布局 |
| **RecyclerView** | 1.3.2 | 列表视图 |
| **Flexbox** | 3.0.0 | 弹性盒布局 |
| **CardView** | 1.0.0 | 卡片视图 |

## 五、生命周期与架构

| 技术 | 版本 | 用途 |
|------|------|------|
| **Lifecycle** | 2.7.0 | 生命周期管理 |
| **ViewModel** | 2.7.0 | MVVM ViewModel |
| **LiveData** | 2.7.0 | 可观察数据 |
| **Lifecycle Compose** | 2.7.0 | Compose 生命周期 |
| **Hilt (Dagger)** | 2.48 | 依赖注入 |
| **Room** | 2.5.2 | 本地数据库 (v20) |
| **DataStore** | 内置 | 键值存储 |
| **WorkManager** | 2.9.0 | 后台任务 |

## 六、网络

| 技术 | 版本 | 用途 |
|------|------|------|
| **OkHttp** | 4.12.0 | HTTP 客户端 |
| **OkHttp Logging** | 4.12.0 | HTTP 日志拦截器 |
| **Okio** | 3.6.0 | IO 工具库 |
| **Retrofit** | 2.9.0 | REST API 封装 |
| **Gson** | 2.10.1 | JSON 序列化 |
| **Jsoup** | 1.17.2 | HTML 解析 |

## 七、AI / 机器学习

| 技术 | 版本 | 用途 |
|------|------|------|
| **llama.cpp** | (C++ JNI) | 本地 LLM 推理 |
| **TensorFlow Lite** | 2.14.0 | 轻量级 ML 推理 |
| **TFLite GPU** | 2.14.0 | GPU 加速推理 |
| **TFLite Support** | 0.4.4 | TFLite 辅助库 |
| **TFLite Task Text** | 0.4.4 | 文本任务支持 |
| **Google ML Kit** | 16.0.0 | OCR 文字识别 |
| **ML Kit Chinese** | 16.0.0 | 中文 OCR |
| **ML Kit Japanese** | 16.0.0 | 日文 OCR |
| **ML Kit Korean** | 16.0.0 | 韩文 OCR |
| **Easy Rules** | 4.1.0 | 规则引擎 |
| **OpenCL** | (系统库) | GPU 并行计算加速 |
| **Atomic (原子操作)** | (C++ 标准库) | 多线程环境下的线程安全控制 |

### 7.1 GPU 加速技术栈说明

- **OpenCL**：用于移动设备上的通用并行计算框架，支持 Qualcomm Adreno、Mali 等 GPU 加速
- **Atomic**：C++ 标准库提供的原子操作，确保全局初始化只执行一次，避免重复初始化

## 八、文件处理

| 技术 | 版本 | 用途 |
|------|------|------|
| **Apache POI** | 5.2.3 | Excel/Word 读写 |
| **iText7 Core** | 7.2.3 | PDF 生成与处理 |
| **Pdfium** | 1.9.0 | PDF 渲染 (Google Pdfium) |
| **TBS SDK** | 1.0.5 | 腾讯文件预览 (Word/Excel/PPT/PDF) |
| **Commons IO** | 2.11.0 | IO 工具 |
| **Commons Compress** | 1.23.0 | 压缩/解压 |
| **Commons Collections4** | 4.4 | 集合工具 |

## 九、图像与媒体

| 技术 | 版本 | 用途 |
|------|------|------|
| **Glide** | 4.16.0 | 图片加载 |
| **Coil** | 2.6.0 | Compose 图片加载 |
| **Lottie** | 6.4.0 | 动画 |
| **PhotoView** | 2.3.0 | 图片缩放查看 |
| **CircleImageView** | 3.1.0 | 圆形头像 |
| **CameraX** | 1.3.1 | 相机拍照 |
| **ExifInterface** | 1.3.7 | 照片 EXIF 信息 |

## 十、文本与标记

| 技术 | 版本 | 用途 |
|------|------|------|
| **Markwon** | 4.6.2 | Markdown 渲染 |
| **ZXing** | 4.3.0 | 二维码扫描 |
| **Guava** | 32.1.2 | 实用工具集 |

## 十一、安全

| 技术 | 版本 | 用途 |
|------|------|------|
| **Security Crypto** | 1.1.0-alpha05 | 加密存储 |
| **Bouncy Castle** | 1.76 | X.509 证书 / 密码学 |
| **ProGuard** | AGP 内置 | 代码混淆 |

## 十二、其他

| 技术 | 版本 | 用途 |
|------|------|------|
| **JGit** | 6.7.0 | Git 操作（备份功能） |
| **Dexter** | 6.2.3 | 权限请求简化 |
| **APK Parser** | 2.6.10 | APK 元数据解析 |
| **Coroutines** | 1.7.3 | Kotlin 协程 |
| **Core KTX** | 1.9.0 | Kotlin 扩展 |
| **Collection** | 1.2.0 | ArrayMap/ArraySet 支持 |
| **Legacy Support** | 1.0.0 | 兼容旧 support 库 |
| **MultiDex** | 2.0.1 | 方法数超过 64K |

## 十三、测试

| 技术 | 版本 | 用途 |
|------|------|------|
| **JUnit** | 4.13.2 | 单元测试 |
| **Mockito** | 4.8.1 | Mock 框架 |
| **Robolectric** | 4.10.3 | Android 本地测试 |
| **AndroidX Test** | 1.5.0 | Android 测试工具 |
| **AndroidX JUnit** | 1.1.5 | Android JUnit 扩展 |
| **Mockito Android** | 4.8.1 | Android Mock 支持 |

## 十四、数据库迁移历史

| 迁移 | 变更 |
|------|------|
| v18 → v19 | 新增 `ocr_history`、`question_images`、`ai_usage_log` 表 |
| v19 → v20 | `questions` 表新增 18 个字段（createdAt, updatedAt, source, tags, points, timeLimit, hint, analysis, knowledgePoint, subCategory, usageCount, correctCount, incorrectCount, lastUsedAt, status, isPublic, author, comment）；新增 4 个索引 |
| 当前 | v20 |

---

## 相关文档

- [系统架构设计](../system/system_architecture.md)
- [Agent 架构设计](../AGENT_ARCHITECTURE.md)
- [数据库结构设计](../database/database_structure.md)
- [模块功能设计](module_function_design.md)
