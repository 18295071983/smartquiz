package com.oilquiz.app.ai.tool;

import android.util.Log;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 终止一个子进程的<b>整棵进程树</b>，而不是只杀我们直接拉起的那个 sh。
 *
 * <p>为什么需要：{@code Process.destroyForcibly()} 只杀直接子进程。{@code sh -c 'curl …'} 里
 * 被 sh exec/fork 出来的 curl、sleep、tar 会变成孤儿继续跑 —— 工具已经返回"超时已终止"，
 * 手机上那个进程还在烧 CPU、占着网络，而且没人知道。旧实现甚至在输出超限时也只杀 sh。
 *
 * <p>做法：从已知 pid 出发，用 {@code /proc/<pid>/stat} 的 ppid 建父子图，BFS 出全部子孙，
 * 先 {@code kill -TERM} 再对幸存者 {@code kill -KILL}。不用 {@code setsid} 包一层是因为
 * 包了之后真正的命令进程变成孙进程，Java 侧拿不到它的 pid，反而更难定位该杀谁。
 *
 * <p>安全性：只对本 uid 的子孙发信号（untrusted_app 也只能信号同 uid 进程），
 * 并且显式排除自身与 pid≤1；扫描有深度与数量上限，避免异常情况下无限扩散。
 * tmux 这类刻意脱离终端的常驻进程不在这棵树里（server 的 ppid 是 1），因此
 * "长任务用 tmux 托管"这个用法不受影响。
 */
public final class ProcessTreeKiller {

    private static final String TAG = "ProcessTreeKiller";
    /** 子孙搜索深度上限（正常命令行 2~3 层就到叶子） */
    private static final int MAX_DEPTH = 6;
    /** 一次最多杀多少个进程（防异常树把整次调用拖住） */
    private static final int MAX_PROCESSES = 64;
    /** SIGTERM 后给多久自行退出，之后转 SIGKILL */
    private static final long GRACE_MILLIS = 400;

    private ProcessTreeKiller() {
    }

    /**
     * 解析 {@code /proc/<pid>/stat} 首行的 pid 与 ppid。
     *
     * <p>必须按<b>最后一个</b>右括号定位：字段二是用括号包起来的命令名，里面可以有空格、
     * 甚至可以带 ')'（例如 "my)tool"），用 split 空格会算错列号。
     *
     * @return {@code {pid, ppid}}；解析不出来返回 null
     */
    public static int[] parsePidPpid(String statLine) {
        if (statLine == null) {
            return null;
        }
        int close = statLine.lastIndexOf(')');
        if (close < 0 || close + 2 >= statLine.length()) {
            return null;
        }
        int pid;
        try {
            pid = Integer.parseInt(statLine.substring(0, statLine.indexOf(' ')).trim());
        } catch (Exception e) {
            return null;
        }
        // close 之后是 " S 1234 ..."：状态、ppid 依次排在后面
        String[] tail = statLine.substring(close + 1).trim().split("\\s+");
        if (tail.length < 2) {
            return null;
        }
        try {
            return new int[]{pid, Integer.parseInt(tail[1])};
        } catch (Exception e) {
            return null;
        }
    }

    /** 由 (pid,ppid) 对建父→子索引 */
    public static Map<Integer, List<Integer>> buildTree(List<int[]> pidPpidPairs) {
        Map<Integer, List<Integer>> children = new HashMap<>();
        if (pidPpidPairs == null) {
            return children;
        }
        for (int[] pair : pidPpidPairs) {
            if (pair == null || pair.length < 2) {
                continue;
            }
            List<Integer> list = children.get(pair[1]);
            if (list == null) {
                list = new ArrayList<>(2);
                children.put(pair[1], list);
            }
            list.add(pair[0]);
        }
        return children;
    }

