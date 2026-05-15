package com.oilquiz.app.repository;

import android.app.Application;
import com.oilquiz.app.database.AppDatabase;
import com.oilquiz.app.database.StudyPlanDao;
import com.oilquiz.app.model.StudyPlan;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StudyPlanRepository {

    private StudyPlanDao studyPlanDao;
    private ExecutorService executorService;

    public StudyPlanRepository(Application application) {
        AppDatabase db = AppDatabase.getDatabase(application);
        studyPlanDao = db.studyPlanDao();
        executorService = Executors.newSingleThreadExecutor();
    }

    // 回调接口
    public interface StudyPlanRepositoryCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    // 获取所有学习计划
    public void getStudyPlans(StudyPlanRepositoryCallback<List<StudyPlan>> callback) {
        executorService.execute(() -> {
            try {
                System.out.println("开始加载学习计划");
                List<StudyPlan> studyPlans = studyPlanDao.getStudyPlans();
                System.out.println("加载到学习计划数量: " + studyPlans.size());
                for (StudyPlan plan : studyPlans) {
                    System.out.println("  - " + plan.getPlanName() + " (ID: " + plan.getId() + ")");
                }
                // 确保返回的是完整的列表
                if (studyPlans == null) {
                    System.out.println("学习计划列表为null，创建空列表");
                    studyPlans = new ArrayList<>();
                }
                System.out.println("准备返回学习计划数量: " + studyPlans.size());
                callback.onSuccess(studyPlans);
            } catch (Exception e) {
                System.out.println("加载学习计划失败: " + e.getMessage());
                e.printStackTrace();
                callback.onError(e.getMessage());
            }
        });
    }

    // 添加学习计划
    public void addStudyPlan(String planName, int targetQuestions, StudyPlanRepositoryCallback<Long> callback) {
        executorService.execute(() -> {
            try {
                long currentTime = System.currentTimeMillis();
                StudyPlan studyPlan = new StudyPlan(
                        0,  // id will be auto-generated
                        planName,
                        targetQuestions,
                        0,  // completedQuestions
                        currentTime,
                        currentTime,  // endDate same as startDate initially
                        1,  // default userId
                        "active"
                );
                System.out.println("准备插入学习计划: " + planName);
                long id = studyPlanDao.insert(studyPlan);
                System.out.println("学习计划插入成功，ID: " + id);
                // 插入后立即查询验证
                List<StudyPlan> plans = studyPlanDao.getStudyPlans();
                System.out.println("插入后数据库中的学习计划数量: " + plans.size());
                for (StudyPlan p : plans) {
                    System.out.println("  - " + p.getPlanName() + " (ID: " + p.getId() + ")");
                }
                callback.onSuccess(id);
            } catch (Exception e) {
                System.out.println("插入学习计划失败: " + e.getMessage());
                e.printStackTrace();
                callback.onError(e.getMessage());
            }
        });
    }

    // 更新学习计划
    public void updateStudyPlan(long id, String planName, int targetQuestions, StudyPlanRepositoryCallback<Void> callback) {
        executorService.execute(() -> {
            try {
                StudyPlan studyPlan = studyPlanDao.getStudyPlanById(id);
                if (studyPlan != null) {
                    studyPlan.setPlanName(planName);
                    studyPlan.setTargetQuestions(targetQuestions);
                    studyPlan.setEndDate(System.currentTimeMillis());
                    studyPlanDao.update(studyPlan);
                }
                callback.onSuccess(null);
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }

    // 删除学习计划
    public void deleteStudyPlan(long id, StudyPlanRepositoryCallback<Void> callback) {
        executorService.execute(() -> {
            try {
                studyPlanDao.deleteStudyPlan(id);
                callback.onSuccess(null);
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }

    // 重置学习计划进度
    public void resetPlanProgress(long id, StudyPlanRepositoryCallback<Void> callback) {
        executorService.execute(() -> {
            try {
                StudyPlan studyPlan = studyPlanDao.getStudyPlanById(id);
                if (studyPlan != null) {
                    studyPlan.setCompletedQuestions(0);
                    studyPlan.setEndDate(System.currentTimeMillis());
                    studyPlanDao.update(studyPlan);
                }
                callback.onSuccess(null);
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }

    // 增加已完成题目数
    public void incrementCompletedQuestions(long id, StudyPlanRepositoryCallback<Void> callback) {
        executorService.execute(() -> {
            try {
                StudyPlan studyPlan = studyPlanDao.getStudyPlanById(id);
                if (studyPlan != null) {
                    studyPlan.setCompletedQuestions(studyPlan.getCompletedQuestions() + 1);
                    studyPlan.setEndDate(System.currentTimeMillis());
                    studyPlanDao.update(studyPlan);
                }
                callback.onSuccess(null);
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }
}