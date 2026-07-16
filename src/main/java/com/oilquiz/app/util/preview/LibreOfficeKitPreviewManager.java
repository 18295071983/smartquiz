package com.oilquiz.app.util.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;

import com.oilquiz.app.infra.AppLogger;

import java.io.File;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LibreOfficeKit 预览管理器
 * 基于 LibreOffice 开源库的文档渲染方案
 */
public class LibreOfficeKitPreviewManager {
    private static final String TAG = "LibreOfficeKitPreviewManager";
    private static LibreOfficeKitPreviewManager instance;
    
    private Context context;
    private ExecutorService executorService;
    private boolean isInitialized = false;
    
    // LibreOfficeKit 接口（使用反射避免类加载时崩溃）
    private Object office = null;
    private Object document = null;
    private Class<?> libreOfficeKitClass = null;
    private Class<?> officeClass = null;
    private Class<?> documentClass = null;
    
    private LibreOfficeKitPreviewManager(Context context) {
        this.context = context.getApplicationContext();
        this.executorService = Executors.newSingleThreadExecutor();
    }
    
    public static synchronized LibreOfficeKitPreviewManager getInstance(Context context) {
        if (instance == null) {
            instance = new LibreOfficeKitPreviewManager(context);
        }
        return instance;
    }
    
