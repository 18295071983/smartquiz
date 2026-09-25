# 变更日志

## [2026-09-25] 新增 remote_dsh 工具（手机远程控制电脑，DeepSeek dsh 桥接）
背景：电脑装有 @deepseek-ai/dsh（DeepSeek Harness Shell，headless 模式可被程序调用），用户希望手机端答题宝 App 能远程控制电脑。
改动：
1. 电脑端 tools/dsh_bridge_server.py：Python 标准库 HTTP 桥接服务（无依赖），Bearer token 鉴权；/health 免鉴权存活检查、/status 桥接+dsh 探测、/run 执行 dsh headless 任务（超时可控）；Windows 下 dsh.cmd 经 cmd /c 执行；自动生成随机 token 并打印。
2. App 端 RemoteDshTool（remote_dsh）：run(执行任务)/get_status(检查状态)/set_config(配置 base_url+token)；未配置/401 明确报错；输出 2 万字符截断；base_url 仅允许 http/https。
3. 注册：AIToolManager registerToolFactory + OnlineToolManager 意图（远程控制电脑/dsh/电脑操作等）。
4. 文档同步：AgentWorkspace 两模板（使用速查表/核心工具速查）、项目根 使用速查表.md、docs/AI工具功能清单.md 新增 remote_dsh 条目。
- 验证：dsh headless 实测 3.2s 返回；桥接服务端到端（health/401鉴权/中文任务/status）Python 客户端全过；编译装机。
- 安全：token 必填（服务启动时生成打印），未配置 App 端拒绝执行；建议仅内网使用。

## [2026-09-25] 运行时内置 pip 模块 + 工具描述/文档同步（解决 App 内 Python 环境无 pip）
背景：Chaquopy 内嵌 CPython 3.10 无独立 python 可执行文件（sys.executable 指向不存在的二进制），`python -m pip` / subprocess 场景必然失败；且随包内嵌的 site-packages 不含 pip。
改动：
1. src/main/python 随包内置 pip 23.0.1 + setuptools 65.5.0 + _distutils_hack（app.imy 852 条目含 pip 616 条，设备 AssetFinder 自动落袋）。
2. PythonExecuteTool / PipInstallTool 工具描述写清 pip 正确用法：禁止 subprocess / python -m pip；装包用 action=pip_install（pip.main 编程式→filesDir/python_user_packages）或 pip_install 工具（自研下载器，不依赖 pip，装纯 Python 包最稳）或 import pip; pip.main(['install','--target',...])。
3. handlePipInstall import pip 失败时明确提示改用 pip_install 工具。
4. PythonToolManager 初始化后加 PIP_SELFTEST 诊断（find_spec(pip)/sys.path/import 版本）。
5. 工作区文档（AgentWorkspace 模板：使用速查表/核心工具速查）、项目根 使用速查表.md、docs/AI工具功能清单.md（新增 pip_install 条目）全部同步。
- 验证：编译 assembleDebug 通过；装机 PID 14956 无崩溃；git 提交 01c8466 + a94f4a7 + 文档同步提交已推 origin/main。

## [2026-09-14] 新增 js_execute 工具（手机端 JS 执行能力）
背景：环境审计发现手机端 Agent 无 JS 引擎（无 Node/Rhino），面对 JS 只能查代码；系统 WebView 本身是完整 JS 引擎。
改动：
1. 新增 JsExecuteTool（复用 WebView evaluateJavascript，后台执行 JS，捕获 console.log/返回值/错误；内存沙箱禁文件/网络；并发上限2，超时可配）。
2. AIToolManager 注册 + 显式 ToolDefinition schema；OnlineToolManager 意图匹配（javascript/js代码/运行js/执行js）；使用速查表新增条目。
3. 本地引擎（AgentLoopEngine）走 AIToolManager 全量，自动可用。
- 验证：装机 13:27:28；真机端到端实测（ES2020 语法/console 捕获/对象回传）75ms 成功；速查表重建含新条目。

## [2026-09-14] 使用速查表移除动态工具条目
需求：动态工具就不要包括了。
改动：速查表核心工具速查移除 create_dynamic_tool/ai_create_tool/tool_registry（保留 ui_component/ui_component_plugin 与全部内置功能工具）；工具创建指南不动。
- 验证：装机 13:11:09；重启重建 grep 确认动态工具条目零匹配。

## [2026-09-14] 使用速查表/工具创建指南与最新源码核对更新
需求：把工作区其他文件（使用速查表、工具创建指南）也更新。
核对：全量提取 46 个注册工具（含 ai/python 目录）对比。
改动：
1. 使用速查表核心工具速查补齐 14 个缺失工具：calculator/time_date/unit_converter/text_tools/reminder/task/system_connect/knowledge_base/video_to_player/export_apk/control_lookup/layout_editor/get_models_profile/update_models_profile。
2. 工具创建指南工作区目录补内置指南清单（含 HTML_DESIGN_RULES.md/APK_SOURCE_GUIDE.md，删除后启动重建）。
3. create_dynamic_tool action 与源码一致无需改。
- 验证：装机 13:09:04；重启自动重建，grep 确认新条目已出现。

## [2026-09-14] 壳文档与最新源码一致性审计（修正 6 处）
需求：html 指导文档、apk 打包工具、工具描述是否根据最新源码设计。
审计：以 MainActivity AppBridge 40 桥 + ApkPacker 契约为权威核对 → 打包契约/工具描述全对；修正 6 处文档不符：
1. APK_SOURCE_GUIDE.md：信息探测 7→8、文件截图剪贴板 4→5、v7 新增 11→13（分组漏计）；getVersion 返回补 {shellVersion,bridgeApi,versionName,versionCode}；precacheUrl 回调 {ok}→{url,ok}（源码实测）；request 补网络错误 status=0+error。
2. HTML_DESIGN_RULES.md：getVersion 字段补全；⑧ 缓存补 getCacheMode()。
- 验证：装机 13:02:00；更新版已推手机 files/ 并同步 assets 内置副本。

## [2026-09-14] 工具描述引导改为工作区指导文件（手机端 Agent 可读）
需求：工具描述中有引导查看指导文件的内容吗？
问题：ExportApkTool 描述有引导，但指向电脑端源码路径 apk_shell/xxx.md——手机端 Agent 读不到。
改动：
1. ExportApkTool.java 3 处：改为"用 workspace 工具读取工作区 files/APK_SOURCE_GUIDE.md（40 桥清单/回调契约）与 files/HTML_DESIGN_RULES.md（HTML 规则）"；移除 apk_shell/ 源码路径。
2. OnlinePromptBuilder 工作区速查段：追加《HTML_DESIGN_RULES.md》《APK_SOURCE_GUIDE.md》。
- 验证：装机 12:54:59；实机步骤见 tool_desc_guide_ref_fix_0914.md。

## [2026-09-14] 打包口径复查（无残留）+ Agent 管理页文件查看/分享
需求：打包工具及工具描述 schema 是否还有残留；Agent 管理页工作区文件不能点击查看/分享。
改动：
1. 复查：util/export + ExportApkTool 无壳 v6 残留；工具描述 4 处 "v6" 均为 PP-OCRv6（OCR 引擎名，保留）。
2. AgentWorkspaceView 文件行新增「查看」+「分享」按钮：
   - 查看：ACTION_VIEW + FileProvider 内容 URI（file_paths.xml 覆盖公共/私有目录），按扩展名猜 MIME 分发系统查看器，无应用 Toast 不崩溃。
   - 分享：ACTION_SEND + EXTRA_STREAM + FileProvider URI + FLAG_GRANT_READ_URI_PERMISSION → 系统分享面板。
   - 按 zone（files/tmp/root）解析完整路径。
- 验证：装机 12:52:05，启动正常无 SecurityException；实机步骤见 workspace_file_view_share_0914.md。

## [2026-09-14] APK 指导文档固定工作区（删除后启动重建）+ 版本口径统一 v7
需求：APK 两份指导文档固定到工作区，像速查表/工具创建指南一样可删除后启动重建；已升 v7，口径一致勿留旧版本号。
改动：
1. assets/apk_shell/guides/ 内置 HTML_DESIGN_RULES.md + APK_SOURCE_GUIDE.md（从 apk_shell/ 复制分发副本）。
2. AgentWorkspace：新增 appContext 字段 + ensureBuiltinGuideAssets()（构造时从 assets 覆盖写回 files/，删除即恢复最新版）；BUILTIN_GUIDE_ASSETS 清单。
3. SmartQuizApplication.onCreate 预热 AgentWorkspace（仅写指南文件，无服务/权限副作用）→ 应用启动后自动重建。
4. AgentWorkspaceView 清空长期文件保留名单追加两份 APK 指导文档。
5. 版本口径统一 v7：HTML_DESIGN_RULES.md（去 v6 标记、22→40 桥）、README.md（v6 专业版→v7 专业版当前、12 库、共存验证 v7）、APK_SOURCE_GUIDE.md（去 v6 表述，40 个 v7 全量为权威）。
- 验证：装机 12:48:28；删除 4 份内置指南→重启→全部自动重建；v6 残留 rg 复查清零。

## [2026-09-14] APK 打包器源码指导创建 + 工具描述修正
需求：apk 打包器没有 apk 源码指导，是否可以创建指导，不能光靠猜。
排查：壳源码在项目 apk_shell/（MainActivity AppBridge 桥对象等）；缺壳源码指导；工具描述"22 个桥方法"为 v6 旧口径。
改动：
1. 新建 apk_shell/APK_SOURCE_GUIDE.md（壳源码指导）：工程结构、导出链路（ExportApkTool→ApkPacker→dt.jet 加密→base.apk 原位补丁→签名→壳加载）、JS 桥完整清单 40 个（源码为准，含 v7 新增通知/前台服务/深链/JS注入/缓存/预缓存）、回调桥契约（6 个 callbackName）、扩展壳源码流程（改 AppBridge→assembleRelease→gen_meta→替换 base.apk）、常见坑。
2. ExportApkTool.java 描述修正：22 个→40 个，指向 APK_SOURCE_GUIDE.md 与 HTML_DESIGN_RULES.md；回调桥清单补全（+startClipboardWatch/precacheUrl）。
- 验证：assembleDebug 通过；已安装手机（12:41:14）；文档已推手机 files/；导出链路实机验证步骤见 apk_source_guide_fix_0914.md。

## [2026-09-14] 思考组件补全 Markdown 渲染（独立思考消息 + 热更新两路径）
需求：思考组件没有 markdown 渲染？
改动：
1. ThinkingMessageViewHolder.bind：思考内容从直接 setText 改为 RenderExecutor.execute + TextViewSpan 全量 Markdown 渲染（与 AI 消息正文同引擎）；宽度实测兜底屏幕，post 布局后渲染。
2. ChatAdapter.updateThinkingRound（按 id 热更新单轮）：setText → setRenderedText（Markdown 渲染）。
- 效果：独立思考消息与按 id 热更新的思考轮内容正确渲染 **加粗**/代码/列表/表格等。
- 验证：assembleDebug 通过；已安装手机（12:35:41）；实机验证见 thinking_markdown_fix_0914.md。

## [2026-09-14] 文件列表卡片支持应用内文件夹导航（file_list 内置 UI）
需求：文件查看器内置 ui 不支持文件夹。
改动：FileListCardView 重构——
1. 目录项（type=dir）点击从 openLink（跳系统）改为 navigateTo：组件内 new File(path).listFiles() 读取并刷新列表（目录优先、文件在后，各按名称排序）。
2. 顶部新增「⬆ 上一级」行（path 非根时显示），parentPath 截取父目录导航返回。
3. 文件项保持点击打开；大小格式化（B/KB/MB）。
- 效果：file_list 内置 UI 支持应用内多级目录浏览，不再只能打开文件。
- 验证：assembleDebug 通过；已安装手机（12:30:42）；实机验证见 file_list_nav_fix_0914.md。

## [2026-09-14] 思考轮次独立 UI 组件（id 锚点 + 独立折叠 + 按 id 热更新）
需求：chatadapter 中思考轮次还是显示在同一个 ui 组件中。
改动：
1. ChatMessage：transient thinkingCollapsedRounds（Set）+ isThinkingRoundCollapsed / toggleThinkingRoundCollapsed（轮级独立折叠状态，不持久化）。
2. ChatAdapter：AIMessageViewHolder 新增 holderMessage；renderRoundAssembled / addRoundThinkingBlock 升级——每轮思考 = 独立 LinearLayout roundBox（tag=thinkingRoundIds[i]），标题行可独立点击折叠/展开该轮（▸/▾ 指示），内容体 tag=round_body_<id>；新增 updateThinkingRound(roundId, newContent) 按 id 定位并热更新单轮（不重建整条消息）。
- 效果：多轮思考每轮独立组件、独立折叠、id 锚点可定位/热更新；消息级 thinkingExpanded 保留（轮级折叠优先）。
- 验证：assembleDebug 通过；已安装手机（12:25:20）；实机验证见 thinking_round_ui_fix_0914.md。

## [2026-09-14] 多轮思考轮次 id 修复（序号归 1 + 补发不再顶号 + turnId 持久化生效）
需求：多轮思考不单独按轮次分配 id？实测（会话文件）确认已按轮次分配，但暴露 3 问题：
1. 子序号从 0 开始（T1-K0）→ 派发器计数器初值 1（T1-K1…）。
2. 恢复补发消耗主 id（旧回合 T1-K*，新回合变 T3/T4/T5，T2 消失）：applySubId(type, masterId) masterId 为空不再 applyMasterId（返回 null 不分配）；ensureRecoveredIds 跳过 turnId 为空的旧消息；无参 applySubId(type)（消息创建路径）保留自动 applyMasterId。
3. turnId/subId 持久化（deepCopyForSave 补拷）本轮装机生效，新消息保存不再丢 id。
- 验证：assembleDebug 通过；已安装手机（12:21:47）；实机验证见 thinking_round_id_fix_0914.md。

## [2026-09-14] 退出界面不再通知（僵尸前台服务）+ 消息对 id 持久化补漏
需求：ai 对话界面退出时生成还会通知，查看对话日志。日志证据：会话最后一条 AI 消息停在 GENERATING；且所有消息 turnId/subId 为空（id 持久化实际丢失）。
改动：
1. 移除 SmartQuizApplication 无条件 startAIProcessingService()：当前生成走 direct streaming 链路（AgentChatHandler→ModelExecutionBridge），不使用该服务；无条件启动会在通知栏常驻"AI 处理服务 正在处理 AI 任务..."（setOngoing），退出界面后仍在 → 消除打扰（服务类保留，旧链路需要时按需启动）。
2. ChatHistoryManager.deepCopyForSave 补拷 turnId/subId：clone() 的 Builder 无这两个字段，保存后消息对 id 全丢（上轮已补 thinkingRoundIds）；恢复路径 Gson 直读不受影响。
- 效果：退出界面无 AI 处理服务常驻通知；turnId/subId 真正持久化，适配器按 id 管理恢复生效。
- 验证：assembleDebug 通过；已安装手机；dumpsys 确认服务不再运行、无常驻通知；实机验证见 exit_no_notify_fix_0914.md。

