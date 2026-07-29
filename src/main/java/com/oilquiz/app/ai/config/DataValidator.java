package com.oilquiz.app.ai.config;

import com.oilquiz.app.ai.chat.ChatMessage;
import com.oilquiz.app.util.AILogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.regex.Pattern;

/**
 * DataValidator - 数据校验工具类
 * 
 * 功能：
 * 1. 数据类型校验 - 验证数据类型是否正确
 * 2. 范围校验 - 验证数值是否在有效范围内
 * 3. 格式校验 - 验证字符串格式是否正确
 * 4. 完整性校验 - 验证数据是否完整
 * 5. 一致性校验 - 验证数据是否一致
 */
public class DataValidator {

    private static final String TAG = "DataValidator";

    // 正则表达式
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");
    private static final Pattern URL_PATTERN = Pattern.compile("^https?://.*");
    private static final Pattern MODEL_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");

    /**
     * 校验结果
     */
    public static class ValidationResult {
        public boolean isValid;
        public String errorMessage;
        public String errorCode;

        public ValidationResult(boolean isValid) {
            this.isValid = isValid;
        }

        public ValidationResult(boolean isValid, String errorMessage) {
            this.isValid = isValid;
            this.errorMessage = errorMessage;
        }

        public ValidationResult(boolean isValid, String errorMessage, String errorCode) {
            this.isValid = isValid;
            this.errorMessage = errorMessage;
            this.errorCode = errorCode;
        }

        public static ValidationResult success() {
            return new ValidationResult(true);
        }

        public static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }

