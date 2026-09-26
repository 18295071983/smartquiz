package com.oilquiz.app.ai.agent.online;

import android.content.Context;

import com.oilquiz.app.util.AILogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 工作区管理 —— 统一 Agent 产生的文件目录（按业界标准：工作区 = 临时执行空间）。
 *
 * 职责划分（对齐 Claude Code / Codex 实践）：
 * - 工作区根目录 = 临时执行空间（scratchpad）：执行中间文件/缓存/进度/长对话摘要
 *   → 任务结束自动清理，不长期保留
 * - files/ 子目录 = 长期文件区：用户明确要求保留的产物（报告/图片/导出）
 *   → 不自动清理，管理页可见
 * - 长期记忆（用户偏好/事实）→ 独立记忆系统（AgentMemoryStore），不占工作区
 *
 * 目录位置（公共目录优先，防"私有目录找不到文件"）：
 * - 已授予"所有文件访问"(MANAGE_EXTERNAL_STORAGE, Android 11+) →
 *   公共目录 Download/OilQuiz/agent_workspace/（文件对用户/文件管理器/其他 App 可见，
 *   可直接分享/备份，无需复制）
 * - 未授权 → 回退应用私有目录 files/agent_workspace/（始终可用，分享时自动复制到公共目录）
 *
 * 目录结构：
 *   agent_workspace/
 *     tmp/    ← 临时执行缓存（自动清理）
 *     files/  ← 长期文件（保留）
 */
public class AgentWorkspace {

    private static final String TAG = "AgentWorkspace";
    private static final String WORKSPACE_DIR = "agent_workspace";
    private static final String TMP_DIR = "tmp";
    private static final String FILES_DIR = "files";
    /** 公共目录根：Download/OilQuiz（SDK 29+ 用户可见） */
    private static final String PUBLIC_ROOT = "OilQuiz";
    /** 内置指导文档（assets/apk_shell/guides/，删除后工作区重建时自动恢复） */
    private static final String[] BUILTIN_GUIDE_ASSETS = {
            "apk_shell/guides/HTML_DESIGN_RULES.md",
            "apk_shell/guides/APK_SOURCE_GUIDE.md",
            // 2026-09-25：抖音下载内置工具 v3.2 文档（官方内核+UIFID自愈+双通道），工作区重建自动恢复
            "apk_shell/guides/douyin_downloader_GUIDE.md",
            "apk_shell/guides/LINUX_TOOLKIT_GUIDE.md",
            // 2026-09-26：本地媒体工具箱（media_toolkit）文档，工作区重建自动恢复
            "apk_shell/guides/MEDIA_TOOLKIT_GUIDE.md",
            // 2026-09-26：第三方组件与许可声明（含内置 ffmpeg 的 LGPL v3 声明）
            // —— LGPL 要求分发时随附许可/源码信息，所以它必须随 APK 走，不能只放仓库
            "apk_shell/guides/THIRD_PARTY_NOTICES.md"
    };

    private final Context appContext;
    private final File workspaceDir;
    private final File tmpDir;
    private final File filesDir;
    /** 是否使用公共目录（true=Download/OilQuiz；false=私有目录回退） */
    private final boolean publicWorkspace;

    private static volatile AgentWorkspace instance;

