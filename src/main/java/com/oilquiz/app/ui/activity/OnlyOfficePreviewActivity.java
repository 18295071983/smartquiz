package com.oilquiz.app.ui.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.util.preview.OnlyOfficePreviewManager;

import java.io.File;
import java.io.IOException;

/**
 * OnlyOffice 文档预览 Activity
 * 使用 OnlyOffice Document Server SDK 或 WebView 预览文档
 *
 * OnlyOffice 工作原理：
 * 1. 需要连接到 OnlyOffice Document Server（可自建或使用官方服务器）
 * 2. 先将文件上传到服务器
 * 3. 通过嵌入式编辑器预览和编辑
 *
 * 使用方式：
 * 1. 自建 Document Server：部署 OnlyOffice Document Server
 * 2. 使用官方测试服务器：需要网络连接
 * 3. 使用云服务：注册 OnlyOffice 云服务
 */
public class OnlyOfficePreviewActivity extends com.oilquiz.app.ui.base.BaseActivity {
    private static final String TAG = "OnlyOfficePreviewActivity";
    private static final String EXTRA_FILE_PATH = "file_path";
    private static final int REQUEST_CODE_FILE_PICKER = 1001;

    private String filePath;
    private OnlyOfficePreviewManager onlyOfficeManager;
    private FrameLayout container;
    private ProgressBar progressBar;
    private TextView statusText;
    private WebView webView;
    private LinearLayout controlBar;
    private Button btnClose;
    private Button btnEdit;

    private String documentServerUrl;
    private String editorJavascriptUrl;

    public static void start(Context context, String filePath) {
        Intent intent = new Intent(context, OnlyOfficePreviewActivity.class);
        intent.putExtra(EXTRA_FILE_PATH, filePath);
        context.startActivity(intent);
    }

    @Override
    protected int getLayoutId() {
        return 0;
    }

    @Override
    protected void initView() {
        com.oilquiz.app.ai.util.APIKeyManager apiKeyManager = com.oilquiz.app.ai.util.APIKeyManager.getInstance(this);
        documentServerUrl = apiKeyManager.getAPIHost(
                com.oilquiz.app.ai.util.APIKeyManager.Service.ONLYOFFICE,
                com.oilquiz.app.ai.util.APIKeyManager.DefaultHost.ONLYOFFICE);
        if (!documentServerUrl.endsWith("/")) {
            documentServerUrl = documentServerUrl + "/";
        }
        editorJavascriptUrl = documentServerUrl + "web-apps/apps/api/documents/api.js";

        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.setBackgroundColor(0xFF333333);

        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setBackgroundColor(0xFF3B82F6);
        titleBar.setPadding(20, 20, 20, 20);
        titleBar.setGravity(android.view.Gravity.CENTER_VERTICAL);

        Button btnBack = new Button(this);
        btnBack.setText("返回");
        btnBack.setOnClickListener(v -> finish());

        TextView tvTitle = new TextView(this);
        tvTitle.setText("OnlyOffice 文档预览");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(18);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMargins(20, 0, 20, 0);
        tvTitle.setLayoutParams(titleParams);

        titleBar.addView(btnBack);
        titleBar.addView(tvTitle);

        controlBar = new LinearLayout(this);
        controlBar.setOrientation(LinearLayout.HORIZONTAL);
        controlBar.setBackgroundColor(0xFF444444);
        controlBar.setPadding(10, 10, 10, 10);
        controlBar.setVisibility(View.GONE);

        btnEdit = new Button(this);
        btnEdit.setText("在编辑器中打开");
        btnEdit.setOnClickListener(v -> openInEditor());
        btnEdit.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        btnClose = new Button(this);
        btnClose.setText("关闭");
        btnClose.setOnClickListener(v -> finish());
        btnClose.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        controlBar.addView(btnEdit);
        controlBar.addView(btnClose);

        statusText = new TextView(this);
        statusText.setText("正在初始化 OnlyOffice...");
        statusText.setTextColor(0xFFFFFFFF);
        statusText.setTextSize(16);
        statusText.setPadding(40, 40, 40, 20);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                10
        );
        progressParams.setMargins(40, 20, 40, 20);

