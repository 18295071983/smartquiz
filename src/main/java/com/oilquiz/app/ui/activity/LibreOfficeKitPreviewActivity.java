package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import java.util.HashMap;
import java.util.Map;

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
    private MaterialToolbar toolbar;
    private ScrollView svContinuous;
    private LinearLayout stripsContainer;
    private TextView tvPartSelector;
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
    // 懒渲染：只缓存当前页及前后邻居页，按需渲染，避免整份文档全部渲染占用内存
    private final Map<Integer, Bitmap> pageCache = new HashMap<>();
    private int renderWidth = 0;
    private int renderHeight = 0;
    private boolean isRenderingPage = false;
    private int renderingIndex = -1;

    // 文档类型 / 模式
    private static final int DOCTYPE_TEXT = 0;       // Writer(Word)
    private static final int DOCTYPE_SPREADSHEET = 1; // Calc(Excel)
    private static final int DOCTYPE_PRESENTATION = 2;// Impress(PPT)
    private int docType = -1;
    private boolean isContinuousMode = false; // Writer 连续滚动
    private boolean isSheetMode = false;      // Calc 工作表
    private String[] partNames;               // 工作表/页/幻灯片名
    private int partCount = 0;

    private float fitScale = 1f; // 适配视口时的基准缩放
    private boolean rendered = false;
    private boolean notesMode = false; // PPT 备注/幻灯片

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
        toolbar = findViewById(R.id.toolbar);
        setupToolbar("文档预览");

        ivPage = findViewById(R.id.iv_page);
        svContinuous = findViewById(R.id.sv_continuous);
        stripsContainer = findViewById(R.id.strips_container);
        tvPartSelector = findViewById(R.id.tv_part_selector);
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
                docType = loKitManager.getDocumentType();
                partCount = loKitManager.getPageCount();
                totalPages = partCount;
                AppLogger.d(TAG, "文档打开成功，类型=" + docType + "，part数=" + partCount);
                // 用上 LibreOfficeKit 的缩放与状态回调：100% 缩放、注册消息回调（进度/失效/光标）
                loKitManager.setClientZoom(100, 100, 0, 0);
                loKitManager.setMessageCallback();
                rendered = true;
                // 按文档类型进入对应模式（Word 连续滚动 / Excel 工作表 / PPT 分页）
                runOnUiThread(this::setupDocMode);
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

    /** 按文档类型进入对应渲染模式：Word→连续滚动，Excel→工作表，PPT/其他→分页。 */
    private void setupDocMode() {
        if (toolbar != null && filePath != null) {
            toolbar.setSubtitle(new File(filePath).getName());
        }
        if (docType == DOCTYPE_TEXT) {
            // Writer(Word)：LibreOfficeKit 为连续文本视图，整份渲染成条带滚动，避免压缩
            isContinuousMode = true;
            isSheetMode = false;
            btnPrev.setVisibility(View.GONE);
            btnNext.setVisibility(View.GONE);
            tvPageInfo.setVisibility(View.GONE);
            tvPartSelector.setVisibility(View.GONE);
            // 用 getPartPageRectangles 获取 Word 连续文档的页码数（供分页参考/诊断）
            int pageCount = loKitManager.getPartPageCount();
            AppLogger.i(TAG, "Word 文档通过 getPartPageRectangles 得到页码数: " + pageCount);
            renderContinuous();
        } else if (docType == DOCTYPE_SPREADSHEET) {
            // Calc(Excel)：工作表导航
            isSheetMode = true;
            isContinuousMode = false;
            setupSheetNav();
        } else {
            // Impress/Drawing：分页
            isContinuousMode = false;
            isSheetMode = false;
            svContinuous.setVisibility(View.GONE);
            ivPage.setVisibility(View.VISIBLE);
            btnPrev.setVisibility(View.VISIBLE);
            btnNext.setVisibility(View.VISIBLE);
            tvPageInfo.setVisibility(View.VISIBLE);
            showPage(0);
        }
    }

    /** Word 连续滚动：后台渲染整份文档为等宽条带，放入 ScrollView 上下滚动。 */
    private void renderContinuous() {
        svContinuous.setVisibility(View.VISIBLE);
        ivPage.setVisibility(View.GONE);
        hidePageControls();
        new Thread(() -> {
            try {
                int docW = loKitManager.getDocumentWidth();
                int docH = loKitManager.getDocumentHeight();
                int screenW = getResources().getDisplayMetrics().widthPixels;
                int stripPx = Math.max(screenW, 900);       // 每条高度(px)
                int renderW = Math.max(screenW, 900);       // 渲染宽度(px)，1x 足够阅读
                float scale = (docW > 0) ? (float) renderW / docW : 1f;
                long totalHpx = (long) (docH * scale);
                if (totalHpx < 1) totalHpx = 1;
                // 内存约束：总像素不超约 30M（≈120MB），超出则按比例降分辨率
                long maxPixels = 30_000_000L;
                if ((long) renderW * totalHpx > maxPixels) {
                    float reduce = (float) maxPixels / ((long) renderW * totalHpx);
                    renderW = Math.max((int) (renderW * reduce), 500);
                    scale = (docW > 0) ? (float) renderW / docW : 1f;
                    totalHpx = (long) (docH * scale);
                    if (totalHpx < 1) totalHpx = 1;
                }
                float stripDocH = stripPx / scale;          // 每条对应的文档高度
                int count = Math.max((int) Math.ceil(totalHpx / (float) stripPx), 1);
                final int fw = renderW;
                final int fs = stripPx;
                final java.util.List<Bitmap> strips = new java.util.ArrayList<>();
                for (int i = 0; i < count; i++) {
                    int offY = (int) (i * stripDocH);
                    Bitmap bmp = loKitManager.renderRegion(fw, fs, 0, offY, docW, (int) Math.ceil(stripDocH));
                    if (bmp != null) {
                        strips.add(bmp);
                    }
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    stripsContainer.removeAllViews();
                    for (Bitmap b : strips) {
                        android.widget.ImageView iv = new android.widget.ImageView(this);
                        iv.setImageBitmap(b);
                        iv.setAdjustViewBounds(true);
                        iv.setLayoutParams(new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT));
                        stripsContainer.addView(iv);
                    }
                    hideLoading();
                });
            } catch (Throwable t) {
                AppLogger.e(TAG, "连续渲染错误", t);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        showError("渲染失败：" + t.getMessage());
                    }
                });
            }
        }, "lokit-continuous").start();
    }

    /** Excel 工作表导航：用 getPartName 列出工作表，顶部选择器切换。 */
    private void setupSheetNav() {
        svContinuous.setVisibility(View.GONE);
        ivPage.setVisibility(View.VISIBLE);
        btnPrev.setVisibility(View.GONE);
        btnNext.setVisibility(View.GONE);
        tvPageInfo.setVisibility(View.GONE);
        partNames = new String[partCount];
        for (int i = 0; i < partCount; i++) {
            String name = loKitManager.getPartName(i);
            partNames[i] = (name != null && !name.isEmpty()) ? name : ("工作表 " + (i + 1));
        }
        tvPartSelector.setText(partNames[0]);
        tvPartSelector.setVisibility(View.VISIBLE);
        tvPartSelector.setOnClickListener(v -> showPartPicker());
        showPage(0);
    }

    /** 弹出工作表（或页/幻灯片）选择列表。 */
    private void showPartPicker() {
        if (partNames == null || partNames.length == 0) {
            return;
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle(isSheetMode ? "选择工作表" : "选择页")
            .setItems(partNames, (dialog, which) -> showPage(which))
            .setNegativeButton("取消", null)
            .show();
    }

    /** PPT 备注/幻灯片 切换（用 setPartMode，PART_MODE_SLIDE=0/NOTES=1）。 */
    private void toggleNotesMode() {
        if (docType != DOCTYPE_PRESENTATION) {
            return;
        }
        notesMode = !notesMode;
        loKitManager.setPartMode(notesMode ? 1 : 0);
        partCount = loKitManager.getPageCount();
        totalPages = partCount;
        pageCache.clear();
        currentPage = 0;
        AppLogger.i(TAG, "切换到 " + (notesMode ? "备注" : "幻灯片") + "，part数=" + partCount);
        showPage(0);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_libreoffice_kit_preview, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem notes = menu.findItem(R.id.action_toggle_notes);
        if (notes != null) {
            notes.setVisible(docType == DOCTYPE_PRESENTATION);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_toggle_notes) {
            toggleNotesMode();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** 计算单页渲染尺寸：以屏幕宽度×2 渲染，放大后文字/图形仍清晰。 */
    private void computeRenderSize() {
        if (renderWidth > 0) {
            return;
        }
        int screenW = getResources().getDisplayMetrics().widthPixels;
        renderWidth = (int) (screenW * 2f);
        // 限制最大宽度，避免超大位图 OOM（当前页仅渲染 1 张 + 邻居 2 张）
        if (renderWidth > 2560) {
            renderWidth = 2560;
        }
        if (renderWidth <= 0) {
            renderWidth = screenW > 0 ? screenW : 1024;
        }
        renderHeight = (int) (renderWidth * 1.5f);
    }

    private void showPage(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= totalPages) {
            return;
        }
        currentPage = pageIndex;
        if (isSheetMode) {
            if (tvPartSelector != null) {
                tvPartSelector.setText(partNames != null && pageIndex < partNames.length
                        ? partNames[pageIndex] : ("工作表 " + (pageIndex + 1)));
            }
        } else {
            tvPageInfo.setText(String.format("%d / %d", currentPage + 1, totalPages));
        }
        btnPrev.setEnabled(currentPage > 0);
        btnNext.setEnabled(currentPage < totalPages - 1);
        ensurePageRendered(pageIndex);
    }

    /** 懒渲染：目标页已缓存则直接展示，否则在后台线程渲染（当前只渲染一页，内存省）。 */
    private void ensurePageRendered(int index) {
        if (pageCache.containsKey(index)) {
            displayPage(index);
            return;
        }
        if (isRenderingPage && renderingIndex == index) {
            return; // 正在渲染该页
        }
        computeRenderSize();
        isRenderingPage = true;
        renderingIndex = index;
        new Thread(() -> {
            Bitmap bmp = loKitManager.renderPage(index, renderWidth, renderHeight);
            runOnUiThread(() -> {
                isRenderingPage = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (bmp != null) {
                    pageCache.put(index, bmp);
                    trimCache();
                    if (currentPage == index) {
                        displayPage(index);
                    }
                } else if (currentPage == index) {
                    AppLogger.e(TAG, "渲染第 " + index + " 页失败");
                    showError("无法渲染第 " + (index + 1) + " 页");
                }
            });
        }, "lokit-page").start();
    }

    /** 只保留当前页及前后邻居页，其余位图回收，避免长文档占用过多内存。 */
    private void trimCache() {
        java.util.Iterator<Map.Entry<Integer, Bitmap>> it = pageCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Bitmap> e = it.next();
            int idx = e.getKey();
            if (Math.abs(idx - currentPage) > 1) {
                Bitmap bmp = e.getValue();
                if (bmp != null && !bmp.isRecycled()) {
                    bmp.recycle();
                }
                it.remove();
            }
        }
    }

    /** 展示某页：设置图片 + 计算适配比例 + 配置 PhotoView 缩放。 */
    private void displayPage(int index) {
        Bitmap bmp = pageCache.get(index);
        if (bmp == null) {
            return;
        }
        ivPage.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            ivPage.setImageBitmap(bmp);
            int vw = ivPage.getWidth();
            int vh = ivPage.getHeight();
            if (vw > 0 && vh > 0 && bmp.getWidth() > 0 && bmp.getHeight() > 0) {
                fitScale = Math.min((float) vw / bmp.getWidth(), (float) vh / bmp.getHeight());
            } else {
                fitScale = ivPage.getScale();
            }
            if (fitScale > 0f) {
                ivPage.setMinimumScale(fitScale);
                ivPage.setMaximumScale(fitScale * 6f);
                // 完整适配一屏(100%)
                ivPage.setScale(fitScale, false);
            }
            hideLoading();
            updateZoomIndicator();
        });
    }

    private void showLoading() {
        loadingLayout.setVisibility(View.VISIBLE);
        ivPage.setVisibility(View.GONE);
        errorLayout.setVisibility(View.GONE);
        hidePageControls();
    }

    private void hideLoading() {
        loadingLayout.setVisibility(View.GONE);
        errorLayout.setVisibility(View.GONE);
        if (isContinuousMode) {
            // 连续模式：用滚动条带，不恢复分页控件
            svContinuous.setVisibility(View.VISIBLE);
        } else {
            ivPage.setVisibility(View.VISIBLE);
            showPageControls();
        }
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
        // 回收所有懒渲染缓存页位图
        for (Bitmap bitmap : pageCache.values()) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        pageCache.clear();
        // 释放 LibreOfficeKit：销毁当前文档 + LO 运行时，释放后端占用的内存
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
