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
 * <p>一个键可以属于两个概念（{@code target} 既可能是产物路径也可能是链接地址），
 * 这不算冲突：调用处知道自己是在渲染文件还是渲染链接，选一个概念问就是了。
 */
public final class ToolResultFields {

    /** 文件路径（含显示名兜底：{@code name} 放最后，只在没有任何路径键时才当路径用） */
    public static final String[] PATH = {
            "file_path", "filePath", "filepath", "path", "output_path", "source_path",
            "target_path", "save_path", "local_path", "dir_path", "directory_path",
            "apk_path", "image_path", "audio_path", "pdf_path", "html_path", "json_path",
            "icon_path", "model_path", "file", "output", "target",
            "file_name", "filename", "name"
    };

    /**
     * 链接地址。
     *
     * <p>{@code target} 在键表里同时出现在 {@link #PATH} 和本表，是有意的：有的工具用它表示
     * 写入目标文件、有的表示跳转地址，由调用处按语义挑一张表问。
     *
     * <p>{@code source} <b>不放进来</b>：它在网页提取里是"来源站点名"而不是链接，
     * 收进来会把站点名当 URL 渲染。历史上也没有任何 URL 读取处用过它（别名表只收观测到的键）。
     */
    public static final String[] URL = {"url", "link", "linkUrl", "href", "target"};

    /** 产物引用：文件或 URL 都算（动态工具结果收集、卡片归类用） */
    public static final String[] ARTIFACT = {"file", "file_path", "path", "output", "url", "link"};

    /** 文件类型 */
    public static final String[] FILE_TYPE = {"file_type", "type", "format"};

    /** 体积/长度 */
    public static final String[] SIZE = {"size", "file_size", "bytes", "bytes_written",
            "size_bytes", "sizeBytes", "apk_size_mb", "model_size_mb", "content_length", "length"};

    /** 提示消息（也可能是失败原因，但默认按"消息"理解，见类注释规则 2） */
    public static final String[] MESSAGE = {"message", "msg", "info"};

    /** 错误原文 */
    public static final String[] ERROR = {"error", "err", "exception"};

    /** 正文摘要 */
    public static final String[] SUMMARY = {"summary", "conclusion", "analysis_summary", "report",
            "overview", "abstract", "description", "answer", "result"};

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
