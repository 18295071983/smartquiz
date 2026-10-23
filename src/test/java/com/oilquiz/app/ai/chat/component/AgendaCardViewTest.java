package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * 日程卡的数据口径（2026-10-23）：解析与按天分组是纯逻辑，这里钉住顺序与"跳过缺字段行"，
 * 避免渲染端出现空行或日期乱序。
 */
public class AgendaCardViewTest {

    @Test
    public void parseSkipsRowsWithoutTitleAndDescription() {
        JSONArray items = arr(
                obj("date", "2026-10-23", "time", "09:00", "title", "晨会"),
                obj("date", "2026-10-23"),
                obj("date", "2026-10-24", "desc", "只有说明"),
                obj("date", "2026-10-24", "name", "别名标题（name）"));
        List<AgendaCardView.Item> out = AgendaCardView.parseItems(items);
        assertEquals(3, out.size());
        assertEquals("晨会", out.get(0).title);
        assertEquals("只有说明", out.get(1).desc);
        assertEquals("别名标题（name）", out.get(2).title);
        assertEquals("todo", out.get(0).status);   // 缺省状态
    }

    @Test
    public void groupByDatePreservesFirstSeenOrder() {
        JSONArray items = arr(
                obj("date", "2026-10-24", "title", "B1"),
                obj("date", "2026-10-23", "title", "A1"),
                obj("date", "2026-10-24", "title", "B2"),
                obj("title", "无日期"));
        List<AgendaCardView.Group> groups =
                AgendaCardView.groupByDate(AgendaCardView.parseItems(items));
        assertEquals(3, groups.size());
        assertEquals("2026-10-24", groups.get(0).date);
        assertEquals(2, groups.get(0).items.size());
        assertEquals("2026-10-23", groups.get(1).date);
        assertEquals("", groups.get(2).date);
    }

    @Test
    public void canRenderNeedsItemsArray() {
        assertTrue(new AgendaCardView().canRender(ComponentData.of("agenda",
                obj("items", arr(obj("date", "2026-10-23", "title", "x"))))));
        assertFalse(new AgendaCardView().canRender(ComponentData.of("agenda", obj("items", arr()))));
        assertFalse(new AgendaCardView().canRender(ComponentData.of("agenda", obj("title", "x"))));
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try {
                o.put(String.valueOf(kv[i]), kv[i + 1]);
            } catch (org.json.JSONException e) {
                throw new RuntimeException(e);
            }
        }
        return o;
    }

    private static JSONArray arr(Object... v) {
        JSONArray a = new JSONArray();
        for (Object o : v) a.put(o);
        return a;
    }
}
