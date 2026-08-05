package com.oilquiz.app.ai.importing;

import android.content.Context;
import android.util.Log;

import com.oilquiz.app.ai.agent.ToolResultInterpreter;
import com.oilquiz.app.ai.engine.ALChat;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.service.OnlineInferenceService;
import com.oilquiz.app.model.Question;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 动态智能字段映射组件。
 * <p>
 * 当 {@link FieldMappingRegistry} 的静态+模糊匹配均无法识别表头时，
 * 调用 AI 模型动态识别"未知表头 → 标准字段名"的映射关系。
 * <p>
 * 四级匹配策略：
 * <ol>
 *   <li>静态精确匹配 — FieldMappingRegistry.resolve() 别名表</li>
 *   <li>模糊匹配 — 包含关系 + 编辑距离</li>
 *   <li>AI 动态识别 — 批量调用 LLM，一次调用识别所有未知表头</li>
 *   <li>用户手动映射 — UI 层提供手动映射界面作为最终兜底</li>
 * </ol>
 * <p>
 * 缓存策略：AI 识别结果以 LRU 缓存存储（maxSize=128），
 * 同一表头名不重复调用 AI，提升后续导入效率。
 */
public class AIFieldMapper {

    private static final String TAG = "AIFieldMapper";

    /** AI 识别结果缓存（表头名 → 标准字段名），maxSize=128 */
    private static final android.util.LruCache<String, String> AI_CACHE = new android.util.LruCache<>(128);

    private final OnlineInferenceService inferenceService;
    private final OnlineModelManager onlineModelManager;
    private final ALChat localChat;

    /** 回调接口 */
    public interface MappingCallback {
        void onMappingComplete(Map<String, Integer> finalMapping, List<String> aiResolvedFields);
        void onMappingProgress(String message);
        void onMappingError(String message);
    }

    public AIFieldMapper(Context context) {
        this.inferenceService = OnlineInferenceService.getInstance(context.getApplicationContext());
        this.onlineModelManager = OnlineModelManager.getInstance(context.getApplicationContext());
        this.localChat = new ALChat();
    }

    /**
     * 构建完整的字段映射（静态+模糊+AI动态）。
     * <p>
     * 流程：
     * 1. 用 FieldMappingRegistry 对所有表头做静态+模糊匹配
     * 2. 收集未匹配的表头，批量调用 AI 动态识别
     * 3. 合并结果，返回 标准字段名→列索引 的完整映射
     *
     * @param headers  表头列表
     * @param callback 回调（在主线程）
     */
    public void buildSmartMappingAsync(List<String> headers, MappingCallback callback) {
        if (headers == null || headers.isEmpty()) {
            if (callback != null) callback.onMappingComplete(new LinkedHashMap<>(), new ArrayList<>());
            return;
        }

        new Thread(() -> {
          final Map<String, Integer> mapping = new LinkedHashMap<>();
          final List<String> aiResolvedFields = new ArrayList<>();
          try {
            List<String> unresolvedHeaders = new ArrayList<>();
            List<Integer> unresolvedIndices = new ArrayList<>();

            // 1. 静态+模糊匹配
            for (int i = 0; i < headers.size(); i++) {
                String header = headers.get(i);
                if (header == null || header.trim().isEmpty()) continue;
                String canonical = FieldMappingRegistry.resolve(header);
                if (canonical != null) {
                    if (!mapping.containsKey(canonical)) {
                        mapping.put(canonical, i);
                    }
                } else {
                    // 检查 AI 缓存
                    String cached = AI_CACHE.get(header.trim().toLowerCase());
                    if (cached != null && !mapping.containsKey(cached)) {
                        mapping.put(cached, i);
                        aiResolvedFields.add(header + " → " + cached + " (缓存)");
                    } else if (cached != null && mapping.containsKey(cached)) {
                        // 已有该字段的列，跳过
                    } else {
                        unresolvedHeaders.add(header);
                        unresolvedIndices.add(i);
                    }
                }
            }

            // 2. 如果有未匹配表头且有可用 AI 模型，批量调用 AI 识别（传入已映射字段作为上下文）
            if (!unresolvedHeaders.isEmpty() && hasAvailableModel()) {
                notifyProgress(callback, "AI 语义动态识别 " + unresolvedHeaders.size() + " 个未知表头...");
                Map<String, String> aiResults = callAIForBatchMapping(unresolvedHeaders, mapping);
                // 3. 冲突检测与消解：多个表头映射到同一标准字段时，按语义强度选择最佳匹配
                Map<String, String> resolved = resolveSemanticConflicts(unresolvedHeaders, unresolvedIndices, aiResults, mapping);
                for (Map.Entry<String, String> entry : resolved.entrySet()) {
                    String header = entry.getKey();
                    String canonical = entry.getValue();
                    int idx = -1;
                    for (int j = 0; j < unresolvedHeaders.size(); j++) {
                        if (unresolvedHeaders.get(j).equals(header)) {
                            idx = unresolvedIndices.get(j);
                            break;
                        }
                    }
                    if (canonical != null && !"null".equals(canonical) && !"unknown".equals(canonical.toLowerCase())) {
                        if (!mapping.containsKey(canonical)) {
                            mapping.put(canonical, idx);
                            aiResolvedFields.add(header + " → " + canonical + " (AI语义识别)");
                        }
                        // 写入缓存
                        AI_CACHE.put(header.trim().toLowerCase(), canonical);
                    }
                }
            }

            // 3. 返回结果
            notifyComplete(callback, mapping, aiResolvedFields);
          } catch (Exception e) {
              Log.e(TAG, "AI 字段映射异常，降级返回已匹配结果: " + e.getMessage(), e);
              // 映射失败时优雅降级：返回已有的静态+模糊匹配结果，不阻断后续流程
              notifyComplete(callback, mapping, aiResolvedFields);
          }
        }).start();
    }

