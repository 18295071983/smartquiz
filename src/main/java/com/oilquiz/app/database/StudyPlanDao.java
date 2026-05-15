package com.oilquiz.app.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.oilquiz.app.model.StudyPlan;

import java.util.List;

@Dao
public interface StudyPlanDao {
    @Insert
    long insert(StudyPlan studyPlan);

    @Update
    void update(StudyPlan studyPlan);

    @Query("DELETE FROM study_plan WHERE id = :id")
    void deleteStudyPlan(long id);

    @Query("SELECT * FROM study_plan WHERE id = :id")
    StudyPlan getStudyPlanById(long id);

    @Query("SELECT * FROM study_plan WHERE userId = :userId")
    List<StudyPlan> getStudyPlansByUserId(long userId);

    @Query("SELECT * FROM study_plan WHERE status = :status")
    List<StudyPlan> getStudyPlansByStatus(String status);

    @Query("SELECT * FROM study_plan")
    List<StudyPlan> getStudyPlans();
}
