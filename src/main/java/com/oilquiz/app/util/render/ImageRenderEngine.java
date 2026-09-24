package com.oilquiz.app.util.render;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class ImageRenderEngine implements FileRenderEngine {
    private static final String TAG = "ImageRenderEngine";
    private static final String[] SUPPORTED_EXTENSIONS = {"jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif", "ico"};
    
    @Override
    public boolean canRender(File file) {
        String fileName = file.getName().toLowerCase();
        for (String extension : SUPPORTED_EXTENSIONS) {
            if (fileName.endsWith("." + extension)) {
                return true;
            }
        }
        return false;
    }
    
    @Override
    public String getEngineName() {
        return "图片渲染引擎";
    }
    
    @Override
    public String getFileTypeDescription(File file) {
        String fileName = file.getName().toLowerCase();
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "JPEG图片";
        } else if (fileName.endsWith(".png")) {
            return "PNG图片";
        } else if (fileName.endsWith(".gif")) {
            return "GIF图片";
        } else if (fileName.endsWith(".bmp")) {
            return "BMP图片";
        } else if (fileName.endsWith(".webp")) {
            return "WebP图片";
        }
        return "图片文件";
    }
    
    @Override
    public void render(File file, RenderCallback callback) {
        try {
            // 用 ImageParserUtil 解析：自动处理 EXIF 旋转 + 采样缩放(最高2048)，
            // 避免手机照片横置、大图因采样过小变糊、PNG 透明区变黑(RGB_565)。
            final int MAX_DIMENSION = 2048;
            callback.onProgress(50);

            android.graphics.Bitmap bitmap =
                    com.oilquiz.app.util.ImageParserUtil.parseImage(file, MAX_DIMENSION, MAX_DIMENSION);
            if (bitmap == null) {
                callback.onError("图片解码失败（文件可能损坏或格式不支持）");
                return;
            }

            int width = bitmap.getWidth();
            int height = bitmap.getHeight();

            // 收集图片信息
            Map<String, Object> imageInfo = new HashMap<>();
            imageInfo.put("bitmap", bitmap);
            imageInfo.put("width", width);
            imageInfo.put("height", height);
            imageInfo.put("mimeType", guessMimeType(file.getName()));
            imageInfo.put("fileSize", file.length() / 1024 + "KB");
            imageInfo.put("fileName", file.getName());

            callback.onProgress(100);
            callback.onSuccess(imageInfo);

        } catch (Exception e) {
            Log.e(TAG, "Error rendering image file: " + e.getMessage(), e);
            callback.onError("渲染失败: " + e.getMessage());
        }
    }

    private String guessMimeType(String fileName) {
        String n = fileName.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".tiff") || n.endsWith(".tif")) return "image/tiff";
        if (n.endsWith(".ico")) return "image/x-icon";
        return "image/jpeg";
    }
}
