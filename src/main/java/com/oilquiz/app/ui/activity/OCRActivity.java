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
import android.widget.ScrollView;
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
    private TextView tvOcrModelName; // OCR 模型名称显示
    private View btnOcrModel; // OCR 模型选择按钮
    private ProgressBar progressBar;
    private TextView progressText;

    private OCRManager ocrManager;
    private Bitmap selectedImage;
    private Uri selectedPdfUri;
    private Uri cameraImageUri;
    private QuestionViewModel questionViewModel;
    private NoteViewModel noteViewModel;
    
    private ScrollView scrollView;
    private View bottomBar;
    private View resultContainer;
    private View actionsContainer;
    
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
        
        // 设置 OCR 模型选择器（底部弹窗）
        setupOCRModelSelector();
        
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
        tvOcrModelName = findViewById(R.id.tv_ocr_model_name); // OCR 模型名称
        btnOcrModel = findViewById(R.id.btn_ocr_model); // OCR 模型选择按钮
        progressBar = findViewById(R.id.progress_bar);
        progressText = findViewById(R.id.progress_text);
        scrollView = findViewById(R.id.scroll_view);
        bottomBar = findViewById(R.id.bottom_bar);
        resultContainer = findViewById(R.id.result_container);
        actionsContainer = findViewById(R.id.actions_container);
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
                Toast.makeText(OCRActivity.this, getString(R.string.h_ab98c004) + languages[position], Toast.LENGTH_SHORT).show();
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 默认使用自动检测
            }
        });
    }
    
    /**
     * 设置 OCR 模型选择器（点击打开底部弹窗）
     */
    private void setupOCRModelSelector() {
        // 显示当前已选的 OCR 模型
        updateOCRModelDisplay();
        
        // 点击打开模型选择弹窗
        btnOcrModel.setOnClickListener(v -> {
            com.oilquiz.app.ui.dialog.OCRModelSelectorDialog dialog = 
                new com.oilquiz.app.ui.dialog.OCRModelSelectorDialog(this);
            dialog.setListener((modelId, modelName) -> {
                // 模型已保存，更新显示
                updateOCRModelDisplay();
            });
            dialog.show();
        });
    }
    
    /**
     * 更新 OCR 模型显示
     */
    private void updateOCRModelDisplay() {
        com.oilquiz.app.ai.model.OnlineModelManager modelManager = 
            com.oilquiz.app.ai.model.OnlineModelManager.getInstance(this);
        String ocrModelId = modelManager.getOCRModelId();
        
        if (ocrModelId == null) {
            tvOcrModelName.setText(getString(R.string.h_b789dbfb));
        } else {
            // 优先显示用户指定的具体模型名（同一 API Key 下的某个模型）
            String ocrModelName = modelManager.getOCRModelName();
            com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig config = 
                modelManager.getModel(ocrModelId);
            if (ocrModelName != null && !ocrModelName.isEmpty()) {
                tvOcrModelName.setText(ocrModelName);
            } else if (config != null) {
                tvOcrModelName.setText(config.name);
            } else {
                tvOcrModelName.setText(getString(R.string.h_b789dbfb));
            }
        }
    }

    /**
     * 识别完成后展示本次实际生效的识别引擎（在线视觉模型 / 本地高精度 OCR PP-OCRv6 / ML Kit）。
     * 本地 RapidOCR 为内置高精度引擎，无需选择；点击模型行仍用于配置在线 OCR 模型。
     */
    private void showLastEngine() {
        try {
            String engine = ocrManager.getLastEngineLabel();
            if (engine != null && !engine.isEmpty() && !"未知引擎".equals(engine)) {
                tvOcrModelName.setText(getString(R.string.h_c69d4624) + engine);
            }
        } catch (Exception e) {
            // 展示失败不影响识别结果
        }
    }

    /**
     * 识别完成统一入口：展示结果、底部从"开始识别"切换为结果操作按钮、滚动到结果区。
     * @param successText 识别成功文本；失败时传 null（错误信息已写入结果框）
     */
    private void showRecognitionResult(String successText) {
        if (successText != null) {
            resultEditText.setText(successText);
            Toast.makeText(this, getString(R.string.h_879810c0), Toast.LENGTH_SHORT).show();
        }
        if (resultContainer != null) resultContainer.setVisibility(View.VISIBLE);
        if (actionsContainer != null) actionsContainer.setVisibility(View.VISIBLE);
        if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        if (scrollView != null) {
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    /**
     * 回到选图态：恢复"开始识别"底部栏、隐藏结果与操作按钮（重新选择文件时调用）。
     */
    private void resetUiForSelection() {
        if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
        if (actionsContainer != null) actionsContainer.setVisibility(View.GONE);
        if (resultContainer != null) resultContainer.setVisibility(View.GONE);
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
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // Android 13+：Photo Picker（系统相册界面，SAF 无存储权限，返回 content:// URI）
            // 用户点"从相册选择"期望进相册，ACTION_OPEN_DOCUMENT 会弹文件管理器，语义不符
            Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            startActivityForResult(intent, PICK_IMAGE_REQUEST);
        } else {
            // 低版本：ACTION_GET_CONTENT（SAF，无需存储权限），系统选择器可直接进入相册
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            startActivityForResult(Intent.createChooser(intent, "选择图片"), PICK_IMAGE_REQUEST);
        }
    }
    
    private void openPdfPicker() {
        Intent intent = new Intent();
        // 直接放开：SAF 文件选择器（ACTION_OPEN_DOCUMENT + OPENABLE），
        // 不设 mime 限制、不弹“打开方式”列表，直接进系统文件选择器由用户自选；
        // 选中后不做类型校验（用户自行负责选对文件）。
        intent.setType("*/*");
        intent.setAction(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_PDF_REQUEST);
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
                    Toast.makeText(OCRActivity.this, getString(R.string.h_2ddf9be8), Toast.LENGTH_SHORT).show();
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
                resetUiForSelection();
                imageView.setImageBitmap(selectedImage);
                imageView.setVisibility(View.VISIBLE);
                findViewById(R.id.tv_preview_hint).setVisibility(View.GONE);
                Toast.makeText(this, getString(R.string.h_5a4a645f), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, getString(R.string.h_433e699f), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == CAMERA_REQUEST) {
            if (cameraImageUri != null) {
                selectedImage = loadOriginalImage(cameraImageUri);
                if (selectedImage != null) {
                    selectedPdfUri = null;
                    resetUiForSelection();
                    imageView.setImageBitmap(selectedImage);
                    imageView.setVisibility(View.VISIBLE);
                    findViewById(R.id.tv_preview_hint).setVisibility(View.GONE);
                    Toast.makeText(this, getString(R.string.h_5a4a645f), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.h_57418b05), Toast.LENGTH_SHORT).show();
                }
            } else if (data != null && data.getExtras() != null) {
                selectedImage = (Bitmap) data.getExtras().get("data");
                if (selectedImage != null) {
                    selectedPdfUri = null;
                    resetUiForSelection();
                    imageView.setImageBitmap(selectedImage);
                    imageView.setVisibility(View.VISIBLE);
                    findViewById(R.id.tv_preview_hint).setVisibility(View.GONE);
                    Toast.makeText(this, getString(R.string.h_5a4a645f), Toast.LENGTH_SHORT).show();
                }
            }
        } else if (requestCode == PICK_PDF_REQUEST && data != null && data.getData() != null) {
            // 直接放开：不做类型校验，用户自选任何文件
            selectedPdfUri = data.getData();
            selectedImage = null;
            resetUiForSelection();
            imageView.setVisibility(View.GONE);
            findViewById(R.id.tv_preview_hint).setVisibility(View.VISIBLE);
            ((TextView)findViewById(R.id.tv_preview_hint)).setText(getString(R.string.h_fc690a0d));
            Toast.makeText(this, getString(R.string.h_b0ae04c0), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, getString(R.string.h_dbb6345e), Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (selectedImage != null) {
            processImage();
        } else if (selectedPdfUri != null) {
            processPdf();
        } else {
            Toast.makeText(this, getString(R.string.h_5b331446), Toast.LENGTH_SHORT).show();
        }
    }

    private void processImage() {
        isProcessing = true;
        showProgress(true, "正在识别...");
        
        // 优先在线视觉模型 OCR，失败自动回退本地高精度 OCR（PP-OCRv6），最终 ML Kit 兜底
        ocrManager.processImageOnlineFirst(selectedImage, new OCRManager.OCRCallback() {
            @Override
            public void onSuccess(String text) {
                isProcessing = false;
                showProgress(false, null);
                
                showLastEngine();
                showRecognitionResult(text);
            }

            @Override
            public void onFailure(String error) {
                isProcessing = false;
                showProgress(false, null);
                
                resultEditText.setText(getString(R.string.h_da61810f) + error);
                showRecognitionResult(null);
            }
        });
    }
    
    private void processPdf() {
        isProcessing = true;
        showProgress(true, "正在打开PDF...");
        
        ocrManager.processPdf(selectedPdfUri, new OCRManager.OCRCallback() {
            @Override
            public void onSuccess(String text) {
                isProcessing = false;
                showProgress(false, null);
                
                showLastEngine();
                showRecognitionResult(text);
            }

            @Override
            public void onFailure(String error) {
                isProcessing = false;
                showProgress(false, null);
                
                resultEditText.setText(getString(R.string.h_bb6a8045) + error);
                showRecognitionResult(null);
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
            Toast.makeText(this, getString(R.string.h_1d8c9cf9), Toast.LENGTH_SHORT).show();
            return;
        }
        
        android.content.ClipboardManager clipboard = 
            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = android.content.ClipData.newPlainText("OCR Text", text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, getString(R.string.h_4a131ed5), Toast.LENGTH_SHORT).show();
    }
    
    private void shareText() {
        String text = resultEditText.getText().toString().trim();
        if (text.isEmpty() || text.startsWith("识别失败") || text.startsWith("PDF识别失败")) {
            Toast.makeText(this, getString(R.string.h_3da84480), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, getString(R.string.h_c34088db), Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 创建一个对话框让用户输入笔记标题
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.h_83b0bc7b));
        
        // 设置输入框
        final EditText input = new EditText(this);
        input.setHint(getString(R.string.h_554a28dd));
        input.setText(getString(R.string.h_3c62c068));
        builder.setView(input);
        
        // 设置按钮
        builder.setPositiveButton(getString(R.string.h_be5fbbe3), (dialog, which) -> {
            String title = input.getText().toString().trim();
            if (title.isEmpty()) {
                title = getString(R.string.h_3c62c068);
            }
            
            // 保存笔记
            final String finalTitle = title;
            noteViewModel.addNote(finalTitle, text, new com.oilquiz.app.repository.NoteRepository.RepositoryCallback<Long>() {
                @Override
                public void onSuccess(Long result) {
                    runOnUiThread(() -> {
                        Toast.makeText(OCRActivity.this, getString(R.string.h_7e97245e), Toast.LENGTH_SHORT).show();
                    });
                }
                
                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        Toast.makeText(OCRActivity.this, getString(R.string.h_34e82ad6) + error, Toast.LENGTH_SHORT).show();
                    });
                }
            });
        });
        
        builder.setNegativeButton(getString(R.string.h_625fb26b), (dialog, which) -> dialog.cancel());
        
        builder.show();
    }

    private void saveAsQuestion() {
        String text = resultEditText.getText().toString().trim();
        if (text.isEmpty() || text.startsWith("识别失败") || text.startsWith("PDF识别失败")) {
            Toast.makeText(this, getString(R.string.h_be0c5a13), Toast.LENGTH_SHORT).show();
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
                Toast.makeText(OCRActivity.this, getString(R.string.h_d6caa7b2), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(OCRActivity.this, getString(R.string.h_9e9563b5) + error, Toast.LENGTH_SHORT).show();
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
