package com.oilquiz.app.util.export;

import java.io.File;
import com.oilquiz.app.util.export.ExportManager;

/**
 * 导出器接口
 * 定义导出功能的通用方法
 */
public interface Exporter {
    /**
     * 导出数据
     * @param task 导出任务
     * @return 导出的文件
     * @throws Exception 导出过程中的异常
     */
    File export(ExportManager.ExportTask task) throws Exception;

    /**
     * 验证导出参数
     * @param task 导出任务
     * @throws IllegalArgumentException 参数验证失败时抛出
     */
    void validateParameters(ExportManager.ExportTask task) throws IllegalArgumentException;

    /**
     * 获取导出文件的默认扩展名
     * @return 文件扩展名
     */
    String getFileExtension();

    /**
     * 获取导出格式的显示名称
     * @return 格式名称
     */
    String getFormatName();
}