package com.oilquiz.app.ai.tool;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 工具结果<b>读侧</b>的字段别名表（单一写者）。
 *
 * <p>为什么要收：同一个概念在不同读取处各写一份键名清单，是本项目"卡片/摘要读不到就静默
 * 掉原始 JSON"的根因之一（另一类根因在组件 props 侧，见 {@code ComponentFieldIntegrityTest}）。
 * 实测漂移样例：文件路径三套
 * （{@code file_path,path,file,name} / {@code file,filePath,path} /
 * {@code file_path,file_name,path,output,target}）、摘要三套、大小两套、URL 两套、消息两套。
 * 新工具换个键名，就得在每个读取处补一遍，漏一处就少一处。
 *
 * <p>三条边界规则（比"全都并起来"重要，都是看着实际代码定的）：
 * <ol>
 *   <li><b>不发明键名。</b>每个概念里的键都是全仓库实际出现过的（用
 *       {@code grep '"[a-zA-Z_]*path[a-zA-Z_]*"'} 之类穷举过），凭常识补进来的键名
 *       只会让表看起来更全、实际永远匹配不上。</li>
 *   <li><b>{@code message} 不属于 {@link #ERROR}。</b>很多工具的 {@code message} 是
 *       成功提示（天气、权限、导入都用它），把它当错误原文会把一句正常提示渲成错误。
 *       确实需要"没 error 就退回 message"的读取处，用 {@link #union} 显式组合。</li>
 *   <li><b>计数按种类分，不合并。</b>{@code rows/words/pages/lines/sources/matches/chars}
 *       看着都像 count，实际是不同的量；并成一个表会显示错数字。所以本表只收
 *       {@link #COUNT_TOTAL} 这种真正通用的，其余保持各处显式的种类清单。</li>
 * </ol>
 *
 * <p><b>第四条规则是 2026-10-23 用真机 bug 换来的</b>（前一次合并时漏了这条）：
 * <b>全局表只收"键名本身就表示这个概念"的键；含义随工具而变的键一律不进表</b>，
 * 由真的需要它的那一处点名。反例就是这次的 {@code output}：
 * 它在 shell / python / 大多数动态工具里是<b>正文（stdout）</b>，只有个别写入类工具拿它当
 * 落盘路径。我把它并进 {@link #PATH} 与 {@link #ARTIFACT} 之后，"第一个命中"的语义让
 * 每个没有真路径键的结果都把 stdout 当文件——shell 结果的 UI 摘要多出一行
 * "📄 文件：&lt;整段输出&gt;"，动态工具结果更被 {@code DynamicToolResultRenderer} 归成
 * 产物卡、渲染出一个指向文本的 file_card（用户看到的就是"卡片文件链接全坏了"）。
 * {@code target}（既可能是落盘目标也可能是跳转地址/连接主机）、{@code name}
 * （工具名/应用名/系列名/文件名）、{@code result}（既可能是正文也可能是执行结果对象）同理。
 * 需要它们的读取处改用本类里点名的组合表（{@link #PATH_OR_NAME} 等），
 * 组合关系写在调用处，不再靠全局表兜。</p>
 */
public final class ToolResultFields {

    /**
     * 文件路径：<b>只收键名本身就是路径的键</b>（见类注释第四条）。
     *
     * <p>{@code output}/{@code target}/{@code name} 不在这里——它们在别的工具里是正文、
     * 跳转目标、显示名；要那种降级请问 {@link #PATH_OR_NAME} / {@link #PATH_FILE_OPS}。</p>
     */
    public static final String[] PATH = {
            "file_path", "filePath", "filepath", "path", "output_path", "source_path",
            "target_path", "save_path", "local_path", "dir_path", "directory_path",
            "apk_path", "image_path", "audio_path", "pdf_path", "html_path", "json_path",
            "icon_path", "model_path", "file"
    };

    /**
     * {@link #PATH} 加"只有名字"降级：读取处明确知道结果里可能只有裸文件名时用
     * （迁移前 {@code file_reader} / {@code file_analyzer} 两处清单尾项就是
     * {@code file_path,path,file,name}）。
     */
    public static final String[] PATH_OR_NAME =
            union(PATH, new String[]{"file_name", "filename", "name"});

    /**
     * 写入类文件工具的老清单，<b>原样保留</b>（{@code output}/{@code target} 在这类结果里
     * 确实就是落盘路径）：{@code file_path,file_name,path,output,target}。
     * 只有 {@code fileOperationsTemplate} 一处用；别的读取处问 {@link #PATH}。
     */
    public static final String[] PATH_FILE_OPS = {
            "file_path", "file_name", "path", "output", "target"
    };

    /**
     * 链接地址（同样只收键名就是链接的键）。
     *
     * <p>{@code target} <b>不放进来</b>：它在系统设置类结果里是"目标页面/设置项"、
     * 在 ssh 里是"连接主机"，收进全局表会把主机名当链接渲染。需要它的
     * {@code appOperationTemplate} 用 {@link #URL_APP_OPS}。
     * {@code source} 也不放进来：它在网页提取里是"来源站点名"。</p>
     */
    public static final String[] URL = {"url", "link", "linkUrl", "href"};

    /** 应用操作类结果的老清单：{@code url,target}（这里的 target 就是跳转地址） */
    public static final String[] URL_APP_OPS = union(new String[]{"url"}, URL, new String[]{"target"});

    /**
     * 产物引用：文件或 URL 都算（动态工具结果收集、卡片归类用）。
     *
     * <p><b>这里的键会被直接写进卡片的 path/url</b>，所以最讲究：{@code output} 曾经被收进来，
     * 于是 {@code {output:"已保存3条"}} 这种结果被归成文件、生成一张点开就是
     * "文件不存在"的卡（本次真机现象）。表里只留键名就是路径或链接的键，
     * 并保持迁移前那三处的相对顺序（{@code file} 先于 {@code path} 先于 {@code url}），
     * 否则同类结果的归类会变。</p>
     */
    public static final String[] ARTIFACT = {"file", "file_path", "path", "url", "link"};

    /** 文件类型 */
    public static final String[] FILE_TYPE = {"file_type", "type", "format"};

    /** 体积/长度 */
    public static final String[] SIZE = {"size", "file_size", "bytes", "bytes_written",
            "size_bytes", "sizeBytes", "apk_size_mb", "model_size_mb", "content_length", "length"};

    /** 提示消息（也可能是失败原因，但默认按"消息"理解，见类注释规则 2） */
    public static final String[] MESSAGE = {"message", "msg", "info"};

    /** 错误原文 */
    public static final String[] ERROR = {"error", "err", "exception"};

    /**
     * 正文摘要：只收键名就是"摘要/正文"的键。
     *
     * <p>{@code result} <b>不放进来</b>：它在多数结果里是整个执行载荷（ {@code ui_component}
     * 返回的 {@code result:"executed"}、动态工具返回的对象），收进来会把载荷当摘要整段渲出来。
     * 迁移前只有 {@code smart_research} 一处把它当正文，用 {@link #SUMMARY_OR_RESULT}。</p>
     */
    public static final String[] SUMMARY = {"summary", "conclusion", "analysis_summary", "report",
            "overview", "abstract", "description", "answer"};

    /** 智能研究的老清单：摘要系列键加 {@code result}（那里的 result 就是报告正文） */
    public static final String[] SUMMARY_OR_RESULT = union(SUMMARY, new String[]{"result"});

    /** 通用总数（仅此一个通用概念；行数/词数/页数等各自成表，见类注释规则 3） */
    public static final String[] COUNT_TOTAL = {"count", "total", "totalQuestions", "successCount"};

    /** 状态词（值域见 {@link ToolStatusVocabulary}） */
    public static final String[] STATUS = {"status"};

    private ToolResultFields() {
    }

    /**
     * 按顺序拼多个概念并去重，用于"这个读取处确实想先试 error、再退到 message"的组合。
     *
     * <p>保留这个显式组合而不是偷偷把 message 塞进 ERROR，是为了让每一处的降级链在代码里
     * 看得见 —— 读取处之间的差异应当是有意选择的，不是表里混着的。
     */
    public static String[] union(String[]... groups) {
        Set<String> ordered = new LinkedHashSet<>();
        if (groups != null) {
            for (String[] group : groups) {
                if (group == null) {
                    continue;
                }
                for (String key : group) {
                    if (key != null && !key.isEmpty()) {
                        ordered.add(key);
                    }
                }
            }
        }
        return ordered.toArray(new String[0]);
    }
}
