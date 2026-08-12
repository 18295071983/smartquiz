package com.oilquiz.app.ai.tool;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;
import com.oilquiz.app.util.AILogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tool(
    value = "system_resource",
    description = "系统资源调用工具，支持打开应用、打开URL、发送短信、拨打电话、控制应用、执行Shell命令、读写系统设置等系统级操作",
    category = "system",
    aliases = {"open_app", "send_sms", "make_call", "launch_app"},
    actions = {
        @Action(name = "open_app", description = "打开指定应用"),
        @Action(name = "open_url", description = "打开URL"),
        @Action(name = "send_sms", description = "发送短信"),
        @Action(name = "make_call", description = "拨打电话"),
        @Action(name = "list_apps", description = "列出已安装应用"),
        @Action(name = "get_app_info", description = "获取应用信息"),
        @Action(name = "app_control", description = "控制应用：强制停止/清除数据/获取详细信息"),
        @Action(name = "shell_command", description = "执行Shell命令"),
        @Action(name = "read_setting", description = "读取系统设置"),
        @Action(name = "write_setting", description = "修改系统设置"),
        @Action(name = "get_current_app", description = "获取当前前台应用信息")
    },
    params = {
        @Param(name = "action", type = "string", description = "操作类型", required = true),
        @Param(name = "app", type = "string", description = "应用名称或包名", required = false),
        @Param(name = "url", type = "string", description = "URL地址", required = false),
        @Param(name = "phone", type = "string", description = "电话号码", required = false),
        @Param(name = "message", type = "string", description = "短信内容", required = false),
        @Param(name = "command", type = "string", description = "Shell命令", required = false),
        @Param(name = "setting_type", type = "string", description = "设置类型: system/secure/global", required = false),
        @Param(name = "setting_key", type = "string", description = "设置键名", required = false),
        @Param(name = "setting_value", type = "string", description = "设置值", required = false),
        @Param(name = "control_action", type = "string", description = "控制操作: force_stop/clear_data/detailed_info", required = false)
    }
)
public class SystemResourceTool implements AITool {
    private static final String TAG = "SystemResourceTool";
    private final Context context;
    
    private static final Map<String, String> APP_PACKAGE_MAP = new HashMap<>();
    static {
        APP_PACKAGE_MAP.put("微信", "com.tencent.mm");
        APP_PACKAGE_MAP.put("wechat", "com.tencent.mm");
        APP_PACKAGE_MAP.put("qq", "com.tencent.mobileqq");
        APP_PACKAGE_MAP.put("QQ", "com.tencent.mobileqq");
        APP_PACKAGE_MAP.put("支付宝", "com.eg.android.AlipayGphone");
        APP_PACKAGE_MAP.put("alipay", "com.eg.android.AlipayGphone");
        APP_PACKAGE_MAP.put("淘宝", "com.taobao.taobao");
        APP_PACKAGE_MAP.put("taobao", "com.taobao.taobao");
        APP_PACKAGE_MAP.put("京东", "com.jingdong.app.mall");
        APP_PACKAGE_MAP.put("jd", "com.jingdong.app.mall");
        APP_PACKAGE_MAP.put("微博", "com.sina.weibo");
        APP_PACKAGE_MAP.put("weibo", "com.sina.weibo");
        APP_PACKAGE_MAP.put("抖音", "com.ss.android.ugc.trill");
        APP_PACKAGE_MAP.put("douyin", "com.ss.android.ugc.trill");
        APP_PACKAGE_MAP.put("快手", "com.kuaishou.nebula");
        APP_PACKAGE_MAP.put("kuaishou", "com.kuaishou.nebula");
        APP_PACKAGE_MAP.put("浏览器", "com.android.browser");
        APP_PACKAGE_MAP.put("browser", "com.android.browser");
        APP_PACKAGE_MAP.put("相机", "com.android.camera");
        APP_PACKAGE_MAP.put("camera", "com.android.camera");
        APP_PACKAGE_MAP.put("设置", "com.android.settings");
        APP_PACKAGE_MAP.put("settings", "com.android.settings");
        APP_PACKAGE_MAP.put("地图", "com.autonavi.minimap");
        APP_PACKAGE_MAP.put("高德地图", "com.autonavi.minimap");
        APP_PACKAGE_MAP.put("amap", "com.autonavi.minimap");
        APP_PACKAGE_MAP.put("百度地图", "com.baidu.BaiduMap");
        APP_PACKAGE_MAP.put("baidu map", "com.baidu.BaiduMap");
        APP_PACKAGE_MAP.put("音乐", "com.android.music");
        APP_PACKAGE_MAP.put("music", "com.android.music");
        APP_PACKAGE_MAP.put("视频", "com.android.video");
        APP_PACKAGE_MAP.put("video", "com.android.video");
        APP_PACKAGE_MAP.put("日历", "com.android.calendar");
        APP_PACKAGE_MAP.put("calendar", "com.android.calendar");
        APP_PACKAGE_MAP.put("联系人", "com.android.contacts");
        APP_PACKAGE_MAP.put("contacts", "com.android.contacts");
        APP_PACKAGE_MAP.put("短信", "com.android.mms");
        APP_PACKAGE_MAP.put("sms", "com.android.mms");
        APP_PACKAGE_MAP.put("电话", "com.android.phone");
        APP_PACKAGE_MAP.put("phone", "com.android.phone");
        APP_PACKAGE_MAP.put("邮件", "com.android.email");
        APP_PACKAGE_MAP.put("email", "com.android.email");
        APP_PACKAGE_MAP.put("微信支付", "com.tencent.mm");
        APP_PACKAGE_MAP.put("滴滴", "com.sdu.didi.psnger");
        APP_PACKAGE_MAP.put("didi", "com.sdu.didi.psnger");
        APP_PACKAGE_MAP.put("美团", "com.meituan.meituan");
        APP_PACKAGE_MAP.put("meituan", "com.meituan.meituan");
        APP_PACKAGE_MAP.put("饿了么", "me.ele");
        APP_PACKAGE_MAP.put("eleme", "me.ele");
        APP_PACKAGE_MAP.put("携程", "ctrip.android.view");
        APP_PACKAGE_MAP.put("ctrip", "ctrip.android.view");
        APP_PACKAGE_MAP.put("大众点评", "com.dianping.v1");
        APP_PACKAGE_MAP.put("dianping", "com.dianping.v1");
    }
    
