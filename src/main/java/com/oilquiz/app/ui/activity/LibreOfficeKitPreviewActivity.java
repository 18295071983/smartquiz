package com.oilquiz.app.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.util.preview.LibreOfficeKitPreviewManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * LibreOfficeKit 文档预览 Activity
 * 使用 LibreOffice 开源库渲染 Office 文档
 */
public class LibreOfficeKitPreviewActivity extends com.oilquiz.app.ui.base.BaseActivity {
    private static final String TAG = "LibreOfficeKitPreviewActivity";
    private static final String EXTRA_FILE_PATH = "file_path";
    private static final int REQUEST_CODE_FILE_PICKER = 1001;
    
    private String filePath;
    private LibreOfficeKitPreviewManager loKitManager;
    private FrameLayout container;
    private TextView tvPageInfo;
    private Button btnPrev;
    private Button btnNext;
    private ScrollView scrollView;
    private LinearLayout pageContainer;
    
    private int currentPage = 0;
    private int totalPages = 0;
    private List<Bitmap> pageBitmaps = new ArrayList<>();
    
    public static void start(Context context, String filePath) {
        Intent intent = new Intent(context, LibreOfficeKitPreviewActivity.class);
        intent.putExtra(EXTRA_FILE_PATH, filePath);
        context.startActivity(intent);
    }
    
    @Override
    protected int getLayoutId() {
        return 0; // 使用动态布局
    }
    
