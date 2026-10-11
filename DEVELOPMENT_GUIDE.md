# 答题宝 (SmartQuiz) 开发指南

## 目录

- [一、项目结构速览](#一项目结构速览)
- [二、核心架构](#二核心架构)
- [三、构建命令速查](#三构建命令速查)
- [四、添加新功能](#四添加新功能)
- [五、关键配置文件](#五关键配置文件)
- [六、调试指南](#六调试指南)
- [七、测试](#七测试)
- [八、Git 协作流程](#八git-协作流程)
- [九、代码规范](#九代码规范)
- [十、常见问题排查](#十常见问题排查)

---

## 一、项目结构速览

```
smartquiz/
├── build.gradle                  # 构建配置（AGP 8.9.2, compileSdk 36, JDK 21）
├── settings.gradle               # 项目设置
├── gradle.properties             # Gradle 属性
├── gradlew / gradlew.bat         # Gradle Wrapper（无需安装 Gradle）
│
├── src/main/
│   ├── AndroidManifest.xml       # 应用清单
│   │
│   ├── java/com/oilquiz/app/
│   │   ├── ai/                   # ★ AI 核心模块
│   │   │   ├── agent/            # Agent 引擎（本地+在线+软件层）
│   │   │   ├── speech/           # 语音服务（TTS/ASR 多引擎）
│   │   │   ├── chat/             # AI 聊天系统
│   │   │   ├── engine/           # 推理引擎
│   │   │   ├── service/          # AI 服务层
│   │   │   ├── model/            # 模型管理
│   │   │   ├── gpu/              # GPU 加速管理
│   │   │   └── tool/             # AI 工具集
│   │   ├── database/             # Room 数据库层（DAO + Database）
│   │   ├── manager/              # 业务管理器（备份、OCR、语音、主题）
│   │   ├── model/                # 数据模型（Question, Note, Score 等）
│   │   ├── repository/           # 数据仓库层
│   │   ├── ui/                   # UI 层（Activity、Adapter、Widget）
│   │   ├── util/                 # 工具类（导出、解析、预览、渲染）
│   │   ├── viewmodel/            # MVVM ViewModel 层
│   │   ├── weather/              # 天气模块
│   │   ├── webview/              # WebView 管理（JS 桥接、安全）
│   │   ├── resource/             # 资源管理器
│   │   ├── infra/                # 基础设施（日志、网络、安全）
│   │   └── di/                   # Hilt 依赖注入
│   │
│   ├── cpp/                      # ★ C++ 本地代码（llama.cpp JNI）
│   │   ├── CMakeLists.txt        # CMake 构建配置
│   │   ├── native-lib.cpp        # JNI 入口（含 setBackend 等）
│   │   ├── llama-bridge.cpp      # llama.cpp 封装
│   │   ├── agent_kv_cache.cpp/h  # Agent 的 KV 缓存管理
│   │   ├── llama.cpp/            # llama.cpp 上游源码（.gitignore 排除，需自行获取）★
│   │   ├── opencl/ spirv-headers/ cmake/  # CMake 依赖（同样被排除）★
│   │   └── build_*.sh/bat        # 手动构建脚本（内含本机绝对路径，需先修正）
│   │
│   ├── res/                      # Android 资源
│   │   ├── layout/               # XML 布局文件（110+ 个）
│   │   ├── drawable/             # 矢量图标与背景（130+ 个）
│   │   ├── values/               # 字符串/颜色/样式/尺寸
│   │   ├── menu/                 # 菜单定义
│   │   └── mipmap-*/             # 应用图标
│   │
│   └── assets/                   # 应用内资源
│       ├── pages/                # WebView 页面（HTML/JS/CSS）
│       ├── templates/            # 导出模板（JSON 格式）
│       └── models/               # AI 模型配置
│
├── src/test/                     # 单元测试
│
├── docs/                         # 设计文档
│   ├── system/                   # 系统架构、API、部署
│   ├── development/              # 功能设计、标准、技术栈
│   ├── database/                 # 数据库结构
│   └── AGENT_ARCHITECTURE.md     # Agent 架构设计
│
├── SETUP_GUIDE.md                # 环境搭建指南
├── DEVELOPMENT_GUIDE.md          # 本文档
└── README.md                     # 项目介绍
```

### 关键代码入口

| 入口文件 | 作用 |
|----------|------|
| [App.java](src/main/java/com/oilquiz/app/App.java) | Application 入口 |
| [MainActivity.java](src/main/java/com/oilquiz/app/MainActivity.java) | 主界面 |
| [WebViewActivity.java](src/main/java/com/oilquiz/app/WebViewActivity.java) | WebView 容器 |
| [UnifiedAgentEngine.java](src/main/java/com/oilquiz/app/ai/agent/UnifiedAgentEngine.java) | 统一 Agent 引擎 |
| [AgentRouter.java](src/main/java/com/oilquiz/app/ai/agent/AgentRouter.java) | Agent 路由（本地/在线） |
| [AgentSoftwareLayer.java](src/main/java/com/oilquiz/app/ai/agent/software/AgentSoftwareLayer.java) | Agent 软件层（思考链） |
| [AIService.java](src/main/java/com/oilquiz/app/ai/service/AIService.java) | AI 服务接口 |
| [SpeechManager.java](src/main/java/com/oilquiz/app/ai/speech/SpeechManager.java) | 语音服务管理器 |
| [AIWeatherManager.java](src/main/java/com/oilquiz/app/ai/tool/AIWeatherManager.java) | 天气工具管理器 |
| [AIToolManager.java](src/main/java/com/oilquiz/app/ai/tool/AIToolManager.java) | AI 工具管理器 |

---

## 二、核心架构

```
┌────────────────────────────────────────────┐
│                Activity Layer               │  UI 交互
├────────────────────────────────────────────┤
│              ViewModel Layer                │  状态管理
├────────────────────────────────────────────┤
│              Repository Layer               │  数据访问
├──────────────────┬─────────────────────────┤
│    Room DB       │    AI Engine (JNI)       │  数据层
│  (用户/题目/成绩) │  (llama.cpp + 推理引擎)   │
└──────────────────┴─────────────────────────┘
```

### AI 子系统架构

```
用户输入 → AgentRouter → ┌─ 本地 Agent → UnifiedAgentEngine → llama.cpp (JNI)
                         │                ↓
                         │           ToolManager (本地工具)
                         │
                         └─ 在线 Agent → OpenAI 兼容 API → 在线工具
                                ↓
                          AgentSoftwareLayer（软件层）
                         ┌───────────────────────────┐
                         │ 意图识别 → 复杂度分析      │
                         │     ↓                     │
                         │ 任务分解 → 执行引擎        │
                         │     ↓                     │
                         │ 思考链 → 结果整合          │
                         └───────────────────────────┘
                                ↓
                          SpeechManager (TTS/ASR)
```

### 数据库核心表

| 表名 | 对应 DAO | 说明 |
|------|----------|------|
| questions | QuestionDao | 题库 |
| notes | NoteDao | 笔记 |
| wrong_questions | WrongQuestionDao | 错题本 |
| scores | ScoreDao | 成绩 |
| study_plans | StudyPlanDao | 学习计划 |
| users | UserDao | 用户信息 |
| chat_history | ChatHistoryDao | AI 对话历史 |
| templates | TemplateDao | 导出模板 |

---

## 三、构建命令速查

### Gradle Wrapper 命令

```bash
# 清理构建
./gradlew clean

# 编译 Debug APK
./gradlew assembleDebug

# 编译 Release APK
./gradlew assembleRelease

# 同步依赖
./gradlew --refresh-dependencies

# 查看所有任务
./gradlew tasks

# 查看依赖树
./gradlew app:dependencies

# 运行单元测试
./gradlew test

# 运行单个测试类
./gradlew test --tests "com.oilquiz.app.ExampleTest"
```

### 根目录构建脚本

> **现状**：根目录**已无构建脚本**（历史版本曾有一批 `build_apk*.bat` / `build_*.sh`，
> 已在项目目录整理中移除）。构建统一走 Gradle Wrapper；`src/main/cpp/` 下另有若干
> **手动构建 native 库**的脚本，但**它们内部写着本机绝对路径**（例如
> `build_arm64_full.sh` 里的 `SRC_DIR=/d/quzp/app/...`，连本项目路径都不是），
> 使用前需自行修正。

日常开发用下面的 Gradle 命令即可。

### 常用 Gradle 命令

```bash
./gradlew clean                       # 清理构建产物
./gradlew assembleDebug               # 完整构建（含 native）
./gradlew compileDebugJavaWithJavac   # 仅编译 Java —— 最快，验证代码改动首选
./gradlew installDebug                # 构建并安装到已连接设备
./gradlew --stop                      # 停止 daemon（排查配置缓存导致的假象时必用）
```

> **排查提示**：怀疑"某个文件缺失导致构建失败"时，**必须先 `--stop` 清掉 daemon 再复测** ——
> 否则 Gradle 会复用上一次的配置状态，可能给出"缺文件也能构建"的假象。
>
> 环境前提（JDK/SDK/NDK、`local.properties`、被忽略的源码目录、大二进制）见
> [SETUP_GUIDE.md](SETUP_GUIDE.md)。

---

## 四、添加新功能

### 示例：添加一个新的 Activity

**第 1 步：创建 Activity 类**

在 `src/main/java/com/oilquiz/app/ui/activity/` 下创建：

```java
package com.oilquiz.app.ui.activity;

import com.oilquiz.app.ui.base.BaseActivity;

public class NewFeatureActivity extends BaseActivity {
    @Override
    protected int getLayoutResId() {
        return R.layout.activity_new_feature;
    }

    @Override
    protected void initViews() {
        // 初始化 UI
    }

    @Override
    protected void initData() {
        // 加载数据
    }
}
```

**第 2 步：创建布局文件**

在 `src/main/res/layout/` 下创建 `activity_new_feature.xml`。

**第 3 步：在 AndroidManifest.xml 中注册**

```xml
<activity
    android:name=".ui.activity.NewFeatureActivity"
    android:label="新功能"
    android:exported="false" />
```

**第 4 步：添加导航（如需）**

修改相应导航代码，添加跳转逻辑。

**第 5 步：构建验证**

```bash
./gradlew assembleDebug
```

### 示例：添加一个新的数据库表

**第 1 步：创建 Model 类** → `src/main/java/com/oilquiz/app/model/`

```java
@Entity(tableName = "new_table")
public class NewEntity {
    @PrimaryKey(autoGenerate = true)
    private long id;
    // ... 字段和 getter/setter
}
```

**第 2 步：创建 DAO 接口** → `src/main/java/com/oilquiz/app/database/`

```java
@Dao
public interface NewEntityDao {
    @Insert
    long insert(NewEntity entity);

    @Query("SELECT * FROM new_table")
    List<NewEntity> getAll();
}
```

**第 3 步：在 AppDatabase 中注册**

```java
@Database(entities = {..., NewEntity.class}, version = X)
public abstract class AppDatabase extends RoomDatabase {
    public abstract NewEntityDao newEntityDao();
}
```

**第 4 步：创建 Repository** → `src/main/java/com/oilquiz/app/repository/`

**第 5 步：创建 ViewModel** → `src/main/java/com/oilquiz/app/viewmodel/`

### 示例：添加一个新的 AI 工具

在 `src/main/java/com/oilquiz/app/ai/tool/` 下：

```java
public class MyNewTool extends BaseAITool {
    @Override
    public String getName() { return "my_tool"; }

    @Override
    public String getDescription() { return "工具描述"; }

    @Override
    public AIToolResult execute(Map<String, Object> params) {
        // 执行逻辑
        return new AIToolResult(true, "执行结果");
    }
}
```

然后在 `AIToolManager` 中注册新工具。

---

## 五、关键配置文件

| 文件 | 关键配置 |
|------|----------|
| `build.gradle` | compileSdk/targetSdk 36, minSdk 31, JDK 21, AGP 8.9.2, NDK 26.1.10909125, abiFilters arm64-v8a |
| `settings.gradle` | rootProject.name = "smartquiz"；仓库源（含阿里云镜像） |
| `gradle.properties` | Gradle JVM 参数、`org.gradle.java.home`、`GRADLE_USER_HOME` |
| `local.properties` | `sdk.dir`（**未入库，需自建**） |
| `proguard-rules.pro` | 主 ProGuard 混淆规则 |
| `proguard-poi-rules.pro` | Apache POI 专用 ProGuard 规则 |
| `src/main/cpp/CMakeLists.txt` | C++ 编译配置（OpenCL/Vulkan/CUDA 开关） |
| `src/main/res/xml/network_security_config.xml` | 网络安全配置 |
| `src/main/res/xml/backup_rules.xml` | 自动备份规则 |

### 修改版本号

在 `build.gradle` 中：

```groovy
defaultConfig {
    versionCode 2          // 每次发布 +1
    versionName "2.0"      // 语义化版本
}
```

> 注：APK 输出文件名由 `build.gradle` 里的 `outputFileName` 模板拼装，形如
> `答题宝-debug-2.0.apk`；改 `versionName` 会同步反映到文件名。

---

## 六、调试指南

### Logcat 日志过滤

在 Android Studio 的 Logcat 中使用以下过滤器：

```
tag:SmartQuiz      # 应用主日志
tag:AIAgent         # AI Agent 日志
tag:Inference       # 推理引擎日志
tag:JNI             # JNI 调用日志
tag:ToolManager     # 工具调用日志
```

### 调试本地 C++ 代码

1. 在 `Run` → `Edit Configurations` → `Debugger` 中
2. 将 **Debug type** 设为 **Dual (Java + Native)**
3. 在 C++ 文件中设置断点即可调试

### 查看数据库

使用 Android Studio 的 **App Inspection** → **Database Inspector**：

1. 运行应用
2. View → Tool Windows → App Inspection
3. 选择 Database Inspector 选项卡
4. 查看和修改 Room 数据库内容

### WebView 页面调试

1. 在应用中打开 WebView 页面
2. Chrome 浏览器访问 `chrome://inspect`
3. 找到远程设备中的 WebView，点击 **inspect**

---

## 七、测试

### 运行所有测试

```bash
./gradlew test
```

### 运行特定模块测试

```bash
./gradlew test --tests "com.oilquiz.app.ai.*"
./gradlew test --tests "com.oilquiz.app.viewmodel.*"
./gradlew test --tests "com.oilquiz.app.webview.*"
```

### 测试文件位置

所有测试位于 `src/test/java/com/oilquiz/app/`，按模块组织。

### 测试框架

- **JUnit 4** — 单元测试
- **Mockito** — Mock 框架
- **Robolectric** — Android 本地测试
- **Espresso** — UI 测试（需在 `androidTest` 目录）

---

## 八、Git 协作流程

### 日常开发流程

```bash
# 1. 开始工作前
git pull origin main

# 2. 创建功能分支（推荐）
git checkout -b feature/新功能名

# 3. 开发并提交
git add -A
git commit -m "feat: 功能描述"

# 4. 推送分支
git push -u origin feature/新功能名

# 5. 合并到 main
git checkout main
git pull origin main          # 先拉取最新
git merge feature/新功能名     # 合并
git push origin main          # 推送
```

### Commit 规范

| 前缀 | 含义 | 示例 |
|------|------|------|
| `feat:` | 新功能 | `feat: 添加语音识别` |
| `fix:` | Bug 修复 | `fix: 修复题库导入崩溃` |
| `docs:` | 文档 | `docs: 更新部署指南` |
| `refactor:` | 重构 | `refactor: 优化推理引擎` |
| `test:` | 测试 | `test: 添加导出模块测试` |
| `chore:` | 杂项 | `chore: 更新 .gitignore` |
| `style:` | 代码格式 | `style: 格式化代码` |

### 回退操作

```bash
# 撤销未提交的修改
git checkout -- 文件名

# 撤销 git add
git reset HEAD 文件名

# 撤销最后一次 commit（保留修改）
git reset --soft HEAD~1

# 丢弃本地修改，强制同步远程
git fetch origin
git reset --hard origin/main
```

### 分支管理

```bash
# 查看所有分支
git branch -a

# 删除本地分支
git branch -d feature/旧功能

# 删除远程分支
git push origin --delete feature/旧功能
```

---

## 九、代码规范

### Java

- **包名**：全小写，`com.oilquiz.app.模块名`
- **类名**：大驼峰 `QuestionActivity`
- **方法名**：小驼峰 `loadQuestions()`
- **常量**：全大写蛇形 `MAX_QUESTION_COUNT`
- **编码**：UTF-8（无 BOM）

### 架构规范

- Activity 继承 `BaseActivity`
- Fragment 继承 `BaseFragment`
- 每个功能模块至少包含：Model → DAO → Repository → ViewModel → Activity
- AI 相关代码集中在 `ai/` 包下
- 工具类放在 `util/` 包下

### 资源命名

- **布局**：`activity_功能名.xml` 或 `item_功能名.xml`
- **图标**：`ic_功能名.xml`
- **背景**：`bg_描述.xml` 或 `rounded_描述.xml`

---

## 十、常见问题排查

### 编译问题

**Q：提示 "SDK location not found"**
- 创建 `local.properties` 文件，添加：
```
sdk.dir=D\:\\Android\\Sdk
```

**Q：报 `specify compileSdk in build.gradle`，但 `compileSdk 36` 明明已经配了**
- ⚠️ **这是误导性报错**。真正原因通常是 **缺 `viewer/src/main/jniLibs/arm64-v8a/liblo-native-code.so`**
  （171 MB，未入库）。`abiFilters "arm64-v8a"` 在缺该库时会搞崩配置阶段，
  错误被 Kotlin 插件包装后指向了无关的 `compileSdk`。
- 不要为此改 `compileSdk` 或换 AGP 版本 —— 方向是错的。
- `build.gradle` 顶部的 `BINARY-PRECHECK` 会在报错中**先**列出真正缺失的文件，
  以那段为准；详见 [BINARY_ASSETS.md](BINARY_ASSETS.md)。

**Q：构建报"缺少必要的二进制资源"**
- 按提示放置缺的文件。两个大文件：
  `src/main/assets/asr/model.int8.onnx`（用 `scripts/fetch-asr-model.ps1` 下载）、
  `liblo-native-code.so`（从 Release 附件下载）。清单见 [BINARY_ASSETS.md](BINARY_ASSETS.md)。

**Q：提示 NDK 找不到**
- 用 SDK Manager 安装 NDK 26.1.10909125
- 或在 `build.gradle` 中注释掉 NDK 相关配置（如果不需要本地库）

**Q：依赖下载失败**
```bash
./gradlew --refresh-dependencies
```

**Q：CMake 报 `add_subdirectory` 找不到 llama.cpp**
- `src/main/cpp/llama.cpp/` 等源码目录**被 .gitignore 排除、不在版本库中**，
  新克隆必然缺少。获取方式见 [SETUP_GUIDE.md](SETUP_GUIDE.md) 第 4 节。

**Q：CMake 编译失败（Windows）**
- 确保 MSYS2 环境已正确安装
- 检查 `src/main/cpp/CMakeLists.txt` 中的路径配置
- 检查 `build.gradle` 里写死的 ninja 路径是否指向你的 SDK

**Q：multidex 相关错误**
- minSdk 31 默认支持 multidex，如果报错检查 `multiDexEnabled true`

> **通用排查原则**：怀疑"缺文件导致失败"时，**先 `./gradlew --stop` 清 daemon 再复测**。
> Gradle 会复用上一次的配置状态，否则可能得到"缺文件也能构建"的**假象**（本项目已踩过）。

### 运行时问题

**Q：应用闪退**
- 检查 Logcat 日志，搜索 "FATAL EXCEPTION"
- 常见原因：空指针、数据库版本不匹配、权限缺失

**Q：AI 功能不工作**
- 确认模型文件已就位（本地 LLM 的 GGUF 由应用内下载管理；离线 ASR 模型见
  [BINARY_ASSETS.md](BINARY_ASSETS.md)）
- 确认 JNI 库已构建到 `src/main/jniLibs/arm64-v8a/`（`libllama-jni.so` 为 CMake 产物，
  需 `src/main/cpp/llama.cpp/` 源码就位后由 `assembleDebug` 生成）
- 检查 AI 服务是否已启动

**Q：数据库升级后崩溃**
- 检查 `AppDatabase` 中的 `Migration` 是否正确
- 或者临时卸载应用重新安装

**Q：WebView 页面白屏**
- 检查 `network_security_config.xml` 中是否允许 HTTP
- 确认 assets 目录中包含所需的 HTML 文件

### Git 问题

**Q：推送被拒绝**
```bash
git pull origin main --rebase
git push origin main
```

**Q：合并冲突**
- 手动解决冲突文件中的 `<<<<<<<` 和 `>>>>>>>`
- 然后 `git add . && git commit`

---

## 参考文档

- [Android 官方开发文档](https://developer.android.com/docs)
- [Room 数据库指南](https://developer.android.com/training/data-storage/room)
- [Hilt 依赖注入](https://developer.android.com/training/dependency-injection/hilt-android)
- [Jetpack Compose](https://developer.android.com/training/jetpack/compose)
- [llama.cpp 项目](https://github.com/ggerganov/llama.cpp)
- 本仓库其它文档：[SETUP_GUIDE.md](SETUP_GUIDE.md)（纯净环境构建）、
  [BINARY_ASSETS.md](BINARY_ASSETS.md)（无法入库的大二进制）、
  [CHANGELOG.md](CHANGELOG.md)（变更历史）、[ROADMAP.md](ROADMAP.md)（规划）