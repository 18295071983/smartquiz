package com.oilquiz.app.ai.usage.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.oilquiz.app.ai.usage.db.entity.ApiPriceConfig;

import java.util.List;

@Dao
public interface PriceDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertPrice(ApiPriceConfig config);
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAllPrices(List<ApiPriceConfig> configs);
    
    @Query("SELECT * FROM api_price_config WHERE modelId = :modelId AND priceType = :priceType AND isActive = 1 LIMIT 1")
    ApiPriceConfig findBy(String modelId, String priceType);
    
    @Query("SELECT * FROM api_price_config WHERE modelId = :modelId")
    List<ApiPriceConfig> findByModelId(String modelId);
    
    @Query("SELECT * FROM api_price_config WHERE isActive = 1")
    List<ApiPriceConfig> findAllActive();
    
    @Query("SELECT * FROM api_price_config")
    List<ApiPriceConfig> findAll();
    
    @Query("UPDATE api_price_config SET isActive = 0")
    void clearAll();
}
