package com.oilquiz.app.ai.usage.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.oilquiz.app.ai.usage.db.entity.ApiResponseSchema;

import java.util.List;

@Dao
public interface SchemaDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertSchema(ApiResponseSchema schema);
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAllSchemas(List<ApiResponseSchema> schemas);
    
    @Query("SELECT * FROM api_response_schema WHERE modelId = :modelId AND providerId = :providerId LIMIT 1")
    ApiResponseSchema findBy(String modelId, String providerId);
    
    @Query("SELECT * FROM api_response_schema WHERE modelId = '' AND providerId = :providerId LIMIT 1")
    ApiResponseSchema findByGeneric(String providerId);
    
    @Query("SELECT * FROM api_response_schema")
    List<ApiResponseSchema> findAll();
    
    @Query("DELETE FROM api_response_schema")
    void clearAll();
}
