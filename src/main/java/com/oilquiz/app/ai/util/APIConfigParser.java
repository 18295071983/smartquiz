package com.oilquiz.app.ai.util;

import com.oilquiz.app.ai.model.APIConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API配置文件解析器
 * 支持从JSON、ENV、CSV等格式的配置文件中提取API配置信息
 */
public class APIConfigParser {

    public static class ParseResult {
        public List<APIConfig> configs = new ArrayList<>();
        public String errorMessage;
        public String detectedFormat;
    }

    /**
     * 从文件内容解析配置
     */
    public static ParseResult parse(String content) {
        ParseResult result = new ParseResult();
        
        if (content == null || content.trim().isEmpty()) {
            result.errorMessage = "文件内容为空";
            return result;
        }

        content = content.trim();

        // 自动检测格式
        if (content.startsWith("{")) {
            result.detectedFormat = "JSON";
            parseJson(content, result);
        } else if (content.startsWith("[")) {
            result.detectedFormat = "JSON Array";
            parseJsonArray(content, result);
        } else if (content.contains(",") && content.split("\n").length >= 2) {
            // 检测是否为CSV格式（包含逗号分隔符和多行）
            result.detectedFormat = "CSV";
            parseCsv(content, result);
            if (result.configs.isEmpty() && result.errorMessage == null) {
                // CSV解析失败，尝试ENV解析
                result.detectedFormat = "ENV";
                parseEnv(content, result);
            }
        } else {
            result.detectedFormat = "ENV";
            parseEnv(content, result);
        }

        return result;
    }

    /**
     * 从InputStream解析配置
     */
    public static ParseResult parse(InputStream inputStream) {
        try {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();
            return parse(content.toString());
        } catch (Exception e) {
            ParseResult result = new ParseResult();
            result.errorMessage = "读取文件失败: " + e.getMessage();
            return result;
        }
    }

