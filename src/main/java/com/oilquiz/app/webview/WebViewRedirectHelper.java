package com.oilquiz.app.webview;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * WebView 文件重定向帮助类
 * 为所有使用 WebView 的功能提供统一的临时规则更新
 */
public class WebViewRedirectHelper {

    private static final String TAG = "WebViewRedirectHelper";

    // 默认规则是否已添加的标志
    private static boolean defaultRulesAdded = false;

    /**
     * 在使用 WebView 之前调用，确保文件重定向规则已配置
     *
     * @param context 上下文
     */
    public static void prepareWebView(Context context) {
        try {
            FileRedirectManager manager = FileRedirectManager.getInstance();

            // 检查管理器是否已初始化
            if (!manager.isInitialized()) {
                // 如果未初始化，先进行初始化
                manager.initialize(mgr -> {
                    // 添加默认的重定向规则
                    addDefaultRedirectRules(context, mgr);
                });
                defaultRulesAdded = true;
            } else if (!defaultRulesAdded) {
                // 如果已初始化但没有添加默认规则，添加默认规则
                addDefaultRedirectRules(context, manager);
                defaultRulesAdded = true;
            }

            // 添加临时规则（每次调用都添加，确保最新）
            addTemporaryRules(context, manager);

            Log.d(TAG, "WebView 文件重定向规则已准备完成");

        } catch (Exception e) {
            Log.e(TAG, "准备 WebView 文件重定向规则失败: " + e.getMessage(), e);
        }
    }

    /**
     * 添加默认的重定向规则
     */
    private static void addDefaultRedirectRules(Context context, FileRedirectManager manager) {
        String filesDir = context.getFilesDir().getAbsolutePath();
        String cacheDir = context.getCacheDir().getAbsolutePath();

        // 默认规则：/webview/ 前缀重定向
        try {
            manager.addRule(new FileRedirectRule.Builder("/webview/", filesDir + "/webview/")
                    .redirectType(FileRedirectRule.RedirectType.PREFIX)
                    .build());
        } catch (IllegalArgumentException e) {
            // 规则已存在，忽略
            Log.d(TAG, "规则已存在: /webview/");
        }

        // 默认规则：/assets/ 前缀重定向
        try {
            manager.addRule(new FileRedirectRule.Builder("/assets/", filesDir + "/assets/")
                    .redirectType(FileRedirectRule.RedirectType.PREFIX)
                    .build());
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "规则已存在: /assets/");
        }

