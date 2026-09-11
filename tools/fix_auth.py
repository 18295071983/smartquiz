# -*- coding: utf-8 -*-
import io

# ---------- 1. OnlineModelManager 加 findConfig ----------
p1 = r'D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\model\OnlineModelManager.java'
with io.open(p1, 'r', encoding='utf-8') as f:
    s1 = f.read()

anchor = '''    public OnlineModelConfig getActiveModel() {
        return activeModelId != null ? getModel(activeModelId) : null;
    }
'''
add = anchor + '''
    /**
     * 按 apiUrl（+可选 modelName）反查配置。
     * 引擎侧鉴权需要 apiSecret/appId（讯飞 HMAC、百度 OAuth），
     * 但内部 HTTP 方法只有 apiUrl/apiKey/modelName，据此找回完整配置。
     */
    public OnlineModelConfig findConfig(String apiUrl, String modelName) {
        if (apiUrl == null) return null;
        for (OnlineModelConfig c : getModelList()) {
            if (c == null || c.apiUrl == null || !c.apiUrl.equals(apiUrl)) continue;
            if (modelName == null || modelName.isEmpty()) return c;
            if (modelName.equals(c.modelName) || modelName.equals(c.selectedModel)) return c;
        }
        return null;
    }
'''
assert anchor in s1, 'OnlineModelManager anchor not found'
s1 = s1.replace(anchor, add, 1)
with io.open(p1, 'w', encoding='utf-8', newline='') as f:
    f.write(s1)
print('OnlineModelManager.findConfig 已添加')

# ---------- 2. OnlineInferenceService 鉴权修复 ----------
p2 = r'D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\service\OnlineInferenceService.java'
with io.open(p2, 'r', encoding='utf-8') as f:
    lines = f.read().split('\n')

def rep_line(no, old_sub, new_sub):
    idx = no - 1
    assert old_sub in lines[idx], '行 %d 未匹配: %s' % (no, lines[idx].strip())
    lines[idx] = lines[idx].replace(old_sub, new_sub)

# 有 config 的 4 处 → 直接传 config.apiSecret/config.appId
for no in (309, 374, 444):
    rep_line(no, 'pcm.applyAuthHeaders(connection, fullUrl, apiKey, null, null);',
             'pcm.applyAuthHeaders(connection, fullUrl, apiKey, config.apiSecret, config.appId);')
rep_line(1063, '.applyAuthHeaders(connection, fullUrl, apiKey, null, null);',
         '.applyAuthHeaders(connection, fullUrl, apiKey, config.apiSecret, config.appId);')

# 有 modelName 的 9 处 → applyAuth 反查
for no in (1154, 1334, 1574, 1745, 1849, 2012, 2480, 2724, 2964):
    rep_line(no, '.applyAuthHeaders(connection, fullUrl, apiKey, null, null);',
             'applyAuth(com.oilquiz.app.ai.model.ProviderConfigManager.get(), connection, fullUrl, apiKey, modelName);')

# ---------- 3. applyAuth 帮助方法（插在 buildOpenAIUrl 前） ----------
auth_method = '''
    /**
     * 应用鉴权头（含 apiSecret/appId）：讯飞 HMAC 签名、百度 OAuth 换 token 都需要，
     * 从 apiUrl+modelName 反查配置补齐；未找到时退化为仅 apiKey（等价旧行为）。
     */
    private void applyAuth(com.oilquiz.app.ai.model.ProviderConfigManager pcm,
                           HttpURLConnection conn, String url, String apiKey, String modelName) {
        String apiSecret = null;
        String appId = null;
        if (modelName != null && !modelName.isEmpty()) {
            try {
                OnlineModelManager.OnlineModelConfig c = OnlineModelManager.getInstance(context)
                        .findConfig(url, modelName);
                if (c != null) {
                    apiSecret = c.apiSecret;
                    appId = c.appId;
                }
            } catch (Exception ignored) {
            }
        }
        pcm.applyAuthHeaders(conn, url, apiKey, apiSecret, appId);
    }

    /**
     * 构建 OpenAI 兼容格式的 URL（统一走 ProviderConfigManager 配置驱动的拼装接口）。
     * 兼容规则：endpoint 自带版本前缀直接拼；baseUrl 已以版本路径结尾（/v1、/v4 等，
     * 如智谱 /api/paas/v4）→ 直接拼 endpoint；已以 endpoint 结尾 → 原样；
     * 否则默认补 /v1 + endpoint。
     */
    private String buildOpenAIUrl(String apiUrl, String endpoint) {'''
anchor2 = '''    /**
     * 构建 OpenAI 兼容格式的 URL（统一走 ProviderConfigManager 配置驱动的拼装接口）。
     * 兼容规则：endpoint 自带版本前缀直接拼；baseUrl 已以版本路径结尾（/v1、/v4 等，
     * 如智谱 /api/paas/v4）→ 直接拼 endpoint；已以 endpoint 结尾 → 原样；
     * 否则默认补 /v1 + endpoint。
     */
    private String buildOpenAIUrl(String apiUrl, String endpoint) {'''
s2 = '\n'.join(lines)
assert anchor2 in s2, 'buildOpenAIUrl anchor not found'
s2 = s2.replace(anchor2, auth_method, 1)
with io.open(p2, 'w', encoding='utf-8', newline='') as f:
    f.write(s2)
print('OnlineInferenceService 鉴权修复完成')
