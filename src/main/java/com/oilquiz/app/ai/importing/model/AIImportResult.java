package com.oilquiz.app.ai.importing.model;

import com.oilquiz.app.model.Question;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 题库 AI 导入结果容器
 * <p>
 * 由 AI 导入编排引擎返回给 UI，承载有效题目、无效题目、错误信息、
 * 统计计数、字段覆盖率等导入过程的最终结果。
 * </p>
 * <p>
 * 纯 POJO，无 Android 依赖，可在任意层级安全传递。
 * </p>
 */
public class AIImportResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 有效题目（通过校验） */
    private List<Question> validQuestions;

    /** 无效题目（校验失败） */
    private List<Question> invalidQuestions;

    /** 错误信息列表，每条对应一个失败点 */
    private List<String> errorMessages;

    /** 检测到的题目总数 */
    private int totalDetected;

    /** 实际入库数 */
    private int totalImported;

    /** 去重命中的重复数 */
    private int duplicatedCount;

    /** 导入耗时（毫秒） */
    private long importTimeMs;

    /** 字段覆盖率（字段名 → 0~1） */
    private Map<String, Float> fieldCoverage;

    /** 源文件名 */
    private String sourceFileName;

    /** 整体是否成功 */
    private boolean success;

    /** 整体失败原因（success=false 时填充） */
    private String errorMessage;

    /** v4: 扩展信息（parseMethod/formatDetected 等） */
    private Map<String, String> extraInfo;

    /** 默认构造 */
    public AIImportResult() {
    }

    public List<Question> getValidQuestions() {
        return validQuestions;
    }

    public void setValidQuestions(List<Question> validQuestions) {
        this.validQuestions = validQuestions;
    }

    public List<Question> getInvalidQuestions() {
        return invalidQuestions;
    }

    public void setInvalidQuestions(List<Question> invalidQuestions) {
        this.invalidQuestions = invalidQuestions;
    }

    public List<String> getErrorMessages() {
        return errorMessages;
    }

    public void setErrorMessages(List<String> errorMessages) {
        this.errorMessages = errorMessages;
    }

    public int getTotalDetected() {
        return totalDetected;
    }

    public void setTotalDetected(int totalDetected) {
        this.totalDetected = totalDetected;
    }

    public int getTotalImported() {
        return totalImported;
    }

    public void setTotalImported(int totalImported) {
        this.totalImported = totalImported;
    }

    public int getDuplicatedCount() {
        return duplicatedCount;
    }

    public void setDuplicatedCount(int duplicatedCount) {
        this.duplicatedCount = duplicatedCount;
    }

    public long getImportTimeMs() {
        return importTimeMs;
    }

    public void setImportTimeMs(long importTimeMs) {
        this.importTimeMs = importTimeMs;
    }

    public Map<String, Float> getFieldCoverage() {
        return fieldCoverage;
    }

    public void setFieldCoverage(Map<String, Float> fieldCoverage) {
        this.fieldCoverage = fieldCoverage;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Map<String, String> getExtraInfo() {
        return extraInfo;
    }

    public void setExtraInfo(Map<String, String> extraInfo) {
        this.extraInfo = extraInfo;
    }

    /** 返回有效题目数 */
    public int getValidCount() {
        return validQuestions == null ? 0 : validQuestions.size();
    }

    /** 返回无效题目数 */
    public int getInvalidCount() {
        return invalidQuestions == null ? 0 : invalidQuestions.size();
    }

    /** 返回有效与无效题目总数 */
    public int getTotalCount() {
        return getValidCount() + getInvalidCount();
    }

    /**
     * 快速构造失败结果。
     *
     * @param message 失败原因
     * @return success=false 的结果实例
     */
    public static AIImportResult failure(String message) {
        AIImportResult result = new AIImportResult();
        result.setSuccess(false);
        result.setErrorMessage(message);
        result.setValidQuestions(new ArrayList<Question>());
        result.setInvalidQuestions(new ArrayList<Question>());
        result.setErrorMessages(new ArrayList<String>());
        return result;
    }

    /**
     * 快速构造成功结果。
     *
     * @param validQuestions   有效题目列表
     * @param invalidQuestions 无效题目列表
     * @param totalImported    实际入库数
     * @return success=true 的结果实例
     */
    public static AIImportResult success(List<Question> validQuestions,
                                         List<Question> invalidQuestions,
                                         int totalImported) {
        AIImportResult result = new AIImportResult();
        result.setSuccess(true);
        result.setValidQuestions(validQuestions == null
                ? new ArrayList<Question>() : validQuestions);
        result.setInvalidQuestions(invalidQuestions == null
                ? new ArrayList<Question>() : invalidQuestions);
        result.setErrorMessages(new ArrayList<String>());
        result.setTotalImported(totalImported);
        return result;
    }
}
