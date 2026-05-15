package com.oilquiz.app.ai.model;

/**
 * ModelInfo - 模型信息数据类
 * 
 * 功能：
 * - 存储模型的简要信息
 * - 用于模型列表展示和下载管理
 * 
 * 属性：
 * - id: 模型唯一标识
 * - name: 模型显示名称
 * - description: 模型描述
 * - sizeMB: 模型文件大小(MB)
 * - contextLength: 上下文长度
 * - quantization: 量化方法
 * - downloadUrl: 下载URL
 * - recommendedGpuLayers: 推荐GPU层数
 * - minRamMB: 最小内存需求(MB)
 * 
 * @author AI Team
 * @since 2024
 */
public class ModelInfo {
    public String id;
    public String name;
    public String description;
    public long sizeMB;
    public int contextLength;
    public String quantization;
    public String downloadUrl;
    public int recommendedGpuLayers;
    public long minRamMB;
}
