package com.oilquiz.app.ai.usage.db;

import android.content.Context;

import androidx.room.Room;

/**
 * 数据库单例管理器
 */
public class DatabaseManager {
    
    private static volatile UsageDatabase instance;
    
    public static UsageDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (DatabaseManager.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                            context.getApplicationContext(),
                            UsageDatabase.class,
                            "ai_usage_database"
                    )
                    .fallbackToDestructiveMigration()
                    .build();
                }
            }
        }
        return instance;
    }
}
