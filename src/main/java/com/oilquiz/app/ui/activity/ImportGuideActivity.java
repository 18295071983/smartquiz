package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.ui.base.BaseActivity;
import androidx.cardview.widget.CardView;

import com.oilquiz.app.R;

public class ImportGuideActivity extends BaseActivity {

    private static final int REQUEST_CODE_IMPORT = 1001;
    private static final int REQUEST_CODE_TEMPLATE = 1002;
    private static final int REQUEST_CODE_AI_IMPORT = 1003;
    /** 存储运行时权限请求码（Android 6~10 使用；Android 11+ 走"所有文件访问"设置页） */
    private static final int REQUEST_CODE_STORAGE_PERMISSION = 1201;

    private CardView cardAIImport;
    private CardView cardDirectImport;
    private CardView cardTemplateImport;
    private CardView cardHistory;
    private MaterialButton btnAIImport;
    private MaterialButton btnDirectImport;
    private MaterialButton btnTemplateImport;
    private MaterialButton btnViewHistory;
    private LinearLayout layoutSupportedFormats;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_import_guide;
    }

    @Override
    protected void initView() {
        // 设置Toolbar
        setupToolbar("题目导入");

        cardAIImport = findViewById(R.id.cardAIImport);
        cardDirectImport = findViewById(R.id.cardDirectImport);
        cardTemplateImport = findViewById(R.id.cardTemplateImport);
        cardHistory = findViewById(R.id.cardHistory);
        btnAIImport = findViewById(R.id.btnAIImport);
        btnDirectImport = findViewById(R.id.btnDirectImport);
        btnTemplateImport = findViewById(R.id.btnTemplateImport);
        btnViewHistory = findViewById(R.id.btnViewHistory);
        layoutSupportedFormats = findViewById(R.id.layoutSupportedFormats);

        // 设置支持格式说明
        setupSupportedFormats();
    }

    @Override
    protected void initData() {
        // 无需初始化数据
    }

    @Override
    protected void initListener() {
        // AI 导入
        btnAIImport.setOnClickListener(v -> startAIImport());
        cardAIImport.setOnClickListener(v -> startAIImport());

        // 直接导入
        btnDirectImport.setOnClickListener(v -> startDirectImport());
        cardDirectImport.setOnClickListener(v -> startDirectImport());

        // 使用模板导入
        btnTemplateImport.setOnClickListener(v -> startTemplateImport());
        cardTemplateImport.setOnClickListener(v -> startTemplateImport());

        // 查看历史
        btnViewHistory.setOnClickListener(v -> viewImportHistory());
        cardHistory.setOnClickListener(v -> viewImportHistory());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupSupportedFormats() {
        String[] formats = {"Excel (.xls, .xlsx)", "CSV (.csv)", "JSON (.json)"};
        int[] icons = {R.drawable.ic_excel, R.drawable.ic_csv, R.drawable.ic_json};

        for (int i = 0; i < formats.length; i++) {
            View formatView = getLayoutInflater().inflate(R.layout.item_format_badge, layoutSupportedFormats, false);
            TextView tvFormat = formatView.findViewById(R.id.tvFormat);
            tvFormat.setText(formats[i]);
            layoutSupportedFormats.addView(formatView);
        }
    }

    private void startAIImport() {
        if (!ensurePublicStoragePermission()) return;
        Intent intent = new Intent(this, AIImportActivity.class);
        startActivityForResult(intent, REQUEST_CODE_AI_IMPORT);
    }

    private void startDirectImport() {
        if (!ensurePublicStoragePermission()) return;
        Intent intent = new Intent(this, ImportActivity.class);
        startActivityForResult(intent, REQUEST_CODE_IMPORT);
    }

    /** 模板导入：进入新模板导入页（下载标准模板 → 填写 → 直接导入入库） */
    private void startTemplateImport() {
        if (!ensurePublicStoragePermission()) return;
        Intent intent = new Intent(this, TemplateImportActivity.class);
        startActivity(intent);
    }

    private void viewImportHistory() {
        Intent intent = new Intent(this, HistoryActivity.class);
        startActivity(intent);
    }

    // ======================== 公共目录权限检查（导入前置） ========================

    /**
     * 已授权返回 true；未授权则引导授权并返回 false。
     * 导入管线（AI/直接/模板）最终都在公共目录 /storage/emulated/0/OilQuiz/ 读写源文件与临时 CSV
     * （ImportDirs），Python 无权限会直接抛 PermissionError，必须在进入导入页前主动校验。
     */
    private boolean ensurePublicStoragePermission() {
        if (hasPublicStoragePermission()) return true;
        requestPublicStoragePermission();
        return false;
    }

    /**
     * 公共目录权限是否已授予：
     * - Android 11+（R）：MANAGE_EXTERNAL_STORAGE（"所有文件访问"）
     * - Android 6~10（M~R）：READ/WRITE_EXTERNAL_STORAGE
     */
    private boolean hasPublicStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                    && androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** 未授权时引导授权：Android 11+ 弹说明框跳"所有文件访问"设置页；Android 6~10 走运行时权限弹窗 */
    private void requestPublicStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.h_a902a02e))
                    .setMessage(getString(R.string.h_8220fcc6)
                            + "未授予时 Python 无法读写该目录，导入会失败。\n"
                            + "请点击\"去授权\"开启\"所有文件访问\"权限，然后重新进入导入。")
                    .setPositiveButton(getString(R.string.h_4a1c90d8), (d, w) -> openAllFilesAccessSetting())
                    .setNegativeButton(getString(R.string.h_625fb26b), null)
                    .show();
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_CODE_STORAGE_PERMISSION);
        }
    }

    /** 跳转系统"所有文件访问"授权页（优先直达本应用，异常时 fallback 通用入口） */
    private void openAllFilesAccessSetting() {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ex) {
                Toast.makeText(this, getString(R.string.h_7bf382b4) + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_STORAGE_PERMISSION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this,
                    granted ? "存储权限已授予，请重新进入导入" : "需要存储权限才能导入题库，请授予后重试",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == RESULT_OK) {
            if (requestCode == REQUEST_CODE_IMPORT || requestCode == REQUEST_CODE_TEMPLATE
                    || requestCode == REQUEST_CODE_AI_IMPORT) {
                // 导入成功，可以显示提示或刷新界面
                Toast.makeText(this, getString(R.string.h_8edceef7), Toast.LENGTH_SHORT).show();
            }
        }
    }
}