    /**
     * 批量调用 AI 识别未知表头。
     * 一次调用处理所有未匹配表头，减少 LLM 调用次数。
     *
     * @param unknownHeaders 未匹配的表头列表
     * @return Map: 表头名 → 标准字段名
     */
    private Map<String, String> callAIForBatchMapping(List<String> unknownHeaders, Map<String, Integer> alreadyMapped) {
        Map<String, String> result = new HashMap<>();
        if (unknownHeaders == null || unknownHeaders.isEmpty()) return result;

        // 构建 prompt
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是题库字段映射专家。请将以下表头名映射到标准字段名。\n\n");
        prompt.append("## 标准字段列表\n");
        for (FieldMappingRegistry.FieldDef def : FieldMappingRegistry.getAllFields()) {
            prompt.append("- ").append(def.canonical)
                  .append(" (").append(def.displayName).append(")");
            if (def.required) prompt.append(" [必填]");
            prompt.append("\n");
        }

        // 已映射字段（提供上下文，避免冲突）
        if (alreadyMapped != null && !alreadyMapped.isEmpty()) {
            prompt.append("\n## 已识别字段（上下文）\n");
            prompt.append("以下字段已通过精确/模糊匹配识别，请勿重复映射：\n");
            for (String canonical : alreadyMapped.keySet()) {
                FieldMappingRegistry.FieldDef def = null;
                for (FieldMappingRegistry.FieldDef d : FieldMappingRegistry.getAllFields()) {
                    if (d.canonical.equals(canonical)) { def = d; break; }
                }
                prompt.append("- ").append(canonical);
                if (def != null) prompt.append(" (").append(def.displayName).append(")");
                prompt.append(" ← 已占用\n");
            }
        }

        prompt.append("\n## 语义消歧规则（极重要）\n");
        prompt.append("必须结合整个表头列表的上下文进行语义判断，避免歧义映射：\n");
        prompt.append("1. 当\"正确答案\"/\"答案\"/\"参考答案\" 与 \"答案1\"/\"答案2\"/\"答案3\"/\"答案4\" 同时出现时：\n");
        prompt.append("   - \"正确答案\"等 → correctAnswer（正确答案）\n");
        prompt.append("   - \"答案1\"/\"答案2\"等 → optionA/optionB/optionC/optionD（选项）\n");
        prompt.append("2. 当\"选项A\"/\"选项B\" 与 \"选项1\"/\"选项2\" 同时出现时：\n");
        prompt.append("   - \"选项A\" → optionA, \"选项B\" → optionB\n");
        prompt.append("   - \"选项1\" → optionA, \"选项2\" → optionB（数字编号按顺序映射）\n");
        prompt.append("3. 当\"题目\"/\"题干\" 与 \"题目内容\"/\"试题正文\" 同时出现时：\n");
        prompt.append("   - 选择语义更完整的（如\"题目内容\"）→ questionText\n");
        prompt.append("4. 当\"解析\"/\"分析\" 与 \"答案解析\" 同时出现时：\n");
        prompt.append("   - 选择更具体的（如\"答案解析\"）→ explanation\n");
        prompt.append("5. 当\"分类\"/\"类别\" 与 \"科目\"/\"学科\" 同时出现时：\n");
        prompt.append("   - 优先\"分类\" → category，\"科目\"→ category（不重复占用）\n");
        prompt.append("6. 数字后缀表头（如\"答案1/2/3/4\"、\"选项1/2/3/4\"、\"选项A/B/C/D\"）按顺序映射为 optionA~D\n");
        prompt.append("7. 同一标准字段只能被一个表头占用，选择语义最精确的那个\n");
        prompt.append("8. 如果某表头无法确定对应字段，返回 \"null\"\n\n");

        prompt.append("## 待映射表头\n");
        prompt.append("表头列表: ");
        JSONArray headerArray = new JSONArray();
        for (String h : unknownHeaders) {
            headerArray.put(h);
        }
        prompt.append(headerArray.toString()).append("\n\n");

        prompt.append("## 输出格式\n");
        prompt.append("只输出以下 JSON，不要输出任何其他文字：\n");
        prompt.append("{\"mappings\":[{\"header\":\"原始表头\",\"field\":\"标准字段名\"}]}\n\n");

        prompt.append("## 示例1（无冲突）\n");
        prompt.append("输入表头: [\"试题正文\", \"标准选项1\", \"正确项\", \"所属学科\"]\n");
        prompt.append("输出: {\"mappings\":[{\"header\":\"试题正文\",\"field\":\"questionText\"},");
        prompt.append("{\"header\":\"标准选项1\",\"field\":\"optionA\"},");
        prompt.append("{\"header\":\"正确项\",\"field\":\"correctAnswer\"},");
        prompt.append("{\"header\":\"所属学科\",\"field\":\"category\"}]}\n\n");

        prompt.append("## 示例2（冲突消歧）\n");
        prompt.append("输入表头: [\"答案1\", \"答案2\", \"答案3\", \"答案4\", \"正确答案\", \"题干\"]\n");
        prompt.append("输出: {\"mappings\":[{\"header\":\"答案1\",\"field\":\"optionA\"},");
        prompt.append("{\"header\":\"答案2\",\"field\":\"optionB\"},");
        prompt.append("{\"header\":\"答案3\",\"field\":\"optionC\"},");
        prompt.append("{\"header\":\"答案4\",\"field\":\"optionD\"},");
        prompt.append("{\"header\":\"正确答案\",\"field\":\"correctAnswer\"},");
        prompt.append("{\"header\":\"题干\",\"field\":\"questionText\"}]}\n\n");

        prompt.append("## 示例3（冲突消歧）\n");
        prompt.append("输入表头: [\"选项A\", \"选项B\", \"选项1\", \"选项2\", \"标准答案\"]\n");
        prompt.append("输出: {\"mappings\":[{\"header\":\"选项A\",\"field\":\"optionA\"},");
        prompt.append("{\"header\":\"选项B\",\"field\":\"optionB\"},");
        prompt.append("{\"header\":\"选项1\",\"field\":\"null\"},");
        prompt.append("{\"header\":\"选项2\",\"field\":\"null\"},");
        prompt.append("{\"header\":\"标准答案\",\"field\":\"correctAnswer\"}]}");

        // 调用 LLM
        String aiOutput = callLLM(prompt.toString());
        if (aiOutput == null || aiOutput.trim().isEmpty()) {
            Log.w(TAG, "AI 映射返回空结果");
            return result;
        }

        // 解析 AI 输出
        try {
            // 尝试提取 JSON
            String jsonStr = extractJson(aiOutput);
            if (jsonStr == null) {
                Log.w(TAG, "AI 输出无法解析为 JSON: " + aiOutput.substring(0, Math.min(200, aiOutput.length())));
                return result;
            }
            JSONObject root = new JSONObject(jsonStr);
            JSONArray mappings = root.optJSONArray("mappings");
            if (mappings != null) {
                for (int i = 0; i < mappings.length(); i++) {
                    JSONObject m = mappings.getJSONObject(i);
                    String header = m.optString("header", "");
                    String field = m.optString("field", "");
                    if (!header.isEmpty() && !field.isEmpty() && !"null".equals(field)) {
                        // 验证 field 是否为已注册的标准字段
                        String verified = verifyCanonical(field);
                        result.put(header, verified != null ? verified : field);
                    }
                }
            }
            Log.d(TAG, "AI 映射结果: " + result);
        } catch (Exception e) {
            Log.e(TAG, "解析 AI 映射输出失败: " + e.getMessage(), e);
        }

        return result;
    }

