package com.oilquiz.app.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.util.preview.TBSPreviewManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * TBS SDK 文件预览 Activity
 * 
 * 使用腾讯浏览服务 SDK 预览 Office 和 PDF 文件
 */
public class TBSFilePreviewActivity extends Activity {
    
    private static final String TAG = "TBSFilePreviewActivity";
    private static final String EXTRA_FILE_PATH = "file_path";
    private static final int REQUEST_CODE_FILE_PICKER = 1001;
    
    private FrameLayout container;
    private ProgressBar progressBar;
    private TextView statusText;
    private TBSPreviewManager tbsManager;
    private String filePath;
    
    /**
     * 启动预览
     * @param context 上下文
     * @param filePath 文件路径
     */
    public static void start(Context context, String filePath) {
        Intent intent = new Intent(context, TBSFilePreviewActivity.class);
        intent.putExtra(EXTRA_FILE_PATH, filePath);
        context.startActivity(intent);
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 获取文件路径
        filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
        if (filePath == null || filePath.isEmpty()) {
            AppLogger.i(TAG, "文件路径为空，启动文件选择器");
            launchFilePicker();
            return;
        }
        
        // 检查文件是否存在
        File file = new File(filePath);
        if (!file.exists()) {
            AppLogger.e(TAG, "文件不存在: " + filePath);
            launchFilePicker();
            return;
        }
        
        // 创建简单的加载界面
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFF2C3E50);
        
        // 状态文本
        statusText = new TextView(this);
        statusText.setText("正在初始化 TBS 预览...");
        statusText.setTextColor(0xFFFFFFFF);
        statusText.setTextSize(16);
        statusText.setPadding(40, 40, 40, 20);
        layout.addView(statusText);
        
