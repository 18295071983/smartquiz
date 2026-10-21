package com.oilquiz.app.ai.chat.component;

import android.content.Context;
import android.util.Log;

import java.io.File;

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
     * 解析为实际存在的文件；无法解析到实体文件时返回 {@code null}
     * （网络/content URI 也返回 null —— 它们不是本地文件，调用方应原样交给 Glide/系统）。
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

    /** 是否远程/媒体库地址（调用方据此决定走 Glide/系统还是本地解码） */
    public static boolean isRemoteOrContent(String url) {
        if (url == null) return false;
        String u = url.trim();
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("content://");
    }
}
