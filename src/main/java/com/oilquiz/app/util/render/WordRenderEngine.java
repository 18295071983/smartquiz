package com.oilquiz.app.util.render;

import android.util.Log;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WordRenderEngine implements FileRenderEngine {
    private static final String TAG = "WordRenderEngine";
    private static final String[] SUPPORTED_EXTENSIONS = {"docx"};

    private static final Map<String, int[]> PAPER_SIZES = new HashMap<>();
    static {
        PAPER_SIZES.put("A4", new int[]{11906, 16838});
        PAPER_SIZES.put("A3", new int[]{16838, 23842});
        PAPER_SIZES.put("A5", new int[]{8391, 11906});
        PAPER_SIZES.put("B4", new int[]{12992, 18358});
        PAPER_SIZES.put("B5", new int[]{10319, 12992});
        PAPER_SIZES.put("Letter", new int[]{12240, 15840});
        PAPER_SIZES.put("Legal", new int[]{12240, 20160});
        PAPER_SIZES.put("Tabloid", new int[]{15840, 24480});
        PAPER_SIZES.put("Executive", new int[]{10440, 15120});
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
            Log.d(TAG, "Rendering Word document: " + file.getName());

            PageInfo pageInfo = detectPageSettings(file);

            StringBuilder htmlContent = new StringBuilder();
            htmlContent.append("<!DOCTYPE html>");
            htmlContent.append("<html>");
            htmlContent.append("<head>");
            htmlContent.append("<meta charset='UTF-8'>");
            htmlContent.append("<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes'>");
            htmlContent.append("<style>");
            htmlContent.append(getEnhancedStyles(pageInfo));
            htmlContent.append("</style>");
            htmlContent.append("</head>");
            htmlContent.append("<body>");

            htmlContent.append("<div class='doc-header'>");
            htmlContent.append("<h1>Word文档</h1>");
            htmlContent.append("<div class='file-info'>");
            htmlContent.append("<span><strong>文件名:</strong> " + escapeHtml(file.getName()) + "</span>");
            htmlContent.append("<span><strong>大小:</strong> " + (file.length() / 1024) + "KB</span>");
            htmlContent.append("<span><strong>纸张:</strong> " + pageInfo.paperType + "</span>");
            if (pageInfo.orientation.equals("landscape")) {
                htmlContent.append("<span><strong>方向:</strong> 横向</span>");
            }
            htmlContent.append("</div>");
            htmlContent.append("</div>");

            htmlContent.append("<div class='document-container'>");

            try (FileInputStream fis = new FileInputStream(file);
                 XWPFDocument document = new XWPFDocument(fis)) {

                List<XWPFParagraph> paragraphs = document.getParagraphs();
                List<XWPFTable> tables = document.getTables();

                List<String> pageContents = paginateContent(paragraphs, tables, pageInfo);

                for (int i = 0; i < pageContents.size(); i++) {
                    htmlContent.append("<div class='paper' id='paper-").append(i + 1).append("'>");
                    htmlContent.append(pageContents.get(i));
                    htmlContent.append("<div class='page-number'>- ").append(i + 1).append(" -</div>");
                    htmlContent.append("</div>");
                }
            }

            htmlContent.append("</div>");

            htmlContent.append(renderPageSizeJavaScript(pageInfo));

            htmlContent.append("</body>");
            htmlContent.append("</html>");

            callback.onSuccess(htmlContent.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error rendering Word document: " + e.getMessage(), e);
            callback.onSuccess(renderErrorPage(file, e.getMessage()));
        }
    }

    private List<String> paginateContent(List<XWPFParagraph> paragraphs, List<XWPFTable> tables, PageInfo pageInfo) {
        List<String> pages = new ArrayList<>();
        StringBuilder currentPage = new StringBuilder();
        int contentHeight = 0;
        int maxContentHeight = pageInfo.height - pageInfo.topMargin - pageInfo.bottomMargin - 60;

        int tableIndex = 0;

        for (int i = 0; i < paragraphs.size(); i++) {
            XWPFParagraph paragraph = paragraphs.get(i);
            String paragraphHtml = renderParagraph(paragraph);

            if (!paragraphHtml.isEmpty()) {
                int estimatedHeight = estimateParagraphHeight(paragraph);

                if (contentHeight + estimatedHeight > maxContentHeight && contentHeight > 0) {
                    pages.add(currentPage.toString());
                    currentPage = new StringBuilder();
                    contentHeight = 0;
                }

                currentPage.append(paragraphHtml);
                contentHeight += estimatedHeight;
            }

            if (tableIndex < tables.size()) {
                XWPFTable table = tables.get(tableIndex);
                String tableHtml = renderTable(table);
                int estimatedTableHeight = estimateTableHeight(table);

                if (contentHeight + estimatedTableHeight > maxContentHeight && contentHeight > 0) {
                    pages.add(currentPage.toString());
                    currentPage = new StringBuilder();
                    contentHeight = 0;
                }

                currentPage.append(tableHtml);
                contentHeight += estimatedTableHeight;
                tableIndex++;
            }
        }

        while (tableIndex < tables.size()) {
            XWPFTable table = tables.get(tableIndex);
            String tableHtml = renderTable(table);
            int estimatedTableHeight = estimateTableHeight(table);

            if (contentHeight + estimatedTableHeight > maxContentHeight && contentHeight > 0) {
                pages.add(currentPage.toString());
                currentPage = new StringBuilder();
                contentHeight = 0;
            }

            currentPage.append(tableHtml);
            contentHeight += estimatedTableHeight;
            tableIndex++;
        }

        if (currentPage.length() > 0) {
            pages.add(currentPage.toString());
        }

        if (pages.isEmpty()) {
            pages.add("");
        }

        return pages;
    }

    private int estimateParagraphHeight(XWPFParagraph paragraph) {
        String text = paragraph.getText();
        if (text == null || text.isEmpty()) return 0;

        int lineCount = (text.length() / 40) + 1;
        return Math.max(lineCount * 28, 35);
    }

    private int estimateTableHeight(XWPFTable table) {
        int rows = table.getNumberOfRows();
        return Math.max(rows * 40, 80);
    }

    private PageInfo detectPageSettings(File file) {
        PageInfo pageInfo = new PageInfo();
        pageInfo.paperType = "A4";
        pageInfo.width = 794;
        pageInfo.height = 1123;
        pageInfo.orientation = "portrait";
        pageInfo.topMargin = 96;
        pageInfo.bottomMargin = 96;
        pageInfo.leftMargin = 85;
        pageInfo.rightMargin = 85;

        return pageInfo;
    }

    private String renderPageSizeJavaScript(PageInfo pageInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("<script>");

        sb.append("window.onload = function() { ");
        sb.append("var maxWidth = Math.min(window.innerWidth - 40, " + pageInfo.width + "); ");
        sb.append("var papers = document.querySelectorAll('.paper'); ");
        sb.append("papers.forEach(function(paper) { ");
        sb.append("paper.style.width = maxWidth + 'px'; ");
        sb.append("paper.style.minHeight = '" + pageInfo.height + "px'; ");
        if (pageInfo.orientation.equals("landscape")) {
            sb.append("paper.style.maxWidth = '" + pageInfo.height + "px'; ");
            sb.append("paper.style.minHeight = '" + pageInfo.width + "px'; ");
        }
        sb.append("}); ");
        sb.append("}; ");

        sb.append("</script>");
        return sb.toString();
    }

    private String getEnhancedStyles(PageInfo pageInfo) {
        StringBuilder styles = new StringBuilder();

        styles.append("body { ");
        styles.append("font-family: 'Microsoft YaHei', 'PingFang SC', 'Hiragino Sans GB', 'Helvetica Neue', Arial, sans-serif; ");
        styles.append("margin: 0; padding: 20px; ");
        styles.append("background: linear-gradient(135deg, #f5f7fa 0%, #e4e8ec 100%); ");
        styles.append("min-height: 100vh; ");
        styles.append("}");

        styles.append(".doc-header { ");
        styles.append("background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); ");
        styles.append("color: white; padding: 24px 28px; ");
        styles.append("box-shadow: 0 4px 12px rgba(0,0,0,0.15); ");
        styles.append("border-radius: 12px; margin-bottom: 24px; max-width: ").append(pageInfo.width + 80).append("px; margin-left: auto; margin-right: auto; ");
        styles.append("}");

        styles.append(".doc-header h1 { margin: 0 0 12px 0; font-size: 26px; font-weight: 600; letter-spacing: 0.5px; }");
        styles.append(".file-info { display: flex; gap: 24px; font-size: 14px; opacity: 0.95; flex-wrap: wrap; }");
        styles.append(".file-info span { background: rgba(255,255,255,0.15); padding: 6px 14px; border-radius: 6px; }");

        styles.append(".document-container { max-width: ").append(pageInfo.width + 80).append("px; margin: 0 auto; padding: 0; }");

        styles.append(".paper { ");
        styles.append("background: white; ");
        styles.append("width: ").append(pageInfo.width).append("px; ");
        styles.append("min-height: ").append(pageInfo.height).append("px; ");
        styles.append("box-shadow: 0 10px 40px rgba(0,0,0,0.15), 0 2px 10px rgba(0,0,0,0.08); ");
        styles.append("border-radius: 4px; margin: 0 auto 32px auto; ");
        styles.append("padding: ").append(pageInfo.topMargin).append("px ").append(pageInfo.rightMargin).append("px ").append(pageInfo.bottomMargin + 30).append("px ").append(pageInfo.leftMargin).append("px; ");
        styles.append("box-sizing: border-box; ");
        styles.append("position: relative; ");
        styles.append("transition: width 0.3s ease; ");
        styles.append("}");

        styles.append(".paper::before { ");
        styles.append("content: ''; ");
        styles.append("position: absolute; top: 0; left: 0; right: 0; bottom: 0; ");
        styles.append("border: 1px solid rgba(0,0,0,0.05); border-radius: 4px; ");
        styles.append("pointer-events: none; ");
        styles.append("}");

        styles.append(".page-number { ");
        styles.append("position: absolute; bottom: 20px; left: 50%; transform: translateX(-50%); ");
        styles.append("font-size: 14px; color: #999; ");
        styles.append("}");

        styles.append(".paragraph { ");
        styles.append("margin-bottom: 14px; line-height: 1.8; ");
        styles.append("font-size: 16px; color: #333; ");
        styles.append("text-align: justify; text-indent: 2em; ");
        styles.append("letter-spacing: 0.3px; ");
        styles.append("}");

        styles.append(".paragraph-heading { ");
        styles.append("font-size: 22px; font-weight: bold; ");
        styles.append("color: #2c3e50; margin-top: 28px; margin-bottom: 16px; ");
        styles.append("padding-bottom: 10px; border-bottom: 2px solid #667eea; ");
        styles.append("text-align: left; text-indent: 0; ");
        styles.append("}");

        styles.append(".paragraph-title { ");
        styles.append("font-size: 28px; font-weight: bold; ");
        styles.append("color: #2c3e50; margin-top: 36px; margin-bottom: 20px; ");
        styles.append("text-align: left; text-indent: 0; ");
        styles.append("}");

        styles.append(".bold { font-weight: bold; }");
        styles.append(".italic { font-style: italic; }");
        styles.append(".underline { text-decoration: underline; }");
        styles.append(".strikethrough { text-decoration: line-through; }");

        styles.append("ul, ol { margin: 12px 0; padding-left: 36px; }");
        styles.append("li { margin-bottom: 8px; line-height: 1.8; }");

        styles.append(".table-container { overflow-x: auto; margin: 18px 0; border-radius: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); }");

        styles.append("table { ");
        styles.append("border-collapse: collapse; ");
        styles.append("width: 100%; min-width: 100%; ");
        styles.append("}");

        styles.append("th { ");
        styles.append("background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); ");
        styles.append("color: white; padding: 14px 16px; text-align: left; font-weight: bold; ");
        styles.append("border: 1px solid #ddd; ");
        styles.append("}");

        styles.append("td { ");
        styles.append("padding: 12px 14px; border: 1px solid #ddd; vertical-align: top; ");
        styles.append("}");

        styles.append("tr:nth-child(even) { background-color: #f9f9f9; }");
        styles.append("tr:hover { background-color: #f0f4ff; }");

        styles.append("blockquote { ");
        styles.append("margin: 18px 0; padding: 14px 24px; ");
        styles.append("background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%); ");
        styles.append("border-left: 4px solid #667eea; color: #555; font-style: italic; border-radius: 0 8px 8px 0; ");
        styles.append("}");

        styles.append("@media (max-width: 600px) { ");
        styles.append("body { padding: 10px; } ");
        styles.append(".doc-header { padding: 18px 20px; border-radius: 8px; margin-bottom: 16px; } ");
        styles.append(".doc-header h1 { font-size: 22px; } ");
        styles.append(".file-info { flex-direction: column; gap: 8px; } ");
        styles.append(".paper { padding: ").append(pageInfo.topMargin / 2).append("px 16px ").append(pageInfo.bottomMargin / 2 + 20).append("px; border-radius: 8px; width: auto !important; min-height: auto !important; box-shadow: 0 4px 16px rgba(0,0,0,0.1); margin-bottom: 20px; } ");
        styles.append(".paragraph { font-size: 14px; text-indent: 1.5em; } ");
        styles.append(".paragraph-heading { font-size: 18px; margin-top: 20px; margin-bottom: 12px; } ");
        styles.append(".paragraph-title { font-size: 22px; margin-top: 24px; margin-bottom: 16px; } ");
        styles.append("th, td { padding: 10px 8px; font-size: 13px; } ");
        styles.append("}");

        return styles.toString();
    }

    private String renderParagraph(XWPFParagraph paragraph) {
        if (paragraph == null) return "";

        String style = paragraph.getStyle();
        StringBuilder sb = new StringBuilder();

        List<XWPFRun> runs = paragraph.getRuns();
        if (runs.isEmpty()) {
            String text = paragraph.getText();
            if (text != null && !text.trim().isEmpty()) {
                return "<p class='paragraph'>" + escapeHtml(text.trim()) + "</p>";
            }
            return "";
        }

        String paragraphClass = "paragraph";
        if (style != null) {
            if (style.contains("Heading1") || style.contains("标题1") || style.contains("Title")) {
                paragraphClass = "paragraph-title";
            } else if (style.contains("Heading2") || style.contains("标题2") || style.contains("Heading")) {
                paragraphClass = "paragraph-heading";
            }
        }

        StringBuilder content = new StringBuilder();
        for (XWPFRun run : runs) {
            String runText = run.text();
            if (runText == null || runText.isEmpty()) continue;

            String runClass = "";

            if (run.isBold()) runClass += " bold";
            if (run.isItalic()) runClass += " italic";
            if (run.isStrikeThrough()) runClass += " strikethrough";

            if (run.getUnderline() != null) {
                org.apache.poi.xwpf.usermodel.UnderlinePatterns underline = run.getUnderline();
                if (underline == org.apache.poi.xwpf.usermodel.UnderlinePatterns.SINGLE) {
                    runClass += " underline";
                }
            }

            String text = escapeHtml(runText);
            if (!runClass.isEmpty()) {
                content.append("<span class='").append(runClass.trim()).append("'>").append(text).append("</span>");
            } else {
                content.append(text);
            }
        }

        if (content.length() == 0) return "";

        return "<p class='" + paragraphClass + "'>" + content.toString() + "</p>";
    }

    private String renderTable(XWPFTable table) {
        if (table == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='table-container'>");
        sb.append("<table>");

        List<XWPFTableRow> rows = table.getRows();
        if (rows.isEmpty()) {
            sb.append("</table></div>");
            return sb.toString();
        }

        for (int i = 0; i < rows.size(); i++) {
            XWPFTableRow row = rows.get(i);
            List<XWPFTableCell> cells = row.getTableCells();

            if (i == 0) {
                sb.append("<thead><tr>");
            } else {
                sb.append("<tr>");
            }

            for (XWPFTableCell cell : cells) {
                List<XWPFParagraph> paragraphs = cell.getParagraphs();
                StringBuilder cellText = new StringBuilder();
                for (XWPFParagraph p : paragraphs) {
                    for (XWPFRun run : p.getRuns()) {
                        String text = run.text();
                        if (text != null) {
                            String runStyle = "";
                            if (run.isBold()) runStyle = "font-weight: bold;";
                            if (!runStyle.isEmpty()) {
                                cellText.append("<span style='").append(runStyle).append("'>").append(escapeHtml(text)).append("</span>");
                            } else {
                                cellText.append(escapeHtml(text));
                            }
                        }
                    }
                    if (p.getRuns().isEmpty()) {
                        String t = p.getText();
                        if (t != null) cellText.append(escapeHtml(t));
                    }
                }

                if (i == 0) {
                    sb.append("<th>").append(cellText.toString()).append("</th>");
                } else {
                    sb.append("<td>").append(cellText.toString()).append("</td>");
                }
            }

            if (i == 0) {
                sb.append("</tr></thead><tbody>");
            } else {
                sb.append("</tr>");
            }
        }

        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String renderErrorPage(File file, String errorMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        sb.append("<style>body{font-family:Arial,sans-serif;margin:20px;background:#f5f5f5;}");
        sb.append(".container{background:white;padding:20px;border-radius:8px;max-width:800px;margin:0 auto;}");
        sb.append("h1{color:#333;}p{color:#666;}.error{color:#e74c3c;background:#fdf2f2;padding:10px;border-radius:4px;}</style>");
        sb.append("</head><body><div class='container'>");
        sb.append("<h1>Word文档</h1>");
        sb.append("<p><strong>文件名:</strong> ").append(escapeHtml(file.getName())).append("</p>");
        sb.append("<p><strong>大小:</strong> ").append(file.length() / 1024).append("KB</p>");
        sb.append("<div class='error'><p>无法读取Word文件内容: ").append(escapeHtml(errorMessage)).append("</p></div>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    @Override
    public String getEngineName() {
        return "Word文档渲染引擎";
    }

    @Override
    public String getFileTypeDescription(File file) {
        return "Word文档 (DOCX)";
    }

    private static class PageInfo {
        String paperType;
        int width;
        int height;
        String orientation;
        int topMargin;
        int bottomMargin;
        int leftMargin;
        int rightMargin;
    }
}
