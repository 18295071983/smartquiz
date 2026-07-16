package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.oilquiz.app.resource.AppResourceManager;
import com.oilquiz.app.resource.PermissionResourceProvider;
import java.util.List;
import androidx.exifinterface.media.ExifInterface;

import android.Manifest;
import android.content.pm.PackageManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.oilquiz.app.R;
import com.oilquiz.app.manager.OCRManager;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.viewmodel.QuestionViewModel;
import com.oilquiz.app.viewmodel.NoteViewModel;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class OCRActivity extends AppCompatActivity {

    private static final int PICK_IMAGE_REQUEST = 1;
    private static final int CAMERA_REQUEST = 2;
    private static final int PICK_PDF_REQUEST = 3;
    private static final int CAMERA_PERMISSION_REQUEST = 4;

    private static final int MAX_OCR_IMAGE_DIMENSION = 4096;

    private ImageView imageView;
    private TextInputEditText resultEditText;
    private MaterialButton selectImageButton;
    private MaterialButton processImageButton;
    private MaterialButton saveTextButton;
    private MaterialButton copyTextButton;
    private MaterialButton shareTextButton;
    private MaterialButton selectPdfButton;
    private Spinner languageSpinner;
    private ProgressBar progressBar;
    private TextView progressText;

    private OCRManager ocrManager;
    private Bitmap selectedImage;
    private Uri selectedPdfUri;
    private Uri cameraImageUri;
    private QuestionViewModel questionViewModel;
    private NoteViewModel noteViewModel;
    
    private boolean isProcessing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ocr);

        ocrManager = new OCRManager(this);
        questionViewModel = new ViewModelProvider(this).get(QuestionViewModel.class);
        noteViewModel = new ViewModelProvider(this).get(NoteViewModel.class);
        noteViewModel.init(this);

        // 初始化视图
        initViews();
        
        // 设置语言选择器
        setupLanguageSpinner();
        
        // 设置按钮点击事件
        setupButtons();
    }
    
    private void initViews() {
        imageView = findViewById(R.id.iv_preview);
        resultEditText = findViewById(R.id.et_recognized_text);
        selectImageButton = findViewById(R.id.btn_select_image);
        processImageButton = findViewById(R.id.btn_start_recognition);
        MaterialButton captureButton = findViewById(R.id.btn_capture_image);
        saveTextButton = findViewById(R.id.btn_save_text);
        copyTextButton = findViewById(R.id.btn_copy_text);
        shareTextButton = findViewById(R.id.btn_share_text);
        selectPdfButton = findViewById(R.id.btn_select_pdf);
        languageSpinner = findViewById(R.id.spinner_language);
        progressBar = findViewById(R.id.progress_bar);
        progressText = findViewById(R.id.progress_text);
    }
    
    private void setupLanguageSpinner() {
        String[] languages = {"自动检测", "中文", "英文", "日文", "韩文"};
        final String[] languageCodes = {OCRManager.LANG_AUTO, OCRManager.LANG_CHINESE, 
                                        OCRManager.LANG_ENGLISH, OCRManager.LANG_JAPANESE, 
                                        OCRManager.LANG_KOREAN};
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, 
            android.R.layout.simple_spinner_item, languages);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);
        
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selectedLang = languageCodes[position];
                ocrManager.switchRecognizer(selectedLang);
                Toast.makeText(OCRActivity.this, "已切换到: " + languages[position], Toast.LENGTH_SHORT).show();
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 默认使用自动检测
            }
        });
    }
    
    private void setupButtons() {
        // 拍照按钮
        MaterialButton captureButton = findViewById(R.id.btn_capture_image);
        captureButton.setOnClickListener(v -> openCamera());
        
        // 选择图片按钮
        selectImageButton.setOnClickListener(v -> openImagePicker());
        
        // 选择PDF按钮
        selectPdfButton.setOnClickListener(v -> openPdfPicker());
        
        // 开始识别按钮
        processImageButton.setOnClickListener(v -> startRecognition());
        
        // 保存文本按钮
        saveTextButton.setOnClickListener(v -> saveAsQuestion());
        
        // 复制文本按钮
        copyTextButton.setOnClickListener(v -> copyText());
        
        // 分享文本按钮
        shareTextButton.setOnClickListener(v -> shareText());
        
        // 添加到笔记按钮
        MaterialButton addToNoteButton = findViewById(R.id.btn_add_to_note);
        addToNoteButton.setOnClickListener(v -> addToNote());
    }

    private void openImagePicker() {
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(Intent.createChooser(intent, "选择图片"), PICK_IMAGE_REQUEST);
    }
    
    private void openPdfPicker() {
        Intent intent = new Intent();
        intent.setType("application/pdf");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(Intent.createChooser(intent, "选择PDF文件"), PICK_PDF_REQUEST);
    }

    private void openCamera() {
        AppResourceManager resources = AppResourceManager.getInstance(this);
        if (!resources.hasCameraPermission()) {
            resources.permissions().requestCameraPermission(this, new PermissionResourceProvider.PermissionCallback() {
                @Override
                public void onGranted() {
                    Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    if (intent.resolveActivity(getPackageManager()) != null) {
                        startActivityForResult(intent, CAMERA_REQUEST);
                    }
                }

                @Override
                public void onDenied(List<String> deniedPermissions) {
                    Toast.makeText(OCRActivity.this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(intent, CAMERA_REQUEST);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AppResourceManager.getInstance(this).permissions().onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK) return;

        if (requestCode == PICK_IMAGE_REQUEST && data != null && data.getData() != null) {
            Uri imageUri = data.getData();
            selectedImage = loadOriginalImage(imageUri);
            if (selectedImage != null) {
                selectedPdfUri = null;
                imageView.setImageBitmap(selectedImage);
                imageView.setVisibility(View.VISIBLE);
                findViewById(R.id.tv_preview_hint).setVisibility(View.GONE);
                Toast.makeText(this, "图片已加载", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "获取图片失败", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == CAMERA_REQUEST) {
            if (cameraImageUri != null) {
                selectedImage = loadOriginalImage(cameraImageUri);
                if (selectedImage != null) {
                    selectedPdfUri = null;
                    imageView.setImageBitmap(selectedImage);
                    imageView.setVisibility(View.VISIBLE);
                    findViewById(R.id.tv_preview_hint).setVisibility(View.GONE);
                    Toast.makeText(this, "图片已加载", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "无法加载拍摄的图片", Toast.LENGTH_SHORT).show();
                }
            } else if (data != null && data.getExtras() != null) {
                selectedImage = (Bitmap) data.getExtras().get("data");
                if (selectedImage != null) {
                    selectedPdfUri = null;
                    imageView.setImageBitmap(selectedImage);
                    imageView.setVisibility(View.VISIBLE);
                    findViewById(R.id.tv_preview_hint).setVisibility(View.GONE);
                    Toast.makeText(this, "图片已加载", Toast.LENGTH_SHORT).show();
                }
            }
        } else if (requestCode == PICK_PDF_REQUEST && data != null && data.getData() != null) {
            selectedPdfUri = data.getData();
            selectedImage = null;
            imageView.setVisibility(View.GONE);
            findViewById(R.id.tv_preview_hint).setVisibility(View.VISIBLE);
            ((TextView)findViewById(R.id.tv_preview_hint)).setText("已选择PDF文件\n点击开始识别");
            Toast.makeText(this, "PDF文件已选择", Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap loadOriginalImage(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(inputStream, null, options);
            inputStream.close();

            int originalWidth = options.outWidth;
            int originalHeight = options.outHeight;

            inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) return null;

            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;

            if (originalWidth > MAX_OCR_IMAGE_DIMENSION || originalHeight > MAX_OCR_IMAGE_DIMENSION) {
                int sampleSize = calculateSampleSize(originalWidth, originalHeight, MAX_OCR_IMAGE_DIMENSION);
                options.inSampleSize = sampleSize;
            }

            Bitmap bitmap = BitmapFactory.decodeStream(inputStream, null, options);
            inputStream.close();

            if (bitmap == null) return null;

            bitmap = rotateImageIfNeeded(uri, bitmap);

            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private int calculateSampleSize(int width, int height, int maxDimension) {
        int sampleSize = 1;
        while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private Bitmap rotateImageIfNeeded(Uri uri, Bitmap bitmap) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream == null) return bitmap;
            ExifInterface exif = new ExifInterface(inputStream);
            inputStream.close();

            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rotation = 0;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                default: return bitmap;
            }

            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (Exception e) {
            return bitmap;
        }
    }
    
    private void startRecognition() {
        if (isProcessing) {
            Toast.makeText(this, "正在处理中，请稍候...", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (selectedImage != null) {
            processImage();
        } else if (selectedPdfUri != null) {
            processPdf();
        } else {
            Toast.makeText(this, "请先选择图片或PDF文件", Toast.LENGTH_SHORT).show();
        }
    }

    private void processImage() {
        isProcessing = true;
        showProgress(true, "正在识别...");
        
        ocrManager.processImage(selectedImage, new OCRManager.OCRCallback() {
            @Override
            public void onSuccess(String text) {
                isProcessing = false;
                showProgress(false, null);
                
                // 确保文本正确显示，避免乱码
                resultEditText.setText(text);
                findViewById(R.id.result_container).setVisibility(View.VISIBLE);
                findViewById(R.id.actions_container).setVisibility(View.VISIBLE);
                Toast.makeText(OCRActivity.this, "识别成功！", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(String error) {
                isProcessing = false;
                showProgress(false, null);
                
                resultEditText.setText("识别失败: " + error);
                findViewById(R.id.result_container).setVisibility(View.VISIBLE);
                findViewById(R.id.actions_container).setVisibility(View.VISIBLE);
                Toast.makeText(OCRActivity.this, "识别失败: " + error, Toast.LENGTH_SHORT).show();
            }
        }, true); // 启用自动检测重试
    }
    
    private void processPdf() {
        isProcessing = true;
        showProgress(true, "正在打开PDF...");
        
        ocrManager.processPdf(selectedPdfUri, new OCRManager.OCRCallback() {
            @Override
            public void onSuccess(String text) {
                isProcessing = false;
                showProgress(false, null);
                
                resultEditText.setText(text);
                findViewById(R.id.result_container).setVisibility(View.VISIBLE);
                findViewById(R.id.actions_container).setVisibility(View.VISIBLE);
                Toast.makeText(OCRActivity.this, "PDF识别成功！", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailure(String error) {
                isProcessing = false;
                showProgress(false, null);
                
                resultEditText.setText("PDF识别失败: " + error);
                findViewById(R.id.result_container).setVisibility(View.VISIBLE);
                findViewById(R.id.actions_container).setVisibility(View.VISIBLE);
                Toast.makeText(OCRActivity.this, "PDF识别失败: " + error, Toast.LENGTH_SHORT).show();
            }
        }, (percent, message) -> {
            runOnUiThread(() -> {
                showProgress(true, message);
                if (progressBar != null) {
                    progressBar.setProgress(percent);
                }
            });
        });
    }
    
    private void showProgress(boolean show, String message) {
        View progressCard = findViewById(R.id.progress_card);
        if (progressCard != null) {
            progressCard.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (progressBar != null) {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        if (progressText != null) {
            progressText.setVisibility(show ? View.VISIBLE : View.GONE);
            if (message != null) {
                progressText.setText(message);
            }
        }
    }
    
    private void copyText() {
        String text = resultEditText.getText().toString().trim();
        if (text.isEmpty() || text.startsWith("Error:")) {
            Toast.makeText(this, "没有可复制的文本", Toast.LENGTH_SHORT).show();
            return;
        }
        
        android.content.ClipboardManager clipboard = 
            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("OCR Text", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "文本已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }
    
    private void shareText() {
        String text = resultEditText.getText().toString().trim();
        if (text.isEmpty() || text.startsWith("识别失败") || text.startsWith("PDF识别失败")) {
            Toast.makeText(this, "没有可分享的文本", Toast.LENGTH_SHORT).show();
            return;
        }
        
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(intent, "分享文本"));
    }
    
    private void addToNote() {
        final String text = resultEditText.getText().toString().trim();
        if (text.isEmpty() || text.startsWith("识别失败") || text.startsWith("PDF识别失败")) {
            Toast.makeText(this, "没有可保存的文本", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 创建一个对话框让用户输入笔记标题
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("添加到笔记");
        
        // 设置输入框
        final EditText input = new EditText(this);
        input.setHint("请输入笔记标题");
        input.setText("OCR识别结果");
        builder.setView(input);
        
        // 设置按钮
        builder.setPositiveButton("保存", (dialog, which) -> {
            String title = input.getText().toString().trim();
            if (title.isEmpty()) {
                title = "OCR识别结果";
            }
            
            // 保存笔记
            final String finalTitle = title;
            noteViewModel.addNote(finalTitle, text, new com.oilquiz.app.repository.NoteRepository.RepositoryCallback<Long>() {
                @Override
                public void onSuccess(Long result) {
                    runOnUiThread(() -> {
                        Toast.makeText(OCRActivity.this, "笔记保存成功", Toast.LENGTH_SHORT).show();
                    });
                }
                
                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        Toast.makeText(OCRActivity.this, "笔记保存失败: " + error, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });
        
        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());
        
        builder.show();
    }

    private void saveAsQuestion() {
        String text = resultEditText.getText().toString().trim();
        if (text.isEmpty() || text.startsWith("识别失败") || text.startsWith("PDF识别失败")) {
            Toast.makeText(this, "请先获取有效文本", Toast.LENGTH_SHORT).show();
            return;
        }

        // 简单解析文字为题目
        Question question = new Question();
        question.setQuestionText(text);
        question.setCategory("OCR导入");
        question.setDifficulty(2); // 中等难度
        question.setCorrectAnswer("A"); // 默认答案
        question.setOptionA("选项A");
        question.setOptionB("选项B");
        question.setOptionC("选项C");
        question.setOptionD("选项D");

        questionViewModel.addQuestion(question, new QuestionViewModel.AddQuestionCallback() {
            @Override
            public void onSuccess() {
                Toast.makeText(OCRActivity.this, "保存题目成功", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(OCRActivity.this, "保存题目失败：" + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ocrManager.release();
        // 释放Bitmap资源
        if (selectedImage != null) {
            selectedImage.recycle();
            selectedImage = null;
        }
    }
}
