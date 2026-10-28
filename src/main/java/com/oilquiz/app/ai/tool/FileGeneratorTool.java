package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tool(
    value = "file_generator",
    description = "文件生成/修改工具：新建、覆盖写入、追加、精确改一处、JSON/配置/Markdown/报告、复制、删除。未指定绝对路径时默认保存到 Agent 工作区（用 workspace 工具查看/读取，返回的 filePath 是完整路径）。**写文件和改文件都优先用本工具，不要为这件事去跑 Python**。**文件名规范：一律用英文/数字/下划线（如 daily_report_0911.md），不要用中文文件名**（中文名在跨进程/链接打开环节易被截断丢失）；给用户文件链接时用返回的完整绝对路径或 files/相对路径，不要缩写/丢字（用户实测点开失败多因此）。",
    category = "file",
    actions = {
        @Action(name = "create", description = "创建文件"),
        @Action(name = "write", description = "覆盖写入整个文件（文件不存在则新建，存在则整篇替换）"),
        @Action(name = "append", description = "追加内容到文件"),
        @Action(name = "edit", description = "精确改一处：old_text 逐字符唯一匹配，命中 0 或多处报错；replace_all=true 才全换；返回 unified diff"),
        @Action(name = "diff", description = "预览差异（不写盘）：与 content 或 file_path_b 比较，返回 unified diff 并出预览卡"),
        @Action(name = "write_json", description = "写入JSON文件"),
        @Action(name = "create_config", description = "创建配置文件"),
        @Action(name = "create_markdown", description = "创建Markdown文件"),
        @Action(name = "create_template", description = "创建模板文件"),
        @Action(name = "generate_report", description = "生成报告")
    },
    params = {
        @Param(name = "file_name", type = "string", description = "文件名", required = true),
        @Param(name = "content", type = "string", description = "文件内容", required = false),
        @Param(name = "format", type = "string", description = "文件格式", required = false),
        @Param(name = "action", type = "string", description = "操作类型", required = true),
        @Param(name = "old_text", type = "string", description = "被替换的原文(edit用，必须逐字符一致)", required = false),
        @Param(name = "new_text", type = "string", description = "替换为(edit用，可为空=删除)", required = false),
        @Param(name = "replace_all", type = "boolean", description = "edit 用：默认只改唯一匹配处，true 才替换全部", required = false),
        @Param(name = "file_path_b", type = "string", description = "diff 用：与另一个文件比较（与 content 二选一）", required = false)
    }
)
public class FileGeneratorTool implements AITool {
    private static final String TAG = "FileGeneratorTool";
    private final Context context;
    
    public FileGeneratorTool(Context context) {
        this.context = context;
    }
    
    @Override
    public String getName() {
        return "file_generator";
    }
    
    @Override
    public String getDescription() {
        return "文件生成/修改工具：新建、覆盖写入、追加、精确改一处、JSON/配置/Markdown/报告、复制、删除。未指定绝对路径时默认保存到 Agent 工作区（用 workspace 工具查看/读取，返回的 filePath 是完整路径）。**写文件和改文件都优先用本工具，不要为这件事去跑 Python**。**文件名规范：一律用英文/数字/下划线（如 daily_report_0911.md），不要用中文文件名**（中文名在跨进程/链接打开环节易被截断丢失）；给用户文件链接时用返回的完整绝对路径或 files/相对路径，不要缩写/丢字（用户实测点开失败多因此）。";
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            normalizeParameters(parameters);
            String action = (String) parameters.get("action");
            if (action == null) {
                action = "create";
            }
            
            switch (action) {
                case "create":
                    return createFile(parameters);
                case "write":
                    // 覆盖写入：语义与 create 相同（截断后整篇写），只是结果文案区分"新建"和"覆盖"
                    return createFile(parameters, true);
                case "edit":
                    return editFile(parameters);
                case "diff":
                    return previewDiff(parameters);
                case "append":
                    return appendToFile(parameters);
                case "write_json":
                case "json":
                    return writeJson(parameters);
                case "create_config":
                case "config":
                    return createConfig(parameters);
                case "create_markdown":
                case "markdown":
                    return createMarkdown(parameters);
                case "create_template":
                case "template":
                    return createTemplate(parameters);
                case "generate_report":
                case "report":
                    return generateReport(parameters);
                case "delete_file":
                case "delete":
                    return deleteFile(parameters);
                case "copy_file":
                case "copy":
                    return copyFile(parameters);
                default:
                    return createFile(parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "Error executing file generator: " + e.getMessage(), e);
            return new AIToolResult("文件生成失败: " + e.getMessage(), parameters);
        }
    }