    public SystemResourceTool(Context context) {
        this.context = context.getApplicationContext();
    }
    
    @Override
    public String getName() {
        return "system_resource";
    }
    
    @Override
    public String getDescription() {
        return "系统资源调用工具，支持打开应用、打开URL、发送短信、拨打电话、发送邮件、打开地图、分享内容等操作";
    }
    
    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        normalizeParameters(parameters);
        
        try {
            String action = (String) parameters.get("action");
            if (action == null) {
                return new AIToolResult("缺少参数: action", parameters);
            }
            
            switch (action) {
                case "open_app":
                    return openApp(parameters);
                case "open_url":
                    return openUrl(parameters);
                case "send_sms":
                    return sendSms(parameters);
                case "make_call":
                    return makeCall(parameters);
                case "send_email":
                    return sendEmail(parameters);
                case "open_map":
                    return openMap(parameters);
                case "share_text":
                    return shareText(parameters);
                case "open_settings":
                    return openSettings(parameters);
                case "list_apps":
                    return listInstalledApps();
                case "check_app":
                case "get_app_info":
                    return checkAppInstalled(parameters);
                case "app_control":
                    return appControl(parameters);
                case "shell_command":
                    return executeShellCommand(parameters);
                case "read_setting":
                    return readSetting(parameters);
                case "write_setting":
                    return writeSetting(parameters);
                case "get_current_app":
                    return getCurrentApp(parameters);
                default:
                    return new AIToolResult("未知操作: " + action, parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "执行出错: " + e.getMessage(), e);
            return new AIToolResult("错误: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult openApp(Map<String, Object> parameters) {
        String appName = (String) parameters.get("app");
        String packageName = (String) parameters.get("package");
        
        if (appName == null && packageName == null) {
            return new AIToolResult("缺少参数: app或package", parameters);
        }
        
        // 1. 如果直接给了包名，尝试打开
        if (packageName != null) {
            return tryLaunchApp(packageName, appName, parameters);
        }
        
        // 2. 特殊处理：浏览器动态检测
        String lower = appName.toLowerCase();
        if ("浏览器".equals(appName) || "browser".equals(lower) || "chrome".equals(lower)) {
            String browserPkg = findBrowserPackage();
            if (browserPkg != null) {
                return tryLaunchApp(browserPkg, appName, parameters);
            }
        }
        
        // 3. 从硬编码映射查找
        String mappedPkg = APP_PACKAGE_MAP.get(lower);
        if (mappedPkg != null) {
            return tryLaunchApp(mappedPkg, appName, parameters);
        }
        
        // 4. 从已安装应用列表模糊匹配
        String matchedPkg = findAppByFuzzyName(appName);
        if (matchedPkg != null) {
            return tryLaunchApp(matchedPkg, appName, parameters);
        }
        
        // 5. 回退：使用 Intent chooser 让系统选择
        return openAppViaChooser(appName, parameters);
    }
    
    /**
     * 尝试启动指定包名的应用
     */
    private AIToolResult tryLaunchApp(String packageName, String appName, Map<String, Object> parameters) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                
                Map<String, Object> result = new HashMap<>();
                result.put("status", "success");
                result.put("message", "已打开应用: " + (appName != null ? appName : packageName));
                result.put("package", packageName);
                return new AIToolResult(result, parameters);
            } else {
                return new AIToolResult("应用未安装或无法启动: " + packageName, parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "打开应用失败: " + e.getMessage());
            return new AIToolResult("打开应用失败: " + e.getMessage(), parameters);
        }
    }
    
