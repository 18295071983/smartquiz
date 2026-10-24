package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.component.ComponentRegistry;
import com.oilquiz.app.ai.chat.component.ComponentTypes;
import com.oilquiz.app.ai.chat.component.LayoutPrimitiveCardView;

import org.junit.Test;

import java.util.Map;

/**
 * 组件名字账本的一致性（2026-10-20 收口）。
 *
 * <p>这一类 bug 的特征是<b>不报错、只是悄悄换个长相</b>：名字对不上时
 * {@code ComponentRegistry#render} 会落到 {@code DynamicCardView} 通配兜底
 * （标题 + content + 键值行 + 折叠的原始 JSON），所以目录/工具文案/拦截表三份名单
 * 各自漂移时可以长期没人发现。本次查实的三处都靠本测试钉住：
 * <ol>
 *   <li>canonical 卡片短名不许出现在原生弹窗拦截表里（曾经 {@code list}/{@code progress}
 *       被弹窗抢走，模型照目录用就拿不到卡片）；</li>
 *   <li>canonical 短名最终要命中"原生卡片实现"，而不是被 Layout 原语占着；</li>
 *   <li>2026-10-25 从 baafa107^ 恢复的 note_card/grid_card 必须命中各自原生实现，
 *       note/grid 短名同步指向它们（不再归一到 info/list）。</li>
 * </ol>
 */
public class ComponentNameIntegrityTest {

    /** ①：目录里教模型用的 canonical 名，一个都不许被原生弹窗表抢走 */
    @Test
    public void canonicalCardNamesAreNotInterceptedAsDialogs() {
        for (String canonical : ComponentTypes.CANONICAL_TARGET.keySet()) {
            assertFalse("canonical 卡片名被原生弹窗表占用: " + canonical,
                    NativeComponentTypes.isModelNative(canonical));
        }
        // 本次让出来的两个短名确实归卡片，替代名确实在弹窗表里
        assertFalse(NativeComponentTypes.isModelNative("list"));
        assertFalse(NativeComponentTypes.isModelNative("progress"));
        assertTrue(NativeComponentTypes.isModelNative("list_picker"));
        assertTrue(NativeComponentTypes.isModelNative("progress_dialog"));
    }

    /** ②：canonical 短名要命中原生卡片实现，而不是 Layout 原语 */
    @Test
    public void canonicalNamesResolveToNativeCardImplementations() {
        ComponentRegistry registry = ComponentRegistry.getInstance();
        for (Map.Entry<String, String> e : ComponentTypes.CANONICAL_TARGET.entrySet()) {
            Object impl = registry.componentFor(e.getKey());
            assertNotNull("canonical 名没有渲染实现: " + e.getKey(), impl);
            assertFalse("canonical 名仍被 Layout 原语占着: " + e.getKey(),
                    impl instanceof LayoutPrimitiveCardView);
        }
        // 名字争夺的四个现场：短名与旧名都落到原生卡，原语靠 primitive_ 逃生口
        assertEquals("TableCardView", registry.componentFor("table").getClass().getSimpleName());
        assertEquals("TableCardView", registry.componentFor("table_card").getClass().getSimpleName());
        assertEquals("MetricCardView", registry.componentFor("stat").getClass().getSimpleName());
        assertEquals("StepsCardView", registry.componentFor("timeline").getClass().getSimpleName());
        assertNotSame(registry.componentFor("table"), registry.componentFor("primitive_table"));
        assertTrue(registry.componentFor("primitive_table") instanceof LayoutPrimitiveCardView);
    }

    /** ③：恢复的 note_card/grid_card 必须有自己的实现，短名也不许再归一到 info/list */
    @Test
    public void restoredLegacyNamesResolveToTheirOwnCards() {
        ComponentRegistry registry = ComponentRegistry.getInstance();
        assertEquals("note_card", ComponentTypes.resolve("note_card"));
        assertEquals("grid_card", ComponentTypes.resolve("grid_card"));
        assertEquals("note_card", ComponentTypes.resolve("note"));
        assertEquals("grid_card", ComponentTypes.resolve("grid"));
        assertEquals("NoteCardView", registry.componentFor("note_card").getClass().getSimpleName());
        assertEquals("GridCardView", registry.componentFor("grid_card").getClass().getSimpleName());
        assertEquals("NoteCardView", registry.componentFor("note").getClass().getSimpleName());
        assertEquals("GridCardView", registry.componentFor("grid").getClass().getSimpleName());
    }

    /** 原语逃生口现在有名字可用，且目录把这件事告诉了模型 */
    @Test
    public void primitiveEscapeHatchIsRegisteredAndDocumented() {
        ComponentRegistry registry = ComponentRegistry.getInstance();
        for (String primitive : LayoutPrimitiveCardView.BRIDGED_TYPES) {
            Object impl = registry.componentFor("primitive_" + primitive);
            assertNotNull("原语逃生口未注册: primitive_" + primitive, impl);
            assertTrue("primitive_ 前缀应命中原语实现: " + primitive,
                    impl instanceof LayoutPrimitiveCardView);
        }
        assertTrue(ComponentTypes.PRIMITIVE_HINT.contains("primitive_"));
    }

    /** 弹窗表本身自洽：替代名指向的内部名必须真实存在，且表里只剩一份 */
    @Test
    public void nativeTableNameIsSelfConsistent() {
        for (Map.Entry<String, String> e : NativeComponentTypes.MODEL_TO_INTERNAL.entrySet()) {
            assertTrue("别名指向了不存在的内部类型: " + e.getValue(),
                    NativeComponentTypes.INTERNAL_NAMES.contains(e.getValue()));
            assertTrue("别名键本身才是模型可见名: " + e.getKey(),
                    NativeComponentTypes.MODEL_NAMES.contains(e.getKey()));
            assertFalse("模型可见名不该等于让给卡片的内部名: " + e.getKey(),
                    NativeComponentTypes.MODEL_NAMES.contains(e.getValue()));
        }
        assertEquals("list", NativeComponentTypes.internalName("list_picker"));
        assertEquals("progress", NativeComponentTypes.internalName("progress_dialog"));
        assertEquals("dialog", NativeComponentTypes.internalName("dialog"));
        assertEquals("list_picker", NativeComponentTypes.modelAliasFor("list"));
        assertEquals("progress_dialog", NativeComponentTypes.modelAliasFor("progress"));
        // 让出名字的项在内部表里存在，但不在模型表里
        assertTrue(NativeComponentTypes.INTERNAL_NAMES.contains("list"));
        assertTrue(NativeComponentTypes.isReservedForCards("list"));
        assertFalse(NativeComponentTypes.MODEL_NAMES.contains("list"));
        assertFalse(NativeComponentTypes.MODEL_NAMES.contains("progress"));
    }

    /** 目录里的每条 alias 都必须指向一个 canonical 名（不能指向另一个 alias） */
    @Test
    public void aliasesPointAtCanonicalNames() {
        for (Map.Entry<String, String> e : ComponentTypes.ALIAS.entrySet()) {
            assertTrue("alias 目标不是 canonical: " + e.getKey() + " → " + e.getValue(),
                    ComponentTypes.CANONICAL_TARGET.containsKey(e.getValue())
                            || ComponentTypes.isScene(e.getValue())
                            || ComponentRegistry.getInstance().hasType(e.getValue()));
        }
    }
}
