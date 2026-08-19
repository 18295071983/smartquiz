package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

public class ImagePreviewEngine extends BasePreviewEngine {

    private static final String[] SUPPORTED_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"};

    @Override
    protected Bitmap generatePreview(Context context, File file, PreviewProgressCallback progressCallback) throws Exception {
        // 采样解码：预览图限制在 1280px 内，避免大图全尺寸解码撑爆内存
        return com.oilquiz.app.util.ImageParserUtil.parseImage(file, 1280, 1280);
    }

    @Override
    protected Bitmap generatePreview(Context context, File file) throws Exception {
        return generatePreview(context, file, null);
    }

    @Override
    public boolean supports(File file) {
        return hasExtension(file, SUPPORTED_EXTENSIONS);
    }

    @Override
    public String getEngineName() {
        return "Image Preview Engine";
    }
}