## [2026-09-14] 组件 ID 持久化 + 组件缓存同步清理（防 id 冲突）
需求：组件 id 不支持持久化，改为持久化；有缓存可从缓存查找/加载；清空历史同步清对应缓存，防止缓存冲突导致异常。
改动：
1. ChatHistoryManager.ComponentDataAdapter：序列化写 id、反序列化读 id（旧数据无 id 保持 null，惰性补发）。
2. deepCopyForSave 补拷 thinkingRoundIds（与 thinkingRounds 一一对应，保存不丢）。
3. clearChat / startNewConversation 同步调用 ComponentCollector.clear()（清组件暂存缓存，防残留组件混入新回合）。
4. 新增 ensureRecoveredIds：历史恢复后按消息 turnId 前缀补发缺失的组件 COMPONENT id / 思考轮 THINKING id（幂等）。
5. ChatIdDispatcher 新增 applySubId(IdType, masterId) 重载（恢复补发指定主 id 前缀，组件 id 与消息对主 id 一致）。
- 效果：组件 id 随组件持久化；恢复/清空全链路 id 锚定、缓存零残留。
- 验证：assembleDebug 通过；已安装手机（12:10:31）；实机验证见 chat_component_persist_fix_0914.md。

## [2026-09-14] 聊天 ID 分类：类型化子 id（U/A/K/F/C/S），适配器按主 id/类型/子序号全链路管理
需求：id 分类（用户/AI/AI思考/工具/工具生成的UI组件）；适配器按分类使用；chatHistory 保存 id；单轮内/多轮内/连续对话内都可管理。
改动：
1. ChatIdDispatcher 新增 IdType（USER/AI/THINKING/TOOL/COMPONENT/SYSTEM）与 applySubId(IdType)：子 id = 主id-类型码序号（T1-U1、T1-A1、T1-K1、T1-F1、T1-C1），每类型独立递增；applyMasterId/reset 时清空子序号。
2. ChatMessage 新增 thinkingRoundIds（与 thinkingRounds 一一对应），addThinkingRound 自动发放 THINKING id（旧数据恢复补占位）。
3. ComponentData 新增 id（COMPONENT 子 id）；组件落地时补发（已有 id 保留）。
4. ChatAdapter 新增管理 API：getMessagesByTurnId / getMessagesByType / getMessagesByTurnAndType / getBySubId。
5. AIChatActivity 全部子 id 申请改带类型（USER/AI/TOOL）；思考轮/组件 id 自动发放。
- 效果：适配器按「主 id 分轮、类型码分桶、子序号排序」管理；单轮/多轮/连续对话 id 全链路可定位；Gson 持久化。
- 验证：assembleDebug 通过；已安装手机（12:06:08）；实机验证见 chat_id_typed_fix_0914.md。

## [2026-09-14] 聊天 ID 派发器：主/子 id 申请发放机制，清空对话重置
需求：增加 id 派发器；对话历史清空后重置；id 分主 id（消息对）及子 id（对内消息），实现申请发放。
改动：
1. 新增 ChatIdDispatcher（单例，线程安全）：applyMasterId（T1、T2…，重置子序号）/applySubId（T1-1…，无主 id 自动申请）/peekMasterId/reset。
2. ChatMessage 新增 subId 字段（Gson 持久化）；turnId 改由派发器发放。
3. AIChatActivity：addUserMessage 申请主/子 id；三处 AI 占位与工具/步骤消息绑定 turnId+subId；clearChat 时派发器 reset（清空历史归零）。
4. 设计要点：仅 clearChat 重置；startNewConversation 不重置（旧会话保留在抽屉，归零会导致切回后 turnId 与旧消息重复）。消息级全局唯一仍由 ChatMessage.id（UUID）承担。
- 验证：assembleDebug 通过；已安装手机（12:01:18）；实机验证见 chat_id_dispatcher_fix_0914.md。

## [2026-09-14] AI 对话页面消息 id 绑定：消息对 turnId + 多轮组件全 id 定位，杜绝索引错乱
需求：消息对跨任务 id 绑定；id 新增/多轮任务组件无 id 绑定导致 UI 显示位置错乱；模型服务分批 id 给 UI 显示。
改动前：工具/步骤消息插入用缓存索引 currentStreamingMessageIndex + 手动 ++（穿插后漂移）；进度更新用创建时索引快照（插入后过时）；resolveStreamingIndex 回退缓存索引；消息对无配对字段。
改动后：
1. ChatMessage 新增 turnId（回合 id）：一次发送 = 一回合，user/AI/工具/步骤消息共享；Gson 持久化，恢复后配对不丢。
2. ChatAdapter 补 getMessageById/getLastMessageByTurnId（实时 id 查找）；既有 id API（appendToken/completeMessage/updateThinkingStep 等）均实时定位。
3. AIChatActivity：addUserMessage 生成 currentTurnId；三处 AI 占位+工具/步骤消息绑定 turnId；工具/步骤插入点改 resolveStreamingIndex()（按 messageId 实时定位）；resolveStreamingIndex 去缓存索引回退；updateInferencePhase/updateInferenceProgress 七处调用点从创建时快照改 lambda 内实时定位；工具卡片按 toolCallId 精确定位。
- 效果：多轮 Agent 穿插下 AI 气泡位置由 id 决定；进度不错写；消息对按 turnId 稳定绑定；RecyclerView 错乱根因消除。
- 验证：assembleDebug 通过；已安装手机（11:56:50）；实机验证见 chat_message_id_fix_0914.md。

## [2026-09-14] AI 对话界面退出/重建不中断生成：界面只是显示器，生成继续落盘 + 重进热加载
需求：ai 对话界面退出时不应中断生成；界面只是显示和操作界面，不应当影响模型工作；模型工作时写历史文件，重进加载，持续更新可热加载。
改动前：onDestroy 会 agentChatHandler.cancel() + modelBridge.stopGeneration()——退出/旋转重建即中断生成，回复只留半截。
改动后（AIChatActivity）：
1. onDestroy 移除 cancel/stopGeneration；新增 uiDetached 标志（回调只落盘、不再更新界面）。
2. 回调守卫：onToken/思考/进度等纯 UI 回调直接跳过；完成/出错/停止走"只落盘"分支（更新消息内容+状态 → saveHistoryAsync）；Agent onToken 的 currentStreamingContent 持续累积供落盘。
3. 重进热加载：恢复历史时发现 GENERATING 残留（后台仍在生成）→ 轮询会话文件（1.5s/次），内容变长/终态即更新界面；连续 3 次无变化或 60 次上限停止；普通/Agent 路径均覆盖。
4. 模型服务本就是应用级单例（AIService/LlamaHelper/桥），不依附界面。
- 遗留：回调/旧 AgentChatHandler 持有旧 Activity 引用至生成结束（短窗口，生成结束释放）；后续可改弱引用。
- 验证：assembleDebug 通过；已安装手机（11:46:02）；实机验证见 local_exit_no_interrupt_fix_0914.md。

## [2026-09-14] 本地推理上下文生命周期修复：页面重建/退出不错位、不中断模型服务
用户提问核查（chatHistory 依附页面？重建/退出影响？）：
1. 桥（ModelExecutionBridge）为 appContext 单例，chatJsonHistory/文件不依附 Activity——页面重建/退出均安全；推理历史 append 即落盘。
2. 修复：恢复最新会话处同步 setLocalSessionId(sessionId)（此前重建后推理历史错位到 default）。
3. 修复：Agent 首次创建会话时 migrateCurrentHistoryToSession 迁移 default→新会话（Agent 首轮不失忆）。
4. null 会话加 localSessionInitialized 幂等（避免每次发送前重载 default 文件）。
5. onDestroy 只 stopGeneration/cancel（停当前生成），不释放模型/AIService 单例——退出/重建后服务继续可用；旋转重建会中断当前生成（现状行为，消息保留归一已完成）。
- 验证：assembleDebug 通过；已安装手机（11:34:15）；详见 local_history_independent_fix_0914.md 四.5。

## [2026-09-14] 本地模型上下文历史独立化：chatHistory 只管 UI，推理历史按会话持久化
需求：本地模型的历史不由 chatHistory 管理；chatHistory 只服务 AI 对话界面显示。
改动前：普通对话发送前从 UI chatHistory 全量重建 chatJsonHistory（内存态、不持久化、无会话隔离），切会话清空从头开始。
改动后（ModelExecutionBridge + AIChatActivity）：
1. chatJsonHistory 按会话持久化到 local_chat_history_{sid}_default.json；setLocalSessionId 切会话保存当前→加载目标（幂等）；clearLocalHistory/deleteLocalHistory 清空/删会话联动。
2. 请求组装按 token 预算倒序收集（safeRef-1024 口径），被挤出对话生成【历史对话要点】临时注入本轮（不入持久化）。
3. UI 只同步轮次性信息（提示词变更标记/要点，setPendingExtraSystemSections）；删除每次发送前 rebuild。
4. 本地 Agent 历史来源改为桥内会话历史（getLocalHistoryEntries），完成后回写（appendExternalChatTurn）→ 普通↔Agent 同一真相源。
5. 旧会话平滑迁移：切回无推理历史文件的旧会话时从 UI 消息一次性重建（干净 user/assistant）。
6. Activity 重建恢复当前会话推理历史；onError 清理待回写标记防串轮。
- 效果：本地上下文按会话独立、重启不丢、普通↔Agent 连续；UI 只做显示。
- 验证：assembleDebug 通过；已安装手机（11:28:50）；实机验证见文档 local_history_independent_fix_0914.md。

## [2026-09-14] 长期记忆变化破坏前缀缓存修复：记忆移出前缀区，请求末尾 D 块动态注入
现象：长期记忆"有时候会变化"→ 在线缓存命中不稳定。
根因：记忆摘要在 execute 首轮注入前缀区（messageHistory.add(1,...)，system 之后第 2 条），按当前用户消息加权生成——记忆库更新后摘要内容变 → 前缀从第 2 条断裂 → 全部历史缓存 miss；且旧实现记忆只在首轮注入，会话中记忆库更新后模型读到的永远是旧摘要。
修复（OnlineAgentEngine）：
1. 记忆从 messageHistory 移出 → buildOutgoingMessagesArray 组装末尾追加（D 块）：system+历史前缀完全稳定，记忆变化不影响缓存命中。
2. 每次用户消息刷新 latestMemorySummary → 记忆库变化立即生效（修复"记忆永不更新"缺陷）。
3. restoreHistory 识别【长期记忆】消息一律跳过（memory_skipped），不再落盘/恢复。
4. 记忆用副本追加，不污染 messageHistory；PROMPT_VERSION → 20260914-v2（旧文件一次性重建后稳定）。
- 效果：记忆变化不再破坏前缀缓存；记忆每轮最新；工具循环内每轮请求末尾均带最新记忆。
- 验证：assembleDebug 通过；已安装手机（11:01:27）；实机验证见文档 memory_prefix_fix_0914.md。

## [2026-09-14] 切回原模型缓存命中暴跌修复：system/env/memory 版本校验原样保留
现象：在线 A → 切 B → 切回 A 后缓存命中暴跌（前几条全 miss）。
根因：restoreHistory() 丢弃所有 system 消息（提示词/环境上下文/长期记忆均为 system role），切回 A 后 execute 重新生成 env/memory → 前缀从第 2 个消息断裂 → 恢复的历史全部 miss。
修复（OnlineAgentEngine）：
1. persistHistory：system 消息落盘用副本打版本标记 pv（仅文件层，不进请求体）。
2. restoreHistory：pv 与 PROMPT_VERSION 一致 → 原样保留 system/env/memory（前缀与切换前逐字节一致，缓存直接命中）；不一致（提示词升级/旧数据）→ 才丢弃重建。
3. 新增 PROMPT_VERSION="20260914-v1"，修改提示词模板时必须同步递增。
4. 【对话历史摘要】无论版本一律保留（原逻辑不变）。
- 效果：切回原模型命中率不再暴跌；Activity 重建/重启同版本历史前缀稳定；旧文件平滑迁移。
- 验证：assembleDebug 通过；已安装手机（10:56:14）；实机验证见文档 cache_switchback_fix_0914.md。

## [2026-09-14] 本地 Agent 缓存命中修复：天气直给指令移出 system 前缀
本地推理（llama.cpp）KV 缓存复用依赖 prompt 前缀字节稳定，但 AgentLoopEngine.run() 在用户消息含天气词时把动态"【本次任务】查 XX 天气..."指令拼进 sysPrompt → system（前缀第一块）每轮变化 → KV 缓存整段失效，每轮全量重算前缀。
修复：动态指令从 system 移到本轮 user 消息末尾（D 块动态尾巴），system + 历史前缀保持静态；行为不变（指令原样迁移，模型仍能读到）。
- 顺带确认本地工具集 activeTools（LinkedHashSet 固定序）+ buildToolsJson 确定性输出，无其他前缀抖动源。
- 验证：assembleDebug 通过；已安装手机（10:47:41）；实机验证：连续问"查北京天气"→"查上海天气"，第二条应更快（KV 前缀命中）。

## [2026-09-14] 历史混杂修复：在线/本地历史按「会话×模型」隔离 + 切换收敛 + 本地上下文过滤
AI 对话页切换模型时在线历史与本地历史混杂（三套历史两两脱节）：
1. **历史文件按「会话 × 模型」双维度隔离**：OnlineAgentEngine 新增 activeModelId，文件改 online_agent_history_{sid}_{modelId}.json；新增 setModelId（保存当前→清空→恢复目标，与 setSessionId 同款模式）——模型 A→B 切换各模型独立上下文、可独立续聊。
2. **切换处理收敛**：onActiveModelChanged 移除 clearHistory（清错对象），改为 setModelId + UI 提示；initAgentChatHandler 重建 handler 后 setModelId→setSessionId 并提示类型切换；发送前兜底同步 setModelId（生成中切换被忽略时补救，幂等）。
3. **本地 Agent 上下文过滤**：buildAgentHistory 改 includeThinking=false + stripContextSections（剥离助手消息 [工具调用]/[思考过程] 段）；本地模型只吃纯对话正文，在线工具链痕迹不再污染，事实结论仍保留。
- 验证：assembleDebug 通过；已安装手机（10:40:21）；待实机验证切换行为。

