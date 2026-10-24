package com.oilquiz.app.ai.tool;

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
     * 另外两份"模型会读到"的文档：Python 侧 android_ui 的组件说明、工作区《使用速查表》的生成源。
     * 前者曾经在教已删除的 note_card/grid_card 且完全没有新卡——这类文档没有编译期约束，
     * 只能靠源码级断言兜住。
     */
    @Test
    public void otherModelVisibleDocsTeachNewCardsWithoutDeadOnes() throws Exception {
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
            for (String kw : new String[]{"script/onclick", ".html/.htm"}) {
                assertTrue("模型可见文档缺 html 分档口径 " + kw, doc.contains(kw));
            }
        }
        // 已删除的实现不许再被教给模型（有 alias 兜底渲染，但目录/示例不该售卖）
        assertTrue("android_ui 仍在教 note_card", !py.contains("note_card"));
        assertTrue("android_ui 仍在教 grid_card", !py.contains("grid_card"));
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
