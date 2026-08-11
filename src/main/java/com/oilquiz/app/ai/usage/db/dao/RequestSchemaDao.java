package com.oilquiz.app.ai.usage.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.oilquiz.app.ai.usage.db.entity.ApiRequestSchema;

import java.util.List;

@Dao
public interface RequestSchemaDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertSchema(ApiRequestSchema schema);
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAllSchemas(List<ApiRequestSchema> schemas);
    
    @Query("SELECT * FROM api_request_schema WHERE modelId = :modelId AND providerId = :providerId LIMIT 1")
    ApiRequestSchema findBy(String modelId, String providerId);
    
    @Query("SELECT * FROM api_request_schema WHERE modelId = '' AND providerId = :providerId LIMIT 1")
    ApiRequestSchema findByGeneric(String providerId);
    
    @Query("SELECT * FROM api_request_schema")
    List<ApiRequestSchema> findAll();
    
    @Query("DELETE FROM api_request_schema")
    void clearAll();
}