## [2026-09-14] 成本优化修复：会话级工具集「只增不改」+ env 跨天刷新保前缀 + 缓存诊断探针
手机工作区缓存诊断（cache_hitrate_diagnosis_0914.md / deepseek_v4flash_cost_analysis_0914.md）实测命中率 44%→39% 下滑、成本高度集中在输入侧未命中段（约 24K tokens 全价/轮）。代码层面确认并修复：
1. **【核心】tools 每轮动态筛选 → 会话内「只增不改」**：OnlineAgentEngine 新增会话级工具名缓存（cachedToolNames/cachedToolsJson），首轮按核心集+意图生成，后续轮仅把新意图工具追加到集合末尾；classifyIntentByModel 兜底由"整体替换 toolsJson"改为"合并新工具名"。tools 前缀字节稳定 → tools 段从每轮全价转为吃缓存折扣（DeepSeek 命中价≈未命中价 1/50）。
2. **env 跨天刷新不再破坏前缀**：refreshEnvIfStale 由原位替换（messageHistory.set）改为"旧 env 不动 + 新 env 追加到末尾"，已存在当日 env 时跳过；跨天首条消息不再整链 miss。
3. **P0 缓存诊断探针**：streamOneIteration 发送前打印 CACHE-PROBE sha256/msg_len/tools_len/thinking，连续两轮哈希一致即前缀稳定，可直接定位抖动点。
4. 工具缓存复位：shutdown/clearHistory/clearAllHistory/setSessionId 均重置（新会话不残留旧工具集）。
- 验证：compileDebugJavaWithJavac 通过；待装机后按 CACHE-PROBE 日志确认前缀稳定、命中率回升至 ≥60%。

## [2026-08-16] 增强文生图工具（image_gen）
用户要求"创建文生图工具，调研免费 API 并设计"——确认项目已有 image_gen（Pollinations.ai 免费无 key），本轮增强：
1. **多模型**：flux（默认）/ flux-realism（写实）/ flux-anime（动漫）/ turbo（快速），白名单校验 + 别名归一化（real/photo→flux-realism 等）。
2. **中文提示词增强**：中文 prompt 自动追加英文质量词（high quality, detailed, 8k, professional）——Flux 对英文理解更好；支持 style 风格参数（photorealistic/cartoon/watercolor 等）。
3. **去重缓存**：同 prompt+model+尺寸+风格 复用已生成图片（ConcurrentHashMap）。
4. **失败降级**：指定模型生成失败自动换 flux 重试一次；仍失败返回明确提示。
5. **指南更新**：AIToolUsageGuide 增加 image_gen 典型调用示例（含 model/style 说明）。
- 免费 API 调研结论：Pollinations.ai 是最优（完全免费、无 key、无限额度、Flux 质量高）；本地 SD 手机跑不动、HuggingFace 有配额限制。

## [2026-08-16] Agent 图片组件同样修复：BitmapFactory 优先解码本地文件
用户询问"Agent 图片组件是否也有这个问题"——检查确认 ImageGridCardView / FileCardView / MarkdownRenderer 的图片预览同样用纯 Glide 加载，file:// 可能不回调转圈。统一修复：
1. **ImageGridCardView**：loadWithFeedback（缩略图）+ showFullImage（全屏）——本地文件（file:// 或 / 开头）优先 BitmapFactory 采样解码，网络 URL 走 Glide（15s 超时+失败提示）。
2. **FileCardView.showImagePreview**：同样 BitmapFactory 优先。
3. **MarkdownRenderer** 图片预览：同样 BitmapFactory 优先。
- 现在所有图片组件（对话附件/输入区/Agent 图片网格/文件卡/Markdown 预览）统一策略：本地文件直接解码，网络才 Glide。

## [2026-08-16] 修复图片预览仍转圈：BitmapFactory 优先解码本地文件
用户反馈"一直转圈，链接的图片渲染器正常吗"——Glide 加载 file:// 在某些场景不回调。改为双路径：
1. **AIChatActivity.showImagePreview**：优先 BitmapFactory 直接解码本地文件（thumbnailPath/localFilePath/file:// 路径），采样解码防 OOM（>2048 降采样）；本地文件不存在才回退 Glide（仍带 15s 超时+失败提示）。
2. **ChatInputManager.showImagePreview**（输入区预览）：同样 BitmapFactory 优先，Glide 兜底。
3. 诊断日志：Image preview target 打印实际加载路径（thumb/local/url）。
- 验证：设备日志显示预览目标正确解析为本地 cache 路径，Bitmap 解码成功无转圈。

## [2026-08-16] 修复对话图片点击预览一直转圈
用户反馈"对话中图片点击后一直转圈"——根因：预览用 Glide 加载 attachment.url（content:// 可能权限过期），失败前一直 loading。
1. **预览优先本地路径**：showImagePreview 增加 thumbnailPath/localFilePath 参数，加载优先级 thumbnailPath → localFilePath → url；纯文件路径转 file:// 再给 Glide，避免 content:// 权限过期导致长时间转圈。
2. **点击回调传完整附件**：onPreview 传 attachment.url + thumbnailPath + localFilePath。
3. 失败仍有 15s 超时 + onLoadFailed 提示（不再无限转圈）。

## [2026-08-16] 修复附件（图片）发送不到对话
用户反馈"图片发送不到对话中"——根因：两套附件列表不一致。
1. **双列表 bug**：图库选图走 ChatInputManager.addAttachment（inputManager 内部列表），但 sendMessage 读的是 AIChatActivity.currentAttachments 字段（只被拍照/录音填充）→ 图库图片 sendMessage 拿不到，发不出去。
2. **统一数据源**：sendMessage 改为从 inputManager.getCurrentAttachments() 取附件（唯一权威）；拍照/录音也改走 inputManager.addAttachment（不再直接写 currentAttachments 字段）；发送后 inputManager.clearAttachments() 清空。
3. **去掉重复用户消息**：handleMultimodalImage / handleOnlineMultimodalImage 里原 `addUserMessage(userText)` 会再插一条无附件用户消息（sendMessage 已加带附件的）→ 删除，只创建 AI 回复消息。
- 现在图库/拍照/录音附件统一经 inputManager，sendMessage 能正确携带发送，对话中显示附件缩略图 + 单条用户消息 + AI 回复。

## [2026-08-16] 核查并修正各路径 token 统计
用户再次质疑统计正确性——逐路径核查：
1. **在线 Agent（多轮）**：引擎 execTotalPromptTokens/execTotalCompletionTokens 累计 ✅（上轮已修）；本轮补两个残留：
   - 移除 `if (finalTokenCount > 0)` 门控：Agent 纯工具操作（最终回答为空文本）时 finalTokenCount=0 会跳过统计 → 改为 `finalTokenCount > 0 || agentExecIn > 0` 才统计。
   - 消息 tokensGenerated：Agent 路径 notifyComplete 不带 token 数（tokenCount=0）→ 补用引擎累计输出。
2. **在线普通对话（单轮）**：onTokenStats API 统计 → updateRequestStats ✅（单轮覆盖=正确）。
3. **本地模型**：LlamaHelper.countTokens 估算输入 + native getTokenCount 输出 ⚠️ 估算非精确（本地无 API 计数，可接受）。
- 各路径统计现状：Agent 用 API 真实累计、在线普通用 API 单轮、本地用 native/估算。

## [2026-08-16] 修正 token 统计：改为引擎累计真实 usage（修复只统计最后一轮）
用户质疑"统计方式不正确"——确认正确，原实现用 getLastPromptTokens/getLastCompletionTokens（最后覆盖值），Agent 多轮（工具调用轮+回答轮）时中间轮次的输入输出全丢，严重低估总消耗。
1. **引擎累计**：OnlineAgentEngine 新增 execTotalPromptTokens/execTotalCompletionTokens，onUsageWithCache 每轮累加（API 每轮返回的是本轮完整输入，含增长的历史前缀，逐轮累加才是真实总输入）；execute 开始重置。
2. **透传**：AgentRouter / AgentChatHandler 新增 getExecTotalPromptTokens/getExecTotalCompletionTokens。
3. **统计使用累计值**：
   - TokenStatsManager.updateRequestStats：Agent 模式用引擎累计输入/输出（不再只取最后一轮）
   - 气泡汇总：显示"📥 输入 X · 📤 输出 Y tokens（累计）"
   - 顶部统计栏：基于 TokenStatsManager（已用累计值更新），自动正确
- 命中率仍按单轮（cache_hit/该轮prompt）计算，语义正确。

## [2026-08-16] 增加缓存命中率显示
在缓存命中统计基础上加命中率：
1. **气泡汇总**：缓存命中显示改为"⚡ 缓存命中率 62%（3328/5335 tokens）"（命中/输入百分比）。
2. **顶部统计栏**：Agent 在线模式追加"· ⚡命中率 62%"（引擎透传 API usage 计算）。

## [2026-08-16] 增加输入/输出 token 统计显示
用户要求"增加输入和输出统计"：
1. **顶部 Token 统计栏**（updateTokenStatsUI）：完成态从"🔵 N tokens"改为"📥 输入 X · 📤 输出 Y tokens（会话累计 Z）"。
2. **实时生成统计**（updateStreamingTokenStats）：在线模式完成态优先显示 API 返回的输入/输出（onlinePromptTokens/onlineCompletionTokens）。
3. **Agent 引擎透传真实 usage**：OnlineAgentEngine 新增 lastPromptTokens/lastCompletionTokens（onUsageWithCache 记录）+ getter；AgentRouter / AgentChatHandler 透传。
4. **Agent 汇总增强**：气泡内 agentSummary 追加"📥 输入 X · 📤 输出 Y tokens"（API 真实值）；TokenStatsManager.updateRequestStats 的输入 token 优先用 Agent 引擎真实 usage（不再用长度估算）。

## [2026-08-16] 修复切出界面/重启后缓存命中失败（Agent 历史持久化）
用户反馈"AI对话界面切出后及应用重启后都会导致缓存命中失败"——根因：Agent 引擎的 messageHistory 是内存字段，Activity 重建/重启后引擎重建 → 历史丢失 → system+env 前缀重新生成（env 时间戳含分钟，每次不同）→ 前缀变化 → 服务商缓存 miss。
1. **env 时间戳改日级**：buildEnvironmentContext 的格式从"yyyy年M月d日 EEEE HH:mm"改为"yyyy年M月d日 EEEE"，同一天内重建前缀稳定。
2. **Agent 历史持久化**：OnlineAgentEngine 新增 persistHistory/restoreHistory/deleteHistoryFile——messageHistory 以 Gson JsonArray 存到私有文件（online_agent_history.json），execute 完成后保存，构造时恢复；clearHistory 同步删文件。
3. **生命周期衔接**：Activity 重建/重启后 initAgentChatHandlerIfNeeded 新建引擎 → restoreHistory 恢复历史 → system+env 前缀不变 → 缓存命中保持；clearChat/startNewConversation/switchToSession 均调 clearHistory 清持久化文件，与新会话同步。
- 之前已验证：同会话连续提问缓存命中（cached_tokens=3328）；修复后切出/重启也应保持命中。

## [2026-08-16] 修复破坏缓存命中的关键点：工具定义顺序不稳定
用户要求"检查模型调用时破坏缓存命中的地方"——逐点排查后确认并修复：
1. **【已修】工具定义顺序随机（最严重）**：toolMetaIndex 是 ConcurrentHashMap，getToolDefinitions 每次构建遍历 values() 顺序不稳定；且引擎每轮 refreshRegistry() 重建索引，导致同一批工具每次 tools JSON 字节不同 → 前缀缓存 miss。修复：getAllToolMetas 按工具名排序（Collections.sort），tools 参数顺序确定。
2. **【已确认安全】其余检查点**：
   - system 提示词：同一模型 agentMode 稳定（detectAgentCapability 按模型名静态判断），模板不变
   - env 上下文（时间/位置/天气）：仅 messageHistory 为空时注入一次，同一对话内前缀稳定
   - messageHistory：追加不插入；trimMessageHistory 从头部截断保留 system 前缀
   - 工具结果：追加到历史尾部，不改变前缀
   - 引擎实例：AgentChatHandler/OnlineAgentEngine 复用（initAgentChatHandlerIfNeeded），历史跨轮保留
   - 模型参数（temperature/max_tokens/stream/tools）：每轮相同
- 修复后多轮 Agent 请求前缀逐字节稳定，主流 API（DeepSeek/OpenAI）自动缓存命中。

## [2026-08-16] API usage 字段解析兼容多服务商
用户指出"不同模型返回的字段可能不同"——修正 usage 解析兼容性：
1. **缓存字段三种结构兼容**：
   - OpenAI/Moonshot/通义：`usage.prompt_tokens_details.cached_tokens`（嵌套对象，此前漏解析）
   - DeepSeek/智谱：`usage.prompt_cache_hit_tokens`（顶层）
   - 兜底：`usage.cached_tokens`（顶层）
2. **token 计数兼容**：firstInt() 依次尝试 prompt_tokens/input_tokens（OpenAI/Anthropic 差异）、completion_tokens/output_tokens；total_tokens 缺失时用 prompt+completion 求和。
- 新增 firstInt 辅助方法；日志打印 prompt/completion/total/cache_hit 便于核对服务商实际字段。

## [2026-08-16] API 缓存命中统计展示
用户要求"分析 API 响应缓存命中，做显示功能"：
1. **请求加 stream_options.include_usage**：callOpenAIStreamWithToolsV2 请求体添加 stream_options.include_usage=true（OpenAI/DeepSeek 标准），流式响应末尾返回 usage（含缓存统计）。
2. **解析缓存字段**：readStreamResponseWithTools 解析 usage 时同时读取 prompt_cache_hit_tokens（DeepSeek）或 cached_tokens（OpenAI），新增 NativeToolStreamCallback.onUsageWithCache 回调；日志打印 prompt/completion/total/cache_hit。
3. **透传链**：OnlineAgentEngine.lastCacheHitTokens 字段 + getLastCacheHitTokens()；AgentRouter / AgentChatHandler 逐层透传。
4. **UI 展示**：Agent 完成时气泡内汇总追加"⚡ 缓存命中 N tokens（本轮省去重复计费）"。
- 效果：在线 Agent 多轮对话时可直观看到前缀缓存命中了多少 token（DeepSeek 返回 prompt_cache_hit_tokens 时）。

## [2026-08-16] 工具定义恢复全量注入（利用 prompt caching + 模型自行探索）
用户指出"在线模型一般有缓存命中及长上下文，可以让模型自行探索"——确认架构后调整策略：
1. **恢复全量工具注入**：OnlineAgentEngine 改回 toolManager.getToolDefinitions()（不再关键词裁剪）。依据：
   - OpenAI/Anthropic 兼容 API（用户可配 DeepSeek/通义/自建等）普遍有 prompt caching——system+工具定义作为固定前缀，多轮不变即命中缓存，全量成本可忽略。
   - 长上下文模型（128K+）下全量工具定义占比小。
   - 全量注入让模型自行探索/组合任意工具，不被裁剪限制能力（模型想用 python/数据库等均可用）。
2. **多轮前缀稳定**：每轮 streamOneIteration 复用同一 toolsJson，tools 参数不变 → 前缀缓存命中最大化。
3. 保留 getToolDefinitionsForMessage/getToolDefinitionsByCategories 作为可选能力（未调用，供未来按需场景）。
4. 保留提示词精简（组件指南 6 种高频 + 知识库节压缩）——省首轮 token 且无副作用。

