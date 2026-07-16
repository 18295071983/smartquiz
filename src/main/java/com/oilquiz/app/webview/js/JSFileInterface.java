package com.oilquiz.app.webview.js;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.webkit.JavascriptInterface;

import com.oilquiz.app.SmartQuizApplication;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * JavaScript 文件操作接口
 * 高风险接口，仅允许操作特定目录
 */
public class JSFileInterface {
    private static final String TAG = "JSFileInterface";
    private final Context context;
    
    // 允许的文件操作目录
    private static final Set<String> ALLOWED_PATHS = new HashSet<>();
    
    static {
        // 允许的目录
        ALLOWED_PATHS.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
        ALLOWED_PATHS.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath());
        ALLOWED_PATHS.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).getAbsolutePath());
    }

    public JSFileInterface(Context context) {
        this.context = context;
        // 添加应用私有目录
        if (context != null) {
            ALLOWED_PATHS.add(context.getFilesDir().getAbsolutePath());
            ALLOWED_PATHS.add(context.getExternalFilesDir(null) != null ? 
                context.getExternalFilesDir(null).getAbsolutePath() : "");
        }
    }

    /**
     * 检查路径是否在允许列表中
     */
    private boolean isPathAllowed(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        
        try {
            File file = new File(path).getCanonicalFile();
            String canonicalPath = file.getAbsolutePath();
            
            for (String allowedPath : ALLOWED_PATHS) {
                if (canonicalPath.startsWith(allowedPath)) {
                    return true;
                }
            }
            
            Log.w(TAG, "禁止访问未授权路径: " + path);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "路径验证失败: " + path, e);
            return false;
        }
    }

    /**
     * 读取文件内容
     */
    @JavascriptInterface
    public String readFile(String path) {
        if (!isPathAllowed(path)) {
            return "{\"error\": \"路径未授权\"}";
        }
        
        try {
            File file = new File(path);
            if (!file.exists() || !file.canRead()) {
                return "{\"error\": \"文件不存在或无法读取\"}";
            }
            
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }
            
            Log.d(TAG, "读取文件成功: " + path + ", 长度: " + content.length());
            return content.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "读取文件失败: " + path, e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 写入文件内容
     */
    @JavascriptInterface
    public boolean writeFile(String path, String content) {
        if (!isPathAllowed(path)) {
            Log.w(TAG, "禁止写入未授权路径: " + path);
            return false;
        }
        
        try {
            File file = new File(path);
            
            // 创建父目录
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
            
            Log.d(TAG, "写入文件成功: " + path);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "写入文件失败: " + path, e);
            return false;
        }
    }

    /**
     * 删除文件
     */
    @JavascriptInterface
    public boolean deleteFile(String path) {
        if (!isPathAllowed(path)) {
            Log.w(TAG, "禁止删除未授权路径: " + path);
            return false;
        }
        
        try {
            File file = new File(path);
            if (file.exists()) {
                boolean deleted = file.delete();
                Log.d(TAG, "删除文件: " + path + ", 结果: " + deleted);
                return deleted;
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "删除文件失败: " + path, e);
            return false;
        }
    }

    /**
     * 检查文件是否存在
     */
    @JavascriptInterface
    public boolean fileExists(String path) {
        if (!isPathAllowed(path)) {
            return false;
        }
        return new File(path).exists();
    }

    /**
     * 获取文件大小
     */
    @JavascriptInterface
    public long getFileSize(String path) {
        if (!isPathAllowed(path)) {
            return -1;
        }
        try {
            File file = new File(path);
            return file.exists() ? file.length() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 创建目录
     */
    @JavascriptInterface
    public boolean createDirectory(String path) {
        if (!isPathAllowed(path)) {
            return false;
        }
        try {
            File dir = new File(path);
            return dir.mkdirs();
        } catch (Exception e) {
            Log.e(TAG, "创建目录失败: " + path, e);
            return false;
        }
    }

    /**
     * 列出目录内容
     */
    @JavascriptInterface
    public String listDirectory(String path) {
        if (!isPathAllowed(path)) {
            return "[]";
        }
        try {
            File dir = new File(path);
            if (!dir.isDirectory()) {
                return "[]";
            }
            
            File[] files = dir.listFiles();
            if (files == null) {
                return "[]";
            }
            
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < files.length; i++) {
                if (i > 0) result.append(",");
                result.append("{");
                result.append("\"name\":\"").append(escapeJson(files[i].getName())).append("\",");
                result.append("\"isDirectory\":").append(files[i].isDirectory()).append(",");
                result.append("\"size\":").append(files[i].length()).append(",");
                result.append("\"lastModified\":").append(files[i].lastModified());
                result.append("}");
            }
            result.append("]");
            return result.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "列出目录失败: " + path, e);
            return "[]";
        }
    }

    /**
     * JSON 字符串转义
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