    /**
     * 模糊匹配已安装应用名称（支持部分匹配、包含匹配）
     */
    private String findAppByFuzzyName(String appName) {
        if (appName == null || appName.isEmpty()) return null;
        String lowerName = appName.toLowerCase();
        PackageManager pm = context.getPackageManager();
        
        // 获取所有有 launcher 的应用
        Intent launchIntent = new Intent(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launcherApps = pm.queryIntentActivities(launchIntent, 0);
        
        String bestMatch = null;
        int bestScore = 0;
        
        for (ResolveInfo ri : launcherApps) {
            if (ri.activityInfo == null || ri.activityInfo.applicationInfo == null) continue;
            String label = pm.getApplicationLabel(ri.activityInfo.applicationInfo).toString();
            String labelLower = label.toLowerCase();
            String pkg = ri.activityInfo.packageName;
            
            int score = 0;
            // 完全匹配
            if (labelLower.equals(lowerName) || pkg.equals(lowerName)) {
                score = 100;
            }
            // 以...开头
            else if (labelLower.startsWith(lowerName) || lowerName.length() >= 2 && labelLower.contains(lowerName)) {
                score = 80;
            }
            // 应用名包含输入或输入包含应用名
            else if (labelLower.contains(lowerName) || lowerName.contains(labelLower)) {
                score = 60;
            }
            // 逐字符匹配（中文按字匹配）
            else {
                int matchCount = 0;
                for (int i = 0; i < lowerName.length(); i++) {
                    if (labelLower.indexOf(lowerName.charAt(i)) >= 0) matchCount++;
                }
                if (lowerName.length() > 0 && matchCount >= lowerName.length() * 0.6) {
                    score = 30;
                }
            }
            
            if (score > bestScore) {
                bestScore = score;
                bestMatch = pkg;
            }
        }
        
        // 至少需要30分才算匹配成功
        return bestScore >= 30 ? bestMatch : null;
    }
    
    /**
     * 回退方案：使用 Intent chooser 让系统选择可处理的应用
     */
    private AIToolResult openAppViaChooser(String appName, Map<String, Object> parameters) {
        try {
            // 尝试用应用市场搜索
            Intent marketIntent = new Intent(Intent.ACTION_VIEW, 
                    Uri.parse("market://search?q=" + Uri.encode(appName)));
            marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            
            // 先尝试用应用名作为搜索关键词，让系统选择
            // 如果没有任何应用可以处理，则提示用户
            PackageManager pm = context.getPackageManager();
            if (marketIntent.resolveActivity(pm) != null) {
                context.startActivity(marketIntent);
                Map<String, Object> result = new HashMap<>();
                result.put("status", "fallback");
                result.put("message", "未找到\"" + appName + "\"，已打开应用商店搜索");
                result.put("suggestion", "请从搜索结果中安装或选择应用");
                return new AIToolResult(result, parameters);
            }
            
            // 最终回退：列出可能的匹配应用供用户参考
            List<String> similarApps = findSimilarApps(appName, 5);
            Map<String, Object> result = new HashMap<>();
            result.put("status", "not_found");
            result.put("message", "未找到应用: " + appName);
            if (!similarApps.isEmpty()) {
                result.put("similar_apps", similarApps);
                result.put("suggestion", "您可能想打开以下应用之一，请指定包名重试");
            } else {
                result.put("suggestion", "设备上没有匹配的应用，请先安装");
            }
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "回退打开应用失败: " + e.getMessage());
            return new AIToolResult("未找到应用: " + appName + "，且回退方案失败", parameters);
        }
    }
    