    /**
     * 验证 AI 返回的字段名是否为已注册的标准字段。
     * 如果不是精确匹配，尝试 resolve 查找。
     */
    private String verifyCanonical(String field) {
        if (field == null || field.trim().isEmpty()) return null;
        String f = field.trim();
        // 直接是标准名
        if (FieldMappingRegistry.getAllFields().stream().anyMatch(d -> d.canonical.equals(f))) {
            return f;
        }
        // 通过 resolve 查找
        return FieldMappingRegistry.resolve(f);
    }

    /**
     * 语义冲突检测与消解。
     * <p>
     * 当多个表头被 AI 映射到同一标准字段时，按语义强度评分选择最佳匹配，
     * 其余表头降级到次优字段或标记为 null。
     * <p>
     * 语义强度评分规则：
     * <ul>
     *   <li>包含"正确"/"标准"/"参考" → 最高优先级（正确答案）</li>
     *   <li>包含"选项" → 选项字段优先</li>
     *   <li>数字后缀（1/2/3/4/A/B/C/D） → 选项字段优先</li>
     *   <li>纯"答案"无修饰 → 若与"正确答案"冲突，降级为选项</li>
     *   <li>包含"内容"/"正文" → 题干字段优先</li>
     *   <li>包含"解析"/"分析" → 解析字段优先</li>
     * </ul>
     *
     * @param headers      未匹配表头列表
     * @param indices      对应列索引
     * @param aiResults    AI 返回的映射结果（表头→标准字段名）
     * @param alreadyMapped 已映射的字段（优先级最高）
     * @return 消解后的映射（表头→标准字段名，已处理冲突）
     */
    private Map<String, String> resolveSemanticConflicts(
            List<String> headers, List<Integer> indices,
            Map<String, String> aiResults, Map<String, Integer> alreadyMapped) {

        Map<String, String> resolved = new LinkedHashMap<>();

        if (aiResults == null || aiResults.isEmpty()) return resolved;

        // 1. 按 canonical 分组：收集映射到同一字段的多个表头
        Map<String, List<String>> fieldToHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : aiResults.entrySet()) {
            String header = entry.getKey();
            String canonical = entry.getValue();
            if (canonical == null || "null".equals(canonical) || "unknown".equals(canonical.toLowerCase())) {
                resolved.put(header, canonical);
                continue;
            }
            fieldToHeaders.computeIfAbsent(canonical, k -> new ArrayList<>()).add(header);
        }

