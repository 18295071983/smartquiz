# 答题宝 AI 工具功能描述清单

> 基于对 `com.oilquiz.app.ai.tool` 下全部 42 个工具实现代码的调查整理。
> 描述坚持客观、无引导（不含"最准/优先/默认/推荐用"等措辞），可直接作为工具注册 schema 与提示词注入的描述源。
> 单一真相源：`AIToolManager.getToolDefinition()` 与各工具类 `getDescription()`。

---

## 一、信息查询类

### ai_weather — 天气查询
- **分类**：weather
- **功能**：按需查询天气数据，支持实时天气/未来几天预报/逐小时/空气质量/预警/生活指数/全部。
  - `current`：实时天气（温度/体感/天气现象/风向风力/湿度/能见度/紫外线）
  - `forecast`：逐日预报（日期/白天夜间天气/最高最低温）
  - `hourly`：逐小时天气
  - `air_quality`：AQI/PM2.5/PM10/污染等级
  - `alerts`：天气预警；`indices`：生活指数；`all`：全部
  - `one_call`：一次返回当前+逐时+逐日+警报+日出日落（需经纬度，可选 `exclude`/`units`/`lang`）
- **位置参数**（三选一）：`city`（城市名 或 和风城市编码）、`lat+lon`（经纬度，别名 `latitude`/`longitude` 也可用）。
- **参数**：`action`、`city`、`lat`、`lon`、`exclude`、`units`、`lang`
- **别名**：get_weather / weather

### network_search — 网络搜索
- **分类**：search
- **功能**：联网搜索 + 智能问答 + 网页读取（秘塔搜索引擎驱动）。实时/最新/动态信息（新闻、价格、天气、汇率、政策、热点）用本工具获取。
  - `search`：关键词搜索，返回标题/链接/摘要
  - `ask`：智能问答，返回答案+引用来源（`model`：concise/detail/research）
  - `read_url`：读取网页正文；`get_webpage`：网页原始内容
  - `extract_info`：提取信息；`summarize`：网页摘要
  - `search_and_read`：搜索并读正文；`get_dynamic_content`：获取 JS 渲染动态网页内容
  - `smart_search`：智能搜索（`maxResults`/`autoRead`）；`smart_read`：对已有搜索结果逐条读正文生成摘要（需传 `results`）
- **参数**：`action`、`query`/`keyword`、`question`、`model`、`limit`/`num_results`、`url`、`maxResults`、`autoRead`、`results`
- **别名**：search

### webpage_reader — 网页阅读
- **分类**：web
- **功能**：Jsoup 解析网页，返回标题/描述/标题结构/正文/链接/摘要/关键词/分类。单页上限 5MB。
  - `read`：读取解析；`extract`：提取关键信息（可传已有 `content`）
  - `summarize`：生成摘要；`read_multiple`：并行批量读取（`urls` 数组）；`follow_links`：跟踪链接
- **参数**：`action`、`url`、`urls`、`content`、`query`、`maxDepth`、`maxLinks`

### smart_research — 智能研究
- **分类**：research
- **功能**：整合搜索和阅读，自动完成「搜索 → 选择 → 阅读 → 摘要」完整研究流程。
  - `research`：完整研究；`quick_search`：快速搜索
  - `deep_read`：深度阅读（`urls` 数组）；`summarize_topic`：主题摘要
- **参数**：`action`、`topic`/`query`、`depth`、`maxResults`、`includeDetails`、`urls`

### time_date — 时间日期
- **分类**：utility
- **功能**：查询当前时间/日期/时区；时间戳与日期互转。
  - `now`：当前时间/日期/时区
  - `timestamp_to_date`：时间戳转日期（秒级 `timestamp`）
  - `date_to_timestamp`：日期转时间戳（`yyyy-MM-dd HH:mm:ss`）
- **参数**：`action`、`timestamp`、`date`

### location — 位置查询
- **分类**：location
- **功能**：获取当前位置信息（经纬度/城市/详细地址）。
  - `get_current`：经纬度+城市+地址；`get_city`：当前城市名；`get_coordinates`：经纬度坐标
  - 返回含 `latitude/longitude/city/district/address` 字段
  - 位置服务未开启或权限未授予时返回错误提示并引导跳转系统设置
- **参数**：`action`
- **别名**：get_location / get_current_location / get_city / get_coordinates

### get_models_profile — 读取模型上下文窗口数据表
- **分类**：meta
- **功能**：读取模型上下文窗口数据表（当前生效版本，含官方来源）。`query`=模型名关键词时只返回匹配条目。
- **参数**：`query`

### update_models_profile — 更新模型上下文窗口数据表
- **分类**：meta
- **功能**：更新模型上下文窗口数据表（以官方文档查证的值为准）。
- **参数**：`json`（完整新表 JSON。条目结构：`match` 模型名关键词、`contextWindow` 窗口 tokens、`provider` 服务商、`apiUrl?` 可选端点关键词、`source?` 官方文档 URL、`date?` 查证日期）。校验通过立即生效并持久化。