    /**
     * 从 root 出发 BFS 取全部子孙（不含 root 自身），带深度与数量上限。
     *
     * <p>环保护：ppid 数据在异常情况下可能自指或成环，visited 集是必须的，不能只靠深度。
     */
    public static List<Integer> collectDescendants(Map<Integer, List<Integer>> tree, int root,
                                                   int maxDepth, int maxResults) {
        List<Integer> out = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        visited.add(root);
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{root, 0});
        while (!queue.isEmpty() && out.size() < maxResults) {
            int[] node = queue.poll();
            int pid = node[0];
            int depth = node[1];
            if (depth >= maxDepth) {
                continue;
            }
            List<Integer> kids = tree.get(pid);
            if (kids == null) {
                continue;
            }
            for (Integer kid : kids) {
                if (kid == null || kid <= 1 || !visited.add(kid)) {
                    continue;
                }
                out.add(kid);
                queue.add(new int[]{kid, depth + 1});
                if (out.size() >= maxResults) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 终止 rootPid 的整棵子树（不含 root 自身，root 由调用方 destroy）。
     *
     * @return 实际发过信号的进程数；0 表示没有子孙或都取不到
     */
    public static int terminateDescendants(int rootPid) {
        if (rootPid <= 1) {
            return 0;
        }
        int self = android.os.Process.myPid();
        List<Integer> victims;
        try {
            List<int[]> pairs = readAllPidPpid();
            victims = collectDescendants(buildTree(pairs), rootPid, MAX_DEPTH, MAX_PROCESSES);
        } catch (Exception e) {
            Log.w(TAG, "扫描 /proc 失败: " + e.getMessage());
            return 0;
        }
        // 理论上不可能，但绝不自杀
        victims.remove(Integer.valueOf(self));
        if (victims.isEmpty()) {
            return 0;
        }

        if (!signal("TERM", victims)) {
            return 0;
        }
        try {
            Thread.sleep(GRACE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Integer> survivors = aliveOf(victims);
        if (!survivors.isEmpty()) {
            signal("KILL", survivors);
        }
        return victims.size();
    }

    /** 读一遍 /proc，返回能读到的 (pid,ppid)；权限受限的条目静默跳过 */
    private static List<int[]> readAllPidPpid() {
        List<int[]> pairs = new ArrayList<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) {
            return pairs;
        }
        for (File entry : entries) {
            String name = entry.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) {
                continue;
            }
            try {
                String line = readFileFirstLine(new File(entry, "stat"));
                int[] parsed = parsePidPpid(line);
                if (parsed != null) {
                    pairs.add(parsed);
                }
            } catch (Exception ignored) {
                // 进程正好退出/不可读：跳过，不影响其余
            }
        }
        return pairs;
    }

    private static List<Integer> aliveOf(List<Integer> pids) {
        List<Integer> alive = new ArrayList<>();
        for (Integer pid : pids) {
            if (pid != null && new File("/proc/" + pid).exists()) {
                alive.add(pid);
            }
        }
        return alive;
    }

    private static boolean signal(String signalName, List<Integer> pids) {
        String[] args = new String[pids.size() + 1];
        args[0] = "-" + signalName;
        for (int i = 0; i < pids.size(); i++) {
            args[i + 1] = String.valueOf(pids.get(i));
        }
        // /system/bin/kill 是 toybox 提供的；个别 ROM 上入口名不同，退回多合一 toybox
        if (execKill("/system/bin/kill", args)) {
            return true;
        }
        String[] viaToybox = new String[args.length + 1];
        viaToybox[0] = "kill";
        System.arraycopy(args, 0, viaToybox, 1, args.length);
        return execKill("/system/bin/toybox", viaToybox);
    }

    private static boolean execKill(String binary, String[] args) {
        Process p = null;
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = binary;
            System.arraycopy(args, 0, cmd, 1, args.length);
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try {
                p.getOutputStream().close();
            } catch (Exception ignored) {
            }
            return p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "kill 调用失败(" + binary + "): " + e.getMessage());
            return false;
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }

    private static String readFileFirstLine(File f) throws Exception {
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
        try {
            return r.readLine();
        } finally {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
    }
}