## [2026-08-16] 大幅节省 Agent token：提示词精简 + 工具定义按需注入
用户担忧"会不会浪费巨量 token"——量化后确认三大消耗点并优化：
1. **组件指南精简（约 -1200 tokens/轮）**：从 21 种组件全量示例压缩为 6 种高频（chart/info_card/table_card/list_card/alert_card/weather_card），其余组件靠工具自动附加（FileTool→file_list、NetworkSearch→list_card 等），模型无需知道全部格式。
2. **知识库策略节压缩（约 -500 tokens/轮）**：从 5 段详述精简为 4 行要点。
3. **工具定义按需注入（最大头，约 -3000~5000 tokens/轮）**：20+ 工具的完整 OpenAI 定义（全量可达数千 token）改为按用户消息关键词匹配类别子集——只注入相关工具（天气/搜索/翻译/计算/文件/时间/定位/数据/图片/应用），基础类别（file/data/general/meta/toolkit/system/app/tool）始终保留；无意图命中时回退全量保证能力。OnlineToolManager.getToolDefinitionsForMessage + OnlineToolRegistry.getToolDefinitionsByCategories。

## [2026-08-16] 确认 Agent 引擎完整利用 UI 组件 + 强化标记输出规则
用户询问"Agent 引擎能否利用这些 UI 组件"——逐链路验证后确认完整可用，并强化引导：
1. **链路验证**（全部打通）：
   - 提示词：buildSystemPrompt（辅助模式）与 buildSystemPromptTakeover（接管模式）均含 buildComponentGuideSection 组件指南。
   - 输出：模型生成 ```component:xxx {json}``` → streamOneIteration 流式 → onToken → safeUpdateMessage → ComponentContentSplitter 解析 → bindMessageContent 实时渲染。
   - 工具：withComponent → ComponentCollector → 完成时合并消息渲染。
   - 清理安全：notifyComplete 的 cleanModelOutput/sanitize 只清孤立代理项/U+FFFD/非法控制字符，不破坏反引号/花括号/冒号，组件标记保留。
2. **强化规则**：组件指南规则明确"必须直接输出组件标记本身（```component:类型 换行 JSON 换行 ```），不要把 JSON 原文或组件说明文字展示给用户；一个标记块只包含一个组件"——提高模型稳定输出标记的概率。

## [2026-08-16] 新增 4 种 UI 组件（对照工具能力缺口）
用户询问"还有哪些 UI 组件可增加、组件如何设计复用"——确认设计模式（ChatComponent 接口 + ComponentRegistry 插件注册 + 标记/withComponent 双触发 + 纯代码建 View），对照工具返回结构新增：
1. **file_list 文件列表卡**：📁/📄 图标 + 文件名 + 大小 + 路径（FileTool.list 自动附加，含 formatSize 大小格式化）
2. **grid_card 宫格卡**：图标网格（应用列表/分类/快捷入口），columns 可配
3. **contact_card 联系卡**：phone/email/sms/map 四型，大号展示 + 一键拨号/发信/地图动作按钮（点击调起系统 Intent）
4. **todo_card 待办卡**：✅/⬜ 完成状态 + 删除线 + 底部"完成 N/M"摘要
- 注册进 ComponentRegistry（现共 21 种）；OnlinePromptBuilder 组件指南补充 4 种新格式示例。
- 复用方式：任何工具 `withComponent(ComponentData.of("file_list", props))` 或模型输出 ```component:file_list {json}``` 即可渲染，无需改渲染管线。

## [2026-08-16] 修正组件标记解析（修复上轮引入的嵌套 JSON 截断 bug）
用户反馈"不要乱改，好好弄一下"——自查发现上一轮把代码块正则结束符从闭合的 ``` 改成第一个 }，导致 metric_card 等含嵌套数组的 JSON 在第一个内层 } 处被截断、解析失败：
1. **恢复正确结束符**：COMPONENT_BLOCK 改回以 ``` 闭合（` ```component:(\w+)\s*([\s\S]*?)``` `），只有真正的三反引号才结束标记，JSON 内部 } 不影响匹配。
2. **大括号配对提取**：新增 extractJsonBody()——以第一个 { 开始，按大括号深度（跳过字符串内 { }）找到配对的最后一个 }，正确提取含嵌套对象的完整 JSON（如 metric_card 的 metrics 数组、chart 的 series 多层）。
3. **裸标记兜底保留**：COMPONENT_BLOCK_BARE 用 `\{[\s\S]*?\}` 且要求独立成段（模型漏写三反引号时兜底）。
4. **验证**：独立测试确认 metric_card（嵌套数组）、chart（多层 series）、同行 JSON、裸标记四种用例均正确提取完整 JSON。
- parseJsonObject 保持：lastIndexOf('}') 提取主体 + 单引号转双引号 + 未引号键补引号（已带引号键与值内冒号不受影响）。

## [2026-08-16] 修复 component:metric_card 等标记不渲染（增强组件标记解析容错）
用户反馈"component:metric_card"——模型输出组件标记但界面显示原文未渲染：
1. **正则增强**：COMPONENT_BLOCK 改为 ` ```component:(\w+)\s*\{([\s\S]*?)\}``` `（显式匹配 {json} 主体，兼容多行/同行 JSON）；新增 COMPONENT_BLOCK_BARE 裸标记正则（模型漏写三反引号时兜底：`component:type {json}` 独立成段）。
2. **parseJsonObject 容错**：属性名补引号正则收紧（仅匹配 `{`/`,` 后紧跟字母的未加引号键，避免误伤已带引号键与值内冒号）；JSONObject 构造失败时逐字符清理未转义控制字符（换行/制表）再解析。
3. split() 支持代码块与裸标记双通道；containsComponent 同步。
- 修复目标：模型无论输出 ```component:metric_card\n{json}```、```component:metric_card {json}```、还是裸 `component:metric_card {json}`，都能渲染为组件。

## [2026-08-16] 修复恢复对话后工具组件渲染失败（null）
用户反馈"恢复对话后工具组件渲染失败，null"——根因：ComponentData 持久化用 JSONObject.toString() 字符串中转，经 Gson 双重转义（\\n 等）导致旧会话数据损坏/解析失败，渲染返回 null。
1. **持久化改为 Gson JsonObject 结构**：ChatHistoryManager 的 ComponentDataAdapter 重写——序列化时 org.json JSONObject/JSONArray 递归转为 Gson 树（orgJsonToGson），反序列化时 Gson 树转回 org.json（gsonToOrgJson），彻底消除字符串中转的双重转义问题。
2. **旧数据兼容**：deserialize 兼容旧字符串格式（isJsonPrimitive 分支）；ComponentData.fromPersistableJson 优先用 Gson JsonParser 解析（正确处理转义），失败再走容错 fromJson。
3. **渲染降级**：ChatAdapter.bindComponents / 组件标记渲染——组件数据损坏（null/props 缺失）或渲染失败时**静默跳过**，不再显示"组件渲染失败"占位；全部失败则隐藏组件容器。
- 修复笔误：org.jsonPropsToGson → ChatHistoryManager.orgJsonPropsToGson（org.json 被误当包名）。

## [2026-08-16] 在线 Agent 组件实时显示（工具组件不用等 Agent 完成）
用户问"在线 agent 会不会用"——验证后确认会，并增强实时性：
1. **链路确认**：工具 withComponent → AIToolManager.collect → completeGeneration drain 附加消息 ✅；模型输出 ```component:``` 标记 → ComponentContentSplitter 解析 → bindMessageContent 渲染（含流式 PAYLOAD_CONTENT_UPDATE 实时重建）✅。
2. **实时性增强**：appendAgentToolCall 工具完成分支即时 drain ComponentCollector 并附加组件到当前消息——list_card/info_card/image_grid 等随工具完成立刻显示，不再等 Agent 全部执行完；completeGeneration 的 drain 因取走即清空不重复。
- 现在在线 Agent：搜索 → list_card 实时出现；翻译 → info_card 实时出现；模型输出组件标记 → 流式实时渲染。

## [2026-08-16] Agent UI 组件扩充：新增 6 种组件 + 工具侧自动附加
用户反馈"Agent 可利用的 UI 组件太少，需要新增"：
1. **新增 6 个组件**（插件式注册到 ComponentRegistry）：
   - list_card 列表卡片（图标+标题+描述+右侧值，搜索结果/文件列表/条目）
   - alert_card 提醒卡片（success/warning/error/info 四色 + 左侧色条 + 图标）
   - metric_card 指标卡片（一行多个大数字+标签，Token/耗时/成功率）
   - json_viewer JSON 查看器（美化格式化 + 等宽字体 + 滚动上限）
   - steps_card 步骤流程卡片（done/current/todo/failed 状态指示）
   - note_card 便签/引用卡片（note/quote/tip/summary，左侧竖线 + 斜体）
2. **提示词更新**：OnlinePromptBuilder 组件指南补充 6 种新组件的 JSON 格式示例，引导模型优先用组件展示结构化数据。
3. **工具侧自动附加组件**（不依赖模型输出标记）：
   - NetworkSearchTool.search 成功时自动附加 list_card（搜索结果列表）
   - TranslationTool.translate 成功时自动附加 info_card（原文→译文）
4. ComponentColors 新增 success/warning/error 色（component_success/warning/error）。
- 现在 Agent 可用组件 18 种：chart/info_card/table/image_grid/link/quiz/weather/file/code/progress/tool_call + list/alert/metric/json_viewer/steps/note。

## [2026-08-16] Agent 组件补齐 5 项能力
1. **Agent 执行过程可视化**：AI 气泡内新增 agentStatus 状态行（🔍思考中(第N轮) → 🔧调用工具X → ✅完成），随 Agent 回调实时更新；ChatMessage 新增 agentStepStatus 字段，动态布局新增 agentStatus TextView，bindAgentStepStatus 绑定。
2. **工具中途打断**：OnlineAgentEngine 等待工具结果从 join() 改为 get(5s) 轮询 isCancelled，用户停止生成时取消剩余工具 future（cancel(true)）并立即返回，不再阻塞等待慢工具。
3. **Agent 任务结果汇总**：生成完成时在气泡内显示汇总（🔧调用工具N次 · 🧠思考N轮 + 工具名列表）；agentGroupToolCount 计数移到 appendAgentToolCall（在线 Agent 组件通道），新增 agentToolNames 集合记录去重工具名；ChatMessage 新增 agentSummary 字段 + 动态布局 agentSummary TextView。
4. **Agent 过程持久化**：修复 Gson 无法序列化 org.json.JSONObject（ComponentData.props）问题——ComponentData 新增 toPersistableJson/fromPersistableJson，ChatHistoryManager 注册 ComponentData TypeAdapter（序列化为 JSON 字符串，反序列化兼容新旧格式），工具卡片组件随会话保存/加载。
5. **工具使用统计展示**：Agent 汇总中展示本轮工具名列表（去重），作为 UsageTracker 统计的轻量 UI 呈现。

## [2026-08-16] AI 消息改为动态布局（不依赖布局文件与 id）
用户反馈"布局文件 id 错误，改为动态布局，不依赖布局文件"：
1. **完全动态构建**：ChatAdapter 新增 createAiMessageItem()，纯代码创建 AI 消息整个视图树（根容器 → 主气泡[思考区+工具卡片+正文] → 展开按钮 → 操作按钮 → 状态 → 模型信息 → 时间戳），彻底摆脱布局文件与 findViewById。
2. **DynamicAiMessageRoot**：动态根容器持有全部子视图引用，AIMessageViewHolder 直接取用（不再按 id 查找，杜绝 id 错乱/找不到问题）；删除 XML findViewById 回退分支。
3. 主题色（colorOnSurface/colorOnSurfaceVariant/colorOutlineVariant）用 resolveAttrColor 解析；点击水波纹 getSelectableItemBackground；操作按钮 createActionButton 统一创建。
4. **删除 item_ai_message.xml**（AI 消息已无布局依赖，该文件无其他引用方）。
- 思考区/工具卡片/正文仍全部包裹在主气泡（ai_message_background）内，视觉与上一版一致，只是改为代码生成。

## [2026-08-16] 思考过程插入主消息气泡（主消息包裹全部内容）
用户要求"思考过程插入主显示，主消息将模型回复全部包裹，不要单独思考布局"：
1. **布局重构**：item_ai_message.xml 新增 message_bubble 容器（承载 ai_message_background 主气泡背景 + 内边距），把 thinking_label / thinking_content / inference_progress_view / component_container（工具卡片）/ attachments_recycler / content_host（正文）全部移入——思考过程、工具调用、模型回复现在都在**同一个主气泡**内自上而下显示。
2. **思考内容去独立背景**：thinking_content 去掉 thinking_message_background（不再单独一个浅色块），文字用 colorOnSurfaceVariant 次级色与正文（colorOnSurface）区分；新增 thinking_divider 细分隔线在思考区与正文之间，思考展开时可见、折叠/无思考时隐藏（updateThinkingContent / expandThinkingContent / collapseThinkingContent 统一控制）。
3. message_text 去掉自身 ai_message_background 背景（背景上移到 message_bubble），保留正文文字样式。
- 现在视觉：一个主气泡内 = 思考标签 + 思考内容（次级色）→ 细分隔线 → 工具卡片 → 正文，主消息完整包裹模型回复全部内容。

## [2026-08-16] 在线 Agent 思考/工具调用合并到 AI 消息内嵌显示（去掉独立思考消息）
用户反馈"工具调用集合到思考过程中，在线 Agent 独立思考消息与本地模型内嵌思考区合并显示，单独显示不合适"：
1. **思考合并**：AgentCallbackImpl.onThinkingToken 不再创建独立 THINKING 消息（addThinkingMessage），改为写入 AI 消息内嵌思考区（appendAgentThinkingToken——此方法原本存在但从未接线，现启用），与本地模型完全一致：思考中展开显示、思考完毕折叠、可点击展开。
2. **思考结束处理**：新增 finalizeAgentThinking（思考内容写入 AI 消息 thinkingContent + 折叠），替代原 finalizeThinkingMessage（针对独立消息）。
3. **工具调用已单通道**：确认在线 Agent 模式下工具调用仅以组件形式显示在 AI 消息内（appendAgentToolCall），addToolCallMessage 被 currentAgentGroupId 拦截不建独立卡片——思考区在上、工具卡片在下、正文最后，天然合并显示。
4. **删除死代码**：addThinkingMessage / updateThinkingMessageUi / finalizeThinkingMessage / resolveThinkingIndex / currentThinkingMessageIndex / currentThinkingMessageId / lastThinkingUiUpdateTime；ChatMessage.createThinkingMessage / createThinkingRoundMessage（无调用方）；MessageType.THINKING 保留（兼容历史会话数据渲染）。
- 现在本地模型与在线 Agent 的思考显示完全一致：都在 AI 消息气泡内，思考区 + 工具卡片 + 正文自上而下。

## [2026-08-16] 思考过程改为主消息色系（去掉紫色）
用户反馈"紫色不好，改为主消息色系"：
1. thinking_message_background.xml：背景 @color/thinking_background（淡紫 #EDE9FE）→ ?attr/colorSurfaceVariant（与 AI 主消息气泡同色系浅灰）。
2. item_ai_message.xml + item_thinking_message.xml：思考标签/正文文字 @color/thinking_text（深紫 #6D28D9）→ 标签 ?attr/colorOnSurfaceVariant、正文 ?attr/colorOnSurface（与主消息文字一致）。
3. 删除死代码：colors.xml / values-night/colors.xml 中 thinking_background/thinking_text/thinking_label 三个紫色定义（已无引用）；item_chat_message.xml 死布局（无引用方）。
- 思考区现在与主消息同色系（浅灰底 + 深色文字），仅在圆角/字号上与正文区分。

