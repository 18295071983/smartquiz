# SmartQuiz / 答题宝 — 项目长期记忆

## 用户协作约定（务必遵守）

- **不要擅自修改构建相关文件**：`CMakeLists.txt`、`build.gradle`、`gradle.properties`、`.cxx/` 等。
  用户原话："你不要给我乱改"。需要改动必须先说明理由并取得明确同意。
- **不要删除缓存目录**（如 `.cxx/`）——用户已明确拒绝过一次。
- 用户同时在 **Qoder** 等其他工具里操作同一仓库，构建可能由其他工具完成。
  遇到构建失败时，先怀疑自己的执行环境，不要急着断言"项目有问题"。
- **不要自动 git 提交/推送**。2026-09-11 用户在我准备 commit 时明确说「你不要提交」。
  默认只做只读 git 查询（status/log/diff/fetch）；`commit`/`push`/`reset` 等必须先拿到明确指令。
  （另注：工作区常有一批**用户自己在 Qoder 里先 `git add` 好的已暂存改动**，别误以为是自己或别人的。）
- 未纳入 git 的新增目录（如 `speech/core/`、`speech/tts/`）删除不可恢复，动之前必须确认。

## 构建环境（Windows）

- **必须用 PowerShell / cmd 构建原生层，不要用 Git Bash。**
  Git Bash(MSYS) 会改写 `cmd.exe /C vulkan-shaders-gen.exe ...` 的参数，
  导致 glslc 的 `-D DATA_A_*` 宏丢失，报 `mul_mat_vecq.comp: error: '#error' : unimplemented`。
  这是环境问题，不是 llama.cpp 或项目配置的问题。
- 若确实要在 Git Bash 里跑 gradlew：需先 `export PATH="/c/Program Files/Git/usr/bin:$PATH"`，
  否则报 `cygpath: command not found`。
- CMake `option()` 的默认值**不会覆盖 `.cxx` 中已缓存的值**；
  `-Pandroid.injected.cmake.arguments` 注入同样会被缓存挡住。改开关需连带处理缓存。
- 产物：`build/outputs/apk/debug/答题宝-debug-2.0.apk`（debug 包约 **762MB**）。
- PowerShell 里的坑：`cmd | Select-Object -Last N` 会让包装器报 exit 1（**不代表 Gradle 失败**）；
  `*>` 写出的日志是 **UTF-16**——用 Python `open(p,'rb').read().decode('utf-16')` 读，
  **别用 `tr -d '\000'`**（会把字节错位成乱码）。另外本机 **PowerShell 工具回显常为空**，
  把输出写文件再读（或用 `python` 直接打印）。
- **不要给 gradlew 加 `--no-daemon`**：`gradle.properties` 里 `org.gradle.daemon=true`，仓库习惯复用常驻守护进程。
  加 `--no-daemon` 会 fork 一次性守护进程，去抢已被常驻 daemon（如 Qoder 启动的那个）持有的
  `D:\Gradle\Home\caches\journal-1\journal-1.lock` → 报 `FileNotFoundException ... (拒绝访问。)`，
  `BUILD FAILED in 2~6s`。**这是环境问题不是代码问题**。排查：
  `Get-CimInstance Win32_Process -Filter "Name='java.exe'"` 看有没有 `GradleDaemon 8.13`。
- Gradle 要写工作区外的 `D:\Gradle\Home`：**沙箱会拦**，构建需用沙箱旁路执行（`dangerouslyDisableSandbox`）。

## 技术栈要点

- Android 应用 `com.oilquiz.app`，AGP 8.4.0，Java 17，minSdk 31 / targetSdk 34。
- LLM 推理：**llama.cpp**（GGUF + mmap，JNI 桥 `src/main/cpp/llama-bridge.cpp`），Vulkan 后端默认 ON。
- CV：**TFLite**（`Interpreter` + `MappedByteBuffer`）；OCR：**MLKit**。项目**未使用 MNN**。
- 语音：`SpeechManager` 门面 + `TtsEngine` 策略（OpenAiTtsEngine / DashScopeTtsEngine / SystemTtsEngine）。
  在线 ASR 走 OpenAI 兼容 `/audio/transcriptions`；在线 TTS 走 `/audio/speech` 或 DashScope 原生。
