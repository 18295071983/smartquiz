package com.oilquiz.app.ui.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.oilquiz.app.R;
import com.oilquiz.app.adapter.QuestionAdapter;
import com.oilquiz.app.ui.base.BaseActivity;
import com.oilquiz.app.util.export.ExportManager;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.util.render.ExcelUtil;
import com.oilquiz.app.viewmodel.QuestionViewModel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class QuestionBankActivity extends BaseActivity {

    private static final int REQUEST_CODE_PICK_FILE = 1001;

    private RecyclerView questionListView;
    private QuestionAdapter questionAdapter;
    private TextView totalCountTextView;
    private TextView favoriteCountTextView;
    private TextView categoryCountTextView;
    private List<Question> allQuestions = new ArrayList<>();
    private List<Question> filteredQuestions = new ArrayList<>();

    @Inject
    QuestionViewModel questionViewModel;

    @Inject
    ExportManager exportManager;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_question_bank;
    }

    @Override
    protected void initView() {
        // 设置工具栏（带返回按钮）
        setupToolbar("题库管理");
        
        questionListView = findViewById(R.id.questionListView);
        totalCountTextView = findViewById(R.id.totalCountTextView);
        favoriteCountTextView = findViewById(R.id.favoriteCountTextView);
        categoryCountTextView = findViewById(R.id.categoryCountTextView);
    }

    @Override
    protected void initData() {
        exportManager.init(this);
        loadQuestions();
    }

    @Override
    protected void initListener() {
        // 绑定视图模式切换按钮
        View btnListView = findViewById(R.id.btnListView);
        if (btnListView != null) {
            btnListView.setOnClickListener(v -> {
                isCardViewMode = false;
                loadQuestions();
            });
        }
        
        View btnCardView = findViewById(R.id.btnCardView);
        if (btnCardView != null) {
            btnCardView.setOnClickListener(v -> {
                isCardViewMode = true;
                loadQuestions();
            });
        }
    }
    
    // 更新视图模式按钮的状态
    private void updateViewModeButtons() {
        View btnListView = findViewById(R.id.btnListView);
        View btnCardView = findViewById(R.id.btnCardView);
        
        if (btnListView != null && btnCardView != null) {
            if (isCardViewMode) {
                ((com.google.android.material.button.MaterialButton)btnCardView).setBackgroundTintList(com.google.android.material.color.MaterialColors.getColorStateList(this, R.color.primary, null));
                ((com.google.android.material.button.MaterialButton)btnListView).setBackgroundTintList(com.google.android.material.color.MaterialColors.getColorStateList(this, R.color.gray_500, null));
            } else {
                ((com.google.android.material.button.MaterialButton)btnListView).setBackgroundTintList(com.google.android.material.color.MaterialColors.getColorStateList(this, R.color.primary, null));
                ((com.google.android.material.button.MaterialButton)btnCardView).setBackgroundTintList(com.google.android.material.color.MaterialColors.getColorStateList(this, R.color.gray_500, null));
            }
        }
    }

    // 显示批量操作对话框
    private void showBatchOperationsDialog() {
        new AlertDialog.Builder(this)
                .setTitle("批量操作")
                .setItems(new String[]{"批量删除", "批量收藏", "批量导出"}, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            batchDeleteQuestions();
                            break;
                        case 1:
                            batchFavoriteQuestions();
                            break;
                        case 2:
                            exportQuestions();
                            break;
                    }
                })
                .show();
    }

    private boolean isCardViewMode = true; // 默认使用卡片视图

    private void loadQuestions() {
        // 加载所有题目
        questionViewModel.getQuestions(new QuestionViewModel.GetQuestionsCallback() {
            @Override
            public void onSuccess(java.util.List<com.oilquiz.app.model.Question> questions) {
                if (questions == null) {
                    questions = new ArrayList<>();
                }
                
                // 如果数据库中没有题目，添加测试题目
                if (questions.isEmpty()) {
                    questions = getMockQuestions();
                }
                
                allQuestions = questions;
                filteredQuestions = new ArrayList<>(questions);
                updateQuestionList();
            }

            @Override
            public void onError(String error) {
                Toast.makeText(QuestionBankActivity.this, "加载题目失败: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    // 更新题目列表
    private void updateQuestionList() {
        if (questionListView != null) {
            // 确保filteredQuestions不为null
            if (filteredQuestions == null) {
                filteredQuestions = new ArrayList<>();
            }
            
            // 先清空RecyclerView，防止旧的ViewHolder残留导致崩溃
            questionListView.setAdapter(null);
            questionListView.removeAllViews();
            
            questionAdapter = new QuestionAdapter(QuestionBankActivity.this, filteredQuestions, isCardViewMode, false, new QuestionAdapter.OnQuestionClickListener() {
                @Override
                public void onQuestionClick(Question question) {
                }

                @Override
                public void onQuestionLongClick(Question question) {
                    if (question != null) {
                        showDeleteConfirmationDialog(question);
                    }
                }

                @Override
                public void onDeleteClick(Question question) {
                    if (question != null) {
                        showDeleteConfirmationDialog(question);
                    }
                }

                @Override
                public void onFavoriteClick(Question question, boolean isFavorited) {
                    if (question != null) {
                        questionViewModel.setQuestionFavorite(question.getId(), isFavorited, new QuestionViewModel.SetFavoriteCallback() {
                            @Override
                            public void onSuccess() {
                                Toast.makeText(QuestionBankActivity.this, isFavorited ? "已收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
                            }

                            @Override
                            public void onError(String error) {
                                Toast.makeText(QuestionBankActivity.this, "操作失败: " + error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }
            });
            questionListView.setLayoutManager(new LinearLayoutManager(QuestionBankActivity.this));
            questionListView.setAdapter(questionAdapter);
        }
        
        // 更新统计信息
        updateStatistics();
        // 更新视图模式按钮状态
        updateViewModeButtons();
    }
    
    // 更新统计信息
    private void updateStatistics() {
        if (allQuestions == null || allQuestions.isEmpty()) {
            if (totalCountTextView != null) {
                totalCountTextView.setText("总题数: 0");
            }
            if (favoriteCountTextView != null) {
                favoriteCountTextView.setText("收藏: 0");
            }
            if (categoryCountTextView != null) {
                categoryCountTextView.setText("分类: 0");
            }
            return;
        }
        
        int total = allQuestions.size();
        int favoriteCount = 0;
        int categoryCount = 0;

        for (Question question : allQuestions) {
            if (question != null) {
                if (question.isFavorite()) {
                    favoriteCount++;
                }
                if (question.getCategory() != null && !question.getCategory().isEmpty()) {
                    categoryCount++;
                }
            }
        }

        // 更新统计卡片
        if (totalCountTextView != null) {
            totalCountTextView.setText("总题数: " + total);
        }
        if (favoriteCountTextView != null) {
            favoriteCountTextView.setText("收藏: " + favoriteCount);
        }
        if (categoryCountTextView != null) {
            categoryCountTextView.setText("分类: " + categoryCount);
        }
    }

    // 生成测试题目
    private List<Question> getMockQuestions() {
        List<Question> questions = new ArrayList<>();

        // 单选题
        Question q1 = new Question();
        q1.setQuestionText("下列哪个是正确的？");
        q1.setOptionA("选项A");
        q1.setOptionB("选项B");
        q1.setOptionC("选项C");
        q1.setOptionD("选项D");
        q1.setQuestionType("单选题");
        q1.setDifficulty(2);
        q1.setCategory("测试分类");
        q1.setCorrectAnswer("A");
        questions.add(q1);

        // 多选题
        Question q2 = new Question();
        q2.setQuestionText("下列哪些是正确的？");
        q2.setOptionA("选项A");
        q2.setOptionB("选项B");
        q2.setOptionC("选项C");
        q2.setOptionD("选项D");
        q2.setQuestionType("多选题");
        q2.setDifficulty(2);
        q2.setCategory("测试分类");
        q2.setCorrectAnswer("AB");
        questions.add(q2);

        // 判断题
        Question q3 = new Question();
        q3.setQuestionText("这是一个判断题。");
        q3.setOptionA("正确");
        q3.setOptionB("错误");
        q3.setQuestionType("判断题");
        q3.setDifficulty(1);
        q3.setCategory("测试分类");
        q3.setCorrectAnswer("正确");
        questions.add(q3);

        // 填空题
        Question q4 = new Question();
        q4.setQuestionText("填空题：请填写答案");
        q4.setQuestionType("填空题");
        q4.setDifficulty(2);
        q4.setCategory("测试分类");
        q4.setCorrectAnswer("答案");
        questions.add(q4);

        // 简答题
        Question q5 = new Question();
        q5.setQuestionText("简答题：请简要回答");
        q5.setQuestionType("简答题");
        q5.setDifficulty(3);
        q5.setCategory("测试分类");
        q5.setCorrectAnswer("这是简答题的答案");
        questions.add(q5);

        return questions;
    }



    private void showDeleteConfirmationDialog(final Question question) {
        new AlertDialog.Builder(this)
                .setTitle("删除题目")
                .setMessage("确定要删除这道题目吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    questionViewModel.deleteQuestion(question.getId(), new QuestionViewModel.DeleteQuestionCallback() {
                        @Override
                        public void onSuccess() {
                            Toast.makeText(QuestionBankActivity.this, "删除成功", Toast.LENGTH_SHORT).show();
                            loadQuestions(); // 重新加载题目列表
                        }

                        @Override
                        public void onError(String error) {
                            Toast.makeText(QuestionBankActivity.this, "删除失败: " + error, Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void importQuestions() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_PICK_FILE);
    }

    private void exportQuestions() {
        // 显示导出选项对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("导出题目");
        builder.setItems(new String[]{"导出为Excel", "导出为PDF", "导出为Word", "导出为HTML", "导出为Markdown"}, (dialog, which) -> {
            // 显示进度对话框
            AlertDialog progressDialog = new AlertDialog.Builder(QuestionBankActivity.this)
                    .setTitle("导出中")
                    .setMessage("正在准备导出，请稍候...")
                    .setCancelable(false)
                    .create();
            progressDialog.show();
            
            questionViewModel.getQuestions(new QuestionViewModel.GetQuestionsCallback() {
                @Override
                public void onSuccess(java.util.List<com.oilquiz.app.model.Question> questions) {
                    if (questions.isEmpty()) {
                        progressDialog.dismiss();
                        Toast.makeText(QuestionBankActivity.this, "没有题目可以导出", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 在后台线程执行导出
                    new Thread(() -> {
                        try {
                            File file = null;
                            String exportType = "";
                            switch (which) {
                                case 0: // Excel
                                    file = exportManager.exportToExcel(QuestionBankActivity.this, questions);
                                    exportType = "Excel";
                                    break;
                                case 1: // PDF
                                    file = exportManager.exportToPDF(QuestionBankActivity.this, questions);
                                    exportType = "PDF";
                                    break;
                                case 2: // Word
                                    file = exportManager.exportToWord(QuestionBankActivity.this, questions);
                                    exportType = "Word";
                                    break;
                                case 3: // HTML
                                    file = exportManager.exportToHTML(QuestionBankActivity.this, questions);
                                    exportType = "HTML";
                                    break;
                                case 4: // Markdown
                                    file = exportManager.exportToMarkdown(QuestionBankActivity.this, questions);
                                    exportType = "Markdown";
                                    break;
                            }
                            
                            final File finalFile = file;
                            final String finalExportType = exportType;
                            
                            runOnUiThread(() -> {
                                progressDialog.dismiss();
                                if (finalFile != null && finalFile.exists()) {
                                    showExportSuccessDialog(finalFile, finalExportType);
                                } else {
                                    Toast.makeText(QuestionBankActivity.this, "导出失败: 无法创建文件", Toast.LENGTH_SHORT).show();
                                }
                            });
                        } catch (Exception e) {
                            e.printStackTrace();
                            runOnUiThread(() -> {
                                progressDialog.dismiss();
                                Toast.makeText(QuestionBankActivity.this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            });
                        }
                    }).start();
                }

                @Override
                public void onError(String error) {
                    progressDialog.dismiss();
                    Toast.makeText(QuestionBankActivity.this, "获取题目失败: " + error, Toast.LENGTH_SHORT).show();
                }
            });
        });
        builder.show();
    }
    
    // 显示导出成功对话框
    private void showExportSuccessDialog(File file, String exportType) {
        new AlertDialog.Builder(this)
                .setTitle("导出成功")
                .setMessage("文件类型: " + exportType + "\n文件路径: " + file.getAbsolutePath() + "\n文件大小: " + (file.length() / 1024) + " KB")
                .setPositiveButton("查看文件", (dialog, which) -> openExportFile(file))
                .setNegativeButton("分享文件", (dialog, which) -> shareExportFile(file))
                .setNeutralButton("确定", null)
                .show();
    }
    
    // 打开导出的文件
    private void openExportFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri;
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                uri = androidx.core.content.FileProvider.getUriForFile(
                        this,
                        "com.oilquiz.app.fileprovider",
                        file
                );
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(file);
            }
            
            String mimeType = getMimeType(file.getAbsolutePath());
            intent.setDataAndType(uri, mimeType);
            
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(Intent.createChooser(intent, "选择打开方式"));
            } else {
                Toast.makeText(this, "没有找到可以打开此文件的应用", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    // 分享导出的文件
    private void shareExportFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            Uri uri;
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                uri = androidx.core.content.FileProvider.getUriForFile(
                        this,
                        "com.oilquiz.app.fileprovider",
                        file
                );
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                uri = Uri.fromFile(file);
            }
            
            String mimeType = getMimeType(file.getAbsolutePath());
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, "导出文件");
            intent.putExtra(Intent.EXTRA_TEXT, "这是从OilQuiz应用导出的文件：" + file.getName());
            
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(Intent.createChooser(intent, "分享文件"));
            } else {
                Toast.makeText(this, "没有找到可以分享此文件的应用", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "无法分享文件: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    // 获取文件MIME类型
    private String getMimeType(String filePath) {
        String extension = filePath.substring(filePath.lastIndexOf(".") + 1).toLowerCase();
        switch (extension) {
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pdf":
                return "application/pdf";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "html":
                return "text/html";
            case "md":
                return "text/markdown";
            default:
                return "application/octet-stream";
        }
    }
    
    // 批量删除题目
    private void batchDeleteQuestions() {
        if (filteredQuestions == null || filteredQuestions.isEmpty()) {
            Toast.makeText(this, "没有题目可以删除", Toast.LENGTH_SHORT).show();
            return;
        }
        
        new AlertDialog.Builder(this)
                .setTitle("批量删除")
                .setMessage("确定要删除所有筛选后的题目吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    try {
                        int deleteCount = 0;
                        for (Question question : filteredQuestions) {
                            if (question != null) {
                                questionViewModel.deleteQuestion(question.getId(), new QuestionViewModel.DeleteQuestionCallback() {
                                    @Override
                                    public void onSuccess() {
                                        // 可以添加成功回调处理
                                    }

                                    @Override
                                    public void onError(String error) {
                                        // 可以添加错误回调处理
                                    }
                                });
                                deleteCount++;
                            }
                        }
                        Toast.makeText(this, "删除成功，共删除 " + deleteCount + " 道题目", Toast.LENGTH_SHORT).show();
                        loadQuestions(); // 重新加载题目列表
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "删除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    // 批量收藏题目
    private void batchFavoriteQuestions() {
        if (filteredQuestions == null || filteredQuestions.isEmpty()) {
            Toast.makeText(this, "没有题目可以收藏", Toast.LENGTH_SHORT).show();
            return;
        }
        
        new AlertDialog.Builder(this)
                .setTitle("批量收藏")
                .setMessage("确定要收藏所有筛选后的题目吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    try {
                        int favoriteCount = 0;
                        for (Question question : filteredQuestions) {
                            if (question != null) {
                                questionViewModel.setQuestionFavorite(question.getId(), true);
                                favoriteCount++;
                            }
                        }
                        Toast.makeText(this, "收藏成功，共收藏 " + favoriteCount + " 道题目", Toast.LENGTH_SHORT).show();
                        loadQuestions(); // 重新加载题目列表
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "收藏失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    // 生成题目模板
    private void generateQuestionTemplate() {
        try {
            if (exportManager != null) {
                File templateFile = exportManager.generateExcelTemplate(this);
                if (templateFile != null && templateFile.exists()) {
                    Toast.makeText(this, "模板生成成功，文件保存在: " + templateFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "生成模板失败: 无法创建文件", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "生成模板失败: 导出管理器未初始化", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "生成模板失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FILE && resultCode == RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    // 处理导入逻辑
                    importQuestionsFromFile(uri);
                }
            }
        }
    }

    private void importQuestionsFromFile(Uri uri) {
        if (uri == null) {
            Toast.makeText(this, "文件Uri为空", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            // 从Uri获取文件路径
            String filePath = uri.getPath();
            if (filePath == null) {
                Toast.makeText(this, "无法获取文件路径", Toast.LENGTH_SHORT).show();
                return;
            }
            
            File file = new File(filePath);
            if (!file.exists()) {
                Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show();
                return;
            }
            
            Toast.makeText(this, "开始导入题目...", Toast.LENGTH_SHORT).show();
            
            // 使用ExcelUtil进行智能导入
            ExcelUtil.smartImport(file, new ExcelUtil.ImportCallback() {
                @Override
                public void onProgress(int current, int total) {
                    runOnUiThread(() -> {
                        // 可以在这里显示导入进度
                        Toast.makeText(QuestionBankActivity.this, "导入进度: " + current + "/" + total, Toast.LENGTH_SHORT).show();
                    });
                }
                
                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        Toast.makeText(QuestionBankActivity.this, "导入失败: " + message, Toast.LENGTH_SHORT).show();
                    });
                }
                
                @Override
                public void onComplete(java.util.List<Question> importedQuestions, ExcelUtil.ImportResult result) {
                    runOnUiThread(() -> {
                        // 保存导入的题目到数据库
                        if (importedQuestions != null) {
                            for (Question question : importedQuestions) {
                                if (question != null) {
                                    questionViewModel.insertQuestion(question);
                                }
                            }
                            
                            Toast.makeText(QuestionBankActivity.this, "导入成功! 导入了 " + result.validQuestions + " 道题目", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(QuestionBankActivity.this, "导入失败: 没有有效的题目", Toast.LENGTH_SHORT).show();
                        }
                        loadQuestions(); // 重新加载题目列表
                    });
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static final int ACTION_TOGGLE_VIEW_MODE = 1001; // 临时菜单项ID

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.question_bank_menu, menu);
        // 添加切换视图模式的菜单项
        MenuItem viewModeItem = menu.add(Menu.NONE, ACTION_TOGGLE_VIEW_MODE, Menu.NONE, isCardViewMode ? "切换到列表视图" : "切换到卡片视图");
        viewModeItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_add_question) {
            Toast.makeText(QuestionBankActivity.this, "添加题目功能已移除", Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.action_import_questions) {
            importQuestions();
            return true;
        } else if (id == R.id.action_export_questions) {
            exportQuestions();
            return true;
        } else if (id == R.id.action_batch_delete) {
            batchDeleteQuestions();
            return true;
        } else if (id == R.id.action_batch_favorite) {
            batchFavoriteQuestions();
            return true;
        } else if (id == R.id.action_generate_template) {
            generateQuestionTemplate();
            return true;
        } else if (id == R.id.action_refresh) {
            loadQuestions();
            return true;
        } else if (id == R.id.action_clear_all) {
            clearAllQuestions();
            return true;
        } else if (id == ACTION_TOGGLE_VIEW_MODE) {
            toggleViewMode();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // 切换视图模式
    private void toggleViewMode() {
        isCardViewMode = !isCardViewMode;
        // 重新加载题目以应用新的视图模式
        loadQuestions();
        // 重新创建菜单以更新菜单项文本
        invalidateOptionsMenu();
    }

    // 清空所有题目
    private void clearAllQuestions() {
        new AlertDialog.Builder(this)
                .setTitle("清空数据库")
                .setMessage("确定要清空数据库中的所有题目吗？此操作不可恢复！")
                .setPositiveButton("确定", (dialog, which) -> {
                    try {
                        questionViewModel.clearAllQuestions(new QuestionViewModel.BatchOperationCallback() {
                            @Override
                            public void onSuccess(int count) {
                                Toast.makeText(QuestionBankActivity.this, "数据库已清空，共删除 " + count + " 道题目", Toast.LENGTH_SHORT).show();
                                loadQuestions(); // 重新加载题目列表
                            }

                            @Override
                            public void onError(String error) {
                                Toast.makeText(QuestionBankActivity.this, "清空失败: " + error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(this, "清空失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadQuestions();
    }
}