---

## 二、计算与换算类

### calculator — 数学计算器
- **分类**：calculator
- **功能**：计算算术表达式。支持 `+ - * / % ^ ( )`、小数与负数。除零/非法表达式返回明确错误。纯 Java 解析，无注入风险，即时返回。
- **参数**：`expression`（算术表达式，必填）

### python_calculate — Python 数学计算
- **分类**：calculator
- **功能**：使用 Python 计算数学表达式（适合复杂/多步计算）。
- **参数**：`expression`（必填）、`task`（可选任务描述）

### unit_converter — 单位换算
- **分类**：utility
- **功能**：纯本地单位换算。
  - 长度：m/km/cm/mm/mile/yd/ft/inch
  - 重量：kg/g/mg/t/lb/oz；温度：celsius/fahrenheit/kelvin
  - 面积：m2/km2/cm2/hectare/acre；体积：l/ml/m3/gallon；速度：mps/kmh/mph
- **参数**：`value` 数值、`from` 源单位、`to` 目标单位

---

## 三、文件与文档类

### file_reader — 文件阅读
- **分类**：file
- **功能**：读取全文/按行/区间提取/搜索/实体提取/预览/解析结构化文件/列目录。自动检测编码（UTF-8/UTF-16/GB18030/GBK），支持 `content://` URI。
  - `read`：读全文；`read_lines`：按行区间；`extract_text`：按起止标记提取
  - `search_text`：关键词/正则搜索；`extract_entities`：正则实体提取
  - `preview`：预览前 N 字符；`list`：列目录
  - `parse_structured`：结构化解析；`parse_excel`/`parse_csv`/`parse_json`/`parse_xml`：专项解析
- **参数**：`file_path`/`file_uri`、`action`、`directory_path`、`encoding`、`startLine`/`endLine`、`startMarker`/`endMarker`、`keyword`、`regex`、`entity_pattern`、`maxLength`、`delimiter`、`max_rows`、`sheet_index`、`json_path`、`target_tag`、`max_items`

### file_analyzer — 文件分析
- **分类**：file
- **功能**：综合分析/统计/关键词/词频/格式检测/目录分析/查找重复文件（大小+内容哈希）。
  - `analyze`：综合分析；`statistics`：统计；`keywords`：关键词（`topN`）
  - `word_count`：词频；`detect_format`：格式检测
  - `analyze_directory`：目录分析；`find_duplicates`：查找重复文件
- **参数**：`action`、`file_path`、`directory_path`、`topN`

### file_generator — 文件生成
- **分类**：file
- **功能**：生成文本/JSON/配置/Markdown 等文件。未指定绝对路径时保存到 Agent 工作区（返回 `filePath` 完整路径，用 `workspace` 查看）。
  - `create`/`append`：创建/追加文本；`json`：写 JSON 数据
  - `config`：写配置键值对；`markdown`：Markdown 文档（`title`+`sections`）
  - `template`：模板；`report`：报告；`copy`：复制；`delete`：删除
- **参数**：`action`、`file_name`（必填）、`content`、`format`、`encoding`、`json_data`、`config`、`title`、`sections`、`source_path`

### python_file_ops — Python 文件操作
- **分类**：python
- **功能**：标准库 + openpyxl 的读写与修改。
  - `read`：读文本（UTF-8/GB18030/UTF-16 自动检测）
  - `parse`：严格解析 CSV（RFC4180）/JSON/XML/Excel（xlsx 读写）
  - `write`：写文件；`append`：追加；`replace`：文本替换
- **参数**：`action`、`file_path`（必填）、`content`、`old_text`、`new_text`、`encoding`、`format`、`max_rows`、`max_chars`、`sheet_index`

### excel_tool — Excel 表格操作
- **分类**：data
- **功能**：xls/xlsx 查询与修改。
  - 查询：`sheets`（工作表列表）、`query`（按条件过滤行：列名+op+match_value）、`cell`（读单元格）
  - 修改：`write_cell`（改单元格）、`add_row`（追加行）、`add_sheet`（新建工作表），修改后自动保存回原文件或 `output_path`
  - 坐标：`sheet`（名称或索引）、`cell_ref`（A1 如 B3）或 `row`+`column`（列名/列字母/列号）
- **参数**：`action`、`file_path`/`file_uri`、`output_path`、`sheet`、`cell_ref`、`row`、`column`、`value`、`values`、`new_sheet_name`、`column_name`、`op`、`match_value`、`row_start`/`row_end`、`max_rows`

### workspace — Agent 工作区
- **分类**：file
- **功能**：管理 Agent 产生的文件（图片/导出/临时数据）。
  - `list`：列出工作区文件；`path`：获取工作区根目录路径
  - `read`：读取工作区文本文件内容；`delete`：删除工作区文件；`clear`：清空工作区
- **参数**：`action`、`file_name` 等

---

## 四、数据与文本类

