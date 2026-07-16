package com.oilquiz.app.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.oilquiz.app.model.Template;

import java.util.List;

@Dao
public interface TemplateDao {
    @Insert
    long insert(Template template);

    @Update
    void update(Template template);

    @Query("DELETE FROM template WHERE id = :id")
    void deleteTemplate(long id);

    @Query("DELETE FROM template")
    void deleteAllTemplates();

    @Query("SELECT * FROM template WHERE id = :id")
    Template getTemplateById(long id);

    @Query("SELECT * FROM template")
    List<Template> getTemplates();

    @Query("SELECT * FROM template WHERE name LIKE '%' || :keyword || '%'")
    List<Template> searchTemplates(String keyword);

    @Query("SELECT COUNT(*) FROM template")
    int getTemplateCount();
}