        // 默认规则：/cache/ 前缀重定向
        try {
            manager.addRule(new FileRedirectRule.Builder("/cache/", cacheDir + "/")
                    .redirectType(FileRedirectRule.RedirectType.PREFIX)
                    .build());
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "规则已存在: /cache/");
        }

        // 默认规则：/temp/ 前缀重定向
        try {
            manager.addRule(new FileRedirectRule.Builder("/temp/", cacheDir + "/temp/")
                    .redirectType(FileRedirectRule.RedirectType.PREFIX)
                    .build());
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "规则已存在: /temp/");
        }

        // 默认规则：/preview/ 前缀重定向（用于文件预览）
        try {
            manager.addRule(new FileRedirectRule.Builder("/preview/", cacheDir + "/preview/")
                    .redirectType(FileRedirectRule.RedirectType.PREFIX)
                    .build());
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "规则已存在: /preview/");
        }

        // 默认规则：/render/ 前缀重定向（用于渲染）
        try {
            manager.addRule(new FileRedirectRule.Builder("/render/", cacheDir + "/render/")
                    .redirectType(FileRedirectRule.RedirectType.PREFIX)
                    .build());
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "规则已存在: /render/");
        }

        Log.d(TAG, "默认重定向规则已添加");
    }

    /**
     * 添加临时规则
     */
    private static void addTemporaryRules(Context context, FileRedirectManager manager) {
        String cacheDir = context.getCacheDir().getAbsolutePath();

        // 临时规则：版本信息文件
        try {
            manager.addRule(new FileRedirectRule.Builder("/version.html", cacheDir + "/version.html")
                    .redirectType(FileRedirectRule.RedirectType.EXACT)
                    .build());
            createVersionHtmlFile(context, cacheDir + "/version.html");
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "规则已存在: /version.html");
        }

        // 临时规则：WebView 介绍页面
        try {
            manager.addRule(new FileRedirectRule.Builder("/webview_intro.html", cacheDir + "/webview_intro.html")
                    .redirectType(FileRedirectRule.RedirectType.EXACT)
                    .build());
            createWebViewIntroFile(context, cacheDir + "/webview_intro.html");
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "规则已存在: /webview_intro.html");
        }

        // 临时规则：关于应用页面
        try {
            manager.addRule(new FileRedirectRule.Builder("/about_app.html", cacheDir + "/about_app.html")
                    .redirectType(FileRedirectRule.RedirectType.EXACT)
                    .build());
            createAboutAppFile(context, cacheDir + "/about_app.html");
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "规则已存在: /about_app.html");
        }
    }

    /**
     * 创建版本信息HTML文件
     */
    private static void createVersionHtmlFile(Context context, String filePath) {
        try {
            File file = new File(filePath);
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            String webViewVersion = android.os.Build.VERSION.RELEASE;
            String appVersion = getAppVersion(context);

            String htmlContent = "<!DOCTYPE html>\n" +
                    "<html>\n" +
                    "<head>\n" +
                    "    <meta charset='UTF-8'>\n" +
                    "    <title>WebView 版本信息</title>\n" +
                    "    <style>\n" +
                    "        body { font-family: sans-serif; padding: 20px; background: #f5f5f5; }\n" +
                    "        .container { max-width: 600px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n" +
                    "        h1 { color: #333; border-bottom: 2px solid #4CAF50; padding-bottom: 10px; }\n" +
                    "        .info-item { margin: 15px 0; padding: 10px; background: #f9f9f9; border-radius: 4px; }\n" +
                    "        .label { font-weight: bold; color: #666; }\n" +
                    "        .value { color: #333; margin-left: 10px; }\n" +
                    "    </style>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "    <div class='container'>\n" +
                    "        <h1>WebView 版本信息</h1>\n" +
                    "        <div class='info-item'>\n" +
                    "            <span class='label'>Android 版本:</span>\n" +
                    "            <span class='value'>" + webViewVersion + "</span>\n" +
                    "        </div>\n" +
                    "        <div class='info-item'>\n" +
                    "            <span class='label'>应用版本:</span>\n" +
                    "            <span class='value'>" + appVersion + "</span>\n" +
                    "        </div>\n" +
                    "        <div class='info-item'>\n" +
                    "            <span class='label'>WebView 实现:</span>\n" +
                    "            <span class='value'>系统 WebView</span>\n" +
                    "        </div>\n" +
                    "        <div class='info-item'>\n" +
                    "            <span class='label'>文件重定向:</span>\n" +
                    "            <span class='value'>已启用</span>\n" +
                    "        </div>\n" +
                    "    </div>\n" +
                    "</body>\n" +
                    "</html>";

            FileWriter writer = new FileWriter(file);
            writer.write(htmlContent);
            writer.close();

            Log.d(TAG, "版本信息HTML文件已创建: " + filePath);

        } catch (IOException e) {
            Log.e(TAG, "创建版本信息文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建 WebView 介绍页面
     */
    private static void createWebViewIntroFile(Context context, String filePath) {
        try {
            File file = new File(filePath);
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            String htmlContent = "<!DOCTYPE html>\n" +
                    "<html>\n" +
                    "<head>\n" +
                    "    <meta charset='UTF-8'>\n" +
                    "    <title>WebView 介绍</title>\n" +
                    "    <style>\n" +
                    "        body { font-family: sans-serif; padding: 20px; background: #f5f5f5; }\n" +
                    "        .container { max-width: 800px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n" +
                    "        h1 { color: #333; border-bottom: 2px solid #4CAF50; padding-bottom: 10px; }\n" +
                    "        p { line-height: 1.6; color: #666; }\n" +
                    "    </style>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "    <div class='container'>\n" +
                    "        <h1>WebView 功能介绍</h1>\n" +
                    "        <p>本应用使用 WebView 组件来显示网页内容和预览文件。</p>\n" +
                    "        <p>WebView 支持加载 HTML、CSS、JavaScript 等网页技术，可以显示丰富的内容。</p>\n" +
                    "        <p>当前 WebView 已启用文件重定向功能，所有文件请求都会经过重定向管理器处理。</p>\n" +
                    "    </div>\n" +
                    "</body>\n" +
                    "</html>";

            FileWriter writer = new FileWriter(file);
            writer.write(htmlContent);
            writer.close();

            Log.d(TAG, "WebView介绍文件已创建: " + filePath);

        } catch (IOException e) {
            Log.e(TAG, "创建WebView介绍文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建关于应用页面
     */
    private static void createAboutAppFile(Context context, String filePath) {
        try {
            File file = new File(filePath);
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            String appVersion = getAppVersion(context);

            String htmlContent = "<!DOCTYPE html>\n" +
                    "<html>\n" +
                    "<head>\n" +
                    "    <meta charset='UTF-8'>\n" +
                    "    <title>关于应用</title>\n" +
                    "    <style>\n" +
                    "        body { font-family: sans-serif; padding: 20px; background: #f5f5f5; }\n" +
                    "        .container { max-width: 800px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }\n" +
                    "        h1 { color: #333; border-bottom: 2px solid #4CAF50; padding-bottom: 10px; }\n" +
                    "        p { line-height: 1.6; color: #666; }\n" +
                    "    </style>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "    <div class='container'>\n" +
                    "        <h1>关于 SmartQuiz</h1>\n" +
                    "        <p>SmartQuiz 是一款专业的测验应用，帮助您创建和管理各种测验。</p>\n" +
                    "        <p>版本: " + appVersion + "</p>\n" +
                    "        <p>本应用使用 WebView 技术提供丰富的内容展示功能。</p>\n" +
                    "    </div>\n" +
                    "</body>\n" +
                    "</html>";

            FileWriter writer = new FileWriter(file);
            writer.write(htmlContent);
            writer.close();

            Log.d(TAG, "关于应用文件已创建: " + filePath);

        } catch (IOException e) {
            Log.e(TAG, "创建关于应用文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取应用版本
     */
    private static String getAppVersion(Context context) {
        try {
            android.content.pm.PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "未知";
        }
    }

    /**
     * 为特定文件路径添加临时重定向规则
     *
     * @param context     上下文
     * @param sourcePath  源路径（如 /temp/file.html）
     * @param targetPath  目标路径（实际文件路径）
     */
    public static void addTemporaryRule(Context context, String sourcePath, String targetPath) {
        try {
            FileRedirectManager manager = FileRedirectManager.getInstance();

            // 确保管理器已初始化
            if (!manager.isInitialized()) {
                prepareWebView(context);
            }

            // 添加临时规则
            manager.addRule(new FileRedirectRule.Builder(sourcePath, targetPath)
                    .redirectType(FileRedirectRule.RedirectType.EXACT)
                    .build());

            Log.d(TAG, "临时规则已添加: " + sourcePath + " -> " + targetPath);

        } catch (IllegalArgumentException e) {
            // 规则已存在，忽略
            Log.d(TAG, "规则已存在: " + sourcePath);
        } catch (Exception e) {
            Log.e(TAG, "添加临时规则失败: " + e.getMessage(), e);
        }
    }
}
