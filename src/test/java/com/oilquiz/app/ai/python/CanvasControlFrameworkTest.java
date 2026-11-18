package com.oilquiz.app.ai.python;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputLayout;
import com.oilquiz.app.ai.chat.component.CanvasControls;
import com.oilquiz.app.testsupport.ThemedAppContext;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;

/**
 * 动态画布控件接框架标准层（2026-11-18，Robolectric）。
 *
 * <p>背景：{@code NativeLayoutRenderer} 以前直接 {@code new android.widget.Button/EditText/
 * Switch/SeekBar}，画布里是"另一套控件"——没有 M2 涟漪/浮动标签/主题开关，跟框架页面并排
 * 一眼就能看出不是同一套 UI。本类钉住两条：</p>
 * <ol>
 *   <li><b>控件类型</b>：button→{@link MaterialButton}、input→{@link TextInputLayout}+
 *       {@link EditText}、switch→{@link SwitchMaterial}、checkbox→{@link MaterialCheckBox}、
 *       radio→{@link MaterialRadioButton}、slider→{@link Slider}、card→{@link MaterialCardView}、
 *       toggle/tag_input→{@link ChipGroup}+{@link Chip}；</li>
 *   <li><b>取值口径不变</b>：换控件后 {@link NativeLayoutRenderer#collectValues} /
 *       {@link NativeLayoutRenderer#applyValues} 仍按 key 收到/回填正确的值
 *       （这是"换控件最容易悄悄坏掉"的一环）。</li>
 * </ol>
 *
 * <p>不测日期/时间选择器的弹层本身（MaterialDatePicker 依赖 Fragment 宿主），只钉住
 * "字段是框架输入框、未选择时收集空串"。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class CanvasControlFrameworkTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ThemedAppContext.get();
    }

    // ==================== 控件类型 ====================

    @Test
    public void buttonIsMaterialButtonAndKeepsActionTag() {
        Map<String, Object> refs = new HashMap<>();
        View root = render("{\"root\":{\"type\":\"column\",\"children\":[{\"type\":\"button\","
                + "\"text\":\"提交\",\"action\":\"submit\",\"key\":\"b1\"}]}}", refs);

        MaterialButton btn = findFirst(root, MaterialButton.class);
        assertNotNull("button 节点必须渲染成 MaterialButton", btn);
        assertEquals("提交", btn.getText().toString());
        assertEquals("submit", String.valueOf(btn.getTag()));
        assertSame(btn, refs.get("b1"));
    }

    @Test
    public void initialValuesShowUpInFields() {
        Map<String, Object> refs = new HashMap<>();
        render("{\"root\":{\"type\":\"column\",\"children\":["
                + "{\"type\":\"input\",\"label\":\"Name\",\"value\":\"张三\",\"key\":\"n\"},"
                + "{\"type\":\"select\",\"label\":\"Option\",\"options\":[\"A\",\"B\"],"
                + "\"value\":\"B\",\"key\":\"c\"}"
                + "]}}", refs);
        assertEquals("张三", ((EditText) refs.get("n")).getText().toString());
        assertEquals("下拉初始值必须显示为已选项", "B",
                ((EditText) refs.get("c")).getText().toString());
    }

    @Test
    public void inputUsesTextInputLayoutWithEditableInner() {
        Map<String, Object> refs = new HashMap<>();
        View root = render("{\"root\":{\"type\":\"input\",\"label\":\"姓名\",\"key\":\"name\"}}", refs);

        assertTrue("input 节点必须渲染成 TextInputLayout", root instanceof TextInputLayout);
        TextInputLayout til = (TextInputLayout) root;
        assertNotNull("TextInputLayout 里必须有可编辑的 TextInputEditText", til.getEditText());
        assertSame(til.getEditText(), refs.get("name"));
    }

    @Test
    public void switchUsesSwitchMaterial() {
        Map<String, Object> refs = new HashMap<>();
        View root = render("{\"root\":{\"type\":\"switch\",\"text\":\"启用\",\"key\":\"sw\"}}", refs);

        assertTrue("switch 节点必须渲染成 SwitchMaterial", root instanceof SwitchMaterial);
        assertSame(root, refs.get("sw"));
    }

    @Test
    public void checkboxAndRadioUseMaterialWidgets() {
        Map<String, Object> refs = new HashMap<>();
        View cb = render("{\"root\":{\"type\":\"checkbox\",\"text\":\"同意\",\"key\":\"cb\"}}", refs);
        assertTrue(cb instanceof MaterialCheckBox);

        View rg = render("{\"root\":{\"type\":\"radio\",\"options\":[\"A\",\"B\"],\"key\":\"r\"}}", refs);
        assertNotNull(rg);
        assertNotNull("radio 选项必须是 MaterialRadioButton",
                findFirst(rg, MaterialRadioButton.class));
    }

    @Test
    public void sliderUsesMaterialSlider() {
        Map<String, Object> refs = new HashMap<>();
        View root = render("{\"root\":{\"type\":\"slider\",\"min\":10,\"max\":20,\"value\":15,"
                + "\"key\":\"n\"}}", refs);

        assertTrue("slider 节点必须渲染成 Material Slider", root instanceof Slider);
        Slider slider = (Slider) root;
        assertEquals(10f, slider.getValueFrom(), 0.01f);
        assertEquals(20f, slider.getValueTo(), 0.01f);
        assertEquals(15f, slider.getValue(), 0.01f);
    }

    @Test
    public void cardUsesMaterialCardView() {
        View root = render("{\"root\":{\"type\":\"card\",\"title\":\"概览\"}}", new HashMap<>());
        assertTrue("card 节点必须渲染成 MaterialCardView", root instanceof MaterialCardView);
    }

    @Test
    public void toggleUsesChipGroupWithSingleSelection() {
        Map<String, Object> refs = new HashMap<>();
        View root = render("{\"root\":{\"type\":\"toggle\",\"options\":[\"是\",\"否\"],"
                + "\"value\":\"是\",\"key\":\"tg\"}}", refs);

        assertTrue("toggle 节点必须渲染成 ChipGroup", root instanceof ChipGroup);
        ChipGroup group = (ChipGroup) root;
        assertEquals(2, group.getChildCount());
        assertTrue(group.isSingleSelection());
        Chip easy = (Chip) group.getChildAt(0);
        Chip hard = (Chip) group.getChildAt(1);
        assertTrue("初始 value=Easy 时第一个 Chip 必须是选中态", easy.isChecked());
        assertTrue("不应把未选中的 Chip 标成选中", !hard.isChecked());
        assertEquals(easy.getId(), group.getCheckedChipId());
    }

    @Test
    public void stepperSecondaryButtonsAreMarkedOwned() {
        Map<String, Object> refs = new HashMap<>();
        View root = render("{\"root\":{\"type\":\"stepper\",\"min\":0,\"max\":10,\"key\":\"st\"}}", refs);

        MaterialButton minus = findFirst(root, MaterialButton.class);
        assertNotNull(minus);
        assertTrue("步进器 +/- 打了“画布自有交互”标记，宿主不会把它当提交按钮",
                CanvasControls.isOwned(minus));
    }

    // ==================== 取值 / 回填口径 ====================

    @Test
    public void collectValuesStillWorksForFrameworkWidgets() {
        Map<String, Object> refs = new HashMap<>();
        render("{\"root\":{\"type\":\"column\",\"children\":["
                + "{\"type\":\"input\",\"hint\":\"姓名\",\"key\":\"name\"},"
                + "{\"type\":\"switch\",\"key\":\"sw\"},"
                + "{\"type\":\"slider\",\"min\":0,\"max\":100,\"value\":30,\"key\":\"n\"},"
                + "{\"type\":\"select\",\"options\":[\"A\",\"B\"],\"key\":\"choice\"}"
                + "]}}", refs);

        ((EditText) refs.get("name")).setText("张三");
        ((SwitchMaterial) refs.get("sw")).setChecked(true);
        ((Slider) refs.get("n")).setValue(55f);
        // select 现在是 M2 exposed dropdown（MaterialAutoCompleteTextView，本质是 EditText）：
        // 用户从弹层选一项后文本即选中值
        ((com.google.android.material.textfield.MaterialAutoCompleteTextView) refs.get("choice"))
                .setText("B", false);

        Map<String, Object> values = NativeLayoutRenderer.collectValues(refs);
        assertEquals("张三", values.get("name"));
        assertEquals(Boolean.TRUE, values.get("sw"));
        assertEquals(Integer.valueOf(55), values.get("n"));
        assertEquals("B", values.get("choice"));
    }

    @Test
    public void toggleAndTagInputCollectValues() {
        Map<String, Object> refs = new HashMap<>();
        View root = render("{\"root\":{\"type\":\"column\",\"children\":["
                + "{\"type\":\"toggle\",\"options\":[\"是\",\"否\"],\"value\":\"是\",\"key\":\"tg\"},"
                + "{\"type\":\"tag_input\",\"key\":\"tags\",\"tags\":[\"a\",\"b\"]}"
                + "]}}", refs);

        Map<String, Object> values = NativeLayoutRenderer.collectValues(refs);
        assertEquals("是", values.get("tg"));
        assertEquals("[\"a\",\"b\"]", values.get("tags"));
        // 初始标签必须真的渲染成 Chip（不是只进了取值列表）
        ChipGroup tagGroup = findFirst(root, ChipGroup.class);
        assertNotNull("tag_input 必须渲染 ChipGroup", tagGroup);
        assertEquals("初始 tags 应渲染为 2 个 Chip", 2, tagGroup.getChildCount());
    }

    @Test
    public void applyValuesRestoresInputSwitchAndSlider() {
        Map<String, Object> refs = new HashMap<>();
        render("{\"root\":{\"type\":\"column\",\"children\":["
                + "{\"type\":\"input\",\"key\":\"name\"},"
                + "{\"type\":\"switch\",\"key\":\"sw\"},"
                + "{\"type\":\"slider\",\"min\":0,\"max\":100,\"key\":\"n\"}"
                + "]}}", refs);

        Map<String, Object> old = new HashMap<>();
        old.put("name", "李四");
        old.put("sw", true);
        old.put("n", 42);
        NativeLayoutRenderer.applyValues(refs, old);

        assertEquals("李四", ((EditText) refs.get("name")).getText().toString());
        assertTrue(((SwitchMaterial) refs.get("sw")).isChecked());
        assertEquals(42f, ((Slider) refs.get("n")).getValue(), 0.01f);
    }

    @Test
    public void dateFieldIsFrameworkInputAndCollectsEmptyUntilPicked() {
        Map<String, Object> refs = new HashMap<>();
        View root = render("{\"root\":{\"type\":\"date\",\"key\":\"d\"}}", refs);

        assertTrue("date 节点必须是框架输入框（点击弹 M2 选择器），实际是 "
                + root.getClass().getName() + "：" + describe(root), root instanceof TextInputLayout);
        assertEquals("选择字段必须走 outlined 样式（描边盒），不能是下划线 FILLED",
                TextInputLayout.BOX_BACKGROUND_OUTLINE, ((TextInputLayout) root).getBoxBackgroundMode());
        assertEquals("未选择时收集空串，不把占位文本当值", "",
                NativeLayoutRenderer.collectValues(refs).get("d"));
    }

    // ==================== 工具 ====================

    private View render(String json, Map<String, Object> refs) {
        View v = NativeLayoutRenderer.render(ctx, json, new JSONObject(), refs);
        assertNotNull("layout 渲染失败：" + json, v);
        return v;
    }

    /** 断言失败时给出可视化线索（渲染器降级会把错误写成 TextView 文案）。 */
    private static String describe(View v) {
        if (v instanceof android.widget.TextView) {
            CharSequence t = ((android.widget.TextView) v).getText();
            return t == null ? "null" : t.toString();
        }
        return String.valueOf(v);
    }

    private static <T extends View> T findFirst(View root, Class<T> type) {
        if (root == null) return null;
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findFirst(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