    /**
     * 查找名称相似的应用列表
     */
    private List<String> findSimilarApps(String appName, int maxResults) {
        List<String> results = new ArrayList<>();
        if (appName == null || appName.isEmpty()) return results;
        String lowerName = appName.toLowerCase();
        PackageManager pm = context.getPackageManager();
        
        Intent launchIntent = new Intent(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launcherApps = pm.queryIntentActivities(launchIntent, 0);
        
        for (ResolveInfo ri : launcherApps) {
            if (ri.activityInfo == null || ri.activityInfo.applicationInfo == null) continue;
            String label = pm.getApplicationLabel(ri.activityInfo.applicationInfo).toString();
            String labelLower = label.toLowerCase();
            // 有任何字符匹配就加入候选
            for (int i = 0; i < lowerName.length(); i++) {
                if (labelLower.indexOf(lowerName.charAt(i)) >= 0) {
                    results.add(label + " (" + ri.activityInfo.packageName + ")");
                    break;
                }
            }
            if (results.size() >= maxResults) break;
        }
        return results;
    }
    
    private AIToolResult openUrl(Map<String, Object> parameters) {
        String url = (String) parameters.get("url");
        
        if (url == null || url.isEmpty()) {
            return new AIToolResult("缺少参数: url", parameters);
        }
        
        try {
            // 正确编码 URL 中的非 ASCII 字符，避免浏览器错误 Punycode 编码
            String encodedUrl = encodeInternationalUrl(url);
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(encodedUrl));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开链接: " + url);
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "打开链接失败: " + e.getMessage());
            return new AIToolResult("打开链接失败: " + e.getMessage(), parameters);
        }
    }
    
    /**
     * 编码含非 ASCII 字符的 URL：域名用 IDN.toASCII，路径用百分号编码
     */
    private static String encodeInternationalUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        boolean hasNonAscii = false;
        for (int i = 0; i < url.length(); i++) {
            if (url.charAt(i) > 127) { hasNonAscii = true; break; }
        }
        if (!hasNonAscii) return url;
        
        try {
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0) return url;
            String scheme = url.substring(0, schemeEnd);
            String rest = url.substring(schemeEnd + 3);
            
            int pathStart = rest.indexOf('/');
            int queryStart = rest.indexOf('?');
            int fragStart = rest.indexOf('#');
            
            int authorityEnd = rest.length();
            if (pathStart >= 0) authorityEnd = pathStart;
            else if (queryStart >= 0) authorityEnd = queryStart;
            else if (fragStart >= 0) authorityEnd = fragStart;
            
            String authority = rest.substring(0, authorityEnd);
            String remainder = rest.substring(authorityEnd);
            
            // 处理 host
            String host = authority;
            String port = "";
            int colonIdx = authority.lastIndexOf(':');
            if (colonIdx >= 0) {
                String possiblePort = authority.substring(colonIdx + 1);
                boolean isPort = true;
                for (int i = 0; i < possiblePort.length(); i++) {
                    if (!Character.isDigit(possiblePort.charAt(i))) { isPort = false; break; }
                }
                if (isPort && !possiblePort.isEmpty()) {
                    host = authority.substring(0, colonIdx);
                    port = ":" + possiblePort;
                }
            }
            
            String encodedHost;
            try { encodedHost = java.net.IDN.toASCII(host); }
            catch (Exception e) { encodedHost = host; }
            
            // 编码路径中的非 ASCII 字符
            StringBuilder encodedRemainder = new StringBuilder(remainder.length());
            for (int i = 0; i < remainder.length(); i++) {
                char c = remainder.charAt(i);
                if (c > 127) {
                    byte[] bytes = String.valueOf(c).getBytes("UTF-8");
                    for (byte b : bytes) encodedRemainder.append(String.format("%%%.2X", b & 0xFF));
                } else if (c == ' ') {
                    encodedRemainder.append("%20");
                } else {
                    encodedRemainder.append(c);
                }
            }
            
            return scheme + "://" + encodedHost + port + encodedRemainder;
        } catch (Exception e) {
            return url;
        }
    }
    
    private AIToolResult sendSms(Map<String, Object> parameters) {
        String phone = (String) parameters.get("phone");
        String message = (String) parameters.get("message");
        
        if (phone == null || phone.isEmpty()) {
            return new AIToolResult("缺少参数: phone", parameters);
        }
        
        try {
            Uri smsUri = Uri.parse("smsto:" + phone);
            Intent intent = new Intent(Intent.ACTION_SENDTO, smsUri);
            if (message != null && !message.isEmpty()) {
                intent.putExtra("sms_body", message);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开短信发送界面");
            result.put("phone", phone);
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "发送短信失败: " + e.getMessage());
            return new AIToolResult("发送短信失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult makeCall(Map<String, Object> parameters) {
        String phone = (String) parameters.get("phone");
        
        if (phone == null || phone.isEmpty()) {
            return new AIToolResult("缺少参数: phone", parameters);
        }
        
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开拨号界面");
            result.put("phone", phone);
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "拨打电话失败: " + e.getMessage());
            return new AIToolResult("拨打电话失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult sendEmail(Map<String, Object> parameters) {
        String to = (String) parameters.get("to");
        String subject = (String) parameters.get("subject");
        String body = (String) parameters.get("body");
        
        if (to == null || to.isEmpty()) {
            return new AIToolResult("缺少参数: to", parameters);
        }
        
        try {
            Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + to));
            if (subject != null) {
                intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            }
            if (body != null) {
                intent.putExtra(Intent.EXTRA_TEXT, body);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开邮件发送界面");
            result.put("to", to);
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "发送邮件失败: " + e.getMessage());
            return new AIToolResult("发送邮件失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult openMap(Map<String, Object> parameters) {
        String location = (String) parameters.get("location");
        String address = (String) parameters.get("address");
        
        if (location == null && address == null) {
            return new AIToolResult("缺少参数: location或address", parameters);
        }
        
        String query = location != null ? location : address;
        
        try {
            Uri mapUri = Uri.parse("geo:0,0?q=" + Uri.encode(query));
            Intent intent = new Intent(Intent.ACTION_VIEW, mapUri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开地图: " + query);
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "打开地图失败: " + e.getMessage());
            return new AIToolResult("打开地图失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult shareText(Map<String, Object> parameters) {
        String text = (String) parameters.get("text");
        String title = (String) parameters.get("title");
        
        if (text == null || text.isEmpty()) {
            return new AIToolResult("缺少参数: text", parameters);
        }
        
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            if (title != null) {
                intent.putExtra(Intent.EXTRA_TITLE, title);
            }
            
            Intent chooser = Intent.createChooser(intent, title != null ? title : "分享");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开分享界面");
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "分享失败: " + e.getMessage());
            return new AIToolResult("分享失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult openSettings(Map<String, Object> parameters) {
        String setting = (String) parameters.get("setting");
        
        Intent intent;
        if (setting != null && !setting.isEmpty()) {
            switch (setting.toLowerCase()) {
                case "wifi":
                    intent = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
                    break;
                case "bluetooth":
                    intent = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                    break;
                case "location":
                    intent = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    break;
                case "display":
                    intent = new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS);
                    break;
                case "sound":
                    intent = new Intent(android.provider.Settings.ACTION_SOUND_SETTINGS);
                    break;
                case "storage":
                    intent = new Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
                    break;
                case "app":
                    intent = new Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
                    break;
                case "battery":
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        intent = new Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS);
                    } else {
                        intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                    }
                    break;
                default:
                    intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                    break;
            }
        } else {
            intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
        }
        
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        
        try {
            context.startActivity(intent);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("message", "已打开设置界面: " + (setting != null ? setting : "系统设置"));
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "打开设置失败: " + e.getMessage());
            return new AIToolResult("打开设置失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult listInstalledApps() {
        List<Map<String, Object>> userApps = new ArrayList<>();
        List<Map<String, Object>> systemApps = new ArrayList<>();
        
        try {
            PackageManager pm = context.getPackageManager();
            
            // 获取所有有 launcher 图标的应用包名集合（用于标记可启动的应用）
            Intent launchIntent = new Intent(Intent.ACTION_MAIN);
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> launcherApps = pm.queryIntentActivities(launchIntent, 0);
            java.util.Set<String> launcherPackages = new java.util.HashSet<>();
            for (ResolveInfo ri : launcherApps) {
                if (ri.activityInfo != null) {
                    launcherPackages.add(ri.activityInfo.packageName);
                }
            }
            
            // 返回所有已安装应用
            List<ApplicationInfo> packages = pm.getInstalledApplications(0);
            for (ApplicationInfo packageInfo : packages) {
                boolean isSystem = (packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean hasLauncher = launcherPackages.contains(packageInfo.packageName);
                boolean isUpdated = (packageInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                
                // 包含规则：
                // 1. 非系统应用（第三方应用）→ 无条件包含
                // 2. 有 launcher 图标的系统应用 → 包含
                // 3. 用户更新过的系统应用 → 包含
                // 4. 纯系统服务包（无 launcher、未更新）→ 跳过
                if (isSystem && !hasLauncher && !isUpdated) {
                    continue;
                }
                
                Map<String, Object> app = new HashMap<>();
                String label = pm.getApplicationLabel(packageInfo).toString();
                app.put("name", label);
                app.put("package", packageInfo.packageName);
                if (!isSystem) {
                    userApps.add(app);
                } else {
                    systemApps.add(app);
                }
            }
            
            // 按名称排序
            java.util.Comparator<Map<String, Object>> byName = (a, b) -> 
                    ((String) a.get("name")).compareTo((String) b.get("name"));
            java.util.Collections.sort(userApps, byName);
            java.util.Collections.sort(systemApps, byName);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("user_app_count", userApps.size());
            result.put("system_app_count", systemApps.size());
            result.put("total_count", userApps.size() + systemApps.size());
            // 用户应用全量返回
            result.put("user_apps", userApps);
            // 系统应用全量返回（不再截断）
            result.put("system_apps", systemApps);
            return new AIToolResult(result, new HashMap<>());
            
        } catch (Exception e) {
            AILogger.e(TAG, "获取应用列表失败: " + e.getMessage());
            return new AIToolResult("获取应用列表失败: " + e.getMessage(), new HashMap<>());
        }
    }
    
    private AIToolResult checkAppInstalled(Map<String, Object> parameters) {
        String appName = (String) parameters.get("app");
        String packageName = (String) parameters.get("package");
        
        if (appName == null && packageName == null) {
            return new AIToolResult("缺少参数: app或package", parameters);
        }
        
        // 特殊处理：浏览器动态检测
        if (packageName == null && appName != null) {
            String lower = appName.toLowerCase();
            if ("浏览器".equals(appName) || "browser".equals(lower) || "chrome".equals(lower)) {
                return checkBrowserInstalled(appName);
            }
            // 硬编码映射
            packageName = APP_PACKAGE_MAP.get(lower);
        }
        
        // 如果硬编码映射找到了，直接检查
        if (packageName != null) {
            try {
                context.getPackageManager().getApplicationInfo(packageName, 0);
                Map<String, Object> result = new HashMap<>();
                result.put("status", "success");
                result.put("installed", true);
                result.put("app", appName);
                result.put("package", packageName);
                return new AIToolResult(result, parameters);
            } catch (PackageManager.NameNotFoundException e) {
                // 包名存在但没安装
            }
        }
        
        // 模糊匹配已安装应用
        if (appName != null) {
            String matchedPkg = findAppByFuzzyName(appName);
            if (matchedPkg != null) {
                Map<String, Object> result = new HashMap<>();
                result.put("status", "success");
                result.put("installed", true);
                result.put("app", appName);
                result.put("package", matchedPkg);
                result.put("match_type", "fuzzy");
                return new AIToolResult(result, parameters);
            }
        }
        
        // 未找到
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("installed", false);
        result.put("app", appName);
        result.put("package", packageName);
        return new AIToolResult(result, parameters);
    }

    /**
     * 动态检测已安装的浏览器应用（不依赖硬编码包名）
     */
    private AIToolResult checkBrowserInstalled(String appName) {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"));
            List<ResolveInfo> browsers = context.getPackageManager().queryIntentActivities(browserIntent, 0);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("installed", !browsers.isEmpty());
            result.put("app", appName);
            
            if (!browsers.isEmpty()) {
                List<String> browserNames = new ArrayList<>();
                for (ResolveInfo ri : browsers) {
                    String name = ri.loadLabel(context.getPackageManager()).toString();
                    String pkg = ri.activityInfo.packageName;
                    browserNames.add(name + " (" + pkg + ")");
                }
                result.put("browsers", browserNames);
                result.put("count", browsers.size());
                result.put("message", "已安装 " + browsers.size() + " 个浏览器");
            } else {
                result.put("message", "未检测到浏览器应用");
            }
            return new AIToolResult(result, null);
        } catch (Exception e) {
            return new AIToolResult("检测浏览器失败: " + e.getMessage(), null);
        }
    }

    /**
     * 查找已安装的浏览器包名
     */
    private String findBrowserPackage() {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"));
            List<ResolveInfo> browsers = context.getPackageManager().queryIntentActivities(browserIntent, 0);
            if (!browsers.isEmpty()) {
                // 优先返回第一个（通常是默认浏览器）
                return browsers.get(0).activityInfo.packageName;
            }
        } catch (Exception ignored) {}
        return null;
    }
    
    // ==================== 新增：应用控制、Shell命令、系统设置 ====================
    
    /**
     * 应用控制：强制停止、清除数据、获取详细信息
     */
    private AIToolResult appControl(Map<String, Object> parameters) {
        String appName = (String) parameters.get("app");
        String packageName = (String) parameters.get("package");
        String controlAction = (String) parameters.get("control_action");
        
        if (controlAction == null || controlAction.isEmpty()) {
            return new AIToolResult("缺少参数: control_action (force_stop/clear_data/detailed_info)", parameters);
        }
        if (appName == null && packageName == null) {
            return new AIToolResult("缺少参数: app或package", parameters);
        }
        
        // 解析包名
        if (packageName == null) {
            packageName = APP_PACKAGE_MAP.get(appName.toLowerCase());
            if (packageName == null) {
                packageName = findAppByFuzzyName(appName);
            }
            if (packageName == null && appName != null && appName.contains(".")) {
                packageName = appName;
            }
        }
        if (packageName == null) {
            return new AIToolResult("未找到应用: " + appName, parameters);
        }
        
        PackageManager pm = context.getPackageManager();
        
        try {
            switch (controlAction) {
                case "force_stop": {
                    // 使用 ActivityManager 强制停止应用
                    ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                    am.killBackgroundProcesses(packageName);
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "success");
                    result.put("message", "已强制停止应用: " + packageName);
                    result.put("package", packageName);
                    return new AIToolResult(result, parameters);
                }
                case "clear_data": {
                    // 使用 pm clear 命令清除应用数据
                    String output = executeShell("pm clear " + packageName);
                    Map<String, Object> result = new HashMap<>();
                    if (output != null && output.contains("Success")) {
                        result.put("status", "success");
                        result.put("message", "已清除应用数据: " + packageName);
                    } else {
                        result.put("status", "failed");
                        result.put("message", "清除应用数据失败: " + (output != null ? output : "未知错误"));
                    }
                    result.put("package", packageName);
                    return new AIToolResult(result, parameters);
                }
                case "detailed_info": {
                    PackageInfo pkgInfo = pm.getPackageInfo(packageName, 0);
                    ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("status", "success");
                    result.put("package", packageName);
                    result.put("name", pm.getApplicationLabel(appInfo).toString());
                    result.put("version_name", pkgInfo.versionName != null ? pkgInfo.versionName : "unknown");
                    @SuppressWarnings("deprecation")
                    long vCode = pkgInfo.versionCode;
                    result.put("version_code", String.valueOf(vCode));
                    result.put("target_sdk", String.valueOf(appInfo.targetSdkVersion));
                    result.put("source_dir", appInfo.sourceDir);
                    result.put("data_dir", appInfo.dataDir);
                    result.put("is_system", (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    result.put("enabled", appInfo.enabled);
                    
                    // 获取安装时间
                    result.put("install_time", String.valueOf(pkgInfo.firstInstallTime));
                    result.put("update_time", String.valueOf(pkgInfo.lastUpdateTime));
                    
                    return new AIToolResult(result, parameters);
                }
                default:
                    return new AIToolResult("未知控制操作: " + controlAction + "，支持: force_stop/clear_data/detailed_info", parameters);
            }
        } catch (PackageManager.NameNotFoundException e) {
            return new AIToolResult("应用未安装: " + packageName, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "应用控制失败: " + e.getMessage());
            return new AIToolResult("应用控制失败: " + e.getMessage(), parameters);
        }
    }
    
    /**
     * 执行 Shell 命令
     */
    private AIToolResult executeShellCommand(Map<String, Object> parameters) {
        String command = (String) parameters.get("command");
        
        if (command == null || command.isEmpty()) {
            return new AIToolResult("缺少参数: command", parameters);
        }
        
        // 安全检查：禁止危险命令
        String lowerCmd = command.toLowerCase().trim();
        if (lowerCmd.startsWith("rm ") || lowerCmd.startsWith("rm -") ||
            lowerCmd.startsWith("format") || lowerCmd.startsWith("factory") ||
            lowerCmd.contains("reboot") || lowerCmd.contains("shutdown") ||
            lowerCmd.contains("su ") || lowerCmd.contains("&& rm")) {
            return new AIToolResult("安全限制：不允许执行危险命令: " + command, parameters);
        }
        
        try {
            String output = executeShell(command);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("command", command);
            result.put("output", output != null ? output : "(无输出)");
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            AILogger.e(TAG, "执行命令失败: " + e.getMessage());
            return new AIToolResult("执行命令失败: " + e.getMessage(), parameters);
        }
    }
    
    /**
     * 执行 Shell 命令并返回输出
     */
    private String executeShell(String command) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", command});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            reader.close();
            
            // 读取错误输出
            BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            StringBuilder errOutput = new StringBuilder();
            while ((line = errReader.readLine()) != null) {
                errOutput.append(line).append("\n");
            }
            errReader.close();
            
            String out = output.toString().trim();
            String err = errOutput.toString().trim();
            
            if (out.isEmpty() && !err.isEmpty()) {
                return "错误: " + err;
            }
            return out.isEmpty() ? "(无输出)" : out;
        } catch (Exception e) {
            return "执行失败: " + e.getMessage();
        }
    }
    
    /**
     * 读取系统设置
     */
    private AIToolResult readSetting(Map<String, Object> parameters) {
        String settingType = (String) parameters.get("setting_type");
        String settingKey = (String) parameters.get("setting_key");
        
        if (settingKey == null || settingKey.isEmpty()) {
            return new AIToolResult("缺少参数: setting_key", parameters);
        }
        if (settingType == null || settingType.isEmpty()) {
            settingType = "system";
        }
        
        try {
            String value;
            switch (settingType.toLowerCase()) {
                case "system":
                    value = Settings.System.getString(context.getContentResolver(), settingKey);
                    break;
                case "secure":
                    value = Settings.Secure.getString(context.getContentResolver(), settingKey);
                    break;
                case "global":
                    value = Settings.Global.getString(context.getContentResolver(), settingKey);
                    break;
                default:
                    return new AIToolResult("未知设置类型: " + settingType + "，支持: system/secure/global", parameters);
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("type", settingType);
            result.put("key", settingKey);
            result.put("value", value != null ? value : "(未设置)");
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("读取设置失败: " + e.getMessage(), parameters);
        }
    }
    
    /**
     * 修改系统设置
     */
    private AIToolResult writeSetting(Map<String, Object> parameters) {
        String settingType = (String) parameters.get("setting_type");
        String settingKey = (String) parameters.get("setting_key");
        String settingValue = (String) parameters.get("setting_value");
        
        if (settingKey == null || settingKey.isEmpty()) {
            return new AIToolResult("缺少参数: setting_key", parameters);
        }
        if (settingValue == null) {
            return new AIToolResult("缺少参数: setting_value", parameters);
        }
        if (settingType == null || settingType.isEmpty()) {
            settingType = "system";
        }
        
        try {
            boolean success;
            switch (settingType.toLowerCase()) {
                case "system":
                    success = Settings.System.putString(context.getContentResolver(), settingKey, settingValue);
                    break;
                case "secure":
                    success = Settings.Secure.putString(context.getContentResolver(), settingKey, settingValue);
                    break;
                case "global":
                    success = Settings.Global.putString(context.getContentResolver(), settingKey, settingValue);
                    break;
                default:
                    return new AIToolResult("未知设置类型: " + settingType + "，支持: system/secure/global", parameters);
            }
            
            Map<String, Object> result = new HashMap<>();
            if (success) {
                result.put("status", "success");
                result.put("message", "已修改设置: " + settingKey + " = " + settingValue);
            } else {
                result.put("status", "failed");
                result.put("message", "修改设置失败，可能缺少 WRITE_SETTINGS 权限");
            }
            result.put("type", settingType);
            result.put("key", settingKey);
            result.put("value", settingValue);
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("修改设置失败: " + e.getMessage(), parameters);
        }
    }
    
    /**
     * 获取当前前台应用信息
     */
    private AIToolResult getCurrentApp(Map<String, Object> parameters) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            
            if (processes != null && !processes.isEmpty()) {
                List<Map<String, Object>> foregroundApps = new ArrayList<>();
                for (ActivityManager.RunningAppProcessInfo proc : processes) {
                    if (proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        Map<String, Object> appInfo = new HashMap<>();
                        appInfo.put("package", proc.processName);
                        appInfo.put("importance", "foreground");
                        try {
                            String label = context.getPackageManager().getApplicationLabel(
                                context.getPackageManager().getApplicationInfo(proc.processName, 0)
                            ).toString();
                            appInfo.put("name", label);
                        } catch (Exception ignored) {}
                        foregroundApps.add(appInfo);
                    }
                }
                result.put("foreground_apps", foregroundApps);
                result.put("count", foregroundApps.size());
            } else {
                result.put("message", "无法获取前台应用列表");
            }
            
            return new AIToolResult(result, parameters);
        } catch (Exception e) {
            return new AIToolResult("获取前台应用失败: " + e.getMessage(), parameters);
        }
    }
    
    private void normalizeParameters(Map<String, Object> parameters) {
        if (parameters == null) return;
        
        if (parameters.containsKey("app_name") && !parameters.containsKey("app")) {
            parameters.put("app", parameters.get("app_name"));
        }
        if (parameters.containsKey("phone_number") && !parameters.containsKey("phone")) {
            parameters.put("phone", parameters.get("phone_number"));
        }
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: open_app/open_url/send_sms/make_call/send_email/open_map/share_text/open_settings/list_apps/check_app/get_app_info/app_control/shell_command/read_setting/write_setting/get_current_app");
        descriptions.put("app", "应用名称或包名（支持模糊匹配）");
        descriptions.put("package", "应用包名");
        descriptions.put("url", "网址链接");
        descriptions.put("phone", "电话号码");
        descriptions.put("message", "短信内容");
        descriptions.put("command", "Shell命令（如: pm list packages, dumpsys activity top, input tap 500 500）");
        descriptions.put("setting_type", "设置类型: system/secure/global");
        descriptions.put("setting_key", "设置键名（如: screen_brightness, wifi_on, airplane_mode_on）");
        descriptions.put("setting_value", "设置值");
        descriptions.put("control_action", "应用控制操作: force_stop(强制停止)/clear_data(清除数据)/detailed_info(详细信息)");
        descriptions.put("to", "收件人邮箱");
        descriptions.put("subject", "邮件主题");
        descriptions.put("body", "邮件正文");
        descriptions.put("location", "位置坐标");
        descriptions.put("address", "地址");
        descriptions.put("text", "分享内容");
        descriptions.put("title", "分享标题");
        descriptions.put("setting", "设置页: wifi/bluetooth/location/display/sound/storage/app/battery");
        return descriptions;
    }
}