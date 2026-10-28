package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;

/**
 * 媒体/文件路径统一解析（2026-10-13）。
 *
 * <p><b>为什么要有这个类</b>：Agent 与模型传来的路径形态极不稳定 —— 可能是绝对路径、
 * {@code file://}、{@code content://}、http(s)，也常常只是<b>裸文件名</b>
 * （如 {@code gen_1791873835846.jpg}）或工作区相对路径（{@code files/chart.png}）。
 * 而本仓库里 {@code FileCardView}、{@code VideoCardView}、{@code AudioCardView}、
 * {@code ComponentActions} 各自抄了一份"走 {@code AgentWorkspace.resolveExistingFile}"的逻辑，
 * {@code ImageGridCardView} 与 {@code NativeLayoutRenderer} 的 image/carousel/avatar 节点、
 * 旧 Markwon 的 {@code GlideStore} 却<b>没有</b>抄 —— 于是同一个文件在文件卡里能打开、
 * 在图片网格里就变成"⚠ 图片加载失败"（真机实测复现）。</p>
 *
 * <p>本类是该口径的<b>唯一实现</b>，新代码一律调它，不要再复制粘贴：</p>
 * <ul>
 *   <li>{@code http(s)://} 与 {@code content://} 原样返回（Glide / 系统能直接吃）；</li>
 *   <li>{@code file://} 剥成绝对路径；</li>
 *   <li>已是绝对路径且存在 → 规范化返回；</li>
 *   <li>其余（裸名 / 相对路径）交 {@code AgentWorkspace.resolveExistingFile}
 *       —— 它内含 files/、tmp/、根目录的按文件名兜底搜索，以及公共↔私有工作区
 *       另一侧的跨侧兜底（工作区随授权切换、写者却可能落在旧侧），
 *       专治模型给的缩写与幻觉路径；</li>
 *   <li>都解析不到时返回<b>原值</b>，由调用方按各自方式降级（占位图 / 失败提示），
 *       本类不做"加载失败"的决策。</li>
 * </ul>
 *
 * <p><b>条目形态（2026-10-23 修卡片"点了打不开"）</b>：卡片的一条记录是
 * {@code {name, path}} 两个字段，而模型经常把 path 写成<b>目录</b>、把文件名只留在
 * name 里（真机会话实测：{@code path="/storage/.../agent_workspace/files/"} +
 * {@code name="李凡_….mp4"}）。这时按 path 打开就是"暂不支持打开文件夹"，卡片看起来
 * 正常、每一条链接却全坏。{@link #resolveEntryToFile} 是这类条目定位的<b>唯一</b>口径：
 * 目录 + 文件名补齐、省略号截断名按目录内唯一匹配还原、都解析不到时交回原值。
 * {@code file_list} 的行、{@code file_card} 的打开/分享、{@code ui_component} 建卡时的
 * 落盘归一，三处都走它，不许再各抄一份。</p>
 */
public final class MediaPathResolver {

    private static final String TAG = "MediaPathResolver";

    private MediaPathResolver() {
    }

    /** 解析为可加载路径字符串；解析不到返回原值（永不返回 null 以外的意外值）。 */
    public static String resolve(Context context, String raw) {
        File f = resolveToFile(context, raw);
        return f != null ? f.getAbsolutePath() : raw;
    }

    /**
     * 解析为实际存在的<b>实体</b>；解析不到返回 {@code null}
     * （网络/content URI 也返回 null —— 它们不是本地文件，调用方应原样交给 Glide/系统）。
     *
     * <p>注意：<b>绝对路径存在时目录也会返回</b>（{@code exists()} 判定，卡片导航要用它），
     * 而工作区兜底搜索（{@code AgentWorkspace#resolveExistingFile}）只会命中文件。
     * 需要"只要文件"的调用方自己判 {@code isFile()}，条形的路径补齐请走
     * {@link #resolveEntryToFile}。</p>
     */
    public static File resolveToFile(Context context, String raw) {
        if (raw == null) return null;
        String p = raw.trim();
        if (p.isEmpty()) return null;
        if (p.startsWith("http://") || p.startsWith("https://") || p.startsWith("content://")) {
            return null;                       // 远程/媒体库 URI：不是本地文件，交调用方原样处理
        }
        if (p.startsWith("file://")) {
            String pathOnly = android.net.Uri.parse(p).getPath();
            if (pathOnly != null && !pathOnly.isEmpty()) p = pathOnly;
        }
        try {
            File direct = new File(p);
            if (direct.isAbsolute() && direct.exists()) return direct;
            // 2026-10-25 窄兜底：Agent 曾把 DCIM/Screenshots 的裸文件名猜成
            // Pictures/Screenshots；该目录不存在时按同名文件去 DCIM/Screenshots 找。
            File screenshot = screenshotFallback(p);
            if (screenshot != null) return screenshot;
            if (context != null) {
                File r = com.oilquiz.app.ai.agent.online.AgentWorkspace
                        .getInstance(context).resolveExistingFile(p);
                if (r != null && r.exists()) return r;
            }
        } catch (Throwable t) {
            Log.w(TAG, "路径解析失败(" + raw + "): " + t.getMessage());
        }
        return null;
    }

