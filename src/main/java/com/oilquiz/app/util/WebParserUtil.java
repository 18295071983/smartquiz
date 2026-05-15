package com.oilquiz.app.util;

import android.util.Log;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 网页解析工具类
 * 用于解析HTML网页内容
 */
public class WebParserUtil {
    private static final String TAG = "WebParserUtil";

    /**
     * 从文件解析HTML
     * @param file HTML文件
     * @return Jsoup Document对象
     */
    public static Document parseHtmlFromFile(File file) {
        try {
            Document doc = Jsoup.parse(file, "UTF-8");
            Log.i(TAG, "HTML文件解析成功: " + file.getAbsolutePath());
            return doc;
        } catch (IOException e) {
            Log.e(TAG, "解析HTML文件失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从字符串解析HTML
     * @param html HTML字符串
     * @return Jsoup Document对象
     */
    public static Document parseHtmlFromString(String html) {
        try {
            Document doc = Jsoup.parse(html);
            Log.i(TAG, "HTML字符串解析成功");
            return doc;
        } catch (Exception e) {
            Log.e(TAG, "解析HTML字符串失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 获取网页标题
     * @param doc Document对象
     * @return 标题
     */
    public static String getTitle(Document doc) {
        if (doc == null) {
            return null;
        }
        return doc.title();
    }

    /**
     * 获取网页正文文本（去除标签）
     * @param doc Document对象
     * @return 纯文本内容
     */
    public static String getPlainText(Document doc) {
        if (doc == null) {
            return null;
        }
        return doc.text();
    }

    /**
     * 获取所有链接
     * @param doc Document对象
     * @return 链接列表
     */
    public static List<String> getAllLinks(Document doc) {
        List<String> links = new ArrayList<>();
        if (doc == null) {
            return links;
        }

        Elements elements = doc.select("a[href]");
        for (Element element : elements) {
            String href = element.attr("abs:href");
            if (href != null && !href.isEmpty()) {
                links.add(href);
            }
        }

        Log.i(TAG, "获取到 " + links.size() + " 个链接");
        return links;
    }

    /**
     * 获取所有图片链接
     * @param doc Document对象
     * @return 图片链接列表
     */
    public static List<String> getAllImages(Document doc) {
        List<String> images = new ArrayList<>();
        if (doc == null) {
            return images;
        }

        Elements elements = doc.select("img[src]");
        for (Element element : elements) {
            String src = element.attr("abs:src");
            if (src != null && !src.isEmpty()) {
                images.add(src);
            }
        }

        Log.i(TAG, "获取到 " + images.size() + " 个图片");
        return images;
    }

    /**
     * 获取所有段落文本
     * @param doc Document对象
     * @return 段落列表
     */
    public static List<String> getAllParagraphs(Document doc) {
        List<String> paragraphs = new ArrayList<>();
        if (doc == null) {
            return paragraphs;
        }

        Elements elements = doc.select("p");
        for (Element element : elements) {
            String text = element.text();
            if (text != null && !text.trim().isEmpty()) {
                paragraphs.add(text);
            }
        }

        Log.i(TAG, "获取到 " + paragraphs.size() + " 个段落");
        return paragraphs;
    }

    /**
     * 获取所有表格数据
     * @param doc Document对象
     * @return 表格数据列表
     */
    public static List<List<String[]>> getAllTables(Document doc) {
        List<List<String[]>> tables = new ArrayList<>();
        if (doc == null) {
            return tables;
        }

        Elements tableElements = doc.select("table");
        for (Element table : tableElements) {
            List<String[]> tableData = new ArrayList<>();
            Elements rows = table.select("tr");
            for (Element row : rows) {
                List<String> rowData = new ArrayList<>();
                Elements cells = row.select("td, th");
                for (Element cell : cells) {
                    rowData.add(cell.text());
                }
                if (!rowData.isEmpty()) {
                    tableData.add(rowData.toArray(new String[0]));
                }
            }
            if (!tableData.isEmpty()) {
                tables.add(tableData);
            }
        }

        Log.i(TAG, "获取到 " + tables.size() + " 个表格");
        return tables;
    }

    /**
     * 通过CSS选择器查找元素
     * @param doc Document对象
     * @param selector CSS选择器
     * @return 元素列表
     */
    public static Elements selectElements(Document doc, String selector) {
        if (doc == null) {
            return new Elements();
        }
        return doc.select(selector);
    }

    /**
     * 通过ID查找元素
     * @param doc Document对象
     * @param id 元素ID
     * @return 元素
     */
    public static Element getElementById(Document doc, String id) {
        if (doc == null) {
            return null;
        }
        return doc.getElementById(id);
    }

    /**
     * 通过类名查找元素
     * @param doc Document对象
     * @param className 类名
     * @return 元素列表
     */
    public static Elements getElementsByClass(Document doc, String className) {
        if (doc == null) {
            return new Elements();
        }
        return doc.getElementsByClass(className);
    }

    /**
     * 通过标签名查找元素
     * @param doc Document对象
     * @param tag 标签名
     * @return 元素列表
     */
    public static Elements getElementsByTag(Document doc, String tag) {
        if (doc == null) {
            return new Elements();
        }
        return doc.getElementsByTag(tag);
    }

    /**
     * 获取元素的文本内容
     * @param element 元素
     * @return 文本内容
     */
    public static String getElementText(Element element) {
        if (element == null) {
            return null;
        }
        return element.text();
    }

    /**
     * 获取元素的属性值
     * @param element 元素
     * @param attribute 属性名
     * @return 属性值
     */
    public static String getElementAttribute(Element element, String attribute) {
        if (element == null) {
            return null;
        }
        return element.attr(attribute);
    }
}
