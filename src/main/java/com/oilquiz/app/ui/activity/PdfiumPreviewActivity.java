package com.oilquiz.app.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
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
import com.oilquiz.app.util.preview.PdfiumPreviewManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.oilquiz.app.R;
import com.oilquiz.app.theme.ThemeColors;
/**
 * Pdfium PDF 预览 Activity
 * 使用免费的 PdfiumAndroid 库渲染 PDF
 */
public class PdfiumPreviewActivity extends com.oilquiz.app.ui.base.BaseActivity {
    private static final String TAG = "PdfiumPreviewActivity";
    private static final String EXTRA_FILE_PATH = "file_path";
    /** SAF 返回的 content:// Uri（Android 10+ 无真实路径，直接用 Uri 打开） */
    private static final String EXTRA_FILE_URI = "file_uri";
    private static final int REQUEST_CODE_FILE_PICKER = 1001;
    
    private String filePath;
    private Uri fileUri;
    private PdfiumPreviewManager pdfManager;
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
        Intent intent = new Intent(context, PdfiumPreviewActivity.class);
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
        rootLayout.setBackgroundColor(ThemeColors.get(R.color.hc_ff333333));
        
        // 标题栏
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setBackgroundColor(ThemeColors.get(R.color.hc_ff3b82f6));
        titleBar.setPadding(20, 20, 20, 20);
        
        Button btnBack = new Button(this);
        btnBack.setText("返回");
        btnBack.setOnClickListener(v -> finish());
        
        TextView tvTitle = new TextView(this);
        tvTitle.setText("PDF 预览");
        tvTitle.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
        tvTitle.setTextSize(18);
        tvTitle.setPadding(20, 0, 20, 0);
        
        tvPageInfo = new TextView(this);
        tvPageInfo.setTextColor(ThemeColors.get(R.color.hc_ffffffff));
        tvPageInfo.setTextSize(14);
        
        titleBar.addView(btnBack);
        titleBar.addView(tvTitle);
        titleBar.addView(tvPageInfo);
        
        // 页面控制按钮
        LinearLayout controlBar = new LinearLayout(this);
        controlBar.setOrientation(LinearLayout.HORIZONTAL);
        controlBar.setBackgroundColor(ThemeColors.get(R.color.hc_ff444444));
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
        pageContainer.setBackgroundColor(ThemeColors.get(R.color.hc_ff666666));
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
        fileUri = getIntent().getParcelableExtra(EXTRA_FILE_URI);

        pdfManager = new PdfiumPreviewManager(this);

        // 优先用 SAF Uri（content://）直开，其次真实路径；都不可用才进文件选择器
        if (openFromIntent()) {
            totalPages = pdfManager.getPageCount();
            AppLogger.d(TAG, "PDF 打开成功，总页数: " + totalPages);
            // 渲染所有页面
            renderAllPages();
            showAllPages();
        } else {
            AppLogger.i(TAG, "无有效 PDF 来源，启动文件选择器");
            launchFilePicker();
        }
    }

    /** 按来源打开 PDF：优先 SAF Uri，其次真实路径；返回是否成功 */
    private boolean openFromIntent() {
        if (fileUri != null) {
            return pdfManager.openDocument(this, fileUri);
        }
        if (filePath != null && !filePath.isEmpty()) {
            File file = new File(filePath);
            if (file.exists()) {
                return pdfManager.openDocument(filePath);
            }
        }
        return false;
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
            Bitmap bitmap = pdfManager.renderPage(i, screenWidth, screenWidth * 2);
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
            imageView.setBackgroundColor(ThemeColors.get(R.color.hc_ffffffff));
            
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
        
        // 释放 Pdfium 资源
        if (pdfManager != null) {
            pdfManager.release();
        }
    }
    
    /**
     * 启动文件选择器
     */
    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/pdf");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        
        try {
            startActivityForResult(Intent.createChooser(intent, "选择要预览的PDF文件"), REQUEST_CODE_FILE_PICKER);
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
                // SAF（系统文件管理器）返回 content:// Uri，Android 10+ 下没有真实文件路径，
                // 直接把 Uri 传给 pdfium 用 ContentResolver.openFileDescriptor 打开，无需解析路径。
                AppLogger.i(TAG, "选择的文件 Uri: " + uri);
                Intent intent = new Intent(this, PdfiumPreviewActivity.class);
                intent.putExtra(EXTRA_FILE_URI, uri);
                startActivity(intent);
                finish();
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
}
