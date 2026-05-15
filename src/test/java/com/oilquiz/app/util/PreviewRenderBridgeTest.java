package com.oilquiz.app.util;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {Build.VERSION_CODES.P})
public class PreviewRenderBridgeTest {

    private Context context;
    private PreviewRenderBridge previewRenderBridge;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getContext();
        previewRenderBridge = new PreviewRenderBridge(context);
    }

    @Test
    public void testCanPreviewTextFile() {
        // 创建一个临时文本文件
        File textFile = createTempFile("test.txt", "Hello, World!");
        assertTrue("文本文件应该可以预览", previewRenderBridge.canPreview(textFile));
        textFile.delete();
    }

    @Test
    public void testCanPreviewPDFFile() {
        // 创建一个临时PDF文件
        File pdfFile = createTempFile("test.pdf", "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\nxref\n0 4\n0000000000 65535 f \n0000000010 00000 n \n0000000053 00000 n \n0000000101 00000 n \ntrailer\n<< /Size 4 /Root 1 0 R >>\n%%EOF");
        assertTrue("PDF文件应该可以预览", previewRenderBridge.canPreview(pdfFile));
        pdfFile.delete();
    }

    @Test
    public void testCannotPreviewUnsupportedFile() {
        // 创建一个不支持的文件类型
        File unsupportedFile = createTempFile("test.unsupported", "Unsupported content");
        assertFalse("不支持的文件类型不应该可以预览", previewRenderBridge.canPreview(unsupportedFile));
        unsupportedFile.delete();
    }

    @Test
    public void testClearPreviewCache() {
        // 测试清理预览缓存
        long initialSize = previewRenderBridge.getPreviewCacheSize();
        previewRenderBridge.clearPreviewCache();
        long afterSize = previewRenderBridge.getPreviewCacheSize();
        assertTrue("清理缓存后，缓存大小应该减小或为0", afterSize <= initialSize);
    }

    // 创建临时文件的辅助方法
    private File createTempFile(String fileName, String content) {
        try {
            File tempFile = new File(context.getCacheDir(), fileName);
            OutputStream outputStream = context.openFileOutput(fileName, Context.MODE_PRIVATE);
            outputStream.write(content.getBytes());
            outputStream.close();
            return new File(context.getFilesDir(), fileName);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}