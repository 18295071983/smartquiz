package com.oilquiz.app.manager;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class BackupManager {

    private static final String DATABASE_NAME = "smartquiz_database";
    private static final String BACKUP_DIR_NAME = "oilquiz_backup";

    // 生成带时间戳的中文文件名
    private String getBackupFileName() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA);
        String timestamp = sdf.format(new Date());
        return "备份_" + timestamp + ".db";
    }

    // 获取备份目录
    private File getBackupDir() {
        File backupDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BACKUP_DIR_NAME);
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }
        return backupDir;
    }

    // 备份数据库
    public File backupDatabase(Context context) throws IOException {
        // 获取数据库文件路径
        File dbFile = context.getDatabasePath(DATABASE_NAME);
        if (!dbFile.exists()) {
            throw new IOException("数据库文件不存在");
        }

        // 检查数据库文件大小
        if (dbFile.length() == 0) {
            throw new IOException("数据库文件为空");
        }

        // 创建备份文件
        File backupDir = getBackupDir();
        File backupFile = new File(backupDir, getBackupFileName());

        // 复制数据库文件到备份位置
        try (FileInputStream fis = new FileInputStream(dbFile);
             FileOutputStream fos = new FileOutputStream(backupFile)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
        }

        // 验证备份文件
        if (!validateBackupFile(backupFile, dbFile.length())) {
            throw new IOException("备份文件验证失败");
        }

        return backupFile;
    }

    // 恢复数据库
    public boolean restoreDatabase(Context context, File backupFile) throws IOException {
        if (!backupFile.exists()) {
            throw new IOException("备份文件不存在");
        }

        // 验证备份文件
        if (!validateBackupFile(backupFile)) {
            throw new IOException("备份文件无效");
        }

        // 获取数据库文件路径
        File dbFile = context.getDatabasePath(DATABASE_NAME);

        // 确保数据库目录存在
        File dbDir = dbFile.getParentFile();
        if (!dbDir.exists()) {
            dbDir.mkdirs();
        }

        // 复制备份文件到数据库位置
        try (FileInputStream fis = new FileInputStream(backupFile);
             FileOutputStream fos = new FileOutputStream(dbFile)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
        }

        return true;
    }

    // 验证备份文件
    private boolean validateBackupFile(File backupFile) {
        return backupFile.exists() && backupFile.length() > 0;
    }

    // 验证备份文件（带大小验证）
    private boolean validateBackupFile(File backupFile, long expectedSize) {
        return backupFile.exists() && backupFile.length() == expectedSize;
    }

    // 获取备份文件列表
    public List<File> getBackupFiles() {
        List<File> backupFiles = new ArrayList<>();
        File backupDir = getBackupDir();
        
        if (backupDir.exists() && backupDir.isDirectory()) {
            File[] files = backupDir.listFiles((dir, name) -> name.endsWith(".db"));
            if (files != null) {
                for (File file : files) {
                    backupFiles.add(file);
                }
            }
        }
        
        return backupFiles;
    }

    // 删除备份文件
    public boolean deleteBackupFile(File backupFile) {
        if (backupFile.exists() && backupFile.getName().endsWith(".db")) {
            return backupFile.delete();
        }
        return false;
    }

    // 清理旧备份文件（保留最新的n个）
    public void cleanupOldBackups(int keepCount) {
        List<File> backupFiles = getBackupFiles();
        if (backupFiles.size() <= keepCount) {
            return;
        }
        
        // 按修改时间排序，保留最新的
        backupFiles.sort((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        
        for (int i = keepCount; i < backupFiles.size(); i++) {
            backupFiles.get(i).delete();
        }
    }
}
