package com.oilquiz.app.ai.agent;

import com.oilquiz.app.ai.tool.AIToolsManager;
import com.oilquiz.app.ai.tool.AIToolManager;
import com.oilquiz.app.ai.tool.AIToolResult;
import com.oilquiz.app.ai.tool.LocationTool;
import com.oilquiz.app.util.AILogger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class ServiceRouter {
    private static final String TAG = "ServiceRouter";

    private final UnifiedAgentEngine agentEngine;
    private final AIToolsManager toolsManager;
    private final AIToolManager toolManager;
    private LocationTool locationTool;

    // 常见城市列表
    private static final String[] CITIES = {
            "北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京", "重庆", "西安",
            "天津", "苏州", "郑州", "长沙", "沈阳", "青岛", "济南", "大连", "哈尔滨", "长春",
            "沈阳", "厦门", "福州", "南昌", "合肥", "昆明", "贵阳", "南宁", "海口", "太原",
            "石家庄", "兰州", "银川", "西宁", "乌鲁木齐", "呼和浩特", "拉萨", "香港", "澳门", "台北",
            "佛山", "东莞", "宁波", "无锡", "温州", "常州", "南通", "徐州", "泉州", "惠州", "金华",
            "珠海", "中山", "台州", "绍兴", "嘉兴", "湖州", "镇江", "扬州", "泰州", "盐城"
    };

    // 城市昵称映射
    private static final Map<String, String> CITY_ALIASES = new HashMap<>();
    static {
        // 一线城市昵称
        CITY_ALIASES.put("帝都", "北京");
        CITY_ALIASES.put("京城", "北京");
        CITY_ALIASES.put("首都", "北京");
        CITY_ALIASES.put("魔都", "上海");
        CITY_ALIASES.put("上海滩", "上海");
        CITY_ALIASES.put("羊城", "广州");
        CITY_ALIASES.put("花城", "广州");
        CITY_ALIASES.put("鹏城", "深圳");
        CITY_ALIASES.put("江城", "武汉");
        CITY_ALIASES.put("山城", "重庆");
        CITY_ALIASES.put("星城", "长沙");
        // 省会城市
        CITY_ALIASES.put("蓉城", "成都");
        CITY_ALIASES.put("春城", "昆明");
        CITY_ALIASES.put("江城", "武汉");
        CITY_ALIASES.put("冰城", "哈尔滨");
        CITY_ALIASES.put("北国春城", "长春");
        CITY_ALIASES.put("鱼城", "济南");
        CITY_ALIASES.put("岛城", "青岛");
        CITY_ALIASES.put("古都", "西安");
        CITY_ALIASES.put("金陵", "南京");
        CITY_ALIASES.put("姑苏", "苏州");
        CITY_ALIASES.put("天堂", "杭州");
    }

    // 定位表达关键词
    private static final String[] LOCATION_KEYWORDS = {
            "这里", "这儿", "当前位置", "我这里", "我现在", "当前", "附近", "周围", "本地"
    };

    public ServiceRouter(UnifiedAgentEngine agentEngine) {
        this.agentEngine = agentEngine;
        this.toolsManager = new AIToolsManager(agentEngine.getActivity());
        this.toolManager = AIToolManager.getInstance(agentEngine.getActivity());
    }

    /**
     * 智能天气查询 - 主方法
     */
    public UnifiedAgentEngine.AgentResult executeWeatherTask(String message) {
        try {
            AILogger.i(TAG, "处理天气查询: " + message);
            
            // 1. 提取城市
            String city = extractCity(message);
            
            // 2. 选择合适的 action
            String action = selectWeatherAction(message);
            
            // 3. 构建参数
            Map<String, Object> params = new HashMap<>();
            params.put("action", action);
            
            if (city != null) {
                params.put("city", city);
                AILogger.i(TAG, "查询城市: " + city + ", action: " + action);
            } else {
                AILogger.i(TAG, "使用定位查询, action: " + action);
            }
            
            // 4. 执行天气查询
            return executeWeather(params, city);
            
        } catch (Exception e) {
            AILogger.e(TAG, "Weather task failed: " + e.getMessage(), e);
            return new UnifiedAgentEngine.AgentResult("天气查询失败: " + e.getMessage(), false);
        }
    }

    /**
     * 执行天气查询
     */
    private UnifiedAgentEngine.AgentResult executeWeather(Map<String, Object> params, String city) {
        try {
            // 构建参数字符串 - 格式：city: 城市名, action: action值
            StringBuilder paramStr = new StringBuilder();
            String action = (String) params.get("action");
            
            if (action != null) {
                paramStr.append("action: ").append(action);
            }
            
            if (city != null && !city.isEmpty()) {
                if (paramStr.length() > 0) {
                    paramStr.append(", ");
                }
                paramStr.append("city: ").append(city);
            }
            
            AILogger.i(TAG, "执行天气工具, 参数: " + paramStr.toString());
            
            // 直接使用 AIToolManager 执行工具
            AIToolResult result = toolManager.executeTool("ai_weather", params);
            
            if (result.isSuccess()) {
                Object data = result.getResult();
                if (data instanceof Map) {
                    // 处理 Map 类型返回结果
                    Map<?, ?> resultMap = (Map<?, ?>) data;
                    StringBuilder sb = new StringBuilder();
                    
                    if (resultMap.containsKey("data")) {
                        sb.append(resultMap.get("data").toString());
                    } else {
                        for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
                            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
                        }
                    }
                    return new UnifiedAgentEngine.AgentResult(sb.toString(), true);
                } else {
                    return new UnifiedAgentEngine.AgentResult(String.valueOf(data), true);
                }
            } else {
                return new UnifiedAgentEngine.AgentResult("天气查询失败: " + result.getErrorMessage(), false);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Execute weather failed: " + e.getMessage(), e);
            return new UnifiedAgentEngine.AgentResult("天气查询失败: " + e.getMessage(), false);
        }
    }

    /**
     * 提取城市名
     * @return 城市名，如果需要定位则返回 null
     */
    private String extractCity(String message) {
        if (message == null || message.isEmpty()) {
            return "北京"; // 默认城市
        }
        
        String msg = message.trim();
        
        // 1. 检查定位表达关键词
        if (isLocationExpression(msg)) {
            AILogger.i(TAG, "检测到定位表达，使用当前位置");
            return null; // 使用定位
        }
        
        // 2. 检查城市昵称
        for (Map.Entry<String, String> entry : CITY_ALIASES.entrySet()) {
            if (msg.contains(entry.getKey())) {
                AILogger.i(TAG, "匹配到城市昵称: " + entry.getKey() + " -> " + entry.getValue());
                return entry.getValue();
            }
        }
        
        // 3. 检查常见城市列表
        for (String city : CITIES) {
            if (msg.contains(city)) {
                AILogger.i(TAG, "匹配到城市: " + city);
                return city;
            }
        }
        
        // 4. 检查城市缩写（如"沪"代表上海，"京"代表北京）
        String abbreviation = extractAbbreviation(msg);
        if (abbreviation != null) {
            AILogger.i(TAG, "匹配到城市缩写: " + abbreviation);
            return abbreviation;
        }
        
        // 5. 未匹配到任何城市，使用定位
        AILogger.i(TAG, "未匹配到城市，使用定位");
        return null;
    }

    /**
     * 检查是否是定位表达
     */
    private boolean isLocationExpression(String message) {
        for (String keyword : LOCATION_KEYWORDS) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        // 也检查英文表达
        String lower = message.toLowerCase();
        return lower.contains("here") || lower.contains("current location") || 
               lower.contains("my location") || lower.contains("nearby");
    }

    /**
     * 提取城市缩写
     */
    private String extractAbbreviation(String message) {
        // 单字缩写：沪、京、粤、苏等
        Map<String, String> abbreviations = new HashMap<>();
        abbreviations.put("沪", "上海");
        abbreviations.put("京", "北京");
        abbreviations.put("粤", "广州");
        abbreviations.put("深", "深圳");
        abbreviations.put("浙", "杭州");
        abbreviations.put("苏", "苏州");
        abbreviations.put("闽", "福州");
        abbreviations.put("湘", "长沙");
        abbreviations.put("鄂", "武汉");
        abbreviations.put("川", "成都");
        abbreviations.put("渝", "重庆");
        abbreviations.put("陕", "西安");
        abbreviations.put("鲁", "济南");
        abbreviations.put("豫", "郑州");
        abbreviations.put("辽", "沈阳");
        abbreviations.put("黑", "哈尔滨");
        abbreviations.put("吉", "长春");
        abbreviations.put("皖", "合肥");
        abbreviations.put("赣", "南昌");
        abbreviations.put("云", "昆明");
        abbreviations.put("贵", "贵阳");
        abbreviations.put("桂", "南宁");
        abbreviations.put("琼", "海口");
        abbreviations.put("津", "天津");
        abbreviations.put("港", "香港");
        abbreviations.put("澳", "澳门");
        abbreviations.put("台", "台北");
        
        for (Map.Entry<String, String> entry : abbreviations.entrySet()) {
            if (message.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        
        return null;
    }

    /**
     * 根据消息内容选择合适的 action
     */
    private String selectWeatherAction(String message) {
        if (message == null || message.isEmpty()) {
            return "all"; // 默认获取全部
        }
        
        String msg = message.toLowerCase();
        
        // 精确天气查询
        if (containsAny(msg, "空气质量", "aqi", "雾霾", "pm2", "pm10", "空气污染")) {
            return "air_quality";
        }
        
        // 预警相关
        if (containsAny(msg, "预警", "警告", "灾害", "台风", "暴雨", "寒潮", "高温")) {
            return "alerts";
        }
        
        // 生活指数
        if (containsAny(msg, "指数", "穿衣", "紫外线", "晾晒", "运动", "感冒", "洗车", "花粉")) {
            return "indices";
        }
        
        // 小时预报
        if (containsAny(msg, "小时", "每时", "分钟", "几分钟后", "会下雨", "会下雪")) {
            return "hourly";
        }
        
        // 天气预报/未来天气
        if (containsAny(msg, "预报", "未来", "明天", "后天", "下周", "这个星期", "周末", "气候")) {
            return "forecast";
        }
        
        // 默认返回完整天气
        return "all";
    }

    /**
     * 检查消息是否包含任意关键词
     */
    private boolean containsAny(String message, String... keywords) {
        for (String keyword : keywords) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public UnifiedAgentEngine.AgentResult executeSearchTask(String message) {
        try {
            String keyword = extractKeyword(message);
            CompletableFuture<String> future = toolsManager.executeTool("search_questions", "keyword: " + keyword);
            String result = future.get(30, TimeUnit.SECONDS);
            return new UnifiedAgentEngine.AgentResult(result, true);
        } catch (Exception e) {
            AILogger.e(TAG, "Search task failed: " + e.getMessage());
            return new UnifiedAgentEngine.AgentResult("搜索失败: " + e.getMessage(), false);
        }
    }

    public UnifiedAgentEngine.AgentResult executeDatabaseTask(String message) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("action", "execute_query");
            params.put("query", message);
            AIToolResult result = toolManager.executeTool("database", params);
            if (result.isSuccess()) {
                return new UnifiedAgentEngine.AgentResult(String.valueOf(result.getResult()), true);
            } else {
                return new UnifiedAgentEngine.AgentResult(result.getErrorMessage(), false);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Database task failed: " + e.getMessage());
            return new UnifiedAgentEngine.AgentResult("数据库操作失败: " + e.getMessage(), false);
        }
    }

    private String extractKeyword(String message) {
        String cleaned = message.replaceAll("(搜索|查找|查询|找一下|搜一下|关于)", "").trim();
        return cleaned.isEmpty() ? message : cleaned;
    }
}