    /**
     * 仅修复一类历史脏数据：{@code .../Pictures/Screenshots/<name>} 不存在时，
     * 去 {@code <external>/DCIM/Screenshots/<name>} 找同名文件。
     *
     * <p>不做通用"按文件名全盘搜索"；只在截图目录这一对已知路径间兜底，
     * 避免把路径错误掩盖成随机命中。</p>
     */
    static File screenshotFallback(String raw) {
        File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        return screenshotFallback(dcim, raw);
    }

    static File screenshotFallback(File dcimRoot, String raw) {
        if (dcimRoot == null || raw == null) return null;
        String path = raw.trim().replace('\\', '/');
        String marker = "/Pictures/Screenshots/";
        int idx = path.indexOf(marker);
        if (idx < 0) return null;
        String name = path.substring(idx + marker.length());
        if (name.isEmpty() || name.contains("/") || name.contains("..")) return null;
        File candidate = new File(new File(dcimRoot, "Screenshots"), name);
        return candidate.isFile() ? candidate : null;
    }

    // ==================== 条目定位（name + path 一条记录） ====================

    /**
     * 解析卡片里<b>一条记录</b>（{@code {name, path}}）指向的实体，解析不到返回 {@code null}。
     *
     * <p>决策顺序（每一步都只在"上一个没结果"时才走）：</p>
     * <ol>
     *   <li>{@code path} 能解析到<b>文件</b> → 就用它（正常形态）；</li>
     *   <li>{@code path} 空 → 名字本身可能就是全路径，拿 {@code name} 单独解析；</li>
     *   <li>{@code path} 解析到<b>目录</b>：
     *     <ul>
     *       <li>目录里有 {@code name} 这个孩子 → 返回目录里的它（补齐后的真实路径）；</li>
     *       <li>{@code name} 是省略号截断名（{@code 前缀...后缀}）且目录里<b>唯一</b>匹配
     *           → 用那个孩子；不唯一就不猜（宁可让行只做到"进入目录"，也别打开错文件）；</li>
     *       <li>都没有 → 返回该<b>目录</b>，调用方按目录处理（file_list 行进目录；
     *           file_card 这类"只有一个文件"的条目自己再按名字搜一次，见
     *           {@code FileCardView#normalizePath}）。</li>
     *     </ul></li>
     *   <li>{@code path} 解析不到实体：仍按"目录串 + 文件名"拼一次（路径没同步/无权限时
     *       {@code exists()} 可能假阴），再退回单用 {@code name} 解析。</li>
     * </ol>
     */
    public static File resolveEntryToFile(Context context, String path, String name) {
        String p = path == null ? "" : path.trim();
        String n = name == null ? "" : name.trim();

        File base = p.isEmpty() ? null : resolveToFile(context, p);
        if (base != null && base.isFile()) return base;

        if (p.isEmpty()) {
            return n.isEmpty() ? null : resolveToFile(context, n);
        }
        if (n.isEmpty()) return base;   // 只有目录没名字：交回调用方按目录处理

        if (base != null && base.isDirectory()) {
            File exact = childOf(base, n);
            if (exact != null) return exact;
            File ellipsis = matchEllipsisInDir(base, n);
            if (ellipsis != null) return ellipsis;
            // 这里不再拿 name 去全工作区搜同名文件：目录行点进目录就是正确行为，
            // 跨目录抓一个同名文件很可能抓到别的（工作区里 files/a.md 与 tmp/a.md 同时存在很常见）
            return base;
        }

        // base 为 null 或形态可疑：先按"目录串 + 文件名"补，再单用名字
        String joined = childPath(p, n);
        File jf = resolveToFile(context, joined);
        if (jf != null) return jf;
        File byName = resolveToFile(context, n);
        return byName != null ? byName : null;
    }

    /**
     * {@link #resolveEntryToFile} 的<b>渲染期</b>版本：只按上盘存在性判断（每条 1-2 次
     * {@code exists()}），不走 {@code AgentWorkspace} 的同名搜索。
     *
     * <p>为什么不在这一步搜工作区：一张 file_list 卡最多 30 行，逐行做"扫 files/ + tmp/ +
     * 根目录 + 另一侧"会把主线程拖成肉眼可见的卡顿；而"目录 + 文件名补齐"这个真机 bug
     * 只需要一次 {@code exists()} 就能判。剩下那些解析不到的行仍然按原值可点，
     * 点击时由 {@code ComponentActions#openLink}（一次）做完整兜底搜索。</p>
     */
    public static File resolveEntryOnDisk(String path, String name) {
        return resolveEntryToFile(null, path, name);
    }

