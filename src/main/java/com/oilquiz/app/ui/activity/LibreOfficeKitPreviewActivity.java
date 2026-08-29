package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import com.github.chrisbanes.photoview.PhotoView;

import com.oilquiz.app.R;
import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.util.preview.LibreOfficeKitPreviewManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * LibreOfficeKit 文档预览 Activity（重新设计壳）
 * 使用 XML 布局：主题适配工具栏 + 单页大图(可双指缩放) + 上一页/下一页 + 页码 + 加载/错误态。
 * 渲染仍基于 LibreOfficeKit（Office→页面 Bitmap）。
 */
public class LibreOfficeKitPreviewActivity extends com.oilquiz.app.ui.base.BaseActivity {
    private static final String TAG = "LibreOfficeKitPreviewActivity";
    private static final String EXTRA_FILE_PATH = "file_path";
    private static final int REQUEST_CODE_FILE_PICKER = 1001;

    private String filePath;
    private LibreOfficeKitPreviewManager loKitManager;

    private PhotoView ivPage;
    private MaterialButton btnPrev;
    private MaterialButton btnNext;
    private TextView tvPageInfo;
    private TextView tvZoom;
    private View loadingLayout;
    private View errorLayout;
    private TextView tvErrorTitle;
    private TextView tvErrorMessage;
    private MaterialButton btnRetry;
    private MaterialButton btnOpenWith;

    private int currentPage = 0;
    private int totalPages = 0;
    private final List<Bitmap> pageBitmaps = new ArrayList<>();

    private float fitScale = 1f; // 适配视口时的基准缩放
    private boolean rendered = false;

    public static void start(android.content.Context context, String filePath) {
        Intent intent = new Intent(context, LibreOfficeKitPreviewActivity.class);
        intent.putExtra(EXTRA_FILE_PATH, filePath);
        context.startActivity(intent);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_libreoffice_kit_preview;
    }

    @Override
    protected void initView() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setupToolbar("文档预览");

