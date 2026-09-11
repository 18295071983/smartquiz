# -*- coding: utf-8 -*-
import io

p = r'D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\tool\ImageGenTool.java'
with io.open(p, 'r', encoding='utf-8') as f:
    s = f.read()

old = '''    /**
     * 在线 imageGen 端口生成（优先通道）。
     * 条件：当前在线模型配置 apiUrl/apiKey 非空 + supportsImageGen 勾选 + 配置表声明 imageModel。
     * 任一条件不满足/调用失败/超时 → 返回 null，由调用方降级免费 API。
     */
    private File tryOnlineGenerate(String prompt, int width, int height) {
        try {
            if (context == null) return null;
            OnlineModelManager omm = OnlineModelManager.getInstance(context);
            if (omm == null) return null;
            OnlineModelManager.OnlineModelConfig cfg = omm.getActiveModel();
            if (cfg == null || cfg.apiUrl == null || cfg.apiUrl.trim().isEmpty()
                    || cfg.apiKey == null || cfg.apiKey.trim().isEmpty()) {
                return null;
            }
            if (!cfg.supportsImageGen) return null; // 未勾选文生图能力
            ProviderConfigManager pcm = ProviderConfigManager.get();
            String imgModel = pcm.getServiceModel(cfg.apiUrl, "imageGen");
            if (imgModel == null || imgModel.trim().isEmpty()) return null; // 配置表未声明 imageModel

            OnlineInferenceService ois = OnlineInferenceService.getInstance(context);
            if (ois == null) return null;
            AILogger.i(TAG, "在线 imageGen 端口生成（model=" + imgModel + "）");
            String result = ois.generateImageAsync(prompt, cfg)
                    .get(ONLINE_GEN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (result == null || result.trim().isEmpty()) return null;
            File file = saveOnlineImage(result, width, height);
            if (file == null) AILogger.w(TAG, "在线生图返回但图片保存失败，降级免费 API");
            return file;
        } catch (Exception e) {
            AILogger.w(TAG, "在线生图不可用，降级免费 API: " + e.getMessage());
            return null;
        }
    }'''

new = '''    /**
     * 在线 imageGen 端口生成（优先通道，仅当用户单独设置了文生图专用模型时启用）。
     * 条件：FEATURE_IMAGE_GEN 功能专用模型已设置（apiUrl/apiKey 非空）+ 配置表声明 imageModel。
     * 未单独设置 → 返回 null，走免费 Pollinations，不消费任何在线生图 API（防止无关消费）。
     */
    private File tryOnlineGenerate(String prompt, int width, int height) {
        try {
            if (context == null) return null;
            OnlineModelManager omm = OnlineModelManager.getInstance(context);
            if (omm == null) return null;
            // 只读文生图功能专用模型：用户单独设置后才走在线端口
            OnlineModelManager.OnlineModelConfig cfg = omm.getFeatureModel(OnlineModelManager.FEATURE_IMAGE_GEN);
            if (cfg == null || cfg.apiUrl == null || cfg.apiUrl.trim().isEmpty()
                    || cfg.apiKey == null || cfg.apiKey.trim().isEmpty()) {
                return null;
            }
            ProviderConfigManager pcm = ProviderConfigManager.get();
            String imgModel = pcm.getServiceModel(cfg.apiUrl, "imageGen");
            if (imgModel == null || imgModel.trim().isEmpty()) return null; // 配置表未声明 imageModel

            OnlineInferenceService ois = OnlineInferenceService.getInstance(context);
            if (ois == null) return null;
            AILogger.i(TAG, "在线 imageGen 端口生成（专用模型=" + cfg.name + " / " + imgModel + "）");
            String result = ois.generateImageAsync(prompt, cfg)
                    .get(ONLINE_GEN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (result == null || result.trim().isEmpty()) return null;
            File file = saveOnlineImage(result, width, height);
            if (file == null) AILogger.w(TAG, "在线生图返回但图片保存失败，降级免费 API");
            return file;
        } catch (Exception e) {
            AILogger.w(TAG, "在线生图不可用，降级免费 API: " + e.getMessage());
            return null;
        }
    }'''

if old not in s:
    print('OLD NOT FOUND')
else:
    s = s.replace(old, new)
    with io.open(p, 'w', encoding='utf-8', newline='') as f:
        f.write(s)
    print('OK replaced tryOnlineGenerate')
