package com.oilquiz.app.ai.chat.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * 链接打开器（全局可复用）。
 *
 * 从 AIChatActivity openUri 抽取：以系统浏览器/应用打开链接，
 * 带 READ_URI_PERMISSION 授权；失败返回 false 由宿主提示。
 */
public final class LinkOpener {

    private LinkOpener() {}

    /** 打开链接（成功返回 true；失败返回 false 不抛异常） */
    public static boolean open(Context context, String url) {
        if (url == null || context == null) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
