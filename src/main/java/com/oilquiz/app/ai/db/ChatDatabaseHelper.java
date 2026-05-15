package com.oilquiz.app.ai.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class ChatDatabaseHelper extends SQLiteOpenHelper {
    private static final String TAG = "ChatDatabaseHelper";
    private static final String DATABASE_NAME = "chat.db";
    private static final int DATABASE_VERSION = 1;

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

    // 创建表的SQL语句
    private static final String CREATE_TABLE_CONVERSATIONS = "CREATE TABLE " + TABLE_CONVERSATIONS + " (" +
            COLUMN_CONV_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COLUMN_CONV_TITLE + " TEXT, " +
            COLUMN_CONV_CREATED_AT + " INTEGER, " +
            COLUMN_CONV_UPDATED_AT + " INTEGER" +
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

    public ChatDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.d(TAG, "Creating database tables...");
        db.execSQL(CREATE_TABLE_CONVERSATIONS);
        db.execSQL(CREATE_TABLE_CHAT_MESSAGES);
        Log.d(TAG, "Database tables created successfully");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion);
        // 这里可以添加数据库升级逻辑
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CHAT_MESSAGES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CONVERSATIONS);
        onCreate(db);
    }
}