### database — 数据库操作
- **分类**：data
- **功能**：答题宝本地数据库操作。支持任意 SQL、表结构查看、题目查询与管理、用户管理、分数记录等。
  - 查询：`execute_sql`（任意 SQL，多语句分号分隔）、`list_tables`、`get_table_schema`、`execute_query`
  - 题目：`get_questions`、`search_questions`、`get_question_count`、`get_question_statistics`、`get_all_categories`、`get_all_question_types`、`get_question_by_id`
  - 题目增改删：`add_questions`（一次多道）、`bulk_import`（大批量，接受 `questions` 数组或 `file_path` JSON 文件，自动跳过无效条目）、`update_question`、`delete_question`、`clear_all_questions`
  - 用户/分数：`get_user`、`add_user`、`get_score_history`、`add_score`、`get_average_score`、`get_database_version`
- **参数**：`action`（必填）、`sql`、`table_name`、`query`、`keyword`、`id`、`category`、`type`、`difficulty`、`page`/`page_size`、`questions`、`file_path`、`username`、`userId`、`email`、`phone`、`password`、`questionText`、`optionA-D`、`correctAnswer`、`explanation`、`questionType`、`score`、`totalQuestions`、`correctCount`、`quizType`

### text_tools — 文本处理
- **分类**：utility
- **功能**：纯本地文本处理（零网络依赖）。
  - `json_format`/`json_validate`：JSON 格式化/校验
  - `upper`/`lower`：大小写转换；`base64_encode`/`base64_decode`：Base64 编解码
  - `url_encode`/`url_decode`：URL 编解码；`regex_extract`：正则提取（`pattern`）
  - `count`：字数统计；`trim`：去空白
- **参数**：`action`、`text`、`pattern`

---

## 五、系统与设备类

### system_resource — 系统资源调用
- **分类**：system
- **功能**：打开应用、打开 URL、发送短信、拨打电话、发送邮件、打开地图、控制应用、执行 Shell 命令、读写系统设置。支持应用名模糊匹配，找不到自动回退系统选择器。
  - `open_app`/`open_url`/`send_sms`/`make_call`/`send_email`（to 必填+subject/body）/`open_map`（location/address）/`share_text`
  - `list_apps`/`check_app`/`get_app_info`/`app_control`
  - `shell_command`：有安全管控，危险命令（rm/reboot/su/dd/chmod/kill/wget 等）与敏感路径（/data/data、/proc、/sys、凭据文件）被拦截，单条 10 秒超时
  - `read_setting`/`write_setting`（system/secure/global）；`get_current_app`；`open_settings`；`share_text`
- **参数**：`action`、`app`、`url`、`phone`、`message`、`to`、`subject`、`body`、`location`、`address`、`command`、`setting_type`、`setting_key`、`setting_value`、`control_action`、`setting`

### system_connect — 系统连接与设备能力
- **分类**：system
- **功能**：系统级 UI/数据/连接管理。
  - UI：`notify`（系统通知）、`floating_window`（悬浮窗，需权限）、`toast`（屏幕提示）、`screenshot`（截屏，需 MediaProjection）
  - 数据：`clipboard`（剪贴板读写）、`battery`（电池状态）、`network`（网络状态）、`volume`（音量控制）、`brightness`（亮度调节）
  - 连接：`wifi`/`bluetooth`（状态/开关）、`hotspot`（热点，需系统权限）、`usb`（USB 状态）、`screen`（屏幕）
- **参数**：`action`（必填）、`title`、`content`、`text`、`op`（read/write/get/set/up/down/on/off/status/keep_on/keep_off/mobile）、`enable`、`stream`、`value`、`path`、`x`、`y`

### permission_manager — 权限管理
- **分类**：system
- **功能**：权限检查、请求与管理。
  - `check`/`check_all`：检查；`request`/`request_and_wait`：请求
  - `get_status`：状态；`list_permissions`：列出；`explain_permission`：解释；`can_request`：可否请求
- **参数**：`action`、`permission`、`permissions`

### app_operation — 应用内页面跳转
- **分类**：app
- **功能**：跳转到应用内各页面（用户/题库/答题/学习计划/错题本/OCR/AI 等）。
  - `navigate`：跳转，`params` 动态注入页面参数（如 `media_gen` 打开 AI 生图页并预填描述）
  - `list_pages`：列出可用页面；`go_home`/`go_back`；`get_info`；`open_settings`；`share`
- **参数**：`action`、`page`、`params`、`setting`、`text`、`title`

---

## 六、Python 执行类

### python_execute — 执行 Python 代码
- **分类**：python
- **功能**：执行任意 Python 代码。内置 `android_ui` 模块（真实显示在手机界面）：系统原生组件 dialog/progress/input/choice（create_component → component_id → update/close/get_result 阻塞取结果）；内置 UI 组件库（create_component 渲染成聊天流卡片，props 带 actions 可交互）；便捷函数 ask_input/ask_choice/show_progress。脚本最后 print 输出作为结果返回。
  - 运行时已内置 pip 模块（`import pip` 可用）。安装新 Python 包：① `action=pip_install(package=包名)`（pip.main 编程式装到 filesDir/python_user_packages）；② 纯 Python 包（py3-none-any wheel）最稳用 `pip_install` 工具（自研下载器，不依赖 pip）；③ 编程式 `import pip; pip.main(['install','--target','<可写目录>','包名'])`。**禁止** subprocess 或 `python -m pip`（Chaquopy 无独立 python 可执行文件，必然失败）。
