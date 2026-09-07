package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.oilquiz.app.R;
import com.oilquiz.app.infra.AppLogger;
import com.oilquiz.app.util.render.ExcelUtil;
import com.oilquiz.app.util.render.ExcelRenderer;

import java.io.File;
import java.util.List;
import java.util.Map;
import com.oilquiz.app.ui.adapter.FilePreviewAdapter;

public class FilePreviewActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_SHEET_INDEX = "sheet_index";
    public static final String EXTRA_FIELD_MAPPING = "field_mapping";
    public static final String EXTRA_RESULT_FIELD_MAPPING = "result_field_mapping";
    private static final int REQUEST_CODE_FILE_PICKER = 1001;

    private File file;
    private int sheetIndex;
    private Map<String, Integer> fieldMapping;
    private List<String> columnHeaders;
    private List<List<String>> dataRows;

    private ProgressBar progressBar;
    private TextView statusText;
    private RecyclerView previewRecyclerView;
    private Button btnNext;
    private Button btnCancel;

    private FilePreviewAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_preview);

        initViews();
        loadData();
        setupListeners();
        loadFilePreview();
    }

    private void initViews() {
        progressBar = findViewById(R.id.progress_bar);
        statusText = findViewById(R.id.progress_text);
        previewRecyclerView = findViewById(R.id.previewRecyclerView);
        btnNext = findViewById(R.id.btnNext);
        btnCancel = findViewById(R.id.btnCancel);

        previewRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    }

    private void loadData() {
        Intent intent = getIntent();
        if (intent != null) {
            String filePath = intent.getStringExtra(EXTRA_FILE_PATH);
            if (filePath != null) {
                file = new File(filePath);
            }
            sheetIndex = intent.getIntExtra(EXTRA_SHEET_INDEX, 0);
            fieldMapping = (Map<String, Integer>) intent.getSerializableExtra(EXTRA_FIELD_MAPPING);
        }
        
        // 如果文件路径为空，启动文件选择器
        if (file == null) {
            AppLogger.i("FilePreviewActivity", "文件路径为空，启动文件选择器");
            launchFilePicker();
        }
    }

    private void setupListeners() {
        btnNext.setOnClickListener(v -> {
            if (adapter != null && adapter.getFieldMapping() != null) {
                Intent resultIntent = new Intent();
                resultIntent.putExtra(EXTRA_RESULT_FIELD_MAPPING, (java.io.Serializable) adapter.getFieldMapping());
                setResult(RESULT_OK, resultIntent);
                finish();
            } else {
                Toast.makeText(this, getString(R.string.h_921ef2ea), Toast.LENGTH_SHORT).show();
            }
        });

        btnCancel.setOnClickListener(v -> finish());
    }

    private void loadFilePreview() {
        LinearLayout progressContainer = findViewById(R.id.progress_container);
        LinearLayout contentContainer = findViewById(R.id.content_container);
        
        if (progressContainer != null) {
            progressContainer.setVisibility(View.VISIBLE);
        }
        if (contentContainer != null) {
            contentContainer.setVisibility(View.GONE);
        }
        if (statusText != null) {
            statusText.setText(getString(R.string.h_447dda29));
        }

        // 检查file对象是否为null
        if (file == null) {
            runOnUiThread(() -> {
                if (progressContainer != null) {
                    progressContainer.setVisibility(View.GONE);
                }
                if (contentContainer != null) {
                    contentContainer.setVisibility(View.VISIBLE);
                }
                if (statusText != null) {
                    statusText.setText(getString(R.string.h_2201ac77));
                    statusText.setVisibility(View.VISIBLE);
                }
                Toast.makeText(FilePreviewActivity.this, getString(R.string.h_8da0929d), Toast.LENGTH_SHORT).show();
            });
            return;
        }

        // 检查文件是否存在
        if (!file.exists()) {
            runOnUiThread(() -> {
                if (progressContainer != null) {
                    progressContainer.setVisibility(View.GONE);
                }
                if (contentContainer != null) {
                    contentContainer.setVisibility(View.VISIBLE);
                }
                if (statusText != null) {
                    statusText.setText(getString(R.string.h_d9523e34));
                    statusText.setVisibility(View.VISIBLE);
                }
                Toast.makeText(FilePreviewActivity.this, getString(R.string.h_48319955), Toast.LENGTH_SHORT).show();
            });
            return;
        }

        // 使用ExcelRenderer渲染全部数据
        ExcelRenderer.renderExcel(file, sheetIndex, new ExcelRenderer.RenderCallback() {
            @Override
            public void onRenderStart() {
                runOnUiThread(() -> {
                    if (statusText != null) {
                        statusText.setText(getString(R.string.h_4ac73467));
                    }
                });
            }

            @Override
            public void onRenderProgress(int current, int total) {
                runOnUiThread(() -> {
                    if (statusText != null) {
                        statusText.setText(getString(R.string.h_651e91ce) + current + "/" + total + " 行");
                    }
                    if (progressBar != null && total > 0) {
                        progressBar.setProgress((int) ((float) current / total * 100));
                    }
                });
            }

            @Override
            public void onRenderComplete(List<List<String>> data, List<String> headers) {
                runOnUiThread(() -> {
                    if (progressContainer != null) {
                        progressContainer.setVisibility(View.GONE);
                    }
                    if (contentContainer != null) {
                        contentContainer.setVisibility(View.VISIBLE);
                    }
                    
                    if (headers != null && !headers.isEmpty() && data != null && !data.isEmpty()) {
                        columnHeaders = headers;
                        dataRows = data;
                        
                        if (previewRecyclerView != null) {
                            adapter = new FilePreviewAdapter(FilePreviewActivity.this, columnHeaders, dataRows, fieldMapping);
                            previewRecyclerView.setAdapter(adapter);
                            previewRecyclerView.setVisibility(View.VISIBLE);
                        }
                        
                        // 显示成功提示
                        Toast.makeText(FilePreviewActivity.this, getString(R.string.h_4be7d9d7) + dataRows.size() + getString(R.string.h_f3d257a2), Toast.LENGTH_SHORT).show();
                    } else {
                        if (statusText != null) {
                            statusText.setText(getString(R.string.h_3fead349));
                            statusText.setVisibility(View.VISIBLE);
                        }
                        Toast.makeText(FilePreviewActivity.this, getString(R.string.h_70238c3e), Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onRenderError(String message) {
                runOnUiThread(() -> {
                    if (progressContainer != null) {
                        progressContainer.setVisibility(View.GONE);
                    }
                    if (contentContainer != null) {
                        contentContainer.setVisibility(View.VISIBLE);
                    }
                    if (statusText != null) {
                        statusText.setText(getString(R.string.h_4bbdceb5) + message);
                        statusText.setVisibility(View.VISIBLE);
                    }
                    Toast.makeText(FilePreviewActivity.this, getString(R.string.h_04a40cad) + message, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    /**
     * 启动文件选择器
     */
    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        
        // 支持的文件类型
        String[] mimeTypes = {
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv",
            "application/json",
            "text/plain"
        };
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        
        try {
            startActivityForResult(Intent.createChooser(intent, "选择要预览的文件"), REQUEST_CODE_FILE_PICKER);
        } catch (android.content.ActivityNotFoundException ex) {
            AppLogger.e("FilePreviewActivity", "没有找到文件选择器应用", ex);
            new android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.h_7030ff64))
                    .setMessage(getString(R.string.h_b5ea0a10))
                    .setPositiveButton(getString(R.string.h_38cf16f2), (dialog, which) -> finish())
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
                    // 预览引擎只能加载本地路径，SAF 返回的 content:// Uri 直接用流复制到应用缓存目录
                    // （Android 10+ 分区存储下无真实路径，不查已废弃的 MediaStore DATA 列）
                    File cached = com.oilquiz.app.util.UriPathResolver.copyContentUriToCache(this, uri.toString());
                    if (cached != null && cached.exists()) {
                        AppLogger.i("FilePreviewActivity", "SAF 文件已复制到缓存: " + cached.getAbsolutePath());
                        // 重新启动预览
                        Intent intent = new Intent(this, FilePreviewActivity.class);
                        intent.putExtra(EXTRA_FILE_PATH, cached.getAbsolutePath());
                        startActivity(intent);
                        finish();
                    } else {
                        AppLogger.e("FilePreviewActivity", "SAF 文件复制失败: " + uri);
                        Toast.makeText(this, getString(R.string.h_c15415d3), Toast.LENGTH_SHORT).show();
                        finish();
                    }
                } catch (Exception e) {
                    AppLogger.e("FilePreviewActivity", "处理文件选择结果失败", e);
                    Toast.makeText(this, getString(R.string.h_4fa68124) + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                }
            } else {
                AppLogger.e("FilePreviewActivity", "文件选择返回空 Uri");
                Toast.makeText(this, getString(R.string.h_39bd3ae1), Toast.LENGTH_SHORT).show();
                finish();
            }
        } else if (requestCode == REQUEST_CODE_FILE_PICKER) {
            // 用户取消了文件选择
            AppLogger.i("FilePreviewActivity", "用户取消了文件选择");
            finish();
        }
    }
}
