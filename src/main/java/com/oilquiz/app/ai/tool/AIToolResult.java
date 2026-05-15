package com.oilquiz.app.ai.tool;

import java.util.Map;

/**
 * AI工具执行结果
 */
public class AIToolResult {
    private final boolean success;
    private final Object result;
    private final String errorMessage;
    private final Map<String, Object> additionalInfo;
    
    /**
     * 创建成功结果
     * @param result 结果数据
     * @param additionalInfo 附加信息
     */
    public AIToolResult(Object result, Map<String, Object> additionalInfo) {
        this.success = true;
        this.result = result;
        this.errorMessage = null;
        this.additionalInfo = additionalInfo;
    }
    
    /**
     * 创建失败结果
     * @param errorMessage 错误信息
     * @param additionalInfo 附加信息
     */
    public AIToolResult(String errorMessage, Map<String, Object> additionalInfo) {
        this.success = false;
        this.result = null;
        this.errorMessage = errorMessage;
        this.additionalInfo = additionalInfo;
    }
    
    /**
     * 是否执行成功
     */
    public boolean isSuccess() {
        return success;
    }
    
    /**
     * 获取执行结果
     */
    public Object getResult() {
        return result;
    }
    
    /**
     * 获取错误信息
     */
    public String getErrorMessage() {
        return errorMessage;
    }
    
    /**
     * 获取附加信息
     */
    public Map<String, Object> getAdditionalInfo() {
        return additionalInfo;
    }
}
