package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * AppToolkitAITool测试类
 */
public class AppToolkitAIToolTest {
    
    private static final String TAG = "AppToolkitAIToolTest";
    
    public static void runTests(Context context) {
        Log.i(TAG, "Starting AppToolkitAITool tests...");
        
        // 测试1: 获取工具信息
        testGetInfo(context);
        
        // 测试2: 测试OCR语言设置
        testOcrLanguage(context);
        
        // 测试3: 测试文件类型检测
        testFileType(context);
        
        Log.i(TAG, "AppToolkitAITool tests completed");
    }
    
    private static void testGetInfo(Context context) {
        try {
            AIToolManager manager = AIToolManager.getInstance(context);
            Map<String, Object> params = new HashMap<>();
            params.put("action", "get_info");
            
            AIToolResult result = manager.executeTool("app_toolkit", params);
            
            if (result.isSuccess()) {
                Log.i(TAG, "✓ testGetInfo passed");
                Object data = result.getResult();
                if (data instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> info = (Map<String, Object>) data;
                    Log.i(TAG, "  Tool name: " + info.get("name"));
                    Log.i(TAG, "  Description: " + info.get("description"));
                }
            } else {
                Log.e(TAG, "✗ testGetInfo failed: " + result.getErrorMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ testGetInfo exception: " + e.getMessage(), e);
        }
    }
    
    private static void testOcrLanguage(Context context) {
        try {
            AIToolManager manager = AIToolManager.getInstance(context);
            
            // 设置语言
            Map<String, Object> setParams = new HashMap<>();
            setParams.put("action", "ocr_set_language");
            setParams.put("language", "chinese");
            
            AIToolResult setResult = manager.executeTool("app_toolkit", setParams);
            if (setResult.isSuccess()) {
                Log.i(TAG, "✓ testOcrLanguage set passed");
            } else {
                Log.e(TAG, "✗ testOcrLanguage set failed: " + setResult.getErrorMessage());
                return;
            }
            
            // 获取语言
            Map<String, Object> getParams = new HashMap<>();
            getParams.put("action", "ocr_get_language");
            
            AIToolResult getResult = manager.executeTool("app_toolkit", getParams);
            if (getResult.isSuccess()) {
                Log.i(TAG, "✓ testOcrLanguage get passed");
                Object data = getResult.getResult();
                if (data instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> info = (Map<String, Object>) data;
                    Log.i(TAG, "  Current language: " + info.get("language"));
                }
            } else {
                Log.e(TAG, "✗ testOcrLanguage get failed: " + getResult.getErrorMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ testOcrLanguage exception: " + e.getMessage(), e);
        }
    }
    
    private static void testFileType(Context context) {
        try {
            AIToolManager manager = AIToolManager.getInstance(context);
            
            // 使用当前应用的apk文件作为测试
            String apkPath = context.getApplicationInfo().sourceDir;
            
            Map<String, Object> params = new HashMap<>();
            params.put("action", "file_get_type");
            params.put("file_path", apkPath);
            
            AIToolResult result = manager.executeTool("app_toolkit", params);
            
            if (result.isSuccess()) {
                Log.i(TAG, "✓ testFileType passed");
                Object data = result.getResult();
                if (data instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> info = (Map<String, Object>) data;
                    Log.i(TAG, "  File name: " + info.get("fileName"));
                    Log.i(TAG, "  File type: " + info.get("type"));
                    Log.i(TAG, "  Extension: " + info.get("extension"));
                    Log.i(TAG, "  Size: " + info.get("size") + " bytes");
                }
            } else {
                Log.e(TAG, "✗ testFileType failed: " + result.getErrorMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ testFileType exception: " + e.getMessage(), e);
        }
    }
}