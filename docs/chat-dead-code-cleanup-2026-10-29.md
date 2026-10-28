# AI 对话界面死代码清理（2026-10-29）

> 分支 `chore/chat-dead-code-cleanup`，代码 tip `fe64102d`（10 个代码提交，148 文件，**−8,873 / +8**；本文档提交另计）
> 已推远端 `github`，已装机 25113PN0EC（Android 17）；**尚未并回 `main`**

---

## 0. 一页结论

AI 对话侧（`ai/chat/**` + `AIChatActivity` + 相关资源）清掉 **8,873 行**，全部是"运行期走不到"的代码：零引用类、无生产者的事件通道、零调用方法、无生产者的 MessageType 分支、没接线的整页壳、未引用的布局与 drawable，以及上述删除自身的残骸。

不改消息数据模型的现役字段、不改推理链路、不改任何 UI 视觉。装机后应当**看不出差别**；唯一肉眼可见的差异是 `DemoChatShellActivity` 那条 adb 演示入口没了（用户拍板删除）。

规模判定先给在后面被反复问到的那句：**不挡开发、不需要重写，纯减法**。

---

## 1. 删了什么（按批次）

| 提交 | 内容 | 文件 | 行数 |
|---|---|---|---|
| `7541a644` | 16 个零引用死类 + 悬空 javadoc/AppModule 空 provider | 21 | +4 / −2,812 |
| `76e4b4b6` | `StreamingEvent` + `NativeEvent` 两条无人发送的事件通道 | 3 | −526 |
| `1b0fbcb2` | 138 段零调用方法 + 27 失效 import + 17 零引用字段 | 38 | −1,528 |
| `22fa2fc7` | `DemoChatShellActivity` + 布局 + manifest（用户拍板） | 3 | −209 |
| `4b88c2df` | 按接收者类型补清的 104 段零调用方法 | 33 | −1,158 |
| `d4a26810` | `ChatShellView` 整页壳 + `ChatMessagesView` + `ChatStateOverlay` | 4 | +1 / −349 |
| `53b3bbab` | 四个无生产者 MessageType 及渲染分支 + 反思卡布局 | 4 | +2 / −286 |
| `91e4d15f` | 50 个无引用布局/drawable（含删后新暴露的 6 个级联 drawable） | 50 | −1,290 |
| `4f0e757c` | 清理自身的残骸：`ChatMessageDiffCallback`、多选/筛选字段与接口、空分区标题、空行 | 27 | −226 |
| `fe64102d` | AGENT_STEP 整条无生产者链路（回调接口+发射+渲染+布局+drawable） | 7 | +1 / −489 |

批次 1 的 16 个死类：`WeatherBannerController`(594)、`GenerationStreamController`(405)、
`AgentSessionView`(328)、`MutableChatList`(217)、`ChatSessionController`(195)、
`RecordingIndicatorView`(175)、`AttachmentChipsView`(174)、`MessageRouteDecider`(117)、
`ModeChipGroup`(116)、`HistoryEntriesBuilder`(109)、`ChatMessageSender`(109)、
`TokenStatsTextBuilder`(79)、`RegenerationPlanner`(69)、`ThinkingSegmentRoller`(45)、
`ModeInstructionInjector`(43)、`LinkOpener`(40)。

---

## 2. 判定口径（下次直接照抄，别再重新讨论）

一条代码算"死"，必须落在下面某一类并且**能给出证据**：

1. **整类零引用**：`grep -rnw 类名 src/main src/test src/androidTest` 只命中自己的文件；
   且不是 manifest 组件、不是布局里的自定义 View 标签、不是注解处理器发现的（Hilt
   `@Module`、prism4j `@PrismBundle` 都会被"按名 grep 无引用"误判成死码——本轮就误判过
   `MutableChatList` 的提供方 `AppModule` 和 `AiPrismBundle`）；全仓无反射字符串。
2. **无生产者的事件/数据**：类型有接收端但全仓没有一处 `new`/工厂调用（`StreamingEvent`、
   `NativeEvent`、`MessageType.AGENT_STEP`）。查字段同口径：**先核它全部写方**再判读方。
