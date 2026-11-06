package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.oilquiz.app.ai.chat.component.ComponentActions;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 卡片 action 默认 callback 的判据（2026-11-06 真机回归）：
 * 有 link/copy/app/package/value 链接的按钮不能被补成纯 callback，
 * 否则点击只剩「已选择」toast，打开/复制动作被吞掉。
 */
public class SystemUIComponentActionEffectTest {

    @Test
    public void pureCallbackValueHasNoOwnEffect() throws Exception {
        assertFalse(ComponentActions.hasOwnEffect(
                new JSONObject().put("label", "好").put("value", "ok")));
    }

    @Test
    public void linkCopyAppPackageValueUrlsHaveOwnEffect() throws Exception {
        assertTrue(ComponentActions.hasOwnEffect(
                new JSONObject().put("label", "打开").put("link", "https://x.com")));
        assertTrue(ComponentActions.hasOwnEffect(
                new JSONObject().put("label", "复制").put("copy", "106.2,38.4")));
        assertTrue(ComponentActions.hasOwnEffect(
                new JSONObject().put("label", "微信").put("action", "open_app").put("app", "微信")));
        assertTrue(ComponentActions.hasOwnEffect(
                new JSONObject().put("label", "导航").put("value", "amapuri://navi?lon=1")));
        assertTrue(ComponentActions.hasOwnEffect(
                new JSONObject().put("label", "打开").put("value", "com.tencent.mm")));
    }
}