    public static AgentWorkspace getInstance(Context context) {
        if (instance == null) {
            synchronized (AgentWorkspace.class) {
                if (instance == null) {
                    instance = new AgentWorkspace(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    /** 重建实例（权限变化后调用：私有目录 ↔ 公共目录切换） */
    public static void rebuildInstance(Context context) {
        synchronized (AgentWorkspace.class) {
            instance = new AgentWorkspace(context.getApplicationContext());
        }
    }

    private AgentWorkspace(Context context) {
        this.appContext = context.getApplicationContext();
        // 公共目录：Download/OilQuiz/agent_workspace（需"所有文件访问"权限，Android 11+）
        File publicWs = null;
        boolean hasPermission = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
                || android.os.Environment.isExternalStorageManager();
        if (hasPermission) {
            try {
                java.io.File downloadDir = android.os.Environment
                        .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                if (downloadDir != null) {
                    publicWs = new File(downloadDir, PUBLIC_ROOT + "/" + WORKSPACE_DIR);
                    if (!publicWs.exists()) publicWs.mkdirs();
                    if (!publicWs.isDirectory() || !publicWs.canWrite()) {
                        AILogger.w(TAG, "公共工作区不可写，回退私有目录: " + publicWs.getAbsolutePath());
                        publicWs = null;
                    }
                }
            } catch (Throwable t) {
                AILogger.w(TAG, "公共工作区检测失败，回退私有目录: " + t.getMessage());
                publicWs = null;
            }
        }
        if (publicWs != null) {
            this.workspaceDir = publicWs;
            this.publicWorkspace = true;
            AILogger.i(TAG, "Agent workspace (public): " + publicWs.getAbsolutePath());
        } else {
            this.workspaceDir = new File(context.getFilesDir(), WORKSPACE_DIR);
            this.publicWorkspace = false;
            AILogger.i(TAG, "Agent workspace (private fallback): " + workspaceDir.getAbsolutePath());
        }
        this.tmpDir = new File(workspaceDir, TMP_DIR);
        this.filesDir = new File(workspaceDir, FILES_DIR);
        ensureDirs();
        // 工作区长期文件区生成"创建工具指南"与"使用速查表"（纯静态内容，无外部依赖，幂等）
        ensureGuideFiles();
        // 内置 APK 壳指导文档（HTML 设计规则 + 壳源码指导）：删除后工作区重建时从 assets 恢复
        ensureBuiltinGuideAssets();
    }

    /** 从 assets 恢复内置指导文档到 files/（每次覆盖写；删除后应用重启/工作区重建即恢复） */
    private void ensureBuiltinGuideAssets() {
        for (String asset : BUILTIN_GUIDE_ASSETS) {
            try (java.io.InputStream in = appContext.getAssets().open(asset)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                String content = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                String name = asset.substring(asset.lastIndexOf('/') + 1);
                writeGuideFile(name, content);
                AILogger.i(TAG, "内置指导文档已就绪: files/" + name);
            } catch (Throwable t) {
                AILogger.w(TAG, "恢复内置指导文档失败: " + asset + " - " + t.getMessage());
            }
        }
    }

    private void ensureDirs() {
        if (!workspaceDir.exists()) workspaceDir.mkdirs();
        if (!tmpDir.exists()) tmpDir.mkdirs();
        if (!filesDir.exists()) filesDir.mkdirs();
    }

    /** 工作区是否位于公共目录（Download/OilQuiz） */
    public boolean isPublicWorkspace() {
        return publicWorkspace;
    }

    /** 公共目录权限是否已授予（Android 11+ 的 MANAGE_EXTERNAL_STORAGE；旧版本恒 true） */
    public static boolean hasPublicStoragePermission() {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
                || android.os.Environment.isExternalStorageManager();
    }

    /** 工作区根目录（不存在则创建） */
    public File getWorkspaceDir() {
        ensureDirs();
        return workspaceDir;
    }

    /** 工作区路径字符串 */
    public String getWorkspacePath() {
        return getWorkspaceDir().getAbsolutePath();
    }

    /** 临时缓存目录（执行中间文件/进度/长对话摘要，任务结束自动清理） */
    public File getTmpDir() {
        ensureDirs();
        return tmpDir;
    }

    /** 长期文件目录（用户保留的产物，不自动清理） */
    public File getFilesDir() {
        ensureDirs();
        return filesDir;
    }

    /** 临时缓存目录路径 */
    public String getTmpPath() {
        return getTmpDir().getAbsolutePath();
    }

    /** 长期文件目录路径 */
    public String getFilesPath() {
        return getFilesDir().getAbsolutePath();
    }

    /** 在指定文件名前拼接工作区路径（相对名拒绝 `..` 路径穿越；绝对路径原样放行由调用方把关） */
    public File resolveFile(String fileName) {
        return resolveSafely(getWorkspaceDir(), fileName);
    }

    /** 解析到长期文件区（用户保留文件） */
    public File resolveFileToFiles(String fileName) {
        return resolveSafely(getFilesDir(), fileName);
    }

    /** 解析到临时缓存区（执行中间文件） */
    public File resolveFileToTmp(String fileName) {
        return resolveSafely(getTmpDir(), fileName);
    }

    /** 安全拼接：拒绝相对路径中的 `..` 段（防 prompt 注入越界读写工作区外文件），返回 null 表示非法 */
    private File resolveSafely(File base, String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        File f = new File(fileName);
        if (f.isAbsolute()) return f;
        // 拒绝路径穿越：任何 .. 段都视为非法（Windows 反斜杠也归一处理）
        String normalized = fileName.replace('\\', '/');
        String[] segments = normalized.split("/");
        for (String seg : segments) {
            if ("..".equals(seg)) {
                AILogger.w(TAG, "拒绝路径穿越: " + fileName);
                return null;
            }
        }
        return new File(base, fileName);
    }

    /**
     * 解析路径为真实存在的文件（供 file_card/file_list 的打开/分享兜底）。
     * 模型传给 UI 组件的路径常不精确（相对名 "report.md"、缩写 "files/报告.pdf"、
     * 或幻觉的公共路径 "/storage/emulated/0/OilQuiz/xxx"），逐一兜底：
     * 1. content:// → 无法转 File，返回 null（调用方保留 URI 处理）
     * 2. file:// 前缀剥离
     * 3. 绝对路径存在 → 直接返回
     * 4. 相对路径 → 依次尝试 files/ → tmp/ → 工作区根
     * 5. 仍找不到 → 按文件名（basename）在 files/、tmp/、工作区根搜索
     */
    public File resolveExistingFile(String pathOrName) {
        if (pathOrName == null || pathOrName.trim().isEmpty()) return null;
        String p = pathOrName.trim();
        if (p.startsWith("content://")) return null;
        if (p.startsWith("file://")) {
            android.net.Uri u = android.net.Uri.parse(p);
            p = u != null ? u.getPath() : null;
            if (p == null || p.isEmpty()) return null;
        }
        ensureDirs();
        File direct = new File(p);
        if (direct.isAbsolute()) {
            if (direct.isFile()) return direct;
            // 绝对路径不存在（如幻觉的公共路径）→ 按文件名兜底搜索
            return searchByName(direct.getName());
        }
        // 相对路径：长期文件区 → 临时区 → 工作区根
        // 模型常给带前缀的相对路径（"files/xxx"、"tmp/xxx"）——filesDir 本身就是 files/ 子目录，
        // 直接拼接会变成 files/files/xxx 找不到；先剥前缀再尝试，避免"链接少了 files/ 前缀"类问题。
        String rel = p;
        if (rel.startsWith("files/")) {
            rel = rel.substring("files/".length());
        } else if (rel.startsWith("tmp/")) {
            rel = rel.substring("tmp/".length());
        }
        File inFiles = new File(filesDir, p);
        if (inFiles.isFile()) return inFiles;
        if (!rel.equals(p)) {
            File inFiles2 = new File(filesDir, rel);
            if (inFiles2.isFile()) return inFiles2;
        }
        File inTmp = new File(tmpDir, p);
        if (inTmp.isFile()) return inTmp;
        File inRoot = new File(workspaceDir, p);
        if (inRoot.isFile()) return inRoot;
        // 纯文件名兜底搜索（模型常只传文件名/去掉目录前缀）
        String name = p;
        int lastSlash = p.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < p.length() - 1) {
            name = p.substring(lastSlash + 1);
        }
        return searchByName(name);
    }

    /** 按文件名在工作区 files/、tmp/、根目录搜索（返回第一个匹配的文件） */
    private File searchByName(String name) {
        if (name == null || name.isEmpty()) return null;
        ensureDirs();
        for (File dir : new File[]{filesDir, tmpDir, workspaceDir}) {
            File[] list = dir.listFiles();
            if (list != null) {
                for (File f : list) {
                    if (f.isFile() && f.getName().equals(name)) return f;
                }
            }
        }
        return null;
    }

    /**
     * 模糊搜索工作区相似文件（供打开失败时给用户可操作提示）。
     * 模型生成的链接常丢字/缩写/漏下划线（如 "银川天气汇报画板0911.html" vs
     * 实际 "银川天气汇报画板_0911.html"）——按文件名包含关系匹配，返回最多 5 个候选。
     */
    public java.util.List<File> searchSimilarFiles(String nameOrPath) {
        java.util.List<File> result = new java.util.ArrayList<>();
        if (nameOrPath == null || nameOrPath.trim().isEmpty()) return result;
        String name = nameOrPath.trim();
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < name.length() - 1) {
            name = name.substring(lastSlash + 1);
        }
        String lower = name.toLowerCase();
        // 剥常见后缀差异：链接可能是目标名的子串（丢了 _/空格 等），双向包含匹配
        ensureDirs();
        for (File dir : new File[]{filesDir, tmpDir, workspaceDir}) {
            File[] list = dir.listFiles();
            if (list == null) continue;
            for (File f : list) {
                if (!f.isFile()) continue;
                String fn = f.getName();
                String fnLower = fn.toLowerCase();
                boolean hit = fnLower.contains(lower) || lower.contains(fnLower)
                        || similarChars(lower, fnLower);
                if (hit && !result.contains(f)) result.add(f);
            }
        }
        result.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        if (result.size() > 5) result = result.subList(0, 5);
        return result;
    }

    /** 宽松相似：去非字母数字字符后互为子串（容忍 _、空格、- 等差异） */
    private static boolean similarChars(String a, String b) {
        String cleanA = a.replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "");
        String cleanB = b.replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "");
        if (cleanA.isEmpty() || cleanB.isEmpty()) return false;
        return cleanA.contains(cleanB) || cleanB.contains(cleanA);
    }

    /** 列出工作区文件（按修改时间倒序；含 tmp/files 标记） */
    public List<WorkspaceFile> listFiles() {
        List<WorkspaceFile> result = new ArrayList<>();
        ensureDirs();
        // 长期文件区
        File[] files = filesDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    result.add(new WorkspaceFile(f.getName(), f.length(), f.lastModified(), "files"));
                }
            }
        }
        // 临时缓存区
        File[] tmps = tmpDir.listFiles();
        if (tmps != null) {
            for (File f : tmps) {
                if (f.isFile()) {
                    result.add(new WorkspaceFile(f.getName(), f.length(), f.lastModified(), "tmp"));
                }
            }
        }
        // 按修改时间倒序
        result.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));
        return result;
    }

    /** 删除工作区文件（仅限工作区内，防越权）。
     *  列表中的文件名可能位于 files/、tmp/ 或工作区根目录——不能只按文件名拼到
     *  工作区根（此前 deleteFile 用 resolveFile 拼到 workspaceDir，实际文件在
     *  files/ 子目录时删除失败——Agent 管理界面"删除文件无效"根因）。
     *  这里依次在 files/ → tmp/ → 工作区根 搜索并删除。 */
    /** 是否为内置工作区文档（系统生成/随包发布，自动恢复，删除受保护） */
    public static boolean isBuiltinGuideFile(String fileName) {
        if (fileName == null) return false;
        String n = fileName.trim();
        if (n.contains("/")) n = n.substring(n.lastIndexOf('/') + 1);
        // 三个工作区自动生成指南
        if (n.equals("工具创建指南.md") || n.equals("使用速查表.md") || n.equals("核心工具速查.md")) return true;
        // 随包内置指导文档（assets/apk_shell/guides/）
        for (String asset : BUILTIN_GUIDE_ASSETS) {
            String base = asset.substring(asset.lastIndexOf('/') + 1);
            if (n.equals(base)) return true;
        }
        return false;
    }

    public boolean deleteFile(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return false;
        String name = fileName.trim();
        // 内置文档保护：工作区自动恢复的系统文件不允许删除（管理页/工具/AI 统一拦截）
        if (isBuiltinGuideFile(name)) {
            AILogger.i(TAG, "Delete blocked: builtin guide protected: " + name);
            return false;
        }
        File f = null;
        // 绝对路径：直接校验是否在工作区内
        File direct = new File(name);
        if (direct.isAbsolute()) {
            f = direct;
        } else {
            // 相对路径：files/ → tmp/ → 工作区根（列表文件名可能带 "files/" 或 "tmp/" 前缀）
            f = resolveExistingFile(name);
            if (f == null) {
                File inRoot = resolveSafely(getWorkspaceDir(), name);
                if (inRoot != null && inRoot.isFile()) f = inRoot;
            }
        }
        if (f == null) return false;
        try {
            String ws = getWorkspaceDir().getCanonicalPath();
            String target = f.getCanonicalPath();
            if (!target.startsWith(ws + File.separator) && !target.equals(ws)) {
                AILogger.w(TAG, "Delete blocked: outside workspace: " + target);
                return false;
            }
            boolean ok = f.delete();
            AILogger.i(TAG, "Delete workspace file: " + target + " -> " + ok);
            return ok;
        } catch (Exception e) {
            AILogger.w(TAG, "Delete workspace file failed: " + e.getMessage());
            return false;
        }
    }

    /** 清理临时缓存区（任务结束调用：清空 tmp/ 下所有文件） */
    public int clearTmp() {
        int removed = 0;
        File[] tmps = getTmpDir().listFiles();
        if (tmps != null) {
            for (File f : tmps) {
                if (f.isFile() && f.delete()) removed++;
            }
        }
        return removed;
    }

    // ==================== 工作区指南文件（创建工具指南 + 使用速查表） ====================

    /** 在工作区长期文件区（files/）生成指南文件（幂等；纯静态内容，无外部依赖，可安全在构造期调用） */
    public void ensureGuideFiles() {
        try {
            writeGuideFile("工具创建指南.md", buildToolCreationGuide());
            writeGuideFile("使用速查表.md", buildUsageCheatsheet());
            writeGuideFile("核心工具速查.md", buildCoreToolsCheatsheet());
            AILogger.i(TAG, "工作区指南文件已生成: files/工具创建指南.md, files/使用速查表.md, files/核心工具速查.md");
        } catch (Throwable t) {
            AILogger.w(TAG, "生成工作区指南文件失败: " + t.getMessage());
        }
    }

    /** 原子写入：先写 .tmp 再 rename，避免写一半崩溃留下半截文件 */
    private void writeGuideFile(String name, String content) {
        File f = new File(getFilesDir(), name);
        File tmp = new File(getFilesDir(), name + ".tmp");
        try (java.io.FileWriter w = new java.io.FileWriter(tmp)) {
            w.write(content);
        } catch (Exception e) {
            tmp.delete();
            AILogger.w(TAG, "写指南临时文件失败: " + name + " - " + e.getMessage());
            return;
        }
        if (tmp.renameTo(f)) return;
        tmp.delete();
        try (java.io.FileWriter w = new java.io.FileWriter(f)) {
            w.write(content);
        } catch (Exception e) {
            AILogger.w(TAG, "写指南文件失败: " + name + " - " + e.getMessage());
        }
    }

    /** 创建工具功能指南（Agent 可用 workspace 工具读取；用户也可打开查看） */
    private String buildToolCreationGuide() {
        return "工具创建功能指南（答题宝 AI Agent 工作区）\n"
                + "================================================================\n"
                + "本文件位于工作区长期文件区（files/），说明如何创建自定义工具/组件。\n"
                + "Agent 可用 workspace 工具读取本文件；用户可在文件管理器查看。\n\n"
                + "一、创建工具的四种方式\n"
                + "----------------------------------------------------------------\n"
                + "1) create_dynamic_tool（Java 动态工具，推荐）：\n"
                + "   action: create / update / delete / list / show / test（默认 list）\n"
                + "   create 参数:\n"
                + "     - tool_name: 工具名（字母数字下划线）\n"
                + "     - description: 工具用途说明\n"
                + "     - parameters: 参数定义 JSON，三种格式：\n"
                + "       ① 简单: {\"参数名\":\"描述\"}\n"
                + "       ② 属性级: {\"参数名\":{\"type\":\"string\",\"description\":\"...\",\"required\":true}}\n"
                + "       ③ 完整 JSON Schema: {\"type\":\"object\",\"properties\":{...},\"required\":[...]}\n"
                + "       type 支持: string/number/integer/boolean/array/object\n"
                + "     - logic: 执行逻辑（支持三类，自动识别）：\n"
                + "       ① Python 脚本：用 script_args['参数名'] 读取参数，print 输出/顶层 return 作为结果\n"
                + "       ② JavaScript 脚本：用 script_args.参数名 读取参数，console.log 输出/表达式值作为结果（WebView/V8 内核执行）\n"
                + "       ③ DSL 命令：echo/set/if/call_tool 等\n"
                + "     - test_params: 试运行参数 JSON（action=test 时使用）\n"
                + "   list 查看全部动态工具；show 查看单个完整定义；delete 删除。持久化保存，重启可用。\n\n"
                + "2) ai_create_tool（AI 自动生成工具）：\n"
                + "   提供 tool_name + description + parameters + logic（logic 可指定 Python 或 JavaScript 执行体），\n"
                + "   由 AI 生成后注册为动态工具。\n\n"
                + "3) ui_component_plugin（原生 UI 组件插件）：\n"
                + "   把自定义 UI 封装为可复用组件类型（type 安全 + 参数校验 + 生命周期）：\n"
                + "   action: create / template / validate / get / list / remove / clear_temporary\n"
                + "   create 参数:\n"
                + "     - name: 插件名（= 新的 component_type，仅字母数字下划线）\n"
                + "     - description: 用途说明\n"
                + "     - params: 参数 schema {字段名:{type,required,default,description,enum}}，\n"
                + "       type 限 string/number/boolean/array/object\n"
                + "     - render: {card: 内置卡片类型 或 layout: 原生控件框架树, title, props}\n"
                + "     - monitor: 可选任务监控 {tool,action,poll_seconds,param_map,success_field,error_field}\n"
                + "     - persist: true=长久落盘可复用(默认) / false=临时仅内存\n"
                + "   创建组件时自动按 params schema 校验：缺必填明确报错、类型自动转换(number/boolean/\n"
                + "   array/object)、有 default 自动填充；render 必须含 card 或 layout。\n\n"
                + "4) ui_component register_type（轻量类型注册）：\n"
                + "   name + description + render（layout 或 card）→ 之后 ui_component(action=create,\n"
                + "   component_type=类型名) 直接创建复用；list_types 查看、remove_type 删除。\n"
                + "5) 连续输入表单（配套能力，实测可用）：custom 类型 props 内加 rounds=N(N>1) → 多轮连续输入,\n"
                + "   弹窗含「添加下一条」(收集本轮值并清空重建继续)与「完成」(收集本轮并结束),\n"
                + "   get_result 返回 {\"rounds\":[{第1轮}...],\"total\":N}——批量录入多条数据(多条记录/题目/清单项)\n"
                + "   一次 create 连续收集。示例: create(component_type=custom, props={fields:[{key:姓名,type:text,\n"
                + "   required:true},{key:金额,type:number}], rounds:3}) → 用户连续填3轮 → 返回3条记录\n"
                + "6) 动态画布（layout_canvas + layout_editor）：ui_component(action=create,\n"
                + "   component_type=layout_canvas, layout=完整带输入控件的布局, title=标题) 创建画布 → 拿 component_id。\n"
                + "   **create 时就要带含 input/button 的完整 layout，不要只建空画布**；每个 input/select/switch/\n"
                + "   date/number 必须带 key，button 必须带 action，否则值无法收集。后续编辑用 layout_editor\n"
                + "   (action=set/add/patch/get, component_id=同一个id)：set 整树替换、add 加**单个控件节点**(勿传含children容器)、\n"
                + "   patch 改删节点、get 查看结构。**全程用同一个 component_id，不要反复重建画布**。\n"
                + "7) 组件实例持久化（跨重启/会话保留）：ui_component(action=create, persist=true, ...) → 组件\n"
                + "   写入持久化文件，App 重启/新会话后 list_components 仍可见（persisted=true）。恢复后**默认静默**\n"
                + "   （不弹窗，get_result 返回 inactive）；用户在场需要交互时传 reactivate=true 才重建弹窗\n"
                + "   （保留同一 component_id）；close 取消持久化；close_all_components 跳过持久化组件。\n"
                + "   适合跨会话保留的静态/展示组件；弹窗类组件恢复时不会连续弹出。\n\n"
                + "二、layout 原生控件框架（JSON 声明真实原生 UI）\n"
                + "----------------------------------------------------------------\n"
                 + "   布局: column(纵向)/row(横向)/scroll(滚动)/card(圆角卡片容器,title)/wrap(流式换行)/grid(网格,columns)/space(弹性空白)/tabs(标签页,tabs=[{label,content}])/stack(层叠,子项gravity定位)/accordion(折叠面板,items=[{title,content}])/carousel(图片轮播,images)\n"
                 + "   展示: text(text,bold,size,color,align)/image(url,width,height)/marquee(text,speed 0~3)/\n"
                 + "         badge(徽章,text,color)/avatar(头像,url,size)/avatar_group(头像组,urls)/quote(引用,text,author)/\n"
                 + "         code(代码块,code,language)/icon(图标,size,color)\n"
                 + "   数据: table(headers=[列],rows=[[值]])/steps(步骤条,steps=[{title,status}])/timeline(时间线,items=[{title,time,description}])/\n"
                 + "         alert(提示条,样式字段用 alert_type 或 variant(success|warning|error|info),title,content)/stat(指标卡,label,value,unit,sub)/empty(空态,icon,title)/notice(通知条,icon,text,action)/progress_ring(环形进度,progress)\n"
                 + "   图表: line_chart(categories,series折线)/bar_chart(柱状)/pie_chart(data=[{label,value}]饼图)/sparkline(data迷你趋势)\n"
                 + "   工具: qrcode(content,size二维码)/barcode(content条形码)/countdown(seconds倒计时)/calendar(日历,value)/breadcrumb(items面包屑)\n"
                 + "   媒体: video(url或src,title,autoPlay,loop,speed,ExoPlayer原生播放)/audio(url或src,title,artist)/\n"
                 + "         html(html富文本内容,maxHeight)\n"
                 + "   输入: input(hint,key)/number(key,min,max)/password/multiline/otp(length)/\n"
                 + "         email/tel/url/search/search_bar(搜索条)/tag_input(标签输入,tags)——点击可唤起软键盘\n"
                 + "   选择: select(options,key)/switch(checked,key)/checkbox(checked)/checkbox_group(复选组)/radio(options,value)/radio_group/\n"
                 + "         date(value)/time(value)/datetime(日期+时间)/color(value)/rating(value 1~5)/\n"
                 + "         toggle(胶囊开关,options,value)/dropdown(下拉,options)/stepper(步进器,min/max/step)/slider_range(双滑块,min/max/low/high)\n"
                 + "   交互: button(text,action 回传 或 tool+tool_params 调后端)/link(text,url或action)/\n"
                 + "         slider(key,min,max)/progress(progress,max)/spinner(加载圈,size)\n"
                 + "   文件: file(key,label,value 预填或手填路径)\n"
                 + "   装饰: divider/divider_v/separator\n"
                 + "   **交互能力（实测可用）**：带 key 的控件值在布局内 button 提交时统一收集，get_result 返回 values={key:值}；多控件内容自动可滚动；divider 正常显示。\n"
                 + "   通用属性: width/height(match/wrap/数字dp/百分比如\"50%\"在wrap/grid内), margin(数字或{top,left,bottom,right}), weight或flex(弹性比例), align(对齐), 容器spacing(子项间距)/alignItems(对齐)/justify(flex_start/flex_end/center/space_between)\n"
                 + "   自定义模板: layout顶层define={模板名:节点树}或ui_component_plugin(register_layout)注册, 树内use={模板名,props:{参数}}引用, 模板内{key}由props替换\n"
                 + "   **类型嵌套与现场定义（实测可用）**: ①已注册组件类型名(register_type/插件/模板)可直接作layout节点type嵌套(如{\"type\":\"online_music_player\"}), 自动展开其render.layout, 节点props覆盖模板占位; ②未注册类型但节点自带layout现场展开({\"type\":\"my_widget\",\"layout\":{...}} 或 {\"type\":\"x\",\"render\":{\"layout\":{...}}}, 等效临时注册); ③临时layout: create时component_type给任意未注册名+layout参数(顶层/props/render三选一等效)不注册即用, 仅本次有效\n"
                + "三、工作区目录\n"
                + "----------------------------------------------------------------\n"
                + "   files/ 长期文件区（用户保留产物，不自动清理；指南文件在此）\n"
                + "   tmp/   临时缓存（执行中间文件，任务结束自动清理）\n"
                + "   生成文件默认保存到 files/，用 workspace 工具查看/读取。\n"
                + "   **可执行文件规则（重要）**：Android 禁止执行写进工作区/App 数据目录的二进制文件（Permission denied，\n"
                + "   实测 exit 126），所以脚本(.sh)没问题 —— 用 sh /完整路径/脚本.sh 执行（解释器读取，不受限）；\n"
                + "   二进制只能随 App 打包分发（例：shell_command 内置的 busybox，无需安装、无需权限）。\n"
                + "   内置指南（删除后应用启动自动重建）：《工具创建指南.md》《使用速查表.md》\n"
                + "   《HTML_DESIGN_RULES.md》(导出APK的HTML设计规则)《APK_SOURCE_GUIDE.md》(导出APK壳v8.1·62桥清单/回调契约)\n"
                + "   《douyin_downloader_GUIDE.md》(抖音下载内置工具v3.2:官方内核/UIFID自愈/双通道,删除自动恢复)。\n"
                + "   《LINUX_TOOLKIT_GUIDE.md》(内置 Linux 工具箱: 工具清单/命令路由/示例/限制/如何加工具)。\n"
                + "   《MEDIA_TOOLKIT_GUIDE.md》(本地媒体工具箱: 截帧/抽音轨/转WAV/剪切/转码/滤镜/图片处理 + 内置 ffmpeg 引擎 + android_media)。\n"
                + "   《THIRD_PARTY_NOTICES.md》(第三方组件与许可声明: ffmpeg LGPL v3 源码地址、busybox GPL v2 等)。\n"
                + "================================================================\n";
    }

    /** 使用速查表（工具/组件/插件/参数传法） */
    private String buildUsageCheatsheet() {
        return "使用速查表（答题宝 AI Agent 工作区）\n"
                + "================================================================\n"
                + "一、参数传法\n"
                + "   组件/工具参数可放顶层参数或 props 内（等效，系统自动合并，props 已有值优先）。\n"
                + "   如 marquee: text/speed 顶层 或 props={text,speed} 均可。\n\n"
                + "二、原生交互组件（ui_component action=create → get_result 取结果）\n"
                + "----------------------------------------------------------------\n"
                + "   dialog(confirm/warning) / input(input_hint) / choice(options) / multi_choice(options) /\n"
                + "   date / time / rating / color / otp(length) / number(min/max) /\n"
                + "   file_picker / image_picker / contact_picker / custom(fields 定义任意字段) /\n"
                + "   voice_recorder(录音) / speech_player(朗读) / snackbar / notification / toast /\n"
                + "   progress(max 或 max_value, update 传 progress) / marquee(text,speed 0~3) /\n"
                + "   media_task(task_id,type=image|video,自动轮询)\n\n"
                + "三、内置卡片（ui_component action=create component_type=卡片类型 props={字段} 直接展示）\n"
                + "----------------------------------------------------------------\n"
                + "   chart(bar/line/pie) / table_card / list_card / grid_card / metric_card / info_card /\n"
                + "   alert_card / steps_card / todo_card / note_card / json_viewer / code_card / link_card /\n"
                + "   image_grid / file_card / file_list / contact_card / quiz_card / weather_card /\n"
                + "   progress_card / html / markdown_card；web=网页卡片, image=图片卡片\n"
                + "   卡片可加 actions=[{\"label\":\"文字\",\"value\":\"回传值\",\"action\":\"callback\"}] 收集点击\n\n"
                + "四、动态插件（ui_component_plugin）\n"
                + "----------------------------------------------------------------\n"
                + "   create/template/validate/get/list/remove/clear_temporary\n"
                + "   persist=true 长久落盘可复用(默认) / false 临时仅内存\n"
                + "   创建时自动按 params schema 校验（缺必填报错/类型转换/默认值填充）\n"
                + "   render 必须含 card(内置卡片) 或 layout(控件树)；monitor 可自动轮询任务\n\n"
                + "四·五、自定义 layout 控件树（三条路：现场 layout / register_type / 插件）\n"
                + "----------------------------------------------------------------\n"
                + "   控件 type: 布局 column/row/scroll/card/wrap/grid/tabs/stack/accordion/carousel；\n"
                + "   展示 text/image/marquee/badge/avatar/avatar_group/quote/code/icon；\n"
                + "   数据 table/steps/timeline/alert(样式字段用 alert_type 或 variant: success|warning|error|info)/stat/empty/notice/progress_ring；\n"
                + "   图表 line_chart/bar_chart/pie_chart/sparkline；工具 qrcode/barcode/countdown/calendar/breadcrumb；\n"
                + "   媒体 video/audio/html；输入 input/number/password/multiline/otp/email/tel/url/search/search_bar/tag_input；\n"
                + "   选择 select/switch/checkbox/checkbox_group/radio/radio_group/date/time/datetime/color/rating/toggle/dropdown/stepper/slider_range；\n"
                + "   交互 button/link/slider/progress/spinner；文件 file；装饰 divider/divider_v/separator\n"
                + "   **交互能力（实测可用）**：输入/选择/交互控件可正常操作（点击可唤起软键盘）；\n"
                + "   带 key 的控件值在布局内 button 提交时统一收集，get_result 返回 values={key:值}；\n"
                + "   多控件内容自动可滚动；divider 正常显示分隔线。\n"
+ "   **类型嵌套与现场定义（实测可用）**: 已注册类型名(register_type/插件/模板)可直接作layout节点type嵌套(如{\"type\":\"online_music_player\"},自动展开其render.layout,节点props覆盖占位); 未注册类型节点带layout现场展开({\"type\":\"my_widget\",\"layout\":{...}}或{\"type\":\"x\",\"render\":{\"layout\":{...}}}); 临时layout: create时component_type任意未注册名+layout参数(顶层/props/render三选一)不注册即用,仅本次有效; use引用模板: {\"use\":\"模板名\",\"props\":{参数}},模板内{key}由props替换。\n"
+ "   连续输入表单（配套能力）: custom + props.rounds=N(N>1) → 多轮连续输入,「添加下一条」收集清空重建/「完成」结束,\n"
+ "   get_result 返回 {\"rounds\":[{第1轮}...],\"total\":N}——批量录入多条数据一次创建连续收集。\n"
+ "   动态画布（layout_canvas + layout_editor）: ui_component(create, component_type=layout_canvas, layout=完整带输入控件布局) 建画布→拿component_id；\n"
+ "   **create 时就要带含 key 的 input/button 完整布局**；编辑用 layout_editor(set/add/patch/get, 同一component_id)；控件必带key、button必带action。\n\n"
                + "五、核心工具速查\n"
                + "----------------------------------------------------------------\n"
                + "   ai_weather       天气(当前/预报/空气质量/预警/生活指数)\n"
                + "   network_search   网络搜索/读网页/信息提取/智能摘要\n"
                + "   smart_research   智能研究(搜索→阅读→摘要全流程)\n"
                + "   webpage_reader   网页阅读/提取/多页抓取\n"
                + "   python_calculate 数学表达式计算\n"
                + "   python_execute   执行Python代码(内置android_ui组件能力;内置pip可import,禁止python -m pip)\n"
                + "   python_analyze_data 数据分析(统计/清洗/转换)\n"
                + "   python_web_reader 抓网页/API(requests+bs4)\n"
                + "   python_file_ops  Python文件读写/解析(CSV/JSON/XML/xlsx/docx/pptx/pdf)\n"
                + "   python_chart     Python绘图(Pillow/matplotlib)生成PNG\n"
                + "   js_execute       JS代码执行(WebView内核:验证/调试/JSON处理/正则)\n"
                + "   pip_install      运行时安装纯Python包(pytz/tqdm等;自研下载器不依赖pip,装后即可import,禁止python -m pip)\n"
                + "   remote_dsh      远程控制电脑(v3 ACP官方通道:dsh0.1.5+pair扫码配对+多轮续接;需电脑端 dsh --profile acp serve + tools/dsh_bridge_server.py 桥接)\n"
                + "   screen_capture   截屏(MediaProjection授权→存files/screenshots/)\n"
                + "   web_render       网页渲染浏览(DOM文本+可选截图,支持SPA/JS页面)\n"
                + "   location         定位/当前位置/城市\n"
                + "   file_reader      读取/解析Excel-CSV-JSON-XML/列目录\n"
                + "   file_analyzer    文件内容分析\n"
                + "   file_generator   生成文本/JSON/配置/Markdown文件(默认存工作区files/)\n"
                + "   database         题库/用户/分数数据库操作\n"
                + "   excel_tool       Excel查询/修改\n"
                + "   linux_shell      内置 Linux 工具箱: exec 跑命令 / tools 列工具 / download 下载 / route 改路由顺序\n"
                + "   system_resource  打开URL/应用/短信/电话/系统操作 + shell_command/termux_exec/shell_mode\n"
                + "   app_operation    应用内页面跳转\n"
                + "   image_gen        AI生成图片\n"
                + "   dashscope_media  百炼文生图/文生视频(通义万相)\n"
                + "   speech_synthesis 语音合成(TTS,可带朗读组件)\n"
                + "   voice_input      语音识别(录音→文字)\n"
                + "   ocr_recognize    图片文字识别/看图理解\n"
                + "   screen_watch     盯梢(监控屏幕,等目标出现/消失或画面变化)\n"
                + "   memory           长期记忆(保存/回忆/删除用户偏好)\n"
                + "   chat_history     对话历史(跨会话读最近消息/关键词搜索,回忆之前创建的工具/组件)\n"
                + "   workspace        工作区文件(列目录/读取/生成/删除)\n"
                + "   ui_component     创建UI组件(原生交互/内置卡片/自定义layout)\n"
                + "   ui_component_plugin 动态插件(注册可复用组件类型)\n"
                + "   create_dynamic_tool 动态创建/管理AI工具(Python/JS/DSL执行体)\n"
                + "   ai_create_tool   AI自动生成新工具(支持Python/JS执行体)\n"
                + "   tool_registry    工具注册表(列出/搜索/取schema)\n"
                + "   permission_manager 权限检查/请求\n"
                + "   calculator       数学计算(四则/幂/括号)\n"
                + "   time_date        时间日期(当前时间/时区/时间戳互转)\n"
                + "   unit_converter   单位换算\n"
                + "   text_tools       文本处理(转换/清洗/格式)\n"
                + "   reminder         提醒(定时/到时通知)\n"
                + "   task             任务管理(创建/更新/查询/进度)\n"
                + "   system_connect   系统级连接与设备能力\n"
                + "   knowledge_base   知识库(全文检索/添加/导入JSON)\n"
                + "   douyin_downloader  抖音下载(官方内核解析+UIFID自愈,自动解包落袋,支持链接/分享文案/BGM)\n"
                + "   douyin_downloader  抖音下载(官方内核解析+UIFID自愈,自动解包落袋,支持链接/分享文案/BGM)\n"
                + "   video_to_player  视频下载转播放(解析视频源/下载到工作区)\n"
                + "   media_toolkit    本地媒体工具箱: probe信息/frame截帧/extract_audio抽音轨/to_wav/trim剪切/transcode转码/image_ops图片\n"
                + "   export_apk       APK导出(把HTML打包成可安装安卓应用；规则见files/HTML_DESIGN_RULES.md)\n"
                + "   control_lookup   控件查询(查找可用UI控件/组件)\n"
                + "   layout_editor    动态画布编辑(set/add/patch/get 同一component_id)\n"
                + "   get_models_profile   模型配置查询\n"
                + "   update_models_profile 模型配置更新\n\n"
                + "六、内置 Linux 命令工具箱（linux_shell）\n"
                + "----------------------------------------------------------------\n"
                + "   独立工具 linux_shell(action=exec/tools/download/route)；system_resource(shell_command/termux_exec/http_download/shell_mode) 兼容保留。\n"
                + "   内置（Termux bionic 构建，随 App 打包，无需装 Termux、无需任何权限）：\n"
                + "     · busybox 1.38（280+ applet：ash/awk/vi/telnet/tar/gzip/nc/httpd/crontab…）\n"
                + "     · openssl 3.6.3（真 TLS + CA 包）、ssh/scp/sftp/ssh-keygen/ssh-keyscan/ssh-add、curl、aria2c\n"
                + "     · jq / ripgrep(rg) / sqlite3 / zstd / zip / unzip / file / tree / ncdu / htop / ps / free / tmux / nano / gawk\n"
                + "     · 系统自带 toybox 一直可用（sed/grep/find/sort/head/tail/wc/md5sum/base64/xargs/diff/du/df…）\n"
                + "   命令路由：内置 → /system/bin → busybox → toybox，某个实现不可用会自动回退；PATH 里内置目录在最前。\n"
                + "     查看/修改：linux_shell(action=route[, command=名字, order=bskt/stkb/b])，order=reset 恢复；\n"
                + "     也可在「工具集 → 设置与数据 → 命令路由（Linux 工具箱）」界面点选。\n"
                + "   环境：HOME/TMPDIR/SSL_CERT_FILE 已配好；需要依赖库的工具由 liblauncher.so 自带库路径（不污染系统命令）。\n"
                + "     · Python 里同样可用：import android_shell（run/run_argv/available/tool_path）\n"
                + "     · wget/curl 支持 https（内置 curl 自带 TLS；wget 走 App 内下载服务）；也可用 system_resource(action=http_download, url=...)\n"
                + "     · 单条命令 25 秒超时（超时返回已产生输出）；长任务用 tmux；默认不拦截，shell_mode 可开只读\n"
                + "     · 示例：rg -n TODO /sdcard/Download ；jq . f.json ；tar -czf $HOME/a.tar.gz dir\n"
                + "     · 没有 ffmpeg（也装不上，见《LINUX_TOOLKIT_GUIDE.md》第七节）：音视频/图片处理一律用 media_toolkit（probe/截帧/抽音轨/转WAV/剪切/转码/图片）\n"
                + "   **脚本 vs 二进制（容易踩坑）**：Android 禁止执行写进工作区/App 数据目录的二进制文件（Permission denied），\n"
                + "     所以复杂逻辑写成 .sh 放工作区，用 sh /完整路径/脚本.sh 执行；二进制只能随 App 打包分发。\n"
                + "   完整说明见工作区《LINUX_TOOLKIT_GUIDE.md》。\n"
                + "   termux_exec：把命令交给已安装的 Termux（完整 Linux 生态，可 apt/pip/ssh/git），\n"
                + "     需在 App 内授权一次并在 Termux 里开启 allow-external-apps=true；返回 stdout/stderr/exit_code，20 秒超时。\n\n"
                + "七、Python android_ui 模块（python_execute 脚本内）\n"
                + "----------------------------------------------------------------\n"
                + "   from android_ui import show_toast, show_dialog, create_component, update_component,\n"
                + "       get_component_result, close_component\n"
                + "   便捷函数: ask_input / ask_choice / show_progress\n"
                + "八、本地媒体工具箱（media_toolkit）\n"
                + "----------------------------------------------------------------\n"
                + "   系统硬解硬编为主，打不开的东西自动回退**内置 ffmpeg 引擎**（engine=auto 默认；处理过程不需要权限、不联网）：\n"
                + "     · probe          媒体信息：时长/分辨率/帧率/码率/旋转/音视频轨/编码器（视频、音频、图片都行）\n"
                + "     · frame          截帧出图：time=秒 / percent=0-100 / index=帧序号 / count=N 抽N张 / exact=true 精确帧\n"
                + "     · thumbnail      缩略图：默认 10% 处、最长边 512（列表预览用）\n"
                + "     · extract_audio  无损抽音轨（aac→m4a、mp3→mp3，重封装不重编码，秒级完成）\n"
                + "     · to_wav         解码 WAV：默认 16kHz 单声道（可直接喂 SenseVoice/ASR），rate/channels 可调\n"
                + "     · trim           无损剪切：start/end 秒，关键帧对齐（起点会吸附到前一个关键帧），不重编码\n"
                + "     · transcode      转码/压缩/换容器：video_mime=h264/h265/av1/keep、audio_mime=aac/none/keep、\n"
                + "                      width/height/scale/bitrate/remove_audio；keep+无效果=纯重封装(换容器，秒级)\n"
                + "     · image_ops      图片：width/height/max、crop=x,y,w,h、rotate、flip=h|v、gray、format、quality\n"
                + "     · filter         ffmpeg 滤镜链：vfilter/afilter（如 scale=-2:720,fps=10 / overlay/ass字幕/atempo=1.5,volume=2）\n"
                + "     · engine         auto(默认,系统优先+ffmpeg回退) / system(只用系统) / ffmpeg(只用 ffmpeg)\n"
                + "   输入：绝对路径 / 工作区相对路径 / content:// URI；输出默认 files/media/，返回 file 绝对路径。\n"
                + "     · Python 里 import android_media 用同一套能力；纯图片批处理也可直接用 Pillow。\n"
                + "   **内置 ffmpeg 引擎（engine 参数）**：avi/flv/rmvb/wmv 等系统框架打不开的容器、rv40/cook/wmv3/vc1 等老编码都能读/截帧/转码；\n"
                + "     转码走 h264_mediacodec/hevc_mediacodec 硬件桥（实测可用），也能跑系统框架没有的滤镜链。\n"
                + "   **仍然做不到的（不要越界承诺）**：mp3/opus **编码**（min 构建无 lame/opus 编码器；mp3 只能抽已有音轨）、drawtext 文字水印（无 freetype）、\n"
                + "     时间轴水印/画中画/多路混流这类复杂编排；非主流编码纯软解会慢；trim 是关键帧对齐（非帧级精确）；\n"
                + "     transcode 实际分辨率会被编码器对齐取整（以返回 output_width/height 为准）；读取外部文件仍受 App 已有存储访问限制。\n"
                + "     做不到就如实告诉用户\"设备媒体框架不支持这个格式/这件事\"，不要硬凑。\n"
                + "   完整说明（能力边界/示例/限制）见工作区《MEDIA_TOOLKIT_GUIDE.md》。\n"
                + "================================================================\n";
    }

    /** 核心工具速查（独立文件，供 Agent 按需读取；约 700 token，避免读全表占满上下文） */
    private String buildCoreToolsCheatsheet() {
        return "核心工具速查（答题宝 AI Agent 工作区）\n"
                + "================================================================\n"
                + "   ai_weather       天气(当前/预报/空气质量/预警/生活指数)\n"
                + "   network_search   网络搜索/读网页/信息提取/智能摘要\n"
                + "   smart_research   智能研究(搜索→阅读→摘要全流程)\n"
                + "   webpage_reader   网页阅读/提取/多页抓取\n"
                + "   python_calculate 数学表达式计算\n"
                + "   python_execute   执行Python代码(内置android_ui组件能力;内置pip可import,禁止python -m pip)\n"
                + "   python_analyze_data 数据分析(统计/清洗/转换)\n"
                + "   python_web_reader 抓网页/API(requests+bs4)\n"
                + "   python_file_ops  Python文件读写/解析(CSV/JSON/XML/xlsx/docx/pptx/pdf)\n"
                + "   python_chart     Python绘图(Pillow/matplotlib)生成PNG\n"
                + "   js_execute       JS代码执行(WebView内核:验证/调试/JSON处理/正则)\n"
                + "   pip_install      运行时安装纯Python包(pytz/tqdm等;自研下载器不依赖pip,装后即可import,禁止python -m pip)\n"
                + "   remote_dsh      远程控制电脑(v3 ACP官方通道:dsh0.1.5+pair扫码配对+多轮续接;需电脑端 dsh --profile acp serve + tools/dsh_bridge_server.py 桥接)\n"
                + "   screen_capture   截屏(MediaProjection授权→存files/screenshots/)\n"
                + "   web_render       网页渲染浏览(DOM文本+可选截图,支持SPA/JS页面)\n"
                + "   location         定位/当前位置/城市\n"
                + "   file_reader      读取/解析Excel-CSV-JSON-XML/列目录\n"
                + "   file_analyzer    文件内容分析\n"
                + "   file_generator   生成文本/JSON/配置/Markdown文件(默认存工作区files/)\n"
                + "   database         题库/用户/分数数据库操作\n"
                + "   excel_tool       Excel查询/修改\n"
                + "   linux_shell      内置 Linux 工具箱: exec 跑命令 / tools 列工具 / download 下载 / route 改路由顺序\n"
                + "   system_resource  打开URL/应用/短信/电话/系统操作 + shell_command/termux_exec/shell_mode\n"
                + "   app_operation    应用内页面跳转\n"
                + "   image_gen        AI生成图片\n"
                + "   dashscope_media  百炼文生图/文生视频(通义万相)\n"
                + "   speech_synthesis 语音合成(TTS,可带朗读组件)\n"
                + "   voice_input      语音识别(录音→文字)\n"
                + "   ocr_recognize    图片文字识别/看图理解\n"
                + "   screen_watch     盯梢(监控屏幕,等目标出现/消失或画面变化)\n"
                + "   memory           长期记忆(保存/回忆/删除用户偏好)\n"
                + "   chat_history     对话历史(跨会话读最近消息/关键词搜索,回忆之前创建的工具/组件)\n"
                + "   workspace        工作区文件(列目录/读取/生成/删除)\n"
                + "   ui_component     创建UI组件(原生交互/内置卡片/自定义layout)\n"
                + "   ui_component_plugin 动态插件(注册可复用组件类型)\n"
                + "   create_dynamic_tool 动态创建/管理AI工具(Python/JS/DSL执行体)\n"
                + "   ai_create_tool   AI自动生成新工具(支持Python/JS执行体)\n"
                + "   tool_registry    工具注册表(列出/搜索/取schema)\n"
                + "   permission_manager 权限检查/请求\n"
                + "   calculator       数学计算(四则/幂/括号)\n"
                + "   time_date        时间日期(当前时间/时区/时间戳互转)\n"
                + "   unit_converter   单位换算\n"
                + "   text_tools       文本处理(转换/清洗/格式)\n"
                + "   reminder         提醒(定时/到时通知)\n"
                + "   task             任务管理(创建/更新/查询/进度)\n"
                + "   system_connect   系统级连接与设备能力\n"
                + "   knowledge_base   知识库(全文检索/添加/导入JSON)\n"
                + "   douyin_downloader  抖音下载(官方内核解析+UIFID自愈,自动解包落袋,支持链接/分享文案/BGM)\n"
                + "   douyin_downloader  抖音下载(官方内核解析+UIFID自愈,自动解包落袋,支持链接/分享文案/BGM)\n"
                + "   video_to_player  视频下载转播放(解析视频源/下载到工作区)\n"
                + "   media_toolkit    本地媒体工具箱: probe信息/frame截帧/extract_audio抽音轨/to_wav/trim剪切/transcode转码/image_ops图片\n"
                + "   export_apk       APK导出(把HTML打包成可安装安卓应用；规则见files/HTML_DESIGN_RULES.md)\n"
                + "   control_lookup   控件查询(查找可用UI控件/组件)\n"
                + "   layout_editor    动态画布编辑(set/add/patch/get 同一component_id)\n"
                + "   get_models_profile   模型配置查询\n"
                + "   update_models_profile 模型配置更新\n\n";
    }

    /**
     * 迁移私有目录旧工作区文件到公共目录（有权限时调用一次）。
     * 复制 files/ 长期文件（tmp/ 临时文件不迁移——任务结束本就清理）。
     * @return 迁移的文件数
     */
    public int migrateFromPrivate(Context context) {
        if (!publicWorkspace || context == null) return 0;
        try {
            java.io.File privateWs = new java.io.File(context.getFilesDir(), WORKSPACE_DIR);
            java.io.File privateFiles = new java.io.File(privateWs, FILES_DIR);
            if (!privateFiles.exists() || !privateFiles.isDirectory()) return 0;
            java.io.File[] old = privateFiles.listFiles();
            if (old == null) return 0;
            int migrated = 0;
            for (java.io.File f : old) {
                if (!f.isFile()) continue;
                java.io.File dest = new java.io.File(filesDir, f.getName());
                if (dest.exists()) continue; // 目标已存在不覆盖
                // 原子迁移：先写 .tmp 再 rename，避免写一半崩溃留下半截文件
                java.io.File tmp = new java.io.File(filesDir, f.getName() + ".tmp");
                try (java.io.InputStream is = new java.io.FileInputStream(f);
                     java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        os.write(buf, 0, n);
                    }
                } catch (Exception e) {
                    tmp.delete(); // 失败清理半截文件
                    AILogger.w(TAG, "迁移文件失败: " + f.getName() + " - " + e.getMessage());
                    continue;
                }
                if (tmp.renameTo(dest)) {
                    migrated++;
                } else {
                    tmp.delete();
                    AILogger.w(TAG, "迁移重命名失败: " + f.getName());
                }
            }
            if (migrated > 0) {
                AILogger.i(TAG, "Migrated " + migrated + " files from private workspace to public");
            }
            return migrated;
        } catch (Throwable t) {
            AILogger.w(TAG, "迁移工作区失败: " + t.getMessage());
            return 0;
        }
    }

    /** 工作区文件信息 */
    public static class WorkspaceFile {
        public final String name;
        public final long size;
        public final long lastModified;
        /** 所在区域: "files"(长期) / "tmp"(临时) / "root"(根) */
        public final String zone;

        public WorkspaceFile(String name, long size, long lastModified) {
            this(name, size, lastModified, "root");
        }

        public WorkspaceFile(String name, long size, long lastModified, String zone) {
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
            this.zone = zone;
        }
    }
}

