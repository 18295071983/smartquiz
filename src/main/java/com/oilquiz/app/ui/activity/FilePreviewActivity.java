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
import java.io.IOException;
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
                Toast.makeText(this, "请完成字段映射", Toast.LENGTH_SHORT).show();
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
            statusText.setText("正在加载文件内容...");
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
                    statusText.setText("文件路径为空");
                    statusText.setVisibility(View.VISIBLE);
                }
                Toast.makeText(FilePreviewActivity.this, "文件路径为空，无法加载文件", Toast.LENGTH_SHORT).show();
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
                    statusText.setText("文件不存在");
                    statusText.setVisibility(View.VISIBLE);
                }
                Toast.makeText(FilePreviewActivity.this, "文件不存在，无法加载", Toast.LENGTH_SHORT).show();
            });
            return;
        }

        // 使用ExcelRenderer渲染全部数据
        ExcelRenderer.renderExcel(file, sheetIndex, new ExcelRenderer.RenderCallback() {
            @Override
            public void onRenderStart() {
                runOnUiThread(() -> {
                    if (statusText != null) {
                        statusText.setText("开始渲染文件...");
                    }
                });
            }

            @Override
            public void onRenderProgress(int current, int total) {
                runOnUiThread(() -> {
                    if (statusText != null) {
                        statusText.setText("渲染中: " + current + "/" + total + " 行");
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
                        Toast.makeText(FilePreviewActivity.this, "文件加载成功，共" + dataRows.size() + "行数据", Toast.LENGTH_SHORT).show();
                    } else {
                        if (statusText != null) {
                            statusText.setText("文件内容为空或格式错误");
                            statusText.setVisibility(View.VISIBLE);
                        }
                        Toast.makeText(FilePreviewActivity.this, "无法读取文件内容", Toast.LENGTH_SHORT).show();
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
                        statusText.setText("加载失败: " + message);
                        statusText.setVisibility(View.VISIBLE);
                    }
                    Toast.makeText(FilePreviewActivity.this, "加载文件失败: " + message, Toast.LENGTH_SHORT).show();
                });
            }
        });
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
                        AppLogger.i("FilePreviewActivity", "选择的文件路径: " + path);
                        // 重新启动预览
                        Intent intent = new Intent(this, FilePreviewActivity.class);
                        intent.putExtra(EXTRA_FILE_PATH, path);
                        startActivity(intent);
                        finish();
                    } else {
                        AppLogger.e("FilePreviewActivity", "无法获取文件路径");
                        Toast.makeText(this, "无法获取文件路径", Toast.LENGTH_SHORT).show();
                        finish();
                    }
                } catch (Exception e) {
                    AppLogger.e("FilePreviewActivity", "处理文件选择结果失败", e);
                    Toast.makeText(this, "处理文件选择结果失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                }
            } else {
                AppLogger.e("FilePreviewActivity", "文件选择返回空 Uri");
                Toast.makeText(this, "文件选择返回空 Uri", Toast.LENGTH_SHORT).show();
                finish();
            }
        } else if (requestCode == REQUEST_CODE_FILE_PICKER) {
            // 用户取消了文件选择
            AppLogger.i("FilePreviewActivity", "用户取消了文件选择");
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
                        AppLogger.w("FilePreviewActivity", "尝试获取文件路径失败: " + e.getMessage());
                    }
                }
                
                // 如果以上方法都失败，尝试使用临时文件方式
                return getPathFromContentUri(uri);
            } else if (uri.getScheme().equals("file")) {
                // 对于 file:// 类型的 Uri
                return uri.getPath();
            }
        } catch (Exception e) {
            AppLogger.e("FilePreviewActivity", "从 Uri 获取文件路径失败", e);
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
            AppLogger.e("FilePreviewActivity", "从 content Uri 创建临时文件失败", e);
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
        File tempFile = File.createTempFile("preview_", "." + extension, getExternalFilesDir(null));
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
