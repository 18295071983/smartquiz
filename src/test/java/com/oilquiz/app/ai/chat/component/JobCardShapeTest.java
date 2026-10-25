package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Termux 长任务活动卡（type=job）渲染契约（2026-10-25）：
 * 只要 props 带非空 job_id 就该渲染；缺 job_id 不渲染（否则会挂一张无数据源的死卡）。
 */
public class JobCardShapeTest {

    private static ComponentData job(JSONObject props) {
        return ComponentData.of("job", props);
    }

    @Test
    public void rendersOnlyWhenJobIdPresent() {
        JobCardView view = new JobCardView();
        assertEquals("job", view.getType());

        JSONObject with = new JSONObject();
        try {
            with.put("job_id", "build_1");
        } catch (Exception ignored) {
        }
        assertTrue("有 job_id 应可渲染", view.canRender(job(with)));

        assertFalse("缺 job_id 不应渲染", view.canRender(job(new JSONObject())));

        JSONObject blank = new JSONObject();
        try {
            blank.put("job_id", "   ");
        } catch (Exception ignored) {
        }
        assertFalse("空白 job_id 不应渲染", view.canRender(job(blank)));
    }
}
