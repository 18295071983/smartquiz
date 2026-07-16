package com.oilquiz.app.viewmodel;

import android.app.Application;
import androidx.lifecycle.AndroidViewModel;
import com.oilquiz.app.model.StudyPlan;
import com.oilquiz.app.repository.StudyPlanRepository;
import java.util.List;

public class StudyPlanViewModel extends AndroidViewModel {

    private StudyPlanRepository studyPlanRepository;

    public StudyPlanViewModel(Application application) {
        super(application);
    }

    public void init(Application application) {
        if (studyPlanRepository == null) {
            studyPlanRepository = new StudyPlanRepository(application);
        }
    }

    // 回调接口
    public interface StudyPlanCallback<T> {
        void onSuccess(T result);
        void onError(String error);
    }

    // 获取所有学习计划
    public void getStudyPlans(StudyPlanCallback<List<StudyPlan>> callback) {
        studyPlanRepository.getStudyPlans(new StudyPlanRepository.StudyPlanRepositoryCallback<List<StudyPlan>>() {
            @Override
            public void onSuccess(List<StudyPlan> result) {
                callback.onSuccess(result);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    // 添加学习计划
    public void addStudyPlan(String planName, int targetQuestions, StudyPlanCallback<Long> callback) {
        studyPlanRepository.addStudyPlan(planName, targetQuestions, new StudyPlanRepository.StudyPlanRepositoryCallback<Long>() {
            @Override
            public void onSuccess(Long result) {
                callback.onSuccess(result);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    // 更新学习计划
    public void updateStudyPlan(long id, String planName, int targetQuestions, StudyPlanCallback<Void> callback) {
        studyPlanRepository.updateStudyPlan(id, planName, targetQuestions, new StudyPlanRepository.StudyPlanRepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                callback.onSuccess(null);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    // 删除学习计划
    public void deleteStudyPlan(long id, StudyPlanCallback<Void> callback) {
        studyPlanRepository.deleteStudyPlan(id, new StudyPlanRepository.StudyPlanRepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                callback.onSuccess(null);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    // 重置学习计划进度
    public void resetPlanProgress(long id, StudyPlanCallback<Void> callback) {
        studyPlanRepository.resetPlanProgress(id, new StudyPlanRepository.StudyPlanRepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                callback.onSuccess(null);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    // 增加已完成题目数
    public void incrementCompletedQuestions(long id, StudyPlanCallback<Void> callback) {
        studyPlanRepository.incrementCompletedQuestions(id, new StudyPlanRepository.StudyPlanRepositoryCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
                callback.onSuccess(null);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }
}