package com.oilquiz.app.ai.util;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ModelFileSelector {

    private static final String TAG = "ModelFileSelector";

    /**
     * 创建选择模型文件的Intent
     * @param context 上下文
     * @return 选择文件的Intent
     */
    public static Intent createModelFilePickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        
        // 添加文件类型过滤，只显示.gguf文件
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Environment.getExternalStorageDirectory().toString());
        }
        
        return intent;
    }
    
    /**
     * 从Uri获取文件路径（已废弃，建议使用copyUriToFile方法）
     * @param context 上下文
     * @param uri 文件Uri
     * @return 文件路径
     */
    @Deprecated
    public static String getPathFromUri(Context context, Uri uri) {
        String path = null;
        
        // 处理不同类型的Uri
        if (DocumentsContract.isDocumentUri(context, uri)) {
            // 处理DocumentProvider
            String documentId = DocumentsContract.getDocumentId(uri);
            if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                // 外部存储
                String[] split = documentId.split(":");
                String type = split[0];
                if ("primary".equalsIgnoreCase(type)) {
                    path = Environment.getExternalStorageDirectory() + "/" + split[1];
                }
            } else if ("com.android.providers.downloads.documents".equals(uri.getAuthority())) {
                // 下载目录
                Uri contentUri = Uri.parse("content://downloads/public_downloads" + documentId);
                path = getPathFromContentUri(context, contentUri);
            } else if ("com.android.providers.media.documents".equals(uri.getAuthority())) {
                // 媒体目录
                String[] split = documentId.split(":");
                String type = split[0];
                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }
                if (contentUri != null) {
                    path = getPathFromContentUri(context, contentUri);
                }
            }
        } else if ("content".equalsIgnoreCase(uri.getScheme())) {
            // 处理content:// Uri
            path = getPathFromContentUri(context, uri);
        } else if ("file".equalsIgnoreCase(uri.getScheme())) {
            // 处理file:// Uri
            path = uri.getPath();
        }
        
        return path;
    }
    
    /**
     * 从Content Uri获取文件路径（已废弃，建议使用copyUriToFile方法）
     * @param context 上下文
     * @param uri Content Uri
     * @return 文件路径
     */
    @Deprecated
    private static String getPathFromContentUri(Context context, Uri uri) {
        String path = null;
        String[] projection = {android.provider.MediaStore.MediaColumns.DATA};
        
        try (android.database.Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int columnIndex = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA);
                if (columnIndex != -1) {
                    path = cursor.getString(columnIndex);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get path from content URI", e);
        }
        
        // 如果无法通过ContentResolver获取路径，尝试其他方法
        if (path == null) {
            // 对于下载文件
            if ("content://downloads/public_downloads".equals(uri.getAuthority())) {
                path = uri.getLastPathSegment();
            }
            // 作为最后的尝试，返回Uri的路径
            if (path == null) {
                path = uri.getPath();
            }
        }
        
        return path;
    }
    
    /**
     * 从Uri直接复制文件到目标文件
     * @param context 上下文
     * @param uri 源文件Uri
     * @param destFile 目标文件
     * @return 是否成功复制
     */
    public static boolean copyUriToFile(Context context, Uri uri, File destFile) {
        return copyUriToFile(context, uri, destFile, null);
    }
    
    /**
     * 从Uri直接复制文件到目标文件（带进度回调）
     * @param context 上下文
     * @param uri 源文件Uri
     * @param destFile 目标文件
     * @param progressCallback 进度回调
     * @return 是否成功复制
     */
    public static boolean copyUriToFile(Context context, Uri uri, File destFile, ProgressCallback progressCallback) {
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
             OutputStream outputStream = new FileOutputStream(destFile)) {
            
            // 获取文件大小
            long fileSize = 0;
            try {
                android.content.ContentResolver resolver = context.getContentResolver();
                
                // 方法1: 使用OpenableColumns.SIZE
                android.database.Cursor cursor = resolver.query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        fileSize = cursor.getLong(sizeIndex);
                    }
                    cursor.close();
                }
                
                // 方法2: 如果方法1失败，尝试使用ContentResolver的openInputStream获取大小
                if (fileSize == 0) {
                    try (InputStream sizeInputStream = resolver.openInputStream(uri)) {
                        if (sizeInputStream != null) {
                            fileSize = sizeInputStream.available();
                        }
                    } catch (Exception e) {
                        // 忽略错误，继续尝试其他方法
                    }
                }
                
                // 方法3: 如果是file:// URI，直接获取文件大小
                if (fileSize == 0 && "file".equals(uri.getScheme())) {
                    String path = uri.getPath();
                    if (path != null) {
                        File file = new File(path);
                        if (file.exists()) {
                            fileSize = file.length();
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to get file size from URI", e);
            }
            
            // 记录文件大小获取结果
            if (fileSize > 0) {
                Log.i("ModelFileSelector", "成功获取文件大小: " + fileSize + " 字节");
            } else {
                Log.w("ModelFileSelector", "无法获取文件大小，将使用循环进度显示");
            }
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;
            
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
                
                // 回调进度
                if (progressCallback != null) {
                    if (fileSize > 0) {
                        int progress = (int) ((totalRead * 100) / fileSize);
                        progressCallback.onProgress(progress);
                    } else {
                        // 如果无法获取文件大小，显示一个不确定的进度
                        // 每读取1MB更新一次进度
                        if (totalRead % (1024 * 1024) == 0) {
                            // 显示一个从0到99的循环进度
                            int progress = (int) (totalRead % 100);
                            progressCallback.onProgress(progress);
                        }
                    }
                }
            }
            outputStream.flush();
            
            // 回调完成
            if (progressCallback != null) {
                progressCallback.onComplete();
            }
            
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy URI to file", e);
            
            // 回调错误
            if (progressCallback != null) {
                progressCallback.onError(e.getMessage());
            }
            
            return false;
        }
    }
    
    /**
     * 进度回调接口
     */
    public interface ProgressCallback {
        void onProgress(int progress);
        void onComplete();
        void onError(String error);
    }
    
    /**
     * 从Uri获取文件名
     * @param context 上下文
     * @param uri 文件Uri
     * @return 文件名
     */
    public static String getFileNameFromUri(Context context, Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        result = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to get file name from URI", e);
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }
    
    /**
     * 检查Uri是否为有效的模型文件
     * @param context 上下文
     * @param uri 文件Uri
     * @return 是否为有效的模型文件
     */
    public static boolean isValidModelUri(Context context, Uri uri) {
        String fileName = getFileNameFromUri(context, uri);
        return fileName != null && fileName.toLowerCase().endsWith(".gguf");
    }
    
    /**
     * 检查文件是否为有效的模型文件（已废弃，建议使用isValidModelUri）
     * @param filePath 文件路径
     * @return 是否为有效的模型文件
     */
    @Deprecated
    public static boolean isValidModelFile(String filePath) {
        return filePath != null && filePath.toLowerCase().endsWith(".gguf");
    }
}