    /**
     * 规范化参数：工具Schema声明的参数名为 file_name，但内部实现读取 file_path。
     * 这里将 file_name 映射到 file_path，保证LLM按Schema传参时工具能正常工作。
     * 未指定绝对路径时文件统一保存到"当前"Agent 工作区长期文件区（AgentWorkspace.getFilesDir()，
     * 公共↔私有随存储授权切换；无需额外权限，可用 workspace 工具查看/读取）。
     */
    private void normalizeParameters(Map<String, Object> parameters) {
        if (parameters == null) return;
        if (!parameters.containsKey("file_path") && parameters.containsKey("file_name")) {
            Object fileName = parameters.get("file_name");
            parameters.put("file_path", fileName);
        }
        if (!parameters.containsKey("file_name") && parameters.containsKey("file_path")) {
            parameters.put("file_name", parameters.get("file_path"));
        }
        // 文件默认保存到"当前"Agent 工作区（AgentWorkspace.getFilesDir()，未授权时为私有目录，
        // 授权后为公共目录；管理页统一可见）；
        // Agent 明确指定绝对路径（/ 开头或 file://）时保留原路径
        Object fpObj = parameters.get("file_path");
        if (fpObj instanceof String) {
            String fp = ((String) fpObj).trim();
            // File 判断绝对路径：Android 上是 "/…"，桌面单测可能是 "C:\…"；
            // 只按 startsWith("/") 会把 Windows 绝对路径误当相对名塞进工作区。
            boolean isAbsolute = new File(fp).isAbsolute() || fp.startsWith("file://");
            if (!isAbsolute) {
                // 相对路径/纯文件名 → 工作区
                String fileNameOnly = fp;
                int lastSlash = fp.lastIndexOf('/');
                if (lastSlash >= 0 && lastSlash < fp.length() - 1) {
                    fileNameOnly = fp.substring(lastSlash + 1);
                }
                if (fileNameOnly == null || fileNameOnly.trim().isEmpty()) {
                    fileNameOnly = "generated_file.txt";
                }
                // 用户保留的产物 → 长期文件区（files/）
                File wsDir = com.oilquiz.app.ai.agent.online.AgentWorkspace
                        .getInstance(context).getFilesDir();
                parameters.put("file_path", new File(wsDir, fileNameOnly).getAbsolutePath());
            }
        }
    }

    /** 保留的历史版本上限（维度六 P2-1 文件版本管理） */
    private static final int MAX_FILE_VERSIONS = 5;

