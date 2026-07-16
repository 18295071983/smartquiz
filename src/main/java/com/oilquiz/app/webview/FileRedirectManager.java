package com.oilquiz.app.webview;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 文件重定向管理器
 * 管理所有文件重定向规则，确保所有文件请求必须显式配置重定向规则
 * 禁止使用任何默认文件路径或行为
 */
public class FileRedirectManager {

    private static final String TAG = "FileRedirectManager";

    // 单例实例
    private static volatile FileRedirectManager instance;

    // 重定向规则列表（线程安全）
    private final List<FileRedirectRule> redirectRules;

    // 是否启用严格模式（默认启用）
    private boolean strictMode = true;

    // 是否已初始化
    private volatile boolean initialized = false;

    private FileRedirectManager() {
        this.redirectRules = new CopyOnWriteArrayList<>();
    }

    /**
     * 获取单例实例
     */
    @NonNull
    public static FileRedirectManager getInstance() {
        if (instance == null) {
            synchronized (FileRedirectManager.class) {
                if (instance == null) {
                    instance = new FileRedirectManager();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化管理器
     * 必须在应用启动时调用，配置所有必需的重定向规则
     *
     * @param config 配置回调
     */
    public void initialize(@NonNull RedirectConfig config) {
        if (initialized) {
            throw new IllegalStateException("FileRedirectManager 已经初始化，请勿重复初始化");
        }

        synchronized (this) {
            // 清空现有规则
            redirectRules.clear();

            // 临时设置为已初始化，以便在配置回调中调用addRule
            boolean tempInitialized = initialized;
            initialized = true;
            
            try {
                // 应用配置
                config.configure(this);

                // 验证配置
                validateConfiguration();
            } finally {
                // 恢复初始化状态
                if (!tempInitialized) {
                    initialized = true;
                }
            }
        }
    }

    /**
     * 验证配置
     * 确保至少配置了一些规则
     */
    private void validateConfiguration() {
        if (strictMode && redirectRules.isEmpty()) {
            throw new IllegalStateException(
                    "严格模式下必须至少配置一个文件重定向规则。" +
                            "请调用 addRule() 方法添加重定向规则。"
            );
        }
    }

    /**
     * 添加重定向规则
     *
     * @param rule 重定向规则
     * @return 当前管理器实例（链式调用）
     */
    @NonNull
    public FileRedirectManager addRule(@NonNull FileRedirectRule rule) {
        checkInitialized();
        // 检查是否已存在相同源路径的规则
        for (FileRedirectRule existingRule : redirectRules) {
            if (existingRule.getSourcePath().equals(rule.getSourcePath()) &&
                    existingRule.getRedirectType() == rule.getRedirectType()) {
                throw new IllegalArgumentException(
                        "已存在相同的重定向规则: " + rule.getSourcePath() +
                                " (类型: " + rule.getRedirectType() + ")"
                );
            }
        }

        redirectRules.add(rule);
        return this;
    }

    /**
     * 添加重定向规则（便捷方法）
     *
     * @param sourcePath 源路径
     * @param targetPath 目标路径
     * @return 当前管理器实例
     */
    @NonNull
    public FileRedirectManager addRule(@NonNull String sourcePath, @NonNull String targetPath) {
        checkInitialized();
        return addRule(new FileRedirectRule.Builder(sourcePath, targetPath).build());
    }

    /**
     * 移除重定向规则
     *
     * @param sourcePath 源路径
     * @param type 重定向类型
     * @return 是否成功移除
     */
    public boolean removeRule(@NonNull String sourcePath, @NonNull FileRedirectRule.RedirectType type) {
        checkInitialized();

        for (FileRedirectRule rule : redirectRules) {
            if (rule.getSourcePath().equals(sourcePath) && rule.getRedirectType() == type) {
                return redirectRules.remove(rule);
            }
        }
        return false;
    }

    /**
     * 清空所有规则
     */
    public void clearRules() {
        checkInitialized();
        redirectRules.clear();
    }

    /**
     * 获取重定向后的路径
     * 如果未配置重定向规则，抛出 FileRedirectException
     *
     * @param originalPath 原始路径
     * @return 重定向后的路径
     * @throws FileRedirectException 当未配置重定向规则时
     */
    @NonNull
    public String getRedirectedPath(@NonNull String originalPath) throws FileRedirectException {
        checkInitialized();

        if (originalPath.isEmpty()) {
            throw new FileRedirectException(originalPath, "路径不能为空");
        }

        // 查找匹配的规则
        FileRedirectRule matchedRule = findMatchingRule(originalPath);

        if (matchedRule == null) {
            // 未找到匹配规则，抛出异常
            throw new FileRedirectException(originalPath);
        }

        // 返回重定向后的路径
        return matchedRule.getRedirectedPath(originalPath);
    }

    /**
     * 查找匹配的规则
     *
     * @param path 路径
     * @return 匹配的规则，如果没有则返回 null
     */
    @Nullable
    private FileRedirectRule findMatchingRule(@NonNull String path) {
        // 优先匹配精确规则
        for (FileRedirectRule rule : redirectRules) {
            if (rule.getRedirectType() == FileRedirectRule.RedirectType.EXACT && rule.matches(path)) {
                return rule;
            }
        }

        // 然后匹配前缀规则
        for (FileRedirectRule rule : redirectRules) {
            if (rule.getRedirectType() == FileRedirectRule.RedirectType.PREFIX && rule.matches(path)) {
                return rule;
            }
        }

        // 最后匹配其他类型
        for (FileRedirectRule rule : redirectRules) {
            if (rule.matches(path)) {
                return rule;
            }
        }

        return null;
    }

    /**
     * 检查路径是否有配置重定向规则
     *
     * @param path 路径
     * @return 是否有配置
     */
    public boolean hasRedirectRule(@NonNull String path) {
        checkInitialized();
        return findMatchingRule(path) != null;
    }

    /**
     * 获取所有规则（不可修改视图）
     *
     * @return 规则列表
     */
    @NonNull
    public List<FileRedirectRule> getAllRules() {
        return Collections.unmodifiableList(new ArrayList<>(redirectRules));
    }

    /**
     * 设置严格模式
     * 严格模式下，所有文件请求必须显式配置重定向规则
     *
     * @param strict 是否启用严格模式
     */
    public void setStrictMode(boolean strict) {
        this.strictMode = strict;
    }

    /**
     * 是否启用严格模式
     */
    public boolean isStrictMode() {
        return strictMode;
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 重置管理器（主要用于测试）
     */
    public void reset() {
        synchronized (this) {
            redirectRules.clear();
            initialized = false;
            strictMode = true;
        }
    }

    /**
     * 检查初始化状态
     */
    private void checkInitialized() {
        if (!initialized) {
            throw new IllegalStateException(
                    "FileRedirectManager 未初始化。" +
                            "请在应用启动时调用 initialize() 方法进行初始化。"
            );
        }
    }

    /**
     * 重定向配置接口
     */
    public interface RedirectConfig {
        void configure(@NonNull FileRedirectManager manager);
    }
}
