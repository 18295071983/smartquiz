package com.oilquiz.app.ai.speech;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.oilquiz.app.ai.model.OnlineModelManager;
import com.oilquiz.app.ai.speech.core.SpeechHttpClient;
import com.oilquiz.app.ai.speech.core.SpeechModelSelector;
import com.oilquiz.app.util.AILogger;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 语音模型注册表（实时 + 内置兜底）
 *
 * <h3>设计理念</h3>
 * <ul>
 *   <li><b>实时获取</b>：从百炼 API 获取模型列表和自定义音色，新模型上线时自动可用</li>
 *   <li><b>内置兜底</b>：百炼 API 不返回内置音色列表，硬编码保留作为离线兜底</li>
 *   <li><b>系统音色</b>：Android 系统 TTS 引擎音色由 {@link SystemTtsEngine} 管理，sys: 前缀区分</li>
 *   <li><b>缓存机制</b>：内存缓存 + SharedPreferences 持久化，TTL 1 小时</li>
 * </ul>
 *
 * <h3>数据来源优先级</h3>
 * <ol>
 *   <li>在线模型列表：{@code GET /api/v1/models?capabilities=TTS/ASR}（百炼公共域名）</li>
 *   <li>自定义音色：{@code POST /api/v1/services/audio/tts/customization}（声音复刻/设计 API）</li>
 *   <li>内置音色：硬编码在 {@link #BUILTIN_TTS_MODELS} 中（离线兜底）</li>
 *   <li>系统音色：{@code SystemTtsEngine.getVoices()}（sys: 前缀）</li>
 * </ol>
 *
 * <h3>接口路由</h3>
 * 百炼的 TTS 有两套接口，必须分开路由：
 * <ul>
 *   <li>{@link #API_DASHSCOPE_QWEN_TTS}：Qwen-TTS 系列 → multimodal-generation</li>
 *   <li>{@link #API_DASHSCOPE_NATIVE}：CosyVoice / Qwen-Audio-TTS → SpeechSynthesizer</li>
 * </ul>
 */
public final class SpeechModelRegistry {

    private static final String TAG = "SpeechModelRegistry";

    /** 缓存 TTL：1 小时（毫秒） */
    private static final long CACHE_TTL_MS = 60L * 60 * 1000;
    private static final String PREFS_NAME = "speech_model_registry";
    private static final String KEY_MODELS_CACHE = "models_cache";
    private static final String KEY_MODELS_TIMESTAMP = "models_timestamp";
    private static final String KEY_VOICES_CACHE = "voices_cache";
    private static final String KEY_VOICES_TIMESTAMP = "voices_timestamp";

    private static final int TIMEOUT_MS = 15000;

    // ==================== 接口类型常量 ====================

    /** OpenAI 兼容 /audio/speech */
    public static final int API_OPENAI_COMPATIBLE = 0;
    /** 百炼 CosyVoice/Qwen-Audio-TTS：SpeechSynthesizer */
    public static final int API_DASHSCOPE_NATIVE = 1;
    /** 百炼 Qwen-TTS：multimodal-generation */
    public static final int API_DASHSCOPE_QWEN_TTS = 2;

    // ==================== 数据类 ====================

    /** 单个语音模型条目（内置 + 在线通用） */
    public static class ModelEntry {
        /** 模型名匹配关键字（小写，包含即匹配） */
        public final String[] matchKeywords;
        /** 显示名称 */
        public final String displayName;
        /** 接口类型（API_*） */
        public final int apiType;
        /** 配套音色 */
        public final TTSService.Voice[] voices;
        /** 推荐默认音色 id */
        public final String defaultVoice;

        ModelEntry(String[] matchKeywords, String displayName, int apiType,
                   String[][] voicePairs, String defaultVoice) {
            this.matchKeywords = matchKeywords;
            this.displayName = displayName;
            this.apiType = apiType;
            this.defaultVoice = defaultVoice;
            List<TTSService.Voice> list = new ArrayList<>();
            for (String[] p : voicePairs) {
                list.add(new TTSService.Voice(p[0], p[1]));
            }
            this.voices = list.toArray(new TTSService.Voice[0]);
        }
    }

    /** 从百炼 API 获取的模型信息 */
    public static class RemoteModelInfo {
        public final String modelId;
        public final String name;
        public final String description;
        public final List<String> capabilities;

        public RemoteModelInfo(String modelId, String name, String description, List<String> capabilities) {
            this.modelId = modelId;
            this.name = name;
            this.description = description;
            this.capabilities = capabilities != null ? capabilities : Collections.emptyList();
        }

        /** 是否为 TTS 模型 */
        public boolean isTts() {
            return capabilities.contains("TTS") || capabilities.contains("Realtime-Text-to-Speech");
        }

        /** 是否为 ASR 模型 */
        public boolean isAsr() {
            return capabilities.contains("ASR") || capabilities.contains("Realtime-ASR");
        }
    }

    /** 常用在线端点模板（模型管理页快速添加） */
    public static class EndpointTemplate {
        public final String name;
        public final String apiUrl;
        public final String ttsModel;
        public final String asrModel;

        EndpointTemplate(String name, String apiUrl, String ttsModel, String asrModel) {
            this.name = name;
            this.apiUrl = apiUrl;
            this.ttsModel = ttsModel;
            this.asrModel = asrModel;
        }
    }

    // ==================== 内置 TTS 模型（离线兜底） ====================

    private static final ModelEntry[] BUILTIN_TTS_MODELS = {
            // 百炼 Qwen-TTS（音色 Cherry 等）
            new ModelEntry(
                    new String[]{"qwen-tts", "qwen3-tts", "qwen3.5-tts"},
                    "百炼 Qwen-TTS", API_DASHSCOPE_QWEN_TTS,
                    new String[][]{
                            {"Cherry", "Cherry（女声·阳光积极，推荐）"},
                            {"Serena", "Serena（女声·温柔）"},
                            {"Ethan", "Ethan（男声·标准普通话）"},
                            {"Chelsie", "Chelsie（女声·二次元虚拟女友）"},
                            {"Momo", "Momo（女声·撒娇搞怪）"},
                            {"Vivian", "Vivian（女声·小暴躁）"},
                            {"Moon", "Moon（男声·率性帅气）"},
                            {"Maia", "Maia（女声·知性温柔）"},
                            {"Kai", "Kai（男声·耳朵SPA）"},
                            {"Nofish", "Nofish（男声·设计师）"},
                            {"Bella", "Bella（女声·萌宝）"},
                            {"Jennifer", "Jennifer（女声·电影质感美音）"},
                            {"Ryan", "Ryan（男声·甜茶节奏）"},
                            {"Katerina", "Katerina（女声·御姐）"},
                            {"Aiden", "Aiden（男声·美语大男孩）"},
                            {"Eldric Sage", "Eldric Sage（男声·沉稳老者）"},
                            {"Mia", "Mia（女声·乖小妹）"},
                            {"Mochi", "Mochi（男声·沙小弥）"},
                            {"Bellona", "Bellona（女声·燕铮莺）"},
                            {"Vincent", "Vincent（男声·田叔烟嗓）"},
                            {"Bunny", "Bunny（女声·萌小姬）"},
                            {"Neil", "Neil（男声·新闻主持人）"},
                            {"Elias", "Elias（女声·墨讲师）"},
                            {"Arthur", "Arthur（男声·徐大爷）"},
                            {"Nini", "Nini（女声·邻家妹妹）"},
                            {"Seren", "Seren（女声·小婉睡眠）"},
                            {"Pip", "Pip（男声·顽屁小孩）"},
                            {"Stella", "Stella（女声·少女阿月）"},
                            {"Bodega", "Bodega（男声·西班牙大叔）"},
                            {"Sonrisa", "Sonrisa（女声·拉美大姐）"},
                            {"Alek", "Alek（男声·战斗民族）"},
                            {"Dolce", "Dolce（男声·意大利大叔）"},
                            {"Sohee", "Sohee（女声·韩国欧尼）"},
                            {"Ono Anna", "Ono Anna（女声·小野杏）"},
                            {"Lenn", "Lenn（男声·德国青年）"},
                            {"Emilien", "Emilien（男声·法国大哥哥）"},
                            {"Andre", "Andre（男声·安德雷）"},
                            {"Radio Gol", "Radio Gol（男声·足球诗人）"},
                            {"Jada", "Jada（女声·上海阿珍）"},
                            {"Dylan", "Dylan（男声·北京晓东）"},
                            {"Li", "Li（男声·南京老李）"},
                            {"Marcus", "Marcus（男声·陕西秦川）"},
                            {"Roy", "Roy（男声·闽南阿杰）"},
                            {"Peter", "Peter（男声·天津李彼得）"},
                            {"Sunny", "Sunny（女声·四川晴儿）"},
                            {"Eric", "Eric（男声·四川程川）"},
                            {"Rocky", "Rocky（男声·粤语阿强）"},
                            {"Kiki", "Kiki（女声·粤语阿清）"},
                    }, "Cherry"),
            // 百炼 Qwen-Audio-TTS（声音复刻/设计）
            new ModelEntry(
                    new String[]{"qwen-audio-tts", "qwen-audio-3.0-tts"},
                    "百炼 Qwen-Audio-TTS（声音复刻/设计）", API_DASHSCOPE_NATIVE,
                    new String[][]{
                            {"longanlingxin", "龙安灵心（女声·社交陪伴旗舰，知心温暖音）"},
                            {"longanlufeng", "龙安鲁风（男声·社交陪伴旗舰，明亮开朗音）"},
                            {"longanfengyue", "龙安风悦（女声·自然亲切音）"},
                            {"longanyuanfei", "龙安元妃（女声·高傲妃子音）"},
                            {"longanlingxi", "龙安灵希（女声·可爱甜美音）"},
                            {"longanxiaoxin", "龙安小昕（女声·亲切活泼音）"},
                            {"longanhuan_v3.6", "龙安欢 v3.6（女声·25岁）"},
                            {"longjielidou_v3.6", "龙杰力豆（男声·5岁天真男童）"},
                            {"longpaopao_v3.6", "龙泡泡（女声·5岁软糯可爱音）"},
                            {"longhuohuo_v3.6", "龙火火（男声·8岁顽皮少年音）"},
                            {"longchuanshu_v3.6", "龙川叔（男声·川普大叔音）"},
                            {"loongmary", "loongmary（女声·温暖英音）"},
                            {"loongeva_v3.6", "loongeva（女声·高智美音）"},
                            {"loongjohn", "loongjohn（男声·沉稳亲切美音）"},
                    }, "longanfengyue"),
            // 百炼 CosyVoice v2
            new ModelEntry(
                    new String[]{"cosyvoice-v2"},
                    "百炼 CosyVoice v2", API_DASHSCOPE_NATIVE,
                    new String[][]{
                            {"longxiaochun_v2", "龙小淳（女声，推荐）"},
                            {"longwan_v2", "龙婉（女声·温柔）"},
                            {"longcheng_v2", "龙橙（男声）"},
                            {"longhua_v2", "龙华（男声·磁性）"},
                            {"longshu_v2", "龙书（男声·沉稳）"},
                            {"longxiaoxia_v2", "龙小夏（女声·知性）"},
                            {"longjielidou_v2", "龙杰力豆（男声·诙谐）"},
                    }, "longxiaochun_v2"),
            // 百炼 CosyVoice v3/v3.5
            new ModelEntry(
                    new String[]{"cosyvoice-v3", "cosyvoice-3"},
                    "百炼 CosyVoice v3/v3.5", API_DASHSCOPE_NATIVE,
                    new String[][]{
                            {"longanyang", "龙安阳（男声·阳光大男孩，推荐）"},
                            {"longanhuan_v3", "龙安欢 v3（女声·方言多语种）"},
                            {"longanhuan", "龙安欢（女声·欢脱元气）"},
                            {"longhuhu_v3", "龙呼呼 v3（女声·6-10岁童声标杆）"},
                            {"longpaopao_v3", "龙泡泡 v3（女声·飞天泡泡音）"},
                            {"longjielidou_v3", "龙杰力豆 v3（男声·10岁顽皮）"},
                            {"longxian_v3", "龙仙 v3（女声·12岁豪放可爱）"},
                            {"longling_v3", "龙铃 v3（女声·10岁稚气呆板）"},
                            {"longshanshan_v3", "龙闪闪 v3（女声·戏剧化童声）"},
                            {"longniuniu_v3", "龙牛牛 v3（男声·阳光男童）"},
                            {"longjiaxin_v3", "龙嘉欣 v3（女声·优雅粤语）"},
                            {"longjiayi_v3", "龙嘉怡 v3（女声·知性粤语）"},
                            {"longanyue_v3", "龙安粤 v3（男声·欢脱粤语）"},
                            {"longlaotie_v3", "龙老铁 v3（男声·东北直率）"},
                            {"longshange_v3", "龙陕哥 v3（男声·原味陕北）"},
                            {"longanmin_v3", "龙安闽 v3（女声·闽南萝莉）"},
                            {"longyingxiao_v3", "龙应笑 v3（女声·清甜推销）"},
                            {"longyingxun_v3", "龙应询 v3（男声·年轻青涩）"},
                            {"longyingjing_v3", "龙应静 v3（女声·低调冷静）"},
                            {"longyingling_v3", "龙应聆 v3（女声·温和共情）"},
                            {"longyingtao_v3", "龙应桃 v3（女声·温柔淡定）"},
                            {"longxiaochun_v3", "龙小淳 v3（女声·知性积极）"},
                            {"longxiaoxia_v3", "龙小夏 v3（女声·沉稳权威）"},
                            {"longyumi_v3", "YUMI v3（女声·正经青年）"},
                            {"longanyun_v3", "龙安昀 v3（男声·居家暖男）"},
                            {"longanwen_v3", "龙安温 v3（女声·优雅知性）"},
                            {"longanli_v3", "龙安莉 v3（女声·利落从容）"},
                            {"longanlang_v3", "龙安朗 v3（男声·清爽利落）"},
                            {"longyingmu_v3", "龙应沐 v3（女声·优雅知性）"},
                            {"longantai_v3", "龙安台 v3（女声·嗲甜台湾）"},
                            {"longhua_v3", "龙华 v3（女声·元气甜美）"},
                            {"longcheng_v3", "龙橙 v3（男声·智慧青年）"},
                            {"longze_v3", "龙泽 v3（男声·温暖元气）"},
                            {"longzhe_v3", "龙哲 v3（男声·呆板大暖男）"},
                            {"longyan_v3", "龙颜 v3（女声·温暖春风）"},
                            {"longxing_v3", "龙星 v3（女声·温婉邻家）"},
                            {"longtian_v3", "龙天 v3（男声·磁性理智）"},
                            {"longwan_v3", "龙婉 v3（女声·细腻柔声）"},
                            {"longqiang_v3", "龙嫱 v3（女声·浪漫风情）"},
                            {"longfeifei_v3", "龙菲菲 v3（女声·甜美娇气）"},
                            {"longhao_v3", "龙浩 v3（男声·多情忧郁）"},
                            {"longanrou_v3", "龙安柔 v3（女声·温柔闺蜜）"},
                            {"longhan_v3", "龙寒 v3（男声·温暖痴情）"},
                            {"longanzhi_v3", "龙安智 v3（男声·睿智轻熟）"},
                            {"longanling_v3", "龙安灵 v3（女声·思维灵动）"},
                            {"longanya_v3", "龙安雅 v3（女声·高雅气质）"},
                            {"longanqin_v3", "龙安亲 v3（女声·亲和活泼）"},
                            {"longmiao_v3", "龙妙 v3（女声·抑扬顿挫）"},
                            {"longsanshu_v3", "龙三叔 v3（男声·沉稳质感）"},
                            {"longyuan_v3", "龙媛 v3（女声·温暖治愈）"},
                            {"longyue_v3", "龙悦 v3（女声·温暖磁性）"},
                            {"longxiu_v3", "龙修 v3（男声·博才说书）"},
                            {"longnan_v3", "龙楠 v3（男声·睿智青年）"},
                            {"longwanjun_v3", "龙婉君 v3（女声·细腻柔声）"},
                            {"longyichen_v3", "龙逸尘 v3（男声·洒脱活力）"},
                            {"longlaobo_v3", "龙老伯 v3（男声·沧桑岁月爷）"},
                            {"longlaoyi_v3", "龙老姨 v3（女声·烟火从容阿姨）"},
                            {"longjiqi_v3", "龙机器 v3（机器人·呆萌）"},
                            {"longhouge_v3", "龙猴哥 v3（男声·经典猴哥）"},
                            {"longdaiyu_v3", "龙黛玉 v3（女声·娇率才女）"},
                            {"longanran_v3", "龙安燃 v3（女声·活泼质感）"},
                            {"longanxuan_v3", "龙安宣 v3（女声·经典直播）"},
                            {"longshuo_v3", "龙硕 v3（男声·博才干练）"},
                            {"longshu_v3", "龙书 v3（男声·沉稳青年）"},
                            {"loongbella_v3", "Bella 3.0（女声·精准干练）"},
                    }, "longanyang"),
            // 百炼 CosyVoice v1
            new ModelEntry(
                    new String[]{"cosyvoice-v1", "cosyvoice"},
                    "百炼 CosyVoice v1", API_DASHSCOPE_NATIVE,
                    new String[][]{
                            {"longxiaochun", "龙小淳（女声，推荐）"},
                            {"longwan", "龙婉（女声·温柔）"},
                            {"longshu", "龙书（男声·沉稳）"},
                            {"longlaotie", "龙老铁（东北男声）"},
                            {"longjielidou", "龙杰力豆（男声·诙谐）"},
                    }, "longxiaochun"),
            // OpenAI TTS
            new ModelEntry(
                    new String[]{"tts-1", "gpt-4o-mini-tts", "gpt-4o-tts"},
                    "OpenAI TTS", API_OPENAI_COMPATIBLE,
                    new String[][]{
                            {"alloy", "Alloy（中性）"},
                            {"nova", "Nova（女声）"},
                            {"shimmer", "Shimmer（女声·清亮）"},
                            {"echo", "Echo（男声）"},
                            {"onyx", "Onyx（男声·低沉）"},
                            {"fable", "Fable（故事腔）"},
                    }, "nova"),
            // 讯飞语音合成（WebSocket 接口）
            new ModelEntry(
                    new String[]{"xfyun-tts", "iflytek-tts"},
                    "讯飞语音合成", API_OPENAI_COMPATIBLE,
                    new String[][]{
                            {"x4_xiaoyan", "小燕（女声·清新自然，推荐）"},
                            {"x4_yezi", "小叶（男声·沉稳）"},
                            {"x4_shaofeng", "少峰（男声·知性）"},
                            {"x4_xiaomei", "小美（女声·温柔）"},
                            {"x4_xiaoxin", "小新（男声·活力）"},
                            {"x4_xiaoyu", "小雨（女声·甜美女声）"},
                            {"x4_xiaolin", "小林（男声·沉稳大气）"},
                            {"x4_xiaorong", "小蓉（女声·温暖）"},
                            {"x4_xiaoshuang", "小双（女声·童声）"},
                            {"xiaoyan", "小燕（女声·经典）"},
                            {"xiaoyu", "小宇（男声·经典）"},
                            {"xiaofeng", "小峰（男声·经典）"},
                            {"catherine", "Catherine（女声·英式英语）"},
                            {"john", "John（男声·美式英语）"},
                    }, "x4_xiaoyan"),
            // 火山引擎语音合成（HTTP REST 接口）
            new ModelEntry(
                    new String[]{"volcano-tts", "volcengine-tts"},
                    "火山引擎语音合成", API_OPENAI_COMPATIBLE,
                    new String[][]{
                            {"zh_female_qingxin", "清新女声（推荐）"},
                            {"zh_male_chunhou", "醇厚男声"},
                            {"zh_female_wanxiang", "万象女声"},
                            {"zh_male_hunsheng", "浑声男声"},
                            {"zh_female_tianmei", "甜美女声"},
                            {"zh_male_yangguang", "阳光男声"},
                            {"zh_female_zhiqi", "知性女声"},
                            {"zh_male_chengshu", "成熟男声"},
                    }, "zh_female_qingxin"),
            // 百度语音合成（HTTP REST 接口）
            new ModelEntry(
                    new String[]{"baidu-tts"},
                    "百度语音合成", API_OPENAI_COMPATIBLE,
                    new String[][]{
                            {"0", "小女声（女声·亲和）"},
                            {"1", "小男声（男声·亲和）"},
                            {"3", "情感男声（男声·情感）"},
                            {"4", "情感女声（女声·情感，推荐）"},
                            {"5", "情感男声（男声·广播）"},
                            {"106", "知性女声"},
                            {"110", "阳光男声"},
                            {"111", "甜美女声"},
                    }, "4"),
            // MiniMax speech
            new ModelEntry(
                    new String[]{"speech-02", "speech-01", "speech-2.5", "minimax"},
                    "MiniMax Speech", API_OPENAI_COMPATIBLE,
                    new String[][]{
                            {"male-qn-qingse", "青涩青年音色"},
                            {"male-qn-jingying", "精英青年音色"},
                            {"female-shaonv", "少女音色"},
                            {"female-yujie", "御姐音色"},
                            {"presenter_male", "男性主持人"},
                            {"presenter_female", "女性主持人"},
                    }, "female-shaonv"),
            // 小米 MiMo TTS（OpenAI 兼容接口）
            new ModelEntry(
                    new String[]{"mimo", "xiaomi-mimo", "mimo-v2.5-tts"},
                    "小米 MiMo TTS", API_OPENAI_COMPATIBLE,
                    new String[][]{
                            {"mimo_default", "MiMo-默认（跟随集群）"},
                            {"冰糖", "冰糖-活泼女声（中文）"},
                            {"茉莉", "茉莉-温柔女声（中文）"},
                            {"苏打", "苏打-阳光男声（中文）"},
                            {"白桦", "白桦-沉稳男声（中文）"},
                            {"Mia", "Mia-清亮女声（英文）"},
                            {"Chloe", "Chloe-优雅女声（英文）"},
                            {"Milo", "Milo-阳光男声（英文）"},
                            {"Dean", "Dean-沉稳男声（英文）"},
                    }, "冰糖"),
    };

    // ==================== ASR 模型（内置推荐） ====================

    /** 常用 ASR 模型（仅用于推荐显示） */
    public static final String[] KNOWN_ASR_MODELS = {
            "qwen3-asr-flash",
            "fun-asr-flash",
            "paraformer-v2",
            "gummy-realtime-v1",
            "whisper-1",
            "16k_zh",
            "16k_en",
            "volcengine_streaming_common",
            "1537",
            "mimo-v2.5-asr",
    };

    // ==================== 常用端点模板 ====================

    /**
     * 常用端点模板（名称 + 地址 + 预置 TTS/ASR 模型），全部来自
     * ProviderConfigManager 配置表（providers.json）；增删服务更新配置文件即可。
     */
    public static EndpointTemplate[] getEndpointTemplates() {
        java.util.List<com.oilquiz.app.ai.model.ProviderConfigManager.EndpointTemplate> list =
            com.oilquiz.app.ai.model.ProviderConfigManager.get().getEndpointTemplates();
        EndpointTemplate[] out = new EndpointTemplate[list.size()];
        for (int i = 0; i < list.size(); i++) {
            com.oilquiz.app.ai.model.ProviderConfigManager.EndpointTemplate t = list.get(i);
            out[i] = new EndpointTemplate(t.name, t.apiUrl, t.ttsModel, t.asrModel);
        }
        return out;
    }

    // ==================== 缓存与异步 ====================

    private static final Gson gson = new Gson();
    private static final ExecutorService fetchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SpeechRegistry-Fetch");
        t.setDaemon(true);
        return t;
    });

    /** 在线模型列表缓存（modelId -> RemoteModelInfo） */
    private static volatile Map<String, RemoteModelInfo> cachedModels = new ConcurrentHashMap<>();
    private static volatile long modelsCacheTime = 0;

    /** 自定义音色缓存（modelId -> voice list） */
    private static volatile Map<String, List<TTSService.Voice>> cachedCustomVoices = new ConcurrentHashMap<>();
    private static volatile long voicesCacheTime = 0;

    private SpeechModelRegistry() {
    }

    // ==================== 实时获取：模型列表 ====================

    /**
     * 异步从百炼 API 刷新模型列表和自定义音色。
     * 调用后缓存更新，后续 getVoicesForModel / getAvailableModels 等方法自动使用新数据。
     *
     * @param context 上下文（用于读取 API Key 和 SharedPreferences）
     */
    public static void refreshAsync(Context context) {
        fetchExecutor.execute(() -> {
            try {
                refreshModels(context);
            } catch (Exception e) {
                AILogger.w(TAG, "刷新模型列表失败: " + e.getMessage());
            }
            try {
                refreshCustomVoices(context);
            } catch (Exception e) {
                AILogger.w(TAG, "刷新自定义音色失败: " + e.getMessage());
            }
        });
    }

    /**
     * 同步从百炼 API 获取模型列表（在 fetchExecutor 中调用）
     *
     * 百炼端点：GET https://dashscope.aliyuncs.com/api/v1/models?capabilities=TTS&page_size=100
     */
    private static void refreshModels(Context context) {
        OnlineModelManager.OnlineModelConfig config = getDashScopeConfig(context);
        if (config == null) {
            AILogger.i(TAG, "无百炼配置，跳过模型列表刷新");
            return;
        }

        // 检查缓存是否过期
        if (System.currentTimeMillis() - modelsCacheTime < CACHE_TTL_MS && !cachedModels.isEmpty()) {
            AILogger.i(TAG, "模型列表缓存未过期，跳过刷新");
            return;
        }

        // 尝试从 SharedPreferences 恢复缓存
        if (cachedModels.isEmpty()) {
            loadModelsFromPrefs(context);
        }
        if (System.currentTimeMillis() - modelsCacheTime < CACHE_TTL_MS && !cachedModels.isEmpty()) {
            return;
        }

        // 从 API 获取 TTS 和 ASR 模型
        Map<String, RemoteModelInfo> models = new HashMap<>();
        for (String capability : new String[]{"TTS", "ASR"}) {
            try {
                List<RemoteModelInfo> list = fetchModelsFromApi(config, capability);
                for (RemoteModelInfo m : list) {
                    models.put(m.modelId, m);
                }
            } catch (Exception e) {
                AILogger.w(TAG, "获取" + capability + "模型列表失败: " + e.getMessage());
            }
        }

        if (!models.isEmpty()) {
            cachedModels = new ConcurrentHashMap<>(models);
            modelsCacheTime = System.currentTimeMillis();
            saveModelsToPrefs(context);
            AILogger.i(TAG, "模型列表刷新成功，共 " + models.size() + " 个模型");
        }
    }

    private static List<RemoteModelInfo> fetchModelsFromApi(OnlineModelManager.OnlineModelConfig config,
                                                             String capability) throws Exception {
        String baseUrl = resolveDashScopeBase(config.apiUrl);
        String url = baseUrl + "/models?capabilities=" + capability + "&page_size=100&language=zh-CN";
        HttpURLConnection conn = SpeechHttpClient.openGet(url, config.apiKey);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        try {
            SpeechHttpClient.assertOk(conn, "获取模型列表");
            String body = SpeechHttpClient.readBody(conn);
            return parseModelsResponse(body);
        } finally {
            conn.disconnect();
        }
    }

    private static List<RemoteModelInfo> parseModelsResponse(String body) {
        List<RemoteModelInfo> result = new ArrayList<>();
        try {
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null) return result;
            JsonObject output = root.getAsJsonObject("output");
            if (output == null) return result;
            JsonArray models = output.getAsJsonArray("models");
            if (models == null) return result;
            for (JsonElement el : models) {
                if (!el.isJsonObject()) continue;
                JsonObject m = el.getAsJsonObject();
                String modelId = optStr(m, "model");
                if (modelId == null || modelId.isEmpty()) continue;
                String name = optStr(m, "name");
                String desc = optStr(m, "description");
                List<String> caps = new ArrayList<>();
                JsonArray capArr = m.getAsJsonArray("capabilities");
                if (capArr != null) {
                    for (JsonElement ce : capArr) {
                        caps.add(ce.getAsString());
                    }
                }
                result.add(new RemoteModelInfo(modelId, name, desc, caps));
            }
        } catch (Exception e) {
            AILogger.w(TAG, "解析模型列表失败: " + e.getMessage());
        }
        return result;
    }

    // ==================== 实时获取：自定义音色 ====================

    /**
     * 从百炼声音复刻/设计 API 获取用户自定义音色
     *
     * 两种 API：
     * - CosyVoice/Qwen-Audio-TTS：model=voice-enrollment, action=list_voice
     * - Qwen-TTS：model=qwen-voice-enrollment, action=list
     */
    private static void refreshCustomVoices(Context context) {
        OnlineModelManager.OnlineModelConfig config = getDashScopeConfig(context);
        if (config == null) return;

        if (System.currentTimeMillis() - voicesCacheTime < CACHE_TTL_MS && !cachedCustomVoices.isEmpty()) {
            return;
        }

        if (cachedCustomVoices.isEmpty()) {
            loadVoicesFromPrefs(context);
        }
        if (System.currentTimeMillis() - voicesCacheTime < CACHE_TTL_MS && !cachedCustomVoices.isEmpty()) {
            return;
        }

        Map<String, List<TTSService.Voice>> allVoices = new HashMap<>();
        String baseUrl = resolveDashScopeBase(config.apiUrl);
        String endpoint = baseUrl + "/services/audio/tts/customization";

        // CosyVoice/Qwen-Audio-TTS 自定义音色
        try {
            List<TTSService.Voice> cosyVoices = fetchCustomVoicesFromApi(
                    config, endpoint, "voice-enrollment", "list_voice", "voice_id");
            if (!cosyVoices.isEmpty()) {
                allVoices.put("cosyvoice", cosyVoices);
            }
        } catch (Exception e) {
            AILogger.w(TAG, "获取CosyVoice自定义音色失败: " + e.getMessage());
        }

        // Qwen-TTS 自定义音色
        try {
            List<TTSService.Voice> qwenVoices = fetchCustomVoicesFromApi(
                    config, endpoint, "qwen-voice-enrollment", "list", "voice");
            if (!qwenVoices.isEmpty()) {
                allVoices.put("qwen-tts", qwenVoices);
            }
        } catch (Exception e) {
            AILogger.w(TAG, "获取Qwen-TTS自定义音色失败: " + e.getMessage());
        }

        if (!allVoices.isEmpty()) {
            cachedCustomVoices = new ConcurrentHashMap<>(allVoices);
            voicesCacheTime = System.currentTimeMillis();
            saveVoicesToPrefs(context);
            AILogger.i(TAG, "自定义音色刷新成功，共 " + allVoices.size() + " 组");
        }
    }

    private static List<TTSService.Voice> fetchCustomVoicesFromApi(
            OnlineModelManager.OnlineModelConfig config, String endpoint,
            String model, String action, String voiceIdField) throws Exception {

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        JsonObject input = new JsonObject();
        input.addProperty("action", action);
        input.addProperty("page_size", 100);
        requestBody.add("input", input);
        String bodyStr = requestBody.toString();

        HttpURLConnection conn = SpeechHttpClient.openPost(endpoint, config.apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bodyStr.getBytes(StandardCharsets.UTF_8));
        }
        try {
            SpeechHttpClient.assertOk(conn, "获取自定义音色");
            String body = SpeechHttpClient.readBody(conn);
            return parseCustomVoicesResponse(body, voiceIdField);
        } finally {
            conn.disconnect();
        }
    }

    private static List<TTSService.Voice> parseCustomVoicesResponse(String body, String voiceIdField) {
        List<TTSService.Voice> result = new ArrayList<>();
        try {
            JsonObject root = gson.fromJson(body, JsonObject.class);
            if (root == null) return result;
            JsonObject output = root.getAsJsonObject("output");
            if (output == null) return result;
            JsonArray voiceList = output.getAsJsonArray("voice_list");
            if (voiceList == null) return result;
            for (JsonElement el : voiceList) {
                if (!el.isJsonObject()) continue;
                JsonObject v = el.getAsJsonObject();
                String id = optStr(v, voiceIdField);
                if (id == null || id.isEmpty()) continue;
                String displayName = id;
                // 如果是 Qwen-TTS 的 voice 字段，加上自定义标记
                if ("voice".equals(voiceIdField)) {
                    displayName = id + "（自定义）";
                } else {
                    // CosyVoice 的 voice_id 可能带前缀
                    String status = optStr(v, "status");
                    if ("OK".equals(status)) {
                        displayName = id + "（自定义·已审核）";
                    } else if ("DEPLOYING".equals(status)) {
                        displayName = id + "（自定义·审核中）";
                    } else {
                        displayName = id + "（自定义）";
                    }
                }
                result.add(new TTSService.Voice(id, displayName));
            }
        } catch (Exception e) {
            AILogger.w(TAG, "解析自定义音色失败: " + e.getMessage());
        }
        return result;
    }

    // ==================== 缓存持久化 ====================

    private static void loadModelsFromPrefs(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long ts = prefs.getLong(KEY_MODELS_TIMESTAMP, 0);
            if (System.currentTimeMillis() - ts > CACHE_TTL_MS) return;
            String json = prefs.getString(KEY_MODELS_CACHE, null);
            if (json == null) return;
            Map<String, RemoteModelInfo> map = new HashMap<>();
            JsonObject root = gson.fromJson(json, JsonObject.class);
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject obj = entry.getValue().getAsJsonObject();
                List<String> caps = new ArrayList<>();
                JsonArray capArr = obj.getAsJsonArray("capabilities");
                if (capArr != null) {
                    for (JsonElement ce : capArr) caps.add(ce.getAsString());
                }
                map.put(entry.getKey(), new RemoteModelInfo(
                        entry.getKey(), optStr(obj, "name"), optStr(obj, "description"), caps));
            }
            cachedModels = new ConcurrentHashMap<>(map);
            modelsCacheTime = ts;
        } catch (Exception e) {
            AILogger.w(TAG, "加载模型缓存失败: " + e.getMessage());
        }
    }

    private static void saveModelsToPrefs(Context context) {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, RemoteModelInfo> entry : cachedModels.entrySet()) {
                RemoteModelInfo m = entry.getValue();
                JsonObject obj = new JsonObject();
                obj.addProperty("name", m.name);
                obj.addProperty("description", m.description);
                JsonArray caps = new JsonArray();
                for (String c : m.capabilities) caps.add(c);
                obj.add("capabilities", caps);
                root.add(entry.getKey(), obj);
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_MODELS_CACHE, root.toString())
                    .putLong(KEY_MODELS_TIMESTAMP, modelsCacheTime)
                    .apply();
        } catch (Exception e) {
            AILogger.w(TAG, "保存模型缓存失败: " + e.getMessage());
        }
    }

    private static void loadVoicesFromPrefs(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long ts = prefs.getLong(KEY_VOICES_TIMESTAMP, 0);
            if (System.currentTimeMillis() - ts > CACHE_TTL_MS) return;
            String json = prefs.getString(KEY_VOICES_CACHE, null);
            if (json == null) return;
            Map<String, List<TTSService.Voice>> map = new HashMap<>();
            JsonObject root = gson.fromJson(json, JsonObject.class);
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonArray arr = entry.getValue().getAsJsonArray();
                List<TTSService.Voice> voices = new ArrayList<>();
                for (JsonElement ve : arr) {
                    JsonObject vo = ve.getAsJsonObject();
                    voices.add(new TTSService.Voice(optStr(vo, "id"), optStr(vo, "name")));
                }
                map.put(entry.getKey(), voices);
            }
            cachedCustomVoices = new ConcurrentHashMap<>(map);
            voicesCacheTime = ts;
        } catch (Exception e) {
            AILogger.w(TAG, "加载音色缓存失败: " + e.getMessage());
        }
    }

    private static void saveVoicesToPrefs(Context context) {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, List<TTSService.Voice>> entry : cachedCustomVoices.entrySet()) {
                JsonArray arr = new JsonArray();
                for (TTSService.Voice v : entry.getValue()) {
                    JsonObject vo = new JsonObject();
                    vo.addProperty("id", v.id);
                    vo.addProperty("name", v.name);
                    arr.add(vo);
                }
                root.add(entry.getKey(), arr);
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_VOICES_CACHE, root.toString())
                    .putLong(KEY_VOICES_TIMESTAMP, voicesCacheTime)
                    .apply();
        } catch (Exception e) {
            AILogger.w(TAG, "保存音色缓存失败: " + e.getMessage());
        }
    }

    /** 强制清除缓存，下次 refreshAsync 会重新获取 */
    public static void invalidateCache(Context context) {
        cachedModels = new ConcurrentHashMap<>();
        cachedCustomVoices = new ConcurrentHashMap<>();
        modelsCacheTime = 0;
        voicesCacheTime = 0;
        if (context != null) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
        }
    }

    // ==================== 查询：模型列表 ====================

    /** 获取在线 TTS 模型列表（从缓存，可能为空） */
    public static List<RemoteModelInfo> getCachedTtsModels() {
        List<RemoteModelInfo> result = new ArrayList<>();
        for (RemoteModelInfo m : cachedModels.values()) {
            if (m.isTts()) result.add(m);
        }
        return result;
    }

    /** 获取在线 ASR 模型列表（从缓存，可能为空） */
    public static List<RemoteModelInfo> getCachedAsrModels() {
        List<RemoteModelInfo> result = new ArrayList<>();
        for (RemoteModelInfo m : cachedModels.values()) {
            if (m.isAsr()) result.add(m);
        }
        return result;
    }

    /** 获取所有可用的 ASR 模型名（在线 + 内置） */
    public static List<String> getAvailableAsrModelNames() {
        List<String> result = new ArrayList<>();
        // 在线获取的
        for (RemoteModelInfo m : cachedModels.values()) {
            if (m.isAsr() && !result.contains(m.modelId)) {
                result.add(m.modelId);
            }
        }
        // 内置的
        for (String m : KNOWN_ASR_MODELS) {
            if (!result.contains(m)) result.add(m);
        }
        return result;
    }

    // ==================== 查询：音色列表（聚合） ====================

    /**
     * 获取指定模型的音色列表（聚合：在线 + 自定义 + 内置）
     *
     * <p>优先级：</p>
     * <ol>
     *   <li>内置音色（按模型名匹配 ModelEntry）——最可靠，因为 API 不返回内置音色</li>
     *   <li>自定义音色（从声音复刻/设计 API 获取）</li>
     *   <li>在线模型信息（仅用于显示模型名，不包含音色）</li>
     * </ol>
     */
    public static List<TTSService.Voice> getVoicesForModel(String modelName) {
        List<TTSService.Voice> result = new ArrayList<>();

        // 1. 内置音色（最可靠）
        ModelEntry builtin = matchTtsModel(modelName);
        if (builtin != null) {
            for (TTSService.Voice v : builtin.voices) {
                result.add(v);
            }
        }

        // 2. 自定义音色（追加到内置后面）
        List<TTSService.Voice> customVoices = getCustomVoicesForModel(modelName);
        if (customVoices != null) {
            for (TTSService.Voice v : customVoices) {
                // 去重
                boolean exists = false;
                for (TTSService.Voice existing : result) {
                    if (existing.id.equals(v.id)) { exists = true; break; }
                }
                if (!exists) result.add(v);
            }
        }

        return result;
    }

    /** 获取指定模型的自定义音色 */
    private static List<TTSService.Voice> getCustomVoicesForModel(String modelName) {
        if (modelName == null) return null;
        String lower = modelName.toLowerCase();
        int apiType = getTtsApiType(modelName);

        if (apiType == API_DASHSCOPE_QWEN_TTS) {
            return cachedCustomVoices.get("qwen-tts");
        } else if (apiType == API_DASHSCOPE_NATIVE) {
            return cachedCustomVoices.get("cosyvoice");
        }
        return null;
    }

    // ==================== 匹配查询（保留原有逻辑） ====================

    /**
     * 按模型名匹配内置 TTS 模型条目（小写包含匹配）
     */
    public static ModelEntry matchTtsModel(String modelName) {
        if (modelName == null || modelName.isEmpty()) return null;
        String lower = modelName.toLowerCase();
        for (ModelEntry entry : BUILTIN_TTS_MODELS) {
            for (String kw : entry.matchKeywords) {
                if (lower.equals(kw)) return entry;
            }
        }
        for (ModelEntry entry : BUILTIN_TTS_MODELS) {
            for (String kw : entry.matchKeywords) {
                if (lower.contains(kw)) return entry;
            }
        }
        return null;
    }

    /** 判断模型是否应走百炼接口 */
    public static boolean isDashScopeNativeModel(String modelName) {
        int type = getTtsApiType(modelName);
        return type == API_DASHSCOPE_NATIVE || type == API_DASHSCOPE_QWEN_TTS;
    }

    /**
     * 取模型对应的接口类型，未命中注册表时按模型名启发式兜底
     */
    public static int getTtsApiType(String modelName) {
        ModelEntry entry = matchTtsModel(modelName);
        if (entry != null) return entry.apiType;
        if (modelName == null) return API_OPENAI_COMPATIBLE;
        String lower = modelName.toLowerCase();
        if (lower.contains("audio") && lower.contains("tts")) return API_DASHSCOPE_NATIVE;
        if (lower.startsWith("qwen") && lower.contains("tts")) return API_DASHSCOPE_QWEN_TTS;
        if (lower.contains("cosyvoice") || lower.contains("sambert")) return API_DASHSCOPE_NATIVE;
        return API_OPENAI_COMPATIBLE;
    }

    /** 全部内置 TTS 模型条目 */
    public static ModelEntry[] getAllTtsModels() {
        return BUILTIN_TTS_MODELS;
    }

    /** 所有注册表音色的并集（离线兜底预设） */
    public static List<TTSService.Voice> getAllPresetVoices() {
        List<TTSService.Voice> result = new ArrayList<>();
        for (ModelEntry entry : BUILTIN_TTS_MODELS) {
            for (TTSService.Voice v : entry.voices) {
                boolean exists = false;
                for (TTSService.Voice existing : result) {
                    if (existing.id.equals(v.id)) { exists = true; break; }
                }
                if (!exists) {
                    result.add(new TTSService.Voice(v.id, v.id + "（" + entry.displayName + "）"));
                }
            }
        }
        return result;
    }

    // ==================== 工具方法 ====================

    /** 获取当前百炼端点配置 */
    private static OnlineModelManager.OnlineModelConfig getDashScopeConfig(Context context) {
        try {
            OnlineModelManager mm = OnlineModelManager.getInstance(context);
            List<OnlineModelManager.OnlineModelConfig> all = mm.getModelList();
            if (all == null) return null;
            for (OnlineModelManager.OnlineModelConfig c : all) {
                if (c.enabled && SpeechModelSelector.isDashScopeEndpoint(c.apiUrl)) {
                    return c;
                }
            }
        } catch (Exception e) {
            AILogger.w(TAG, "获取百炼配置失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 从用户配置的 apiUrl 推断百炼 API 基础 URL。
     * 用户可能配置的是 compatible-mode/v1 端点，需要提取基础 URL。
     */
    private static String resolveDashScopeBase(String apiUrl) {
        if (apiUrl == null) return "https://dashscope.aliyuncs.com/api/v1";
        // 去掉 /compatible-mode/v1 等后缀，保留协议+域名
        String base = apiUrl;
        for (String suffix : new String[]{"/compatible-mode/v1", "/compatible-mode/v1/",
                "/api/v1", "/api/v1/", "/v1", "/v1/"}) {
            int idx = base.indexOf(suffix);
            if (idx > 0) {
                base = base.substring(0, idx);
                break;
            }
        }
        return base + "/api/v1";
    }

    private static String optStr(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        return (el == null || el.isJsonNull()) ? null : el.getAsString();
    }
}
