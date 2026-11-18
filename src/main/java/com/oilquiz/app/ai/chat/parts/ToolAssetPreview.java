package com.oilquiz.app.ai.chat.parts;

import android.content.Context;
import android.view.View;
import android.widget.ScrollView;

import com.oilquiz.app.ui.modal.AppSheet;

/**
 * 独立预览入口（2026-11-18）：`open_file` 这类"只给路径"的事件复用同一套文件渲染器，
 * 不再只弹 Toast。
 */
public final class ToolAssetPreview {

    private ToolAssetPreview() {
    }

    /** 按路径打开文件卡弹层；路径为空/不支持时什么都不做。 */
    public static void show(Context ctx, String path) {
        if (ctx == null || path == null || path.trim().isEmpty()) {
            return;
        }
        ToolAssetRef ref = ToolAssetRef.fromPath(path.trim());
        if (ref == null) {
            return;
        }
        ScrollView scroll = new ScrollView(ctx);
        final View card = AssetRenderer.build(ctx, ref, "文件");
        scroll.addView(card, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        AppSheet.builder(ctx)
                .title("文件")
                .content(scroll)
                .onDismiss(d -> AssetRenderer.cancel(card))
                .action("关闭", true, null)
                .show();
    }
}