- 思考标签检测：**统一走模板，不硬编码**。native 从 GGUF 内置 chat template 提取
  `mThinkStartTag`/`mThinkEndTags`（`refreshThinkingTags` + `nativeGetThinkingTags` JNI）；
  Java 侧经 `LlamaHelper.getThinkingTags()` → `ThinkingTagConfig` 供 `OutputRouter` /
  `ChatAdapter` 等使用。chatJson 路径另发 `{"type":"meta"}` 事件。
- **Excel 选表：已彻底移除"自动选表"（2026-09-11）**。`QuestionImportTaskManager` 里的
  `pickBestSheet`/`isQuestionBankSheet`/`scoreHeaders`/`STEM_KEYWORDS`/`ANSWER_KEYWORDS` 全部删除，
  `start()` 只把 `sheetMode=all` 映射为 sheetIndex=-1。**工作表必须由智能体显式指定**：
  先 `excel_tool(action=sheets)` 或 `file_reader(parse_excel)` 看表，再传
  `index`(+sheetIndex) / `multi`(+sheetIndexes) / `all`；`sheetMode` 现为**必填**，空或 `best`/`auto` 直接失败。
  `util/OfficeParserUtil.HEADER_KEYWORDS` + `headerScore()` 仍保留，供附件预处理等其它路径使用。
- **导入不再做预处理/清洗**：`AIImportActivity.startAgentImport()` 提示词里"清洗/预处理"那一步已删除。
- **`import_start` 的 `interactive` 默认 `true`，语义已改为"智能体创建 ui_component 交互"**（2026-09-11）：
  四决策点（字段映射/数据预览/智能填充/入库）**不再弹系统 AlertDialog**，改为——
  `AgentInteractiveDecisionHandler` 把决策载荷发布到 `TaskStatus.pendingDecision` →
  `import_status` 返回 `awaitingDecision` + `pendingDecision{decisionId,type,title,message,options,payload}` →
  智能体 `ui_component(action=create, component_type=choice, title, message, options=...)` 交互
  （`choice` 是**原生弹窗**类型，独立弹出、不依赖聊天宿主）+ `get_result` 取值（**返回选项文本**）→
  `import_decide(taskId, decisionId, selected=选项文本 | choice=下标[, mapping])` 回传 → 导入线程继续。
  导入线程在决策点 `CountDownLatch` 阻塞，**超时 5 分钟或任务被取消 → 按【取消】处理（未确认不导入）**。
  `interactive=false` → `AutoImportDecisionHandler` 全自动。
  相关文件：`ai/importing/AgentInteractiveDecisionHandler.java`（新）、`ai/tool/ImportDecideTool.java`（新）、
  `QuestionImportTaskManager`（pendingDecision/submitDecision/status 暴露）。
  注意：`ImportActivity`（非智能体直连导入）仍用旧的 `InteractiveImportDecisionHandler`（弹 AlertDialog），未改。
- **多工作表导入**：`sheetMode=multi` + `sheetIndexes`（JSON 数组如 `[1,2]`）
  → `QuestionImportTaskManager.startMulti(context, file, sheetIndexes, docHint, fillMissing, skipIncomplete, questionType, interactive)`
  （8 参交互版为主；另有 7 参委托重载 `...questionType)` 默认 `interactive=false`）
  → `ImportMain.runSheets(file, sheetIndexes, null, listener)`（4 参重载）。
  `AIImportActivity.runV2ImportSheets()` 是 UI 侧同能力入口。
  ⚠️ 合并时曾出现**重复 `startMulti`（7 参同签名）**导致编译失败——若再看到两个 7 参 `startMulti` 定义，删掉那个无委托的即可。
