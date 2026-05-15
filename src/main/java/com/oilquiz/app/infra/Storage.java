package com.oilquiz.app.infra;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.IOException;

public class Storage {

    public static File getExternalStorageDir(Context context, String type) {
        return Environment.getExternalStoragePublicDirectory(type);
    }

    public static File getAppDir(Context context) {
        File appDir = new File(context.getFilesDir(), "oilquiz");
        if (!appDir.exists()) {
            appDir.mkdirs();
        }
        return appDir;
    }

    public static File createFile(File directory, String fileName) throws IOException {
        File file = new File(directory, fileName);
        if (!file.exists()) {
            file.createNewFile();
        }
        return file;
    }

    public static boolean deleteFile(File file) {
        if (file.exists()) {
            return file.delete();
        }
        return false;
    }

    public static boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    public static boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    }
}