        // 2. 对每个有冲突的字段（多个表头映射到同一字段），按语义强度选择最佳
        for (Map.Entry<String, List<String>> entry : fieldToHeaders.entrySet()) {
            String canonical = entry.getKey();
            List<String> competingHeaders = entry.getValue();

            if (competingHeaders.size() == 1) {
                // 无冲突
                resolved.put(competingHeaders.get(0), canonical);
                continue;
            }

            // 有冲突：按语义强度排序
            Log.d(TAG, "检测到字段冲突: " + canonical + " ← " + competingHeaders);

            // 计算每个表头的语义强度分数
            List<HeaderScore> scores = new ArrayList<>();
            for (String h : competingHeaders) {
                scores.add(new HeaderScore(h, scoreSemanticStrength(h, canonical)));
            }
            // 降序排列：分数最高的最优先
            scores.sort((a, b) -> Integer.compare(b.score, a.score));

            // 最高分的表头获得该字段
            String winner = scores.get(0).header;
            resolved.put(winner, canonical);
            Log.d(TAG, "冲突消解: " + winner + " → " + canonical + " (最高语义分数: " + scores.get(0).score + ")");

            // 其余表头尝试降级到次优字段
            for (int i = 1; i < scores.size(); i++) {
                String loser = scores.get(i).header;
                String downgrade = findDowngradeField(loser, canonical, alreadyMapped, resolved);
                resolved.put(loser, downgrade);
                Log.d(TAG, "冲突降级: " + loser + " → " + (downgrade != null ? downgrade : "null"));
            }
        }

