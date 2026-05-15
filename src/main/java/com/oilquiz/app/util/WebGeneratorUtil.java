package com.oilquiz.app.util;

import android.util.Log;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 网页生成工具类
 * 用于生成HTML网页文件
 */
public class WebGeneratorUtil {
    private static final String TAG = "WebGeneratorUtil";

    /**
     * 生成简单HTML页面
     * @param file 目标文件
     * @param title 页面标题
     * @param body 页面主体内容
     * @return 是否成功
     */
    public static boolean generateSimpleHtml(File file, String title, String body) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-CN\">\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\">\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("    <title>").append(title != null ? title : "").append("</title>\n");
        html.append("    <style>\n");
        html.append("        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 20px; line-height: 1.6; color: #333; background-color: #f5f5f5; }\n");
        html.append("        .container { max-width: 800px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }\n");
        html.append("        h1 { color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 10px; }\n");
        html.append("        h2 { color: #34495e; margin-top: 30px; }\n");
        html.append("        p { margin-bottom: 15px; }\n");
        html.append("        a { color: #3498db; text-decoration: none; }\n");
        html.append("        a:hover { text-decoration: underline; }\n");
        html.append("    </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("    <div class=\"container\">\n");
        html.append("        <h1>").append(title != null ? title : "").append("</h1>\n");
        html.append("        ").append(body != null ? body : "").append("\n");
        html.append("    </div>\n");
        html.append("</body>\n");
        html.append("</html>");

        return FileGeneratorUtil.generateTextFile(file, html.toString());
    }

    /**
     * 生成带段落的HTML页面
     * @param file 目标文件
     * @param title 页面标题
     * @param paragraphs 段落列表
     * @return 是否成功
     */
    public static boolean generateHtmlWithParagraphs(File file, String title, List<String> paragraphs) {
        StringBuilder body = new StringBuilder();
        if (paragraphs != null) {
            for (String paragraph : paragraphs) {
                if (paragraph != null && !paragraph.isEmpty()) {
                    body.append("        <p>").append(paragraph).append("</p>\n");
                }
            }
        }
        return generateSimpleHtml(file, title, body.toString());
    }

    /**
     * 生成带列表的HTML页面
     * @param file 目标文件
     * @param title 页面标题
     * @param items 列表项
     * @param ordered 是否有序列表
     * @return 是否成功
     */
    public static boolean generateHtmlWithList(File file, String title, List<String> items, boolean ordered) {
        StringBuilder body = new StringBuilder();
        if (items != null && !items.isEmpty()) {
            String listTag = ordered ? "ol" : "ul";
            body.append("        <").append(listTag).append(">\n");
            for (String item : items) {
                if (item != null && !item.isEmpty()) {
                    body.append("            <li>").append(item).append("</li>\n");
                }
            }
            body.append("        </").append(listTag).append(">\n");
        }
        return generateSimpleHtml(file, title, body.toString());
    }

    /**
     * 生成带表格的HTML页面
     * @param file 目标文件
     * @param title 页面标题
     * @param headers 表头
     * @param rows 数据行
     * @return 是否成功
     */
    public static boolean generateHtmlWithTable(File file, String title, String[] headers, List<String[]> rows) {
        StringBuilder body = new StringBuilder();
        
        // 生成表格
        body.append("        <table style=\"width: 100%; border-collapse: collapse; margin: 20px 0;\">\n");
        
        // 表头
        if (headers != null && headers.length > 0) {
            body.append("            <thead>\n");
            body.append("                <tr>\n");
            for (String header : headers) {
                body.append("                    <th style=\"background-color: #3498db; color: white; padding: 12px; text-align: left; border: 1px solid #ddd;\">")
                    .append(header).append("</th>\n");
            }
            body.append("                </tr>\n");
            body.append("            </thead>\n");
        }
        
        // 数据行
        if (rows != null && !rows.isEmpty()) {
            body.append("            <tbody>\n");
            for (String[] row : rows) {
                body.append("                <tr>\n");
                for (String cell : row) {
                    body.append("                    <td style=\"padding: 12px; border: 1px solid #ddd;\">")
                        .append(cell != null ? cell : "").append("</td>\n");
                }
                body.append("                </tr>\n");
            }
            body.append("            </tbody>\n");
        }
        
        body.append("        </table>\n");
        
        return generateSimpleHtml(file, title, body.toString());
    }

    /**
     * 生成带链接的HTML页面
     * @param file 目标文件
     * @param title 页面标题
     * @param links 链接映射（显示文本 -> URL）
     * @return 是否成功
     */
    public static boolean generateHtmlWithLinks(File file, String title, Map<String, String> links) {
        StringBuilder body = new StringBuilder();
        
        if (links != null && !links.isEmpty()) {
            body.append("        <ul>\n");
            for (Map.Entry<String, String> entry : links.entrySet()) {
                String text = entry.getKey();
                String url = entry.getValue();
                if (text != null && url != null) {
                    body.append("            <li><a href=\"").append(url).append("\">")
                        .append(text).append("</a></li>\n");
                }
            }
            body.append("        </ul>\n");
        }
        
        return generateSimpleHtml(file, title, body.toString());
    }

    /**
     * 生成带图片的HTML页面
     * @param file 目标文件
     * @param title 页面标题
     * @param imageUrls 图片URL列表
     * @return 是否成功
     */
    public static boolean generateHtmlWithImages(File file, String title, List<String> imageUrls) {
        StringBuilder body = new StringBuilder();
        
        if (imageUrls != null && !imageUrls.isEmpty()) {
            body.append("        <div style=\"display: flex; flex-wrap: wrap; gap: 20px;\">\n");
            for (String url : imageUrls) {
                if (url != null && !url.isEmpty()) {
                    body.append("            <img src=\"").append(url)
                        .append("\" style=\"max-width: 300px; max-height: 300px; object-fit: contain; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1);\">\n");
                }
            }
            body.append("        </div>\n");
        }
        
        return generateSimpleHtml(file, title, body.toString());
    }

    /**
     * 生成自定义样式的HTML页面
     * @param file 目标文件
     * @param title 页面标题
     * @param customStyle 自定义CSS样式
     * @param body 页面主体内容
     * @return 是否成功
     */
    public static boolean generateCustomHtml(File file, String title, String customStyle, String body) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-CN\">\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\">\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("    <title>").append(title != null ? title : "").append("</title>\n");
        if (customStyle != null && !customStyle.isEmpty()) {
            html.append("    <style>\n");
            html.append("        ").append(customStyle).append("\n");
            html.append("    </style>\n");
        }
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("    ").append(body != null ? body : "").append("\n");
        html.append("</body>\n");
        html.append("</html>");

        return FileGeneratorUtil.generateTextFile(file, html.toString());
    }

    /**
     * 生成完整的HTML字符串
     * @param title 页面标题
     * @param body 页面主体内容
     * @return HTML字符串
     */
    public static String generateHtmlString(String title, String body) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-CN\">\n");
        html.append("<head>\n");
        html.append("    <meta charset=\"UTF-8\">\n");
        html.append("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("    <title>").append(title != null ? title : "").append("</title>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("    <h1>").append(title != null ? title : "").append("</h1>\n");
        html.append("    ").append(body != null ? body : "").append("\n");
        html.append("</body>\n");
        html.append("</html>");
        return html.toString();
    }
}
