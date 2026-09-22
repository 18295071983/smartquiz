package com.oilquiz.app.ai.tool.openai;

import java.util.List;

/**
 * 结构化参数工具接口（2026-09-23）：
 * 动态工具（DynamicAITool / PythonDynamicTool）与内置工具体系统一的结构化参数出口，
 * 供 getToolDefinition / tool_registry(get) 解析出真实类型/必填/默认/枚举，
 * 避免"只有 Map 参数描述 → 退化成全 string schema → 模型按错误 schema 调用"的质量断裂。
 */
public interface StructuredParamTool {
    /** 结构化参数定义列表（含 name/type/required/description/default/enum）；空则调用方回退 Map 描述 */
    List<ParamDefinition> getParameterDefinitions();
}