        return resolved;
    }

    /**
     * 计算表头对目标字段的语义强度分数。
     * 分数越高表示语义匹配越精确。
     */
    private int scoreSemanticStrength(String header, String canonical) {
        String h = header.trim().toLowerCase();
        int score = 0;

        switch (canonical) {
            case "correctAnswer":
                if (h.contains("正确") || h.contains("标准") || h.contains("参考")) score += 100;
                if (h.equals("答案") || h.equals("answer")) score += 30; // 纯"答案"优先级较低
                if (h.contains("答案")) score += 20;
                if (h.contains("正确选项") || h.contains("正确答案")) score += 50;
                break;

            case "optionA":
            case "optionB":
            case "optionC":
            case "optionD":
                if (h.contains("选项")) score += 50;
                if (h.matches(".*[abcd].*")) score += 30;
                if (h.matches(".*[1234].*")) score += 20;
                // "答案1/2/3/4" 映射到选项时给较高分
                if (h.contains("答案") && h.matches(".*[1234].*")) score += 40;
                break;

            case "questionText":
                if (h.contains("内容") || h.contains("正文")) score += 80;
                if (h.contains("题干")) score += 60;
                if (h.contains("题目") || h.contains("问题")) score += 40;
                if (h.contains("试题")) score += 50;
                break;

            case "explanation":
                if (h.contains("解析") || h.contains("分析")) score += 60;
                if (h.contains("答案解析")) score += 80;
                if (h.contains("说明")) score += 20;
                break;

            case "category":
                if (h.contains("分类")) score += 60;
                if (h.contains("科目") || h.contains("学科")) score += 50;
                if (h.contains("类别")) score += 40;
                if (h.contains("关键字") || h.contains("知识点")) score += 30;
                break;

            case "questionType":
                if (h.contains("题型")) score += 80;
                if (h.contains("类型")) score += 50;
                break;

            case "difficulty":
                if (h.contains("难度")) score += 80;
                if (h.contains("等级")) score += 30;
                break;

            default:
                score += 10;
                break;
        }

        return score;
    }

    /**
     * 为冲突失败的表头寻找次优字段。
     * 例如：表头"答案1"竞争 correctAnswer 失败后，应降级到 optionA。
     */
    private String findDowngradeField(String header, String lostCanonical,
                                       Map<String, Integer> alreadyMapped,
                                       Map<String, String> resolved) {
        String h = header.trim().toLowerCase();

        // 如果竞争的是 correctAnswer 但失败了，检查是否是数字后缀的"答案N" → 选项
        if ("correctAnswer".equals(lostCanonical)) {
            if (h.contains("答案") || h.contains("选项")) {
                // 按数字/字母后缀映射到选项
                String optionField = mapNumberedHeaderToOption(h, alreadyMapped, resolved);
                if (optionField != null) return optionField;
            }
        }

        // 如果竞争的是 questionText 但失败了，可能是"标题"/"描述"等
        if ("questionText".equals(lostCanonical)) {
            // 尝试 explanation
            if (h.contains("说明") || h.contains("描述") || h.contains("备注")) {
                if (!alreadyMapped.containsKey("explanation") && !resolvedContainsValue(resolved, "explanation")) {
                    return "explanation";
                }
            }
        }

        // 无法降级
        return null;
    }

    /**
     * 将带数字/字母后缀的表头映射到 optionA~D。
     * 已被占用的选项字段跳过。
     */
    private String mapNumberedHeaderToOption(String header, Map<String, Integer> alreadyMapped, Map<String, String> resolved) {
        String[] options = {"optionA", "optionB", "optionC", "optionD"};

        // 提取末尾的数字或字母
        char lastChar = header.charAt(header.length() - 1);
        int optionIndex = -1;

        if (lastChar >= '1' && lastChar <= '4') {
            optionIndex = lastChar - '1';
        } else if (lastChar >= 'a' && lastChar <= 'd') {
            optionIndex = lastChar - 'a';
        } else if (lastChar >= 'A' && lastChar <= 'D') {
            optionIndex = lastChar - 'A';
        }

        if (optionIndex >= 0 && optionIndex < 4) {
            String candidate = options[optionIndex];
            // 检查是否已被占用
            if (!alreadyMapped.containsKey(candidate) && !resolvedContainsValue(resolved, candidate)) {
                return candidate;
            }
        }

        // 按顺序找第一个未被占用的选项
        for (String opt : options) {
            if (!alreadyMapped.containsKey(opt) && !resolvedContainsValue(resolved, opt)) {
                return opt;
            }
        }

        return null;
    }

    /** 检查 resolved 中是否已包含某个值 */
    private boolean resolvedContainsValue(Map<String, String> resolved, String value) {
        if (resolved == null) return false;
        for (String v : resolved.values()) {
            if (value.equals(v)) return true;
        }
        return false;
    }

    /** 表头语义分数内部类 */
    private static class HeaderScore {
        final String header;
        final int score;
        HeaderScore(String header, int score) {
            this.header = header;
            this.score = score;
        }
    }

    /**
     * 从 AI 输出中提取 JSON 字符串
     */
    private String extractJson(String output) {
        if (output == null) return null;
        String trimmed = output.trim();
        // 去除 markdown 代码块标记
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "");
        }
        // 找到第一个 { 和最后一个 }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return null;
    }

    /**
     * 调用 LLM（在线优先，降级到本地）
     */
    private String callLLM(String prompt) {
        // 1. 在线模型优先
        OnlineModelManager.OnlineModelConfig onlineConfig = onlineModelManager.getActiveModel();
        if (onlineConfig != null) {
            try {
                List<com.oilquiz.app.ai.chat.ChatMessage> emptyHistory = new ArrayList<>();
                String result = inferenceService.generateAsync(prompt, onlineConfig, emptyHistory, 1024, false).get();
                result = ToolResultInterpreter.cleanModelOutput(result);
                if (result != null && !result.trim().isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                Log.w(TAG, "在线模型映射失败: " + e.getMessage());
            }
        }

        // 2. 降级到本地模型
        if (localChat.isInitialized()) {
            try {
                String result = localChat.sendMessage(prompt, 512, 0.1f, 0.9f, 40);
                result = ToolResultInterpreter.cleanModelOutput(result);
                if (result != null && !result.trim().isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                Log.w(TAG, "本地模型映射失败: " + e.getMessage());
            }
        }

        return null;
    }

    /**
     * 是否有可用的 AI 模型
     */
    private boolean hasAvailableModel() {
        return onlineModelManager.getActiveModel() != null || localChat.isInitialized();
    }

    /**
     * 预缓存映射结果（供 UI 层手动映射后写入缓存，下次自动匹配）
     */
    public static void cacheMapping(String header, String canonical) {
        if (header != null && canonical != null) {
            AI_CACHE.put(header.trim().toLowerCase(), canonical);
        }
    }

    /**
     * 清除缓存
     */
    public static void clearCache() {
        AI_CACHE.evictAll();
    }

    private void notifyProgress(MappingCallback callback, String msg) {
        if (callback != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.onMappingProgress(msg));
        }
    }

    private void notifyComplete(MappingCallback callback, Map<String, Integer> mapping, List<String> aiResolved) {
        if (callback != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> callback.onMappingComplete(mapping, aiResolved));
        }
    }
}
