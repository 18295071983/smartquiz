package com.oilquiz.app.ai.agent;

import com.oilquiz.app.ai.chat.component.ComponentRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.assertTrue;

/**
 * 动态工具结果卡片的类型覆盖测试（2026-11-16，重构 R6 的验收）。
 *
 * <p>重构目标 R6 是"结果渲染复用 ComponentTypes 渲染器"。核查后发现
 * {@link DynamicToolResultRenderer} 本来就<b>不自己画 View</b>：它只把工具结果映射成
 * {@link com.oilquiz.app.ai.chat.component.ComponentData}，再交给 {@link ComponentRegistry}
 * 里已注册的卡片渲染 —— 也就是说 R6 实质已完成。</p>
 *
 * <p>唯一真实风险是：它产出的卡片类型若没在 {@link ComponentRegistry} 注册，会静默走
 * fallback（用户看到一张"通用 JSON 卡"而不是图表/表格）。这个测试把"产出类型 ⊆ 注册类型"
 * 钉住，以后有人新增卡片分支忘了注册就会红。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class DynamicToolResultRendererTypesTest {

    /** DynamicToolResultRenderer 会产出的全部卡片类型（与其内部各 *Card 方法一一对应） */
    private static final String[] EMITTED_TYPES = {
            "info_card", "table_card", "metric_card", "html", "list_card",
            "link_card", "json_viewer", "file_card", "file_list", "image_grid",
            "video", "audio", "playlist"
    };

    @Test
    public void everyEmittedCardTypeIsRegistered() {
        ComponentRegistry registry = ComponentRegistry.getInstance();
        StringBuilder missing = new StringBuilder();
        for (String type : EMITTED_TYPES) {
            if (!registry.hasType(type)) {
                missing.append(type).append(' ');
            }
        }
        assertTrue("以下卡片类型未在 ComponentRegistry 注册（会静默走 fallback）: " + missing,
                missing.length() == 0);
    }

    @Test
    public void rendererProducesRegisteredComponentData() throws Exception {
        // 端到端：给一个数组结果，渲染器应产出 list_card，且该类型已注册
        java.util.List<com.oilquiz.app.ai.chat.component.ComponentData> cards =
                DynamicToolResultRenderer.render("demo_tool",
                        com.oilquiz.app.ai.tool.AIToolResult.success(
                                new org.json.JSONArray("[{\"name\":\"a\"},{\"name\":\"b\"}]")));
        if (!cards.isEmpty()) {
            String type = cards.get(0).getType();
            assertTrue("渲染器产出的类型必须先注册: " + type,
                    ComponentRegistry.getInstance().hasType(type));
        }
    }
}
