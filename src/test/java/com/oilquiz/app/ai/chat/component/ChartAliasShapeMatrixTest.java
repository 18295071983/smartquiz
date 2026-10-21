package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 别名形状全覆盖守卫（饼图事故的延伸排查，2026-10-21）。
 *
 * <p>饼图那次查实的问题不是"某个卡写错字段"，而是<b>结构性的</b>：
 * {@code pie_chart/bar_chart/stat/timeline/progress_ring…} 这些名字被 canonical 归一到原生卡
 * （"原生实现赢名字"），可工具说明里教给模型的**参数形状仍是 layout 原语那一套**。
 * 名字换了、形状没换 → 模型照说明传 → {@code canRender=false} →
 * {@code ComponentRegistry.render} 静默落 {@link DynamicCardView}，用户看到一堆原始 JSON，
 * 而模型以为卡片画好了。</p>
 *
 * <p>所以本测试不测 canonical 的 {@code SCHEMA}（那是 {@link ComponentFieldIntegrityTest} 的活），
 * 专门按<b>工具说明对每个别名教的字面形状</b>造 props，逐个要求能被渲出。
 * 任何一条红，就等于"模型照说明做会失败"。</p>
 */
public class ChartAliasShapeMatrixTest {

    /** 工具说明里对每个别名教的形状（照抄措辞，不是我以为的形状）。 */
    private static final String[][] ALIAS_SHAPES = {
            // 图表族
            {"chart", "{\"chartType\":\"pie\",\"categories\":[\"a\"],\"series\":[{\"name\":\"n\",\"data\":[1]}]}"},
            {"line_chart", "{\"categories\":[\"a\",\"b\"],\"series\":[{\"name\":\"n\",\"data\":[1,2]}]}"},
            {"bar_chart", "{\"categories\":[\"a\",\"b\"],\"series\":[{\"name\":\"n\",\"data\":[1,2]}]}"},
            {"pie_chart", "{\"data\":[{\"name\":\"晴\",\"value\":3},{\"name\":\"雨\",\"value\":1}]}"},
            {"sparkline", "{\"data\":[1,2,3,4]}"},
            // 数据卡族
            {"table", "{\"headers\":[\"列\"],\"rows\":[[\"值\"]]}"},
            {"steps", "{\"steps\":[{\"title\":\"一步\"}]}"},
            {"timeline", "{\"items\":[{\"title\":\"一件事\"}]}"},
            {"alert", "{\"type\":\"warning\",\"title\":\"注意\",\"content\":\"正文\"}"},
            {"stat", "{\"label\":\"今日最高\",\"value\":\"17.7\"}"},
            {"metric_card", "{\"title\":\"指标卡\",\"metrics\":[{\"label\":\"湿度\",\"value\":\"50%\"}]}"},
            {"empty", "{\"icon\":\"★\",\"title\":\"暂无数据\"}"},
            {"notice", "{\"icon\":\"i\",\"text\":\"通知内容\"}"},
            {"progress_ring", "{\"progress\":68}"},
            {"progress_card", "{\"title\":\"本周进度\",\"progress\":68}"},
            // 内容/媒体族
            {"info_card", "{\"title\":\"信息\",\"items\":[{\"label\":\"a\",\"value\":\"b\"}]}"},
            {"list_card", "{\"title\":\"列表\",\"items\":[{\"title\":\"条目\"}]}"},
            {"table_card", "{\"title\":\"表格\",\"headers\":[\"列\"],\"rows\":[[\"值\"]]}"},
            {"image_grid", "{\"images\":[\"https://a/b.png\"],\"columns\":2}"},
            {"link_card", "{\"url\":\"https://a.b\",\"title\":\"链接\"}"},
            {"json_viewer", "{\"title\":\"JSON\",\"data\":{\"k\":\"v\"}}"},
            {"json", "{\"title\":\"JSON\",\"data\":{\"k\":\"v\"}}"},
            {"steps_card", "{\"title\":\"步骤\",\"steps\":[{\"title\":\"一步\"}]}"},
            {"file_list", "{\"title\":\"文件\",\"files\":[{\"name\":\"a.xlsx\"}]}"},
            {"contact_card", "{\"type\":\"phone\",\"title\":\"客服\",\"value\":\"13800000000\"}"},
            {"todo_card", "{\"title\":\"待办\",\"items\":[{\"done\":true,\"text\":\"一项\"}]}"},
            {"quiz_card", "{\"type\":\"single\",\"question\":\"1+1?\",\"options\":[\"1\",\"2\"],\"answer\":\"1\"}"},
            {"weather_card", "{\"city\":\"北京\",\"temp\":\"18\",\"text\":\"晴\"}"},
            {"file_card", "{\"name\":\"a.xlsx\",\"size\":\"12KB\",\"type\":\"xlsx\",\"path\":\"/w/a.xlsx\"}"},
            {"code_card", "{\"language\":\"java\",\"code\":\"int a=1;\",\"title\":\"代码\"}"},
            {"html", "{\"html\":\"<h3>标题</h3>\",\"title\":\"富文本\"}"},
            {"markdown_card", "{\"content\":\"**加粗** 文本\",\"title\":\"MD\"}"},
            {"video", "{\"url\":\"https://a/b.mp4\",\"title\":\"视频\"}"},
            {"audio", "{\"url\":\"https://a/b.mp3\",\"title\":\"音频\",\"artist\":\"某人\"}"},
    };

