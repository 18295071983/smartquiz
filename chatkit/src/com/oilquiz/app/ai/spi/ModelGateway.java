package com.oilquiz.app.ai.spi;

/**
 * 模型/模式能力网关（SPI，解耦 OnlineModelManager / ChatModeManager 单例）。
 *
 * 逻辑组件通过本接口查询模型能力（功能专用模型、深度思考开关）；
 * 宿主实现桥接具体管理器。
 */
public interface ModelGateway {

    /** 功能专用模型 id（如 FEATURE_ASR），无配置返回 null/空 */
    String getFeatureModelId(String feature);

    /** 当前是否启用深度思考模式 */
    boolean isDeepThinkingEnabled();
}
