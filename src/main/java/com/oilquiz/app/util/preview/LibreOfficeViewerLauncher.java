package com.oilquiz.app.util.preview;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;

/**
 * 用集成在主 APK 内的官方 LibreOffice 查看器(org.libreoffice.LibreOfficeMainActivity)打开文档。
 * 统一入口：将文件路径转成 content URI 显式启动官方查看器。
 */
public final class LibreOfficeViewerLauncher {
    private static final String TAG = "LibreOfficeViewerLauncher";
    private static final String VIEWER_CLASS = "org.libreoffice.LibreOfficeMainActivity";

    private LibreOfficeViewerLauncher() {
    }

    /**
     * 用官方查看器打开文件。
     * @return true 若成功发起
     */
    public static boolean launch(Context context, String filePath) {
        try {
            if (filePath == null || filePath.isEmpty()) {
                return false;
            }
            File file = new File(filePath);
            if (!file.exists()) {
                return false;
            }

            // PDF 交给内置 Pdfium 内核：支持连续滚动，LibreOffice 只把 PDF 当 Draw、只能一页一页翻。
            if (file.getName().toLowerCase().endsWith(".pdf")) {
                com.oilquiz.app.ui.activity.PdfiumPreviewActivity.start(context, filePath);
                Log.i(TAG, "PDF 交由 Pdfium 内核连续滚动预览: " + file.getName());
                return true;
            }

            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent();
            intent.setClassName(context.getPackageName(), VIEWER_CLASS);
            intent.setDataAndType(uri, getMimeType(file.getName()));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            Log.i(TAG, "已交由集成官方查看器打开: " + file.getName());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "启动官方查看器失败: " + e.getMessage(), e);
            return false;
        }
    }

    /** 获取文件 MIME 类型（与集成查看器 manifest 声明的 38 种格式对齐）。 */
    public static String getMimeType(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        switch (ext) {
            // MS Office
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "ppsx": return "application/vnd.openxmlformats-officedocument.presentationml.slideshow";
            // MS Office 模板
            case "dot": case "dotx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.template";
            case "xlt": case "xltx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.template";
            case "pot": case "potx": return "application/vnd.openxmlformats-officedocument.presentationml.template";
            // ODF
            case "odt": return "application/vnd.oasis.opendocument.text";
            case "ods": return "application/vnd.oasis.opendocument.spreadsheet";
            case "odp": return "application/vnd.oasis.opendocument.presentation";
            case "odg": return "application/vnd.oasis.opendocument.graphics";
            // ODF 模板
            case "ott": return "application/vnd.oasis.opendocument.text-template";
            case "ots": return "application/vnd.oasis.opendocument.spreadsheet-template";
            case "otp": return "application/vnd.oasis.opendocument.presentation-template";
            case "otg": return "application/vnd.oasis.opendocument.graphics-template";
            // ODF flat-xml
            case "fodt": return "application/vnd.oasis.opendocument.text-flat-xml";
            case "fods": return "application/vnd.oasis.opendocument.spreadsheet-flat-xml";
            case "fodp": return "application/vnd.oasis.opendocument.presentation-flat-xml";
            case "fodg": return "application/vnd.oasis.opendocument.graphics-flat-xml";
            // 其他文档格式
            case "rtf": return "application/rtf";
            case "csv": return "text/csv";
            case "vsd": return "application/vnd.visio";
            case "vsdx": return "application/vnd.visio2013";
            case "vdx": return "application/vnd.visio.xml";
            case "pub": return "application/x-mspublisher";
            case "wps": return "application/vnd.ms-works";
            case "key": return "application/vnd.apple.keynote";
            case "abw": return "application/x-abiword";
            case "pmd": return "application/x-pagemaker";
            // 矢量图形
            case "emf": return "image/x-emf";
            case "svm": return "image/x-svm";
            case "wmf": return "image/x-wmf";
            case "svg": return "image/svg+xml";
            case "txt": return "text/plain";
            case "pdf": return "application/pdf";
            default: return "*/*";
        }
    }
}