- **知识库全文索引用 FTS4（不是 FTS5）**：部分设备内置 SQLite 未编译 FTS5，建表报
  `no such module: fts5`。2026-09-11 按用户指示改为
  `CREATE VIRTUAL TABLE kb_chunks_fts USING fts4(title, category, keywords, search_text, content, tokenize=unicode61)`。
  **FTS4 无 `bm25()`（FTS5 专属）**：查询改用 FTS4 的 `snippet(fts,'[',']','…',4,20)`（列号必填），
  排序退化为 `ORDER BY c.updated_at DESC`（相关性由可选在线语义重排承担）。
  `DB_VERSION` 升到 2：`onUpgrade` 会 DROP 旧 FTS 表 → 重建 FTS4 → `rebuildFtsIndex()` 从 `kb_chunks` 全量回填。
  文件：`ai/knowledge/KnowledgeBaseManager.java`（改前是 fts5 + `bm25()` + 可选列 snippet）。
- **`import_start` 的 `filePath` 必须是导入页选的原始文件**，不能传 agent 工作区 `tmp/` 下
  预处理器生成的清洗文件（每轮 execute 结束 `clearTmp` 会删掉，下一轮对话就失效）。
  工具侧已有防护；`AIImportActivity.createTempFileFromUri()` 目前仍写**私有 cache**
  （`File.createTempFile(..., getCacheDir())`），未被系统清理就会一直在，属已知弱点。
- 附件预解析注入文案 `h_fe85860b` 分布在 4 处资源（app 的 `values/`、`values-en/`、`values-zh-rTW/`
  的 `strings_migrated.xml`，以及 `chatkit/src/res/values/strings.xml`），改文案要同步 4 处
  + `tools/i18n_migrate/translations_*.tsv`。

## AI 导入：远端 14 提交已恢复到本地（2026-09-11 17:13 更新）

- 用户 16:41 曾把 `main` 硬回退到 `0591fb7` 丢弃 14 个提交；17:13 又要求"将 git 最新改动恢复到本地，保留我刚才的修改"。
- 操作：`git fetch` + `git merge --ff-only FETCH_HEAD`（`main` 0591fb7 是 145826a 的严格祖先，干净快进），
  再 `git stash apply`（我此前未提交的改动）叠加，手动解决 3 处冲突。
- 现在 **`main = 145826a`**（= 备份分支 `backup-ai-import-20260911-1641` / tag `ai-import-backup-20260911-1641`）。
- 那 14 个提交里**现在本地都有了**，含此前被刻意回退的「四决策点弹窗 + 强制交互确认」线
  （`0e43773`/`df6ad45`/`b1c6874`/`6b3afec`/`145826a`）。`interactive` 默认 true 即来自此线。
- 我叠加保留的改动（在远端线之上）：`sheetMode` 默认 `best`、`import_start` 的 tmp 文件防护、
  `buildStartResult` 的 `sheetSelection` 返回、把 `startMulti` 重复定义合并掉、`pickBestSheet` 净分选表。
- 14 个提交全量清单：`git log --oneline 0591fb7..145826a`（即原来 `main..FETCH_HEAD` 那段）。

## 待办 / 已知问题

- **缺陷 D（未修）**：python `import_preprocessor.py:_iter_xlsx` 在 `sheet_index=None`（自动扫表）
  时，以**首个有表头的 sheet** 为基准做「列数+列名」一致性校验，不一致的 sheet **整表跳过**。
  若首表是示例/模板表（列名略有差异），真题库表会被静默丢弃 → 只导入首表几行。
  现在 `import_start.sheetMode` 必须显式指定：传 `index`/`multi` 可绕开；**传 `all` 仍会踩到**。
  这是上游 python 的设计取舍，要修需改 `_iter_xlsx` 的"表头一致性跳过"逻辑。

- `src/main/cpp/llama-bridge.cpp:98-103`：`n_batch` 被钳制到 `MAX_CONTEXT_BATCH = 8`，
  严重拖慢 prefill 速度。**已发现未修复**，需用户确认后再动。
- P0 安全：`NetworkSearchTool.java` 硬编码 METASO key；`build.gradle` 明文签名密码。未处理。
- 语音修复（SSL 宽松回退 + SystemTtsEngine 并发/回调）已进包，**真机效果尚未验证**。