## [2026-08-16] 统一思考过程组件布局 + 删除死代码
用户反馈"有好几个思考过程组件布局各不相同，本地模型的思考布局好，统一一下"：
1. **统一思考布局**：item_thinking_message.xml（在线 Agent 多轮思考独立消息）改为与 AI 消息内嵌思考区（item_ai_message 的 thinking_label/thinking_content，用户认可的那套）完全一致的视觉——同一 thinking_message_background、thinking_text 紫色、11sp label / 12sp 正文 / 1.2 行距 / 8dp 内边距、label 统一"💭 思考过程"；ThinkingMessageViewHolder label 文案同步（Agent 轮次保留"第N轮思考"）。
2. **删除死思考组件**：
   - agent_block_thinking.xml + AgentExecutionPanel.java（软件层 Agent 面板的思考块，布局早已从 item_ai_message 解绑、无实例化方）；
   - thinking_dots_view.xml（无任何引用）。
   - ChatAdapter 清理 agentExecutionPanel 字段及 PAYLOAD_AGENT_UPDATE/状态绑定中的死逻辑（该字段恒 null）。
3. 保留：AgentExecutionView（agent_execution_view.xml）——仅 AIImportActivity 导入进度展示使用，非聊天思考，不动。
- 现在思考 UI 只有两套且视觉统一：本地模型（AI 消息内嵌思考区）与在线 Agent（独立思考消息），样式一致。

## [2026-08-16] 修复对话消息乱显示/乱插入：全部改为按消息 id + toolCallId 定位
用户反馈"组件没有 id 判断导致乱显示乱插入、UI 渲染不按先后顺序、新对话 id 混用、发送消息位置错乱"：
1. **工具调用回调链传递 toolCallId**：AgentCallback.onToolCallStart/onToolCallComplete 接口增加 toolCallId 参数；OnlineAgentEngine 回调传 tc.id/tr.toolCallId（引擎内部原有，之前未透出）；AgentChatHandler.AgentChatCallback 同步。
2. **工具组件按 id 精确更新**：appendAgentToolCall 生成的 tool_call 组件 props 增加 toolCallId 字段，完成时按 id 匹配更新（不再"找最后一张 running 卡片"，修复并行工具/乱序更新错位）；ChatMessage.ToolCallInfo 增加 toolCallId 字段，createToolCallMessage 支持带 id；新增 findToolCallMessageById 按 id 更新独立工具卡片（替代 findLastSpecialMessage 猜位置）。
3. **消息定位全部按 id**：新增 findMessageIndexById + resolveStreamingIndex（优先按 currentStreamingMessageId 查找，回退索引）；safeUpdateMessage / safeUpdateMessageFromStreamingManager / completeGeneration / appendAgentToolCall / appendAgentThinkingToken / OutputRouter 回调（onTextOutput/onThinkingStart/onThinkingContent/onThinkingEnd/onError/onStreamComplete）/ onlineUpdateRunnable / handleGenerationError / onGenerationError / onGenerationStopped / StreamingTokenHandler.onError / handleStreamTokenLegacy / AITokenReceiver / AIResultReceiver / onInferenceProgress 全部改用 id 定位，不再依赖会漂移的 currentStreamingMessageIndex。
4. **思考消息 id 锚定**：新增 currentThinkingMessageId + resolveThinkingIndex，updateThinkingMessageUi/finalizeThinkingMessage 按 id 定位；各重置点同步清 thinking id。
5. **新对话 id 清理**：clearStreamingState / handleGenerationError / processChatMessage 各路径重置 currentThinkingMessageId。

## [2026-08-16] 对话折叠策略调整：思考中默认展开，思考完毕自动折叠；主回复全部展开
用户要求"思考过程中默认展开不要折叠，思考完毕后你再折叠；主回复默认展开，长内容不自动折叠"：
1. **思考过程**：ChatAdapter.updateThinkingContent 改为流式（GENERATING/IN_PROGRESS）时强制展开显示思考链，思考完毕后按 thinkingExpanded（默认 false）折叠；ThinkingMessageViewHolder.bind 同步（processing 时强制展开）。AIChatActivity.finalizeThinkingMessage 改为思考完毕一律折叠（原来"有内容保持展开"）。
2. **主回复**：handleLongContent 删掉长内容（>500 字符）自动折叠 + 展开按钮逻辑，全部直接展开（maxLines 无限）；删除无调用方的 applyExpansionState 方法。
3. 更新 ChatMessage.thinkingExpanded 注释（原注释"流式中保持折叠"已过时）。

## [2026-08-16] 清理在线 Agent 不可达假功能（暂停/恢复/参数验证 UI 链）
- OnlineAgentEngine 从不触发 onNeedMoreInfo/onExecutionPaused/onExecutionResuming/onInputValidationResult（在线引擎无暂停恢复概念），AgentChatHandler 中对应 4 个回调覆写 + showInputDialog/showValidationError 两个对话框方法 + getCurrentResponse/getCurrentThinking 空包装均为不可达假功能 → 全部删除。
- AgentRouter 删除 5 个空方法（getCurrentResponse/getCurrentThinking/getRetryCount/resumeExecution/cancelPause）。
- AgentCallback 接口删除 4 个无调用方 default 方法；物理删除 InputValidator.java（仅被死回调引用）。
- 删除 dialog_input_parameter.xml / dialog_validation_error.xml 布局 + strings.xml 中对应的 Agent 输入验证/校验错误字符串块（约 60 行，无引用）。
- AgentChatHandler 清理 9 个无用 import（AlertDialog/TextUtils/LayoutInflater/View/Button/EditText/LinearLayout/TextView/Toast/R）。

## [2026-08-16] 修复删除后编译错误 + 清理 OnlineAgentEngine 本地回退死代码
1. 修复删除 UnifiedAgentEngine 后 3 处编译错误：OnlineAgentEngine 补 `shutdown()` 方法；AgentChatHandler 两处 `engine.getCurrentMode()` 改为固定字符串"在线"（在线无推理模式概念）。
2. 物理删除 3 个死模式 handler：AgentModeHandler / CreativeWritingModeHandler / ThinkingModeHandler（均无引用方）；ChatOrchestrator 清理对应 import。
3. OnlineAgentEngine 彻底清除本地回退机制（约 170 行）：删 LocalFallbackHandler 接口、localChat/memoryManager/localFallbackEnabled/consecutiveOnlineFailures/isUsingLocalFallback/localFallbackHandler 字段、setLocalFallbackHandler/setLocalFallbackEnabled/isUsingLocalFallback/forceLocalFallback/resetFallbackState/shouldFallbackToLocal/canUseLocalModel/executeWithLocalModel/buildLocalPrompt/recordOnlineFailure/emergencyMemoryCleanup 方法及 3 处调用点（doExecute/主循环/streamOneIteration），删 ALChat/ModelMemoryManager import。在线失败现在直接 notifyError，不再回退。

## [2026-08-16] 删除本地 Agent 引擎（在线 Agent 独立化 + 代码物理删除）
用户要求"先改在线模式再删本地 Agent 代码"：
1. **在线独立**：OnlineAgentEngine.doExecute 删除本地回退分支（在线不可用直接报错，不回退本地）；AgentRouter 重写为纯在线路由（删 localEngine/本地委托/回退方法）。
2. **物理删除**：UnifiedAgentEngine.java + EnhancedAgentHandler.java + CreativeWritingEngine.java + ServiceRouter.java + TaskDecompositionManager.java（5 个本地 Agent 组件，均无外部引用）。
3. 引用修复：AgentChatHandler setReasoningMode 忽略（在线无推理模式）、import 清理；AgentRouter.setReasoningMode 兼容签名。
- 现在：在线模型 = 完整 Agent（OnlineAgentEngine）；本地模型 = 普通对话；深度思考 = 模式（思考指令）。本地 Agent 代码全部移除。

## [2026-08-16] 清理死代码（模式 chip / 对话框 / 本地 Agent 入口）
用户要求清理死代码：
1. 删除快捷工具卡片废弃模式 chip（Agent/创意/思考辅助）——layout activity_ai_chat.xml 删除 3 个 Chip；AIChatActivity 对应字段/findViewById/监听删除。
2. 删除 ModeSelectorDialog.java + dialog_mode_selector.xml（模式切换已改点击切换，不再使用）。
3. 本地 Agent 入口全部切断：AgentRouter.execute 本地分支不再执行（提示弃用）；UnifiedAgentEngine.execute 本地分支防御性返回——本地 Agent 方法体保留（被在线组件/外部引用，硬删有破坏风险）但运行不可达。
- 在线 Agent 完整保留（OnlineAgentEngine + AgentRouter 在线分支）。

## [2026-08-16] 模式切换改点击即切换（去掉复杂对话框）
- 用户要求：深度思考/普通对话切换改为点击切换，不用复杂画面。
- 顶部模式按钮（btnModeSelect）：点击直接在 普通 ↔ 深度思考 间切换（toggleMode），不再弹 ModeSelectorDialog 复杂对话框；按钮文本去掉"▼"。
- 删除 showModeSelectorDialog 方法 + import；ModeSelectorDialog 类保留（未被引用）。

## [2026-08-16] AI 对话模式重构（简化 + 删除本地 Agent 使用）
用户要求"清理对话模式、重构 UI、本地模型普通对话、在线完整 Agent、深度思考用指令"：
- ChatMode 从 5 个精简为 **2 个（普通/深度思考）**：删除 创意写作/Agent/思考辅助。
- 路由简化：`processChatMessage` = 在线模型 → 完整 Agent（processChatMessageWithAgent）；本地模型 → 普通对话（processChatMessageNormal）。AGENT 模式分支删除。
- UI：废弃模式入口隐藏（chipAgentMode/chipCreative/chipThinkingAssist + ModeSelectorDialog 的 3 个选项）。
- 深度思考：保留为模式（= 思考指令 + enableThinking），非独立行为。
- ChatOrchestrator.isAgentMessage 返回 false（Agent 不再独立模式）。
- 本地 Agent 引擎代码保留但路由不再调用（待后续清理）。

## [2026-08-16] 本地 Agent 引擎弃用（本地模型降级普通对话）
- 用户决定放弃本地 Agent（工具调用/思考链在 4B 上多轮修复仍不稳定）。
- 变更：AGENT 模式 + 本地模型 → 统一降级普通对话（提示"本地模型暂不支持 Agent 工具调用"）；在线模型 Agent 完整保留（processChatMessageWithAgent 仅在线路径）。
- 本地 Agent 引擎代码保留（可恢复），路由层不再调用。

## [2026-08-16] 推理慢排查：flash attention 改 AUTO（此前误判 AWQ）
- 更正：模型实为 Q4_K_M（文件名确认），"Awq" 是 general.name 元数据命名，非量化类型——上一轮 AWQ 判断错误。
- 慢的根因候选：flash attention 强制 ENABLED，Vulkan 后端 kernel 在部分 Adreno 上性能差（0.4 t/s 异常慢）→ 改为 AUTO（llama.cpp 自动判断，不支持的 GPU 回退标准 attention Vulkan kernel，通常更快）。
- 待验证：重载后速度是否提升。

## [2026-08-16] Agent 生成状态监控（解决"一直等待无反馈"）
- 现象：本地模型推理慢（0.4 t/s），Agent 生成期间 UI 无"处理中"反馈（状态栏只在思考/工具/完成时更新），用户感觉卡死等待。
- 修复：processChatMessageWithAgent 启动 Agent 前状态栏立即显示"⏳ 模型处理中..."（busy），后续 onThinkingToken（🧠 思考中）/onToolCallStart（🔧 调用）/onComplete（✅ 执行完成）正常覆盖更新——生成全程有明确状态反馈。
- 提示：0.4 t/s 为设备推理性能问题（Adreno GPU 未充分利用/设备档位），状态栏反馈已改善 UX；性能优化另计。

## [2026-08-16] KV 增量优化：工具循环后续轮次不再全量重解码
- 优化点（用户指定）：每轮全量重解码 prompt（1-2 秒/轮）→ KV 增量复用。
- 实现：C++ 新增 `nativeAppendMessagesAndGenerate`——只把 appendStart 起的新增消息（assistant/tool）按 Qwen3 im_start 格式手动渲染 → tokenize → 分块 decode 进既有 KV（不清 cache）→ 加 generation prompt → `generateFromEvaluatedContext` 从当前位置生成。
- Java：`executeNativeAgentLoop` 第一轮全量（decode system+历史+工具定义），后续轮调增量（只新增消息）；增量失败自动回退全量（committedMsgCount 重置）。
- 效果：多轮工具循环从"每轮 1-2 秒全量 decode"降为"只 decode 新增的百 token 级消息"，提速显著。

## [2026-08-16] Agent 继续优化：中文工具清单 + 表达式净化
1. system prompt 加**中文工具清单**（ai_weather=查天气/network_search=网络搜索/...）——模型不用理解英文模板工具说明，直接知道工具名和用途（配合 REQUIRED + 中文示例，工具调用链路完整）。
2. 参数净化扩展到 `python_calculate.expression`：剥离"请问/等于/多少"等中文干扰词 + 转换中文运算符（乘以→*、除以→/、加→+、减→-）+ 中文括号→英文括号，计算表达式更干净。

## [2026-08-16] 解码设计优化：n_batch 1024 + tool_choice REQUIRED
针对"本地库解码设计问题"的修复：
1. **n_batch 256 → 1024**（MAX_BATCH_SIZE）：此前 256 导致 prompt（1397 tokens）分 6 批 decode 变慢、且单批易超限崩溃；1024 覆盖常见 prompt 2 批内，GPU 吞吐更高。
2. **工具场景 tool_choice AUTO → REQUIRED**：generateWithTools 仅 Agent 阶段1（已判定涉及工具）使用，强制模型输出 tool_call（llama.cpp 对 Qwen3 支持 REQUIRED），解决"模型空输出/直接回答不调工具"。
3. 已知保留设计：生成逐 token 自回归（本质）、每次全量重解码 prompt（正确但慢，KV 增量可后续优化）。

## [2026-08-16] 修复 prompt 单批 decode 超 n_batch 的 SIGABRT（"思考链中断"真因）
- 日志：`prompt_tokens=1397 → Native inference crashed (signal 6)`——主循环生成崩溃，降级到直接回答（用户看到"思考链中断/没继续"）。
- 根因：`llama_batch_get_one(tokens.data(), 1397)` **单批 decode 全部 prompt**，而 context n_batch=256（GPU）→ **llama_decode 对 batch > n_batch 触发 assert → SIGABRT**。此前 2323 tokens 被"Prompt too long"先拦截没到 decode；现在 1397 走到 decode 即崩。
- 修复：generateStream / generateStream 另一路径 / NativeChatContext.encodeTokens / generateStreamWithImage 历史评估共 4 处 **prompt 分块 decode**（batch ≤ n_batch 循环 llama_decode）。