    /**
     * 初始化 LibreOfficeKit
     * @return true 如果初始化成功
     */
    public boolean initialize() {
        try {
            // 尝试加载库
            System.loadLibrary("lo-native-code");
            
            // 动态加载 LibreOfficeKit 相关类
            libreOfficeKitClass = Class.forName("org.libreoffice.kit.LibreOfficeKit");
            officeClass = Class.forName("org.libreoffice.kit.Office");
            documentClass = Class.forName("org.libreoffice.kit.Document");
            
            // 初始化 LibreOfficeKit
            if (context instanceof android.app.Activity) {
                Method initMethod = libreOfficeKitClass.getMethod("init", android.app.Activity.class);
                initMethod.invoke(null, context);
                
                // 创建 Office 实例
                Method getHandleMethod = libreOfficeKitClass.getMethod("getLibreOfficeKitHandle");
                Object handle = getHandleMethod.invoke(null);
                
                // 调用 Office 构造函数
                java.lang.reflect.Constructor<?> officeConstructor = officeClass.getConstructor(java.nio.ByteBuffer.class);
                office = officeConstructor.newInstance(handle);
                
                isInitialized = true;
                AppLogger.i(TAG, "LibreOfficeKit 初始化成功");
                return true;
            } else {
                AppLogger.e(TAG, "Context is not an Activity");
                return false;
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "LibreOfficeKit 初始化错误: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 打开文档
     * @param filePath 文件路径
     * @return true 如果成功打开
     */
    public boolean openDocument(String filePath) {
        if (!isInitialized) {
            if (!initialize()) {
                return false;
            }
        }
        
        try {
            // 关闭之前的文档
            closeDocument();
            
            // 打开新文档
            Method documentLoadMethod = officeClass.getMethod("documentLoad", String.class);
            document = documentLoadMethod.invoke(office, filePath);
            if (document == null) {
                Method getErrorMethod = officeClass.getMethod("getError");
                String error = (String) getErrorMethod.invoke(office);
                AppLogger.e(TAG, "打开文档失败: " + filePath + "，错误: " + error);
                return false;
            }
            
            // 初始化渲染
            Method initializeForRenderingMethod = documentClass.getMethod("initializeForRendering");
            initializeForRenderingMethod.invoke(document);
            
            AppLogger.i(TAG, "文档打开成功: " + filePath);
            return true;
        } catch (Exception e) {
            AppLogger.e(TAG, "打开文档错误: " + e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 渲染文档页面
     * @param pageIndex 页面索引（从0开始）
     * @param width 目标宽度
     * @param height 目标高度
     * @return 渲染后的 Bitmap
     */
    public Bitmap renderPage(int pageIndex, int width, int height) {
        if (!isInitialized || document == null) {
            AppLogger.e(TAG, "LibreOfficeKit 未初始化或文档未打开");
            return null;
        }
        
        try {
            // 设置页面
            Method setPartMethod = documentClass.getMethod("setPart", int.class);
            setPartMethod.invoke(document, pageIndex);
            
            // 创建 ByteBuffer
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(width * height * 4);
            if (buffer == null) {
                AppLogger.e(TAG, "创建缓冲区失败");
                return null;
            }
            
            // 获取文档宽度和高度
            Method getDocumentWidthMethod = documentClass.getMethod("getDocumentWidth");
            long documentWidth = (Long) getDocumentWidthMethod.invoke(document);
            Method getDocumentHeightMethod = documentClass.getMethod("getDocumentHeight");
            long documentHeight = (Long) getDocumentHeightMethod.invoke(document);
            
            // 渲染页面
            Method paintTileMethod = documentClass.getMethod("paintTile", java.nio.ByteBuffer.class, int.class, int.class, int.class, int.class, int.class, int.class);
            paintTileMethod.invoke(document, buffer, width, height, 0, 0, (int) documentWidth, (int) documentHeight);
            
            // 创建 Bitmap
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            
            AppLogger.d(TAG, "页面渲染成功: " + pageIndex);
            return bitmap;
        } catch (Exception e) {
            AppLogger.e(TAG, "渲染页面错误: " + e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 获取文档总页数
     * @return 总页数
     */
    public int getPageCount() {
        if (!isInitialized || document == null) {
            return 0;
        }
        
        try {
            Method getPartsMethod = documentClass.getMethod("getParts");
            return (Integer) getPartsMethod.invoke(document);
        } catch (Exception e) {
            AppLogger.e(TAG, "获取页数错误: " + e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * 获取页面尺寸
     * @param pageIndex 页面索引
     * @return 页面尺寸矩形
     */
    public Rect getPageSize(int pageIndex) {
        if (!isInitialized || document == null) {
            return null;
        }
        
        try {
            // 设置页面
            Method setPartMethod = documentClass.getMethod("setPart", int.class);
            setPartMethod.invoke(document, pageIndex);
            
            // 获取页面尺寸
            Method getDocumentWidthMethod = documentClass.getMethod("getDocumentWidth");
            long width = (Long) getDocumentWidthMethod.invoke(document);
            Method getDocumentHeightMethod = documentClass.getMethod("getDocumentHeight");
            long height = (Long) getDocumentHeightMethod.invoke(document);
            
            return new Rect(0, 0, (int) width, (int) height);
        } catch (Exception e) {
            AppLogger.e(TAG, "获取页面尺寸错误: " + e.getMessage(), e);
        }
        return null;
    }
    
    /**
     * 关闭文档
     */
    public void closeDocument() {
        if (document != null) {
            try {
                Method destroyMethod = documentClass.getMethod("destroy");
                destroyMethod.invoke(document);
                document = null;
                AppLogger.i(TAG, "文档已关闭");
            } catch (Exception e) {
                AppLogger.e(TAG, "关闭文档错误: " + e.getMessage(), e);
            }
        }
    }
    
    /**
     * 释放资源
     */
    public void release() {
        closeDocument();
        
        if (office != null) {
            try {
                Method destroyMethod = officeClass.getMethod("destroy");
                destroyMethod.invoke(office);
                office = null;
                AppLogger.i(TAG, "LibreOfficeKit 资源已释放");
            } catch (Exception e) {
                AppLogger.e(TAG, "释放资源错误: " + e.getMessage(), e);
            }
        }
        
        isInitialized = false;
    }
    
    /**
     * 检查 LibreOfficeKit 是否可用
     * @return true 如果可用
     */
    public boolean isAvailable() {
        try {
            System.loadLibrary("lo-native-code");
            return true;
        } catch (Exception e) {
            AppLogger.w(TAG, "LibreOfficeKit 库不可用: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 检查是否已初始化
     * @return true 如果已初始化
     */
    public boolean isInitialized() {
        return isInitialized;
    }
    
    // 不再需要原生方法声明，使用反射调用
}