    @Override
    protected void initView() {
        // 创建根布局
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.setBackgroundColor(0xFF333333);
        
        // 标题栏
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setBackgroundColor(0xFF3B82F6);
        titleBar.setPadding(20, 20, 20, 20);
        
        Button btnBack = new Button(this);
        btnBack.setText("返回");
        btnBack.setOnClickListener(v -> finish());
        
        TextView tvTitle = new TextView(this);
        tvTitle.setText("LibreOffice 文档预览");
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(18);
        tvTitle.setPadding(20, 0, 20, 0);
        
        tvPageInfo = new TextView(this);
        tvPageInfo.setTextColor(0xFFFFFFFF);
        tvPageInfo.setTextSize(14);
        
        titleBar.addView(btnBack);
        titleBar.addView(tvTitle);
        titleBar.addView(tvPageInfo);
        
        // 页面控制按钮
        LinearLayout controlBar = new LinearLayout(this);
        controlBar.setOrientation(LinearLayout.HORIZONTAL);
        controlBar.setBackgroundColor(0xFF444444);
        controlBar.setPadding(10, 10, 10, 10);
        
        btnPrev = new Button(this);
        btnPrev.setText("上一页");
        btnPrev.setOnClickListener(v -> showPage(currentPage - 1));
        
        btnNext = new Button(this);
        btnNext.setText("下一页");
        btnNext.setOnClickListener(v -> showPage(currentPage + 1));
        
        controlBar.addView(btnPrev);
        controlBar.addView(btnNext);
        
        // 页面容器
        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1.0f));
        
        pageContainer = new LinearLayout(this);
        pageContainer.setOrientation(LinearLayout.VERTICAL);
        pageContainer.setBackgroundColor(0xFF666666);
        pageContainer.setPadding(20, 20, 20, 20);
        
        scrollView.addView(pageContainer);
        
        // 组装布局
        rootLayout.addView(titleBar);
        rootLayout.addView(controlBar);
        rootLayout.addView(scrollView);
        
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
        
        loKitManager = LibreOfficeKitPreviewManager.getInstance(this);
        
        // 初始化 LibreOfficeKit
        if (!loKitManager.isInitialized()) {
            boolean initialized = loKitManager.initialize();
            if (!initialized) {
                AppLogger.e(TAG, "LibreOfficeKit 初始化失败");
                showErrorAndFinish("LibreOfficeKit 初始化失败，请检查库是否正确集成");
                return;
            }
        }
        
        if (loKitManager.openDocument(filePath)) {
            totalPages = loKitManager.getPageCount();
            AppLogger.d(TAG, "文档打开成功，总页数: " + totalPages);
            
            // 渲染所有页面
            renderAllPages();
            showAllPages();
        } else {
            AppLogger.e(TAG, "打开文档失败");
            showErrorAndFinish("打开文档失败");
        }
    }
    
    @Override
    protected void initListener() {
        // 监听器已在 initView 中设置
        
        // 添加滚动监听，当用户滚动时更新当前页码
        scrollView.getViewTreeObserver().addOnScrollChangedListener(() -> {
            updateCurrentPageFromScroll();
        });
    }
    
    /**
     * 根据滚动位置更新当前页码
     */
    private void updateCurrentPageFromScroll() {
        int scrollY = scrollView.getScrollY();
        int currentPageIndex = 0;
        
        // 计算当前滚动位置对应的页面
        int accumulatedHeight = 0;
        for (int i = 0; i < pageContainer.getChildCount(); i++) {
            View child = pageContainer.getChildAt(i);
            accumulatedHeight += child.getHeight() + 20; // 20是页边距
            if (scrollY < accumulatedHeight) {
                currentPageIndex = i;
                break;
            }
        }
        
        // 如果页码发生变化，更新currentPage并刷新页面信息
        if (currentPageIndex != currentPage) {
            currentPage = currentPageIndex;
            // 更新页面信息
            tvPageInfo.setText(String.format("%d / %d", currentPage + 1, totalPages));
            
            // 更新按钮状态
            btnPrev.setEnabled(currentPage > 0);
            btnNext.setEnabled(currentPage < totalPages - 1);
        }
    }
    
    private void renderAllPages() {
        // 获取屏幕宽度
        int screenWidth = getResources().getDisplayMetrics().widthPixels - 80; // 减去边距
        
        for (int i = 0; i < totalPages; i++) {
            Bitmap bitmap = loKitManager.renderPage(i, screenWidth, screenWidth * 2);
            if (bitmap != null) {
                pageBitmaps.add(bitmap);
            }
        }
    }
    
    private void showAllPages() {
        // 更新页面信息
        tvPageInfo.setText(String.format("%d / %d", currentPage + 1, totalPages));
        
        // 清空容器
        pageContainer.removeAllViews();
        
        // 显示所有页面
        for (int i = 0; i < pageBitmaps.size(); i++) {
            ImageView imageView = new ImageView(this);
            imageView.setImageBitmap(pageBitmaps.get(i));
            imageView.setAdjustViewBounds(true);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setBackgroundColor(0xFFFFFFFF);
            
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = 20;
            imageView.setLayoutParams(params);
            
            pageContainer.addView(imageView);
        }
    }
    
    private void showPage(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= totalPages) {
            return;
        }
        
        currentPage = pageIndex;
        
        // 更新页面信息
        tvPageInfo.setText(String.format("%d / %d", currentPage + 1, totalPages));
        
        // 更新按钮状态
        btnPrev.setEnabled(currentPage > 0);
        btnNext.setEnabled(currentPage < totalPages - 1);
        
        // 滚动到指定页面
        scrollToPage(currentPage);
    }
    
    private void scrollToPage(int pageIndex) {
        if (pageIndex < 0 || pageIndex >= pageBitmaps.size()) {
            return;
        }
        
        // 计算滚动位置
        final int scrollY = calculateScrollY(pageIndex);
        
        // 滚动到指定位置
        scrollView.post(() -> scrollView.scrollTo(0, scrollY));
    }
    
    private int calculateScrollY(int pageIndex) {
        int scrollY = 0;
        for (int i = 0; i < pageIndex; i++) {
            if (i < pageContainer.getChildCount()) {
                View child = pageContainer.getChildAt(i);
                scrollY += child.getHeight() + 20; // 20是页边距
            }
        }
        return scrollY;
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // 释放 Bitmap 资源
        for (Bitmap bitmap : pageBitmaps) {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        pageBitmaps.clear();
        
        // 释放 LibreOfficeKit 资源
        if (loKitManager != null) {
            loKitManager.release();
        }
    }
    
    /**
     * 显示错误并关闭
     */
    private void showErrorAndFinish(String error) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("LibreOffice 预览失败")
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
        
        // 跳转到 TBS 文件预览
        com.oilquiz.app.ui.activity.TBSFilePreviewActivity.start(this, filePath);
        finish();
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
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
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
                        Intent intent = new Intent(this, LibreOfficeKitPreviewActivity.class);
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
        String extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extension == null) {
            extension = "tmp";
        }
        
        // 创建临时文件
        File tempFile = File.createTempFile("lo_kit_", "." + extension, getExternalFilesDir(null));
        tempFile.deleteOnExit();
        
        // 复制文件内容
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