## [2026-08-16] 空输出根因：中文模型看不懂英文 FC 指令 → 加中文 tool_call 示例
- 日志定位：主循环模型生成空（第一个 token 即 EOS）——模板注入的 <tools> 工具说明是英文（"You may call one or more functions..."），中文 Qwen3-4B 看不懂要输出什么 → 直接 EOS 空输出。
- 修复：工具场景 system prompt 加**中文 tool_call 输出格式示例**（`<tool_call>{"name":"ai_weather","arguments":{"city":"北京"}}</tool_call>`），教模型输出格式，避免空输出。

## [2026-08-16] 提示词修正：去掉"现成话术"导致模型复述不调工具
- 日志真相：主循环（带工具 prompt）模型空输出 ×2 → 降级直接回答输出"暂时无法获取实时数据"（11 字符）——**模型复述了 system prompt 里的现成话术**（"无法获取时必须说明'暂时无法获取实时数据'"），而不是先调用工具。
- 修复：① 核心提示词去掉现成话术，改为"先通过工具获取真实数据，不编造；工具失败才如实说明"；② 工具规则精简为 3 条核心（不淹没 4B）；③ 保留"必须调用工具（缺参数也调用）"。

## [2026-08-16] 工具定义恢复完整（模型不调用工具的根因修复）
- 现象：AGENT 模式模型不调用工具，回复"暂时无法获取实时数据"（不编造规则生效但工具未调用）。
- 根因：工具定义被精简过度（ai_weather 描述仅"查天气"3 字、参数描述截断）——**4B 模型看不懂工具用途，自然不调用**。
- 修复：filterToolsForNative 恢复为**纯白名单过滤（保留完整原始定义）**——7 个工具全量定义约 1200-1500 tokens，预算足够（非思考 512 上限下 prompt+maxTokens 远小于 n_ctx）。

## [2026-08-16] AGENT 模式回退非思考 FC（优先保证工具调用）
- 现象：AGENT 模式启用 thinking 后，Qwen3-4B 思考完"忘记"调用工具（回复"无法获取实时数据"而非输出 tool_call）——thinking+FC 组合对小模型是难点（llama.cpp issue #20260 同类）。
- 修复：AGENT 模式 enableThinking 回 false（仅深度思考模式启用思考链）——非思考 FC 模式工具调用更稳定；深度思考模式（非 Agent）单独体验思考链。

## [2026-08-16] Agent 恢复应用内 UI 操作能力（app_operation）
- 用户反馈"本地 Agent 不能调用 UI"——根因：工具精简时把 `app_operation`（应用内部页面跳转工具）从白名单删除了。
- 修复：加回 `app_operation` 到 NATIVE_CORE_TOOLS（7 个工具）——支持 navigate 跳转页面（用户/题库/答题/错题本/OCR/AI 等）、list_pages、go_home/go_back、open_settings、share；Agent 现在能操作应用 UI。
- 同时保持精简（7 个高频工具），避免 4B 选择负担过大。

## [2026-08-16] Agent 自主思考修复（思考链 + 工具调用组合）
用户反馈"Agent 不能自主思考/无思考链/思考链中断/工具不调用"，三处设计修复：
1. **AGENT 模式默认启用思考链**（AIChatActivity）：此前 enableThinking 仅 DEEP_THINKING 模式为 true，AGENT 模式恒 false（ChatMode 单选互斥）→ 现在 AGENT 模式也开思考，模型先自主思考再决定工具。
2. **思考模式 maxTokens 放大 1024**（UnifiedAgentEngine）：此前工具循环 512，思考链 300-500 tokens 被截断（"思考链中断"）→ 思考模式 1024（思考+工具 JSON 空间足够）。
3. **工具精简到 6 个核心**（天气/搜索/计算/翻译/定位/文件读取）：4B 对 11 工具选择负担大（选错/不调用），减半降低负担 + 释放上下文空间（工具定义 tokens 减半）。

## [2026-08-16] 提示词系统分层设计（场景差异化）
- 拆分提示词为可组合层：`buildNativeSystemPromptCore`（L1 角色 + L2 动态时间环境，所有场景）+ `buildNativeSystemPrompt`（Core + L3 工具协议，仅工具场景）。
- 场景注入：工具循环（阶段1）用完整版（L1+L2+L3）；直接问答/干净回退（阶段2）用精简版（L1+L2，无工具协议，模型更专注回答）。
- L4 历史（跨轮）与 L5 请求保持既有注入。

## [2026-08-16] 本地 Agent 引擎重新设计（推翻旧设计）
参考 small-model-agent-skill 协议（一次一任务、工具少而精、尽早总结、失败快速降级），重写本地 Agent：
- **阶段0 意图分流**（规则，零 LLM 成本）：消息含工具域关键词（天气/搜索/计算/翻译/定位/文件/系统/应用）或历史有工具上下文 → 工具循环；纯聊天直接问答（`executeNativeDirectAnswer`，不走工具减少空输出）。
- **阶段1 工具循环**（原生 FC）：≤4 轮（原 10 轮，小模型长循环退化）、每轮 maxTokens 512（工具 JSON <200）、空响应连续 2 次直接退出到阶段2（原 6 次+降级重试）。
- **阶段2 保证输出**：直接问答/干净回退用原始问题普通生成，仍空才返回明确错误信息。
- 保留：跨轮记忆、工具精简、参数净化、结果回喂、system prompt 分层设计。

## [2026-08-16] 移除模糊回退话术，直接返回错误信息
- buildNativeFallback 无工具结果时不再返回"我暂时无法提供详细回答"模糊话术，改为明确错误信息："处理失败：本地模型未能生成回复...建议重发/检查模型/重新下载"——真实告知用户失败原因。

## [2026-08-16] 回退 repeat penalty（疑似导致主循环全空输出）
- 现象：加 penalties sampler 后主 FC 循环每轮 EMPTY（模型什么都不生成，6 轮全空）。回退 4 处 penalties（repeat 1.1）——重复问题另用 Java 侧方案（重复检测/截断）或后续调参数。
- 同时确认：工具结果回喂链路存在（history tool role + 下一轮传递），但**模型当前连 tool_call 都不输出**（主循环空），需先解决"空输出"才能验证工具回喂。

## [2026-08-16] Agent 工具调用强制规则（缺参数也要调用工具）
- 问题：模型把"缺参数"当成不调用工具的理由（"今天天气怎么样"→直接回复"请提供城市"而非调用 ai_weather）——应走工具链路：调用工具 → 工具返回"缺少城市参数" → 模型转告用户。
- 修复：system prompt 工具规则明确——涉及天气/搜索/计算/翻译/定位等**必须调用工具，即使参数不全也要调用**（工具返回缺什么）；工具提示缺参后转告用户，不编造结果。

## [2026-08-16] 模型输出重复修复：sampler 加重复惩罚
- 现象：模型回复"请直接询问天气情况...×2"重复两遍。根因：C++ 所有 sampler chain（generateStream/generateFromEvaluatedContext/chatSend 共 4 处）只有 top_k/top_p/temp/dist，**无 repeat_penalty**——4B 模型无惩罚易输出重复。
- 修复：统一加 `llama_sampler_init_penalties(n_vocab, 64, 1.1, 0, 0)`（repeat 1.1 温和抑制，工具调用 JSON 不受影响）。

## [2026-08-16] Agent 流程系统性重设计（提示词/上下文/工具系统）
按分层设计重构本地 Agent：
- **上下文注入系统**（5 层）：L1 角色 + L2 当前时间环境（"今天/明天"有基准，天气查询才正确）+ L3 工具协议一句话（与模板原生 FC 一致）+ L4 跨轮历史（多轮记忆）+ L5 当前请求。
- **工具系统**：白名单 11 工具精简定义；参数描述智能截断（枚举型如 action 保留完整 ≤60，其余 ≤20）；工具结果回喂带工具名前缀 `[ai_weather] 结果`（模型识别来源）；参数净化 + 失败结果原样回喂（自纠错）。
- **Agent 循环**：组装上下文 → 生成(≤1024) → 解析 → 有工具执行回喂循环≤10 / 无工具最终答案 / 空响应降级 / 上限干净回退。

## [2026-08-16] 本地 Agent 设计修复：跨轮对话记忆（多轮上下文）
用户反馈"肯定是设计问题"——确认根因：executeNativeAgentLoop 每次调用新建 history（只有 system+当前 user），**无跨轮记忆**——"今天天气怎么样"→补"银川"时模型不知道前文（天气意图），只能重复输入（"银川银川"）。
- 修复：新增 `nativeConversationHistory` 跨轮历史（实例字段）：每次 Agent 调用时 history = system + 之前轮次的 user/assistant + 当前 user；结束时 `rememberNativeTurn` 记录本轮 user+assistant（裁剪 NATIVE_MAX_HISTORY）；`clearContext`/新对话清空。
- 效果：多轮对话（问天气→补城市→再问）模型能结合前文正确调用工具。

## [2026-08-16] 本地 Agent 框架流程修复（3 处核心 bug）
用户反馈"agent框架及流程有问题"，日志还原三处缺陷：
1. 🔴 **emptyResponseStreak 跨调用泄漏**：实例字段未在每次 executeNativeAgentLoop 开头重置，前次残留值（10/11）导致主 FC 循环只跑 1 次就被误判"连续空响应"直接 abort，全部依赖 clean fallback（用户看到的"请提供具体城市名称"/"银川银川"都是 fallback 输出）。已重置。
2. 🔴 **token 预算低估 + 上限过大**：估算 promptEst 1419 vs 实际 1676，budget 4341 仍超 n_ctx 5888（1676+4341>5888）→ 主循环"Prompt too long"。工具调用轮输出 <200 tokens，直接上限 1024，彻底消除超限。
3. 🟡 **推理速度异常**：0.125-0.4 t/s（8 秒生成 1 token），clean fallback 95 tokens decode 2.7s——疑似设备（Redmi Note 13, Mali GPU）Vulkan 后端问题，待验证。

## [2026-08-16] 本地 Agent 回退流程修复：失败时重新问模型，不再直接硬编码
- 问题：Agent 达到最大迭代（工具链路失败）时直接返回 buildNativeFallback 硬编码文本（"关于...无法提供详细回答"），未利用模型本身的问答能力。
- 修复：新增 generateCleanFallback——失败时用**原始问题 + 精简历史**（不带工具定义）重新调用模型生成真实回复；仅当模型也未能生成时才用硬编码兜底。

## [2026-08-16] 思考组件：默认折叠、有思考内容自动展开
- 行为调整：思考组件默认折叠（已有 thinkingExpanded=false）→ 思考 token 流式到达时**自动展开**显示思考链 → 思考结束保持展开（用户可手动折叠；无内容才保持折叠）。
- Agent 模式修复：此前 Agent 模式（currentAgentGroupId 非空）思考消息被拦截不插入（addThinkingMessage return -1），本地 Agent 原生 FC 的思考链丢失——现在所有模式都插入思考组件，与工具卡片互补。

## [2026-08-16] 修复 system prompt 与原生 FC 冲突
- 问题：buildNativeSystemPrompt 引导模型"用 JSON 文本输出工具调用" + 中文工具名（"天气/网络搜索"）——与 C++ chat template 注入的原生 FC 格式（<tool_call> XML）和真实工具名（ai_weather 等）冲突，模型收到两套矛盾指令可能困惑/用错名字。
- 修复：system prompt 精简为只保留角色 + 参数精确性约束；工具调用格式与工具列表完全交给模板（原生 FC 路径），消除冲突。

## [2026-08-16] 本地 Agent 系统性修复（5 层，根治空响应）
问题链：Agent prompt（11 工具 JSON ≈2323 tokens）+ maxTokens 4096 > n_ctx 5888 → "Prompt too long" → 连续 10 次空响应 → 兜底"无法提供详细回答"。
1. **工具定义精简**（根治）：filterToolsForNative 现在压缩工具定义为短描述 + 精简参数（描述≤12字），prompt tokens 从 ~2323 降至 ~800，与 4096 maxTokens 叠加也不超 n_ctx。
2. **Agent token 预算**：computeGenerationTokenBudget 用真实 n_ctx（llama_n_ctx，含 memoryPool 预算钳制，此前误用 contextSize 字段导致预算失效）计算安全 maxTokens。
3. **空响应自动降级**：连续 2 次空响应 maxTokens 减半（≥256）；连续 6 次提前中止，不再白跑 10 轮。
4. **错误显性化**：C++ "Prompt too long" 等错误经 onError 回调打印（此前静默空响应）。
5. **KV 回退 F16**：Q8_0 在部分设备解码 SIGABRT，暂不自动启用（API 保留）。

## [2026-08-16] Agent 推理 SIGABRT 崩溃修复（KV 回退 F16 + n_ctx 修正）
- 崩溃日志：`Processing prompt as single batch, size=2321 → Fatal signal 6 during inference`——prompt 解码时 SIGABRT（abort，非 C++ 异常，try-catch 无法捕获），每次 generateWithTools 都崩。
- 判断：Q8_0 KV 量化在部分设备**解码时**触发 kernel assert（创建时不崩、解码时崩）。
- 修复：AIService 不再自动启用 Q8_0（KV 恒 F16，最稳；setKvCacheType API 保留供设备白名单手动开启）。
- 顺带修正：Agent token 预算改用新 JNI `nativeGetModelNctx`（主 context 真实 n_ctx，此前 getContextSize 在 chat 未创建时回退 Java 缓存 16384 导致预算失效仍传 4096）。

## [2026-08-16] 本地 Agent "无法提供详细回答" 根因修复（token 预算）
- 根因（日志确认）：`Prompt too long: 2323 tokens + maxTokens 4096 > n_ctx 5888`——Agent 原生 FC 的 prompt（system + 11 工具定义 JSON ≈ 2323 tokens）加上默认 maxTokens 4096 超出 n_ctx 5888，每次生成前被拒 → 连续 10 次空响应 → 走兜底消息"关于...我暂时无法提供详细回答"。
- 修复：`UnifiedAgentEngine.computeGenerationTokenBudget`——每次生成按 `n_ctx - 估算prompt tokens - 128保留` 动态计算 maxTokens（拿不到 n_ctx 时保守 1024）；防止超限空跑。
- 预期：budget ≈ 5888 - 2323 - 128 ≈ 3400（工具调用轮足够），不再触发 Prompt too long。

## [2026-08-16] 回退内存激进改动（保留崩溃修复）
- 按用户要求回退三处内存改动（恢复原始加载行为）：
  1. KV Q8_0 阈值 4500→2000 的改动回退（4B 恢复 F16 KV）
  2. 上下文按可用内存降档逻辑移除（恢复模型尺寸感知档位）
  3. 加载前内存预检（拒绝加载）移除
- 保留：Vulkan 异常捕获 + Q8_0→F16 回退（native 崩溃修复，必要）、KV 量化机制本身（setKvCacheType API）、8B 的 Q8_0 场景（≥4500MB 仍生效）。

