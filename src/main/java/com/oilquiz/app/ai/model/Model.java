package com.oilquiz.app.ai.model;

import java.io.Serializable;

/**
 * Model - AI模型数据类
 * 
 * 功能：
 * - 存储AI模型的所有元数据信息
 * - 支持序列化和反序列化
 * - 用于模型选择、比较和管理
 * 
 * 模型属性：
 * - id: 模型唯一标识符
 * - name: 模型显示名称
 * - description: 模型描述
 * - architecture: 模型架构
 * - contextLength: 上下文长度
 * - vocabSize: 词表大小
 * - parameters: 参数量
 * - quantization: 量化方法
 * - memory: 内存占用
 * - tps: 每秒Token数
 * - performanceScore: 性能评分
 * - qualityScore: 质量评分
 * - useCases: 适用场景
 * 
 * 使用示例：
 * Model model = new Model("qwen2-0.5b", "Qwen2 0.5B", ...);
 * 
 * @author AI Team
 * @since 2024
 */
public class Model implements Serializable {

    private String id;
    private String name;
    private String description;
    private String architecture;
    private int contextLength;
    private int vocabSize;
    private String parameters;
    private String quantization;
    private String memory;
    private String tps;
    private float performanceScore;
    private float qualityScore;
    private String useCases;
    private boolean selected;

    public Model(String id, String name, String description, String architecture, int contextLength, int vocabSize, String parameters, String quantization, String memory, String tps, float performanceScore, float qualityScore, String useCases, boolean selected) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.architecture = architecture;
        this.contextLength = contextLength;
        this.vocabSize = vocabSize;
        this.parameters = parameters;
        this.quantization = quantization;
        this.memory = memory;
        this.tps = tps;
        this.performanceScore = performanceScore;
        this.qualityScore = qualityScore;
        this.useCases = useCases;
        this.selected = selected;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getArchitecture() {
        return architecture;
    }

    public void setArchitecture(String architecture) {
        this.architecture = architecture;
    }

    public int getContextLength() {
        return contextLength;
    }

    public void setContextLength(int contextLength) {
        this.contextLength = contextLength;
    }

    public int getVocabSize() {
        return vocabSize;
    }

    public void setVocabSize(int vocabSize) {
        this.vocabSize = vocabSize;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    public String getQuantization() {
        return quantization;
    }

    public void setQuantization(String quantization) {
        this.quantization = quantization;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public String getTps() {
        return tps;
    }

    public void setTps(String tps) {
        this.tps = tps;
    }

    public float getPerformanceScore() {
        return performanceScore;
    }

    public void setPerformanceScore(float performanceScore) {
        this.performanceScore = performanceScore;
    }

    public float getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(float qualityScore) {
        this.qualityScore = qualityScore;
    }

    public String getUseCases() {
        return useCases;
    }

    public void setUseCases(String useCases) {
        this.useCases = useCases;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }
}