        ivPage = findViewById(R.id.iv_page);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);
        tvPageInfo = findViewById(R.id.tv_page_info);
        tvZoom = findViewById(R.id.tv_zoom);
        loadingLayout = findViewById(R.id.loading_layout);
        errorLayout = findViewById(R.id.error_layout);
        tvErrorTitle = findViewById(R.id.tv_error_title);
        tvErrorMessage = findViewById(R.id.tv_error_message);
        btnRetry = findViewById(R.id.btn_retry);
        btnOpenWith = findViewById(R.id.btn_open_with);

        setupZoomListener();
        showLoading();
    }

    /** 绑定缩放回调，实时显示缩放百分比指示器。 */
    private void setupZoomListener() {
        ivPage.setZoomable(true);
        ivPage.setMaximumScale(8f);
        ivPage.setOnScaleChangeListener((scaleFactor, focusX, focusY) ->
                updateZoomIndicator());
    }

    /** 更新缩放百分比指示器(相对适配基准)。 */
    private void updateZoomIndicator() {
        if (tvZoom == null) return;
        float relative = (fitScale > 0f) ? (ivPage.getScale() / fitScale) : 1f;
        tvZoom.setText(String.format("%d%%", Math.round(relative * 100)));
        tvZoom.setVisibility(View.VISIBLE);
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

        loKitManager = LibreOfficeKitPreviewManager.getInstance(this);

        // 初始化 LibreOfficeKit（放到后台线程，避免阻塞 UI）
        new Thread(() -> {
            if (!loKitManager.isInitialized()) {
                boolean initialized = loKitManager.initialize(this);
                if (!initialized) {
                    runOnUiThread(() -> {
                        AppLogger.e(TAG, "LibreOfficeKit 初始化失败");
                        showError("LibreOfficeKit 初始化失败，请检查库是否正确集成");
                    });
                    return;
                }
            }
            if (loKitManager.openDocument(filePath)) {
                totalPages = loKitManager.getPageCount();
                AppLogger.d(TAG, "文档打开成功，总页数: " + totalPages);
                rendered = true;
                runOnUiThread(() -> {
                    renderAllPages();
                    hideLoading();   // 先恢复图片区域尺寸(GONE 时宽高为 0)
                    showPage(0);     // 再显示第 0 页
                });
            } else {
                runOnUiThread(() -> {
                    AppLogger.e(TAG, "打开文档失败");
                    showError("打开文档失败，文件可能已损坏或格式不支持");
                });
            }
        }, "lokit-init").start();
    }

    @Override
    protected void initListener() {
        btnPrev.setOnClickListener(v -> showPage(currentPage - 1));
        btnNext.setOnClickListener(v -> showPage(currentPage + 1));
        btnRetry.setOnClickListener(v -> {
            rendered = false;
            showLoading();
            initData();
        });
        btnOpenWith.setOnClickListener(v -> useAlternativePreview());
    }

    private void renderAllPages() {
        pageBitmaps.clear();
        int width = getResources().getDisplayMetrics().widthPixels - 120;
        if (width <= 0) width = 1024;
        for (int i = 0; i < totalPages; i++) {
            Bitmap bitmap = loKitManager.renderPage(i, width, (int) (width * 1.5));
            if (bitmap != null) {
                pageBitmaps.add(bitmap);
            }
        }
        if (totalPages <= 0) {
            totalPages = Math.max(pageBitmaps.size(), 1);
        }
    }

    private void showPage(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pageBitmaps.size()) {
            return;
        }
        currentPage = pageIndex;
        // 关键：必须在视图完成 layout(拿到真实宽高)之后再 setImageBitmap，
        // 并直接用位图和视图尺寸计算 fit 比例，强制回到完整适配一屏。
        ivPage.post(() -> {
            Bitmap bmp = pageBitmaps.get(pageIndex);
            if (bmp == null) {
                return;
            }
            ivPage.setImageBitmap(bmp);
            int vw = ivPage.getWidth();
            int vh = ivPage.getHeight();
            if (vw > 0 && vh > 0) {
                fitScale = Math.min((float) vw / bmp.getWidth(), (float) vh / bmp.getHeight());
            } else {
                fitScale = ivPage.getScale();
            }
            if (fitScale > 0f) {
                ivPage.setMinimumScale(fitScale);
                ivPage.setMaximumScale(fitScale * 6f);
                // 强制回到完整适配(100%)，避免被基准矩阵算错导致初始即放大裁剪
                ivPage.setScale(fitScale, false);
            }
            updateZoomIndicator();
            android.util.Log.i(TAG, "page=" + bmp.getWidth() + "x" + bmp.getHeight()
                    + " view=" + vw + "x" + vh + " fitScale=" + fitScale
                    + " curScale=" + ivPage.getScale());
        });
        tvPageInfo.setText(String.format("%d / %d", currentPage + 1, totalPages));
        btnPrev.setEnabled(currentPage > 0);
        btnNext.setEnabled(currentPage < totalPages - 1);
    }

    private void showLoading() {
        loadingLayout.setVisibility(View.VISIBLE);
        ivPage.setVisibility(View.GONE);
        errorLayout.setVisibility(View.GONE);
        hidePageControls();
    }

    private void hideLoading() {
        loadingLayout.setVisibility(View.GONE);
        ivPage.setVisibility(View.VISIBLE);
        errorLayout.setVisibility(View.GONE);
        showPageControls();
    }

    private void hidePageControls() {
        btnPrev.setVisibility(View.GONE);
        btnNext.setVisibility(View.GONE);
        tvPageInfo.setVisibility(View.GONE);
    }

    private void showPageControls() {
        btnPrev.setVisibility(View.VISIBLE);
        btnNext.setVisibility(View.VISIBLE);
        tvPageInfo.setVisibility(View.VISIBLE);
    }

    private void showError(String message) {
        loadingLayout.setVisibility(View.GONE);
        ivPage.setVisibility(View.GONE);
        errorLayout.setVisibility(View.VISIBLE);
        tvErrorMessage.setText(message);
        hidePageControls();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (Bitmap bitmap : pageBitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        pageBitmaps.clear();
        if (loKitManager != null) {
            loKitManager.release();
        }
    }

    private void useAlternativePreview() {
        AppLogger.d(TAG, "使用备用预览方式");
        // 无 TBS 时回退到系统打开
        try {
            File file = new File(filePath);
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "用其他应用打开"));
        } catch (Exception e) {
            AppLogger.e(TAG, "用其他应用打开失败: " + e.getMessage(), e);
        }
        finish();
    }

    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择要预览的文件"), REQUEST_CODE_FILE_PICKER);
        } catch (android.content.ActivityNotFoundException ex) {
            AppLogger.e(TAG, "没有找到文件选择器应用", ex);
            finish();
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
                        start(LibreOfficeKitPreviewActivity.this, path);
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
                finish();
            }
        } else if (requestCode == REQUEST_CODE_FILE_PICKER) {
            AppLogger.i(TAG, "用户取消了文件选择");
            finish();
        }
    }

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

    private File createTempFileFromUri(Uri uri) throws IOException {
        String mimeType = getContentResolver().getType(uri);
        String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extension == null) {
            extension = "tmp";
        }
        File tempFile = File.createTempFile("lo_kit_", "." + extension, getExternalFilesDir(null));
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