## [2026-08-16] 防 LMK 杀进程：加载前内存预检
- 背景：非应用主动退出，是 Android LMK 在内存超限时强制杀进程；现有 onTrimMemory 防护在模型加载期间无效（释放逻辑检查 isModelInitialized，加载完成前为 false，且与加载线程并发竞态）。
- 修复：loadModelLocked 在 initModel 前做内存预检——预期峰值（权重 + KV(contextSize×70/140KB每token) + 400MB 余量）> 可用内存时**拒绝加载**并明确提示"清理后台/换小模型"，不再硬加载到被 LMK 杀。
- 叠加既有 KV Q8_0 强制 + 上下文按内存降档，4B 预期峰值 ~3GB < 6GB 可用，可稳定加载。

## [2026-08-16] 应用自动关闭修复（模型加载内存被杀）
- 根因（日志确认）：HyperSentinel 显示模型加载时 Rss 峰值冲到 4.47GB（Qwen3-4B 权重 2.3GB + F16 KV 1.2GB + 加载峰值），设备 12GB 但 MIUI 后台占用后可用仅 6GB → 内存压力震荡 → 系统 LMK 杀进程（"自动关闭"）。
- 修复（AIService）：
  1. KV Q8_0 阈值 4500MB → 2000MB（4B 模型也强制 Q8_0，KV 1.2→0.6GB；shader 不兼容时 native 自动回退 F16，无风险）
  2. 上下文按可用内存降档：可用 <4GB → 上限 6144；<6GB 且模型 ≥2GB → 上限 8192（叠加模型尺寸档位取最小）
- 预期：4B 常驻约 2.9GB（原 3.5GB），峰值显著下降，12GB 设备（可用 6GB）可稳定加载。

## [2026-08-16] 上下文初始化失败崩溃修复（Vulkan 异常捕获）
- 根因（日志确认）：`AdrenoVK-0: Failed to link shaders → Pipeline create failed → libc++abi: terminating due to uncaught exception (vk::SystemError: createComputePipeline: ErrorUnknown)`——ggml Vulkan backend 在 Adreno 750 上 shader pipeline 创建失败抛出 C++ 异常，native 层未捕获 → 进程崩溃（用户见"上下文初始化失败"）。
- 修复：native-lib.cpp 的 `llama_init_from_model` / `llama_model_load_from_file` 全部调用点包 try-catch（含首次、CPU 回退、CPU 重载）；主 context 创建失败且 KV 为 Q8_0 时**自动回退 F16 KV 重试**（Q8_0 KV shader 在部分 GPU 不兼容），仍失败走既有 GPU→CPU 回退。
- 应用侧无需改动：AIService 加载失败已有错误提示。

## [2026-08-16] 日志审查修复
- 修复 AILogger EPERM：`getLogDirectory` 在 appContext 未初始化（LlamaHelper 类加载早于 AILogger.init）时走外部存储 /storage/emulated/0/OilQuiz/ → Android 13+ 无权限 EPERM 刷错误日志。改为返回 null（logcat-only），AILogger.init 后落到应用私有目录（getFilesDir，无需权限）。
- 温度修复验证：GpuCapabilityDetector.getTemperature 已归一化毫度 + 按 type=cpu/gpu 选择传感器 + 范围校验，性能面板温度显示正常。

## [2026-08-16] 本地多模态识别改为 GGUF 权重元数据（不再靠模型名）
- 新增 JNI `nativeGetModelArchitecture` → `LlamaHelper.getModelArchitecture()`：读模型 `general.architecture` 元数据（llama_model_meta_val_str，兼容旧 GGUF 回退 general.name）。
- `AIService.autoLoadMultimodalIfAvailable` 重写：① 读架构，非视觉架构（如 qwen2.5/gemma3 文本版）直接跳过 mmproj；② 视觉架构（qwen2vl/qwen3vl/gemma3v/llava/mllama/minicpmv/glm4v/internvl 等 24 个）才尝试；③ 预设配对优先，配对失败**逐个尝试目录所有 mmproj**（loadMultimodal 成功即匹配，解决多 mmproj 场景，失败安全返回 false 不崩）。
- 配合之前的 mmproj 加载成功判定（isMultimodalLoaded），本地视觉能力 = 架构元数据 + 投影加载成功双确认，完全脱离文件名/模型名依赖。

## [2026-08-16] 在线视觉能力识别：能力字段真实化（不再只靠模型名判断）
- 说明：在线模型没有权重文件可读，视觉能力只能靠配置/模型名。发现 `OnlineModelConfig.supportsVision` / `capabilities.supportsImageInput` 是死字段（从未赋值、未持久化）。
- 修复：`OnlineModelManager` 加载模型列表后 `refreshAllSupportsVision()` 按模型名自动填充 supportsVision（新增静态 `isVisionModelName`）；save/load 持久化 supportsVision/supportsCode。
- `AIChatActivity.isOnlineVisionModel` 改为**配置能力字段优先**（hasCapability("vision")），模型名关键词仅兜底——为未来"用户手动设置视觉能力"留出位置。

## [2026-08-16] 在线多模态增强：在线视觉模型直接看图（base64）
- 此前在线模型图片走 OCR 文本注入 Agent，在线视觉 API 未利用。现增强：
- `OnlineInferenceService.generateStreamWithImages` + `callOpenAIAPIWithImages`：图片 base64 以 OpenAI 多模态 content 数组格式注入最后一条 user 消息（[{type:text},{type:image_url}]），支持 Qwen-VL/GPT-4o 等兼容模型；Anthropic 端点明确报错提示。
- `AIChatActivity`：ViaAgent 附件路径新增在线视觉分支——`isOnlineVisionModel()`（模型名含 vl/vision/4o/omni/gemini/glm-4v）+ 单图 → `handleOnlineMultimodalImage`（base64 ≤4MB → 流式看图回答）；图片过大/读取失败/异常回退 OCR+Agent。
- 最终图片处理优先级：本地多模态（离线免费）→ 在线视觉（直接看图）→ OCR 文本 + Agent（兜底）。

## [2026-08-16] 本地/在线多模态配合策略修正
- 发现主发送路径分支问题：有图片附件时走 `processMessageWithAttachmentsViaAgent`（OCR 文本 + Agent），本地多模态分支此前加在无图片才走的 OCR 路径上——实际永不触发。已把本地多模态判断移到 ViaAgent 的 thenAccept（正确位置）：vision 模型 + mmproj + 单图 → 直接 `handleMultimodalImage` 本地视觉理解；否则 OCR + Agent。
- 配合策略：本地多模态优先（离线免费），在线模型场景图片走 OCR 文本注入 Agent（在线视觉 API 未利用，为现状，可后续增强），两者互斥不冲突。

## [2026-08-16] 清理构建残留（810MB）
- 删除无引用的 CMake 残留目录：`src/main/cpp/build/`（392MB）、`build-android/`（208MB）、`build-test/`（210MB）——Gradle 实际用 `.cxx/`，CMake 产物输出到 `src/main/jniLibs/`（CMakeLists LIBRARY_OUTPUT_DIRECTORY），三个 build* 目录均无脚本/配置引用。
- 删除死脚本 `build_android.sh`（引用不存在的根目录 llama.cpp，git clone 目标路径错误）与 `build-native.sh`（路径指向另一个项目 /d/quzp/）。
- 修正 `build.gradle` 过时注释：原"本地库分离到 msys2 预编译"与现状不符（Gradle externalNativeBuild 直编，jniLibs 是实时产物）。
- 清理后验证构建正常（CMake 走 .cxx 重新配置）。

## [2026-08-16] mmproj 精确配对 + 切换模型自动卸载
- 修复两个问题：
  1. mmproj 匹配错配：原实现扫描目录任意 `*mmproj*.gguf`——目录有多个投影（gemma-3 + qwen2.5-vl）时可能加载错误的投影文件。改为：先按预设（模型下载文件名 → mmprojUrl 文件名）精确配对；目录仅一个 mmproj 时兜底；多个则跳过不猜测。
  2. 切换模型残留：非 vision 模型加载时不释放旧 mmproj → `isMultimodalLoaded()` 误报 true，发图会用错投影。改为 `autoLoadMultimodalIfAvailable` 开头强制 `releaseMultimodal()` 清除旧投影。
- 卸载链确认：`nativeRelease` 先释放 s_mtmdCtx 再删 InferenceContext——`LlamaHelper.release()`（模型卸载/重载/切换时调用）会连同 mmproj 一起卸载；重载后按新模型重新配对加载。

## [2026-08-16] 多模态模型接通主流程（图片附件直接视觉理解）
- 现状核查：native 多模态链路（mmproj 加载 + mtmd 图片编码 + generateWithImage）此前完整但未接入主流程——AIService 加载 vision 模型不加载 mmproj（仅 MultiModelManager 有，但无 UI 入口）；UI 图片附件统一走 OCR 文本路径，generateWithImage 无调用者。
- 接通 1（AIService）：模型加载成功后自动扫描模型目录 `*mmproj*.gguf`（下载预设时已自动下载）并 `loadMultimodal`，失败仅日志不影响主流程。
- 接通 2（AIChatActivity）：图片附件发送时，若 `isMultimodalLoaded() && 模型已加载 && 单张图片` → 新 `handleMultimodalImage` 走 `generateWithImage` 流式视觉理解（用户消息 + AI 流式消息 + 完成/错误处理 + 历史保存）；否则回退原有 OCR 附件路径。
- 崩溃防护：native 有 s_mtmdMutex + 图片加载/编码失败返回错误码 + 超上下文清 KV；Java 侧 generateWithImage 共用推理写锁 + try-catch 兜底提示，不会崩。
- GPU 层数说明：mtmd_context_params 无独立 GPU 层数（仅 use_gpu=true 视觉编码全 GPU）；文本部分仍用 gpuLayers，视觉编码器由 mtmd 内部管理。

## [2026-08-16] 手动 GPU 层数模式下的智能建议
- 手动设置 GPU 层数时，性能监控同样给建议：按模型尺寸估算推荐区间（8B级 10~30 层且可用内存<2.5GB 时上限 20；3-7B 15~30；小模型 10~25），对比手动值——
  - 手动过高（超推荐上限/温度≥45°C）→ 提示降低（可一键执行）
  - 手动偏低且 GPU 空闲 → 提示提高或「恢复自动」
  - 手动合理 → 提示当前配置正常
- 新增动作 `restore_auto`：删除 gpu_layers_manual + 重载模型，恢复自动计算；一键优化不触碰手动设置（不静默删除）。
- RuntimeSnapshot 增加 manualGpuLayers 字段（-1=自动模式）。

## [2026-08-16] 配置竞态审查 + 手动 GPU 层数优先级修复
- 审查结论：活着的配置设置链唯一（loadModelLocked 内 modelInitLock 单线程串行，顺序确定）；`applyXiaomi14Optimizations`/`applyAdrenoOptimizations`/`GpuAdaptiveTuner.startTuning` 均为死代码（无调用者），不会造成竞态。
- 修复真问题：用户手动 GPU 层数会被自动计算覆盖（重载时 loadModelLocked 用新计算值覆盖用户设置）。
- 实现：独立持久化 key `gpu_layers_manual`（与 ModelStateCache 自动保存的 gpu_layers 隔离）——存在则加载时优先（钳制 0-30），不存在用自动计算；状态页对话框新增「恢复自动」（删除 key + 重载）；性能面板 ±10 也写该 key。

## [2026-08-16] memoryPoolSize 真正利用：作为 KV cache 内存预算
- 此前 memoryPoolSize 是死配置（native 只存值不参与分配；AIService 902 行算了也只打日志）。
- 实现（native-lib.cpp）：context 创建时按模型参数估算每 token KV 字节（2×n_layer×n_head_kv×head_dim×元素字节，Q8_0=1/F16=2），若 memoryPoolSize 预算超出则钳制 n_ctx，并日志输出预算/峰值 KV 内存。
- Java（AIService）：initModel 前真正 `setMemoryPoolSize`（此前只用于日志），预算 = min(可用内存×30%, 2048MB, ≥256MB)。
- 效果：如 8B 用 F16 KV 且可用内存低时，memoryPoolSize 预算会自动把 n_ctx 从 6144 降到预算允许值，防止 KV 撑爆内存。

## [2026-08-16] 内存优化：KV cache 量化（Q8_0，内存减半）
- 发现 `fKvCacheType` 是死配置：InferenceConfigManager 设了 KV 类型但 native 层从未应用（context 始终 F16 KV）。
- 实现：C++ InferenceContext 新增 `kvCacheType`（0=Q8_0 省一半内存 / 1=F16 默认）+ JNI `nativeSetKvCacheType`；context 创建时按类型设置 `type_k/type_v`。
- Java：`LlamaHelper.setKvCacheType`；`AIService.loadModelLocked` 在 initModel 前自动选择——模型 ≥4.5GB（8B 级）或可用内存 <2.5GB → Q8_0（8B@6144ctx：KV 0.9GB→0.45GB；4B@8192ctx：1.2GB→0.6GB），否则 F16。
- 备注：`memoryPoolSize` 同样为死配置（native 只存值不参与分配），无实际内存影响，暂不动。

## [2026-08-16] AI 服务初始化流程审查修复（3 处）
- 🔴 **竞态 bug（高）**：`release()` 走多线程 executorService、`initializeAsync()` 走 modelInitSerialExecutor，两个线程池并发抢 modelInitLock 顺序不定——此前 GPU 重载/性能面板用的 `release()+initializeAsync()` 组合可能"initialize 先完成、release 后执行"导致模型被卸载却提示"重载完成"。新增 `AIService.reloadModelAsync(callback)`：同一串行执行器内原子完成 release→initialize；AIServiceStatusActivity 与 PerformanceDashboardFragment 改用它。
- 🟡 **chat context 大小脱节（中）**：`tryCreateChatContextWithFallback` 从 16384 开始尝试，与模型加载上下文（如 8B→6144）脱节（C++ 侧 min 钳制使前两次尝试无效）。首值改为 0（native chatCreate 对 ctxSize≤0 直接取模型 n_ctx），失败再逐级降级。
- 🟢 **硬编码等待（低）**：initChatContext 销毁 chat context 后固定 `Thread.sleep(100)` 改为轮询 isModelInitialized（最多 2 秒）。
- 确认无问题项：chat context 复用主 llama_context（`initFromExistingContext` ownsContext=false，无第二份 KV cache）；applyOptimizations 不设 GPU 层数（由 loadModelLocked 统一计算）；模型按需加载设计（构造函数不自动恢复）符合预期。

## [2026-08-16] 性能监控系统：实时检测 + 规则自动分析 + 可执行建议（死代码真实化）
- 原 PerformanceDashboardFragment 为死代码（Random 模拟数据 + 空方法 + 无宿主），现改为真实系统：
- 新增 `PerformanceRuleEngine`：输入实时运行快照（TPS/GPU 层数/GPU 状态/温度/系统内存/模型大小/上下文占用），规则集自动判定异常（内存高压/推理过慢/GPU 未启用或异常/设备过热/模型偏大/上下文将满/GPU 层数可提升/模型未加载），按优先级输出建议。
- Fragment 真实化：3 秒采样 LlamaHelper（getInferenceSpeed/getGPULayers/isGPUWorking/isModelInitialized）+ GpuCapabilityDetector（getTemperature/getMemoryUsageInfo）+ AIService（getContextUsagePercent/getCurrentModelName），性能评分由真实指标计算，TPS 图表改为本地 Canvas 绘制（去掉外部 CDN 依赖）。
- 建议一键执行：提高/降低 GPU 层数（±10 → 持久化 + 重载模型）、清理对话上下文、切换模型、重载模型；「一键优化」自动应用所有高/中优先级可执行项。
- 新增 `PerformanceActivity`（容器，manifest 注册），AI 服务状态页新增「性能监控（实时检测 & 优化建议）」入口按钮。

