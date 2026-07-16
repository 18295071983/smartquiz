package com.oilquiz.app.ai.agent;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 输入验证器
 * 验证用户输入是否符合要求
 */
public class InputValidator {
    
    /**
     * 验证结果
     */
    public static class ValidationResult {
        public boolean valid;
        public String errorMessage;
        public String correctFormat;
        public List<String> examples;
        
        public ValidationResult(boolean valid, String errorMessage, 
                              String correctFormat, List<String> examples) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.correctFormat = correctFormat;
            this.examples = examples != null ? examples : new ArrayList<>();
        }
        
        public static ValidationResult success() {
            return new ValidationResult(true, null, null, null);
        }
        
        public static ValidationResult failure(String error, String format, List<String> examples) {
            return new ValidationResult(false, error, format, examples);
        }
    }
    
    /**
     * 验证用户输入
     * @param toolName 工具名称
     * @param paramName 参数名称
     * @param value 用户输入值
     * @return 验证结果
     */
    public static ValidationResult validateInput(String toolName, String paramName, String value) {
        if (value == null || value.trim().isEmpty()) {
            return ValidationResult.failure(
                "输入不能为空",
                getCorrectFormat(toolName, paramName),
                getExamples(toolName, paramName)
            );
        }
        
        String normalizedParam = normalizeParamName(paramName);
        
        // 根据参数名选择验证方法
        switch (normalizedParam) {
            case "city":
            case "location":
            case "place":
                return validateCity(value);
                
            case "time":
            case "datetime":
            case "start_time":
            case "schedule_time":
                return validateDateTime(value);
                
            case "email":
            case "to_email":
            case "recipient_email":
                return validateEmail(value);
                
            case "phone":
            case "phone_number":
            case "mobile":
                return validatePhoneNumber(value);
                
            case "expression":
            case "calculation":
            case "formula":
                return validateExpression(value);
                
            case "query":
            case "keyword":
            case "search":
                return validateQuery(value);
                
            default:
                // 默认验证：非空且长度合理
                return validateGeneric(value);
        }
    }
    
    /**
     * 标准化参数名
     */
    private static String normalizeParamName(String paramName) {
        if (paramName == null) return "generic";
        return paramName.toLowerCase().replace("_", "");
    }
    
    /**
     * 验证城市名称
     */
    private static ValidationResult validateCity(String value) {
        // 基本验证：2-50个字符，可包含中文、英文、数字、空格
        if (value.length() < 2 || value.length() > 50) {
            return ValidationResult.failure(
                "城市名称长度应在2-50个字符之间",
                "城市名称",
                Arrays.asList("北京", "上海", "深圳", "New York", "东京")
            );
        }
        
        // 允许的字符：中文、英文、数字、空格、-
        String pattern = "^[\\u4e00-\\u9fa5a-zA-Z0-9\\s\\-]+$";
        if (!Pattern.matches(pattern, value)) {
            return ValidationResult.failure(
                "城市名称包含无效字符",
                "城市名称",
                Arrays.asList("北京", "上海", "深圳", "杭州", "广州")
            );
        }
        
        return ValidationResult.success();
    }
    
    /**
     * 验证日期时间
     */
    private static ValidationResult validateDateTime(String value) {
        value = value.trim();
        
        // 相对时间表达
        if (value.matches("^(今天|明天|后天|本周|下周|本月|下月).*")) {
            return ValidationResult.success();
        }
        
        // 标准时间格式：yyyy-MM-dd HH:mm 或 HH:mm
        if (value.matches("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}")) {
            return ValidationResult.success();
        }
        
        if (value.matches("\\d{2}:\\d{2}")) {
            return ValidationResult.success();
        }
        
        // 中文时间表达
        if (value.matches(".*上午.*\\d+.*点.*\\d+.*") || 
            value.matches(".*下午.*\\d+.*点.*\\d+.*") ||
            value.matches(".*晚上.*\\d+.*点.*\\d+.*")) {
            return ValidationResult.success();
        }
        
        return ValidationResult.failure(
            "时间格式不正确",
            "时间格式支持：\n• 相对时间：明天上午10点\n• 标准时间：2024-05-25 10:00\n• 简短时间：10:30",
            Arrays.asList("明天上午10点", "明天10:00", "2024-05-25 14:30", "下午3点")
        );
    }
    
    /**
     * 验证邮箱
     */
    private static ValidationResult validateEmail(String value) {
        String emailPattern = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
        
        if (!Pattern.matches(emailPattern, value)) {
            return ValidationResult.failure(
                "邮箱格式不正确",
                "邮箱格式：username@example.com",
                Arrays.asList("zhangsan@example.com", "li@test.org", "wang@company.cn")
            );
        }
        
        return ValidationResult.success();
    }
    
    /**
     * 验证电话号码
     */
    private static ValidationResult validatePhoneNumber(String value) {
        // 移除所有非数字字符
        String digits = value.replaceAll("[^0-9]", "");
        
        if (digits.length() != 11) {
            return ValidationResult.failure(
                "手机号应为11位数字",
                "手机号格式：1xxxxxxxxxx",
                Arrays.asList("13800138000", "18612345678", "15987654321")
            );
        }
        
        if (!digits.startsWith("1")) {
            return ValidationResult.failure(
                "手机号应以1开头",
                "手机号格式：1xxxxxxxxxx",
                Arrays.asList("13800138000", "18612345678", "15987654321")
            );
        }
        
        return ValidationResult.success();
    }
    
    /**
     * 验证数学表达式
     */
    private static ValidationResult validateExpression(String value) {
        if (value.trim().isEmpty()) {
            return ValidationResult.failure(
                "表达式不能为空",
                "数学表达式：如 1+2*3",
                Arrays.asList("1+2*3", "2^10", "100/5", "(1+2)*3")
            );
        }
        
        // 检查是否包含有效的数学运算
        String pattern = "[\\d+\\-*/().^%\\s]+";
        if (!Pattern.matches(pattern, value)) {
            return ValidationResult.failure(
                "表达式包含无效字符",
                "支持：数字、+、-、*、/、()、^、%",
                Arrays.asList("1+2*3", "2^10", "100/5", "(1+2)*3")
            );
        }
        
        return ValidationResult.success();
    }
    
    /**
     * 验证搜索关键词
     */
    private static ValidationResult validateQuery(String value) {
        if (value.trim().isEmpty()) {
            return ValidationResult.failure(
                "搜索关键词不能为空",
                "搜索关键词",
                Arrays.asList("人工智能", "机器学习", "北京天气", "最新新闻")
            );
        }
        
        if (value.trim().length() < 2) {
            return ValidationResult.failure(
                "搜索关键词太短",
                "请输入至少2个字符",
                Arrays.asList("AI", "科技", "新闻")
            );
        }
        
        return ValidationResult.success();
    }
    
    /**
     * 通用验证
     */
    private static ValidationResult validateGeneric(String value) {
        if (value.trim().length() < 1) {
            return ValidationResult.failure(
                "输入不能为空",
                null,
                null
            );
        }
        
        if (value.length() > 500) {
            return ValidationResult.failure(
                "输入过长，请控制在500字以内",
                null,
                null
            );
        }
        
        return ValidationResult.success();
    }
    
    /**
     * 获取正确格式说明
     */
    public static String getCorrectFormat(String toolName, String paramName) {
        String normalized = normalizeParamName(paramName);
        
        switch (normalized) {
            case "city":
            case "location":
                return "城市名称（2-50个字符）";
                
            case "time":
            case "datetime":
            case "start_time":
                return "时间格式：\n• 相对时间：明天上午10点\n• 标准时间：2024-05-25 10:00\n• 简短时间：10:30";
                
            case "email":
                return "邮箱格式：username@example.com";
                
            case "phone":
            case "phone_number":
                return "手机号格式：1xxxxxxxxxx（11位数字）";
                
            case "expression":
                return "数学表达式，支持：数字、+、-、*、/、()、^、%";
                
            case "query":
            case "keyword":
                return "搜索关键词（至少2个字符）";
                
            default:
                return null;
        }
    }
    
    /**
     * 获取示例
     */
    public static List<String> getExamples(String toolName, String paramName) {
        String normalized = normalizeParamName(paramName);
        
        switch (normalized) {
            case "city":
            case "location":
                return Arrays.asList("北京", "上海", "深圳", "杭州", "广州", "New York", "东京");
                
            case "time":
            case "datetime":
            case "start_time":
                return Arrays.asList("明天上午10点", "明天10:00", "2024-05-25 14:30", "下午3点");
                
            case "email":
                return Arrays.asList("zhangsan@example.com", "li@test.org", "wang@company.cn");
                
            case "phone":
            case "phone_number":
                return Arrays.asList("13800138000", "18612345678", "15987654321");
                
            case "expression":
                return Arrays.asList("1+2*3", "2^10", "100/5", "(1+2)*3");
                
            case "query":
            case "keyword":
                return Arrays.asList("人工智能", "机器学习", "北京天气", "最新新闻");
                
            default:
                return new ArrayList<>();
        }
    }
    
    /**
     * 生成验证错误提示
     */
    public static String generateErrorMessage(ValidationResult result, String paramName) {
        StringBuilder sb = new StringBuilder();
        sb.append("参数 ").append(paramName).append(" 验证失败\n");
        
        if (result.errorMessage != null) {
            sb.append("\n原因：").append(result.errorMessage);
        }
        
        if (result.correctFormat != null) {
            sb.append("\n\n正确格式：\n").append(result.correctFormat);
        }
        
        if (!result.examples.isEmpty()) {
            sb.append("\n\n示例：\n");
            for (int i = 0; i < Math.min(3, result.examples.size()); i++) {
                sb.append("• ").append(result.examples.get(i)).append("\n");
            }
        }
        
        return sb.toString();
    }
}