3. **零调用方法**：`名字(` 在整个生产代码 + 测试里出现次数 == 声明次数。分两轮口径——
   先"名字全局"，再"按接收者类型"（后者才能揪出与别处同名的 `ChatAdapter.addMessage`、
   整块 Selection Mode）。
4. **未引用资源**：资源名在 `src/**` + `build.gradle` + `proguard` 里零提及，必须同时匹配
   `R.layout.x` / `@layout/x` / `R.drawable.x` / `@drawable/x` 这些**带点号或斜杠前缀**的形式；
   并核过 `getIdentifier` 只有 5 处、均为用户图片 URI 名，不是资源名。
5. **级联残骸**：每删一批都会让"只被死码牵住"的东西露出来（`ChatMessageDiffCallback` 被
   删掉的 `updateMessages` 牵着；`agent_step_progress` 被删掉的布局牵着）。**删除后必须重扫一轮**。

---

## 3. 四个把结论带偏的坑（都真实发生过，别再踩）

1. **`(?<![\w$.])` 这类"点号后视"会吃掉所有合法调用。** 用它匹配 `obj.foo()` 与
   `R.layout.x` 时，前面的 `.` 让 lookbehind 直接失配 → 大面积假死、以及资源侧第一批
   "37 个未引用布局"的假清单。正确写法：`(?<![\w$])`（允许点号）。
2. **漏算 `::` 方法引用。** `RENDER_POOL.execute(ChatAdapter::drainPendingRenders)`、
   `postDelayed(this::measureContentHeight, …)` 都不带 `(`，名字计数看不见它们，
   7 个活方法被误删。
3. **接收者类型表必须是"文件作用域"。** 全局 `变量名 → 类型` 表里 `it`/`views` 这类短名
   会被别的文件污染，导致 `it.hasBody()` 被算成别类调用、`ChatMessage` 的 getter 被误判。
   真机不出错只是运气——编译器抓到了。
4. **静默不等于零。** Gradle `BUILD SUCCESSFUL in 2s` 是 `FROM-CACHE`（不是重跑）；
   `python subprocess(shell=True)` 下 cmd 把 `stash@{0}^1` 的 `^` 当转义符吃掉 → diff 为空 →
   "0 个声明被删"的假对账；正则少 `(?m)` 会让整段文本只匹配一次 → 同样假 0。
   三条都用"阳性对照"兜住：现役类/方法必须报 >0，仪器会响才信它的 0。

---

## 4. 用编译器当裁判的收敛流程（本轮唯一可靠的方法）

静态推断两轮都不可靠之后改成这样，成本 6 轮、每轮一次 `compileDebugJavaWithJavac`：

1. 候选全删（按花括号配平连同 javadoc/注解整段摘除，逐文件断言"声明名清单前后差 ⊆ 计划"）；
2. `JAVA_TOOL_OPTIONS=-Duser.language=en ./gradlew --offline compileDebugJavaWithJavac`
   （不设英文会 GBK 吞错，中文 `符号: 方法 x` 也照样能解析）；
3. 从 `cannot find symbol / symbol: method X` 提取 X，**按名字恢复** X 的全部候选段；
4. 重复到编译干净。本轮 216 段候选 → 净删 104 段，102 个方法名被裁判判为在用而保留。

恢复不用手工贴代码：`git checkout -- .` 回到上一个绿态，把排除名单喂给同一脚本重切。

---

## 5. 验证台账（都是真跑，不是缓存）

