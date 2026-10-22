package com.oilquiz.app.ai.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@link ProcessTreeKiller} 里可纯测的部分：/proc 的 stat 解析与父子图遍历。
 *
 * <p>真正发信号的 {@code terminateDescendants} 依赖 /proc 与 kill，只能在设备上验；
 * 但会杀错进程的地方恰恰是这两段纯逻辑 —— ppid 解析错位就杀错 pid，环没挡住就会死循环，
 * 所以把它们单独钉住。
 */
public class ProcessTreeKillerTest {

    @Test
    public void parsesStandardStatLine() {
        // 真实格式：pid (comm) state ppid pgrp session tty_nr ...
        int[] parsed = ProcessTreeKiller.parsePidPpid("1234 (sh) S 999 1234 1234 0 0");
        assertNotNull(parsed);
        assertEquals(1234, parsed[0]);
        assertEquals(999, parsed[1]);
    }

    @Test
    public void parsesCommWithSpaces() {
        int[] parsed = ProcessTreeKiller.parsePidPpid("42 (my cool tool) S 7 42 42");
        assertNotNull(parsed);
        assertEquals(42, parsed[0]);
        assertEquals(7, parsed[1]);
    }

    /** 命令名里带右括号时，必须按**最后一个** ')' 定位，否则列号全部错位 */
    @Test
    public void parsesCommContainingClosingParen() {
        int[] parsed = ProcessTreeKiller.parsePidPpid("55 (awk(x)) S 21 55 55");
        assertNotNull(parsed);
        assertEquals(55, parsed[0]);
        assertEquals(21, parsed[1]);
    }

    @Test
    public void rejectsUnparsableLines() {
        assertNull(ProcessTreeKiller.parsePidPpid(null));
        assertNull(ProcessTreeKiller.parsePidPpid(""));
        assertNull(ProcessTreeKiller.parsePidPpid("not a stat line"));
        assertNull(ProcessTreeKiller.parsePidPpid("1234 (sh"));          // 没有右括号
        assertNull(ProcessTreeKiller.parsePidPpid("1234 (sh) S"));        // 缺 ppid
        assertNull(ProcessTreeKiller.parsePidPpid("abc (sh) S 9"));       // pid 不是数字
    }

    @Test
    public void buildsParentToChildrenIndex() {
        List<int[]> pairs = Arrays.asList(
                new int[]{100, 1}, new int[]{101, 100}, new int[]{102, 100}, new int[]{103, 101});
        Map<Integer, List<Integer>> tree = ProcessTreeKiller.buildTree(pairs);
        assertEquals(Arrays.asList(101, 102), tree.get(100));
        assertEquals(1, tree.get(101).size());
    }

    @Test
    public void collectsAllDescendantsWithoutTheRootItself() {
        Map<Integer, List<Integer>> tree = ProcessTreeKiller.buildTree(Arrays.asList(
                new int[]{100, 1}, new int[]{101, 100}, new int[]{102, 100},
                new int[]{103, 101}));
        List<Integer> got = ProcessTreeKiller.collectDescendants(tree, 100, 6, 64);
        assertEquals(true, got.contains(101));
        assertEquals(true, got.contains(102));
        assertEquals(true, got.contains(103));
        assertFalse("绝不把 root 自己算进去（root 由调用方 destroy）", got.contains(100));
    }

    @Test
    public void depthLimitStopsShallow() {
        Map<Integer, List<Integer>> tree = ProcessTreeKiller.buildTree(Arrays.asList(
                new int[]{200, 100}, new int[]{300, 200}, new int[]{400, 300}));
        List<Integer> shallow = ProcessTreeKiller.collectDescendants(tree, 100, 2, 64);
        assertTrue(shallow.contains(200));
        assertTrue(shallow.contains(300));
        assertFalse("超过 maxDepth 的不得进入结果: " + shallow, shallow.contains(400));
    }

    @Test
    public void resultCapIsRespected() {
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            pairs.add(new int[]{1000 + i, 100});
        }
        List<Integer> capped = ProcessTreeKiller.collectDescendants(
                ProcessTreeKiller.buildTree(pairs), 100, 6, 10);
        assertEquals(10, capped.size());
    }

    /** ppid 数据在竞态下可能自指/成环，靠深度挡不住，必须有 visited */
    @Test
    public void cycleDoesNotHangOrDuplicate() {
        Map<Integer, List<Integer>> tree = ProcessTreeKiller.buildTree(Arrays.asList(
                new int[]{200, 300}, new int[]{300, 200}, new int[]{201, 200}));
        List<Integer> got = ProcessTreeKiller.collectDescendants(tree, 200, 6, 64);
        assertEquals(2, got.size());
        assertTrue(got.contains(300));
        assertTrue(got.contains(201));
    }

    @Test
    public void skipsInitAndKernelLevelPids() {
        Map<Integer, List<Integer>> tree = ProcessTreeKiller.buildTree(Arrays.asList(
                new int[]{1, 100}, new int[]{0, 100}, new int[]{101, 100}));
        List<Integer> got = ProcessTreeKiller.collectDescendants(tree, 100, 6, 64);
        assertEquals(Arrays.asList(101), got);
    }
}
