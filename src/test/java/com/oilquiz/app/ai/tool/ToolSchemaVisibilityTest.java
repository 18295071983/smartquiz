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

    @Test
    public void pythonChartSchemaTeachesCardMode() {
        String schema = dump(AIToolManager.getInstance(context).getToolDefinition("python_chart"));
        assertTrue("在线 schema 没教 render", schema.contains("render"));
        assertTrue("在线 schema 没教 card 形态", schema.contains("card"));
        assertTrue("在线 schema 没教 chartType 覆盖", schema.contains("chartType"));
        assertTrue("在线 schema 没列原生图型", schema.contains("stacked_bar"));
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
