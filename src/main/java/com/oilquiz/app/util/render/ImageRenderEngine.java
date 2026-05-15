package com.oilquiz.app.util.render;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class ImageRenderEngine implements FileRenderEngine {
    private static final String TAG = "ImageRenderEngine";
    private static final String[] SUPPORTED_EXTENSIONS = {"jpg", "jpeg", "png", "gif", "bmp", "webp"};
    
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
            // 读取图片信息
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            
            int width = options.outWidth;
            int height = options.outHeight;
            String mimeType = options.outMimeType;
            
            // 计算采样率，避免OOM
            int maxDimension = 1024;
            int sampleSize = 1;
            if (width > maxDimension || height > maxDimension) {
                int widthRatio = Math.round((float) width / (float) maxDimension);
                int heightRatio = Math.round((float) height / (float) maxDimension);
                sampleSize = Math.max(widthRatio, heightRatio);
            }
            
            // 解码图片
            options.inJustDecodeBounds = false;
            options.inSampleSize = sampleSize;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            
            callback.onProgress(50);
            
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            
            // 收集图片信息
            Map<String, Object> imageInfo = new HashMap<>();
            imageInfo.put("bitmap", bitmap);
            imageInfo.put("width", width);
            imageInfo.put("height", height);
            imageInfo.put("mimeType", mimeType);
            imageInfo.put("fileSize", file.length() / 1024 + "KB");
            imageInfo.put("fileName", file.getName());
            
            callback.onProgress(100);
            callback.onSuccess(imageInfo);
            
        } catch (Exception e) {
            Log.e(TAG, "Error rendering image file: " + e.getMessage(), e);
            callback.onError("渲染失败: " + e.getMessage());
        }
    }
}
