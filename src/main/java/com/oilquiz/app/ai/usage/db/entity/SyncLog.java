package com.oilquiz.app.ai.usage.db.entity;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 同步日志表
 */
@Entity(tableName = "sync_log")
public class SyncLog {
    
    @PrimaryKey(autoGenerate = true)
    private int id;
    
    private String syncType;
    private int remoteVersion;
    private int localVersion;
    private String syncStatus;
    private String errorMsg;
    private long syncTime;
    
    public SyncLog() {
        this.syncTime = System.currentTimeMillis();
    }
    
    // Getters and Setters
    
    public int getId() {
        return id;
    }
    
    public void setId(int id) {
        this.id = id;
    }
    
    public String getSyncType() {
        return syncType;
    }
    
    public void setSyncType(String syncType) {
        this.syncType = syncType;
    }
    
    public int getRemoteVersion() {
        return remoteVersion;
    }
    
    public void setRemoteVersion(int remoteVersion) {
        this.remoteVersion = remoteVersion;
    }
    
    public int getLocalVersion() {
        return localVersion;
    }
    
    public void setLocalVersion(int localVersion) {
        this.localVersion = localVersion;
    }
    
    public String getSyncStatus() {
        return syncStatus;
    }
    
    public void setSyncStatus(String syncStatus) {
        this.syncStatus = syncStatus;
    }
    
    public String getErrorMsg() {
        return errorMsg;
    }
    
    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }
    
    public long getSyncTime() {
        return syncTime;
    }
    
    public void setSyncTime(long syncTime) {
        this.syncTime = syncTime;
    }
}
