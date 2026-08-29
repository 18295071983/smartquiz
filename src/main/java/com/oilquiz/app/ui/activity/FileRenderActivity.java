package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.webkit.WebView;
import com.google.android.material.button.MaterialButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.oilquiz.app.R;
import com.oilquiz.app.util.PreviewRenderBridge;
import com.oilquiz.app.util.render.FileRenderEngine;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class FileRenderActivity extends AppCompatActivity {

    private static final String TAG = "FileRenderActivity";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_FILE_URI = "file_uri";

    private LinearLayout loadingLayout;
    private ProgressBar progressBar;
    private ProgressBar progressHorizontal;
    private TextView tvLoading;
    private TextView tvProgress;
    private LinearLayout contentLayout;
    private ImageView ivImage;
    private TextView tvText;
    private WebView wvHtml;
    private LinearLayout errorLayout;
    private TextView tvErrorTitle;
    private TextView tvErrorMessage;
    private MaterialButton btnRetry;
    private MaterialButton btnOpenWith;
    private TextView tvFileName;
    private MaterialButton btnShare;
    private TextView tvEngineStatus;
    private TextView tvFileInfo;

    private File currentFile;
    private PreviewRenderBridge previewRenderBridge;
    private ScaleGestureDetector scaleGestureDetector;
    private float scale = 1.0f;
    private android.widget.VideoView videoView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_render);

        initViews();
        setupListeners();
        setupImageZoom();
        previewRenderBridge = new PreviewRenderBridge(this);
        handleIntent(getIntent());
    }

    private void setupImageZoom() {
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scale *= detector.getScaleFactor();
                scale = Math.max(0.1f, Math.min(scale, 5.0f));
                ivImage.setScaleX(scale);
                ivImage.setScaleY(scale);
                return true;
            }
        });

        ivImage.setOnTouchListener((v, event) -> {
            if (event == null) return false;
            scaleGestureDetector.onTouchEvent(event);
            return true;
        });
    }

    private void initViews() {
        loadingLayout = findViewById(R.id.loading_layout);
        progressBar = findViewById(R.id.progress_bar);
        progressHorizontal = findViewById(R.id.progress_horizontal);
        tvLoading = findViewById(R.id.tv_loading);
        tvProgress = findViewById(R.id.tv_progress);
        contentLayout = findViewById(R.id.content_layout);
        ivImage = findViewById(R.id.iv_image);
        tvText = findViewById(R.id.tv_text);
        wvHtml = findViewById(R.id.wv_html);
        errorLayout = findViewById(R.id.error_layout);
        tvErrorTitle = findViewById(R.id.tv_error_title);
        tvErrorMessage = findViewById(R.id.tv_error_message);
        btnRetry = findViewById(R.id.btn_retry);
        btnOpenWith = findViewById(R.id.btn_open_with);
        tvFileName = findViewById(R.id.tv_file_name);
        btnShare = findViewById(R.id.btn_share);
        tvEngineStatus = findViewById(R.id.tv_engine_status);
        tvFileInfo = findViewById(R.id.tv_file_info);

        setupWebView();
    }

    private void setupWebView() {
        if (wvHtml != null) {
            android.webkit.WebSettings webSettings = wvHtml.getSettings();
            webSettings.setJavaScriptEnabled(true);
            webSettings.setDomStorageEnabled(true);
            webSettings.setLoadWithOverviewMode(true);
            webSettings.setUseWideViewPort(true);
            webSettings.setSupportZoom(true);
            webSettings.setBuiltInZoomControls(true);
            webSettings.setDisplayZoomControls(false);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                webSettings.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }

            wvHtml.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public void onPageStarted(android.webkit.WebView view, String url, android.graphics.Bitmap favicon) {
                    super.onPageStarted(view, url, favicon);
                    Log.d(TAG, "WebView started loading: " + url);
                }

                @Override
                public void onPageFinished(android.webkit.WebView view, String url) {
                    super.onPageFinished(view, url);
                    Log.d(TAG, "WebView finished loading: " + url);
                }

                @Override
                public void onReceivedError(android.webkit.WebView view, int errorCode, String description, String failingUrl) {
                    super.onReceivedError(view, errorCode, description, failingUrl);
                    Log.e(TAG, "WebView error: " + description + " (" + errorCode + ")");
                }
            });
        }
    }

    private void setupListeners() {
        if (btnShare != null) {
            btnShare.setOnClickListener(v -> shareFile());
        }
        if (btnRetry != null) {
            btnRetry.setOnClickListener(v -> renderFile(currentFile));
        }
        if (btnOpenWith != null) {
            btnOpenWith.setOnClickListener(v -> openWithOtherApp(currentFile));
        }
    }

    private void handleIntent(Intent intent) {
        if (intent.hasExtra(EXTRA_FILE_PATH)) {
            String filePath = intent.getStringExtra(EXTRA_FILE_PATH);
            currentFile = new File(filePath);
            renderFile(currentFile);
        } else if (intent.hasExtra(EXTRA_FILE_URI)) {
            Uri uri = intent.getParcelableExtra(EXTRA_FILE_URI);
            try {
                currentFile = copyFileFromUri(uri);
                if (currentFile != null) {
                    renderFile(currentFile);
                } else {
                    showImportFileDialog();
                }
            } catch (IOException e) {
                Log.e(TAG, "Error copying file from URI: " + e.getMessage(), e);
                showError("文件处理失败", "无法复制文件: " + e.getMessage());
            }
        } else {
            showImportFileDialog();
        }
    }

    private void renderFile(File file) {
        if (file == null || !file.exists()) {
            showError("文件不存在", "无法找到指定的文件");
            return;
        }

        // Office 文档（Word/Excel/PPT）：优先 LibreOfficeKit（纯离线、最高保真）；
        // 不可用则优先调起系统 Office 应用（如 WPS）；再不行回退内置引擎（POI→HTML）。
        if (isOfficeDocument(file)) {
            if (com.oilquiz.app.util.preview.LibreOfficeKitPreviewManager.getInstance(this).isAvailable()) {
                com.oilquiz.app.ui.activity.LibreOfficeKitPreviewActivity.start(this, file.getAbsolutePath());
                return;
            }
            if (hasExternalViewer(file)) {
                openWithOtherApp(file);
                return;
            }
        }

        tvFileName.setText(file.getName());
        if (tvFileInfo != null) {
            tvFileInfo.setText("文件大小: " + (file.length() / 1024) + "KB");
        }
        tvEngineStatus.setText("引擎状态: 准备中");
        showLoading();

        FileRenderEngine engine = PreviewRenderBridge.RenderEngineFactory.getInstance().getEngineForFile(file);
        if (engine != null) {
            tvEngineStatus.setText("引擎状态: 使用 " + engine.getEngineName());
        } else {
            tvEngineStatus.setText("引擎状态: 未找到适合的引擎");
        }

        previewRenderBridge.renderFile(file, new PreviewRenderBridge.PreviewCallback() {
            @Override
            public void onSuccess(Object previewContent) {
                runOnUiThread(() -> {
                    tvEngineStatus.setText("引擎状态: 渲染成功");
                    hideLoading();
                    displayRenderedContent(previewContent);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    tvEngineStatus.setText("引擎状态: 渲染失败");
                    hideLoading();
                    showError("渲染失败", error);
                });
            }

            @Override
            public void onProgress(int progress) {
                runOnUiThread(() -> {
                    tvEngineStatus.setText("引擎状态: 渲染中 " + progress + "%");
                    updateProgress(progress);
                });
            }
        });
    }

    private void showLoading() {
        loadingLayout.setVisibility(View.VISIBLE);
        contentLayout.setVisibility(View.GONE);
        errorLayout.setVisibility(View.GONE);
        progressHorizontal.setProgress(0);
        tvProgress.setText("0%");
    }

    private void hideLoading() {
        loadingLayout.setVisibility(View.GONE);
        contentLayout.setVisibility(View.VISIBLE);
    }

    private void updateProgress(int progress) {
        progressHorizontal.setProgress(progress);
        tvProgress.setText(progress + "%");
    }

    private void displayRenderedContent(Object content) {
        ivImage.setVisibility(View.GONE);
        tvText.setVisibility(View.GONE);
        wvHtml.setVisibility(View.GONE);
        hideVideoView();

        if (content instanceof Bitmap) {
            ivImage.setImageBitmap((Bitmap) content);
            ivImage.setVisibility(View.VISIBLE);
        } else if (content instanceof String) {
            String contentStr = (String) content;
            if (contentStr.startsWith("<html") || contentStr.contains("<html") || contentStr.contains("<body") || contentStr.contains("<div")) {
                Log.d(TAG, "Loading HTML content, length: " + contentStr.length());
                wvHtml.loadDataWithBaseURL("file:///android_asset/", contentStr, "text/html", "UTF-8", null);
                wvHtml.setVisibility(View.VISIBLE);
            } else {
                tvText.setText(contentStr);
                tvText.setVisibility(View.VISIBLE);
            }
        } else if (content instanceof Map) {
            Map<?, ?> contentMap = (Map<?, ?>) content;
            if (contentMap.containsKey("videoPath")) {
                showVideo(String.valueOf(contentMap.get("videoPath")));
            } else if (contentMap.containsKey("bitmap")) {
                ivImage.setImageBitmap((Bitmap) contentMap.get("bitmap"));
                ivImage.setVisibility(View.VISIBLE);
            } else if (contentMap.containsKey("htmlContent")) {
                String htmlContent = String.valueOf(contentMap.get("htmlContent"));
                Log.d(TAG, "Loading HTML content from map, length: " + htmlContent.length());
                wvHtml.loadDataWithBaseURL("file:///android_asset/", htmlContent, "text/html", "UTF-8", null);
                wvHtml.setVisibility(View.VISIBLE);
            } else if (contentMap.containsKey("content")) {
                tvText.setText(String.valueOf(contentMap.get("content")));
                tvText.setVisibility(View.VISIBLE);
            } else if (contentMap.containsKey("textContent")) {
                tvText.setText(String.valueOf(contentMap.get("textContent")));
                tvText.setVisibility(View.VISIBLE);
            }
        }
    }

    /** 隐藏/释放已展示的视频（切换渲染内容或退出时调用） */
    private void hideVideoView() {
        if (videoView != null) {
            try {
                videoView.stopPlayback();
            } catch (Throwable ignored) {
            }
            contentLayout.removeView(videoView);
            videoView = null;
        }
    }

    /** 应用内视频播放（VideoView，不依赖系统播放器）；加载失败提示可用"用其他应用打开" */
    private void showVideo(String videoPath) {
        final java.io.File vf = new java.io.File(videoPath);
        if (!vf.exists()) {
            showError("视频不存在", "找不到视频文件: " + videoPath);
            return;
        }
        final android.widget.VideoView vv = new android.widget.VideoView(this);
        videoView = vv;
        // 插入到文件名下方的正文区（weight=1 撑满剩余空间，与图片/文本一致）
        contentLayout.addView(vv, 1, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        vv.setOnPreparedListener(mp -> {
            try {
                mp.setLooping(false);
                mp.setOnVideoSizeChangedListener((m, w, h) -> {
                    if (w > 0 && h > 0) {
                        vv.post(() -> {
                            int width = vv.getWidth();
                            if (width > 0) {
                                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT,
                                        (int) (width * h / (float) w));
                                vv.setLayoutParams(lp);
                            }
                        });
                    }
                });
                vv.start();
            } catch (Throwable ignored) {
            }
        });
        vv.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "视频播放失败，可点「用其他应用打开」", Toast.LENGTH_LONG).show();
            return true;
        });
        try {
            vv.setVideoURI(Uri.fromFile(vf));
            vv.requestFocus();
        } catch (Throwable t) {
            Toast.makeText(this, "视频打开失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        hideVideoView();
        super.onDestroy();
    }

    private void showError(String title, String message) {
        loadingLayout.setVisibility(View.GONE);
        contentLayout.setVisibility(View.VISIBLE);
        errorLayout.setVisibility(View.VISIBLE);
        tvErrorTitle.setText(title);
        tvErrorMessage.setText(message);
    }

    private void shareFile() {
        if (currentFile != null && currentFile.exists()) {
            try {
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", currentFile);
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("*/*");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(shareIntent, "分享文件"));
            } catch (Exception e) {
                Log.e(TAG, "Error sharing file: " + e.getMessage(), e);
                Toast.makeText(this, "分享失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openWithOtherApp(File file) {
        if (file != null && file.exists()) {
            try {
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, getMimeType(file.getName()));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, "选择应用打开"));
            } catch (Exception e) {
                Log.e(TAG, "Error opening file with other app: " + e.getMessage(), e);
                Toast.makeText(this, "打开失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 是否为 Office 文档（Word/Excel/PPT） */
    private boolean isOfficeDocument(File file) {
        String n = file.getName().toLowerCase();
        return n.endsWith(".doc") || n.endsWith(".docx") || n.endsWith(".xls") ||
                n.endsWith(".xlsx") || n.endsWith(".ppt") || n.endsWith(".pptx");
    }

    /** 获取文件 MIME 类型 */
    private String getMimeType(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        switch (ext) {
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt": return "application/vnd.ms-powerpoint";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "pdf": return "application/pdf";
            default: return "*/*";
        }
    }

    /** 是否存在能打开该文件的外部应用（如 WPS） */
    private boolean hasExternalViewer(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, getMimeType(file.getName()));
            return getPackageManager().queryIntentActivities(intent, 0) != null &&
                    getPackageManager().queryIntentActivities(intent, 0).size() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private File copyFileFromUri(Uri uri) throws IOException {
        File appDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "preview");
        if (!appDir.exists()) {
            appDir.mkdirs();
        }

        String originalFileName = uri.getLastPathSegment();
        String extension = "";
        if (originalFileName != null && originalFileName.contains(".")) {
            extension = originalFileName.substring(originalFileName.lastIndexOf("."));
        }
        String fileName = "preview_" + System.currentTimeMillis() + extension;
        File file = new File(appDir, fileName);

        java.io.InputStream inputStream = getContentResolver().openInputStream(uri);
        java.io.FileOutputStream outputStream = new java.io.FileOutputStream(file);

        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) > 0) {
            outputStream.write(buffer, 0, length);
        }

        inputStream.close();
        outputStream.close();

        return file;
    }

    private void showImportFileDialog() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("导入文件")
            .setMessage("没有文件可供渲染，请选择一个文件导入")
            .setPositiveButton("选择文件", (dialog, which) -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.setType("*/*");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, 1001);
            })
            .setNegativeButton("取消", (dialog, which) -> {
                finish();
            })
            .setCancelable(false)
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            try {
                currentFile = copyFileFromUri(uri);
                if (currentFile != null) {
                    renderFile(currentFile);
                } else {
                    showError("文件处理失败", "无法复制文件");
                }
            } catch (IOException e) {
                Log.e(TAG, "Error copying file from URI: " + e.getMessage(), e);
                showError("文件处理失败", "无法复制文件: " + e.getMessage());
            }
        }
    }
}