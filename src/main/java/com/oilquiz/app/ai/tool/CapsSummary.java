package com.oilquiz.app.ai.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 电脑端回传的 caps 摘要（2026-11-15，§18 能力协商体检）。
 *
 * <p>为什么单独一个纯类：手机端以前只在请求里**声明** caps，却从不解析服务端的回执——
 * 插件版本比手机新（多了能力）或手机比插件新（少声明）时，两边都不会说，
 * 只能事后从"某个卡片/事件没出现"反推。这个类只做三件事：解析、格式化、留存，
 * 不碰网络/Android，可直接 JVM 单测。</p>
 *
 * <p>输入是插件 {@code caps.js} 的 {@code clientCaps()} 块（WS {@code hello.caps}、
 * SSE {@code ready.caps} / {@code snapshot.caps}、{@code /status.caps.clients[]}），
 * 也兼容旧版 {@code /v2/hello.caps}（{@code summarize()}：{@code declared} 是布尔 +
 * {@code client} 数组）。</p>
 *
 * <pre>
 *   { version:1, legacy:false, declared:[...], declared_count:25, missing:[...], unknown:[...] }
 * </pre>
 */
public final class CapsSummary {

    /** 来源标签：WS 握手帧 */
    public static final String SOURCE_WS_HELLO = "ws:hello";
    /** 来源标签：SSE ready / snapshot 帧 */
    public static final String SOURCE_SSE = "sse:ready";
    /** 来源标签：本地兜底（没收到服务端摘要，只能用自己声明的） */
    public static final String SOURCE_LOCAL = "local";

    private final int version;
    private final boolean legacy;
    private final boolean serverKnown;
    private final List<String> declared;
    private final List<String> missing;
    private final List<String> unknown;
    private final String source;

    private CapsSummary(int version, boolean legacy, boolean serverKnown,
                        List<String> declared, List<String> missing, List<String> unknown,
                        String source) {
        this.version = version;
        this.legacy = legacy;
        this.serverKnown = serverKnown;
        this.declared = Collections.unmodifiableList(declared);
        this.missing = Collections.unmodifiableList(missing);
        this.unknown = Collections.unmodifiableList(unknown);
        this.source = source == null ? "" : source;
    }

    /**
     * 解析服务端 caps 块；结构不认识返回 {@code null}（调用方保留旧值）。
     *
     * <p>容忍两种形状（见类注释），并对缺字段做默认：{@code version=RemoteCaps.VERSION}、
     * {@code legacy=false}、列表缺省为空。</p>
     */
    public static CapsSummary parse(Object raw, String source) {
        if (!(raw instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) raw;

        int version = intOf(m.get("version"), RemoteCaps.VERSION);
        boolean legacy = boolOf(m.get("legacy"), false);

        // 新形状：declared 是数组；旧 summarize：declared 是布尔、真正名单在 client。
        List<String> declared = stringsOf(m.get("declared"));
        if (declared.isEmpty()) declared = stringsOf(m.get("client"));

        List<String> missing = stringsOf(m.get("missing"));
        List<String> unknown = stringsOf(m.get("unknown"));

        // 有 missing/unknown 字段（哪怕为空数组）就算拿到了服务端口径
        boolean serverKnown = m.containsKey("missing") || m.containsKey("unknown");
        return new CapsSummary(version, legacy, serverKnown, declared, missing, unknown, source);
    }

    /**
     * 本地兜底：只知道"我声明了什么"，不知道服务端有什么。
     *
     * <p>只在服务端没回 caps 摘要时用（旧插件 / 极端断链），日志里 {@code 缺失=?}，
     * 不假装"零缺失"。</p>
     */
    public static CapsSummary local() {
        return new CapsSummary(RemoteCaps.VERSION, false, false,
                new ArrayList<>(java.util.Arrays.asList(RemoteCaps.ALL)),
                new ArrayList<>(), new ArrayList<>(), SOURCE_LOCAL);
    }

    public int version() {
        return version;
    }

    /** true = 本端没声明 caps（旧客户端）；服务端不会裁剪 */
    public boolean legacy() {
        return legacy;
    }

    /** true = 已拿到服务端的 missing/unknown 口径（不是本地兜底） */
    public boolean serverKnown() {
        return serverKnown;
    }

    /** 本端声明的能力（顺序即 RemoteCaps.ALL / 服务端回执顺序） */
    public List<String> declared() {
        return declared;
    }

    /** 服务端有、本端没声明的能力；{@link #serverKnown()} 为 false 时为空（不是零缺失） */
    public List<String> missing() {
        return missing;
    }

    /** 本端声明了但服务端不认识的能力（手机比插件新时出现） */
    public List<String> unknown() {
        return unknown;
    }

    public String source() {
        return source;
    }

    /**
     * 一行日志（真机验收口径）：
     * <pre>[caps] 声明=25 缺失=0 未知=0 version=1 来源=ws:hello</pre>
     * 缺失非空时附前 5 个能力名；未拿到服务端摘要时显示 {@code 缺失=?}。
     */
    public String line() {
        StringBuilder b = new StringBuilder("[caps] 声明=").append(declared.size());
        if (!serverKnown) {
            b.append(" 缺失=?");
        } else {
            b.append(" 缺失=").append(missing.size());
            if (!missing.isEmpty()) b.append('(').append(preview(missing)).append(')');
        }
        b.append(" 未知=").append(unknown.size());
        if (!unknown.isEmpty()) b.append('(').append(preview(unknown)).append(')');
        b.append(" version=").append(version);
        if (legacy) b.append(" legacy=1");
        if (!source.isEmpty()) b.append(" 来源=").append(source);
        return b.toString();
    }

    @Override
    public String toString() {
        return line();
    }

    // ---------------- 解析小工具（纯函数，容错优先） ----------------

    private static String preview(List<String> list) {
        StringBuilder b = new StringBuilder();
        int max = Math.min(5, list.size());
        for (int i = 0; i < max; i += 1) {
            if (i > 0) b.append(',');
            b.append(list.get(i));
        }
        if (list.size() > max) b.append(",…");
        return b.toString();
    }

    /** 数组 / 逗号分隔字符串 → 去重去空的列表；其它类型返回空表 */
    static List<String> stringsOf(Object raw) {
        List<Object> items = new ArrayList<>();
        if (raw instanceof Object[]) {
            Collections.addAll(items, (Object[]) raw);
        } else if (raw instanceof Iterable) {
            for (Object o : (Iterable<?>) raw) items.add(o);
        } else if (raw instanceof String) {
            for (String s : ((String) raw).split(",")) items.add(s);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : items) {
            String s = o == null ? "" : String.valueOf(o).trim();
            if (!s.isEmpty()) seen.add(s);
        }
        return new ArrayList<>(seen);
    }

    static int intOf(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try {
                return Integer.parseInt(((String) o).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    static boolean boolOf(Object o, boolean def) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof String) {
            String s = ((String) o).trim();
            if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
            if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        }
        return def;
    }
}
