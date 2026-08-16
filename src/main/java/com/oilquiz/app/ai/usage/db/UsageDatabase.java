package com.oilquiz.app.ai.usage.db;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.oilquiz.app.ai.usage.db.dao.ModelDao;
import com.oilquiz.app.ai.usage.db.dao.ProviderDao;
import com.oilquiz.app.ai.usage.db.dao.PriceDao;
import com.oilquiz.app.ai.usage.db.dao.RequestSchemaDao;
import com.oilquiz.app.ai.usage.db.dao.SchemaDao;
import com.oilquiz.app.ai.usage.db.dao.SyncLogDao;
import com.oilquiz.app.ai.usage.db.dao.UsageDao;
import com.oilquiz.app.ai.usage.db.entity.ApiProviderConfig;
import com.oilquiz.app.ai.usage.db.entity.ApiPriceConfig;
import com.oilquiz.app.ai.usage.db.entity.ApiRequestSchema;
import com.oilquiz.app.ai.usage.db.entity.ApiResponseSchema;
import com.oilquiz.app.ai.usage.db.entity.ApiUsageLog;
import com.oilquiz.app.ai.usage.db.entity.ModelEntity;
import com.oilquiz.app.ai.usage.db.entity.SyncLog;

/**
 * AI 用量统计 Room 数据库
 */
@Database(
    entities = {
        ApiProviderConfig.class,
        ApiPriceConfig.class,
        ApiUsageLog.class,
        ApiResponseSchema.class,
        ApiRequestSchema.class,
        ModelEntity.class,
        SyncLog.class
    },
    version = 2,
    exportSchema = true
)
public abstract class UsageDatabase extends RoomDatabase {
    
    public abstract ProviderDao providerDao();
    public abstract PriceDao priceDao();
    public abstract UsageDao usageDao();
    public abstract SchemaDao schemaDao();
    public abstract RequestSchemaDao requestSchemaDao();
    public abstract ModelDao modelDao();
    public abstract SyncLogDao syncLogDao();
}
