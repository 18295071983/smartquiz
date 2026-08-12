package com.oilquiz.app.ai.usage.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import com.oilquiz.app.ai.usage.db.entity.SyncLog;

import java.util.List;

@Dao
public interface SyncLogDao {
    
    @Insert
    long insert(SyncLog log);
    
    @Query("SELECT * FROM sync_log ORDER BY syncTime DESC LIMIT 20")
    List<SyncLog> findRecent();
    
    @Query("SELECT MAX(remoteVersion) FROM sync_log WHERE syncStatus = 'success'")
    Integer getLastConfigVersion();
    
    @Query("SELECT * FROM sync_log WHERE syncType = :type ORDER BY syncTime DESC LIMIT 1")
    SyncLog findLatestByType(String type);
}
