package com.oilquiz.app.ai.speech;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音模型内置资源注册表（Speech Model Registry）
 *
 * 内置常见语音合成/识别服务商的资源信息：
 * - 请求地址（OpenAI 兼容地址，可替换为业务空间专属域名）
 * - 模型名（TTS / ASR）
 * - 各模型配套的音色列表
 *
 * 运行时按当前选择的模型名自动匹配对应条目，用于：
 * 1. 音色列表展示（不同模型只显示配套音色，避免不可用音色误导用户）
 * 2. 请求接口路由（如百炼 Qwen-TTS/CosyVoice 需走 DashScope 原生接口）
 * 3. 模型管理页"快速添加"常用语音端点
 */
public final class SpeechModelRegistry {

    private SpeechModelRegistry() {
    }

    /** 接口类型：请求路由依据 */
    public static final int API_OPENAI_COMPATIBLE = 0;  // OpenAI 兼容 /audio/speech
    public static final int API_DASHSCOPE_NATIVE = 1;   // 百炼原生 /api/v1/services/audio/tts/SpeechSynthesizer

    /** 单个语音模型条目 */
    public static class ModelEntry {
        /** 模型名匹配关键字（小写，包含即匹配） */
        public final String[] matchKeywords;
        /** 显示名称 */
        public final String displayName;
        /** 接口类型（API_*） */
        public final int apiType;
        /** 配套音色（id -> 显示名） */
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

    // ==================== TTS 模型注册表 ====================

    private static final ModelEntry[] TTS_MODELS = {
            // 百炼 Qwen-TTS（原生接口，音色 Cherry 等）- 非实时模型
            new ModelEntry(
                    new String[]{"qwen-tts", "qwen3-tts", "qwen3.5-tts"},
                    "百炼 Qwen-TTS（非实时）", API_DASHSCOPE_NATIVE,
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
            // 百炼 Qwen-Audio-TTS（原生接口，音色 longan* 等）- 支持声音复刻/设计
            new ModelEntry(
                    new String[]{"qwen-audio-tts", "qwen-audio-3.0-tts"},
                    "百炼 Qwen-Audio-TTS（声音复刻/设计）", API_DASHSCOPE_NATIVE,
                    new String[][]{
                            // qwen-audio-3.0-tts-plus 旗舰音色
                            {"longanlingxin", "龙安灵心（女声·社交陪伴旗舰，知心温暖音）"},
                            {"longanlufeng", "龙安鲁风（男声·社交陪伴旗舰，明亮开朗音）"},
                            // qwen-audio-3.0-tts-flash 精品中文
                            {"longanfengyue", "龙安风悦（女声·自然亲切音）"},
                            {"longanyuanfei", "龙安元妃（女声·高傲妃子音）"},
                            {"longanlingxi", "龙安灵希（女声·可爱甜美音）"},
                            {"longanxiaoxin", "龙安小昕（女声·亲切活泼音）"},
                            {"longanhuan_v3.6", "龙安欢 v3.6（女声·25岁）"},
                            // 儿童陪伴
                            {"longjielidou_v3.6", "龙杰力豆（男声·5岁天真男童）"},
                            {"longpaopao_v3.6", "龙泡泡（女声·5岁软糯可爱音）"},
                            // 角色音/游戏
                            {"longhuohuo_v3.6", "龙火火（男声·8岁顽皮少年音）"},
                            {"longchuanshu_v3.6", "龙川叔（男声·川普大叔音）"},
                            // 精品英文
                            {"loongmary", "loongmary（女声·温暖英音）"},
                            {"loongeva_v3.6", "loongeva（女声·高智美音）"},
                            {"loongjohn", "loongjohn（男声·沉稳亲切美音）"},
                    }, "longanfengyue"),
            // 百炼 CosyVoice v2（原生接口，音色带 _v2 后缀）
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
            // 百炼 CosyVoice v3/v3.5（原生接口，仅北京地域）
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
            // 百炼 CosyVoice v1（原生接口）
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
            // MiniMax speech（OpenAI 兼容）
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
    };

    // ==================== ASR 模型注册表（常用模型名参考） ====================

    /** 常用 ASR 模型（仅用于推荐显示，识别走 OpenAI 兼容 /audio/transcriptions） */
    public static final String[] KNOWN_ASR_MODELS = {
            "qwen3-asr-flash",           // 百炼 Qwen3-ASR
            "fun-asr-flash",             // 百炼 Fun-ASR
            "paraformer-v2",             // 百炼 Paraformer
            "gummy-realtime-v1",         // 百炼实时识别
            "whisper-1",                 // OpenAI Whisper
    };

    // ==================== 常用端点模板 ====================

    public static final EndpointTemplate[] ENDPOINT_TEMPLATES = {
            new EndpointTemplate("阿里云百炼（公共）",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "qwen3-tts-flash", "qwen3-asr-flash"),
            new EndpointTemplate("OpenAI",
                    "https://api.openai.com/v1",
                    "tts-1", "whisper-1"),
            new EndpointTemplate("MiniMax",
                    "https://api.minimaxi.com/v1",
                    "speech-02-hd", null),
            new EndpointTemplate("DeepSeek（仅对话）",
                    "https://api.deepseek.com",
                    null, null),
    };

    // ==================== 匹配查询 ====================

    /**
     * 按模型名匹配 TTS 模型条目（小写包含匹配）
     *
     * @param modelName 当前选择的 TTS 模型名
     * @return 匹配到的条目，未匹配返回 null
     */
    public static ModelEntry matchTtsModel(String modelName) {
        if (modelName == null || modelName.isEmpty()) return null;
        String lower = modelName.toLowerCase();
        // 精确优先：先按完整模型名匹配，再按关键字包含匹配
        for (ModelEntry entry : TTS_MODELS) {
            for (String kw : entry.matchKeywords) {
                if (lower.equals(kw)) return entry;
            }
        }
        for (ModelEntry entry : TTS_MODELS) {
            for (String kw : entry.matchKeywords) {
                if (lower.contains(kw)) return entry;
            }
        }
        return null;
    }

    /** 取匹配模型的配套音色列表，未匹配返回空列表 */
    public static List<TTSService.Voice> getVoicesForModel(String modelName) {
        List<TTSService.Voice> result = new ArrayList<>();
        ModelEntry entry = matchTtsModel(modelName);
        if (entry != null) {
            for (TTSService.Voice v : entry.voices) {
                result.add(v);
            }
        }
        return result;
    }

    /** 判断模型是否应走百炼原生接口 */
    public static boolean isDashScopeNativeModel(String modelName) {
        ModelEntry entry = matchTtsModel(modelName);
        return entry != null && entry.apiType == API_DASHSCOPE_NATIVE;
    }

    /** 全部内置 TTS 模型条目（供 UI 快速选择） */
    public static ModelEntry[] getAllTtsModels() {
        return TTS_MODELS;
    }

    /** 所有注册表音色的并集（端点无法拉取时的兜底预设） */
    public static List<TTSService.Voice> getAllPresetVoices() {
        List<TTSService.Voice> result = new ArrayList<>();
        for (ModelEntry entry : TTS_MODELS) {
            for (TTSService.Voice v : entry.voices) {
                boolean exists = false;
                for (TTSService.Voice existing : result) {
                    if (existing.id.equals(v.id)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    result.add(new TTSService.Voice(v.id, v.id + "（" + entry.displayName + "）"));
                }
            }
        }
        return result;
    }
}
