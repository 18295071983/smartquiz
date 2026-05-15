package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import com.oilquiz.app.util.AILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统资源调用工具，支持打开应用、打开URL、发送短信、拨打电话等系统级操作
 * 类似openclow功能，让Agent能够调用手机系统资源
 */
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
                    return checkAppInstalled(parameters);
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
        
        if (packageName == null) {
            packageName = APP_PACKAGE_MAP.get(appName.toLowerCase());
        }
        
        if (packageName == null) {
            return new AIToolResult("未找到应用: " + appName, parameters);
        }
        
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                
                Map<String, Object> result = new HashMap<>();
                result.put("status", "success");
                result.put("message", "已打开应用: " + appName);
                result.put("package", packageName);
                return new AIToolResult(result, parameters);
            } else {
                return new AIToolResult("应用未安装: " + packageName, parameters);
            }
        } catch (Exception e) {
            AILogger.e(TAG, "打开应用失败: " + e.getMessage());
            return new AIToolResult("打开应用失败: " + e.getMessage(), parameters);
        }
    }
    
    private AIToolResult openUrl(Map<String, Object> parameters) {
        String url = (String) parameters.get("url");
        
        if (url == null || url.isEmpty()) {
            return new AIToolResult("缺少参数: url", parameters);
        }
        
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
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
        List<Map<String, Object>> apps = new ArrayList<>();
        
        try {
            List<ApplicationInfo> packages = context.getPackageManager().getInstalledApplications(0);
            
            for (ApplicationInfo packageInfo : packages) {
                if ((packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    Map<String, Object> app = new HashMap<>();
                    app.put("name", context.getPackageManager().getApplicationLabel(packageInfo).toString());
                    app.put("package", packageInfo.packageName);
                    apps.add(app);
                }
            }
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("count", apps.size());
            result.put("apps", apps);
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
        
        if (packageName == null) {
            packageName = APP_PACKAGE_MAP.get(appName.toLowerCase());
        }
        
        if (packageName == null) {
            return new AIToolResult("未知应用: " + appName, parameters);
        }
        
        try {
            context.getPackageManager().getApplicationInfo(packageName, 0);
            
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("installed", true);
            result.put("app", appName);
            result.put("package", packageName);
            return new AIToolResult(result, parameters);
            
        } catch (PackageManager.NameNotFoundException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("installed", false);
            result.put("app", appName);
            result.put("package", packageName);
            return new AIToolResult(result, parameters);
        }
    }
    
    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("action", "操作类型: open_app, open_url, send_sms, make_call, send_email, open_map, share_text, open_settings, list_apps, check_app");
        descriptions.put("app", "应用名称（用于open_app和check_app操作，如：微信、QQ、支付宝）");
        descriptions.put("package", "应用包名（用于open_app和check_app操作）");
        descriptions.put("url", "网址链接（用于open_url操作）");
        descriptions.put("phone", "电话号码（用于send_sms和make_call操作）");
        descriptions.put("message", "短信内容（用于send_sms操作）");
        descriptions.put("to", "收件人邮箱（用于send_email操作）");
        descriptions.put("subject", "邮件主题（用于send_email操作）");
        descriptions.put("body", "邮件正文（用于send_email操作）");
        descriptions.put("location", "位置坐标（用于open_map操作）");
        descriptions.put("address", "地址（用于open_map操作）");
        descriptions.put("text", "分享内容（用于share_text操作）");
        descriptions.put("title", "分享标题（用于share_text操作）");
        descriptions.put("setting", "设置项（用于open_settings操作：wifi, bluetooth, location, display, sound, storage, app, battery）");
        return descriptions;
    }
}