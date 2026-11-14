package com.oilquiz.app.ai.chat.status;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ④-1（2026-11-15）：kind 契约的归一化 / 降级 / 必填校验。
 *
 * <p>与插件 {@code slot.test.mjs} 的 kind 用例同规则：未知 kind → state + warn；
 * progress/list/action 必填不匹配 → warn + 降级 state；旧 display/toggle 静默成 state。
 * 这些断言是"插件/App 两头一致"的 App 侧钉子。</p>
 */
public class SlotKindTest {

    private static JSONObject frame(String kind, String title, String detail) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", "dock-test");
        if (kind != null) o.put("kind", kind);
        o.put("title", title);
        o.put("detail", detail);
        return o;
    }

    // ===== state / 旧别名 =====

    @Test
    public void missingKindDefaultsToStateWithoutWarning() throws Exception {
        SlotKind.Normalized n = SlotKind.normalize(frame(null, "后台任务", "1 个在跑"));
        assertEquals(SlotKind.STATE, n.kind);
        assertFalse(n.hasWarnings());
        assertEquals(0, n.progress);
        assertEquals(0, n.count);
        assertTrue(n.actions.isEmpty());
    }

    @Test
    public void legacyDisplayAndToggleAreSilentAliases() throws Exception {
        for (String legacy : new String[]{"display", "toggle", " DISPLAY "}) {
            SlotKind.Normalized n = SlotKind.normalize(frame(legacy, "旧帧", ""));
            assertEquals("旧别名必须静默归一: " + legacy, SlotKind.STATE, n.kind);
            assertFalse("旧别名不该回 warn: " + legacy, n.hasWarnings());
        }
    }

    @Test
    public void unknownKindFallsBackToStateWithWarning() throws Exception {
        SlotKind.Normalized n = SlotKind.normalize(frame("sparkline", "未知形态", ""));
        assertEquals(SlotKind.STATE, n.kind);
        assertTrue("未知 kind 必须回 warn", n.hasWarnings());
        assertTrue(n.warnings.get(0).contains("sparkline"));
    }

    @Test
    public void stateIgnoresProgressCountAndActions() throws Exception {
        JSONObject f = frame("state", "静止状态", "");
        f.put("progress", 88);
        f.put("count", 9);
        f.put("actions", new JSONArray().put(new JSONObject()
                .put("id", "x").put("label", "不该出现")));
        SlotKind.Normalized n = SlotKind.normalize(f);
        assertEquals(SlotKind.STATE, n.kind);
        assertEquals(0, n.progress);
        assertEquals(0, n.count);
        assertTrue("state 不保留 actions", n.actions.isEmpty());
    }

    // ===== progress =====

    @Test
    public void progressIsClampedAndRounded() throws Exception {
        JSONObject f = frame("progress", "压缩中", "上下文 42%");
        f.put("progress", 42.6);
        SlotKind.Normalized n = SlotKind.normalize(f);
        assertEquals(SlotKind.PROGRESS, n.kind);
        assertEquals(43, n.progress);
        assertFalse(n.hasWarnings());

        f.put("progress", -8);
        assertEquals("下界夹到 0", 0, SlotKind.normalize(f).progress);

        f.put("progress", 180);
        assertEquals("上界夹到 100", 100, SlotKind.normalize(f).progress);

        f.put("progress", "55");
        assertEquals("数字字符串也收", 55, SlotKind.normalize(f).progress);
    }

    @Test
    public void progressWithoutRequiredFieldsDegrades() throws Exception {
        JSONObject noProgress = frame("progress", "缺进度", "");
        SlotKind.Normalized a = SlotKind.normalize(noProgress);
        assertEquals(SlotKind.STATE, a.kind);
        assertTrue(a.hasWarnings());

        JSONObject noTitle = frame("progress", "", "只有副标题");
        noTitle.put("progress", 20);
        SlotKind.Normalized b = SlotKind.normalize(noTitle);
        assertEquals(SlotKind.STATE, b.kind);
        assertTrue(b.hasWarnings());

        JSONObject badValue = frame("progress", "坏进度", "");
        badValue.put("progress", "abc");
        assertEquals(SlotKind.STATE, SlotKind.normalize(badValue).kind);
    }

    // ===== list =====

    @Test
    public void listKeepsPositiveCount() throws Exception {
        JSONObject f = frame("list", "插话排队", "3 条等待");
        f.put("count", 3);
        SlotKind.Normalized n = SlotKind.normalize(f);
        assertEquals(SlotKind.LIST, n.kind);
        assertEquals(3, n.count);
        assertFalse(n.hasWarnings());

        f.put("count", 3.9);
        assertEquals("count 取整", 3, SlotKind.normalize(f).count);
    }

    @Test
    public void listWithoutCountDegrades() throws Exception {
        JSONObject noCount = frame("list", "清单", "");
        assertEquals(SlotKind.STATE, SlotKind.normalize(noCount).kind);

        noCount.put("count", 0);
        SlotKind.Normalized zero = SlotKind.normalize(noCount);
        assertEquals("count=0 不算清单", SlotKind.STATE, zero.kind);
        assertTrue(zero.hasWarnings());

        JSONObject noTitle = frame("list", "", "");
        noTitle.put("count", 2);
        assertEquals(SlotKind.STATE, SlotKind.normalize(noTitle).kind);
    }

    // ===== action =====

    @Test
    public void actionKeepsAtMostTwoSanitizedActions() throws Exception {
        JSONObject f = frame("action", "权限", "");
        JSONArray actions = new JSONArray();
        actions.put(new JSONObject().put("id", "permission").put("label", "调整").put("style", "primary"));
        actions.put(new JSONObject().put("id", "jobs").put("label", "任务"));
        actions.put(new JSONObject().put("id", "extra").put("label", "多余"));
        f.put("actions", actions);

        SlotKind.Normalized n = SlotKind.normalize(f);
        assertEquals(SlotKind.ACTION, n.kind);
        assertEquals(2, n.actions.size());
        assertEquals("permission", n.actions.get(0).id);
        assertEquals(SlotAction.STYLE_PRIMARY, n.actions.get(0).style);
        assertEquals("jobs", n.actions.get(1).id);
        assertEquals(SlotAction.STYLE_PLAIN, n.actions.get(1).style);
        assertTrue("截断必须回 warn", n.hasWarnings());
    }

    @Test
    public void actionWithoutValidActionsDegrades() throws Exception {
        SlotKind.Normalized empty = SlotKind.normalize(frame("action", "权限", ""));
        assertEquals(SlotKind.STATE, empty.kind);
        assertTrue(empty.hasWarnings());

        JSONObject bad = frame("action", "权限", "");
        JSONArray actions = new JSONArray();
        actions.put(new JSONObject().put("id", "").put("label", "空 id"));
        actions.put(new JSONObject().put("label", "无 id"));
        bad.put("actions", actions);
        SlotKind.Normalized n = SlotKind.normalize(bad);
        assertEquals("全部 action 都无效 → state", SlotKind.STATE, n.kind);
        assertTrue(n.hasWarnings());
    }

    @Test
    public void actionInvalidStyleFallsBackToPlain() throws Exception {
        JSONObject f = frame("action", "权限", "");
        JSONArray actions = new JSONArray();
        actions.put(new JSONObject().put("id", "permission").put("label", "调整").put("style", "danger"));
        f.put("actions", actions);
        SlotKind.Normalized n = SlotKind.normalize(f);
        assertEquals(SlotKind.ACTION, n.kind);
        assertEquals(SlotAction.STYLE_PLAIN, n.actions.get(0).style);
        assertTrue(n.hasWarnings());
    }

    @Test
    public void duplicateActionIdDroppedAndNonObjectIgnored() throws Exception {
        JSONObject f = frame("action", "权限", "");
        JSONArray actions = new JSONArray();
        actions.put("坏项");
        actions.put(new JSONObject().put("id", "permission").put("label", "调整"));
        actions.put(new JSONObject().put("id", "permission").put("label", "重复"));
        f.put("actions", actions);
        SlotKind.Normalized n = SlotKind.normalize(f);
        assertEquals(1, n.actions.size());
        assertEquals("调整", n.actions.get(0).label);
        assertTrue(n.hasWarnings());
    }

    // ===== Slot 存取 =====

    @Test
    public void slotStoresProgressAndActions() {
        DockSlotBus.Slot progress = new DockSlotBus.Slot("p", 20, "压缩", "", "", false,
                SlotKind.PROGRESS, "task", false, 0, 55, null);
        assertEquals(SlotKind.PROGRESS, progress.kind);
        assertEquals(55, progress.progress);
        assertEquals(0, progress.count);
        assertTrue(progress.actions.isEmpty());

        List<SlotAction> actions = Arrays.asList(
                new SlotAction("permission", "调整", "primary"),
                new SlotAction("jobs", "任务", null));
        DockSlotBus.Slot action = new DockSlotBus.Slot("a", 10, "权限", "", "permission", false,
                SlotKind.ACTION, "state", false, 0, 0, actions);
        assertEquals(SlotKind.ACTION, action.kind);
        assertEquals(2, action.actions.size());
        assertEquals("permission", action.actions.get(0).id);
        assertEquals(SlotAction.STYLE_PRIMARY, action.actions.get(0).style);
        assertEquals(SlotAction.STYLE_PLAIN, action.actions.get(1).style);
        assertEquals(0, action.progress);
    }

    @Test
    public void withSourcePreservesProgressAndActions() {
        List<SlotAction> actions = Collections.singletonList(
                new SlotAction("permission", "调整", "primary"));
        DockSlotBus.Slot action = new DockSlotBus.Slot("a", 10, "权限", "", "", false,
                SlotKind.ACTION, "state", false, 0, 0, actions);
        DockSlotBus.Slot local = action.withSource("local");
        assertEquals("local", local.source);
        assertEquals(action.actions, local.actions);

        DockSlotBus.Slot progress = new DockSlotBus.Slot("p", 20, "压缩", "", "", false,
                SlotKind.PROGRESS, "task", false, 0, 37, null);
        DockSlotBus.Slot pLocal = progress.withSource("local");
        assertEquals(37, pLocal.progress);
        assertEquals(SlotKind.PROGRESS, pLocal.kind);
    }

    @Test
    public void constructorsNormalizeLegacyKindsAndClearFields() {
        DockSlotBus.Slot state = new DockSlotBus.Slot("s", 30, "状态", "", "", false,
                "display", "state", false, 3, 77, Collections.singletonList(
                        new SlotAction("x", "不该出现", null)));
        assertEquals("旧 display 构造也归一", SlotKind.STATE, state.kind);
        assertEquals("state 不保留 count", 0, state.count);
        assertEquals("state 不保留 progress", 0, state.progress);
        assertTrue("state 不保留 actions", state.actions.isEmpty());
    }

    @Test
    public void effectiveKindFallsBackWhenRequiredFieldMissing() {
        assertEquals(SlotKind.STATE,
                SlotKind.effectiveKind(SlotKind.ACTION, 0, Collections.<SlotAction>emptyList()));
        assertEquals(SlotKind.STATE,
                SlotKind.effectiveKind(SlotKind.LIST, 0, Collections.<SlotAction>emptyList()));
        assertEquals(SlotKind.PROGRESS,
                SlotKind.effectiveKind(SlotKind.PROGRESS, 0, Collections.<SlotAction>emptyList()));
    }

    @Test
    public void renderJsonCarriesKindProgressCountActions() {
        JSONObject o = SlotKind.toJson(SlotKind.ACTION, 0, 0,
                Collections.singletonList(new SlotAction("permission", "调整", "primary")));
        assertEquals(SlotKind.ACTION, o.optString("kind"));
        assertEquals(1, o.optJSONArray("actions").length());
        assertEquals("permission", o.optJSONArray("actions").optJSONObject(0).optString("id"));
    }
}
