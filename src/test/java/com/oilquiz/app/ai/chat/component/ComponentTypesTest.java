package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 组件类型目录口径（B1-2）。
 *
 * <p>钉住三件事：旧名/重复名必须归一到 canonical；canonical 必须真的指向原生卡片实现
 * （而不是被 Layout 原语占着）；原语不能因为归一而被弄丢（primitive_ 逃生口）。
 * 这三条挂了，模型选类型就会拿到"名字对不上长相/参数"的卡片。
 */
public class ComponentTypesTest {

    @Test
    public void legacyAndDuplicateNamesResolveToCanonical() {
        assertEquals("table", ComponentTypes.resolve("table_card"));
        assertEquals("steps", ComponentTypes.resolve("steps_card"));
        assertEquals("steps", ComponentTypes.resolve("timeline"));
        assertEquals("metric", ComponentTypes.resolve("stat"));
        assertEquals("metric", ComponentTypes.resolve("metric_card"));
        assertEquals("alert", ComponentTypes.resolve("alert_card"));
        assertEquals("chart", ComponentTypes.resolve("bar_chart"));
        assertEquals("html", ComponentTypes.resolve("web"));
        assertEquals("quiz", ComponentTypes.resolve("question_card"));
        assertEquals("list", ComponentTypes.resolve("grid"));
    }

    @Test
    public void unknownNamesArePassedThrough() {
        assertEquals("some_custom_type", ComponentTypes.resolve("some_custom_type"));
        assertEquals("file_list", ComponentTypes.resolve("file_list"));
        assertEquals(null, ComponentTypes.resolve(null));
    }

    @Test
    public void canonicalNamesPointAtNativeImplementations() {
        ComponentRegistry registry = ComponentRegistry.getInstance();
        for (String canonical : ComponentTypes.CANONICAL_TARGET.keySet()) {
            assertTrue("canonical 未注册: " + canonical, registry.hasType(canonical));
        }
        // 原生卡赢下名字：table 必须解析到 table_card 的实现，而不是 Layout 原语
        ChatComponent table = registry.componentFor("table");
        assertTrue("table 应由 TableCardView 渲染，实际 " + table.getClass().getSimpleName(),
                table instanceof TableCardView);
        ChatComponent steps = registry.componentFor("steps");
        assertTrue("steps 应由 StepsCardView 渲染，实际 " + steps.getClass().getSimpleName(),
                steps instanceof StepsCardView);
    }

    @Test
    public void layoutPrimitivesKeepEscapeHatch() {
        ComponentRegistry registry = ComponentRegistry.getInstance();
        // 名字被 canonical 占用后，原语仍可显式取用
        assertTrue(registry.hasType("primitive_table"));
        assertTrue(registry.hasType("primitive_timeline"));
        assertTrue(registry.componentFor("primitive_table") instanceof LayoutPrimitiveCardView);
        // 没被占用的原语保持原名可用
        assertTrue(registry.hasType("qrcode"));
    }

    @Test
    public void catalogCoversEveryCanonicalType() {
        org.json.JSONArray catalog = ComponentTypes.catalogJson();
        assertNotNull(catalog);
        StringBuilder types = new StringBuilder();
        for (int i = 0; i < catalog.length(); i++) {
            types.append(catalog.optJSONObject(i).optString("type")).append(',');
        }
        for (String canonical : ComponentTypes.CANONICAL_TARGET.keySet()) {
            assertTrue("目录缺少 canonical 类型 " + canonical, types.toString().contains(canonical + ","));
        }
        assertTrue("面向模型的目录应控制在 22 条以内，实际 " + catalog.length(),
                catalog.length() <= 22);
    }

    @Test
    public void aliasTargetsAllExist() {
        for (String alias : ComponentTypes.ALIAS.keySet()) {
            String canonical = ComponentTypes.resolve(alias);
            assertTrue("alias 指向了不存在的 canonical: " + alias + "→" + canonical,
                    ComponentTypes.CANONICAL_TARGET.containsKey(canonical)
                            || ComponentTypes.isScene(canonical));
        }
    }
}
