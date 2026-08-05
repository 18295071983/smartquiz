package com.oilquiz.app.ai.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class ChatDatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "ChatDatabaseHelper";
    private static final String DATABASE_NAME = "chat.db";
    private static final int DATABASE_VERSION = 2;

    // 聊天记录表
    public static final String TABLE_CHAT_MESSAGES = "chat_messages";
    public static final String COLUMN_ID = "id";
    public static final String COLUMN_CONVERSATION_ID = "conversation_id";
    public static final String COLUMN_ROLE = "role";
    public static final String COLUMN_CONTENT = "content";
    public static final String COLUMN_TIMESTAMP = "timestamp";
    public static final String COLUMN_IS_COMPRESSED = "is_compressed";

    // 会话表
    public static final String TABLE_CONVERSATIONS = "conversations";
    public static final String COLUMN_CONV_ID = "id";
    public static final String COLUMN_CONV_TITLE = "title";
    public static final String COLUMN_CONV_CREATED_AT = "created_at";
    public static final String COLUMN_CONV_UPDATED_AT = "updated_at";
    public static final String COLUMN_CONV_SCENE = "scene";

    // 创建表的SQL语句
    private static final String CREATE_TABLE_CONVERSATIONS = "CREATE TABLE " + TABLE_CONVERSATIONS + " (" +
            COLUMN_CONV_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COLUMN_CONV_TITLE + " TEXT, " +
            COLUMN_CONV_CREATED_AT + " INTEGER, " +
            COLUMN_CONV_UPDATED_AT + " INTEGER, " +
            COLUMN_CONV_SCENE + " TEXT NOT NULL DEFAULT 'ai_chat'" +
            ");";

    private static final String CREATE_TABLE_CHAT_MESSAGES = "CREATE TABLE " + TABLE_CHAT_MESSAGES + " (" +
            COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COLUMN_CONVERSATION_ID + " INTEGER, " +
            COLUMN_ROLE + " TEXT, " +
            COLUMN_CONTENT + " TEXT, " +
            COLUMN_TIMESTAMP + " INTEGER, " +
            COLUMN_IS_COMPRESSED + " INTEGER DEFAULT 0, " +
            "FOREIGN KEY (" + COLUMN_CONVERSATION_ID + ") REFERENCES " + TABLE_CONVERSATIONS + "(" + COLUMN_CONV_ID + ")" +
            ");";

    private static final String CREATE_INDEX_SCENE = "CREATE INDEX IF NOT EXISTS idx_conversation_scene ON " +
            TABLE_CONVERSATIONS + "(" + COLUMN_CONV_SCENE + ");";

    public ChatDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.d(TAG, "Creating database tables...");
        db.execSQL(CREATE_TABLE_CONVERSATIONS);
        db.execSQL(CREATE_TABLE_CHAT_MESSAGES);
        db.execSQL(CREATE_INDEX_SCENE);
        Log.d(TAG, "Database tables created successfully");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);
        if (oldVersion < 2) {
            // v2: conversations 表新增 scene 列（默认 ai_chat）+ 场景索引
            try {
                db.execSQL("ALTER TABLE " + TABLE_CONVERSATIONS + " ADD COLUMN " + COLUMN_CONV_SCENE + " TEXT NOT NULL DEFAULT 'ai_chat';");
                db.execSQL(CREATE_INDEX_SCENE);
                Log.d(TAG, "Upgraded to v2: added scene column and index");
            } catch (Exception e) {
                Log.e(TAG, "Failed to upgrade to v2: " + e.getMessage(), e);
                // 兜底：删表重建
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_CHAT_MESSAGES);
                db.execSQL("DROP TABLE IF EXISTS " + TABLE_CONVERSATIONS);
                onCreate(db);
            }
        }
    }
}
