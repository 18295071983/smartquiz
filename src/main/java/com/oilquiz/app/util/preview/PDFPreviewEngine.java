package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;

public class PDFPreviewEngine extends BasePreviewEngine {

    private static final String[] SUPPORTED_EXTENSIONS = {".pdf"};

    @Override
    protected Bitmap generatePreview(Context context, File file, PreviewProgressCallback progressCallback) throws Exception {
        try (ParcelFileDescriptor fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pdfRenderer = new PdfRenderer(fileDescriptor)) {
            
            if (pdfRenderer.getPageCount() > 0) {
                PdfRenderer.Page page = pdfRenderer.openPage(0);
                
                // 创建适当大小的Bitmap
                int width = page.getWidth();
                int height = page.getHeight();
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                
                // 渲染PDF页面
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                page.close();
                
                return bitmap;
            } else {
                return null;
            }
        } catch (IOException e) {
            throw new Exception("Failed to render PDF", e);
        }
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
        return "PDF Preview Engine";
    }
}