    @Test
    public void everyDocumentedAliasShapeIsRenderable() {
        StringBuilder broken = new StringBuilder();
        for (String[] row : ALIAS_SHAPES) {
            String type = row[0];
            ComponentData data = ComponentData.of(type, parse(row[1]));
            ChatComponent c = ComponentRegistry.getInstance().componentFor(type);
            if (c == null) {
                broken.append("\n  ").append(type).append(" → 注册表解析不到实现");
                continue;
            }
            boolean ok;
            try {
                ok = c.canRender(data);
            } catch (Throwable t) {
                broken.append("\n  ").append(type).append(" → canRender 抛 ").append(t);
                continue;
            }
            if (!ok) {
                broken.append("\n  ").append(type).append(" → canRender=false（实现=")
                        .append(c.getClass().getSimpleName()).append("，会掉原始 JSON 兜底）");
            }
        }
        assertFalse("以下名字按工具说明教的形状传参会渲不出卡：" + broken, broken.length() > 0);
    }

    /**
     * 凡是被 canonical/alias "抢过名字"的类型，必须命中原生卡而不是 layout 原语——
     * 因为抢名字就等于换了参数形状，落在原语上说明归一没做完。
     *
     * <p>反过来，{@code empty}/{@code notice} 这类<b>没有原生对应卡</b>的名字本就不是 alias，
     * 由原语渲是设计如此，不该被这条断言误伤。</p>
     *
     * <p>已知欠账（不是 bug，改了会换观感，需产品拍）：B1-2 那轮"原生实现赢名字"把
     * {@code bar_chart/pie_chart/sparkline} 归到了 {@link ChartCardView}，<b>唯独漏了 {@code line_chart}</b>
     * → 它仍由原语渲，能出图但与同族长得不一样。要统一就给 {@code ComponentTypes.ALIAS}
     * 加 {@code line_chart→chart}（届时本测试会自动把它纳入断言）。</p>
     */
    @Test
    public void renamedTypesResolveToNativeCardsNotPrimitives() {
        for (String[] row : ALIAS_SHAPES) {
            String name = row[0];
            boolean nameWasClaimed = ComponentTypes.ALIAS.containsKey(name)
                    || ComponentTypes.isCanonical(name)
                    || ComponentTypes.isScene(name);
            if (!nameWasClaimed) {
                continue;
            }
            ChatComponent c = ComponentRegistry.getInstance().componentFor(name);
            assertFalse("被抢了名字却没有实现: " + name, c == null);
            assertFalse("名字已归一到原生卡、实现却仍落在 layout 原语（归一没做完）: " + name,
                    c instanceof LayoutPrimitiveCardView);
        }
    }

    private static JSONObject parse(String json) {
        try {
            return new JSONObject(json);
        } catch (org.json.JSONException e) {
            throw new RuntimeException(e);
        }
    }
}
