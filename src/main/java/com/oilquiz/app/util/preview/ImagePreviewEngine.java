package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

public class ImagePreviewEngine extends BasePreviewEngine {

    private static final String[] SUPPORTED_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"};

    @Override
    protected Bitmap generatePreview(Context context, File file, PreviewProgressCallback progressCallback) throws Exception {
        return BitmapFactory.decodeFile(file.getAbsolutePath());
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