    /**
     * 覆盖写前备份历史版本：当前文件 → 同名.ver1，旧版本依次轮转（.ver1→.ver2…），
     * 超出 MAX_FILE_VERSIONS 的最旧版本删除。仅备份非空已存在文件，失败不阻断主流程。
     */
    private void backupVersion(File file) {
        try {
            if (file == null || !file.exists() || file.length() == 0) return;
            File dir = file.getParentFile();
            String base = file.getName();
            // 1) 轮转旧版本（从旧到新，先腾出最末位）
            for (int v = MAX_FILE_VERSIONS - 1; v >= 1; v--) {
                File oldV = new File(dir, base + ".ver" + v);
                File newV = new File(dir, base + ".ver" + (v + 1));
                if (newV.exists() && !newV.delete()) {
                    AILogger.w(TAG, "删除旧版本失败: " + newV.getName());
                }
                if (oldV.exists() && !oldV.renameTo(newV)) {
                    AILogger.w(TAG, "轮转版本失败: " + oldV.getName());
                }
            }
            // 2) 当前文件 → ver1
            File ver1 = new File(dir, base + ".ver1");
            java.nio.file.Files.copy(file.toPath(), ver1.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            AILogger.i(TAG, "已备份文件版本: " + base + " → " + base + ".ver1");
        } catch (Exception e) {
            AILogger.w(TAG, "备份文件版本失败: " + (file != null ? file.getName() : "?") + " - " + e.getMessage());
        }
    }

    private AIToolResult createFile(Map<String, Object> parameters) {
        return createFile(parameters, false);
    }

    /**
     * @param overwrite true 表示 {@code action=write}（整篇覆盖），只影响结果文案与结果字段，
     *                  落盘行为与 create 相同：截断后写、覆盖前备份 .verN、出 diff 卡。
     */
    private AIToolResult createFile(Map<String, Object> parameters, boolean overwrite) {
        String filePath = (String) parameters.get("file_path");
        String content = (String) parameters.get("content");
        String encoding = (String) parameters.get("encoding");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        if (content == null) {
            content = "";
        }
        
        if (encoding == null) {
            encoding = "UTF-8";
        }
        
        try {
            File file = new File(filePath);
            
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // diff 卡生产者（2026-10-24）：覆盖写前留一份旧文本，写成功后由 UI 桥接层出变更对比卡。
            // 新建文件时 snapshot 返回 null，不会挂"全是新增"的噪音卡。
            String diffBefore = FileChangeDiff.snapshot(context, filePath);

            // 维度六 P2-1 文件版本管理：覆盖前备份历史版本（保留最近 5 版，避免覆盖丢失）
            backupVersion(file);
            
            // encoding 参数要真的生效（2026-10-28）：此前无论传什么都写 UTF-8，
            // 从 python_file_ops 迁过来的写 GB18030 场景会静默变成乱码。
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), charsetOrDefault(encoding))) {
                writer.write(content);
            }
            
            // 通过 FileProvider 生成 content:// URI，用户可直接点击打开/分享
            String contentUri = generateContentUri(file);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", overwrite ? "文件已覆盖写入" : "文件创建成功");
            result.put("action", overwrite ? "write" : "create");
            result.put("filePath", filePath);
            result.put("size", file.length());
            if (contentUri != null) {
                result.put("contentUri", contentUri);
            }
            // 为所有文件类型生成可点击链接提示
            if (contentUri != null) {
                String fileName = file.getName();
                // 无扩展名文件容错（lastIndexOf 返回 -1 时 substring 会崩溃）
                int dotIndex = fileName.lastIndexOf('.');
                String ext = dotIndex > 0 ? fileName.substring(dotIndex).toLowerCase() : "";
                String desc;
                switch (ext) {
                    case ".html": case ".htm":
                        desc = "查看网页"; break;
                    case ".txt": case ".log": case ".md": case ".csv":
                        desc = "查看文件"; break;
                    case ".json": case ".xml": case ".yaml": case ".yml": case ".properties": case ".ini": case ".conf":
                        desc = "查看配置文件"; break;
                    case ".pdf":
                        desc = "查看PDF文档"; break;
                    case ".jpg": case ".jpeg": case ".png": case ".gif": case ".bmp": case ".webp":
                        desc = "查看图片"; break;
                    case ".mp3": case ".wav": case ".ogg": case ".m4a": case ".flac":
                        desc = "播放音频"; break;
                    case ".mp4": case ".avi": case ".mkv": case ".mov": case ".wmv":
                        desc = "播放视频"; break;
                    case ".xlsx": case ".xls":
                        desc = "查看Excel文件"; break;
                    case ".doc": case ".docx":
                        desc = "查看Word文档"; break;
                    case ".zip": case ".rar": case ".7z": case ".tar": case ".gz":
                        desc = "查看压缩包"; break;
                    case ".apk":
                        desc = "安装应用"; break;
                    default:
                        desc = "打开文件"; break;
                }
                result.put("openHint", "用户可通过以下链接" + desc + ": [" + fileName + "](" + contentUri + ")");
            }
            