| 项 | 结果 |
|---|---|
| `testDebugUnitTest --no-build-cache --rerun-tasks`（分支） | 61 tasks executed，168 套件 / **1259 tests / 0 skipped / 0 failures / 0 errors** |
| 同命令跑 `main` 基线 | 168 / 1259 / 0 / 0 / 0 —— 用例数可对比，因为 `git diff main..HEAD` **不含任何 src/test 文件** |
| `assembleDebug` | BUILD SUCCESSFUL，产物 986,731,587 字节 |
| 包体取证 | 阳性：`ChatMessage`29 / `AIChatActivity`6 / `AgentChatHandler`3 / `InferenceStateManager`2；阴性全 0：`AgentStepInfo`、`AgentStepViewHolder`、`ChatMessageDiffCallback`、`ChatShellView`、`StreamingEvent` 等 25 项；`createAgentSummaryMessage`=2 仍在 |
| 装机 | 本地与设备 `base.apk` md5 相同 `874ccc4887074a265919408f3a649b42`，`lastUpdateTime=2026-10-29 03:55:52` |
| 远端 | `git ls-remote` = GitHub API = 本地 `fe64102d` |

---

## 6. 刻意没动（含理由）

- **11 段（12 个名字）"生产未调用、仅单测在用"的方法**：`thresholdPx`、`shouldFollow`、
  `getCurrentStats`、`getPendingTokenCount`、`isKnown`、`unregisterComponentCallback`、
  `componentFor`、`isCanonical`、`isScene`、`resolveEntryPath`、`containsStructure`、`views`。
  删它们要连测试一起删，属取向问题，未拍板前不动。
- **102 个被编译器裁判保住的方法名**里可能仍夹个别死方法（恢复按名字，宁可留）。
- **非对话范围**：全仓仍有 163 个不可达类（约 3.0 万行，集中在 `ai/tool`、`ai/python`、
  `ai/importing`）；`activity_study_progress.xml`(238) 等非对话未引用布局。
- **`assets/pages/ai-chat.html` + `js/pages/ai-chat.js`**：WebView 壳里的第二套对话 UI，
  `pages/index.html:151` 有真实入口，不是死码，是"并行实现"。
- **`docs/legacy/v1-chat-ui/`、`tools/backup_res/`**：非构建目录的旧 UI 归档；本轮删掉
  `agent_step_progress` 等 drawable 后，归档副本里的 `@drawable/…` 指向已不存在的资源，
  不影响编译运行，要不要补说明待定。
- **5 个 `*Flag`（Parts/Streaming/Tail/WebRender/HtmlSubmit）**：默认 `true`、可
  SharedPreferences 关的回退开关，两个分支都算活。

---

## 7. 复验命令（任何人都能自己跑）

```bash
# 用例与四数（先看有没有 skipped）
rm -rf build/test-results
./gradlew --offline --no-build-cache --rerun-tasks testDebugUnitTest
python -c "
import glob,xml.etree.ElementTree as ET
t=s=f=e=0
for p in glob.glob('build/test-results/testDebugUnitTest/*.xml'):
    r=ET.parse(p).getroot(); t+=int(r.get('tests',0)); s+=int(r.get('skipped',0))
    f+=int(r.get('failures',0)); e+=int(r.get('errors',0))
print(t,s,f,e)"

# 包体取证：正向必须 >0，才信反向的 0
python -c "
import zipfile,re
z=zipfile.ZipFile('build/outputs/apk/debug/答题宝-debug-2.0.apk')
b=b''.join(z.read(n) for n in z.namelist() if re.fullmatch(r'classes\d*\.dex',n))
h=lambda n: b.count(('/'+n+';').encode())
print('正向', h('ChatMessage'), h('AIChatActivity'))
print('反向', h('AgentStepInfo'), h('ChatShellView'), h('StreamingEvent'))"

# 死类复扫要点：允许点号 + 算上 :: 引用 + 文件作用域变量表
grep -rnw "WeatherBannerController" src/main src/test src/androidTest --include=*.java
```

---

## 8. 待办

1. 验机（用户自验清单：Agent 汇总卡、工具卡展开/折叠、思考行文案、流式滚动、导出总结、
   附件缩略图、各类卡片背景）；
2. 并回 `main`（先 `--is-ancestor`，能 FF 就不重跑）；
3. 可选：非对话侧 163 个不可达类的同口径清理；`agent_step_progress` 归档副本的说明。
