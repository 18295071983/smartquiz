package com.oilquiz.app.ai.tool;

import java.util.HashMap;
import java.util.Map;

/**
 * AI工具抽象基类，实现了AITool接口的默认方法
 * 
 * 提供了工具的基本属性和默认实现，子类只需实现execute方法
 */
public abstract class BaseAITool implements AITool {

    /** 内置 Linux 工具箱说明：注解(注解值必须是编译期常量)与工具描述共用一份文案 */
    public static final String TOOLKIT_HINT = "shell_command 已内置 busybox 1.38（Termux 官方 bionic 构建，无需安装 Termux、无需任何权限）："
            + "ash(完整 Linux shell)/awk/vi/telnet/tar/gzip/md5sum/base64/httpd/… 400+ 命令可直接按名字调用（wget/curl 已支持 https，走 App 内下载服务）；"
            + "系统自带的 toybox 命令优先（sed/grep/find/sort/head 等），busybox 在 PATH 末尾补足系统没有的，"
            + "也可用 $BUSYBOX_BIN_DIR 下的绝对路径调用。Python 子进程同样可用（js_execute 在 WebView 里没有子进程能力，用不了这些命令）（App 启动时已把 PATH/LD_LIBRARY_PATH 注入，"
            + "python_execute 里 subprocess 直接调 busybox/sed 等即可）。复杂逻辑建议写成脚本放工作区再执行（sh /路径/脚本.sh）："
            + "Android 禁止执行工作区/数据目录里的二进制文件（Permission denied），但脚本由解释器读取，不受限";

    private final String toolName;
    private final String description;

    public BaseAITool(String toolName, String description) {
        this.toolName = toolName;
        this.description = description;
    }

    @Override
    public String getName() {
        return toolName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        return new HashMap<>();
    }
    
    public boolean canHandle(String input) {
        return input != null && input.toLowerCase().contains(toolName.toLowerCase());
    }

    /**
     * 把异常转成可读原因，供工具失败时回填给 Agent/用户。
     *
     * <p>为什么需要：{@code e.getMessage()} 允许为 null（NullPointerException、部分框架/反射异常、
     * 某些 Chaquopy 包装异常都是 null），直接拼接会得到「xx失败: 」这种**原因为空**的失败信息，
     * Agent 拿到后无从判断（真机实测：python_file_ops 报 "工具执行失败: Python文件工具失败: "）。
     * 这里在 message 为空时退化为「异常类名」，并尽量带上 cause。
     */
    protected static String errText(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        String m = e.getMessage();
        if (m != null && !m.trim().isEmpty()) {
            return m;
        }
        StringBuilder sb = new StringBuilder(e.getClass().getSimpleName());
        Throwable c = e.getCause();
        if (c != null && c != e) {
            sb.append(" <- ").append(c.getClass().getSimpleName());
            if (c.getMessage() != null && !c.getMessage().trim().isEmpty()) {
                sb.append(": ").append(c.getMessage());
            }
        }
        return sb.toString();
    }
}