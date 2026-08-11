package com.oilquiz.app.ai.usage.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.oilquiz.app.ai.usage.db.entity.ApiProviderConfig;

import java.util.List;

@Dao
public interface ProviderDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertProvider(ApiProviderConfig config);
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAllProviders(List<ApiProviderConfig> configs);
    
    @Update(onConflict = OnConflictStrategy.REPLACE)
    void updateProvider(ApiProviderConfig config);
    
    @Query("SELECT * FROM api_provider_config WHERE providerId = :providerId")
    ApiProviderConfig findByProviderId(String providerId);
    
    @Query("SELECT * FROM api_provider_config WHERE isActive = 1")
    List<ApiProviderConfig> findActiveProviders();
    
    @Query("SELECT * FROM api_provider_config")
    List<ApiProviderConfig> findAll();
    
    @Query("DELETE FROM api_provider_config")
    void clearAll();
}
