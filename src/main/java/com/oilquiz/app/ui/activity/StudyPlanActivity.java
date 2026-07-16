package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;
import android.widget.LinearLayout;
import android.text.InputType;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.snackbar.Snackbar;

import com.oilquiz.app.R;
import com.oilquiz.app.model.StudyPlan;
import com.oilquiz.app.ui.adapter.StudyPlanAdapter;
import com.oilquiz.app.viewmodel.StudyPlanViewModel;

import java.util.List;
import java.util.ArrayList;

public class StudyPlanActivity extends AppCompatActivity implements StudyPlanAdapter.OnStudyPlanClickListener {

    private StudyPlanViewModel studyPlanViewModel;
    private ListView studyPlanListView;
    private EditText planNameEditText;
    private EditText targetQuestionsEditText;
    private Button addPlanButton;
    private StudyPlanAdapter studyPlanAdapter;
    private List<StudyPlan> studyPlansList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_study_plan);

            initViews();
            setupViewModel();
            setupListeners();
            
            // 加载学习计划
            loadStudyPlans();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void initViews() {
        try {
            setTitle("学习计划");
            
            studyPlanListView = findViewById(R.id.lv_study_plans);
            planNameEditText = findViewById(R.id.et_plan_name);
            targetQuestionsEditText = findViewById(R.id.et_target_questions);
            addPlanButton = findViewById(R.id.btn_add_plan);

            studyPlansList = new ArrayList<StudyPlan>();
            studyPlanAdapter = new StudyPlanAdapter(this, studyPlansList, this);
            studyPlanListView.setAdapter(studyPlanAdapter);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "初始化视图失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setupViewModel() {
        try {
            studyPlanViewModel = new ViewModelProvider(this).get(StudyPlanViewModel.class);
            studyPlanViewModel.init(getApplication());
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "初始化ViewModel失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void setupListeners() {
        try {
            // 添加计划按钮
            addPlanButton.setOnClickListener(v -> addStudyPlan());
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "设置监听器失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadStudyPlans() {
        System.out.println("开始加载学习计划");
        studyPlanViewModel.getStudyPlans(new StudyPlanViewModel.StudyPlanCallback<List<StudyPlan>>() {
            @Override
            public void onSuccess(List<StudyPlan> studyPlans) {
                System.out.println("加载学习计划成功，数量: " + (studyPlans != null ? studyPlans.size() : 0));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (studyPlans != null) {
                            System.out.println("处理学习计划列表，大小: " + studyPlans.size());
                            
                            // 构建详细信息
                            StringBuilder plansInfo = new StringBuilder("学习计划详情：");
                            for (int i = 0; i < studyPlans.size(); i++) {
                                try {
                                    StudyPlan plan = studyPlans.get(i);
                                    if (plan != null) {
                                        plansInfo.append("\n").append(i+1).append(". ")
                                                .append(plan.getPlanName()).append(" (ID: ")
                                                .append(plan.getId()).append(")");
                                        System.out.println("处理学习计划 " + (i+1) + ": " + plan.getPlanName());
                                    } else {
                                        plansInfo.append("\n").append(i+1).append(". 空学习计划");
                                        System.out.println("处理学习计划 " + (i+1) + ": 空学习计划");
                                    }
                                } catch (Exception e) {
                                    plansInfo.append("\n").append(i+1).append(". 处理失败: " + e.getMessage());
                                    System.out.println("处理学习计划 " + (i+1) + " 失败: " + e.getMessage());
                                    e.printStackTrace();
                                }
                            }
                            System.out.println(plansInfo.toString());
                            
                            // 使用适配器的updateData方法更新数据
                            studyPlanAdapter.updateData(studyPlans);
                            System.out.println("适配器数据更新完成");
                            
                            // 验证ListView的状态
                            System.out.println("ListView是否为空: " + (studyPlanListView.getAdapter() == null));
                            if (studyPlanListView.getAdapter() != null) {
                                System.out.println("ListView适配器数据大小: " + studyPlanListView.getAdapter().getCount());
                            }
                            
                            // 显示最终结果
                            Toast.makeText(StudyPlanActivity.this, "显示 " + studyPlansList.size() + " 个学习计划", Toast.LENGTH_SHORT).show();
                        } else {
                            System.out.println("学习计划列表为null");
                            Toast.makeText(StudyPlanActivity.this, "学习计划列表为null", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }

            @Override
            public void onError(String error) {
                System.out.println("加载学习计划失败: " + error);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(StudyPlanActivity.this, "加载学习计划失败: " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void addStudyPlan() {
        String planName = planNameEditText.getText().toString().trim();
        String targetQuestionsStr = targetQuestionsEditText.getText().toString().trim();

        if (planName.isEmpty()) {
            Toast.makeText(this, "请输入计划名称", Toast.LENGTH_SHORT).show();
            return;
        }

        if (targetQuestionsStr.isEmpty()) {
            Toast.makeText(this, "请输入目标题目数", Toast.LENGTH_SHORT).show();
            return;
        }

        int targetQuestions;
        try {
            targetQuestions = Integer.parseInt(targetQuestionsStr);
            if (targetQuestions <= 0) {
                Toast.makeText(this, "目标题目数必须大于0", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
            return;
        }

        studyPlanViewModel.addStudyPlan(planName, targetQuestions, new StudyPlanViewModel.StudyPlanCallback<Long>() {
            @Override
            public void onSuccess(Long result) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        planNameEditText.setText("");
                        targetQuestionsEditText.setText("");
                        Snackbar.make(studyPlanListView, "学习计划创建成功", Snackbar.LENGTH_SHORT).show();
                        loadStudyPlans();
                    }
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(StudyPlanActivity.this, "创建学习计划失败: " + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void showPlanDetails(StudyPlan plan) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        double progress = plan.getTargetQuestions() > 0 ? (double) plan.getCompletedQuestions() / plan.getTargetQuestions() : 0;
        builder.setTitle(plan.getPlanName())
                .setMessage(String.format(
                        "目标题目数: %d\n" +
                        "已完成题目数: %d\n" +
                        "进度: %.1f%%\n" +
                        "开始时间: %s\n" +
                        "结束时间: %s",
                        plan.getTargetQuestions(),
                        plan.getCompletedQuestions(),
                        progress * 100,
                        formatDate(plan.getStartDate()),
                        formatDate(plan.getEndDate())))
                .setPositiveButton("开始学习", (dialog, which) -> {
                    Intent intent = new Intent(this, QuizActivity.class);
                    intent.putExtra(QuizActivity.EXTRA_QUIZ_MODE, "practice");
                    intent.putExtra(QuizActivity.EXTRA_QUESTION_COUNT, plan.getTargetQuestions() - plan.getCompletedQuestions());
                    startActivity(intent);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showPlanOptions(StudyPlan plan) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("学习计划选项")
                .setItems(new String[]{"查看详情", "编辑计划", "删除计划", "重置进度"}, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showPlanDetails(plan);
                            break;
                        case 1:
                            editStudyPlan(plan);
                            break;
                        case 2:
                            deleteStudyPlan(plan);
                            break;
                        case 3:
                            resetPlanProgress(plan);
                            break;
                    }
                })
                .show();
    }

    private void editStudyPlan(StudyPlan plan) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("编辑学习计划");
        
        // 创建线性布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        
        // 添加计划名称输入框
        final EditText editTextPlanName = new EditText(this);
        editTextPlanName.setHint("计划名称");
        editTextPlanName.setText(plan.getPlanName());
        LinearLayout.LayoutParams params1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params1.setMargins(0, 0, 0, 16);
        editTextPlanName.setLayoutParams(params1);
        layout.addView(editTextPlanName);
        
        // 添加目标题目数输入框
        final EditText editTextTargetQuestions = new EditText(this);
        editTextTargetQuestions.setHint("目标题目数");
        editTextTargetQuestions.setText(String.valueOf(plan.getTargetQuestions()));
        editTextTargetQuestions.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams params2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        editTextTargetQuestions.setLayoutParams(params2);
        layout.addView(editTextTargetQuestions);
        
        builder.setView(layout)
                .setPositiveButton("保存", (dialog, which) -> {
                    String newName = editTextPlanName.getText().toString().trim();
                    String newTargetStr = editTextTargetQuestions.getText().toString().trim();
                    
                    if (newName.isEmpty()) {
                        Toast.makeText(this, "请输入计划名称", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    int newTarget;
                    try {
                        newTarget = Integer.parseInt(newTargetStr);
                        if (newTarget <= 0) {
                            Toast.makeText(this, "目标题目数必须大于0", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    studyPlanViewModel.updateStudyPlan(plan.getId(), newName, newTarget, new StudyPlanViewModel.StudyPlanCallback<Void>() {
                        @Override
                        public void onSuccess(Void result) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Snackbar.make(studyPlanListView, "学习计划更新成功", Snackbar.LENGTH_SHORT).show();
                                    loadStudyPlans();
                                }
                            });
                        }

                        @Override
                        public void onError(String error) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(StudyPlanActivity.this, "更新学习计划失败: " + error, Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteStudyPlan(StudyPlan plan) {
        new AlertDialog.Builder(this)
                .setTitle("删除学习计划")
                .setMessage("确定要删除这个学习计划吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    studyPlanViewModel.deleteStudyPlan(plan.getId(), new StudyPlanViewModel.StudyPlanCallback<Void>() {
                        @Override
                        public void onSuccess(Void result) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Snackbar.make(studyPlanListView, "学习计划删除成功", Snackbar.LENGTH_SHORT).show();
                                    loadStudyPlans();
                                }
                            });
                        }

                        @Override
                        public void onError(String error) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(StudyPlanActivity.this, "删除学习计划失败: " + error, Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void resetPlanProgress(StudyPlan plan) {
        new AlertDialog.Builder(this)
                .setTitle("重置学习进度")
                .setMessage("确定要重置这个学习计划的进度吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    studyPlanViewModel.resetPlanProgress(plan.getId(), new StudyPlanViewModel.StudyPlanCallback<Void>() {
                        @Override
                        public void onSuccess(Void result) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Snackbar.make(studyPlanListView, "学习进度重置成功", Snackbar.LENGTH_SHORT).show();
                                    loadStudyPlans();
                                }
                            });
                        }

                        @Override
                        public void onError(String error) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(StudyPlanActivity.this, "重置学习进度失败: " + error, Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String formatDate(long timestamp) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        return sdf.format(new java.util.Date(timestamp));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 注释掉onResume中的loadStudyPlans()调用，避免重复加载
        // loadStudyPlans();
    }

    @Override
    public void onStudyPlanClick(StudyPlan plan) {
        showPlanDetails(plan);
    }

    @Override
    public void onEditClick(StudyPlan plan) {
        editStudyPlan(plan);
    }

    @Override
    public void onDeleteClick(StudyPlan plan) {
        deleteStudyPlan(plan);
    }

    @Override
    public void onResetClick(StudyPlan plan) {
        resetPlanProgress(plan);
    }
}