    /**
     * {@link #resolveEntryToFile} 的字符串版：解析到实体返回绝对路径，
     * 否则<b>原样返回 path</b>（path 为空时返回 name），交调用方降级提示。
     * 建卡时把 props 里的路径归一成完整文件路径用的就是这个。
     */
    public static String resolveEntryPath(Context context, String path, String name) {
        File f = resolveEntryToFile(context, path, name);
        if (f != null) return f.getAbsolutePath();
        String p = path == null ? "" : path.trim();
        if (!p.isEmpty()) return p;
        return name == null ? "" : name.trim();
    }

    /** 目录里确有这个名字的孩子（文件或目录都算，file_list 的目录行同样靠它补齐）；没有返回 null */
    private static File childOf(File dir, String name) {
        try {
            File direct = new File(dir, name);          // 名字可能是相对子路径（"sub/x.mp4"）
            if (direct.exists()) return direct;
            String bare = bareName(name);
            if (!bare.equals(name)) {
                File b = new File(dir, bare);
                if (b.exists()) return b;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 目录内按省略号截断名找<b>唯一</b>匹配；不唯一/无匹配/读不到目录返回 null（不猜） */
    private static File matchEllipsisInDir(File dir, String name) {
        String matched;
        try {
            java.util.List<String> candidates = new ArrayList<>();
            String[] children = dir.list();
            if (children == null) return null;
            for (String c : children) candidates.add(c);
            matched = matchEllipsisName(name, candidates);
        } catch (Throwable t) {
            return null;
        }
        return matched == null ? null : childOf(dir, matched);
    }

    /** 目录 + 文件名拼接：去掉目录尾斜杠、名字里的前导斜杠与目录段（只取 basename） */
    public static String childPath(String dir, String name) {
        String d = dir == null ? "" : dir.trim();
        String n = name == null ? "" : name.trim();
        while (d.endsWith("/")) {
            d = d.substring(0, d.length() - 1);
        }
        n = bareName(n);
        if (d.isEmpty()) return n;
        if (n.isEmpty()) return d;
        return d + "/" + n;
    }

    /** 取路径最后一段（先剥 file:// 前缀和尾斜杠）；空值原样返回 */
    public static String bareName(String pathOrName) {
        if (pathOrName == null) return "";
        String s = pathOrName.trim();
        if (s.startsWith("file://")) {
            String only = null;
            try {
                only = android.net.Uri.parse(s).getPath();
            } catch (Throwable ignored) {   // 非 Android 运行时（JVM 单测）退回手工剥前缀
            }
            if (only == null || only.isEmpty()) {
                s = s.substring("file://".length()).replaceFirst("^/+", "/");
            } else {
                s = only;
            }
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        int idx = s.lastIndexOf('/');
        return idx >= 0 ? s.substring(idx + 1) : s;
    }

    /** 省略号标记：模型截长名时常用这三种（半角三点、中文/单点省略号、双省略号） */
    private static final String[] ELLIPSIS_MARKERS = {"...", "…", "‥"};

    /**
     * 名字被省略号截断（形如 {@code 前缀...后缀}）时，在候选文件名里找<b>唯一</b>匹配。
     *
     * <p>真机形态：卡片写 {@code 抖音_李凡_末日手艺人斩诡强化万物..._7698807827401362726.mp4}，
     * 磁盘上是 {@code 抖音_李凡_末日手艺人斩诡强化万物 我叫顾满，穿来的。…​_7698807827401362726.mp4}
     * —— 头尾都在、中间被换成省略号。工作区的 {@code searchSimilarFiles} 靠"互为子串"匹配，
     * 对这种"两头对得上、中间被吞"的写法必然失配，所以这里单独补一条前缀+后缀规则。</p>
     *
     * <p>只在<b>恰好一个</b>候选命中时返回；0 个或多个都返回 {@code null}：多个时猜哪个都可能
     * 打开别人的文件，宁可不打开。</p>
     */
    public static String matchEllipsisName(String name, java.util.List<String> candidates) {
        if (name == null || candidates == null || candidates.isEmpty()) return null;
        String n = name.trim();
        int idx = -1;
        int markerLen = 0;
        for (String marker : ELLIPSIS_MARKERS) {
            int i = n.indexOf(marker);
            if (i > 0 && (idx < 0 || i < idx)) {
                idx = i;
                markerLen = marker.length();
            }
        }
        if (idx <= 0) return null;
        String prefix = n.substring(0, idx);
        String suffix = n.substring(idx + markerLen);
        // 两头必须都在：只有前缀（"很长的名字..."）等于没有约束，匹配出来是一串候选
        if (prefix.trim().isEmpty() || suffix.trim().isEmpty()) return null;
        String hit = null;
        for (String c : candidates) {
            if (c == null) continue;
            String cand = c.trim();
            if (cand.length() < prefix.length() + suffix.length()) continue;
            if (cand.equals(n)) return cand;   // 名字本来就对（大小写/空格差异外的精确命中）
            if (cand.startsWith(prefix) && cand.endsWith(suffix)) {
                if (hit != null) return null;   // 不唯一 → 放弃
                hit = cand;
            }
        }
        return hit;
    }
}
