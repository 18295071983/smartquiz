package com.oilquiz.app.ai.usage.db.dao;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

import com.oilquiz.app.ai.usage.db.entity.ModelEntity;

/**
 * Model 表的 DAO 操作接口
 */
@Dao
public interface ModelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ModelEntity entity);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    List<Long> insertAll(List<ModelEntity> entities);

    @Update
    int update(ModelEntity entity);

    @Delete
    int delete(ModelEntity entity);

    @Query("SELECT * FROM models ORDER BY modelId ASC")
    List<ModelEntity> getAll();

    @Query("SELECT * FROM models WHERE providerId = :providerId ORDER BY modelId ASC")
    List<ModelEntity> getAllByProviderId(String providerId);

    @Query("SELECT * FROM models WHERE modelId = :modelId LIMIT 1")
    ModelEntity getByModelId(String modelId);

    @Query("DELETE FROM models WHERE providerId = :providerId")
    int deleteAllByProviderId(String providerId);

    @Query("DELETE FROM models")
    int deleteAll();

    @Query("SELECT COUNT(*) FROM models")
    int count();
}
