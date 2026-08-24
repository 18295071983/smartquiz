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
        File inFiles = new File(filesDir, p);
        if (inFiles.isFile()) return inFiles;
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

    /** 删除工作区文件（仅限工作区内，防越权） */
    public boolean deleteFile(String fileName) {
        File f = resolveFile(fileName);
        if (f == null) return false;
        try {
            String ws = getWorkspaceDir().getCanonicalPath();
            String target = f.getCanonicalPath();
            if (!target.startsWith(ws + File.separator) && !target.equals(ws)) {
                AILogger.w(TAG, "Delete blocked: outside workspace: " + target);
                return false;
            }
            return f.delete();
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
            AILogger.i(TAG, "工作区指南文件已生成: files/工具创建指南.md, files/使用速查表.md");
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
                + "     - logic: 执行逻辑（Python 脚本自动识别，脚本内用 script_args['参数名'] 读取参数，\n"
                + "       支持顶层 return 返回结果；或 DSL 命令 echo/set/if/call_tool 等）\n"
                + "     - test_params: 试运行参数 JSON（action=test 时使用）\n"
                + "   list 查看全部动态工具；show 查看单个完整定义；delete 删除。持久化保存，重启可用。\n\n"
                + "2) ai_create_tool（AI 自动生成工具）：\n"
                + "   提供 tool_name + description + parameters + logic，由 AI 生成后注册为动态工具。\n\n"
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
                + "   component_type=类型名) 直接创建复用；list_types 查看、remove_type 删除。\n\n"
                + "二、layout 原生控件框架（JSON 声明真实原生 UI）\n"
                + "----------------------------------------------------------------\n"
                + "   布局: column(纵向)/row(横向)/scroll(滚动)\n"
                + "   展示: text(text,bold,size,color)/image(url,width,height)/marquee(text,speed 0~3)\n"
                + "   输入: input(hint,key)/number(key,min,max)/password/multiline/otp(length)\n"
                + "   选择: select(options,key)/switch(checked,key)/checkbox(checked)/radio(options,value)/\n"
                + "         date(value)/time(value)/color(value)/rating(value 1~5)\n"
                + "   交互: button(text,action 回传 或 tool+tool_params 调后端)/slider(key,min,max)/\n"
                + "         progress(progress,max)\n"
                + "   装饰: divider\n"
                + "   文本支持 {key} 从 props 替换；尺寸 width/height 支持 match/fill/wrap/数字 dp\n"
                + "   传法三种等效: 顶层 layout / props={layout:...} / render={layout:...}\n\n"
                + "三、工作区目录\n"
                + "----------------------------------------------------------------\n"
                + "   files/ 长期文件区（用户保留产物，不自动清理；指南文件在此）\n"
                + "   tmp/   临时缓存（执行中间文件，任务结束自动清理）\n"
                + "   生成文件默认保存到 files/，用 workspace 工具查看/读取。\n"
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
                + "五、核心工具速查\n"
                + "----------------------------------------------------------------\n"
                + "   ai_weather       天气(当前/预报/空气质量/预警/生活指数)\n"
                + "   network_search   网络搜索/读网页/信息提取/智能摘要\n"
                + "   smart_research   智能研究(搜索→阅读→摘要全流程)\n"
                + "   webpage_reader   网页阅读/提取/多页抓取\n"
                + "   python_calculate 数学表达式计算\n"
                + "   python_execute   执行Python代码(内置android_ui组件能力)\n"
                + "   python_analyze_data 数据分析(统计/清洗/转换)\n"
                + "   python_web_reader 抓网页/API(requests+bs4)\n"
                + "   python_file_ops  Python文件读写/解析\n"
                + "   python_chart     Python绘图(Pillow)生成PNG\n"
                + "   location         定位/当前位置/城市\n"
                + "   file_reader      读取/解析Excel-CSV-JSON-XML/列目录\n"
                + "   file_analyzer    文件内容分析\n"
                + "   file_generator   生成文本/JSON/配置/Markdown文件(默认存工作区files/)\n"
                + "   database         题库/用户/分数数据库操作\n"
                + "   excel_tool       Excel查询/修改\n"
                + "   system_resource  打开URL/应用/短信/电话/系统操作\n"
                + "   app_operation    应用内页面跳转\n"
                + "   image_gen        AI生成图片\n"
                + "   dashscope_media  百炼文生图/文生视频(通义万相)\n"
                + "   speech_synthesis 语音合成(TTS,可带朗读组件)\n"
                + "   voice_input      语音识别(录音→文字)\n"
                + "   ocr_recognize    图片文字识别\n"
                + "   memory           长期记忆(保存/回忆/删除用户偏好)\n"
                + "   workspace        工作区文件(列目录/读取/生成/删除)\n"
                + "   ui_component     创建UI组件(原生交互/内置卡片/自定义layout)\n"
                + "   ui_component_plugin 动态插件(注册可复用组件类型)\n"
                + "   create_dynamic_tool 动态创建/管理AI工具\n"
                + "   ai_create_tool   AI自动生成新工具\n"
                + "   tool_registry    工具注册表(列出/搜索/取schema)\n"
                + "   permission_manager 权限检查/请求\n"
                + "   app_toolkit      聚合工具(OCR/图像/解析/天气/计算)\n\n"
                + "六、Python android_ui 模块（python_execute 脚本内）\n"
                + "----------------------------------------------------------------\n"
                + "   from android_ui import show_toast, show_dialog, create_component, update_component,\n"
                + "       get_component_result, close_component\n"
                + "   便捷函数: ask_input / ask_choice / show_progress\n"
                + "================================================================\n";
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
