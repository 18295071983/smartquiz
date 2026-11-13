package com.oilquiz.app.ai.chat.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 权限变更卡（2026-11-13）：点权限槽 → 弹卡 → 选一项发 permission.set。
 */
public class PermissionChangeCardTest {

    @Test
    public void itemsCoverSandboxThenPolicy() {
        CharSequence[] items = PermissionChangeCard.items();
        assertEquals(PermissionChangeCard.SANDBOX_LABELS.length
                + PermissionChangeCard.POLICY_LABELS.length, items.length);
        assertEquals("只读（只能读文件）", items[0].toString());
        assertTrue(items[items.length - 1].toString().contains("每次询问"));
    }

    @Test
    public void sandboxChoiceBuildsRequest() {
        JSONObject body = PermissionChangeCard.requestFor(1);
        assertNotNull(body);
        assertEquals("permission.set", body.optString("action"));
        assertEquals("workspace-write", body.optString("sandbox"));
        assertTrue("选沙箱时不该带 policy", body.optString("policy").isEmpty());
    }

    @Test
    public void policyChoiceBuildsRequest() {
        JSONObject body = PermissionChangeCard.requestFor(PermissionChangeCard.SANDBOX_LABELS.length);
        assertNotNull(body);
        assertEquals("never", body.optString("policy"));
        assertTrue(body.optString("sandbox").isEmpty());

        JSONObject ask = PermissionChangeCard.requestFor(PermissionChangeCard.SANDBOX_LABELS.length + 1);
        assertEquals("ask", ask.optString("policy"));
    }

    @Test
    public void outOfRangeYieldsNull() {
        assertNull(PermissionChangeCard.requestFor(-1));
        assertNull(PermissionChangeCard.requestFor(99));
    }

    @Test
    public void titleCarriesCurrentValue() {
        assertEquals("变更权限", PermissionChangeCard.title(""));
        assertEquals("变更权限（当前：workspace-write · never）",
                PermissionChangeCard.title(" workspace-write · never "));
    }
}
