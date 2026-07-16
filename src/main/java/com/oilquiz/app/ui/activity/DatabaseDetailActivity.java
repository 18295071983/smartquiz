package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.oilquiz.app.R;
import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.database.UserDao;
import com.oilquiz.app.database.QuestionDao;
import com.oilquiz.app.database.StudyPlanDao;
import com.oilquiz.app.database.NoteDao;
import com.oilquiz.app.database.WrongQuestionDao;
import com.oilquiz.app.model.User;
import com.oilquiz.app.model.Question;
import com.oilquiz.app.model.StudyPlan;
import com.oilquiz.app.model.Note;
import com.oilquiz.app.model.WrongQuestion;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DatabaseDetailActivity extends AppCompatActivity {

    private TextView tvDatabaseStatus;
    private TextView tvDatabaseVersion;
    private TextView tvUserCount;
    private TextView tvQuestionCount;
    private TextView tvStudyPlanCount;
    private TextView tvNoteCount;
    private TextView tvWrongQuestionCount;
    private TextView tvDatabasePath;

    private ExecutorService executorService;
    private boolean isActivityDestroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_database_detail);

        setTitle("数据库详情");

        initViews();
        executorService = Executors.newSingleThreadExecutor();

        loadDatabaseDetails();
    }

    private void initViews() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }

        tvDatabaseStatus = findViewById(R.id.tv_database_status);
        tvDatabaseVersion = findViewById(R.id.tv_database_version);
        tvUserCount = findViewById(R.id.tv_user_count);
        tvQuestionCount = findViewById(R.id.tv_question_count);
        tvStudyPlanCount = findViewById(R.id.tv_study_plan_count);
        tvNoteCount = findViewById(R.id.tv_note_count);
        tvWrongQuestionCount = findViewById(R.id.tv_wrong_question_count);
        tvDatabasePath = findViewById(R.id.tv_database_path);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean isValid() {
        return !isActivityDestroyed && !isFinishing();
    }

    private void loadDatabaseDetails() {
        // 先显示加载中状态
        if (isValid()) {
            runOnUiThread(() -> {
                if (isValid()) {
                    tvDatabaseStatus.setText("数据库状态：加载中...");
                }
            });
        }

        executorService.execute(() -> {
            try {
                // 检查数据库初始化状态
                final AppDatabase database = AppDatabase.getDatabase(this);
                
                // 检查数据库是否为空
                if (database == null) {
                    if (isValid()) {
                        runOnUiThread(() -> {
                            if (isValid()) {
                                tvDatabaseStatus.setText("数据库状态：未初始化");
                                Toast.makeText(DatabaseDetailActivity.this, "数据库未初始化", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    return;
                }

                // 获取所有数据
                UserDao userDao = database.userDao();
                List<User> users = userDao.getAllUsers();
                int userCount = users != null ? users.size() : 0;

                QuestionDao questionDao = database.questionDao();
                List<Question> questions = questionDao.getQuestions();
                int questionCount = questions != null ? questions.size() : 0;

                StudyPlanDao studyPlanDao = database.studyPlanDao();
                List<StudyPlan> studyPlans = studyPlanDao.getStudyPlans();
                int studyPlanCount = studyPlans != null ? studyPlans.size() : 0;

                NoteDao noteDao = database.noteDao();
                List<Note> notes = noteDao.getNotes();
                int noteCount = notes != null ? notes.size() : 0;

                WrongQuestionDao wrongQuestionDao = database.wrongQuestionDao();
                List<WrongQuestion> wrongQuestions = wrongQuestionDao.getWrongQuestions();
                int wrongQuestionCount = wrongQuestions != null ? wrongQuestions.size() : 0;

                // 获取数据库路径
                String databasePath = getDatabasePath("smartquiz_database").getAbsolutePath();

                // 获取数据库版本 - 使用常量而不是从数据库获取
                int version = AppDatabase.DATABASE_VERSION;

                // 一次性更新所有UI
                if (isValid()) {
                    runOnUiThread(() -> {
                        if (isValid()) {
                            tvDatabaseStatus.setText("数据库状态：正常");
                            tvDatabaseVersion.setText("数据库版本：" + version);
                            tvDatabasePath.setText("数据库路径：" + databasePath);
                            tvUserCount.setText("用户数量：" + userCount);
                            tvQuestionCount.setText("题目数量：" + questionCount);
                            tvStudyPlanCount.setText("学习计划数量：" + studyPlanCount);
                            tvNoteCount.setText("笔记数量：" + noteCount);
                            tvWrongQuestionCount.setText("错题数量：" + wrongQuestionCount);
                        }
                    });
                }

            } catch (Exception e) {
                e.printStackTrace();
                if (isValid()) {
                    runOnUiThread(() -> {
                        if (isValid()) {
                            tvDatabaseStatus.setText("数据库状态：异常");
                            Toast.makeText(DatabaseDetailActivity.this, "加载数据库详情失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        isActivityDestroyed = true;
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }
}
