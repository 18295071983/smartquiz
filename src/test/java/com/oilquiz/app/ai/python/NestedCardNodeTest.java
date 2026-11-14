package com.oilquiz.app.ai.python;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * layout 控件树里的「嵌套卡片类型」（2026-11-15，Robolectric）。
 *
 * <p>真机边界：{@code {"type":"key_value","items":[["k1","v1"]]}} 作为 layout 子节点时，
 * 校验器已放行（服务端 200），但渲染器只认 register_type 模板 / 插件 render.layout /
 * 节点自带 layout，三条都不匹配 → 灰字「这部分无法显示」，k1 不出现。</p>
 *
 * <p>本类锁住修好之后的口径：</p>
 * <ol>
 *   <li>ComponentRegistry 注册的卡片类型作为子节点 → 内嵌渲染成卡片视图（k1/v1 出现在 View 树里）；</li>
 *   <li>不认识的 type 仍是灰字「这部分无法显示」（原有降级不变）；</li>
 *   <li>嵌套卡片深度上限：layout 卡里再套 layout 卡最多允许 2 层（深度 0/1/2），
 *       更深一层换成灰字「嵌套太深」，不再递归；</li>
 *   <li>卡片自己的 items 是数据、不是子节点，绝不能被当 children 递归。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
public class NestedCardNodeTest {

    /** 真机那条：layout 里 [text, key_value, compare] 三兄弟。 */
    @Test
    public void nestedCardTypesRenderInlineInsteadOfGrayPlaceholder() throws Exception {
        JSONObject layout = column(
                textNode("NEST-KV"),
                new JSONObject("{\"type\":\"key_value\",\"items\":[[\"k1\",\"v1\"]]}"),
                new JSONObject("{\"type\":\"compare\",\"items\":[{"
                        + "\"title\":\"基础版\",\"rows\":[[\"c1\",\"c2\"]]}]}"));

        List<String> texts = texts(render(layout, new JSONObject()));

        assertTrue("同级 text 节点必须照常渲染: " + texts, texts.contains("NEST-KV"));
        assertTrue("key_value 的键名 k1 要出现（此前整块灰字）: " + texts, texts.contains("k1"));
        assertTrue("key_value 的值 v1 要出现: " + texts, texts.contains("v1"));
        assertTrue("compare 的标题要出现: " + texts, texts.contains("基础版"));
        assertFalse("嵌套卡不该再落灰字: " + texts, texts.contains("这部分无法显示"));
    }

    /** 原有降级不能被这次改动吃掉：未知 type 仍是一条灰字，其余节点照常。 */
    @Test
    public void unknownTypeStillFallsBackToGrayPlaceholder() throws Exception {
        JSONObject layout = column(textNode("OK"),
                new JSONObject("{\"type\":\"no_such_card_xyz\"}"));

        List<String> texts = texts(render(layout, new JSONObject()));

        assertTrue(texts.contains("OK"));
        assertTrue("未知 type 仍要灰字降级: " + texts, texts.contains("这部分无法显示"));
    }

    /** 两层嵌套（layout 卡里再套 layout 卡，深度 0/1/2）仍然渲染出内容。 */
    @Test
    public void twoLevelsOfNestedCardsStillRenderContent() throws Exception {
        JSONObject keyValue = new JSONObject("{\"type\":\"key_value\",\"items\":[[\"k1\",\"v1\"]]}");
        JSONObject inner = layoutCard(column(keyValue));
        JSONObject outer = layoutCard(column(inner));

        List<String> texts = texts(render(column(outer), new JSONObject()));

        assertTrue("两层嵌套内的 key_value 要渲染: " + texts, texts.contains("k1"));
        assertFalse("没超上限不该出现「嵌套太深」: " + texts, texts.contains("嵌套太深"));
    }

    /** 深度上限：第 3 层卡片（深度 3）降级成灰字「嵌套太深」，更深的卡片内容不渲染。 */
    @Test
    public void nestedCardBeyondDepthLimitShowsTooDeep() throws Exception {
        JSONObject keyValue = new JSONObject("{\"type\":\"key_value\",\"items\":[[\"deep-k\",\"deep-v\"]]}");
        JSONObject third = layoutCard(column(keyValue));
        JSONObject second = layoutCard(column(third));
        JSONObject first = layoutCard(column(second));

        List<String> texts = texts(render(column(first), new JSONObject()));

        assertTrue("超过深度上限要灰字「嵌套太深」: " + texts, texts.contains("嵌套太深"));
        assertFalse("过深的卡片内容不能再渲染: " + texts, texts.contains("deep-k"));
    }

    /**
     * 卡片节点的 items 是数据而不是子节点：这里塞一个「如果是 children 就会画出文字」的形状，
     * 断言那行文字不出现（key_value 只把它当数据解析，解析不出条目 → 空态卡）。
     */
    @Test
    public void cardItemsAreDataNotChildren() throws Exception {
        JSONObject node = new JSONObject();
        node.put("type", "key_value");
        JSONArray items = new JSONArray();
        items.put(new JSONObject("{\"type\":\"column\",\"children\":["
                + "{\"type\":\"text\",\"text\":\"ITEMS-AS-CHILDREN\"}]}"));
        node.put("items", items);

        List<String> texts = texts(render(column(node), new JSONObject()));

        assertFalse("items 是卡片数据，不能当 children 递归: " + texts,
                texts.contains("ITEMS-AS-CHILDREN"));
    }

    /** 卡片 props 里的绑定按当前 props.data 求一次值（卡片没有输入重算通道，只能静态）。 */
    @Test
    public void cardPropsBindingsResolveOnceAgainstPropsData() throws Exception {
        JSONObject props = new JSONObject();
        props.put("data", new JSONObject().put("name", "答题宝"));
        JSONObject node = new JSONObject(
                "{\"type\":\"key_value\",\"items\":[[\"name\",{\"$\":\"data.name\"}]]}");

        List<String> texts = texts(render(column(node), props));

        assertTrue("嵌套卡 props 的 {\"$\":\"data.name\"} 要求值: " + texts, texts.contains("答题宝"));
    }

    // ---- helpers ----

    private static View render(JSONObject layout, JSONObject props) {
        Context context = ApplicationProvider.getApplicationContext();
        Map<String, Object> refs = new HashMap<>();
        View root = NativeLayoutRenderer.render(context, layout, props, refs);
        assertNotNull("layout 渲染不该返回 null", root);
        return root;
    }

    private static JSONObject textNode(String text) throws Exception {
        return new JSONObject().put("type", "text").put("text", text);
    }

    private static JSONObject column(Object... children) throws Exception {
        JSONObject node = new JSONObject();
        node.put("type", "column");
        JSONArray arr = new JSONArray();
        for (Object child : children) arr.put(child);
        node.put("children", arr);
        return node;
    }

    /** layout 卡：卡片类型 layout（ComponentRegistry.LayoutCardView）+ 内层控件树。 */
    private static JSONObject layoutCard(JSONObject innerLayout) throws Exception {
        JSONObject node = new JSONObject();
        node.put("type", "layout");
        node.put("props", new JSONObject().put("layout", innerLayout));
        return node;
    }

    private static List<String> texts(View root) {
        List<String> out = new ArrayList<>();
        collectTexts(root, out);
        return out;
    }

    private static void collectTexts(View view, List<String> out) {
        if (view == null) return;
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) out.add(text.toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTexts(group.getChildAt(i), out);
            }
        }
    }
}