        container = new FrameLayout(this);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
        ));

        rootLayout.addView(titleBar);
        rootLayout.addView(controlBar);
        rootLayout.addView(statusText);
        rootLayout.addView(progressBar);
        rootLayout.addView(container);

        setContentView(rootLayout);
    }

    @Override
    protected void initData() {
        filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
        if (filePath == null || filePath.isEmpty()) {
            AppLogger.i(TAG, "文件路径为空，启动文件选择器");
            launchFilePicker();
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            AppLogger.e(TAG, "文件不存在: " + filePath);
            launchFilePicker();
            return;
        }

        onlyOfficeManager = OnlyOfficePreviewManager.getInstance(this);

        if (!onlyOfficeManager.isInitialized()) {
            boolean initialized = onlyOfficeManager.initialize();
            if (!initialized) {
                AppLogger.e(TAG, "OnlyOffice SDK 初始化失败");
                showErrorAndFinish("OnlyOffice SDK 初始化失败");
                return;
            }
        }

        if (onlyOfficeManager.supportsFile(filePath)) {
            AppLogger.d(TAG, "文件格式支持: " + filePath);
            openFile();
        } else {
            AppLogger.e(TAG, "文件格式不支持: " + filePath);
            showErrorAndFinish("文件格式不支持: " + onlyOfficeManager.getFileExtension(filePath));
        }
    }

    @Override
    protected void initListener() {
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void openFile() {
        statusText.setText("正在准备文档预览...");
        controlBar.setVisibility(View.VISIBLE);

        webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                statusText.setText("正在加载 OnlyOffice 编辑器...");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                statusText.setVisibility(View.GONE);
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                AppLogger.e(TAG, "WebView 错误: " + description);
                statusText.setText("加载失败，请检查网络连接");
            }
        });

        container.addView(webView);

        // 生成 OnlyOffice 预览页面
        String previewHtml = generateOnlyOfficePreviewHtml();
        webView.loadDataWithBaseURL(documentServerUrl, previewHtml, "text/html", "UTF-8", null);

        AppLogger.d(TAG, "OnlyOffice 预览启动成功");
    }

    /**
     * 生成 OnlyOffice 预览 HTML 页面
     * 使用 OnlyOffice 嵌入式编辑器 API
     */
    private String generateOnlyOfficePreviewHtml() {
        String fileName = new File(filePath).getName();
        String fileExtension = getFileExtension(filePath).toLowerCase();

        // OnlyOffice 配置
        String documentType = "text";
        if (fileExtension.matches("xls|xlsx|xlsm|csv|ods")) {
            documentType = "spreadsheet";
        } else if (fileExtension.matches("ppt|pptx|pptm|odp")) {
            documentType = "presentation";
        } else if (fileExtension.matches("pdf")) {
            documentType = "pdf";
        }

        return "<!DOCTYPE html>" +
                "<html>" +
                "<head>" +
                "<meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
                "<title>OnlyOffice Preview - " + fileName + "</title>" +
                "<script type='text/javascript' src='" + editorJavascriptUrl + "'></script>" +
                "<style>" +
                "body { margin: 0; padding: 20px; font-family: Arial, sans-serif; background: #333; color: #fff; }" +
                ".container { max-width: 1200px; margin: 0 auto; }" +
                ".header { margin-bottom: 20px; }" +
                ".title { font-size: 24px; font-weight: bold; }" +
                ".info { color: #aaa; margin-top: 10px; }" +
                ".editor { width: 100%; height: 600px; border: 1px solid #555; background: #fff; }" +
                ".notice { background: #444; padding: 15px; border-radius: 8px; margin-top: 20px; }" +
                ".notice h3 { margin-top: 0; color: #4CAF50; }" +
                ".notice ul { margin: 10px 0 0 0; padding-left: 20px; }" +
                ".notice li { margin: 5px 0; }" +
                ".btn { display: inline-block; padding: 10px 20px; background: #4CAF50; color: white; " +
                "text-decoration: none; border-radius: 5px; margin-top: 10px; cursor: pointer; }" +
                ".btn:hover { background: #45a049; }" +
                "</style>" +
                "</head>" +
                "<body>" +
                "<div class='container'>" +
                "<div class='header'>" +
                "<div class='title'>OnlyOffice 文档预览</div>" +
                "<div class='info'>文件名: " + fileName + "</div>" +
                "<div class='info'>类型: " + documentType.toUpperCase() + "</div>" +
                "<div class='info'>扩展名: ." + fileExtension + "</div>" +
                "</div>" +

                "<div id='placeholder' class='editor'></div>" +

                "<div class='notice'>" +
                "<h3>⚠️ 使用说明</h3>" +
                "<p>OnlyOffice 预览需要连接到 Document Server。</p>" +
                "<ul>" +
                "<li><strong>在线模式：</strong>需要配置 OnlyOffice Document Server URL</li>" +
                "<li><strong>离线模式：</strong>请使用应用内置的 TBS 或 Pdfium 预览</li>" +
                "<li><strong>自建服务器：</strong>部署 OnlyOffice Document Server 并配置 URL</li>" +
                "</ul>" +
                "<p>当前配置: " + documentServerUrl + "</p>" +
                "<a class='btn' href='javascript:openInOnlyOffice()'>在 OnlyOffice 编辑器中打开</a>" +
                "</div>" +
                "</div>" +

                "<script>" +
                "var docEditor = null;" +
                "var fileName = '" + fileName + "';" +
                "var fileType = '" + fileExtension + "';" +
                "var documentType = '" + documentType + "';" +
                "var documentServerUrl = '" + documentServerUrl + "';" +

                "function openInOnlyOffice() {" +
                "    if (docEditor) {" +
                "        docEditor.destroyEditor();" +
                "    }" +
                "" +
                "    var config = {" +
                "        document: {" +
                "            fileType: fileType," +
                "            title: fileName," +
                "            url: documentServerUrl + 'cache/files/" + fileName + "'" +
                "        }," +
                "        documentType: documentType," +
                "        editorConfig: {" +
                "            mode: 'view'," +
                "            callbackUrl: documentServerUrl + 'Callback'" +
                "        }," +
                "        width: '100%'," +
                "        height: '600px'" +
                "    };" +
                "" +
                "    docEditor = new DocsAPI.DocEditor('placeholder', config);" +
                "}" +

                "window.onload = function() {" +
                "    // 自动尝试打开编辑器" +
                "    setTimeout(openInOnlyOffice, 1000);" +
                "};" +
                "</script>" +
                "</body>" +
                "</html>";
    }

    /**
     * 在 OnlyOffice 编辑器中打开
     */
    private void openInEditor() {
        if (webView != null) {
            webView.evaluateJavascript("openInOnlyOffice()", null);
        }
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String filePath) {
        if (filePath == null || !filePath.contains(".")) {
            return "";
        }
        return filePath.substring(filePath.lastIndexOf(".") + 1);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (webView != null) {
            webView.stopLoading();
            webView.removeAllViews();
            webView.destroy();
        }

        if (onlyOfficeManager != null) {
            onlyOfficeManager.release();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * 显示错误并关闭
     */
    private void showErrorAndFinish(String error) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("OnlyOffice 预览失败")
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

        new android.app.AlertDialog.Builder(this)
                .setTitle("选择预览方式")
                .setItems(new String[]{"TBS SDK", "Pdfium (仅PDF)", "WebView (表格文件)"}, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            com.oilquiz.app.ui.activity.TBSFilePreviewActivity.start(this, filePath);
                            break;
                        case 1:
                            if (getFileExtension(filePath).equalsIgnoreCase("pdf")) {
                                com.oilquiz.app.ui.activity.PdfiumPreviewActivity.start(this, filePath);
                            } else {
                                com.oilquiz.app.ui.activity.WebViewFilePreviewActivity.start(this, filePath);
                            }
                            break;
                        case 2:
                            com.oilquiz.app.ui.activity.WebViewFilePreviewActivity.start(this, filePath);
                            break;
                    }
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 启动文件选择器
     */
    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);

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
                    String path = getPathFromUri(uri);
                    if (path != null) {
                        AppLogger.i(TAG, "选择的文件路径: " + path);
                        Intent intent = new Intent(this, OnlyOfficePreviewActivity.class);
                        intent.putExtra(EXTRA_FILE_PATH, path);
                        startActivity(intent);
                        finish();
                    } else {
                        AppLogger.e(TAG, "无法获取文件路径");
                        finish();
                    }
                } catch (Exception e) {
                    AppLogger.e(TAG, "处理文件选择结果失败", e);
                    finish();
                }
            } else {
                AppLogger.e(TAG, "文件选择返回空 Uri");
                finish();
            }
        } else if (requestCode == REQUEST_CODE_FILE_PICKER) {
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
                        AppLogger.w(TAG, "尝试获取文件路径失败: " + e.getMessage());
                    }
                }

                return getPathFromContentUri(uri);
            } else if (uri.getScheme().equals("file")) {
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
        String mimeType = getContentResolver().getType(uri);
        String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extension == null) {
            extension = "tmp";
        }

        File tempFile = File.createTempFile("onlyoffice_", "." + extension, getExternalFilesDir(null));
        tempFile.deleteOnExit();

        try (java.io.InputStream inputStream = getContentResolver().openInputStream(uri);
             java.io.FileOutputStream outputStream = new java.io.FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
        }

        return tempFile;
    }
}