- **参数**：`code`（上限 200KB）、`task`、`context`、`timeout`（5~120 秒）

### pip_install — 运行时安装纯 Python 包
- **分类**：code
- **功能**：自研 wheel 下载器（不依赖运行时 pip），从 PyPI 镜像下载纯 Python 包（py3-none-any wheel）解压到 filesDir/runtime_packages/ 并注入 sys.path（装后 python_execute 即可 import，跨重启保留）。
  - 在线安装：`package=包名`（支持 name / name==版本 / name>=版本 / name~=版本），递归解析纯 Python 依赖，C 依赖列入 skipped 返回；
  - 本地安装：package 传本地 .whl 文件路径直接从文件安装；
  - 仅下载：action=download 下载 wheel 到工作区 files/wheels/；换源：source 参数（tuna/aliyun/pypi/自定义）并持久化默认。
  - 与 python_execute 的 action=pip_install（装到 filesDir/python_user_packages）目录不同互不覆盖。
- **限制**：只装 py3-none-any；带 C 扩展的包（numpy/scipy/lxml 等）Android 上无法运行时编译，拒绝并提示编译期预打包。
- **参数**：`package`、`action`（install/download/set_source）、`source`、`timeout`

### remote_dsh — 远程控制电脑（DeepSeek dsh 官方会话通道）
- **分类**：remote
- **功能**：调用电脑端安装的 dsh（DeepSeek Harness Shell）执行任务，AI 可远程操作电脑——读文件/跑命令/查信息/让 DeepSeek agent 干活，**支持多轮会话续接**（电脑端 dsh 记忆连续）。
  - 架构：手机 App → HTTP(Bearer token) → 电脑端 tools/dsh_bridge_server.py(v4) → dsh ACP serve（session/new / session/prompt / SSE；每会话一条流）；命令类任务可走 /exec 直连；ACP 不可用时降级 headless。
  - run：执行任务+自动续接会话（task=自然语言描述，如"看看D盘有哪些项目文件夹"；已有 session_id 直接续接，没有自动创建）；start：新建会话（重置电脑端记忆）；history：读当前会话历史（max=条数，默认10）；get_status：检查桥接与 dsh 双通道状态；set_config：配置 base_url(电脑地址)+token(访问令牌)。
  - 会话续接原理：dsh web 事件溯源日志持久（append-only session log），同一 session_id 连续 prompt 即续接（实测：第二轮问"我刚才让你回复什么"→ 正确回忆第一轮回复）。
  - 安全：必须配置 token（桥接服务启动时打印）才可调用；未配置/鉴权失败明确报错；base_url 仅允许 http/https；dsh web(127.0.0.1:3080) 只监听电脑本机，手机只访问带 token 的桥接层(8218)。
