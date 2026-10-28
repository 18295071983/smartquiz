package com.oilquiz.app.ai.tool;

/**
 * 工具结果<b>读侧</b>的键名清单（唯一出处）。
 *
 * <h3>本类为什么长这样</h3>
 * <p>同一个概念在不同读取处各抄一份键清单，是本项目"卡片/摘要读不到就静默掉原始 JSON"的
 * 根因之一（另一类在组件 props 侧，见 {@code ComponentFieldIntegrityTest}）。实测漂移样例：
 * 文件路径三套、摘要三套、大小两套、URL 两套、消息两套。新工具换个键名，就得在每个读取处
 * 补一遍，漏一处就少一处。所以清单必须集中在一处。</p>
 *
 * <h3>集中的单位是"读取处"，不是"概念"</h3>
 * <p>2026-10-23 两次踩过同一坑，记死在这里：
 * <ol>
 *   <li>第一版把各处清单按<b>概念</b>并成全局表（一张 {@code PATH}、一张 {@code SUMMARY}…），
 *       读取处一律问概念表。并集配上"第一个命中"的语义，等于把每个读取处的接受面
 *       <b>放大到全部工具的键空间</b>：{@code output} 在 shell/python/多数动态工具里是
 *       <b>标准输出正文</b>，只有写入类工具拿它当落盘路径，并进 PATH/ARTIFACT 之后
 *       shell 摘要多打一行"📄 文件：&lt;整段 stdout&gt;"，动态工具结果被归成产物文件、
 *       生成一张 path 指向一段文本的 file_card（真机现象：卡片外观正常、每条链接点开都失败）。
 *       {@code target}（落盘目标/跳转地址/ssh 主机三义）、{@code name}
 *       （工具名/应用名/系列名/文件名）、{@code result}（整段载荷/报告正文）同病。</li>
 *   <li>第二版只摘掉那几个坏键、其余仍问概念表——顺序仍然和历史上不一样：
 *       {@code file,filePath,path} 变成 {@code filePath,path,…,file}，
 *       {@code size,bytes_written,file_size} 变成 {@code size,file_size,bytes_written}，
 *       {@code url,target} 变成 {@code url,link,linkUrl,href,target}。
 *       first-hit 语义就写在顺序里，顺序变了就是行为变了，而这类变化没人审得过来。</li>
 * </ol>
 * 所以现在<b>每个读取处一张表，逐字照抄 {@code git show 3d5687b8^} 的原清单（含顺序）</b>，
 * 由 {@code ToolResultFieldsTest} 按"模板名 + 变量名"逐个比对到字面一致。
 * 本类<b>不提供 union/并表能力</b>——合并正是引入事故的那个动作；
 * 一处想放宽，就在那一处把键写出来，并在测试里留下"旧顺序仍是新顺序的子序列"的证据。</p>
 *
 * <h3>唯一保留的放宽</h3>
 * <p>{@link #DYNAMIC_ARTIFACT}：动态工具产物归类历史上只看 {@code file,path,url}，
 * 现在多认 {@code file_path,link} 两个键。逐键核过：都是路径/链接形状、
 * 历史三键的相对顺序不变，只会把原本"静默漏识别"的结果认出来，不会改变原有正确选择。</p>
 */
public final class ToolResultFields {

    private ToolResultFields() {
    }

    // ==================== network_search ====================

    /** networkSearchTemplate 的结果数（原清单；{@code totalQuestions}/{@code successCount} 是别的量，不收） */
    public static final String[] SEARCH_COUNT = {"count", "total"};

    /** networkSearchTemplate 的失败原因（搜索工具确实把提示放在 error→message→msg） */
    public static final String[] SEARCH_ERROR = {"error", "message", "msg"};

    // ==================== database ====================

    /** databaseTemplate 的提示文本（工具只写 message 一个键） */
    public static final String[] DATABASE_MESSAGE = {"message"};

    /** databaseTemplate 的错误原文 */
    public static final String[] DATABASE_ERROR = {"error"};

    // ==================== location ====================

    /** locationTemplate 的错误原文（无 error 时退到 message） */
    public static final String[] LOCATION_ERROR = {"error", "message"};

    // ==================== app_operation ====================

    /** appOperationTemplate 的消息（原清单就是 message,msg,info 三个） */
    public static final String[] APP_OPERATION_MESSAGE = {"message", "msg", "info"};

    /** appOperationTemplate 的计数（只认 count；total/successCount 在这类结果里是别的量） */
    public static final String[] APP_OPERATION_COUNT = {"count"};

    // ==================== file_reader / file_analyzer ====================

