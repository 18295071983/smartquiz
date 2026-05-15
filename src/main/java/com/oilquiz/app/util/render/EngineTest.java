package com.oilquiz.app.util.render;

import com.oilquiz.app.util.PreviewRenderBridge;
import java.io.File;

public class EngineTest {
    public static void main(String[] args) {
        // 测试不同文件类型的引擎选择
        testEngineSelection("test.docx");
        testEngineSelection("test.pptx");
        testEngineSelection("test.txt");
        testEngineSelection("test.html");
        testEngineSelection("test.png");
    }
    
    private static void testEngineSelection(String fileName) {
        File file = new File(fileName);
        PreviewRenderBridge.RenderEngineFactory factory = PreviewRenderBridge.RenderEngineFactory.getInstance();
        FileRenderEngine engine = factory.getEngineForFile(file);
        
        System.out.println("File: " + fileName);
        if (engine != null) {
            System.out.println("Selected Engine: " + engine.getEngineName());
            System.out.println("File Type: " + engine.getFileTypeDescription(file));
        } else {
            System.out.println("No engine found");
        }
        System.out.println();
    }
}