# -*- coding: utf-8 -*-
import io

p = r'D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\tool\DashscopeMediaTool.java'
with io.open(p, 'r', encoding='utf-8') as f:
    s = f.read()

old = '''            String apiKey = parameters.get("api_key") != null
                    ? String.valueOf(parameters.get("api_key")).trim() : null;
            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = resolveDefaultApiKey();
            }
            if (apiKey == null || apiKey.isEmpty()) {
                return AIToolResult.fail("未配置 API Key：请在模型管理页配置在线模型，或传入 api_key 参数");
            }
            // 端点：api_url 参数 > 配置端点 > 默认公共；据此判断协议（百炼原生 / OpenAI 兼容）
            String compatBase = resolveCompatBase(parameters);'''

new = '''            String apiKey = parameters.get("api_key") != null
                    ? String.valueOf(parameters.get("api_key")).trim() : null;
            // 文生图/文生视频：功能专用模型优先（单独设置后才消费在线 API，防止无关消费）
            boolean isGenAction = "image".equals(action) || "video".equals(action);
            String genFeature = "video".equals(action)
                    ? com.oilquiz.app.ai.model.OnlineModelManager.FEATURE_VIDEO_GEN
                    : com.oilquiz.app.ai.model.OnlineModelManager.FEATURE_IMAGE_GEN;
            com.oilquiz.app.ai.model.OnlineModelManager.OnlineModelConfig featureCfg =
                    isGenAction ? resolveFeatureConfig(genFeature) : null;
            if (featureCfg != null) {
                // 专用模型已设置：Key / 端点 / 模型名全部以专用配置为准
                if (featureCfg.apiKey != null && !featureCfg.apiKey.trim().isEmpty()) {
                    apiKey = featureCfg.apiKey.trim();
                }
                if (featureCfg.apiUrl != null && !featureCfg.apiUrl.trim().isEmpty()) {
                    parameters.put("api_url", featureCfg.apiUrl.trim());
                }
                if (featureCfg.modelName != null && !featureCfg.modelName.trim().isEmpty()) {
                    parameters.put("model", featureCfg.modelName.trim());
                }
            } else if (isGenAction && (apiKey == null || apiKey.isEmpty())) {
                // 生成类动作未设置专用模型且未显式传 api_key → 拒绝，不使用主模型 Key 消费无关 API
                String label = "video".equals(action) ? "文生视频" : "文生图";
                return AIToolResult.fail("未设置" + label
                        + "专用模型：请先在模型管理中选择" + label + "专用模型（防止无关 API 消费）；或显式传入 api_key");
            }
            if (apiKey == null || apiKey.isEmpty()) {
                apiKey = resolveDefaultApiKey();
            }
            if (apiKey == null || apiKey.isEmpty()) {
                return AIToolResult.fail("未配置 API Key：请在模型管理页配置在线模型，或传入 api_key 参数");
            }
            // 端点：api_url 参数 > 配置端点 > 默认公共；据此判断协议（百炼原生 / OpenAI 兼容）
            String compatBase = resolveCompatBase(parameters);'''

if old not in s:
    print('OLD NOT FOUND')
else:
    s = s.replace(old, new)
    with io.open(p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)
    print('OK replaced execute')