        // 进度条
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                10
        );
        progressParams.setMargins(40, 20, 40, 20);
        layout.addView(progressBar, progressParams);
        
        // 容器（用于 TBS 预览）
        container = new FrameLayout(this);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
        ));
        layout.addView(container);
        
        setContentView(layout);
        
        // 初始化 TBS
        initTBS();
    }
    
    private void initTBS() {
        tbsManager = TBSPreviewManager.getInstance(this);
        
        // 检查 TBS 是否可用
        if (!tbsManager.isTBSAvailable()) {
            statusText.setText("TBS SDK 未集成");
            AppLogger.e(TAG, "TBS SDK 未集成");
            showErrorAndFinish("TBS SDK 未集成，请检查 AAR 文件是否正确放置");
            return;
        }
        
        // 检查是否已初始化
        // 根据腾讯文档示例，TBS SDK 应该在主 Activity 中初始化
        if (!tbsManager.isInitialized()) {
            AppLogger.w(TAG, "TBS SDK 未初始化，尝试在预览 Activity 中初始化");
            statusText.setText("正在初始化 TBS SDK...");
            
            // 设置隐私政策同意状态（如果未设置）
            if (!tbsManager.isPrivacyPolicyAccepted()) {
                tbsManager.setPrivacyPolicyAccepted(true);
            }
            
            tbsManager.initializeAsync((success, errorCode) -> {
                runOnUiThread(() -> {
                    if (success) {
                        AppLogger.d(TAG, "TBS SDK 初始化成功");
                        openFile();
                    } else {
                        AppLogger.e(TAG, "TBS SDK 初始化失败，错误码: " + errorCode);
                        String errorMsg = getTBSInitErrorMessage(errorCode);
                        statusText.setText("初始化失败: " + errorMsg);
                        showErrorAndFinish("TBS SDK 初始化失败: " + errorMsg);
                    }
                });
            });
        } else {
            AppLogger.d(TAG, "TBS SDK 已初始化，直接打开文件");
            openFile();
        }
    }
    
    /**
     * 检查网络是否可用
     */
    private boolean isNetworkAvailable() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                return info != null && info.isConnected();
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "检查网络失败: " + e.getMessage());
        }
        return true; // 默认返回 true，让 TBS 自己处理
    }
    
    /**
     * 获取 TBS 初始化错误信息
     */
    private String getTBSInitErrorMessage(int errorCode) {
        switch (errorCode) {
            case -1:
                return "未知错误";
            case -2:
                return "LicenseKey 无效";
            case -3:
                return "网络错误";
            case -4:
                return "授权失败";
            default:
                return "错误码: " + errorCode;
        }
    }
    
    /**
     * 显示错误并关闭
     */
    private void showErrorAndFinish(String error) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("TBS 预览失败")
                .setMessage(error)
                .setPositiveButton("使用备用预览", (dialog, which) -> useAlternativePreview())
                .setNegativeButton("取消", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }
    
    /**
     * 使用备用预览方式
     */
    private void useAlternativePreview() {
        AppLogger.d(TAG, "使用备用预览方式");
        
        // 显示预览方式选择对话框
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择预览方式")
                .setItems(new String[]{"LibreOffice (开源免费)", "OnlyOffice (开源免费)", "Pdfium (仅PDF)", "WebView (仅表格文件)"}, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            // 使用 LibreOfficeKit 预览
                            AppLogger.d(TAG, "使用 LibreOfficeKitPreviewActivity 预览文件");
                            Intent libreOfficeIntent = new Intent(this, LibreOfficeKitPreviewActivity.class);
                            libreOfficeIntent.putExtra("file_path", filePath);
                            startActivity(libreOfficeIntent);
                            break;
                        case 1:
                            // 使用 OnlyOffice 预览
                            AppLogger.d(TAG, "使用 OnlyOfficePreviewActivity 预览文件");
                            Intent onlyOfficeIntent = new Intent(this, OnlyOfficePreviewActivity.class);
                            onlyOfficeIntent.putExtra("file_path", filePath);
                            startActivity(onlyOfficeIntent);
                            break;
                        case 2:
                            // PDF文件使用PdfiumPreviewActivity
                            String ext = getFileExtension(filePath);
                            if (ext.equalsIgnoreCase("pdf")) {
                                AppLogger.d(TAG, "使用 PdfiumPreviewActivity 预览 PDF 文件");
                                Intent pdfiumIntent = new Intent(this, PdfiumPreviewActivity.class);
                                pdfiumIntent.putExtra("file_path", filePath);
                                startActivity(pdfiumIntent);
                            } else {
                                // 非PDF文件使用其他方式
                                useWebViewPreview();
                            }
                            break;
                        case 3:
                            // 使用 WebView 预览
                            useWebViewPreview();
                            break;
                    }
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    /**
     * 使用 WebView 预览
     */
    private void useWebViewPreview() {
        String ext = getFileExtension(filePath);
        if (ext.equalsIgnoreCase("xlsx") || ext.equalsIgnoreCase("xls") || 
            ext.equalsIgnoreCase("csv") || ext.equalsIgnoreCase("json")) {
            // Excel、CSV、JSON文件使用WebViewFilePreviewActivity
            AppLogger.d(TAG, "使用 WebViewFilePreviewActivity 预览表格文件");
            Intent intent = new Intent(this, WebViewFilePreviewActivity.class);
            intent.putExtra("file_path", filePath);
            startActivity(intent);
        } else {
            // 其他文件使用FilePreviewActivity
            AppLogger.d(TAG, "使用 FilePreviewActivity 预览文件");
            Intent intent = new Intent(this, FilePreviewActivity.class);
            intent.putExtra("file_path", filePath);
            startActivity(intent);
        }
    }
    
    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filePath) {
        if (filePath == null || !filePath.contains(".")) {
            return "";
        }
        return filePath.substring(filePath.lastIndexOf(".") + 1).toLowerCase();
    }
    
    private void openFile() {
        statusText.setText("正在打开文件...");
        
        // 检查文件路径
        AppLogger.d(TAG, "准备打开文件: " + filePath);
        java.io.File file = new java.io.File(filePath);
        AppLogger.d(TAG, "文件存在: " + file.exists());
        AppLogger.d(TAG, "文件大小: " + file.length() + " bytes");
        AppLogger.d(TAG, "文件可读: " + file.canRead());
        AppLogger.d(TAG, "文件绝对路径: " + file.getAbsolutePath());
        
        // 检查文件是否在 App 私有目录下
        String privateDir = getExternalFilesDir(null).getAbsolutePath();
        String cacheDir = getCacheDir().getAbsolutePath();
        AppLogger.d(TAG, "App 私有目录: " + privateDir);
        AppLogger.d(TAG, "App Cache 目录: " + cacheDir);
        AppLogger.d(TAG, "文件在私有目录下: " + filePath.startsWith(privateDir));
        AppLogger.d(TAG, "文件在 Cache 目录下: " + filePath.startsWith(cacheDir));
        
        int result = tbsManager.openFileWithActivity(this, filePath, new TBSPreviewManager.TBSPreviewCallback() {
            @Override
            public void onFileOpened() {
                runOnUiThread(() -> {
                    AppLogger.d(TAG, "TBS 文件已打开");
                    statusText.setText("文件已打开");
                });
            }
            
            @Override
            public void onFileClosed() {
                runOnUiThread(() -> {
                    AppLogger.d(TAG, "TBS 文件已关闭");
                    finish();
                });
            }
            
            @Override
            public void onReadyToDisplay() {
                runOnUiThread(() -> {
                    AppLogger.d(TAG, "TBS 准备显示文档");
                    statusText.setVisibility(View.GONE);
                    progressBar.setVisibility(View.GONE);
                });
            }
            
            @Override
            public void onClick() {
                AppLogger.d(TAG, "TBS 点击事件");
            }
            
            @Override
            public void onScrollBegin() {
                AppLogger.d(TAG, "TBS 开始滚动");
            }
            
            @Override
            public void onScrollEnd() {
                AppLogger.d(TAG, "TBS 结束滚动");
            }
            
            @Override
            public void onScaleBegin() {
                AppLogger.d(TAG, "TBS 开始缩放");
            }
            
            @Override
            public void onScaleEnd() {
                AppLogger.d(TAG, "TBS 结束缩放");
            }
            
            @Override
            public void onPageChanged(int currentPage, int totalPages) {
                AppLogger.d(TAG, "TBS 页面变化: " + currentPage + "/" + totalPages);
            }
        }, container);
        
        if (result != 0) {
            statusText.setText("打开文件失败: " + result);
            AppLogger.e(TAG, "打开文件失败: " + result);
        } else {
            AppLogger.d(TAG, "TBS 预览启动成功");
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        if (isFinishing() && tbsManager != null) {
            tbsManager.closeFile();
        }
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tbsManager != null) {
            tbsManager.closeFile();
        }
    }
    
    @Override
    public void onBackPressed() {
        // 关闭 TBS 预览
        if (tbsManager != null) {
            tbsManager.closeFile();
        }
        super.onBackPressed();
    }
    
    /**
     * 启动文件选择器
     */
    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        
        // 支持的文件类型
        String[] mimeTypes = {
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain",
            "text/csv",
            "application/json"
        };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        
        try {
            startActivityForResult(Intent.createChooser(intent, "选择要预览的文件"), REQUEST_CODE_FILE_PICKER);
        } catch (android.content.ActivityNotFoundException ex) {
            AppLogger.e(TAG, "没有找到文件选择器应用", ex);
            new android.app.AlertDialog.Builder(this)
                    .setTitle("错误")
                    .setMessage("没有找到文件选择器应用，请安装文件管理器")
                    .setPositiveButton("确定", (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
        }
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQUEST_CODE_FILE_PICKER && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    // 将 Uri 转换为文件路径
                    String path = getPathFromUri(uri);
                    if (path != null) {
                        AppLogger.i(TAG, "选择的文件路径: " + path);
                        // 重新启动预览
                        Intent intent = new Intent(this, TBSFilePreviewActivity.class);
                        intent.putExtra(EXTRA_FILE_PATH, path);
                        startActivity(intent);
                        finish();
                    } else {
                        AppLogger.e(TAG, "无法获取文件路径");
                        showErrorAndFinish("无法获取文件路径");
                    }
                } catch (Exception e) {
                    AppLogger.e(TAG, "处理文件选择结果失败", e);
                    showErrorAndFinish("处理文件选择结果失败: " + e.getMessage());
                }
            } else {
                AppLogger.e(TAG, "文件选择返回空 Uri");
                showErrorAndFinish("文件选择返回空 Uri");
            }
        } else if (requestCode == REQUEST_CODE_FILE_PICKER) {
            // 用户取消了文件选择
            AppLogger.i(TAG, "用户取消了文件选择");
            finish();
        }
    }
    
    /**
     * 从 Uri 获取文件路径
     */
    private String getPathFromUri(Uri uri) {
        try {
            if (uri.getScheme().equals("content")) {
                // 对于 content:// 类型的 Uri
                // 尝试多种方式获取文件路径
                String[] projections = {
                    android.provider.MediaStore.Images.Media.DATA,
                    android.provider.MediaStore.MediaColumns.DATA,
                    android.provider.MediaStore.Files.FileColumns.DATA
                };
                
                for (String projection : projections) {
                    try {
                        android.database.Cursor cursor = getContentResolver().query(uri, new String[]{projection}, null, null, null);
                        if (cursor != null) {
                            if (cursor.moveToFirst()) {
                                int columnIndex = cursor.getColumnIndexOrThrow(projection);
                                String path = cursor.getString(columnIndex);
                                cursor.close();
                                if (path != null && !path.isEmpty()) {
                                    return path;
                                }
                            }
                            cursor.close();
                        }
                    } catch (Exception e) {
                        // 尝试下一种方式
                        AppLogger.w(TAG, "尝试获取文件路径失败: " + e.getMessage());
                    }
                }
                
                // 如果以上方法都失败，尝试使用临时文件方式
                return getPathFromContentUri(uri);
            } else if (uri.getScheme().equals("file")) {
                // 对于 file:// 类型的 Uri
                return uri.getPath();
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "从 Uri 获取文件路径失败", e);
        }
        return null;
    }
    
    /**
     * 从 content:// Uri 获取文件路径（通过创建临时文件）
     */
    private String getPathFromContentUri(Uri uri) {
        try {
            // 创建临时文件
            File tempFile = createTempFileFromUri(uri);
            if (tempFile != null) {
                return tempFile.getAbsolutePath();
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "从 content Uri 创建临时文件失败", e);
        }
        return null;
    }
    
    /**
     * 从 Uri 创建临时文件
     */
    private File createTempFileFromUri(Uri uri) throws IOException {
        // 获取文件类型
        String mimeType = getContentResolver().getType(uri);
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extension == null) {
            extension = "tmp";
        }
        
        // 创建临时文件
        File tempFile = File.createTempFile("tbs_", "." + extension, getExternalFilesDir(null));
        tempFile.deleteOnExit();
        
        // 复制文件内容
        try (InputStream inputStream = getContentResolver().openInputStream(uri);
             FileOutputStream outputStream = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
        }
        
        return tempFile;
    }
    
    /**
     * 横屏适配
     * 根据腾讯文档：自定义 layout 方式需要主动调用接口适配横屏
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        
        // 屏幕方向改变时，通知 TBS SDK 调整大小
        if (container != null && tbsManager != null) {
            int width = container.getWidth();
            int height = container.getHeight();
            
            AppLogger.d(TAG, "屏幕方向改变，调整 TBS 布局: " + width + "x" + height);
            
            // 延迟调用，确保布局已完成
            container.post(() -> {
                int newWidth = container.getWidth();
                int newHeight = container.getHeight();
                tbsManager.onSizeChanged(newWidth, newHeight);
            });
        }
    }
}
