package com.oilquiz.app.ai.chat.component;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CardActionTargetTest {

    @Test
    public void parsesAmStartComponent() {
        assertEquals("com.tencent.mm/.ui.LauncherUI",
                CardActionTarget.parseAmTarget(
                        "am start -n com.tencent.mm/.ui.LauncherUI --es from card"));
    }

    @Test
    public void parsesAmStartUrl() {
        assertEquals("https://example.com/a?b=1",
                CardActionTarget.parseAmTarget(
                        "am start -a android.intent.action.VIEW -d https://example.com/a?b=1"));
    }

    @Test
    public void parsesAmStartPackageFlagAndBarePackage() {
        assertEquals("com.tencent.mm",
                CardActionTarget.parseAmTarget("am start -p com.tencent.mm"));
        assertEquals("com.tencent.mm",
                CardActionTarget.parseAmTarget("am start com.tencent.mm"));
    }

    @Test
    public void recognizesPackagesAndUrls() {
        assertTrue(CardActionTarget.looksLikePackage("com.tencent.mm"));
        assertFalse(CardActionTarget.looksLikePackage("微信"));
        assertFalse(CardActionTarget.looksLikePackage("report.md"));
        assertTrue(CardActionTarget.isHttpUrl("https://example.com"));
        assertFalse(CardActionTarget.isHttpUrl("com.tencent.mm"));
    }

    @Test
    public void planLaunchCarriesActionForSystemResource() {
        // system_resource.execute 缺 action 会直接回失败结果：卡片按钮必须自己声明 open_app，
        // 否则点击永远报"缺少参数: action"、应用起不来。
        ComponentActions.LaunchPlan plan =
                ComponentActions.planLaunch("微信", "", "");
        assertEquals("open_app", plan.toToolParams().get("action"));
        assertEquals("微信", plan.toToolParams().get("app"));
        assertFalse(plan.isEmpty());
    }

    @Test
    public void planLaunchResolvesAmAndBarePackage() {
        ComponentActions.LaunchPlan component =
                ComponentActions.planLaunch("", "", "am start -n com.tencent.mm/.ui.LauncherUI");
        assertEquals("com.tencent.mm", component.packageName);
        assertEquals("com.tencent.mm",
                ComponentActions.planLaunch("", "", "com.tencent.mm").packageName);
        assertEquals("", ComponentActions.planLaunch("", "", "com.tencent.mm").appName);
    }

    @Test
    public void planLaunchKeepsUrlAndRejectsOtherAmSubcommands() {
        assertEquals("https://example.com",
                ComponentActions.planLaunch("", "", "am start -d https://example.com").url);
        assertTrue(ComponentActions.planLaunch("", "", "am force-stop com.tencent.mm").error
                .contains("shell_command"));
        assertTrue(ComponentActions.planLaunch("", "", "").isEmpty());
    }
}
