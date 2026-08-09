package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.util.render.TemplateFileGenerator;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模板导入页面。
 * <p>
 * 提供标准题库导入模板（Excel/CSV/JSON，含示例行）的下载能力：
 * 用户下载模板 → 按表头填写题目 → 回到「直接导入」选择该文件入库。
 * 模板通过 MediaStore 保存到系统「下载/OilQuiz」目录，文件管理器可直接查看编辑。
 * <p>
 * 模板表头与 {@code FieldMappingRegistry} 标准字段一致，导入时可被自动映射识别。
 */
public class TemplateImportActivity extends BaseActivity {

    private MaterialButton btnDownloadExcel;
    private MaterialButton btnDownloadCsv;
    private MaterialButton btnDownloadJson;
    private MaterialButton btnGoImport;
    private TextView tvTemplatePath;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected int getLayoutId() {
        return R.layout.activity_template_import;
    }

    @Override
    protected void initView() {
        setupToolbar("模板导入");
        btnDownloadExcel = findViewById(R.id.btnDownloadExcel);
        btnDownloadCsv = findViewById(R.id.btnDownloadCsv);
        btnDownloadJson = findViewById(R.id.btnDownloadJson);
        btnGoImport = findViewById(R.id.btnGoImport);
        tvTemplatePath = findViewById(R.id.tvTemplatePath);
        tvTemplatePath.setText("模板将保存到：内部存储/Download/OilQuiz/");
    }

    @Override
    protected void initData() {
        // 无需初始化数据
    }

    @Override
    protected void initListener() {
        btnDownloadExcel.setOnClickListener(v -> downloadTemplate("EXCEL"));
        btnDownloadCsv.setOnClickListener(v -> downloadTemplate("CSV"));
        btnDownloadJson.setOnClickListener(v -> downloadTemplate("JSON"));
        btnGoImport.setOnClickListener(v -> {
            Intent intent = new Intent(this, ImportActivity.class);
            startActivity(intent);
        });
    }

    /** 后台生成指定格式的模板文件（保存到系统下载目录）并反馈结果 */
    private void downloadTemplate(final String format) {
        setButtonsEnabled(false);
        showToast("正在生成 " + format + " 模板...");
        executor.execute(() -> {
            String savedPath = null;
            try {
                savedPath = TemplateFileGenerator.generateToDownloads(
                        getApplicationContext(), format);
            } catch (Throwable t) {
                android.util.Log.e("TemplateImport", "模板生成异常: " + t.getMessage(), t);
            }
            final String path = savedPath;
            runOnUiThread(() -> {
                setButtonsEnabled(true);
                if (path != null) {
                    tvTemplatePath.setText("已保存：" + path);
                    showLongToast("模板已保存到：\n" + path
                            + "\n\n请在文件管理器中打开填写，完成后点击「填好了，去导入」");
                } else {
                    showLongToast("模板生成失败，请检查存储权限后重试");
                }
            });
        });
    }

    /** 生成期间禁用按钮，避免重复点击 */
    private void setButtonsEnabled(boolean enabled) {
        btnDownloadExcel.setEnabled(enabled);
        btnDownloadCsv.setEnabled(enabled);
        btnDownloadJson.setEnabled(enabled);
        btnGoImport.setEnabled(enabled);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
