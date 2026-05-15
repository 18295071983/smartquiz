package com.oilquiz.app.ai.tool;

import android.content.Context;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件分析工具，提供文件内容分析和统计功能
 */
public class FileAnalyzerTool implements AITool {
    private static final String TAG = "FileAnalyzerTool";
    private final Context context;
    
    private static final Set<String> STOP_WORDS = new HashSet<>();
    static {
        STOP_WORDS.add("的"); STOP_WORDS.add("是"); STOP_WORDS.add("在"); STOP_WORDS.add("有"); STOP_WORDS.add("和");
        STOP_WORDS.add("了"); STOP_WORDS.add("我"); STOP_WORDS.add("你"); STOP_WORDS.add("他"); STOP_WORDS.add("她");
        STOP_WORDS.add("它"); STOP_WORDS.add("这"); STOP_WORDS.add("那"); STOP_WORDS.add("这些"); STOP_WORDS.add("那些");
        STOP_WORDS.add("什么"); STOP_WORDS.add("怎么"); STOP_WORDS.add("为什么"); STOP_WORDS.add("因为"); STOP_WORDS.add("所以");
        STOP_WORDS.add("但是"); STOP_WORDS.add("如果"); STOP_WORDS.add("可以"); STOP_WORDS.add("可能"); STOP_WORDS.add("应该");
        STOP_WORDS.add("需要"); STOP_WORDS.add("会"); STOP_WORDS.add("不会"); STOP_WORDS.add("能"); STOP_WORDS.add("不");
        STOP_WORDS.add("一个"); STOP_WORDS.add("一些"); STOP_WORDS.add("所有"); STOP_WORDS.add("每个"); STOP_WORDS.add("没有");
        STOP_WORDS.add("我们"); STOP_WORDS.add("你们"); STOP_WORDS.add("他们"); STOP_WORDS.add("它们"); STOP_WORDS.add("这个");
        STOP_WORDS.add("那个"); STOP_WORDS.add("非常"); STOP_WORDS.add("很"); STOP_WORDS.add("更"); STOP_WORDS.add("最");
        STOP_WORDS.add("也"); STOP_WORDS.add("还"); STOP_WORDS.add("再"); STOP_WORDS.add("又"); STOP_WORDS.add("都");
        STOP_WORDS.add("就"); STOP_WORDS.add("要"); STOP_WORDS.add("去"); STOP_WORDS.add("来"); STOP_WORDS.add("上");
        STOP_WORDS.add("下"); STOP_WORDS.add("出"); STOP_WORDS.add("进"); STOP_WORDS.add("过"); STOP_WORDS.add("到");
        STOP_WORDS.add("for"); STOP_WORDS.add("and"); STOP_WORDS.add("the"); STOP_WORDS.add("is"); STOP_WORDS.add("are");
        STOP_WORDS.add("was"); STOP_WORDS.add("were"); STOP_WORDS.add("be"); STOP_WORDS.add("been"); STOP_WORDS.add("being");
        STOP_WORDS.add("have"); STOP_WORDS.add("has"); STOP_WORDS.add("had"); STOP_WORDS.add("do"); STOP_WORDS.add("does");
        STOP_WORDS.add("did"); STOP_WORDS.add("will"); STOP_WORDS.add("would"); STOP_WORDS.add("could"); STOP_WORDS.add("should");
        STOP_WORDS.add("may"); STOP_WORDS.add("might"); STOP_WORDS.add("must"); STOP_WORDS.add("shall"); STOP_WORDS.add("can");
    }
    
    public FileAnalyzerTool(Context context) {
        this.context = context;
    }
    
    @Override
    public String getName() {
        return "file_analyzer";
    }
    
