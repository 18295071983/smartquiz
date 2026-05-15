package com.oilquiz.app.util;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 文件上传工具类
 * 用于上传本地文件到服务器
 */
public class FileUploader {
    private static final String TAG = "FileUploader";
    
    private static final int DEFAULT_TIMEOUT = 60;
    private static final MediaType MEDIA_TYPE_OCTET_STREAM = 
            MediaType.parse("application/octet-stream");
    
    private OkHttpClient client;
    
    /**
     * 上传进度监听器
     */
    public interface UploadProgressListener {
        void onProgress(long uploaded, long total);
        void onComplete(Response response);
        void onError(Exception e);
    }
    
    /**
     * 构造函数
     */
    public FileUploader() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 构造函数（使用自定义OkHttpClient）
     * @param client OkHttpClient实例
     */
    public FileUploader(OkHttpClient client) {
        this.client = client;
    }
    
    /**
     * 同步上传单个文件
     * @param url 上传URL
     * @param file 要上传的文件
     * @param fileParamName 文件参数名
     * @return 响应对象
     */
    public Response uploadFileSync(String url, File file, String fileParamName) {
        return uploadFileSync(url, file, fileParamName, null);
    }
    
    /**
     * 同步上传单个文件（带额外参数）
     * @param url 上传URL
     * @param file 要上传的文件
     * @param fileParamName 文件参数名
     * @param params 额外参数
     * @return 响应对象
     */
    public Response uploadFileSync(String url, File file, String fileParamName, 
                                    Map<String, String> params) {
        Request request = buildUploadRequest(url, file, fileParamName, params, null);
        
        try {
            return client.newCall(request).execute();
        } catch (IOException e) {
            Log.e(TAG, "同步上传失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 异步上传单个文件
     * @param url 上传URL
     * @param file 要上传的文件
     * @param fileParamName 文件参数名
     * @param listener 进度监听器
     */
    public void uploadFileAsync(String url, File file, String fileParamName, 
                                  UploadProgressListener listener) {
        uploadFileAsync(url, file, fileParamName, null, listener);
    }
    
    /**
     * 异步上传单个文件（带额外参数）
     * @param url 上传URL
     * @param file 要上传的文件
     * @param fileParamName 文件参数名
     * @param params 额外参数
     * @param listener 进度监听器
     */
    public void uploadFileAsync(String url, File file, String fileParamName, 
                                  Map<String, String> params, 
                                  UploadProgressListener listener) {
        Request request = buildUploadRequest(url, file, fileParamName, params, listener);
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "异步上传失败: " + e.getMessage(), e);
                if (listener != null) {
                    listener.onError(e);
                }
            }
            
            @Override
            public void onResponse(Call call, Response response) {
                Log.i(TAG, "文件上传成功: " + file.getAbsolutePath());
                if (listener != null) {
                    listener.onComplete(response);
                }
            }
        });
    }
    
    /**
     * 同步上传多个文件
     * @param url 上传URL
     * @param files 文件映射（参数名 -> 文件）
     * @param params 额外参数
     * @return 响应对象
     */
    public Response uploadMultipleFilesSync(String url, Map<String, File> files, 
                                             Map<String, String> params) {
        Request request = buildMultipleFilesUploadRequest(url, files, params);
        
        try {
            return client.newCall(request).execute();
        } catch (IOException e) {
            Log.e(TAG, "同步多文件上传失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 异步上传多个文件
     * @param url 上传URL
     * @param files 文件映射（参数名 -> 文件）
     * @param params 额外参数
     * @param listener 进度监听器
     */
    public void uploadMultipleFilesAsync(String url, Map<String, File> files, 
                                          Map<String, String> params, 
                                          UploadProgressListener listener) {
        Request request = buildMultipleFilesUploadRequest(url, files, params);
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "异步多文件上传失败: " + e.getMessage(), e);
                if (listener != null) {
                    listener.onError(e);
                }
            }
            
            @Override
            public void onResponse(Call call, Response response) {
                Log.i(TAG, "多文件上传成功");
                if (listener != null) {
                    listener.onComplete(response);
                }
            }
        });
    }
    
    /**
     * 构建单个文件上传请求
     */
    private Request buildUploadRequest(String url, File file, String fileParamName, 
                                        Map<String, String> params, 
                                        UploadProgressListener listener) {
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM);
        
        // 添加额外参数
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                builder.addFormDataPart(entry.getKey(), entry.getValue());
            }
        }
        
        // 添加文件
        RequestBody fileBody = RequestBody.create(file, MEDIA_TYPE_OCTET_STREAM);
        builder.addFormDataPart(fileParamName, file.getName(), fileBody);
        
        return new Request.Builder()
                .url(url)
                .post(builder.build())
                .build();
    }
    
    /**
     * 构建多文件上传请求
     */
    private Request buildMultipleFilesUploadRequest(String url, Map<String, File> files, 
                                                     Map<String, String> params) {
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM);
        
        // 添加额外参数
        if (params != null) {
            for (Map.Entry<String, String> entry : params.entrySet()) {
                builder.addFormDataPart(entry.getKey(), entry.getValue());
            }
        }
        
        // 添加文件
        if (files != null) {
            for (Map.Entry<String, File> entry : files.entrySet()) {
                File file = entry.getValue();
                RequestBody fileBody = RequestBody.create(file, MEDIA_TYPE_OCTET_STREAM);
                builder.addFormDataPart(entry.getKey(), file.getName(), fileBody);
            }
        }
        
        return new Request.Builder()
                .url(url)
                .post(builder.build())
                .build();
    }
    
    /**
     * 上传JSON数据
     * @param url 上传URL
     * @param json JSON字符串
     * @return 响应对象
     */
    public Response uploadJsonSync(String url, String json) {
        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(json, JSON);
        
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        
        try {
            return client.newCall(request).execute();
        } catch (IOException e) {
            Log.e(TAG, "上传JSON失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 异步上传JSON数据
     * @param url 上传URL
     * @param json JSON字符串
     * @param listener 进度监听器
     */
    public void uploadJsonAsync(String url, String json, UploadProgressListener listener) {
        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(json, JSON);
        
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "异步上传JSON失败: " + e.getMessage(), e);
                if (listener != null) {
                    listener.onError(e);
                }
            }
            
            @Override
            public void onResponse(Call call, Response response) {
                Log.i(TAG, "JSON上传成功");
                if (listener != null) {
                    listener.onComplete(response);
                }
            }
        });
    }
    
    /**
     * 上传表单数据
     * @param url 上传URL
     * @param params 表单参数
     * @return 响应对象
     */
    public Response uploadFormSync(String url, Map<String, String> params) {
        FormBody.Builder builder = new FormBody.Builder();
        
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.add(entry.getKey(), entry.getValue());
        }
        
        Request request = new Request.Builder()
                .url(url)
                .post(builder.build())
                .build();
        
        try {
            return client.newCall(request).execute();
        } catch (IOException e) {
            Log.e(TAG, "上传表单失败: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 异步上传表单数据
     * @param url 上传URL
     * @param params 表单参数
     * @param listener 进度监听器
     */
    public void uploadFormAsync(String url, Map<String, String> params, 
                                  UploadProgressListener listener) {
        FormBody.Builder builder = new FormBody.Builder();
        
        for (Map.Entry<String, String> entry : params.entrySet()) {
            builder.add(entry.getKey(), entry.getValue());
        }
        
        Request request = new Request.Builder()
                .url(url)
                .post(builder.build())
                .build();
        
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "异步上传表单失败: " + e.getMessage(), e);
                if (listener != null) {
                    listener.onError(e);
                }
            }
            
            @Override
            public void onResponse(Call call, Response response) {
                Log.i(TAG, "表单上传成功");
                if (listener != null) {
                    listener.onComplete(response);
                }
            }
        });
    }
    
    /**
     * 取消所有上传
     */
    public void cancelAll() {
        client.dispatcher().cancelAll();
    }
    
    /**
     * 检查文件是否可上传
     * @param file 文件对象
     * @param maxSizeBytes 最大文件大小（字节）
     * @return 是否可上传
     */
    public static boolean canUploadFile(File file, long maxSizeBytes) {
        if (!file.exists()) {
            Log.e(TAG, "文件不存在: " + file.getAbsolutePath());
            return false;
        }
        
        if (!file.canRead()) {
            Log.e(TAG, "文件不可读: " + file.getAbsolutePath());
            return false;
        }
        
        if (file.length() > maxSizeBytes) {
            Log.e(TAG, "文件大小超过限制: " + file.getAbsolutePath() + 
                    ", 大小: " + file.length() + " bytes, 限制: " + maxSizeBytes + " bytes");
            return false;
        }
        
        return true;
    }
    
    /**
     * 获取文件MIME类型
     * @param fileName 文件名
     * @return MIME类型
     */
    public static String getMimeType(String fileName) {
        String extension = FileParserUtil.getFileExtension(new File(fileName));
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "pdf":
                return "application/pdf";
            case "doc":
                return "application/msword";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls":
                return "application/vnd.ms-excel";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "txt":
                return "text/plain";
            case "json":
                return "application/json";
            case "xml":
                return "application/xml";
            case "html":
            case "htm":
                return "text/html";
            case "zip":
                return "application/zip";
            default:
                return "application/octet-stream";
        }
    }
}
