package com.oilquiz.app.ai.spi;

import com.oilquiz.app.ai.tool.AIToolResult;

import java.util.Map;

/**
 * 工具执行网关（SPI，解耦 AIToolManager 单例）。
 *
 * 逻辑组件（工具编排）通过本接口执行任意具名工具；
 * 宿主实现桥接 AIToolManager 或任意工具执行器。
 */
public interface ToolGateway {

    /** 执行工具（同步返回结果；失败时 result.isSuccess()==false） */
    AIToolResult executeTool(String toolName, Map<String, Object> params);
}