    /**
     * fileReaderTemplate 与 fileAnalyzerTemplate 的路径（两处原清单相同）。
     *
     * <p>尾项 {@code name} 是有意的：读取类结果常只给文件名。{@code dir_path} 之类
     * "路径形状的键"没在历史清单里，不收——收了就可能把目录当被读文件显示。</p>
     */
    public static final String[] PATH_WITH_NAME = {"file_path", "path", "file", "name"};

    /** fileAnalyzerTemplate 的类型（就是这三个键，两个读取处共用） */
    public static final String[] FILE_TYPE = {"file_type", "type", "format"};

    /** fileAnalyzerTemplate 的大小 */
    public static final String[] ANALYZER_SIZE = {"size", "file_size", "length"};

    /** fileAnalyzerTemplate 的摘要 */
    public static final String[] ANALYZER_SUMMARY = {"summary", "analysis_summary", "overview"};

    // ==================== file_generator（写入类） ====================

    /** fileGeneratorTemplate 的消息 */
    public static final String[] FILE_GENERATOR_MESSAGE = {"message", "msg"};

    /**
     * fileGeneratorTemplate 的路径——<b>只有这一处可以把 {@code output}/{@code target}
     * 当路径</b>：写入类工具拿这两个键存落盘目标。别的读取处不许借用这张表。
     */
    public static final String[] FILE_GENERATOR_PATH =
            {"file_path", "file_name", "path", "output", "target"};

    /** fileGeneratorTemplate 的大小（顺序照旧：bytes_written 在 file_size 之前） */
    public static final String[] FILE_GENERATOR_SIZE = {"size", "bytes_written", "file_size"};

    /** fileGeneratorTemplate 的 unified diff（edit/diff 动作用，模型要照着它核对改对没有） */
    public static final String[] FILE_GENERATOR_DIFF = {"unifiedDiff", "unified_diff", "diff_text"};

    // ==================== webpage_reader ====================

    /** webpageReaderTemplate 的链接 */
    public static final String[] WEBPAGE_URL = {"url", "link"};

    /** webpageReaderTemplate 的摘要 */
    public static final String[] WEBPAGE_SUMMARY = {"summary", "abstract", "overview", "description"};

    // ==================== smart_research ====================

    /**
     * smartResearchTemplate 的正文——这一处的 {@code result} 就是报告正文，
     * 所以它有；别的读取处不许据此把 {@code result} 当摘要。
     */
    public static final String[] RESEARCH_SUMMARY =
            {"summary", "conclusion", "report", "overview", "result", "answer"};

    // ==================== system_resource（shell / ssh） ====================

    /** systemResourceTemplate 的消息 */
    public static final String[] SYSTEM_RESOURCE_MESSAGE = {"message", "msg", "info"};

    /**
     * systemResourceTemplate 的链接位：{@code url} 优先，没有才退到 {@code target}
     * ——ssh 类结果的 target 是连接目标，历史上就是被这样显示的，顺序不许调。
     */
    public static final String[] SYSTEM_RESOURCE_URL = {"url", "target"};

    /**
     * systemResourceTemplate 的产物文件位：<b>{@code file} 在 {@code path} 之前</b>。
     *
     * <p>不收 {@code output}——shell 结果里它就是那段 stdout，收进来等于把整段输出
     * 当"📄 文件："再打一遍（2026-10-23 真机故障）。顺序也不许并成
     * {@code filePath,path,…,file}：shell/python 工具常同时带 {@code file} 与 {@code path}，
     * 谁先命中渲出来的就是谁。</p>
     */
    public static final String[] SYSTEM_RESOURCE_FILE = {"file", "filePath", "path"};

    // ==================== permission_manager / python_calculate ====================

    /** permissionManagerTemplate 的消息 */
    public static final String[] PERMISSION_MESSAGE = {"message", "msg"};

    /** pythonCalculateTemplate 的错误原文 */
    public static final String[] CALCULATE_ERROR = {"error", "err", "exception"};

    // ==================== 动态工具产物归类 ====================

    /**
     * 产物引用：文件或 URL 都算（{@code DynamicToolResultRenderer} 归类用）。
     *
     * <p><b>这里的值会被直接写进卡片的 path/url</b>，所以最讲究：{@code output} 曾在表里，
     * 于是 {@code {output:"已保存3条"}} 被归成文件、生成一张点开就"文件不存在"的卡。
     * 历史清单是 {@code file,path,url}；现表多认 {@code file_path,link}（见类注释
     * "唯一保留的放宽"），且 {@code file→path→url} 的相对顺序一字未动——
     * 顺序变了同类结果的归类就会变。</p>
     */
    public static final String[] DYNAMIC_ARTIFACT = {"file", "file_path", "path", "url", "link"};
}
