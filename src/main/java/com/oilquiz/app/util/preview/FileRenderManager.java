package com.oilquiz.app.util.preview;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.util.render.FileRenderEngine;
import com.oilquiz.app.util.render.TextRenderEngine;
import com.oilquiz.app.util.render.MarkdownRenderEngine;
import com.oilquiz.app.util.render.HTMLRenderEngine;
import com.oilquiz.app.util.render.ImageRenderEngine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class FileRenderManager {
    private static final String TAG = "FileRenderManager";
    private static FileRenderManager instance;
    private List<FileRenderEngine> renderEngines;
    
    private FileRenderManager(Context context) {
        renderEngines = new ArrayList<>();
        initRenderEngines();
    }
    
    public static synchronized FileRenderManager getInstance(Context context) {
        if (instance == null) {
            instance = new FileRenderManager(context);
        }
        return instance;
    }
    
    private void initRenderEngines() {
        // 添加文本渲染引擎
        renderEngines.add(new TextRenderEngine());
        
        // 添加Markdown渲染引擎
        renderEngines.add(new MarkdownRenderEngine());
        
        // 添加HTML渲染引擎
        renderEngines.add(new HTMLRenderEngine());
        
        // 添加图片渲染引擎
        renderEngines.add(new ImageRenderEngine());
    }
    
    /**
     * 渲染文件
     * @param file 要渲染的文件
     * @param callback 渲染回调
     */
    public void renderFile(File file, FileRenderEngine.RenderCallback callback) {
        // 检查文件是否存在
        if (!file.exists()) {
            callback.onError("文件不存在");
            return;
        }
        
        // 查找适合的渲染引擎
        FileRenderEngine engine = findSuitableEngine(file);
        if (engine != null) {
            Log.d(TAG, "Using render engine: " + engine.getEngineName());
            engine.render(file, callback);
        } else {
            callback.onError("不支持的文件类型");
        }
    }
    
    /**
     * 查找适合的渲染引�?     * @param file 文件
     * @return 适合的渲染引�?     */
    private FileRenderEngine findSuitableEngine(File file) {
        for (FileRenderEngine engine : renderEngines) {
            if (engine.canRender(file)) {
                return engine;
            }
        }
        return null;
    }
    
    /**
     * 获取文件类型描述
     * @param file 文件
     * @return 文件类型描述
     */
    public String getFileTypeDescription(File file) {
        FileRenderEngine engine = findSuitableEngine(file);
        if (engine != null) {
            return engine.getFileTypeDescription(file);
        }
        return "未知文件类型";
    }
    
    /**
     * 检查文件是否可以渲�?     * @param file 文件
     * @return 是否可以渲染
     */
    public boolean canRender(File file) {
        return findSuitableEngine(file) != null;
    }
}
