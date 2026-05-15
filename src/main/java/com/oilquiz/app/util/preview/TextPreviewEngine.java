package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;

public class TextPreviewEngine extends BasePreviewEngine {

    private static final String[] SUPPORTED_EXTENSIONS = {".txt", ".csv", ".log", ".json", ".xml", ".html", ".css", ".js", ".java", ".kt"};

    @Override
    protected Bitmap generatePreview(Context context, File file, PreviewProgressCallback progressCallback) throws Exception {
        String content = readFileContent(file);
        return createTextBitmap(content);
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
        return "Text Preview Engine";
    }

    private String readFileContent(File file) throws IOException {
        StringBuilder content = new StringBuilder();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), "UTF-8")) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                content.append(buffer, 0, read);
            }
        }
        return content.toString();
    }

    private Bitmap createTextBitmap(String content) {
        // 创建一个固定大小的Bitmap
        int width = 800;
        int height = 600;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        
        // 填充背景
        canvas.drawColor(Color.WHITE);
        
        // 绘制文本
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(14);
        paint.setTypeface(Typeface.MONOSPACE);
        
        // 计算文本换行
        String[] lines = content.split("\n");
        int y = 20;
        for (String line : lines) {
            if (y > height - 20) {
                canvas.drawText("... (truncated)", 10, y, paint);
                break;
            }
            canvas.drawText(line, 10, y, paint);
            y += 20;
        }
        
        return bitmap;
    }
}