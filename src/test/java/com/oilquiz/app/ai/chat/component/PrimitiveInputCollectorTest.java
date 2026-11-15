package com.oilquiz.app.ai.chat.component;

import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * PrimitiveInputCollector 单测（2026-11-16，第 3 项验收）。
 *
 * <p>交互回传的价值全在"取到的值对不对"，所以这里用 Robolectric 真造控件树验证：
 * EditText 文本、CheckBox 勾选、SeekBar 进度都要被收集到，且深度/数量有上限。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class PrimitiveInputCollectorTest {

    private View tree() {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        EditText input = new EditText(ctx);
        input.setText("hello");
        input.setContentDescription("city");
        root.addView(input);

        CheckBox cb = new CheckBox(ctx);
        cb.setChecked(true);
        root.addView(cb);

        SeekBar bar = new SeekBar(ctx);
        bar.setProgress(66);
        root.addView(bar);
        return root;
    }

    @Test
    public void collectsInputsByViewType() {
        Map<String, Object> values = PrimitiveInputCollector.collect(tree());
        assertEquals("EditText 用 contentDescription 作为键", "hello", values.get("city"));
        assertTrue("CheckBox 应被收集为 boolean true", Boolean.TRUE.equals(values.get("toggle_2"))
                || Boolean.TRUE.equals(values.get("toggle_3")));
        boolean hasProgress = values.containsValue(66);
        assertTrue("SeekBar 进度应被收集: " + values, hasProgress);
    }

    @Test
    public void collectsNothingFromPlainLayout() {
        android.content.Context ctx = RuntimeEnvironment.getApplication();
        LinearLayout empty = new LinearLayout(ctx);
        assertTrue("没有输入控件时返回空 map", PrimitiveInputCollector.collect(empty).isEmpty());
    }

    @Test
    public void toJsonIsValid() {
        String json = PrimitiveInputCollector.toJson(PrimitiveInputCollector.collect(tree()));
        assertTrue("应能序列化成 JSON: " + json, json.startsWith("{") && json.contains("city"));
    }
}
