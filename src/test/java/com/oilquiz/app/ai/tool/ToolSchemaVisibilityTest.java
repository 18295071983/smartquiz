package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.oilquiz.app.ai.tool.openai.ParamDefinition;
import com.oilquiz.app.ai.tool.openai.ToolDefinition;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * 模型可见性守卫（2026-10-23）。
 *
 * <p>教训来自本轮：新卡片/新图型的文档写进了注解与 {@code getParameterDescriptions}，
 * 但在线引擎取的是 {@link AIToolManager#getToolDefinition} 里<b>另一份硬编码定义</b>——
 * `python_chart` 的 render=card 一开始就没到模型眼前。文案三处同源还不够，
 * 必须直接对"在线模型真正拿到的那份 schema"做断言，否则功能加了模型也不会用。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class ToolSchemaVisibilityTest {

    @Mock
    private Context context;

    @Test
    public void uiComponentSchemaTeachesNewCardsAndChartTypes() {
        String schema = dump(AIToolManager.getInstance(context).getToolDefinition("ui_component"));

        // 四张新卡 + 题库卡两种新题型
        for (String key : new String[]{"playlist", "agenda", "location", "diff"}) {
            assertTrue("在线 schema 没教 " + key, schema.contains(key));
        }
        assertTrue("在线 schema 没教填空/简答", schema.contains("fill|short"));
        // 图表扩型
        assertTrue("在线 schema 没教新图型",
                schema.contains("area|stacked_bar|pie|scatter|radar"));
        assertTrue("在线 schema 没教散点坐标", schema.contains("points"));
        // html 的能力按来源分档 + 工作区 .html 产物自动出卡（2026-10-24）：
        // 此前三份抄本都在售卖"图片全屏预览/卡内导航"，而模型直给的内联串根本拿不到这些能力
        assertTrue("在线 schema 没教 html 分档（内联串剥脚本）", schema.contains("script/onclick"));
        assertTrue("在线 schema 没教 .html 产物自动出卡", schema.contains(".html/.htm"));
        // 页内回传的用法（2026-10-24 第 3 步）：不教就等于让模型继续只会 footer 按钮
        assertTrue("在线 schema 没教 Android.submit", schema.contains("Android.submit"));
        assertTrue("在线 schema 没教 submit 的 payload 上限", schema.contains("16KB"));
        // component_type 那段此前另写了一份"图片点击全屏预览"，没提这能力只有本地网页/远程页才有
        assertTrue("在线 schema 还在无档位地售卖图片预览", !schema.contains("图片点击全屏预览"));
    }

    /**
     * 写文件能力收口（2026-10-28）：{@code file_generator} 补齐 write/edit/diff，
     * {@code document_parser}（原名 python_file_ops）的写侧与 diff <b>已删除</b>（只剩 read/parse）。
     * 只改能力不教 schema，模型照样会去 python 里改文件，所以两侧"模型真正读到的那份定义"都要钉住。
     */
    @Test
    public void fileGeneratorOwnsWritesAndDocumentParserIsReadOnly() throws Exception {
        String fg = dump(AIToolManager.getInstance(context).getToolDefinition("file_generator"));
        assertTrue("在线 file_generator 没教 action=write", fg.contains("write"));
        assertTrue("在线 file_generator 没教 action=edit", fg.contains("edit"));
        assertTrue("在线 file_generator 缺 old_text 参数", fg.contains("old_text"));
        assertTrue("在线 file_generator 缺 replace_all 参数", fg.contains("replace_all"));
        assertTrue("在线 file_generator 没教 action=diff（预览）", fg.contains("diff"));
        assertTrue("在线 file_generator 缺 file_path_b 参数", fg.contains("file_path_b"));

        String annotationCopy = read("src/main/java/com/oilquiz/app/ai/tool/FileGeneratorTool.java");
        assertTrue("注解那份也没声明 edit", annotationCopy.contains("@Action(name = \"edit\""));
        assertTrue("注解那份也没声明 write", annotationCopy.contains("@Action(name = \"write\""));
        assertTrue("注解那份也没声明 diff", annotationCopy.contains("@Action(name = \"diff\""));

        String pfo = dump(AIToolManager.getInstance(context).getToolDefinition("document_parser"));
        assertTrue("document_parser 没把写侧指向 file_generator", pfo.contains("file_generator"));
        assertFalse("document_parser 还在兜售 write", pfo.contains("write="));
        assertFalse("document_parser 还在兜售 edit", pfo.contains("old_text"));
        assertFalse("document_parser 还在兜售 diff", pfo.contains("diff="));
        // 注解那份（离线模型读的）同样只剩 read/parse
        String pfoCopy = read("src/main/java/com/oilquiz/app/ai/python/DocumentParserTool.java");
        assertFalse("注解里还留着 write 动作", pfoCopy.contains("@Action(name = \"write\""));
        assertFalse("注解里还留着 edit 动作", pfoCopy.contains("@Action(name = \"edit\""));
        assertFalse("注解里还留着 diff 动作", pfoCopy.contains("@Action(name = \"diff\""));
    }

    @Test
    public void pythonChartSchemaTeachesCardMode() {
        String schema = dump(AIToolManager.getInstance(context).getToolDefinition("python_chart"));
        assertTrue("在线 schema 没教 render", schema.contains("render"));
        assertTrue("在线 schema 没教 card 形态", schema.contains("card"));
        assertTrue("在线 schema 没教 chartType 覆盖", schema.contains("chartType"));
        assertTrue("在线 schema 没列原生图型", schema.contains("stacked_bar"));
    }

    /**
     * 捷径思维的防线（2026-10-28 用户反馈"每次写文件都用 python"）。
     *
     * <p>模型是照着工具描述选工具的：python_execute 的描述把能力铺得很全（UI、Termux、一堆预装库），
     * 却从不说"什么时候不该用它"，于是它成了万能锤。两侧模型读的是两份抄本（在线硬编码定义、
     * 注解/构造），只改一份等于只修一半。</p>
     */
    @Test
    public void pythonExecuteTeachesDeclarativeFirst() throws Exception {
        String online = dump(AIToolManager.getInstance(context).getToolDefinition("python_execute"));
        assertTrue("在线 python_execute 描述没教『先想专用工具』", online.contains("先想专用工具"));
        assertTrue("在线描述没点名 file_generator 这个替代品", online.contains("file_generator"));
        String annotationCopy = read("src/main/java/com/oilquiz/app/ai/python/PythonExecuteTool.java");
        assertTrue("注解/构造那份抄本没教『先想专用工具』", annotationCopy.contains("先想专用工具"));
    }

    /**
     * python 侧 {@code create_component} 的展示面（2026-10-24 订正）。
     *
     * <p>实现事实：{@code android_ui.set_ui_callback} 由 {@code PythonToolManager} 注入，
     * 内置组件走 {@code createComponent} → {@code registry.render(fAct, data)} 装进
     * <b>AlertDialog</b>，无前台 Activity 时降级 Toast；整条路径一次都没调
     * {@code ComponentCollector}，所以脚本建的卡片<b>不进聊天流、也不留档</b>。
     * 而三份 python_execute 描述（注解 / 构造 / 在线硬编码定义）与 android_ui 注释此前都写
     * "渲染成聊天流卡片"——模型照这个预期写脚本，产物就只是弹一下的对话框。</p>
     */
    @Test
    public void pythonCreateComponentTeachesDialogSurfaceNotChatStream() throws Exception {
        assertDialogSurface(dump(AIToolManager.getInstance(context)
                .getToolDefinition("python_execute")), "在线定义");
        assertDialogSurface(read(
                "src/main/java/com/oilquiz/app/ai/python/PythonExecuteTool.java"), "注解+构造两份抄本");
        assertDialogSurface(read("src/main/python/android_ui.py"), "android_ui 组件说明");
    }

    private static void assertDialogSurface(String doc, String channel) {
        assertTrue(channel + " 没说清是弹窗", doc.contains("弹窗"));
        assertFalse(channel + " 仍在教 python 侧出聊天流卡片", doc.contains("渲染成聊天流卡片"));
        assertTrue(channel + " 没指引改用 ui_component", doc.contains("ui_component"));
    }

    /**
     * 另外两份"模型会读到"的文档：Python 侧 android_ui 的组件说明、工作区《使用速查表》的生成源。
     * 2026-10-25 起 note_card/grid_card 已恢复原生实现，文档必须重新教它们，
     * 不能再把名字写成"已删除"。
     */
    @Test
    public void otherModelVisibleDocsTeachRestoredCards() throws Exception {
        String py = read("src/main/python/android_ui.py");
        String guideSource = read(
                "src/main/java/com/oilquiz/app/ai/agent/online/AgentWorkspace.java");

        for (String doc : new String[]{py, guideSource}) {
            for (String kw : new String[]{"playlist", "agenda", "location", "diff"}) {
                assertTrue("模型可见文档缺新卡 " + kw, doc.contains(kw));
            }
            for (String kw : new String[]{"fill", "short"}) {
                assertTrue("模型可见文档缺题型 " + kw, doc.contains(kw));
            }
            for (String kw : new String[]{"stacked_bar", "scatter", "radar", "area"}) {
                assertTrue("模型可见文档缺图型 " + kw, doc.contains(kw));
            }
            for (String kw : new String[]{"script/onclick", ".html/.htm",
                    "Android.submit", "16KB"}) {
                assertTrue("模型可见文档缺 html 分档/回传口径 " + kw, doc.contains(kw));
            }
        }
        // 恢复的实现必须继续教给模型（历史会话与新建都要能命中）
        assertTrue("android_ui 未教恢复的 note_card", py.contains("note_card"));
        assertTrue("android_ui 未教恢复的 grid_card", py.contains("grid_card"));
        // update 不许再说"所有类型通用"：聊天流卡片（info_card/html/chart…）传 props
        // 在 PythonToolManager.updateComponent 里明确返回 not_supported，教了就是让模型白跑一次调用
        String uiTool = read("src/main/java/com/oilquiz/app/ai/tool/SystemUIComponentTool.java");
        assertTrue("ui_component 仍在教 update 所有类型通用", !uiTool.contains("所有类型通用"));
    }

    /**
     * Termux 显式引擎（2026-10-25）：Agent 的 shell/python 此前够不到 Termux（沙箱 cwd 只读、
     * 无 apt/pip/git、无法 tmux 长跑）。补了 linux_shell(engine=termux / termux_wake / termux_status)
     * 与 Python 的 android_termux 桥接。功能加了但 schema/指南没教，模型就不会用——四处同源都得断言。
     */
    @Test
    public void termuxEngineIsTaughtToModel() throws Exception {
        // ① 在线模型真正读的那份硬编码 schema
        String shell = dump(AIToolManager.getInstance(context).getToolDefinition("linux_shell"));
        assertTrue("在线 linux_shell schema 没教 engine", shell.contains("engine"));
        assertTrue("在线 linux_shell schema 没教 engine=termux", shell.contains("termux"));
        assertTrue("在线 linux_shell schema 没教 termux_wake", shell.contains("termux_wake"));
        assertTrue("在线 linux_shell schema 没教 termux_status", shell.contains("termux_status"));

        String py = dump(AIToolManager.getInstance(context).getToolDefinition("python_execute"));
        assertTrue("在线 python_execute schema 没教 android_termux", py.contains("android_termux"));

        // ② 注解 + 构造两份抄本（PythonExecuteTool.java）
        String pyTool = read("src/main/java/com/oilquiz/app/ai/python/PythonExecuteTool.java");
        assertTrue("PythonExecuteTool 抄本没教 android_termux", pyTool.contains("android_termux"));

        // ③ 工作区指南生成源（AgentWorkspace.java）+ 独立指南文档
        String guideSource = read(
                "src/main/java/com/oilquiz/app/ai/agent/online/AgentWorkspace.java");
        assertTrue("AgentWorkspace 指南没教 engine=termux", guideSource.contains("engine=termux"));
        assertTrue("AgentWorkspace 指南没教 android_termux", guideSource.contains("android_termux"));

        String guideMd = read(
                "src/main/assets/apk_shell/guides/LINUX_TOOLKIT_GUIDE.md");
        assertTrue("LINUX_TOOLKIT_GUIDE 没教 engine=termux", guideMd.contains("engine=termux"));
        assertTrue("LINUX_TOOLKIT_GUIDE 没教 android_termux", guideMd.contains("android_termux"));

        // ④ Python 桥接模块本体存在且暴露 run/wake/available + 超时捞结果的 submit/poll/wait
        String bridge = read("src/main/python/android_termux.py");
        for (String fn : new String[]{"def run(", "def wake(", "def available(",
                "def submit(", "def poll(", "def wait("}) {
            assertTrue("android_termux.py 缺 " + fn, bridge.contains(fn));
        }
        // 异步作业必须走 tmux detached（run() 超时那次拿不到结果，这是"捞回结果"的核心口径）
        assertTrue("android_termux.py 没走 tmux detached", bridge.contains("tmux new-session"));
        assertTrue("android_termux.py 缺完成标记", bridge.contains("_DONE_MARKER"));
    }

    /**
     * run_file 与内联代码同命名空间（2026-10-25）：用文件编辑工具写 .py 再 run_file 执行时，
     * 目标文件跑在裸 namespace，此前拿不到 WORKSPACE_FILES / 标准库预置 / android_helper 星号，
     * 表现为"贴进 python_execute 能跑、存成文件就 NameError"。现在 run_python_file 注入同口径 namespace。
     */
    @Test
    public void runPythonFileInjectsInlineNamespace() throws Exception {
        String helper = read("src/main/python/android_helper.py");
        assertTrue("缺 WORKSPACE 常量注入", helper.contains("WORKSPACE_FILES")
                && helper.contains("_workspace_constants"));
        assertTrue("缺标准库预置注入", helper.contains("_prelude_namespace")
                && helper.contains("_PRELUDE_NAMESPACE"));
        // run_python_file 委托引擎 execute_with_auto_fix（顶层 return/自动修复/装包全对齐内联），
        // 并把 _prelude_namespace() 作为 variables 注入
        assertTrue("run_python_file 未委托引擎（缺全量对齐）",
                helper.contains("engine.execute_with_auto_fix"));
        assertTrue("run_python_file 未注入预置命名空间", helper.contains("variables = _prelude_namespace()"));
    }

    /**
     * 文件编辑/diff 与清理口径（2026-10-25，2026-10-28 随写侧迁移改判）。
     *
     * <p>document_parser（原名 python_file_ops）的 write/edit/replace/diff <b>已经从代码里删掉</b>（整块迁到 file_generator），
     * 所以这里只钉两件事：它的 schema 仍要把用户指去 file_generator；唯一性校验那两条断言
     * 跟着实现搬到了 file_generator，改在那边断言。
     * 工作区 WORKSPACE_TMP/files 与 android_termux 的断言与此无关，保持原样。</p>
     */
    @Test
    public void fileEditDiffAndCleanupAreTaughtToModel() throws Exception {
        String fops = dump(AIToolManager.getInstance(context).getToolDefinition("document_parser"));
        assertTrue("document_parser 没把写动作指向 file_generator", fops.contains("file_generator"));
        assertTrue("document_parser 的描述没把定位说清（文档解析/只读）: " + fops, fops.contains("文档解析（只读）"));

        // 在线/注解/工作区指南都提到 WORKSPACE_TMP（临时区自清），别只有一处
        String pyTool = read("src/main/java/com/oilquiz/app/ai/python/PythonExecuteTool.java");
        assertTrue("buildSafeCode 没注入 WORKSPACE_TMP", pyTool.contains("WORKSPACE_TMP"));
        String guide = read("src/main/java/com/oilquiz/app/ai/agent/online/AgentWorkspace.java");
        assertTrue("工作区指南没提 WORKSPACE_TMP", guide.contains("WORKSPACE_TMP"));
        assertTrue("工作区指南没讲清 files 不自动清", guide.contains("不自动清"));

        // android_termux 作业清理
        String bridge = read("src/main/python/android_termux.py");
        assertTrue("android_termux.py 缺 purge", bridge.contains("def purge("));
        assertTrue("android_termux.py 缺 list_jobs", bridge.contains("def list_jobs("));
        // 容器预置：进 Ubuntu 容器一句调用，不必每次手写 ~/ubuntu -- …
        assertTrue("android_termux.py 缺 container 预置",
                bridge.contains("def _in_container") && bridge.contains("container=False"));

        // edit 的唯一性校验（0 处/多处报错）随实现搬到了 file_generator，口径不能丢
        String fgJava = read("src/main/java/com/oilquiz/app/ai/tool/FileGeneratorTool.java");
        assertTrue("file_generator edit 未做未找到精确匹配校验", fgJava.contains("未找到精确匹配"));
        assertTrue("file_generator edit 未做不唯一校验", fgJava.contains("不唯一"));
    }

    /**
     * Termux 长任务活动卡（2026-10-25）：termux_submit 把长命令 detached 并挂一张自刷新的 job 卡。
     * 卡型必须在 ComponentTypes 目录里注册，工具在线 schema 必须教 termux_submit/termux_purge，
     * 否则模型不知道有"跑完自己更新的活动卡"这条路。
     */
    @Test
    public void termuxLiveActivityCardIsTaughtToModel() throws Exception {
        String shell = dump(AIToolManager.getInstance(context).getToolDefinition("linux_shell"));
        assertTrue("在线 linux_shell 没教 termux_submit", shell.contains("termux_submit"));
        assertTrue("在线 linux_shell 没教 termux_purge", shell.contains("termux_purge"));
        assertTrue("在线 linux_shell 没教 job_id", shell.contains("job_id"));
        assertTrue("在线 linux_shell 没提活动卡自刷新", shell.contains("活动卡"));

        String types = read("src/main/java/com/oilquiz/app/ai/chat/component/ComponentTypes.java");
        // job 是工具挂载卡（withComponent），不进"面向模型的目录"（有 29 条上限），只在 ComponentRegistry 渲染
        assertTrue("ComponentTypes 不该把 job 列为模型可创建类型", !types.contains("SCHEMA.put(\"job\""));
        assertTrue("ComponentTypes 该注明 job 是工具挂载", types.contains("type=job"));

        String registry = read("src/main/java/com/oilquiz/app/ai/chat/component/ComponentRegistry.java");
        assertTrue("ComponentRegistry 没注册 JobCardView", registry.contains("new JobCardView()"));
    }

    /**
     * 长任务进度插槽（2026-10-27）。
     *
     * <p>插槽本体在聊天页（{@code AgentProgressBus}），但模型一开始完全不知道：8cf73a3f 只改了
     * 注解的 description/actions，而在线引擎读的是 {@link AIToolManager#getToolDefinition} 里
     * <b>另一份硬编码 schema</b>——那份没有 progress 动作，模型于是继续反复 create progress_card，
     * 真机上表现为卡片越叠越多、进度还停在快照上。四处同源都要断言，避免再漂一次。</p>
     */
    @Test
    public void longTaskProgressSlotIsTaughtToModel() throws Exception {
        // ① 在线模型真正拿到的硬编码 schema
        String online = dump(AIToolManager.getInstance(context).getToolDefinition("ui_component"));
        assertTrue("在线 schema 的 action 清单没有 progress", online.contains("progress("));
        assertTrue("在线 schema 没教进度插槽", online.contains("进度插槽"));
        assertTrue("在线 schema 没教 action=progress", online.contains("action=progress"));
        assertTrue("在线 schema 还在推荐 progress_card（应明确不要反复建卡）",
                online.contains("不要用 progress_card 反复建卡"));
        // 顺带把另外两个被硬编码副本漏掉的动作补回（注解/实现都有，模型却看不到）
        assertTrue("在线 schema 没教 list_components", online.contains("list_components"));
        assertTrue("在线 schema 没教 close_all_components", online.contains("close_all_components"));

        // ② 注解副本：actions 必须列 progress，action 参数说明也要跟上
        String tool = read("src/main/java/com/oilquiz/app/ai/tool/SystemUIComponentTool.java");
        assertTrue("注解 actions 没列 progress", tool.contains("@Action(name = \"progress\""));
        assertTrue("action 参数说明没列 progress", tool.contains("progress(更新本轮长任务进度插槽"));

        // ③ 工作区速查表 + 在线系统提示词
        String guide = read("src/main/java/com/oilquiz/app/ai/agent/online/AgentWorkspace.java");
        assertTrue("工作区速查表没教 action=progress", guide.contains("action=progress"));
        assertTrue("工作区速查表没教进度插槽", guide.contains("进度插槽"));
        String prompt = read("src/main/java/com/oilquiz/app/ai/agent/online/OnlinePromptBuilder.java");
        assertTrue("在线系统提示词没教进度插槽", prompt.contains("进度插槽"));
        assertTrue("在线系统提示词没教 action=progress", prompt.contains("action=progress"));
    }

    private static String read(String path) throws Exception {
        java.io.File f = new java.io.File(path);
        assertTrue("找不到文档（测试需从模块根目录跑）: " + path, f.isFile());
        return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String dump(ToolDefinition d) {
        StringBuilder sb = new StringBuilder();
        sb.append(d.getDescription()).append('\n');
        for (ParamDefinition p : d.getParameters()) {
            sb.append(p.getName()).append(':').append(p.getDescription()).append('\n');
        }
        return sb.toString();
    }
}
