package com.oilquiz.app.util.render;

import android.util.Log;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PowerPointRenderEngine implements FileRenderEngine {
    private static final String TAG = "PowerPointRenderEngine";
    private static final String[] SUPPORTED_EXTENSIONS = {"pptx"};

    private Map<String, String> themeColors = new HashMap<>();

    public PowerPointRenderEngine() {
        initThemeColors();
    }

    private void initThemeColors() {
        themeColors.put("theme-accent-1", "c45911");
        themeColors.put("theme-accent-2", "2e75b6");
        themeColors.put("theme-accent-3", "833c0c");
        themeColors.put("theme-accent-4", "1f497d");
        themeColors.put("theme-accent-5", "7030a0");
        themeColors.put("theme-accent-6", "00b0f0");
        themeColors.put("theme-dark-1", "1f1f1f");
        themeColors.put("theme-dark-2", "2e2e2e");
        themeColors.put("theme-light-1", "ffffff");
        themeColors.put("theme-light-2", "d9d9d9");
    }

    @Override
    public boolean canRender(File file) {
        String fileName = file.getName().toLowerCase();
        for (String extension : SUPPORTED_EXTENSIONS) {
            if (fileName.endsWith("." + extension)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void render(File file, RenderCallback callback) {
        try {
            Log.d(TAG, "Rendering PowerPoint: " + file.getName());

            StringBuilder htmlContent = new StringBuilder();
            htmlContent.append("<!DOCTYPE html>");
            htmlContent.append("<html>");
            htmlContent.append("<head>");
            htmlContent.append("<meta charset='UTF-8'>");
            htmlContent.append("<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes'>");
            htmlContent.append("<style>");
            htmlContent.append(getEnhancedStyles());
            htmlContent.append("</style>");
            htmlContent.append("</head>");
            htmlContent.append("<body>");

            htmlContent.append("<div class='ppt-header'>");
            htmlContent.append("<h1>PowerPoint演示文稿</h1>");
            htmlContent.append("<div class='file-info'>");
            htmlContent.append("<span><strong>文件名:</strong> " + escapeHtml(file.getName()) + "</span>");
            htmlContent.append("<span><strong>大小:</strong> " + (file.length() / 1024) + "KB</span>");
            htmlContent.append("</div>");
            htmlContent.append("</div>");

            htmlContent.append(renderPPTX(file));

            htmlContent.append("</body>");
            htmlContent.append("</html>");

            callback.onSuccess(htmlContent.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error rendering PowerPoint: " + e.getMessage(), e);
            callback.onSuccess(renderErrorPage(file, e.getMessage()));
        }
    }

    private String getEnhancedStyles() {
        StringBuilder styles = new StringBuilder();

        styles.append("body { ");
        styles.append("font-family: 'Microsoft YaHei', 'PingFang SC', 'Hiragino Sans GB', 'Helvetica Neue', Arial, sans-serif; ");
        styles.append("margin: 0; padding: 0; ");
        styles.append("background-color: #");
        styles.append(themeColors.getOrDefault("theme-light-2", "d9d9d9"));
        styles.append("; ");
        styles.append("}");

        styles.append(".ppt-header { ");
        styles.append("background: linear-gradient(135deg, #");
        styles.append(themeColors.getOrDefault("theme-accent-1", "c45911"));
        styles.append(" 0%, #");
        styles.append(themeColors.getOrDefault("theme-accent-2", "2e75b6"));
        styles.append(" 100%); ");
        styles.append("color: white; padding: 24px 20px; ");
        styles.append("box-shadow: 0 4px 12px rgba(0,0,0,0.15); ");
        styles.append("}");

        styles.append(".ppt-header h1 { margin: 0 0 12px 0; font-size: 26px; font-weight: 600; letter-spacing: 0.5px; }");
        styles.append(".file-info { display: flex; gap: 24px; font-size: 14px; opacity: 0.95; flex-wrap: wrap; }");
        styles.append(".file-info span { background: rgba(255,255,255,0.15); padding: 4px 12px; border-radius: 4px; }");

        styles.append(".slides-container { padding: 24px 20px; max-width: 1400px; margin: 0 auto; }");
        styles.append(".slides-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 24px; }");

        styles.append(".slide-card { ");
        styles.append("background: white; border-radius: 12px; overflow: hidden; ");
        styles.append("box-shadow: 0 4px 15px rgba(0,0,0,0.1); ");
        styles.append("transition: transform 0.3s, box-shadow 0.3s; ");
        styles.append("}");

        styles.append(".slide-card:hover { ");
        styles.append("transform: translateY(-6px); ");
        styles.append("box-shadow: 0 12px 30px rgba(0,0,0,0.18); ");
        styles.append("}");

        styles.append(".slide-number { ");
        styles.append("background: linear-gradient(135deg, #");
        styles.append(themeColors.getOrDefault("theme-accent-2", "2e75b6"));
        styles.append(" 0%, #");
        styles.append(themeColors.getOrDefault("theme-dark-2", "2e2e2e"));
        styles.append(" 100%); ");
        styles.append("color: white; padding: 12px 18px; font-size: 14px; font-weight: 600; }");

        styles.append(".slide-content { padding: 18px; }");

        styles.append(".slide-title { ");
        styles.append("font-size: 18px; font-weight: bold; ");
        styles.append("color: #");
        styles.append(themeColors.getOrDefault("theme-dark-1", "1f1f1f"));
        styles.append("; ");
        styles.append("margin-bottom: 14px; padding-bottom: 12px; ");
        styles.append("border-bottom: 3px solid #");
        styles.append(themeColors.getOrDefault("theme-accent-1", "c45911"));
        styles.append("; ");
        styles.append("}");

        styles.append(".slide-text { ");
        styles.append("font-size: 15px; line-height: 1.8; ");
        styles.append("color: #444; ");
        styles.append("}");

        styles.append(".slide-text p { ");
        styles.append("margin: 0 0 12px 0; ");
        styles.append("padding: 10px 14px; ");
        styles.append("background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%); ");
        styles.append("border-left: 4px solid #");
        styles.append(themeColors.getOrDefault("theme-accent-2", "2e75b6"));
        styles.append("; ");
        styles.append("border-radius: 0 6px 6px 0; ");
        styles.append("}");

        styles.append(".slide-text p:first-child { ");
        styles.append("border-left-color: #");
        styles.append(themeColors.getOrDefault("theme-accent-1", "c45911"));
        styles.append("; ");
        styles.append("}");

        styles.append(".bold { font-weight: bold; }");
        styles.append(".italic { font-style: italic; }");
        styles.append(".underline { text-decoration: underline; }");

        styles.append(".text-red { color: #e74c3c; }");
        styles.append(".text-blue { color: #3498db; }");
        styles.append(".text-green { color: #27ae60; }");
        styles.append(".text-orange { color: #e67e22; }");
        styles.append(".text-purple { color: #9b59b6; }");
        styles.append(".text-yellow { color: #f1c40f; }");
        styles.append(".text-white { color: #ffffff; }");
        styles.append(".text-black { color: #000000; }");

        styles.append(".empty-slide { ");
        styles.append("color: #999; font-style: italic; text-align: center; ");
        styles.append("padding: 28px; background: #f8f9fa; border-radius: 6px; ");
        styles.append("}");

        styles.append("@media (max-width: 600px) { ");
        styles.append(".ppt-header h1 { font-size: 22px; } ");
        styles.append(".file-info { flex-direction: column; gap: 8px; } ");
        styles.append(".slides-grid { grid-template-columns: 1fr; } ");
        styles.append("}");

        return styles.toString();
    }

    private String renderPPTX(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='slides-container'>");
        sb.append("<div class='slides-grid'>");

        try (FileInputStream fis = new FileInputStream(file);
             XMLSlideShow pptx = new XMLSlideShow(fis)) {

            List<XSLFSlide> slides = pptx.getSlides();

            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                String slideContent = extractSlideContent(slide, i + 1);
                sb.append(slideContent);
            }
        }

        sb.append("</div>");
        sb.append("</div>");
        return sb.toString();
    }

    private String extractSlideContent(XSLFSlide slide, int slideNumber) {
        StringBuilder sb = new StringBuilder();

        sb.append("<div class='slide-card'>");
        sb.append("<div class='slide-number'>第 ").append(slideNumber).append(" 页</div>");
        sb.append("<div class='slide-content'>");

        StringBuilder titleText = new StringBuilder();
        StringBuilder bodyText = new StringBuilder();
        boolean hasContent = false;

        List<XSLFShape> shapes = slide.getShapes();
        for (XSLFShape shape : shapes) {
            if (shape instanceof XSLFTextShape) {
                XSLFTextShape textShape = (XSLFTextShape) shape;
                String text = textShape.getText();

                if (text == null || text.trim().isEmpty()) continue;
                hasContent = true;

                boolean isTitle = textShape.getTextType() != null &&
                                  textShape.getTextType().toString().contains("TITLE");

                if (isTitle || titleText.length() == 0) {
                    if (titleText.length() > 0) titleText.append("<br>");
                    titleText.append(escapeHtml(text.trim()));
                } else {
                    if (bodyText.length() > 0) bodyText.append("<br>");
                    bodyText.append(escapeHtml(text.trim()));
                }
            }
        }

        if (!hasContent) {
            sb.append("<div class='empty-slide'>此幻灯片为空</div>");
        } else {
            if (titleText.length() > 0) {
                sb.append("<div class='slide-title'>").append(titleText.toString()).append("</div>");
            }

            if (bodyText.length() > 0) {
                sb.append("<div class='slide-text'>");
                String[] lines = bodyText.toString().split("<br>");
                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        sb.append("<p>").append(line).append("</p>");
                    }
                }
                sb.append("</div>");
            }
        }

        sb.append("</div>");
        sb.append("</div>");

        return sb.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private String renderErrorPage(File file, String errorMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        sb.append("<style>body{font-family:Arial,sans-serif;margin:20px;background:#f5f5f5;}");
        sb.append(".container{background:white;padding:20px;border-radius:8px;max-width:800px;margin:0 auto;}");
        sb.append("h1{color:#333;}p{color:#666;}.error{color:#e74c3c;background:#fdf2f2;padding:10px;border-radius:4px;}</style>");
        sb.append("</head><body><div class='container'>");
        sb.append("<h1>PowerPoint演示文稿</h1>");
        sb.append("<p><strong>文件名:</strong> ").append(escapeHtml(file.getName())).append("</p>");
        sb.append("<p><strong>大小:</strong> ").append(file.length() / 1024).append("KB</p>");
        sb.append("<div class='error'><p>无法读取PowerPoint文件内容: ").append(escapeHtml(errorMessage)).append("</p></div>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    @Override
    public String getEngineName() {
        return "PowerPoint渲染引擎";
    }

    @Override
    public String getFileTypeDescription(File file) {
        return "PowerPoint演示文稿 (PPTX)";
    }
}