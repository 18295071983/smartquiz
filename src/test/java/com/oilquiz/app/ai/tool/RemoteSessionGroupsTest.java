package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话选择框的分组规则（2026-11-14）。
 *
 * <p>用户在手机上看到的会话条数比电脑上多得多：电脑按 <b>工作区（cwd）</b>分，
 * 手机以前把所有工作区平铺。这里把"分组 / 当前工作区置顶 / 组名怎么截"钉成纯函数 + 单测，
 * 免得下次改 UI 时又退回一行一条。</p>
 */
public class RemoteSessionGroupsTest {

    private static Map<String, Object> row(String id, String cwd, Object updatedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", id);
        m.put("cwd", cwd);
        m.put("updated_at", updatedAt);
        return m;
    }

    @Test
    public void groupsByCwdPreservingInputOrder() {
        List<Map<String, Object>> rows = new ArrayList<>(Arrays.asList(
                row("s1", "D:\\qzq\\smartquiz", 100),
                row("s2", "C:\\profiles\\desktop", 300),
                row("s3", "D:\\qzq\\smartquiz", 200)));

        LinkedHashMap<String, List<Map<String, Object>>> groups = RemoteSessionGroups.group(rows);

        assertEquals("两个工作区两个组", 2, groups.size());
        assertEquals("组内保持传入顺序（服务端已按最近活动排过）",
                Arrays.asList("s1", "s3"), ids(groups.get("D:\\qzq\\smartquiz")));
        assertEquals(Arrays.asList("s2"), ids(groups.get("C:\\profiles\\desktop")));
    }

    @Test
    public void currentWorkspaceComesFirstThenByRecentActivity() {
        LinkedHashMap<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        groups.put("D:\\qzq\\smartquiz", new ArrayList<>(Arrays.asList(row("a", "D:\\qzq\\smartquiz", 500))));
        groups.put("C:\\profiles\\desktop", new ArrayList<>(Arrays.asList(row("b", "C:\\profiles\\desktop", 100))));
        groups.put("D:\\other", new ArrayList<>(Arrays.asList(row("c", "D:\\other", 900))));

        List<String> order = RemoteSessionGroups.orderedKeys(groups, "C:\\profiles\\desktop");

        assertEquals("当前工作区第一，其余按最近活动倒序",
                Arrays.asList("C:\\profiles\\desktop", "D:\\other", "D:\\qzq\\smartquiz"), order);
    }

    @Test
    public void noCurrentWorkspaceFallsBackToRecentActivityOnly() {
        LinkedHashMap<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        groups.put("D:\\a", new ArrayList<>(Arrays.asList(row("a", "D:\\a", 10))));
        groups.put("D:\\b", new ArrayList<>(Arrays.asList(row("b", "D:\\b", 99))));

        assertEquals(Arrays.asList("D:\\b", "D:\\a"), RemoteSessionGroups.orderedKeys(groups, ""));
        assertEquals("null 也当没有当前工作区", Arrays.asList("D:\\b", "D:\\a"),
                RemoteSessionGroups.orderedKeys(groups, null));
    }

    @Test
    public void labelKeepsLastTwoSegments() {
        assertEquals("qzq/smartquiz", RemoteSessionGroups.label("D:\\qzq\\smartquiz"));
        assertEquals("profiles/desktop",
                RemoteSessionGroups.label("C:\\Users\\xiaocong\\.workbuddy\\dsh-home\\profiles\\desktop"));
        assertEquals("xiaocong/desktop", RemoteSessionGroups.label("C:\\Users\\xiaocong\\desktop"));
        assertEquals("foo", RemoteSessionGroups.label("C:\\Users\\foo"));
        assertEquals("末尾斜杠不影响分组显示", "qzq/smartquiz",
                RemoteSessionGroups.label("D:\\qzq\\smartquiz\\"));
        assertEquals("未知工作区", RemoteSessionGroups.label(""));
        assertEquals("未知工作区", RemoteSessionGroups.label(null));
        assertEquals("qzq/smartquiz", RemoteSessionGroups.label("/home/qzq/smartquiz"));
    }

    @Test
    public void latestOfAcceptsNumberAndNumericString() {
        assertEquals(300L, RemoteSessionGroups.latestOf(Arrays.asList(
                row("a", "x", 100),
                row("b", "x", "300.5"),
                row("c", "x", null))));
        assertEquals(0L, RemoteSessionGroups.latestOf(null));
        assertEquals(0L, RemoteSessionGroups.latestOf(Arrays.asList(row("a", "x", "不是数字"))));
    }

    @Test
    public void msOfParsesJsonNumberShapes() {
        assertEquals(1234L, RemoteSessionGroups.msOf(1234));
        assertEquals(1234L, RemoteSessionGroups.msOf(1234L));
        assertEquals(1234L, RemoteSessionGroups.msOf("1234.0"));
        assertEquals(0L, RemoteSessionGroups.msOf(null));
        assertEquals(0L, RemoteSessionGroups.msOf(""));
        assertEquals(0L, RemoteSessionGroups.msOf("garbage"));
        assertEquals("负数当没有", 0L, RemoteSessionGroups.msOf(-5));
    }

    @Test
    public void defaultVisibleKeepsDesktopAndFollowingOnly() {
        assertTrue("桌面账本里的要看得到", RemoteSessionGroups.defaultVisible(true, false));
        assertTrue("正在跟随的必须看得到（哪怕不在账本里）",
                RemoteSessionGroups.defaultVisible(false, true));
        assertTrue(RemoteSessionGroups.defaultVisible(true, true));
        assertTrue("磁盘上的历史遗留默认不显示 —— 这是\"手机上一堆\"的来源",
                !RemoteSessionGroups.defaultVisible(false, false));
    }

    @Test
    public void emptySessionListGroupsToNothing() {
        assertTrue(RemoteSessionGroups.group(null).isEmpty());
        assertTrue(RemoteSessionGroups.group(new ArrayList<>()).isEmpty());
        assertTrue(RemoteSessionGroups.orderedKeys(
                new LinkedHashMap<>(), "D:\\qzq").isEmpty());
    }

    private static List<String> ids(List<Map<String, Object>> rows) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> m : rows) out.add(String.valueOf(m.get("session_id")));
        return out;
    }
}