    /**
     * 从文件解析配置
     */
    public static ParseResult parseFile(File file) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();
            return parse(content.toString());
        } catch (Exception e) {
            ParseResult result = new ParseResult();
            result.errorMessage = "读取文件失败: " + e.getMessage();
            return result;
        }
    }

    private static void parseJson(String content, ParseResult result) {
        try {
            JSONObject json = new JSONObject(content);
            
            // 检测是否为多配置格式（如OpenAI的env格式JSON化）
            if (json.has("OPENAI_API_KEY") || json.has("api_keys")) {
                parseEnvFromJson(json, result);
                return;
            }

            // 单配置格式
            APIConfig config = parseSingleConfig(json, null);
            if (config != null) {
                result.configs.add(config);
            }
        } catch (JSONException e) {
            result.errorMessage = "JSON解析失败: " + e.getMessage();
        }
    }

    private static void parseJsonArray(String content, ParseResult result) {
        try {
            JSONArray array = new JSONArray(content);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                APIConfig config = parseSingleConfig(item, null);
                if (config != null) {
                    result.configs.add(config);
                }
            }
        } catch (JSONException e) {
            result.errorMessage = "JSON解析失败: " + e.getMessage();
        }
    }

    private static void parseEnv(String content, ParseResult result) {
        Pattern keyPattern = Pattern.compile("^\\s*([A-Z_]+)\\s*=\\s*(.+)$");
        Pattern valuePattern = Pattern.compile("^[\"'](.+)[\"']\\s*$");

        String[] lines = content.split("\n");
        String openAiKey = null, openAiBase = null, anthropicKey = null, 
               googleKey = null, deepseekKey = null, deepseekBase = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            Matcher keyMatcher = keyPattern.matcher(trimmed);
            if (keyMatcher.matches()) {
                String key = keyMatcher.group(1);
                String value = keyMatcher.group(2);
                
                // 去除引号
                Matcher valueMatcher = valuePattern.matcher(value);
                if (valueMatcher.matches()) {
                    value = valueMatcher.group(1);
                }

                switch (key) {
                    case "OPENAI_API_KEY":
                        openAiKey = value;
                        break;
                    case "OPENAI_API_BASE":
                    case "OPENAI_BASE_URL":
                        openAiBase = value;
                        break;
                    case "ANTHROPIC_API_KEY":
                        anthropicKey = value;
                        break;
                    case "GOOGLE_API_KEY":
                    case "GEMINI_API_KEY":
                        googleKey = value;
                        break;
                    case "DEEPSEEK_API_KEY":
                        deepseekKey = value;
                        break;
                    case "DEEPSEEK_BASE_URL":
                        deepseekBase = value;
                        break;
                }
            }
        }

        if (openAiKey != null) {
            APIConfig config = new APIConfig();
            config.setName("OpenAI");
            config.setServiceType(APIConfig.ServiceType.OPENAI);
            config.setApiKey(openAiKey);
            config.setApiHost(openAiBase != null ? openAiBase : "https://api.openai.com/v1");
            result.configs.add(config);
        }

        if (anthropicKey != null) {
            APIConfig config = new APIConfig();
            config.setName("Anthropic Claude");
            config.setServiceType(APIConfig.ServiceType.ANTHROPIC);
            config.setApiKey(anthropicKey);
            config.setApiHost("https://api.anthropic.com");
            result.configs.add(config);
        }

        if (googleKey != null) {
            APIConfig config = new APIConfig();
            config.setName("Google Gemini");
            config.setServiceType(APIConfig.ServiceType.GOOGLE);
            config.setApiKey(googleKey);
            config.setApiHost("https://generativelanguage.googleapis.com/v1beta");
            result.configs.add(config);
        }

        if (deepseekKey != null) {
            APIConfig config = new APIConfig();
            config.setName("DeepSeek");
            config.setServiceType(APIConfig.ServiceType.CUSTOM);
            config.setApiKey(deepseekKey);
            config.setApiHost(deepseekBase != null ? deepseekBase : "https://api.deepseek.com");
            result.configs.add(config);
        }

        if (result.configs.isEmpty() && result.errorMessage == null) {
            result.errorMessage = "未检测到有效的API配置";
        }
    }

    /**
     * 解析CSV格式配置
     * 支持两种格式：
     * 1. 标准CSV表格格式（表头+数据行）：name,service_type,api_key,api_host,model
     * 2. 键值对逐行格式（每行一个key,value）：apiKey,sk-xxx
     * 
     * 支持的列名/键名（不区分大小写）：
     * name, service_type, provider, api_key, apikey, key, api_host, base_url, model, model_name, description
     */
    private static void parseCsv(String content, ParseResult result) {
        try {
            String[] lines = content.split("\n");
            if (lines.length < 2) {
                result.errorMessage = "CSV文件至少需要包含表头和一行数据";
                return;
            }

            // 检测是否为键值对逐行格式（每行只有两个元素：key,value）
            String[] firstLineParts = parseCsvLine(lines[0].trim());
            boolean isKeyValueFormat = firstLineParts.length == 2 && 
                                      !firstLineParts[0].toLowerCase().contains("name") &&
                                      !firstLineParts[0].toLowerCase().contains("service") &&
                                      !firstLineParts[0].toLowerCase().contains("type");

            if (isKeyValueFormat) {
                // 键值对逐行格式：解析所有键值对
                Map<String, String> keyValueMap = new java.util.HashMap<>();
                
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    
                    String[] parts = parseCsvLine(line);
                    if (parts.length >= 2) {
                        String key = parts[0].trim().toLowerCase();
                        String value = parts[1].trim();
                        // 去除反引号包裹
                        if (value.startsWith("`") && value.endsWith("`")) {
                            value = value.substring(1, value.length() - 1);
                        }
                        keyValueMap.put(key, value);
                    }
                }

                // 提取必要的配置项
                String apiKey = keyValueMap.get("apikey");
                if (apiKey == null || apiKey.isEmpty()) {
                    apiKey = keyValueMap.get("api_key");
                }
                if (apiKey == null || apiKey.isEmpty()) {
                    apiKey = keyValueMap.get("key");
                }

                if (apiKey == null || apiKey.isEmpty()) {
                    result.errorMessage = "未找到API Key（apikey/api_key/key）";
                    return;
                }

                APIConfig config = new APIConfig();
                
                // 名称
                String name = keyValueMap.get("name");
                if (name == null || name.isEmpty()) {
                    name = keyValueMap.get("workspacename");
                }
                if (name == null || name.isEmpty()) {
                    name = keyValueMap.get("workspace_name");
                }
                config.setName(name != null && !name.isEmpty() ? name : "导入配置");
                
                // API Key
                config.setApiKey(apiKey);
                
                // 服务类型
                String serviceHint = keyValueMap.get("service_type");
                if (serviceHint == null) serviceHint = keyValueMap.get("servicetype");
                if (serviceHint == null) serviceHint = keyValueMap.get("provider");
                config.setServiceType(detectServiceType(serviceHint, apiKey));
                
                // API Host - 优先使用openAiCompatible作为默认API地址
                String host = keyValueMap.get("openaicompatible");
                if (host == null || host.isEmpty()) {
                    host = keyValueMap.get("open_ai_compatible");
                }
                if (host == null || host.isEmpty()) {
                    host = keyValueMap.get("api_host");
                }
                if (host == null || host.isEmpty()) {
                    host = keyValueMap.get("apihost");
                }
                if (host == null || host.isEmpty()) {
                    host = keyValueMap.get("base_url");
                }
                if (host == null || host.isEmpty()) {
                    host = keyValueMap.get("baseurl");
                }
                config.setApiHost(host != null ? host : "");
                
                // 模型名称
                String model = keyValueMap.get("model");
                if (model == null) model = keyValueMap.get("model_name");
                if (model == null) model = keyValueMap.get("modelname");
                config.setModelName(model != null ? model : "");
                
                // 描述
                String desc = keyValueMap.get("description");
                if (desc == null) desc = keyValueMap.get("desc");
                config.setDescription(desc != null ? desc : "");
                
                config.setCategory(APIConfig.Category.AI);
                result.configs.add(config);
                
            } else {
                // 标准CSV表格格式
                // 解析表头
                String[] headers = parseCsvLine(lines[0]);
                int nameIdx = -1, serviceIdx = -1, apiKeyIdx = -1, hostIdx = -1, modelIdx = -1, descIdx = -1;
                
                for (int i = 0; i < headers.length; i++) {
                    String header = headers[i].toLowerCase().trim();
                    if (header.equals("name")) {
                        nameIdx = i;
                    } else if (header.contains("service") || header.contains("type") || 
                               header.equals("provider")) {
                        serviceIdx = i;
                    } else if (header.equals("api_key") || header.equals("apikey") || header.equals("key")) {
                        apiKeyIdx = i;
                    } else if (header.contains("host") || header.contains("url")) {
                        hostIdx = i;
                    } else if (header.equals("model") || header.equals("model_name") || header.equals("modelname")) {
                        modelIdx = i;
                    } else if (header.equals("description") || header.equals("desc")) {
                        descIdx = i;
                    }
                }

                if (apiKeyIdx < 0) {
                    result.errorMessage = "CSV中未找到API Key列";
                    return;
                }

                // 解析数据行
                for (int row = 1; row < lines.length; row++) {
                    String line = lines[row].trim();
                    if (line.isEmpty()) continue;

                    String[] values = parseCsvLine(line);
                    
                    String apiKey = apiKeyIdx < values.length ? values[apiKeyIdx].trim() : "";
                    if (apiKey.isEmpty()) continue;

                    APIConfig config = new APIConfig();
                    
                    // 名称
                    String name = nameIdx >= 0 && nameIdx < values.length ? 
                        values[nameIdx].trim() : "导入配置 " + row;
                    config.setName(name.isEmpty() ? "导入配置 " + row : name);
                    
                    // API Key
                    config.setApiKey(apiKey);
                    
                    // 服务类型
                    String serviceHint = serviceIdx >= 0 && serviceIdx < values.length ? 
                        values[serviceIdx].trim() : "";
                    config.setServiceType(detectServiceType(serviceHint, apiKey));
                    
                    // API Host
                    String host = hostIdx >= 0 && hostIdx < values.length ? 
                        values[hostIdx].trim() : "";
                    config.setApiHost(host);
                    
                    // 模型名称
                    String model = modelIdx >= 0 && modelIdx < values.length ? 
                        values[modelIdx].trim() : "";
                    config.setModelName(model);
                    
                    // 描述
                    String desc = descIdx >= 0 && descIdx < values.length ? 
                        values[descIdx].trim() : "";
                    config.setDescription(desc);
                    
                    config.setCategory(APIConfig.Category.AI);
                    result.configs.add(config);
                }
            }
        } catch (Exception e) {
            result.errorMessage = "CSV解析失败: " + e.getMessage();
        }
    }

    /**
     * 解析CSV单行，处理引号包裹的字段
     */
    private static String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());
        return result.toArray(new String[0]);
    }

    private static void parseEnvFromJson(JSONObject json, ParseResult result) {
        // 处理JSON化的env格式
        if (json.has("OPENAI_API_KEY")) {
            APIConfig config = new APIConfig();
            config.setName("OpenAI");
            config.setServiceType(APIConfig.ServiceType.OPENAI);
            config.setApiKey(json.optString("OPENAI_API_KEY"));
            config.setApiHost(json.optString("OPENAI_API_BASE", 
                json.optString("OPENAI_BASE_URL", "https://api.openai.com/v1")));
            config.setModelName(json.optString("default_model", ""));
            result.configs.add(config);
        }

        if (json.has("ANTHROPIC_API_KEY")) {
            APIConfig config = new APIConfig();
            config.setName("Anthropic Claude");
            config.setServiceType(APIConfig.ServiceType.ANTHROPIC);
            config.setApiKey(json.optString("ANTHROPIC_API_KEY"));
            config.setApiHost("https://api.anthropic.com");
            config.setModelName(json.optString("default_model", ""));
            result.configs.add(config);
        }

        if (json.has("GOOGLE_API_KEY") || json.has("GEMINI_API_KEY")) {
            APIConfig config = new APIConfig();
            config.setName("Google Gemini");
            config.setServiceType(APIConfig.ServiceType.GOOGLE);
            config.setApiKey(json.optString("GOOGLE_API_KEY", 
                json.optString("GEMINI_API_KEY")));
            config.setApiHost("https://generativelanguage.googleapis.com/v1beta");
            config.setModelName(json.optString("default_model", ""));
            result.configs.add(config);
        }
    }

    private static APIConfig parseSingleConfig(JSONObject json, String defaultName) {
        String apiKey = json.optString("api_key");
        
        // 尝试其他可能的字段名
        if (apiKey.isEmpty()) {
            apiKey = json.optString("apiKey");
        }
        if (apiKey.isEmpty()) {
            apiKey = json.optString("key");
        }
        if (apiKey.isEmpty()) {
            apiKey = json.optString("API_KEY");
        }
        
        if (apiKey.isEmpty()) {
            return null;
        }

        APIConfig config = new APIConfig();
        
        // 名称
        String name = json.optString("name");
        config.setName(name.isEmpty() ? (defaultName != null ? defaultName : "导入配置") : name);
        
        // API Key
        config.setApiKey(apiKey);
        
        // 服务类型
        String serviceType = json.optString("service_type", 
            json.optString("serviceType",
            json.optString("provider", "")));
        config.setServiceType(detectServiceType(serviceType, apiKey));
        
        // API Host
        String host = json.optString("api_host",
            json.optString("apiHost",
            json.optString("base_url",
            json.optString("baseUrl", ""))));
        config.setApiHost(host);
        
        // 模型名称
        String model = json.optString("model_name",
            json.optString("modelName",
            json.optString("model", "")));
        config.setModelName(model);
        
        // 描述
        config.setDescription(json.optString("description", ""));
        
        // 分类
        config.setCategory(json.optString("category", APIConfig.Category.AI));
        
        return config;
    }

    private static String detectServiceType(String hint, String apiKey) {
        if (hint != null && !hint.isEmpty()) {
            hint = hint.toLowerCase();
            if (hint.contains("openai") || hint.contains("open_ai")) {
                return APIConfig.ServiceType.OPENAI;
            }
            if (hint.contains("anthropic") || hint.contains("claude")) {
                return APIConfig.ServiceType.ANTHROPIC;
            }
            if (hint.contains("google") || hint.contains("gemini")) {
                return APIConfig.ServiceType.GOOGLE;
            }
            if (hint.contains("deepseek")) {
                return APIConfig.ServiceType.CUSTOM;
            }
        }

        // 根据API Key特征推断
        if (apiKey.startsWith("sk-")) {
            if (apiKey.startsWith("sk-ant-")) {
                return APIConfig.ServiceType.ANTHROPIC;
            }
            return APIConfig.ServiceType.OPENAI;
        }
        if (apiKey.startsWith("AIza")) {
            return APIConfig.ServiceType.GOOGLE;
        }
        if (apiKey.startsWith("sk-ds-")) {
            return APIConfig.ServiceType.CUSTOM;
        }

        return APIConfig.ServiceType.CUSTOM;
    }

    /**
     * 生成示例配置文件内容
     */
    public static String generateOpenAIExample() {
        return "{\n" +
            "  \"name\": \"My OpenAI Config\",\n" +
            "  \"service_type\": \"openai\",\n" +
            "  \"api_key\": \"sk-your-openai-api-key\",\n" +
            "  \"api_host\": \"https://api.openai.com/v1\",\n" +
            "  \"model_name\": \"gpt-4o\"\n" +
            "}";
    }

    public static String generateAnthropicExample() {
        return "{\n" +
            "  \"name\": \"My Claude Config\",\n" +
            "  \"service_type\": \"anthropic\",\n" +
            "  \"api_key\": \"sk-ant-your-anthropic-api-key\",\n" +
            "  \"api_host\": \"https://api.anthropic.com\",\n" +
            "  \"model_name\": \"claude-3-5-sonnet-20241022\"\n" +
            "}";
    }

    public static String generateGeminiExample() {
        return "{\n" +
            "  \"name\": \"My Gemini Config\",\n" +
            "  \"service_type\": \"google\",\n" +
            "  \"api_key\": \"AIzaSyYourGoogleApiKey\",\n" +
            "  \"api_host\": \"https://generativelanguage.googleapis.com/v1beta\",\n" +
            "  \"model_name\": \"gemini-2.0-flash\"\n" +
            "}";
    }

    public static String generateEnvExample() {
        return "# OpenAI Configuration\n" +
            "OPENAI_API_KEY=sk-your-openai-api-key\n" +
            "OPENAI_API_BASE=https://api.openai.com/v1\n\n" +
            "# Anthropic Configuration\n" +
            "ANTHROPIC_API_KEY=sk-ant-your-anthropic-api-key\n\n" +
            "# Google Gemini Configuration\n" +
            "GOOGLE_API_KEY=AIzaSyYourGoogleApiKey\n";
    }

    /**
     * 生成CSV示例文件内容
     */
    public static String generateCsvExample() {
        return "name,service_type,api_key,api_host,model\n" +
            "OpenAI,openai,sk-your-key,https://api.openai.com/v1,gpt-4o\n" +
            "Claude,anthropic,sk-ant-your-key,https://api.anthropic.com,claude-3-5-sonnet\n" +
            "Gemini,google,AIzaSyYourKey,https://generativelanguage.googleapis.com/v1beta,gemini-2.0-flash";
    }
}