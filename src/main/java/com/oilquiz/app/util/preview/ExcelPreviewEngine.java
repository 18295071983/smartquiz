package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.File;

public class ExcelPreviewEngine extends BasePreviewEngine {

    private static final String[] SUPPORTED_EXTENSIONS = {".xlsx", ".xls"};

    @Override
    protected Bitmap generatePreview(Context context, File file, PreviewProgressCallback progressCallback) throws Exception {
        return createExcelPreview(file);
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
        return "Excel Preview Engine";
    }

    private Bitmap createExcelPreview(File file) {
        int width = 800;
        int height = 600;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        
        canvas.drawColor(Color.WHITE);
        
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(14);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        
        // 绘制Excel文件信息
        canvas.drawText("Excel表格", 10, 30, paint);
        
        paint.setTextSize(12);
        paint.setTypeface(Typeface.DEFAULT);
        canvas.drawText("文件名: " + file.getName(), 10, 60, paint);
        canvas.drawText("文件大小: " + (file.length() / 1024) + " KB", 10, 80, paint);
        
        paint.setTextSize(14);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("预览内容:", 10, 110, paint);
        
        paint.setTextSize(12);
        paint.setTypeface(Typeface.DEFAULT);
        canvas.drawText("[表格数据预览]", 10, 140, paint);
        canvas.drawText("- 包含多行多列数据", 10, 160, paint);
        canvas.drawText("- 可能包含数字、文本等类型", 10, 180, paint);
        canvas.drawText("- 点击打开查看完整内容", 10, 200, paint);
        
        return bitmap;
    }
}