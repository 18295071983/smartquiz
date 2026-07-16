package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.util.render.WebViewRenderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class SimpleFilePreviewActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_SHEET_INDEX = "sheet_index";
    private static final int REQUEST_CODE_PICK_FILE = 1001;

    private File file;
    private int sheetIndex;

    private ProgressBar progressBar;
    private TextView statusText;
    private WebView previewWebView;
    private ImageView previewImageView;
    private Button btnClose;
    private Button btnImport;

    enum FileType {
        EXCEL,
        IMAGE,
        TEXT,
        HTML,
        PDF,
        WORD,
        UNSUPPORTED
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_simple_file_preview);

        initViews();
        loadData();
        setupListeners();
        loadFilePreview();
    }

    private void initViews() {
        View loadingState = findViewById(R.id.loadingState);
        progressBar = loadingState.findViewById(R.id.progressIndicator);
        statusText = loadingState.findViewById(R.id.loadingText);
        previewWebView = findViewById(R.id.previewWebView);
        previewImageView = findViewById(R.id.previewImageView);
        btnClose = findViewById(R.id.btnClose);
        btnImport = findViewById(R.id.btnImport);
    }

    private FileType detectFileType(File file) {
        String fileName = file.getName().toLowerCase();
        
        if (fileName.endsWith(".xls") || fileName.endsWith(".xlsx")) {
            return FileType.EXCEL;
        } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || 
                   fileName.endsWith(".png") || fileName.endsWith(".gif") || 
                   fileName.endsWith(".bmp") || fileName.endsWith(".webp")) {
            return FileType.IMAGE;
        } else if (fileName.endsWith(".txt") || fileName.endsWith(".md") || 
                   fileName.endsWith(".json") || fileName.endsWith(".xml") ||
                   fileName.endsWith(".csv")) {
            return FileType.TEXT;
        } else if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
            return FileType.HTML;
        } else if (fileName.endsWith(".pdf")) {
            return FileType.PDF;
        } else if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
            return FileType.WORD;
        }
        return FileType.UNSUPPORTED;
    }

    private void loadData() {
        Intent intent = getIntent();
        if (intent != null) {
            String filePath = intent.getStringExtra(EXTRA_FILE_PATH);
            if (filePath != null) {
                file = new File(filePath);
            }
            sheetIndex = intent.getIntExtra(EXTRA_SHEET_INDEX, 0);
        }
    }

    private void setupListeners() {
        btnClose.setOnClickListener(v -> finish());
        btnImport.setOnClickListener(v -> importFile());
    }

    private void importFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    // 从Content URI创建临时文件
                    File tempFile = createTempFileFromUri(uri);
                    if (tempFile != null) {
                        // 更新文件对象并重新加载预览
                        file = tempFile;
                        sheetIndex = 0; // 默认为第一个工作表
                        loadFilePreview();
                    } else {
                        Toast.makeText(this, "无法创建临时文件", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "导入文件失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    // 从Content URI创建临时文件
    private File createTempFileFromUri(Uri uri) throws IOException {
        ContentResolver contentResolver = getContentResolver();
        InputStream inputStream = contentResolver.openInputStream(uri);
        if (inputStream == null) {
            return null;
        }

        // 获取原始文件的后缀名
        String fileExtension = "";
        String originalFileName = getFileNameFromUri(uri);
        if (originalFileName != null && originalFileName.lastIndexOf('.') > 0) {
            fileExtension = originalFileName.substring(originalFileName.lastIndexOf('.'));
        }

        // 创建临时文件，保留原始文件的后缀名
        File tempFile = File.createTempFile("preview", fileExtension, getCacheDir());
        tempFile.deleteOnExit();

        // 复制文件内容
        try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        } finally {
            inputStream.close();
        }

        return tempFile;
    }

    // 从URI获取文件名
    private String getFileNameFromUri(Uri uri) {
        String fileName = null;
        String scheme = uri.getScheme();
        if ("content".equals(scheme)) {
            ContentResolver contentResolver = getContentResolver();
            String[] projection = {android.provider.MediaStore.MediaColumns.DISPLAY_NAME};
            android.database.Cursor cursor = contentResolver.query(uri, projection, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME);
                if (columnIndex != -1) {
                    fileName = cursor.getString(columnIndex);
                }
                cursor.close();
            }
        } else if ("file".equals(scheme)) {
            fileName = new File(uri.getPath()).getName();
        }
        return fileName;
    }

    private void loadFilePreview() {
        previewWebView.setVisibility(View.GONE);
        previewImageView.setVisibility(View.GONE);

        if (file == null) {
            progressBar.setVisibility(View.GONE);
            statusText.setText("请点击\"导入文件\"按钮选择要预览的文件");
            statusText.setVisibility(View.VISIBLE);
            return;
        }

        if (!file.exists()) {
            progressBar.setVisibility(View.GONE);
            statusText.setText("文件不存在");
            statusText.setVisibility(View.VISIBLE);
            Toast.makeText(this, "文件不存在，无法加载", Toast.LENGTH_SHORT).show();
            return;
        }

        FileType fileType = detectFileType(file);
        
        switch (fileType) {
            case EXCEL:
                renderExcelFile();
                break;
            case IMAGE:
                renderImageFile();
                break;
            case TEXT:
                renderTextFile();
                break;
            case HTML:
                renderHtmlFile();
                break;
            case PDF:
                renderPdfFile();
                break;
            case WORD:
                renderWordFile();
                break;
            case UNSUPPORTED:
                showUnsupportedFile();
                break;
        }
    }

    private void renderExcelFile() {
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("正在加载文件内容...");
        statusText.setVisibility(View.VISIBLE);

        WebViewRenderer.renderExcelToHtmlForPreview(file, sheetIndex, new WebViewRenderer.RenderCallback() {
            @Override
            public void onRenderStart() {
                runOnUiThread(() -> statusText.setText("开始渲染文件..."));
            }

            @Override
            public void onRenderProgress(int current, int total) {
                runOnUiThread(() -> {
                    statusText.setText("渲染中: " + current + "/" + total + " 行");
                    if (total > 0) {
                        progressBar.setProgress((int) ((float) current / total * 100));
                    }
                });
            }

            @Override
            public void onRenderComplete(String htmlContent) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setVisibility(View.GONE);
                    WebViewRenderer.displayInWebView(previewWebView, htmlContent);
                    previewWebView.setVisibility(View.VISIBLE);
                    Toast.makeText(SimpleFilePreviewActivity.this, "文件加载成功", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onRenderError(String message) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("加载失败: " + message);
                    statusText.setVisibility(View.VISIBLE);
                    Toast.makeText(SimpleFilePreviewActivity.this, "加载文件失败: " + message, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void renderImageFile() {
        progressBar.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        try {
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap != null) {
                previewImageView.setImageBitmap(bitmap);
                previewImageView.setVisibility(View.VISIBLE);
                Toast.makeText(this, "图片加载成功", Toast.LENGTH_SHORT).show();
            } else {
                statusText.setText("无法加载图片文件");
                statusText.setVisibility(View.VISIBLE);
                Toast.makeText(this, "无法加载图片文件", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            statusText.setText("加载图片失败: " + e.getMessage());
            statusText.setVisibility(View.VISIBLE);
            Toast.makeText(this, "加载图片失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderTextFile() {
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("正在加载文本文件...");
        statusText.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                StringBuilder content = new StringBuilder();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8")
                );
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                reader.close();

                String html = "<!DOCTYPE html><html><head>" +
                    "<meta charset=\"UTF-8\">" +
                    "<style>body{font-family:monospace;padding:16px;white-space:pre-wrap;word-wrap:break-word;}</style>" +
                    "</head><body>" + escapeHtml(content.toString()) + "</body></html>";

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setVisibility(View.GONE);
                    WebViewRenderer.displayInWebView(previewWebView, html);
                    previewWebView.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "文本文件加载成功", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("加载失败: " + e.getMessage());
                    statusText.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "加载文本文件失败", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void renderHtmlFile() {
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText("正在加载HTML文件...");
        statusText.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                StringBuilder content = new StringBuilder();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(file), "UTF-8")
                );
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
                reader.close();

                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setVisibility(View.GONE);
                    WebViewRenderer.displayInWebView(previewWebView, content.toString());
                    previewWebView.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "HTML文件加载成功", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("加载失败: " + e.getMessage());
                    statusText.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "加载HTML文件失败", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void renderPdfFile() {
        openWithSystemApp();
    }

    private void renderWordFile() {
        openWithSystemApp();
    }

    private void showUnsupportedFile() {
        openWithSystemApp();
    }

    private void openWithSystemApp() {
        progressBar.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri;
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                uri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "com.oilquiz.app.fileprovider",
                    file
                );
            } else {
                uri = Uri.fromFile(file);
            }
            
            String mimeType = getContentResolver().getType(uri);
            if (mimeType == null) {
                mimeType = "*/*";
            }
            
            intent.setDataAndType(uri, mimeType);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "选择应用打开"));
        } catch (Exception e) {
            statusText.setText("无法打开文件，请安装相应的应用");
            statusText.setVisibility(View.VISIBLE);
            Toast.makeText(this, "无法打开文件: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#039;");
    }
}