- **扫码一键配对（pair）**：电脑端启动桥接后自动打开浏览器显示配对页（http://127.0.0.1:8218/pair，仅本机可访问），页面二维码内容=dshpair://电脑IP:8218?token=令牌；手机端 action=pair 打开相机扫二维码，自动保存 base_url/token（zxing-android-embedded 4.3.0）。配对后会话重置（session_id 清除）。
- **ACP 官方通道（v3 已启用）**：dsh 已升级 0.1.5-rc.3（launcher npm install，主 DSH_HOME 配置/会话未动）。ACP serve=dsh --profile acp serve --host 0.0.0.0 --port 7800 --token xxx（dsh-acp-server@0.12，bearer 鉴权 + 内置 Web UI + healthz）。协议：POST /acp initialize（响应 Acp-Connection-Id）→session/new（必须带 mcpServers:{}）→session/prompt（prompt 为 blocks 数组，非对象）；GET /acp/stream(SSE) 流式 agent_message_chunk + result.stopReason 判定回合。**桥接 v3 后端切 ACP**（App 无感，接口不变）：session start/prompt(同 sessionId 续接)/history(本桥接内存记录)/get_status/set_config + headless /run fallback。已验证：start→prompt 两轮续接（R2 回忆 R1）→history 全通。
- **0.1.5 注意事项**：① dsh web(3080) 升级后带 token 鉴权（/api/session/* 旧路由已移除），本桥接不再依赖 web 通道；② 主 home web profile 第三方插件（dsh-plugin-marketplace@0.2.8、dsh-notify-win、dsh-toolkit github 源）与 0.1.5 不兼容（dsh-settings 删除 installSettingsSection API），已从 bundles 摘除（原 package.json 备份在 tools/dsh-home-backup/web-profile/，dsh-desktop/dsh-image-pathify 保留）；③ ACP serve 重启后旧 sessionId 失效，App 重新 action=start 即可。
- **限制**：电脑端需先启动桥接（双击 tools/start_dsh_bridge.bat，等价于 python tools/dsh_bridge_server.py --token xxx --cwd 工作目录）**且 ACP serve 在 7800 运行**（dsh --profile acp serve --host 127.0.0.1 --port 7800 --token xxx；启动脚本已一并拉起）；手机与电脑需同一网络或经安全隧道（花生壳映射 127.0.0.1:8218）。
- **直连执行（shell，2026-09-27 新增）**：`action=shell` 把 `task` 当一条命令直接在电脑上跑（bridge `POST /exec`，**不经电脑端 LLM**）——
  毫秒级返回、输出原样（含 stderr）、非零退出如实回传。适合"跑这条命令并把原始输出贴回来"（`git status`、`Get-ChildItem`…）。
  此前这类任务走 `run`（起完整 dsh agent，几十秒 + 烧 token + 输出可能被模型改写）。可选 `shell=auto|cmd|bash`（默认 auto：优先 pwsh 回退 powershell）。
- **ACP 权限应答（v4 修复）**：dsh ACP 在执行**需要授权的工具**（写文件、跑命令）前会发 `session/request_permission` 并等客户端回答，
  该请求**只投递到会话流**（`GET /acp/stream` + `Acp-Session-Id`）。此前桥接只开连接流且从不回答 →
  这类任务**永久挂起**直到超时（手机端只看到"等待回复超时"，表现为"ACP 有问题、用起来有毛病"）。
  现在桥接为每个会话建立会话流并自动应答（默认 `allow-once`，`--permission deny` 可整体拒绝），
  应答记录在 `/status` 的 `permissions`（时间/会话/toolCallId/选项/HTTP 码）可审计。
- **超时语义（2026-09-27 修复）**：`timeout` 参数现在真的生效（秒，5~600，默认 120）——
  `RemoteDshTool.executionTimeoutMs()` 按 `timeout+45s` 向 `OnlineToolManager` 申报，
  不再被其 30s 默认值掐断（此前实测 `timeout=150` 仍在 30s 失败且结果为空）。超时会明确告知"等了多久、任务可能仍在电脑上"。
- **界面提示（2026-09-27 新增）**：AI 聊天页顶部（模型状态胶囊下方）常驻「电脑连接」状态条，三态可见：🔴 未配对 / 🟢 已连接（带地址）/ 🟡 已断开；点一下直接进「远程连接（电脑）」界面。此前连接状态只在 AI 的文字回复里出现，界面上没有任何提示。
  **默认关闭**（多数用户不用这个功能，不占聊天页空间）：要用的人在「远程连接（电脑）」界面打开开关「在聊天页顶部显示连接状态条」，或直接点状态条右侧 ✕ 隐藏（偏好存 ai_prefs.remote_dsh_bar，与配对配置分开，清除配置不会重置它）。
- **新手教程（2026-09-27 新增）**：「远程连接（电脑）」顶部「❓ 怎么用 / 电脑端怎么配」→ 教程页：功能说明+能说哪些话、电脑端准备步骤（Node.js/Python/装 dsh/放文件/双击启动）、手机扫码配对、连不上排查、安全说明。
  **电脑端程序一键导出**：教程页可把 App 内置的电脑端程序（start_dsh_bridge.bat/.sh、dsh_bridge_server.py、qrcodegen.js、README.md）导出到 `Download/OilQuiz/remote_dsh/`，拷到电脑上双击即可（否则新用户拿不到桥接脚本）。
- **连接界面（2026-09-27 新增）**：**工具集 → 设置与数据 → 远程连接（电脑）**（AI 对话页工具抽屉「管理」组也有入口「🖥️ 远程连接（电脑）」）——
  连接（探测 GET /status，通了才置为已连接、才允许执行）/ 断开（本机停用：run·shell·start·history 一律被拒，配置与令牌保留，电脑端不受影响）/
  清除配置（地址/令牌/会话/连接状态全清，二次确认）/ 扫码配对 / 手动填地址+令牌 → 保存并连接。
  状态点：绿=已连接、黄=已断开、红=未配置；显示令牌打码与位数、当前会话、最近探测时间与耗时。
  AI 也可代劳：`action=connect`（连接）、`action=disconnect`（断开）。
- **参数**：`action`（run/shell/connect/disconnect/pair/start/history/get_status/set_config）、`task`、`max`、`shell`、`base_url`、`token`、`timeout`

### python_analyze_data — Python 数据分析
- **分类**：python
- **功能**：数据分析（统计/清洗/转换/图表计算）。适合处理用户提供的数据或表格内容。
- **参数**：`data`、`task`

### python_web_reader — Python 网页抓取
- **分类**：python
- **功能**：requests + bs4 抓取网页/API 并提取信息。
  - `fetch`：GET/POST 抓取（可带 headers/params/JSON body）
  - `extract`：bs4 提取标题/正文/链接/表格/JSON-LD
  - `fetch_json`：请求 JSON API
  - 适合登录态/Cookie/自定义请求头/API 等场景
- **参数**：`action`、`url`（必填）、`method`、`headers`、`params`、`data`、`content`、`timeout`、`max_chars`

### python_chart — Python 数据图表
- **分类**：python
- **功能**：Pillow 生成 PNG 数据可视化图表。
  - `bar`：柱状图（多系列）；`line`：折线图（多系列）；`pie`：饼图；`scatter`：散点图
  - `data` JSON：bar/line 用 `{labels, values}` 或多系列 `{labels, series:[{name, values}]}`；pie 用 `{labels, values}`；scatter 用 `{points:[[x,y]]}`
  - 图片保存到工作区 `files/`，可指定 `output_path`
- **参数**：`action`（必填）、`data`（必填）、`title`、`width`、`height`、`output_path`、`colors`、`show_values`

---

## 七、媒体与视觉类

### image_gen — 文生图
- **分类**：media
- **功能**：根据描述生成图片（免费 API）。
- **参数**：`prompt`（必填）、`width`、`height`、`model`（flux/flux-realism/flux-anime/turbo）、`style`

### dashscope_media — 百炼文生图/文生视频
- **分类**：media
- **功能**：通义万相文生图/文生视频。
  - `image`：文生图（wan2.2-t2i-flash / wan2.2-t2i-plus）
  - `video`：文生视频（wan2.2-t2v-plus，异步提交返回 task_id）
  - `query`：按 task_id 查询进度并下载结果；`models`：列出可用模型
  - 视频尺寸白名单：1080*1920 / 1920*1080 / 1440*1440 / 1632*1248 / 1248*1632 / 480*832 / 832*480 / 624*624
  - 结果保存到工作区 files/，返回文件卡片/图片卡片
- **参数**：`action`、`prompt`、`model`、`size`、`duration`、`task_id`、`api_key`

### ocr_recognize — 图片理解
- **分类**：utility
- **功能**：OCR 文字识别 + 视觉问答（看图理解）。
  - `ocr_recognize`：识别图片文字；`ocr_recognize_pdf`：识别 PDF 文字
  - `image_understand`：图片理解视觉问答（`question`，自动选模型；`image_understand_local`/`image_understand_online`=指定本地/在线视觉模型）
  - `ocr_set_language`/`ocr_get_language`：识别语言（auto/chinese/english/japanese/korean）
- **参数**：`action`、`image_path`、`pdf_path`、`question`、`language`

### video_to_player — 视频下载转播放
- **分类**：media
- **功能**：输入视频页面链接或直链 URL，解析视频源并下载到本地工作区，返回 `local_path`（mp4 绝对路径）供 video_player 组件渲染原生播放。直链（mp4/webm 等扩展名或视频 Content-Type）直接下载；网页链接抓取 HTML 提取 og:video 或 `<video>` 标签 src 后下载。
- **参数**：`url`（必填）、`title`、`timeout`

### media_toolkit — 本地媒体工具箱
- **分类**：media
- **功能**：两套引擎，**系统框架优先**（MediaExtractor / MediaCodec / MediaMuxer / MediaMetadataRetriever + Media3 Transformer），打不开或做不到时**自动回退内置 ffmpeg**（ffmpeg-kit-min 8.1.9，以库形式 `System.loadLibrary` 加载，进程内 ffmpeg n8.1.3）。处理过程**不需要任何权限、不联网**（读取外部文件仍受 App 已有存储访问限制）。
  - `probe`：媒体信息（时长/分辨率/帧率/码率/旋转/音视频轨/编码器；视频、音频、图片都行）
  - `frame`：截帧出图（`time` 秒 / `percent` 0-100 / `index` 帧序号 / `count`=N 均匀抽 N 张 / `exact`=精确帧）
  - `thumbnail`：缩略图（默认 10% 处、最长边 512）
  - `extract_audio`：无损抽音轨（aac→m4a、mp3→mp3，重封装不重编码）
  - `to_wav`：解码 WAV（默认 16kHz 单声道 16bit，可直接喂语音识别；`rate`/`channels` 可调）
  - `trim`：无损剪切（`start`/`end` 秒，关键帧对齐，不重编码，MP4 输出）
  - `transcode`：转码/压缩/改分辨率/换容器（`video_mime`/`audio_mime`/`width`/`height`/`scale`/`bitrate`/`remove_audio`/`timeout`；keep+无效果=纯重封装）
  - `image_ops`：图片处理（`width`/`height`/`max`/`crop`/`rotate`/`flip`/`gray`/`format`/`quality`）
  - `filter`：ffmpeg 滤镜链（`vfilter`/`afilter`，如 scale/fps/crop/overlay/ass字幕/atempo/volume）—— 系统框架没有的能力
  - `engine`：`auto`（默认，系统优先 + 自动回退 ffmpeg）/ `system`（只用系统）/ `ffmpeg`（只用 ffmpeg）
  - 内置 ffmpeg 实测能力：avi/flv/rmvb(.rm)/wmv(asf) 解封装；rv40/rv30/cook/wmv3/vc1/msmpeg4v3 解码；h264_mediacodec/hevc_mediacodec 硬件编码桥；体积 arm64-v8a 未压缩 15.06MB（APK 内压缩约 7.8MB）
  - 输入支持绝对路径/工作区相对路径/content:// URI；输出默认落工作区 `files/media/`，返回文件绝对路径
  - Python 侧同等能力：`import android_media`（probe/frame/thumbnail/extract_audio/to_wav/trim/transcode/image_ops）
  - **能力边界**：仍不支持 mp3/opus **编码**（min 构建无 lame/libopus，mp3 只能抽已有音轨）、drawtext 文字水印（无 freetype）、时间轴水印/画中画/多路混流这类复杂编排；非主流编码为纯软解会慢。`trim` 为关键帧对齐（非帧级精确）；`transcode` 实际分辨率会被编码器对齐取整（以返回 `output_width`/`output_height` 为准）。做不到时明确报错，不越界承诺
- **参数**：`action`（必填）、`path`（必填）、`output`、`time`、`percent`、`index`、`count`、`exact`、`width`、`height`、`max`、`format`、`quality`、`crop`、`rotate`、`flip`、`gray`、`start`、`end`、`rate`、`channels`、`video_mime`、`audio_mime`、`bitrate`、`scale`、`remove_audio`、`timeout`、`engine`、`vfilter`、`afilter`

---

## 八、UI 组件类

### ui_component — 创建 UI 组件
- **分类**：system
- **功能**：创建系统原生组件（dialog/progress/input/choice/multi_choice/date/time/snackbar/list/notification/custom 动态表单/marquee 跑马灯/media_task 任务监控等）或内置卡片（chart/info_card/table_card 等）。有结构的信息用组件卡片展示。
  - 生命周期：`create` → 拿 `component_id` → `update`/`close`/`get_result`
  - `register_type`：外部注入自定义类型（原生控件框架树 render.layout）
  - `list_types`/`remove_type`/`clear_temporary_types`：注册类型管理
  - `custom` 动态表单：`fields` 定义任意字段（text/number/select/switch/slider/date 等）；props 加 `rounds=N` 支持多轮连续输入批量录入
  - `layout` 现场自定义 UI：原生控件框架树 JSON，支持嵌套已注册类型、`use` 引用 layout 模板
- **参数**：`action`（必填）、`component_type`、`component_id`、`title`、`message`、`dialog_type`、`progress`、`options`、`default_value`、`input_hint`、`action_label`、`fields`、`items`、`url`、`props`、`wait_seconds`、`auto_close`、`name`、`layout`、`render`、`monitor`

### ui_component_plugin — UI 组件插件系统
- **分类**：system
- **功能**：动态创建/复用原生 UI 组件插件与原生 layout 模板库。
  - 插件动作：`create`/`template`/`validate`/`get`/`list`/`remove`/`clear_temporary`
  - layout 模板动作：`register_layout`/`layout_list`/`layout_remove`/`layout_clear_temporary`
  - 生命周期：`persist=true` 长久落盘可复用，`false` 临时仅内存任务结束消失
  - 兼容性自动校验：插件名仅字母数字下划线、params 类型限 string/number/boolean/array/object、render.card 限内置卡片、render.layout 限原生控件框架、monitor.tool 限已注册工具
  - 注册后可用 `ui_component` 创建，或用插件名/类型名作 layout 节点 type 嵌套复用
- **参数**：`action`（必填）、`name`、`description`、`params`、`render`、`layout`、`monitor`、`persist`

### control_lookup — 控件参数查询
- **分类**：meta
- **功能**：按关键词检索低频 UI 控件的详细参数说明（视频/音频/图表/二维码/日期等）。
  - `search`：按关键词查控件参数；`list`：列出全部低频控件
  - 高频常用控件（text/button/input/select/table 等）已在系统提示词列出，无需查询
- **参数**：`action`（必填）、`keyword`

### layout_editor — 布局画布编辑器
- **分类**：system
- **功能**：动态编辑常驻布局画布（layout_canvas 组件）的控件树。
  - `set`：整体替换布局；`add`：追加单个控件节点（非容器）；`patch`：修改/删除节点
  - `get`：查看当前布局；`rebuild`：强制重渲染
  - 前置：先用 `ui_component(create, component_type=layout_canvas, layout=完整含 input/button 的布局)` 创建画布拿 component_id；每个输入控件必须带 `key`、button 必须带 `action`
  - 全程用同一个 component_id，勿反复重建画布
- **参数**：`action`（必填）、`component_id`（必填）、`layout`、`node`、`container`、`index`、`key`、`path`、`props`、`remove`

---

## 九、工具管理类

### tool_registry — 工具注册表（MCP 式工具发现）
- **分类**：meta
- **功能**：列出可用工具（list）、按关键词搜索工具（search）、获取单个工具完整参数 schema（get）。模型不确定有哪些工具或需要某工具详细参数时调用，避免猜测工具名/参数。
- **参数**：`action`（必填）、`keyword`、`tool`

### create_dynamic_tool — 动态创建工具
- **分类**：tool
- **功能**：把重复性任务封装成可复用工具。
  - `create`：填 tool_name + description + parameters + logic（Python 脚本或 DSL），创建后可被后续对话直接调用
  - `update`/`delete`：修改/移除；`list`：列出全部；`show`：查看单个完整定义；`test`：试运行不落库
  - `parameters` 支持三种格式：简单 `{参数名:描述}`、属性级、完整 JSON Schema
  - `logic`：Python 脚本（用 `script_args['参数名']` 读取参数，print/返回值作为结果）或 DSL 命令
- **参数**：`action`、`tool_name`、`description`、`parameters`、`logic`、`test_params`

### ai_create_tool — AI 创建工具
- **分类**：tool
- **功能**：使用 AI 自动生成新工具。
- **参数**：`tool_name`（必填）、`description`（必填）、`parameters`、`logic`

---

## 十、语音类

### voice_input — 语音输入（ASR）
- **分类**：speech
- **功能**：将语音/音频转换为文字。
  - `recognize`：识别音频文件；`record`：交互式录音组件（弹窗录音，点完成结束，录音前自动停止 TTS 播放）
  - `record_and_recognize`：固定时长录音（需录音权限）；`check`：检查可用性
  - 未配置语音识别模型时提示先配置（如 qwen3-asr-flash / whisper-1）
- **参数**：`action`、`audio_path`/`audio_uri`、`language`、`duration_seconds`、`title`、`hint`、`timeout_seconds`

### speech_synthesis — 语音合成（TTS）
- **分类**：speech
- **功能**：将文字合成为语音并播放，或保存为音频文件。在线 TTS 不可用时自动回退系统 TTS。
  - `synthesize`：阻塞播放；`speak`：带播放组件朗读（弹窗可见可停止，用 ui_component get_result 等待 completed/stopped）
  - `save`：保存文件；`play`：播放音频；`stop`：停止；`voices`/`set_voice`：音色
- **参数**：`action`、`text`、`voice`、`title`、`output_path`、`audio_path`、`save_voice`、`duration_seconds`、`timeout_seconds`

---

## 十一、记忆类

### memory — 长期记忆
- **分类**：memory
- **功能**：跨会话保存/读取/删除用户信息。
  - `save`：保存（用户明确要求记住或主动告知个人信息/偏好时）；`recall`：读取
  - `delete`：删除单条；`list`：列出所有；`clear`：清空
  - 记忆跨对话保留
- **参数**：`action`、`key`、`value`、`text`（记忆内容）

---

## 十二、聚合工具

### app_toolkit — 应用工具集
- **分类**：app
- **功能**：聚合天气/计算/OCR/图像/网页等能力，通过 `action` 指定具体操作。
  - 天气：weather_current/forecast/hourly/air/alerts/indices/all
  - 计算：calculate；OCR：ocr_recognize（在线视觉模型）/ocr_recognize_pdf/ocr_set_language/ocr_get_language
  - 图像识别：image_label_recognize/object_detect
  - 图像处理：image_save/scale/crop/rotate/generate_color/generate_text
  - 网页解析：web_parse_html/get_title/get_links/get_images/get_text
  - 其他：get_info/get_guide
- **参数**：`action`（必填）、`image_path`、`expression`、`url`、`language`、`width`、`height`、`city`
- **说明**：文件读取/解析用 `file_reader` 工具

---

## 附：别名与遗留工具名

| 工具名 | 解析目标 |
|---|---|
| `system_ui_control` / `ui_control` | `ui_component` |
| `get_gps` | `location` |
| `weather` / `get_weather` | `ai_weather` |
| `search` | `network_search` |
| `get_location` / `get_current_location` / `get_city` / `get_coordinates` | `location` |

## 附：工具分类统计

| 分类 | 工具 |
|---|---|
| 信息查询 | ai_weather、network_search、webpage_reader、smart_research、time_date、location、get_models_profile、update_models_profile |
| 计算换算 | calculator、python_calculate、unit_converter |
| 文件文档 | file_reader、file_analyzer、file_generator、python_file_ops、excel_tool、workspace |
| 数据文本 | database、text_tools |
| 系统设备 | system_resource、system_connect、permission_manager、app_operation |
| Python | python_execute、python_analyze_data、python_web_reader、python_chart |
| 媒体视觉 | image_gen、dashscope_media、ocr_recognize、video_to_player、media_toolkit |
| UI 组件 | ui_component、ui_component_plugin、control_lookup、layout_editor |
| 工具管理 | tool_registry、create_dynamic_tool、ai_create_tool |
| 语音 | voice_input、speech_synthesis |
| 记忆 | memory |
| 聚合 | app_toolkit |
