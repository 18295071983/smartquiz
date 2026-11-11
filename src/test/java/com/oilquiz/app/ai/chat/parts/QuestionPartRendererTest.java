package com.oilquiz.app.ai.chat.parts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import androidx.test.core.app.ApplicationProvider;

import com.oilquiz.app.ai.chat.turn.AssistantTurn.Part;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;

/**
 * 问答卡渲染器：一次 ask 的多个问题必须在同一张卡里、一次提交整批答案。
 *
 * <p>拆开提交会被 DSH 的 {@code BAD_ANSWER} 拒绝（要求"每个问题恰好一条答案"），
 * 所以这里用真实 View 树点一遍，而不是只测字符串。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class QuestionPartRendererTest {

    private static final String ARGS = "{\"callId\":\"call-9\",\"questions\":["
            + "{\"id\":\"q1\",\"question\":\"选哪个？\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}],\"multiSelect\":false},"
            + "{\"id\":\"q2\",\"question\":\"还要什么？\",\"detail\":\"可自由填写\"}]}";

    @Test
    public void submitSendsWholeBatchWithSelectedAndCustom() {
        Context ctx = ApplicationProvider.getApplicationContext();
        PartHost host = mock(PartHost.class);
        when(host.context()).thenReturn(ctx);

        Part part = Part.question("question:call-9", "call-9", "选哪个？", ARGS,
                Part.APPROVAL_PENDING, 1L);
        QuestionPartRenderer renderer = new QuestionPartRenderer();
        View root = renderer.createView(host, new LinearLayout(ctx));
        renderer.bindView(root, part, host);

        CheckBox optionA = findCheckBox(root, "A");
        assertNotNull("要有 A 选项", optionA);
        optionA.setChecked(true);
        android.widget.EditText free = findEditText(root);
        assertNotNull(free);
        free.setText("补充一句");

        Button submit = findButton(root, "提交回答");
        assertNotNull(submit);
        submit.performClick();

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(host).onQuestionAnswer(eq("question:call-9"), captor.capture());
        String json = captor.getValue();
        assertTrue(json, json.contains("\"id\":\"q1\""));
        assertTrue(json, json.contains("\"selected\":[\"A\"]"));
        assertTrue(json, json.contains("\"custom\":\"补充一句\""));
        assertTrue("第二个问题也要在批次里：" + json, json.contains("\"id\":\"q2\""));
    }

    @Test
    public void answeredStatusHidesSubmit() {
        Context ctx = ApplicationProvider.getApplicationContext();
        PartHost host = mock(PartHost.class);
        when(host.context()).thenReturn(ctx);

        Part part = Part.question("question:call-9", "call-9", "选哪个？", ARGS,
                Part.APPROVAL_APPROVED, 1L);
        QuestionPartRenderer renderer = new QuestionPartRenderer();
        View root = renderer.createView(host, new LinearLayout(ctx));
        renderer.bindView(root, part, host);

        assertEquals("已回答时不该再有提交按钮", null, findButton(root, "提交回答"));
    }

    // ===== 遍历 =====

    private static CheckBox findCheckBox(View v, String label) {
        if (v instanceof CheckBox && label.equals(String.valueOf(((CheckBox) v).getText()))) {
            return (CheckBox) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i += 1) {
                CheckBox found = findCheckBox(g.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Button findButton(View v, String label) {
        if (v instanceof Button && label.equals(String.valueOf(((Button) v).getText()))) {
            return (Button) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i += 1) {
                Button found = findButton(g.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static android.widget.EditText findEditText(View v) {
        if (v instanceof android.widget.EditText) return (android.widget.EditText) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i += 1) {
                android.widget.EditText found = findEditText(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
