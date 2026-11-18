package com.oilquiz.app.ai.python;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;

/**
 * 表单绑定 + 公式的"同一棵树内实时重算"（Robolectric，非纯 JVM）。
 *
 * <p>纯 JVM 的公式单测只管求值器本身；这一条补上接线：输入框 changed → props.data 更新 →
 * 同一张卡里登记的 text 公式节点原地刷新（不重建 View、不丢焦点）。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class LayoutBindingScopeTest {

    @Test
    public void changingInputRecomputesFormulaAndWritesPropsData() throws Exception {
        // 2026-11-18：input 节点已改成框架的 TextInputLayout（Material 组件），
        // Robolectric 的裸 Application 上下文没套 Material 主题，Material 组件会拒绝创建；
        // 真机上 Application/Activity 上下文都带清单里的 Theme.OilQuizApp，所以这里用带主题的上下文。
        Context context = com.oilquiz.app.testsupport.ThemedAppContext.get();
        JSONObject props = new JSONObject();
        JSONObject data = new JSONObject();
        data.put("a", 2);
        data.put("b", 3);
        data.put("name", "答题宝");
        props.put("data", data);
        JSONObject layout = new JSONObject("{\"type\":\"column\",\"children\":["
                + "{\"type\":\"text\",\"key\":\"name\",\"text\":{\"$\":\"data.name\"}},"
                + "{\"type\":\"text\",\"key\":\"total\",\"text\":{\"=\":\"a * b\"}},"
                + "{\"type\":\"input\",\"key\":\"a\",\"value\":\"2\"},"
                + "{\"type\":\"input\",\"key\":\"b\",\"value\":\"3\"}"
                + "]}");

        Map<String, Object> refs = new HashMap<>();
        View root = NativeLayoutRenderer.render(context, layout, props, refs);
        assertNotNull(root);

        TextView nameView = (TextView) refs.get("text_name");
        TextView totalView = (TextView) refs.get("text_total");
        EditText bInput = (EditText) refs.get("b");
        assertNotNull(nameView);
        assertNotNull(totalView);
        assertNotNull(bInput);
        assertEquals("答题宝", nameView.getText().toString());
        assertEquals("6", totalView.getText().toString());

        // 用户把 b 从 3 改成 5：watcher 写回 props.data.b，并重算 text 公式
        bInput.setText("5");
        assertEquals("10", totalView.getText().toString());
        assertEquals("5", props.getJSONObject("data").optString("b"));
    }
}