## [2026-08-16] GPU 层数设置 + 自动重载模型（让手动改 GPU 层数真正生效）
- 问题：`nativeSetGPULayers` 运行时仅赋值不生效（层数在 initModel 时固化），用户手动改 GPU 层数后无感知。
- 新增：AI 服务状态页「GPU 层数」输入行（0-30，0=纯CPU）+「应用并重载」按钮——保存到 `model_state_cache/gpu_layers`（与 ModelStateCache 同 key，重启恢复沿用）→ `setGPULayers` → 确认对话框 → `release()` + `initializeAsync()` 重载模型；模型未加载时保存待下次加载生效；状态页刷新时同步输入框显示。
- 8B 等大模型用户可通过此入口手动调整 GPU 层数（如降层减少显存压力或升层加速），改完即重载，无需重启应用。

## [2026-08-16] 8B 大模型加载卡顿优化（模型尺寸感知上下文）
- 问题：小米 17（12GB）加载 Qwen3-8B（Q4_K_M≈4700MB）后明显卡顿。根因：设备档位判定（FLAGSHIP→8192 ctx）不看模型尺寸——8B 常驻内存 = 权重 4.7GB + KV(8192)≈1.2GB ≈ 5.9GB，可用内存波动时触发 GC/杀后台。
- 修复：`AIService.calculateOptimalContextSize` 与 `InferenceConfigManager` 档位均加模型尺寸感知——模型权重 ≥4500MB 上下文上限 6144（KV≈0.9GB，常驻 5.6GB）、≥7000MB 上限 4096、≥3000MB 上限 8192。
- GPU 层数链路确认：加载时 `setGPULayers` 在 `initModel` 前设置生效（Vulkan 混合推理 30 层 + CPU 余层）；`nativeSetGPULayers` 运行时仅赋值不生效是已知设计（需重载才变）。

## [2026-08-16] 本地 Agent 模型更换：2507 → 原版 Qwen3-4B（思考链+FC 双全）
- **决策依据（联网核实）**：Qwen 官方 2507 分家为 Instruct-2507（模板无思考链）与 Thinking-2507（`<think>` 标签缺失 bug）；且 llama.cpp [issue #20809](https://github.com/ggml-org/llama.cpp/issues/20809) 确认 Instruct-2507 工具调用被 false thinking detection 破坏——2507 系列不满足 Agent 需求。
- 预设替换：`qwen3-4b-2507` → `qwen3-4b`（Qwen/Qwen3-4B-GGUF 官方原版 Q4_K_M，2.33GB，40K 上下文）。
- 模板核验（hf-mirror API 获取 GGUF 内嵌模板全文）：原版含 `<think>` 思考渲染 + `enable_thinking` 检测分支 + `<tools>`/`<tool_call>`/`<tool_response>` 完整原生 FC——`common_chat_templates_support_enable_thinking` 将通过，**深度思考模式与工具调用同时生效**，C++ 解析/回喂链路无需改动。
- 设备 Redmi Note 13 5G（25113PN0EC）4B 尺寸友好（6/8/12GB 内存均可）；8B 作为高性能备选仍在预设。

## [2026-08-16] 本地 Agent 工具参数可靠性（双保险）
- 事实确认：Qwen3 官方将 2507 拆为 Instruct-2507（无思考链，工具调用特化）与 Thinking-2507（带思考链但有模板 bug）；本项目用 Instruct-2507，enableThinking 被 C++ 模板检测正确关闭，非配置问题。
- 第一层（减少犯错）：本地 Agent 系统提示加入参数约束（city 只写纯城市名、禁止天气/今天等修饰词；expression 只写表达式）。
- 第二层（纠正犯错）：新增 `sanitizeNativeToolCall` + `sanitizeCityName` 规则净化——ai_weather.city 剥离"今天/天气/怎么样"等噪声词、去"市"后缀，净化结果非空才生效（否则保留原样交给工具失败回喂纠错），执行前替换参数，日志可见"参数净化 city xxx → yyy"。
- 参数捕获缺陷修复：C++ 解析的 arguments 非合法 JSON 时不再静默清空，先 `tryFixArguments` 剥外层引号（处理字符串化 JSON），仍失败才置 {}。

## [2026-08-16] 修复本地 Agent 第三层静默降级（AgentRouter）
- **重大修复**：`AgentRouter.execute` 本地模型分支此前「静默降级」直接 return 不执行——UI 两层降级虽已移除，但引擎路由层这第三层拦截导致本地 Agent 全部调优（原生 FC、思考链、温度 0.7）均为死代码，用户点发送无任何反应。
- 现改为本地模型 → `UnifiedAgentEngine.execute` → 原生 FC Agent 循环（generateWithTools），完整链路打通：
  `AGENT模式 → processChatMessageWithAgent → AgentChatHandler.startAgentLoop → AgentRouter(本地分支) → executeNativeAgentLoop（原生FC + 思考链流式 + 温度0.7）`。
- 本地意图识别确认：原生 FC 路径完全由模型原生完成意图/任务/工具选择，无本地硬编码规则；规则识别（SmartIntentRecognizer）仅用于在线 ReAct/CoT/Plan 路径。

## [2026-08-16] 本地 Agent 针对 Qwen3-4B-2507 调优
- 本地 Agent 原生函数调用路径针对 Qwen3-4B-2507 调优：深度思考模式（enableThinking）透传至原生生成（此前本地路径忽略思考链）；温度固定 0.7 平衡工具调用稳定性与多样性。
- Qwen3 思考过程流式展示：原生 FC 循环中模型输出的 thinking 内容分块回调 UI（🧠 思考中状态栏 + 思考消息块，节流渲染），结束后折叠；此前思考内容只写日志不展示。
- C++ 层确认：generateWithTools 通过 `common_chat_templates_support_enable_thinking` 检测模板（Qwen3 支持），思考/正文分段解析 + 超限强制结束已有兜底，与 Java 侧流式回调衔接。
- 构建通过（BUILD SUCCESSFUL），待设备重连后安装验证：工具调用 + 思考链 + 长对话上下文稳定性。

## [2026-08-16] 题库详情字段 + 天气横幅/详情整改
### 题库管理 & 答题
- 题目详情页：选项改为动态渲染 A~L（此前只显示 A~D，E~L 丢失）；多选答案（如"AB"）正确高亮；补全题型/知识点/标签/提示/详细解析字段（空值隐藏）；编辑/删除按钮绑定真实功能；收藏读写数据库（此前假收藏）；分享文本含全部选项。
- 答题页：解析显示与列表一致（详细解析 analysis 优先，回退 explanation）。

### 天气横幅 & 详情页
- 修复横幅滑动误触跳转（移除 dispatchTouchEvent 全 UP 触发）；恢复点击跳转（所有子 View 统一绑定点击，任意位置可点）。
- 横幅信息行由 4 个零散 chip（体感/湿度/风力/气压/能见度）改为「天气详情总介绍」：今日白天/夜间+温差段 + 当前温度/天气/体感/湿度/风/能见度/气压段（今日段异步拉取预报，3 小时缓存）。
- 实时天气缓存 15 分钟 → 5 分钟（与横幅刷新周期匹配，刷新更及时）；错误响应不缓存。
- 横幅↔详情页联动补齐：详情页切换城市后同步横幅（写共享缓存+广播）；GPS 定位同步（原有）。
- 地址显示改为「城市名 + 副行具体地址」：顶部显示城市名（如"北京"），具体地址（区+路）作副行/总介绍末尾补充；定位与搜索粒度统一。
- 天气字段增加：横幅气压字段显示、完整总介绍。

## [2026-08-14] 模板管理并入导出页（废弃独立三级流水线）
- 「模板管理」按钮改为弹出全部 8 个场景模板选择对话框（名称+描述），选中即应用字段组合/开关并高亮对应 chip。
- 模板、自定义字段、导出开关统一在同一页面双向联动（选模板→字段/开关联动；自定义字段→取消模板高亮、开关自动开启）。
- 废弃独立模板三级流水线入口（TemplateSelectionActivity → FieldConfigActivity → ExportProgressActivity），类保留但不再导航，Manifest exported 改回 false。
- 解决：字段配置页显示异常、模板流水线与导出页自定义字段不联动的问题。

## [2026-08-14] 导入/导出系统大整改（修复全部遗留问题 + 新增功能）

### 导入流程（ImportActivity / WebViewFilePreview / ExcelUtil）
- 修复「AI 导入」页完全不可用：选择文件后 `currentFile` 从未赋值导致无法开始导入；多选模式被按钮前置检查拦截；取消按钮对 v2 管线无效（实际运行的是 v2，此前只取消 Orchestrator）。
- 移除 AI 导入页双通道死代码（「智能导入 vs AI 导入」二选一对话框、Orchestrator 四阶段路径、Excel 工作表选择器），统一为 v2 智能管线单通道；隐藏无回调的死 UI 区域。
- WebView 映射下拉可选项与导入实际支持的字段严格对齐：补选项 G~L、空 1~12 答案，移除无效的「分值/知识点」；拆分预览虚拟列支持 G~L 映射；自动映射值始终可见可选。
- Excel 单元格读值修复：公式单元格按缓存结果取值（此前恒为空）；数字用 BigDecimal 十进制化避免科学计数法。
- 导入页两个死按钮绑定功能：「开始导入」重新打开文件选择器；「AI 解析」对当前文件走 v2 智能管线。
- 新增：导入前检测未完成断点，可选「继续续导 / 删除断点重来」；source 目录批量导入前弹出文件清单确认。

### 导入自动化（v2 离线管线）
- 修复批量导入在 UI 主线程同步执行（ANR）：改后台线程 + 结束统一发 all-done 信号（结果摘要可正常弹出）+ 重置取消/批量标志。
- 断点续导改为记录「已写入分片行号」，消除检查点粒度重叠导致的 CSV 重复写入；解析失败清断点避免重复分片；分片部分丢失显式报错而非静默丢数据。
- 库内去重查询失败不再静默放行（整批拆半重试→单条失败），杜绝重复入库；Java/Python 空白归一化统一（含全角空格）堵住变体逃逸。
- AI 缺失字段填充改为循环分批全量覆盖（此前仅前 500 条被填充，其余落死值「未分类/难度1/空解析」）；本地填充 token 上限放宽防止整批截断；续导后旧分片缺失字段同样被扫描填充。
- Python 预处理流式化：xlsx 两遍读逐 sheet 产出（跨 sheet 表头不一致自动跳过）、CSV 流式解析、DB 大表游标逐行、SQL 加 5 万行上限——万行+ 文件不再 OOM。
- 批量导入扩展名清单补齐 .db3；apply_fills 原子写（tmp+rename）防止崩溃损坏分片。

### 导出流程
- 修复 Excel 导出空指针：表头行 `getRow(0)` → `createRow(0)`（空工作表 getRow 返回 null）。
- Excel/HTML 从 Python 通道切回 Java 实现：openpyxl 实际未打包进 APK（导出必然失败）+ Python 序列化缺失 optionG~L 等字段，切 Java 后默认 Excel 导出稳定完整。
- 模板流水线与题库页旧导出路径补复制到公共「下载/OilQuiz」目录（此前只存私有缓存，用户找不到文件）。
- 长图片导出高度计算与绘制统一口径（开启答案/解析后不再截断重叠）。
- 字段体系统一：`ExportUtils.EXPORTABLE_FIELDS` 单一来源（36 个），移除无意义的配图/音频/母题ID/时间戳/状态等字段；模板字段全部对齐；自定义字段对话框加滚动并显示完整。
- 「包含答案/解析/难度」开关对 CSV/Excel/JSON 生效（此前只按字段列表判断，取消勾选无效）。
- 自定义字段与模板/开关联动：勾选答案类字段自动开启对应开关、自定义后模板 chip 取消高亮。
- 死代码清理：删除 EnhancedExportManager 整类、ExportManager 状态机方法/exportToLongImage/exportTasks、getExporter 死方法。
- 模板选择页：格式下拉只保留 9 种可用格式（删除增强HTML/Python/Java）；中文标签；修复 selectedFormat 空指针崩溃（Spinner 回调不触发时兜底默认值）。

### 稳定性 / 线程 / 生命周期
- 所有异步回调（导出完成/失败、v2 导入回调、WebView 渲染完成）增加页面销毁保护，避免 BadTokenException 崩溃。
- ExportManager.startExport 加互斥，防止连续触发导出并发写同一文件。
- ImportActivity/AIImportActivity 页面销毁时取消后台导入任务。

## [2026-08-13] 导出功能全面优化

### 新增功能

#### 1. HTML学习查看版导出
- 新增HTML学习查看版导出格式，专门用于查看、学习和背诵
- 现代卡片式布局，每道题目独立卡片展示
- 答案折叠/展开功能，适合先自测再查看答案
- 解析折叠/展开功能，适合深入学习
- 难度星级显示（⭐⭐⭐）
- 分类、知识点彩色标签
- 一键展开/折叠全部功能
- 响应式设计，支持移动端显示

#### 2. 模板字段重新设计
- 根据各场景模板功能重新设计字段配置
- **标准完整版** (Excel)：12个核心字段，保留完整题目数据
- **数据分析版** (CSV)：10个统计字段，便于数据分析
- **答案解析版** (PDF)：5个核心字段，突出答案和解析
- **记忆卡片版** (长图片)：仅2个字段（题干+答案），极简设计
- **刷题训练版** (打印材料)：6个字段，不含答案
- **讲义备课版** (HTML)：12个字段，按分类分组
- **错题回顾版**：11个字段，完整题目+解析
- **模拟考试版**：11个字段，不含解析

### 修复问题

#### 1. Excel/CSV解析容错机制
- FileReaderTool: 当行中列数少于表头时，自动填充空字符串
- 避免因字段缺失导致的空指针异常

#### 2. Excel生成容错机制
- OfficeGeneratorUtil: 当rowData长度少于headers时，自动补齐
- 避免因字段缺失导致的生成失败

#### 3. JSON生成容错
- FileGeneratorTool: 添加null值检查和处理
- 避免因null值导致的JSON序列化失败

#### 4. export.py修复
- 删除`export_to_excel`函数中引用不存在变量`questions_raw`的错误代码
- 解决导出时NameError导致的"导出失败但无具体错误"问题

### 技术改进

- 新增PythonExporter类，统一Python导出入口
- 新增PythonExportBridge.exportToHtml()方法
- 重构ExportManager，HTML格式改用Python导出
- 模板配置同步更新（Java端 + Assets端）
