package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

/**
 * 卡片动作的"先做事、再回传"口径（2026-11-06 真机回归）。
 *
 * <p>历史 bug：创建卡片时给每个 action 补了 {@code action:"callback"}，点击只走回传 +
 * "已选择"toast，{@code link}/{@code copy} 根本不执行。这里钉住：
 * callback + link 必须先真的打开链接，callback + copy 必须真的进剪贴板；
 * 纯 callback 才只回传。</p>
 */
@RunWith(RobolectricTestRunner.class)
public class ComponentActionsPerformTest {

    @Test
    public void callbackWithLinkOpensTheLinkInsteadOfOnlyToasting() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        // before fix: 这个组合只会 notifyResult + toast，什么都不会启动
        ComponentActions.perform(activity, "① 高德 App 直呼", "https://example.com/nav",
                "", "callback", "chat_test_1", "① 高德 App 直呼");

        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull("callback + link 必须真的把链接打开", started);
        assertEquals(com.oilquiz.app.WebViewActivity.class.getName(), started.getComponent().getClassName());
        assertEquals("https://example.com/nav", started.getStringExtra("url"));
    }

    @Test
    public void callbackWithCopyReallyCopies() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ComponentActions.perform(activity, "复制坐标", "", "106.24,38.52",
                "callback", "chat_test_2", "复制坐标");

        ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull(cm);
        ClipData clip = cm.getPrimaryClip();
        assertNotNull(clip);
        assertEquals("106.24,38.52", clip.getItemAt(0).getText().toString());
        assertNull("复制动作不应该再打开别的页面",
                shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void pureCallbackDoesNotStartAnything() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ComponentActions.perform(activity, "好", "", "", "callback", "chat_test_3", "ok");
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void valueOnlyUrlStillRendersAsExecutableAction() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        JSONObject a = new JSONObject().put("label", "打开").put("value", "https://example.com/x");
        assertNotNull("只有 value 的链接按钮也必须可点", ComponentActions.actionRunner(activity, a));
    }
}