            Map<String, Object> info = new HashMap<>(parameters);
            AIToolResult out = new AIToolResult(result, info);
            FileChangeDiff.attach(context, out, filePath, diffBefore,
                    overwrite ? "已覆盖写入 · " + file.getName() : null);
            return out;
        } catch (Exception e) {
            return new AIToolResult("创建文件失败: " + e.getMessage(), parameters);
        }
    }

    /** 通过 FileProvider 生成文件的 content:// URI */
    private String generateContentUri(File file) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "com.oilquiz.app.fileprovider", file);
            return uri.toString();
        } catch (Exception e) {
            AILogger.w(TAG, "Failed to generate content URI: " + e.getMessage());
            return null;
        }
    }
    
    private AIToolResult appendToFile(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String content = (String) parameters.get("content");
        Boolean newLine = (Boolean) parameters.get("newLine");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        if (content == null) {
            content = "";
        }
        
        if (newLine == null) {
            newLine = true;
        }
        
        try {
            File file = new File(filePath);
            
            if (!file.exists()) {
                return new AIToolResult("文件不存在: " + filePath, parameters);
            }
            
            // diff 卡生产者：追加也会改变文件内容，写前快照旧文本
            String diffBefore = FileChangeDiff.snapshot(context, filePath);
            String appendContent = newLine ? "\n" + content : content;
            
            // 追加要跟上原文件的编码（2026-10-28）：此前恒写 UTF-8，
            // 往 GB18030 文件后面追中文会变成一段乱码。
            String requestedEncoding = (String) parameters.get("encoding");
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file, true),
                    charsetOrDefault(requestedEncoding != null
                            ? requestedEncoding : detectCharsetOf(file).name()))) {
                writer.write(appendContent);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "内容追加成功");
            result.put("filePath", filePath);
            result.put("newSize", file.length());
            
            Map<String, Object> info = new HashMap<>(parameters);
            AIToolResult out = new AIToolResult(result, info);
            FileChangeDiff.attach(context, out, filePath, diffBefore,
                    "已追加 · " + file.getName());
            return out;
        } catch (Exception e) {
            return new AIToolResult("追加内容失败: " + e.getMessage(), parameters);
        }
    }
    
    /** edit/snapshot 结果里 diff 文本的上限（超长只截断展示，落盘内容不受影响） */
    private static final int MAX_EDIT_DIFF_CHARS = 6000;

    /**
     * 精确改一处（2026-10-28 从 python_file_ops 搬到本工具：写文件不该为了"改一行"去起 Python 解释器）。
     *
     * <p>语义与业界口径一致：{@code old_text} 逐字符唯一匹配才改，命中 0 处或多处直接报错、
     * 绝不"找一处凑合改"；{@code replace_all=true} 才替换全部匹配。改完把 unified diff
     * 打回给模型核对，并照常挂 diff 卡。</p>
     */
    private AIToolResult editFile(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        Object oldObj = parameters.get("old_text");
        String oldText = oldObj == null ? null : String.valueOf(oldObj);
        Object newObj = parameters.get("new_text");
        String newText = newObj == null ? "" : String.valueOf(newObj);
        boolean replaceAll = Boolean.TRUE.equals(parameters.get("replace_all"))
                || "true".equalsIgnoreCase(String.valueOf(parameters.get("replace_all")));
        String encoding = (String) parameters.get("encoding");

        if (filePath == null || filePath.trim().isEmpty()) {
            return new AIToolResult("缺少参数: file_name（要修改哪个文件）", parameters);
        }
        if (oldText == null || oldText.isEmpty()) {
            return new AIToolResult("缺少参数: old_text（被替换的原文，必须逐字符一致，含缩进/空格/换行）", parameters);
        }

        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath + "（新建请用 action=create / write）", parameters);
        }

        try {
            Decoded decoded = readDecoded(file, encoding);
            EditOutcome outcome = applyEdit(decoded.text, oldText, newText, replaceAll);
            if (outcome.error != null) {
                return new AIToolResult("[edit 失败] " + outcome.error, parameters);
            }

            // diff 卡生产者：改前留一份旧文本（与 create/append 同一套）
            String diffBefore = FileChangeDiff.snapshot(context, filePath);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(outcome.text.getBytes(decoded.charset));
            }

            String diff = UnifiedDiffText.render(decoded.text, outcome.text, 3);
            if (diff.length() > MAX_EDIT_DIFF_CHARS) {
                diff = diff.substring(0, MAX_EDIT_DIFF_CHARS) + "\n...(diff 过长已截断)";
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("action", "edit");
            result.put("message", "已编辑: " + filePath + "（替换 " + outcome.replaced
                    + " 处，共 " + outcome.count + " 处匹配）");
            result.put("filePath", filePath);
            result.put("size", file.length());
            result.put("matched", outcome.count);
            result.put("replaced", outcome.replaced);
            result.put("unifiedDiff", diff.isEmpty() ? "(内容无变化)" : diff);
            String contentUri = generateContentUri(file);
            if (contentUri != null) {
                result.put("contentUri", contentUri);
            }

            Map<String, Object> info = new HashMap<>(parameters);
            AIToolResult out = new AIToolResult(result, info);
            FileChangeDiff.attach(context, out, filePath, diffBefore,
                    "已修改 · " + file.getName() + "（替换 " + outcome.replaced + " 处）");
            return out;
        } catch (Exception e) {
            return new AIToolResult("编辑文件失败: " + e.getMessage(), parameters);
        }
    }

    /**
     * 预览差异（不写盘）：{@code file_path} 与 {@code content}（提议的新文本）比，
     * 或与 {@code file_path_b}（另一个文件）比。结果带 unifiedDiff，并挂一张标题为
     * 「变更预览」的 diff 卡——用户能先看见要改什么，再决定让不让改。
     */
    private AIToolResult previewDiff(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        Object contentObj = parameters.get("content");
        String pathB = (String) parameters.get("file_path_b");
        String encoding = (String) parameters.get("encoding");

        if (filePath == null || filePath.trim().isEmpty()) {
            return new AIToolResult("缺少参数: file_name（拿哪个文件做对比）", parameters);
        }
        File file = new File(filePath);
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath + "（新建请用 action=create / write）", parameters);
        }

        try {
            Decoded current = readDecoded(file, encoding);
            String proposed;
            String targetLabel;
            if (pathB != null && !pathB.trim().isEmpty()) {
                File other = new File(pathB);
                if (!other.exists()) {
                    return new AIToolResult("对比文件不存在: " + pathB, parameters);
                }
                proposed = readDecoded(other, encoding).text;
                targetLabel = other.getName();
            } else if (contentObj != null) {
                proposed = String.valueOf(contentObj);
                targetLabel = "(提议内容)";
            } else {
                return new AIToolResult("diff 需要 content（提议的新文本）或 file_path_b（另一个文件）", parameters);
            }

            boolean changed = !current.text.equals(proposed);
            String diff = UnifiedDiffText.render(current.text, proposed, 3);
            if (diff.length() > MAX_EDIT_DIFF_CHARS) {
                diff = diff.substring(0, MAX_EDIT_DIFF_CHARS) + "\n...(diff 过长已截断)";
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("action", "diff");
            result.put("path", filePath);
            result.put("comparedWith", targetLabel);
            result.put("changed", changed);
            result.put("message", changed
                    ? "预览完成：与" + targetLabel + " 存在差异（未写入任何文件）"
                    : "两者无差异（未写入任何文件）");
            result.put("unifiedDiff", diff.isEmpty() ? "(两者无差异)" : diff);

            Map<String, Object> info = new HashMap<>(parameters);
            AIToolResult out = new AIToolResult(result, info);
            FileChangeDiff.attachPreview(out, "变更预览 · " + file.getName(), filePath,
                    current.text, proposed);
            return out;
        } catch (Exception e) {
            return new AIToolResult("预览差异失败: " + e.getMessage(), parameters);
        }
    }

    /**
     * edit 的纯逻辑部分（不碰文件系统，便于单测）：数匹配、按 replace_all 决定替换几处、
     * 给出可执行的报错文案。
     */
    static EditOutcome applyEdit(String text, String oldText, String newText, boolean replaceAll) {
        if (text == null) text = "";
        if (oldText == null || oldText.isEmpty()) {
            return EditOutcome.error("old_text 不能为空");
        }
        if (newText == null) newText = "";
        int count = countOccurrences(text, oldText);
        if (count == 0) {
            return EditOutcome.error("未找到精确匹配。old_text 必须逐字符一致（含缩进/空格/换行）；"
                    + "先用 file_reader 或 workspace(action=read) 核对原文再改。");
        }
        if (count > 1 && !replaceAll) {
            return EditOutcome.error("old_text 匹配 " + count + " 处、不唯一。请扩大 old_text 上下文到唯一，"
                    + "或传 replace_all=true 替换全部。");
        }
        int limit = replaceAll ? count : 1;
        StringBuilder sb = new StringBuilder(text.length() + newText.length());
        int from = 0;
        int replaced = 0;
        int idx;
        while (replaced < limit && (idx = text.indexOf(oldText, from)) >= 0) {
            sb.append(text, from, idx).append(newText);
            from = idx + oldText.length();
            replaced++;
        }
        sb.append(text, from, text.length());
        return EditOutcome.ok(count, replaced, sb.toString());
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        int from = 0;
        int idx;
        while ((idx = text.indexOf(needle, from)) >= 0) {
            n++;
            from = idx + needle.length();
        }
        return n;
    }

    /** edit 的结果载体：要么带 error，要么带替换后的全文与计数 */
    static final class EditOutcome {
        final int count;
        final int replaced;
        final String text;
        final String error;

        private EditOutcome(int count, int replaced, String text, String error) {
            this.count = count;
            this.replaced = replaced;
            this.text = text;
            this.error = error;
        }

        static EditOutcome ok(int count, int replaced, String text) {
            return new EditOutcome(count, replaced, text, null);
        }

        static EditOutcome error(String message) {
            return new EditOutcome(0, 0, null, message);
        }
    }

    /** 读文本用：解出来的编码要带回写回时用，避免"读成 UTF-8、写回 GB18030"式的整篇乱码 */
    static final class Decoded {
        final String text;
        final java.nio.charset.Charset charset;

        Decoded(String text, java.nio.charset.Charset charset) {
            this.text = text;
            this.charset = charset;
        }
    }

    /**
     * 严格解码：按 指定编码 → UTF-8 → GB18030 依次试，第一个能完整解码的编码胜出。
     * 全失败时按 UTF-8 替换坏字节兜底（至少能改，不让工具在这里彻底失败）。
     */
    static Decoded readDecoded(File file, String requestedEncoding) throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        java.util.List<java.nio.charset.Charset> candidates = new ArrayList<>();
        if (requestedEncoding != null && !requestedEncoding.trim().isEmpty()) {
            try {
                candidates.add(java.nio.charset.Charset.forName(requestedEncoding.trim()));
            } catch (Exception ignored) {
                // 认不出的编码名就退回自动检测
            }
        }
        java.nio.charset.Charset utf8 = StandardCharsets.UTF_8;
        java.nio.charset.Charset gb18030 = java.nio.charset.Charset.forName("GB18030");
        if (!candidates.contains(utf8)) candidates.add(utf8);
        if (!candidates.contains(gb18030)) candidates.add(gb18030);

        for (java.nio.charset.Charset cs : candidates) {
            java.nio.charset.CharsetDecoder decoder = cs.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            try {
                return new Decoded(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString(), cs);
            } catch (Exception ignored) {
                // 试下一个编码
            }
        }
        return new Decoded(new String(bytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /** 参数里的编码名 → Charset；认不出就 UTF-8（写文件不能因为编码名拼错就整个失败） */
    static java.nio.charset.Charset charsetOrDefault(String encoding) {
        if (encoding == null || encoding.trim().isEmpty()) return StandardCharsets.UTF_8;
        try {
            return java.nio.charset.Charset.forName(encoding.trim());
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** 已有文件是什么编码（严格解码探测，探测失败按 UTF-8），追加时用来跟上原文件 */
    static java.nio.charset.Charset detectCharsetOf(File file) {
        try {
            return readDecoded(file, null).charset;
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private AIToolResult writeJson(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        Map<String, Object> data = (Map<String, Object>) parameters.get("data");
        Boolean pretty = (Boolean) parameters.get("pretty");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        if (data == null) {
            return new AIToolResult("缺少参数: data", parameters);
        }
        
        if (pretty == null) {
            pretty = true;
        }
        
        try {
            String jsonContent = convertToJson(data, pretty);
            
            return createFile(Map.of(
                "file_path", filePath,
                "content", jsonContent,
                "encoding", "UTF-8"
            ));
        } catch (Exception e) {
            return new AIToolResult("写入JSON失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult createConfig(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        Map<String, String> config = (Map<String, String>) parameters.get("config");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        if (config == null) {
            config = new HashMap<>();
        }
        
        StringBuilder content = new StringBuilder();
        content.append("# Configuration File\n");
        content.append("# Generated at: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n\n");
        
        for (Map.Entry<String, String> entry : config.entrySet()) {
            content.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
        }
        
        return createFile(Map.of(
            "file_path", filePath,
            "content", content.toString(),
            "encoding", "UTF-8"
        ));
    }
    
    private AIToolResult createMarkdown(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String title = (String) parameters.get("title");
        String content = (String) parameters.get("content");
        List<String> sections = (List<String>) parameters.get("sections");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        StringBuilder mdContent = new StringBuilder();
        
        if (title != null) {
            mdContent.append("# ").append(title).append("\n\n");
        }
        
        mdContent.append("> Generated at: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n\n");
        
        if (sections != null && !sections.isEmpty()) {
            for (int i = 0; i < sections.size(); i++) {
                mdContent.append("## ").append(i + 1).append(". ").append(sections.get(i)).append("\n\n");
            }
        } else if (content != null) {
            mdContent.append(content).append("\n");
        }
        
        return createFile(Map.of(
            "file_path", filePath,
            "content", mdContent.toString(),
            "encoding", "UTF-8"
        ));
    }
    
    private AIToolResult createTemplate(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String templateType = (String) parameters.get("type");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        if (templateType == null) {
            templateType = "default";
        }
        
        String content = generateTemplate(templateType);
        
        return createFile(Map.of(
            "file_path", filePath,
            "content", content,
            "encoding", "UTF-8"
        ));
    }
    
    private AIToolResult generateReport(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        String title = (String) parameters.get("title");
        Map<String, Object> data = (Map<String, Object>) parameters.get("data");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        StringBuilder report = new StringBuilder();
        
        report.append("# ").append(title != null ? title : "报告").append("\n\n");
        report.append("**生成时间**: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n\n");
        
        if (data != null) {
            report.append("## 内容摘要\n\n");
            
            for (Map.Entry<String, Object> entry : data.entrySet()) {
                report.append("### ").append(entry.getKey()).append("\n\n");
                
                Object value = entry.getValue();
                if (value instanceof List) {
                    List<?> list = (List<?>) value;
                    for (int i = 0; i < list.size(); i++) {
                        report.append(i + 1).append(". ").append(list.get(i)).append("\n");
                    }
                } else {
                    report.append(value.toString()).append("\n");
                }
                report.append("\n");
            }
        }
        
        report.append("---\n");
        report.append("*报告结束*\n");
        
        return createFile(Map.of(
            "file_path", filePath,
            "content", report.toString(),
            "encoding", "UTF-8"
        ));
    }
    
    private AIToolResult deleteFile(Map<String, Object> parameters) {
        String filePath = (String) parameters.get("file_path");
        
        if (filePath == null) {
            return new AIToolResult("缺少参数: file_path", parameters);
        }
        
        File file = new File(filePath);
        
        if (!file.exists()) {
            return new AIToolResult("文件不存在: " + filePath, parameters);
        }
        
        try {
            boolean deleted = file.delete();
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", deleted ? "success" : "failed");
            result.put("message", deleted ? "文件删除成功" : "文件删除失败");
            result.put("filePath", filePath);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("删除文件失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult copyFile(Map<String, Object> parameters) {
        String sourcePath = (String) parameters.get("source");
        String destinationPath = (String) parameters.get("destination");
        
        if (sourcePath == null) {
            return new AIToolResult("缺少参数: source", parameters);
        }
        
        if (destinationPath == null) {
            return new AIToolResult("缺少参数: destination", parameters);
        }
        
        File source = new File(sourcePath);
        File destination = new File(destinationPath);
        
        if (!source.exists()) {
            return new AIToolResult("源文件不存在: " + sourcePath, parameters);
        }
        
        try {
            File parentDir = destination.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            
            java.nio.file.Files.copy(source.toPath(), destination.toPath(), 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "文件复制成功");
            result.put("source", sourcePath);
            result.put("destination", destinationPath);
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("复制文件失败: " + e.getMessage(), parameters);
        }
    }
    
    private String convertToJson(Map<String, Object> data, boolean pretty) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        
        if (pretty) {
            json.append("\n");
        }
        
        int index = 0;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (index > 0) {
                json.append(",");
            }
            
            if (pretty) {
                json.append("\n  ");
            }
            
            json.append("\"").append(entry.getKey()).append("\": ");
            
            Object value = entry.getValue();
            if (value instanceof String) {
                json.append("\"").append(escapeString((String) value)).append("\"");
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value.toString());
            } else if (value instanceof List) {
                json.append(convertListToJson((List<?>) value, pretty));
            } else if (value instanceof Map) {
                json.append(convertToJson((Map<String, Object>) value, pretty));
            } else {
                json.append("null");
            }
            
            index++;
        }
        
        if (pretty) {
            json.append("\n");
        }
        
        json.append("}");
        return json.toString();
    }
    
    private String convertListToJson(List<?> list, boolean pretty) {
        if (list == null) {
            return "[]";
        }
        StringBuilder json = new StringBuilder();
        json.append("[");
        
        if (pretty) {
            json.append("\n");
        }
        
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                json.append(",");
            }
            
            if (pretty) {
                json.append("\n  ");
            }
            
            Object value = list.get(i);
            if (value instanceof String) {
                json.append("\"").append(escapeString((String) value)).append("\"");
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value.toString());
            } else if (value == null) {
                json.append("null");
            } else {
                json.append("\"").append(escapeString(value.toString())).append("\"");
            }
        }
        
        if (pretty) {
            json.append("\n");
        }
        
        json.append("]");
        return json.toString();
    }
    
    private String escapeString(String str) {
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    private String generateTemplate(String type) {
        switch (type.toLowerCase()) {
            case "java":
                return "public class Template {\n    public static void main(String[] args) {\n        System.out.println(\"Hello World\");\n    }\n}\n";
            case "python":
                return "#!/usr/bin/env python3\n\"\"\"Template script\"\"\"\n\ndef main():\n    print(\"Hello World\")\n\nif __name__ == \"__main__\":\n    main()\n";
            case "markdown":
                return "# Document Title\n\n## Section 1\n\nContent here...\n\n## Section 2\n\nMore content...\n";
            case "json":
                return "{\n  \"key\": \"value\",\n  \"number\": 123,\n  \"list\": [1, 2, 3]\n}\n";
            case "xml":
                return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<root>\n    <item>value</item>\n</root>\n";
            case "html":
                return "<!DOCTYPE html>\n<html>\n<head>\n    <title>Document</title>\n</head>\n<body>\n    <h1>Hello World</h1>\n</body>\n</html>\n";
            case "csv":
                return "Name,Age,Email\nJohn Doe,30,john@example.com\nJane Smith,25,jane@example.com\n";
            default:
                return "# Template File\n# Generated at: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + "\n\n";
        }
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: create, append, write_json, create_config, create_markdown, create_template, generate_report, delete_file, copy_file");
        descriptions.put("file_path", "文件路径（必填）");
        descriptions.put("content", "文件内容");
        descriptions.put("encoding", "文件编码（默认UTF-8）");
        descriptions.put("data", "JSON数据（用于write_json和generate_report操作）");
        descriptions.put("pretty", "是否格式化输出（用于write_json操作，默认true）");
        descriptions.put("config", "配置键值对（用于create_config操作）");
        descriptions.put("title", "标题（用于create_markdown和generate_report操作）");
        descriptions.put("sections", "章节列表（用于create_markdown操作）");
        descriptions.put("type", "模板类型（用于create_template操作：java, python, markdown, json, xml, html, csv）");
        descriptions.put("newLine", "追加时是否换行（用于append操作，默认true）");
        descriptions.put("source", "源文件路径（用于copy_file操作）");
        descriptions.put("destination", "目标文件路径（用于copy_file操作）");
        return descriptions;
    }
}