    @Override
    public String getDescription() {
        return "文件分析工具，提供文件统计、关键词提取、内容分析等功能";
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = (String) parameters.get("action");
            if (action == null) {
                action = "analyze";
            }
            
            switch (action) {
                case "analyze":
                    return analyzeFile(parameters);
                case "statistics":
                    return getStatistics(parameters);
                case "keywords":
                    return extractKeywords(parameters);
                case "word_count":
                    return wordCount(parameters);
                case "detect_format":
                    return detectFormat(parameters);
                case "analyze_directory":
                    return analyzeDirectory(parameters);
                case "find_duplicates":
                    return findDuplicates(parameters);
                default:
                    return analyzeFile(parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error executing file analyzer: " + e.getMessage(), e);
            return new AIToolResult("文件分析失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult analyzeFile(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            Map<String, Object> analysis = new HashMap<>();
            
            analysis.put("fileInfo", getFileInfo(file));
            analysis.put("statistics", getFileStatistics(file));
            analysis.put("keywords", extractFileKeywords(file, 10));
            analysis.put("format", detectFileFormat(file));
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("analysis", analysis);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("分析文件失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult getStatistics(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("statistics", getFileStatistics(file));
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取统计信息失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult extractKeywords(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        Integer topN = (Integer) parameters.get("topN");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        if (topN == null) topN = 10;
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("keywords", extractFileKeywords(file, topN));
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("提取关键词失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult wordCount(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            Map<String, Object> stats = getFileStatistics(file);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("characters", stats.get("characters"));
            result.put("words", stats.get("words"));
            result.put("chineseChars", stats.get("chineseChars"));
            result.put("englishWords", stats.get("englishWords"));
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("统计失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult detectFormat(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("format", detectFileFormat(file));
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("检测格式失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult analyzeDirectory(Map<String, Object> parameters) {
        String dirPath = (String) parameters.get("directory_path");
        
        if (dirPath == null) {
            return new AIToolResult("缺少参数: directory_path", parameters);
        }
        
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            return new AIToolResult("目录不存在或不是目录: " + dirPath, parameters);
        }
        
        try {
            Map<String, Object> analysis = new HashMap<>();
            List<Map<String, Object>> filesInfo = new ArrayList<>();
            
            File[] files = dir.listFiles();
            if (files != null) {
                long totalSize = 0;
                int fileCount = 0;
                int dirCount = 0;
                
                for (File file : files) {
                    Map<String, Object> fileInfo = new HashMap<>();
                    fileInfo.put("name", file.getName());
                    fileInfo.put("path", file.getAbsolutePath());
                    fileInfo.put("isFile", file.isFile());
                    fileInfo.put("isDirectory", file.isDirectory());
                    
                    if (file.isFile()) {
                        fileInfo.put("size", file.length());
                        totalSize += file.length();
                        fileCount++;
                        
                        String extension = getFileExtension(file.getName());
                        fileInfo.put("extension", extension);
                        fileInfo.put("format", detectFormatByExtension(extension));
                    } else {
                        dirCount++;
                    }
                    
                    filesInfo.add(fileInfo);
                }
                
                analysis.put("files", filesInfo);
                analysis.put("totalFiles", fileCount);
                analysis.put("totalDirectories", dirCount);
                analysis.put("totalSize", totalSize);
                analysis.put("formats", countFormats(filesInfo));
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("analysis", analysis);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("分析目录失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult findDuplicates(Map<String, Object> parameters) {
        String dirPath = (String) parameters.get("directory_path");
        
        if (dirPath == null) {
            return new AIToolResult("缺少参数: directory_path", parameters);
        }
        
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            return new AIToolResult("目录不存在或不是目录: " + dirPath, parameters);
        }
        
        try {
            Map<Long, List<String>> sizeMap = new HashMap<>();
            
            collectFilesBySize(dir, sizeMap);
            
            List<Map<String, Object>> duplicates = new ArrayList<>();
            for (Map.Entry<Long, List<String>> entry : sizeMap.entrySet()) {
                List<String> files = entry.getValue();
                if (files.size() > 1) {
                    Map<String, Object> group = new HashMap<>();
                    group.put("size", entry.getKey());
                    group.put("count", files.size());
                    group.put("files", files);
                    duplicates.add(group);
                }
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("duplicates", duplicates);
            result.put("duplicateGroups", duplicates.size());
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("查找重复文件失败: " + e.getMessage(), parameters);
        }
    }
    
    private Map<String, Object> getFileInfo(File file) {
        Map<String, Object> info = new HashMap<>();
        info.put("name", file.getName());
        info.put("path", file.getAbsolutePath());
        info.put("size", file.length());
        info.put("lastModified", file.lastModified());
        info.put("extension", getFileExtension(file.getName()));
        return info;
    }
    
    private Map<String, Object> getFileStatistics(File file) {
        Map<String, Object> stats = new HashMap<>();
        
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            int characters = 0;
            int words = 0;
            int chineseChars = 0;
            int englishWords = 0;
            int lines = 0;
            int paragraphs = 0;
            boolean inWord = false;
            boolean inParagraph = false;
            
            int c;
            while ((c = reader.read()) != -1) {
                characters++;
                
                if (c == '\n') {
                    lines++;
                    if (inParagraph) {
                        paragraphs++;
                        inParagraph = false;
                    }
                }
                
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    inParagraph = true;
                    
                    if (Character.isLetter(c)) {
                        if (!inWord) {
                            inWord = true;
                            if (isChineseChar(c)) {
                                chineseChars++;
                            } else {
                                englishWords++;
                            }
                        }
                    } else {
                        inWord = false;
                    }
                } else {
                    inWord = false;
                }
            }
            
            if (inParagraph) paragraphs++;
            
            stats.put("characters", characters);
            stats.put("words", chineseChars + englishWords);
            stats.put("chineseChars", chineseChars);
            stats.put("englishWords", englishWords);
            stats.put("lines", lines);
            stats.put("paragraphs", paragraphs);
            
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to get statistics: " + e.getMessage());
        }
        
        return stats;
    }
    
    private List<Map<String, Object>> extractFileKeywords(File file, int topN) {
        List<Map<String, Object>> keywords = new ArrayList<>();
        
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Map<String, Integer> wordCount = new HashMap<>();
            StringBuilder wordBuffer = new StringBuilder();
            
            int c;
            while ((c = reader.read()) != -1) {
                if (Character.isLetterOrDigit(c)) {
                    wordBuffer.append((char) c);
                } else {
                    if (wordBuffer.length() > 0) {
                        String word = wordBuffer.toString().toLowerCase();
                        if (word.length() >= 2 && !STOP_WORDS.contains(word)) {
                            wordCount.put(word, wordCount.getOrDefault(word, 0) + 1);
                        }
                        wordBuffer = new StringBuilder();
                    }
                }
            }
            
            if (wordBuffer.length() > 0) {
                String word = wordBuffer.toString().toLowerCase();
                if (word.length() >= 2 && !STOP_WORDS.contains(word)) {
                    wordCount.put(word, wordCount.getOrDefault(word, 0) + 1);
                }
            }
            
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(wordCount.entrySet());
            sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
            
            for (int i = 0; i < Math.min(topN, sorted.size()); i++) {
                Map<String, Object> keyword = new HashMap<>();
                keyword.put("word", sorted.get(i).getKey());
                keyword.put("count", sorted.get(i).getValue());
                keywords.add(keyword);
            }
            
        } catch (Exception e) {
            AILogger.e(TAG, "Failed to extract keywords: " + e.getMessage());
        }
        
        return keywords;
    }
    
    private Map<String, Object> detectFileFormat(File file) {
        Map<String, Object> format = new HashMap<>();
        String name = file.getName();
        String extension = getFileExtension(name).toLowerCase();
        
        format.put("extension", extension);
        format.put("type", detectFormatByExtension(extension));
        format.put("encoding", "UTF-8");
        
        return format;
    }
    
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1);
        }
        return "";
    }
    
    private String detectFormatByExtension(String extension) {
        switch (extension) {
            case "txt": return "纯文本";
            case "md": return "Markdown";
            case "json": return "JSON";
            case "xml": return "XML";
            case "html": case "htm": return "HTML";
            case "java": return "Java";
            case "kt": return "Kotlin";
            case "py": return "Python";
            case "js": return "JavaScript";
            case "css": return "CSS";
            case "csv": return "CSV";
            case "xlsx": case "xls": return "Excel";
            case "docx": case "doc": return "Word";
            case "pdf": return "PDF";
            case "jpg": case "jpeg": case "png": case "gif": return "图片";
            case "mp4": case "avi": case "mov": return "视频";
            case "mp3": case "wav": case "flac": return "音频";
            default: return "未知";
        }
    }
    
    private boolean isChineseChar(int c) {
        return c >= '\u4e00' && c <= '\u9fa5';
    }
    
    private Map<String, Integer> countFormats(List<Map<String, Object>> filesInfo) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> fileInfo : filesInfo) {
            String format = (String) fileInfo.get("format");
            counts.put(format, counts.getOrDefault(format, 0) + 1);
        }
        return counts;
    }
    
    private void collectFilesBySize(File dir, Map<Long, List<String>> sizeMap) {
        File[] files = dir.listFiles();
        if (files == null) return;
        
        for (File file : files) {
            if (file.isDirectory()) {
                collectFilesBySize(file, sizeMap);
            } else {
                long size = file.length();
                sizeMap.computeIfAbsent(size, k -> new ArrayList<>()).add(file.getAbsolutePath());
            }
        }
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: analyze, statistics, keywords, word_count, detect_format, analyze_directory, find_duplicates");
        descriptions.put("file_path", "文件路径（必填，用于analyze, statistics, keywords, word_count, detect_format操作）");
        descriptions.put("directory_path", "目录路径（必填，用于analyze_directory, find_duplicates操作）");
        descriptions.put("topN", "关键词数量（用于keywords操作，默认10）");
        return descriptions;
    }
}