        public static ValidationResult error(String message, String code) {
            return new ValidationResult(false, message, code);
        }
    }

    // ========== 基础类型校验 ==========

    /**
     * 校验字符串是否非空
     */
    public static ValidationResult validateNotEmpty(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            return ValidationResult.error(fieldName + "不能为空", "EMPTY_FIELD");
        }
        return ValidationResult.success();
    }

    /**
     * 校验字符串长度
     */
    public static ValidationResult validateLength(String value, int min, int max, String fieldName) {
        if (value == null) {
            return ValidationResult.error(fieldName + "不能为空", "NULL_FIELD");
        }
        if (value.length() < min) {
            return ValidationResult.error(fieldName + "长度不能小于" + min, "TOO_SHORT");
        }
        if (value.length() > max) {
            return ValidationResult.error(fieldName + "长度不能大于" + max, "TOO_LONG");
        }
        return ValidationResult.success();
    }

    /**
     * 校验整数范围
     */
    public static ValidationResult validateRange(int value, int min, int max, String fieldName) {
        if (value < min) {
            return ValidationResult.error(fieldName + "不能小于" + min, "OUT_OF_RANGE");
        }
        if (value > max) {
            return ValidationResult.error(fieldName + "不能大于" + max, "OUT_OF_RANGE");
        }
        return ValidationResult.success();
    }

    /**
     * 校验浮点数范围
     */
    public static ValidationResult validateRange(double value, double min, double max, String fieldName) {
        if (value < min) {
            return ValidationResult.error(fieldName + "不能小于" + min, "OUT_OF_RANGE");
        }
        if (value > max) {
            return ValidationResult.error(fieldName + "不能大于" + max, "OUT_OF_RANGE");
        }
        return ValidationResult.success();
    }

    // ========== 格式校验 ==========

    /**
     * 校验URL格式
     */
    public static ValidationResult validateUrl(String url) {
        if (url == null || url.isEmpty()) {
            return ValidationResult.error("URL不能为空", "EMPTY_URL");
        }
        if (!URL_PATTERN.matcher(url).matches()) {
            return ValidationResult.error("URL格式无效", "INVALID_URL");
        }
        return ValidationResult.success();
    }

    /**
     * 校验模型名称格式
     */
    public static ValidationResult validateModelName(String name) {
        if (name == null || name.isEmpty()) {
            return ValidationResult.error("模型名称不能为空", "EMPTY_MODEL_NAME");
        }
        if (!MODEL_NAME_PATTERN.matcher(name).matches()) {
            return ValidationResult.error("模型名称只能包含字母、数字、下划线和连字符", "INVALID_MODEL_NAME");
        }
        return ValidationResult.success();
    }

    /**
     * 校验API Key格式
     */
    public static ValidationResult validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return ValidationResult.error("API Key不能为空", "EMPTY_API_KEY");
        }
        if (apiKey.length() < 10) {
            return ValidationResult.error("API Key长度不足", "API_KEY_TOO_SHORT");
        }
        return ValidationResult.success();
    }

    // ========== JSON校验 ==========

    /**
     * 校验JSON对象
     */
    public static ValidationResult validateJsonObject(JSONObject json, String requiredFields[]) {
        if (json == null) {
            return ValidationResult.error("JSON对象为空", "NULL_JSON");
        }

        for (String field : requiredFields) {
            if (!json.has(field)) {
                return ValidationResult.error("缺少必需字段: " + field, "MISSING_FIELD");
            }
        }

        return ValidationResult.success();
    }

    /**
     * 校验JSON字段类型
     */
    public static ValidationResult validateJsonFieldType(JSONObject json, String field, Class<?> expectedType) {
        if (json == null || !json.has(field)) {
            return ValidationResult.error("字段不存在: " + field, "FIELD_NOT_FOUND");
        }

        try {
            Object value = json.get(field);
            if (!expectedType.isInstance(value)) {
                return ValidationResult.error("字段类型错误: " + field, "INVALID_TYPE");
            }
        } catch (JSONException e) {
            return ValidationResult.error("字段解析错误: " + field, "PARSE_ERROR");
        }

        return ValidationResult.success();
    }

    // ========== 模型配置校验 ==========

    /**
     * 校验模型配置
     */
    public static ValidationResult validateModelConfig(JSONObject config) {
        if (config == null) {
            return ValidationResult.error("模型配置为空", "NULL_CONFIG");
        }

        // 校验GPU层数（最大 30 层）
        int gpuLayers = config.optInt("gpuLayers", -1);
        if (gpuLayers < -1 || gpuLayers > 30) {
            return ValidationResult.error("GPU层数无效（最大30层）", "INVALID_GPU_LAYERS");
        }

        // 校验线程数（最大 4 线程）
        int threadCount = config.optInt("threadCount", -1);
        if (threadCount < 1 || threadCount > 4) {
            return ValidationResult.error("线程数无效（最大4线程）", "INVALID_THREAD_COUNT");
        }

        // 校验批大小
        int batchSize = config.optInt("batchSize", -1);
        if (batchSize < 1 || batchSize > 256) {
            return ValidationResult.error("批大小无效", "INVALID_BATCH_SIZE");
        }

        // 校验内存限制
        int maxMemoryMB = config.optInt("maxMemoryMB", -1);
        if (maxMemoryMB < 512 || maxMemoryMB > 8192) {
            return ValidationResult.error("内存限制无效", "INVALID_MEMORY_LIMIT");
        }

        return ValidationResult.success();
    }

    /**
     * 校验AI配置
     */
    public static ValidationResult validateAIConfig(JSONObject config) {
        if (config == null) {
            return ValidationResult.error("AI配置为空", "NULL_CONFIG");
        }

        // 校验温度参数
        double temperature = config.optDouble("temperature", -1);
        if (temperature < 0 || temperature > 2) {
            return ValidationResult.error("温度参数无效", "INVALID_TEMPERATURE");
        }

        // 校验Top-P参数
        double topP = config.optDouble("topP", -1);
        if (topP < 0 || topP > 1) {
            return ValidationResult.error("Top-P参数无效", "INVALID_TOP_P");
        }

        // 校验最大Token数
        int maxTokens = config.optInt("maxTokens", -1);
        if (maxTokens < 1 || maxTokens > 8192) {
            return ValidationResult.error("最大Token数无效", "INVALID_MAX_TOKENS");
        }

        return ValidationResult.success();
    }

    // ========== 数据一致性校验 ==========

    /**
     * 校验模型状态一致性
     */
    public static ValidationResult validateModelStateConsistency(
            boolean isModelLoaded, String currentModel, boolean isInitialized) {
        if (isModelLoaded && (currentModel == null || currentModel.isEmpty())) {
            return ValidationResult.error("模型已加载但未指定模型名称", "INCONSISTENT_MODEL_STATE");
        }

        if (isInitialized && !isModelLoaded) {
            return ValidationResult.error("AI服务已初始化但模型未加载", "INCONSISTENT_INIT_STATE");
        }

        return ValidationResult.success();
    }

    /**
     * 校验聊天历史一致性
     */
    public static ValidationResult validateChatHistoryConsistency(
            List<ChatMessage> chatHistory, int expectedSize) {
        if (chatHistory == null) {
            return ValidationResult.error("聊天历史为空", "NULL_CHAT_HISTORY");
        }

        if (expectedSize > 0 && chatHistory.size() != expectedSize) {
            return ValidationResult.error("聊天历史大小不匹配", "SIZE_MISMATCH");
        }

        // 检查消息完整性
        for (int i = 0; i < chatHistory.size(); i++) {
            ChatMessage msg = chatHistory.get(i);
            if (msg == null) {
                return ValidationResult.error("消息[" + i + "]为空", "NULL_MESSAGE");
            }
            if (msg.id == null || msg.id.isEmpty()) {
                return ValidationResult.error("消息[" + i + "]ID为空", "EMPTY_MESSAGE_ID");
            }
        }

        return ValidationResult.success();
    }

    // ========== 批量校验 ==========

    /**
     * 批量校验配置
     */
    public static ValidationResult validateAll(JSONObject fullConfig) {
        if (fullConfig == null) {
            return ValidationResult.error("配置为空", "NULL_CONFIG");
        }

        // 校验模型配置
        if (fullConfig.has("model")) {
            ValidationResult result = validateModelConfig(fullConfig.optJSONObject("model"));
            if (!result.isValid) return result;
        }

        // 校验AI配置
        if (fullConfig.has("ai")) {
            ValidationResult result = validateAIConfig(fullConfig.optJSONObject("ai"));
            if (!result.isValid) return result;
        }

        return ValidationResult.success();
    }

    // ========== 日志方法 ==========

    /**
     * 记录校验错误
     */
    public static void logValidationError(String context, ValidationResult result) {
        if (!result.isValid) {
            AILogger.w(TAG, context + " - 校验失败: " + result.errorMessage + " [" + result.errorCode + "]");
        }
    }
}
