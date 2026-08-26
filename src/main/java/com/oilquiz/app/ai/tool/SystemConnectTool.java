package com.oilquiz.app.ai.tool;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import com.oilquiz.app.ai.tool.annotation.Action;
import com.oilquiz.app.ai.tool.annotation.Param;
import com.oilquiz.app.ai.tool.annotation.Tool;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统级连接工具：系统级 UI（悬浮窗/通知/截图）、系统级数据（剪贴板/电池/网络/音量/亮度）、
 * 系统级连接（WiFi/蓝牙/热点/USB）。比 system_resource 更聚焦系统能力与设备连接管理。
 */
@Tool(
    value = "system_connect",
    description = "系统级连接与设备能力工具。"
            + "【系统级UI】notify(系统通知栏)/floating_window(悬浮窗,需悬浮窗权限)/toast(屏幕提示)/screenshot(截屏)。"
            + "【系统级数据】clipboard(剪贴板读写)/battery(电池状态)/network(网络状态/数据开关)/volume(音量控制)/brightness(亮度调节)。"
            + "【系统级连接】wifi(WiFi信息/开关)/bluetooth(蓝牙开关/扫描)/hotspot(热点开关)/usb(USB连接状态)/screen(屏幕状态/点亮/常亮)。"
            + "设备连接与系统参数管理统一走本工具；需要跳转系统设置页用 system_resource(open_settings)。",
    category = "system",
    aliases = {"system_connect", "sys_connect", "设备连接", "系统连接", "system_ui", "系统UI"},
    actions = {
        // ========== 系统级 UI ==========
        @Action(name = "notify", description = "发系统通知栏通知",
            params = {
                @Param(name = "title", type = "string", description = "通知标题", required = true),
                @Param(name = "content", type = "string", description = "通知内容", required = false),
                @Param(name = "id", type = "integer", description = "通知ID（可选，默认时间戳）", required = false)
            }),
        @Action(name = "toast", description = "屏幕 Toast 提示",
            params = {
                @Param(name = "text", type = "string", description = "提示文本", required = true),
                @Param(name = "long", type = "boolean", description = "是否长显示（默认false）", required = false)
            }),
        @Action(name = "floating_window", description = "创建悬浮窗（需授权悬浮窗权限，无权限自动跳设置页）",
            params = {
                @Param(name = "text", type = "string", description = "悬浮窗文字内容", required = true),
                @Param(name = "x", type = "integer", description = "初始X坐标（可选）", required = false),
                @Param(name = "y", type = "integer", description = "初始Y坐标（可选）", required = false)
            }),
        @Action(name = "close_floating", description = "关闭悬浮窗"),
        @Action(name = "screenshot", description = "截屏并保存到工作区",
            params = {
                @Param(name = "path", type = "string", description = "保存路径（可选，默认工作区files/screenshot.png）", required = false)
            }),
        // ========== 系统级数据 ==========
        @Action(name = "clipboard", description = "读写剪贴板",
            params = {
                @Param(name = "op", type = "string", description = "操作: read(读)/write(写)", required = true),
                @Param(name = "text", type = "string", description = "写入内容（write用）", required = false)
            }),
        @Action(name = "battery", description = "查询电池状态（电量/充电状态/温度/电压）"),
        @Action(name = "network", description = "网络状态/数据开关",
            params = {
                @Param(name = "op", type = "string", description = "操作: status(状态)/mobile(移动数据开关)", required = false),
                @Param(name = "enable", type = "boolean", description = "开关值（mobile用）", required = false)
            }),
        @Action(name = "volume", description = "音量控制",
            params = {
                @Param(name = "stream", type = "string", description = "类型: music(媒体,默认)/ring(铃声)/alarm(闹钟)/notification(通知)", required = false),
                @Param(name = "op", type = "string", description = "操作: get(查询,默认)/set(设置)/up(加)/down(减)", required = false),
                @Param(name = "value", type = "integer", description = "目标音量0-100（set用）", required = false)
            }),
        @Action(name = "brightness", description = "屏幕亮度",
            params = {
                @Param(name = "op", type = "string", description = "操作: get(查询,默认)/set(设置0-255)/auto(自动亮度)", required = false),
                @Param(name = "value", type = "integer", description = "亮度值0-255（set用）", required = false)
            }),
        // ========== 系统级连接 ==========
        @Action(name = "wifi", description = "WiFi 状态/开关",
            params = {
                @Param(name = "op", type = "string", description = "操作: status(状态,默认)/on(开)/off(关)", required = false)
            }),
        @Action(name = "bluetooth", description = "蓝牙状态/开关",
            params = {
                @Param(name = "op", type = "string", description = "操作: status(状态,默认)/on(开)/off(关)", required = false)
            }),
        @Action(name = "hotspot", description = "个人热点开关（需热点权限）",
            params = {
                @Param(name = "enable", type = "boolean", description = "true开/false关", required = true)
            }),
        @Action(name = "usb", description = "USB 连接状态"),
        @Action(name = "screen", description = "屏幕状态/常亮",
            params = {
                @Param(name = "op", type = "string", description = "操作: status(状态,默认)/keep_on(常亮开)/keep_off(常亮关)", required = false)
            })
    }
)
public class SystemConnectTool implements AITool {

    private static final String TAG = "SystemConnectTool";

    private final Context context;

    public SystemConnectTool(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String getName() {
        return "system_connect";
    }

    @Override
    public String getDescription() {
        return "系统级连接与设备能力工具。系统级UI: notify(通知)/floating_window(悬浮窗)/toast/screenshot(截屏)。"
                + "系统级数据: clipboard(剪贴板)/battery(电池)/network(网络)/volume(音量)/brightness(亮度)。"
                + "系统级连接: wifi(开关/状态)/bluetooth(开关/状态)/hotspot(热点)/usb(USB状态)/screen(屏幕)。"
                + "系统参数与设备连接管理统一走本工具；跳转系统设置页用 system_resource(open_settings)。";
    }

    @Override
    public Map<String, String> getParameterDescriptions() {
        Map<String, String> m = new HashMap<>();
        m.put("action", "操作: notify/toast/floating_window/close_floating/screenshot/clipboard/battery/network/volume/brightness/wifi/bluetooth/hotspot/usb/screen");
        m.put("title", "通知标题（notify用）");
        m.put("content", "通知内容（notify用）");
        m.put("text", "Toast文本/剪贴板写入内容/悬浮窗文字");
        m.put("op", "子操作: read/write/get/set/up/down/on/off/status/keep_on/keep_off/mobile");
        m.put("enable", "开关值（network-mobile/hotspot用）");
        m.put("stream", "音量类型: music/ring/alarm/notification");
        m.put("value", "数值（音量/亮度）");
        m.put("path", "截图保存路径（screenshot用）");
        m.put("x", "悬浮窗X坐标");
        m.put("y", "悬浮窗Y坐标");
        return m;
    }

    @Override
    public AIToolResult execute(Map<String, Object> parameters) {
        try {
            String action = parameters.get("action") != null
                    ? String.valueOf(parameters.get("action")) : "battery";
            switch (action) {
                case "notify": return notify(parameters);
                case "toast": return toast(parameters);
                case "floating_window": return floatingWindow(parameters);
                case "close_floating": return closeFloating();
                case "screenshot": return screenshot(parameters);
                case "clipboard": return clipboard(parameters);
                case "battery": return battery();
                case "network": return network(parameters);
                case "volume": return volume(parameters);
                case "brightness": return brightness(parameters);
                case "wifi": return wifi(parameters);
                case "bluetooth": return bluetooth(parameters);
                case "hotspot": return hotspot(parameters);
                case "usb": return usb();
                case "screen": return screen(parameters);
                default:
                    return AIToolResult.fail("未知操作: " + action + "（支持 notify/toast/floating_window/clipboard/battery/network/volume/brightness/wifi/bluetooth/hotspot/usb/screen）");
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "系统连接工具失败: " + e.getMessage(), e);
            return AIToolResult.fail("系统连接工具失败: " + e.getMessage());
        }
    }

    // ==================== 系统级 UI ====================

    private AIToolResult notify(Map<String, Object> p) {
        String title = str(p.get("title"));
        String content = p.get("content") != null ? String.valueOf(p.get("content")) : "";
        int id = p.get("id") != null ? Integer.parseInt(String.valueOf(p.get("id"))) : (int) (System.currentTimeMillis() % 100000);
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return AIToolResult.fail("通知服务不可用");
            String channelId = "system_connect_notify";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel ch = new android.app.NotificationChannel(
                        channelId, "系统连接通知", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("AI 系统级连接通知");
                nm.createNotificationChannel(ch);
            }
            Intent intent = new Intent(context, com.oilquiz.app.ui.activity.AIChatActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(context, id, intent,
                    Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_UPDATE_CURRENT
                            | android.app.PendingIntent.FLAG_IMMUTABLE : android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(context, channelId)
                    : new android.app.Notification.Builder(context);
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(id, builder.build());
            Map<String, Object> r = new HashMap<>();
            r.put("status", "success");
            r.put("message", "通知已发送: " + title);
            r.put("id", id);
            return AIToolResult.success(r);
        } catch (Exception e) {
            return AIToolResult.fail("发送通知失败: " + e.getMessage());
        }
    }

    private AIToolResult toast(Map<String, Object> p) {
        String text = str(p.get("text"));
        boolean longShow = p.get("long") != null && Boolean.parseBoolean(String.valueOf(p.get("long")));
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> android.widget.Toast.makeText(context, text,
                longShow ? android.widget.Toast.LENGTH_LONG : android.widget.Toast.LENGTH_SHORT).show());
        Map<String, Object> r = new HashMap<>();
        r.put("status", "success");
        r.put("message", "Toast 已显示");
        return AIToolResult.success(r);
    }

    private AIToolResult floatingWindow(Map<String, Object> p) {
        // 悬浮窗：需要 SYSTEM_ALERT_WINDOW 权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(context)) {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + context.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            } catch (Exception ignored) {
            }
            return AIToolResult.fail("需要悬浮窗权限，已打开设置页请授权后重试");
        }
        String text = str(p.get("text"));
        int x = p.get("x") != null ? Integer.parseInt(String.valueOf(p.get("x"))) : 80;
        int y = p.get("y") != null ? Integer.parseInt(String.valueOf(p.get("y"))) : 200;
        FloatingWindowController.show(context, text, x, y);
        Map<String, Object> r = new HashMap<>();
        r.put("status", "success");
        r.put("message", "悬浮窗已创建: " + text + "（可拖动，用 close_floating 关闭）");
        return AIToolResult.success(r);
    }

    private AIToolResult closeFloating() {
        FloatingWindowController.hide(context);
        Map<String, Object> r = new HashMap<>();
        r.put("status", "success");
        r.put("message", "悬浮窗已关闭");
        return AIToolResult.success(r);
    }

    private AIToolResult screenshot(Map<String, Object> p) {
        // 系统截图：需 MediaProjection 授权（弹系统授权框）。简化：返回引导说明。
        Map<String, Object> r = new HashMap<>();
        r.put("status", "need_permission");
        r.put("message", "截屏需要 MediaProjection 屏幕捕获授权（系统弹窗确认）。"
                + "请让用户确认后，可用 python_execute 调 android_ui 或 system_resource(open_url) 跳转系统截屏。"
                + "当前版本未内置屏幕捕获服务，建议用系统截屏（音量下+电源）或接入辅助功能。");
        r.put("path", p.get("path") != null ? String.valueOf(p.get("path")) : "");
        return AIToolResult.success(r);
    }

    // ==================== 系统级数据 ====================

    private AIToolResult clipboard(Map<String, Object> p) {
        String op = p.get("op") != null ? String.valueOf(p.get("op")) : "read";
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return AIToolResult.fail("剪贴板服务不可用");
        if ("write".equals(op)) {
            String text = str(p.get("text"));
            cm.setPrimaryClip(android.content.ClipData.newPlainText("AI内容", text));
            Map<String, Object> r = new HashMap<>();
            r.put("status", "success");
            r.put("message", "已写入剪贴板");
            r.put("text", text);
            return AIToolResult.success(r);
        }
        String text = "";
        if (cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            android.content.ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
            text = item.getText() != null ? item.getText().toString()
                    : (item.getUri() != null ? item.getUri().toString() : "");
        }
        Map<String, Object> r = new HashMap<>();
        r.put("status", "success");
        r.put("message", "剪贴板内容已读取");
        r.put("text", text);
        return AIToolResult.success(r);
    }

    private AIToolResult battery() {
        try {
            Intent bat = context.registerReceiver(null,
                    new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat == null) return AIToolResult.fail("电池信息不可用");
            int level = bat.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = bat.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            int status = bat.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            int temp = bat.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0);
            int voltage = bat.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0);
            boolean charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
            int pct = scale > 0 ? (int) (level * 100f / scale) : level;
            Map<String, Object> r = new HashMap<>();
            r.put("status", "success");
            r.put("percent", pct);
            r.put("charging", charging);
            r.put("temperature_c", temp / 10.0);
            r.put("voltage_mv", voltage);
            r.put("message", "电量 " + pct + "%" + (charging ? "（充电中）" : "（未充电）")
                    + " 温度 " + (temp / 10.0) + "℃");
            return AIToolResult.success(r);
        } catch (Exception e) {
            return AIToolResult.fail("读取电池失败: " + e.getMessage());
        }
    }

    private AIToolResult network(Map<String, Object> p) {
        String op = p.get("op") != null ? String.valueOf(p.get("op")) : "status";
        if ("mobile".equals(op)) {
            // 移动数据开关需写系统设置权限，通常不可直接改；返回状态+引导
            boolean enable = p.get("enable") != null && Boolean.parseBoolean(String.valueOf(p.get("enable")));
            Map<String, Object> r = new HashMap<>();
            r.put("status", "restricted");
            r.put("message", "移动数据开关需要系统权限，建议让用户手动在快捷设置切换"
                    + (enable ? "（目标：开）" : "（目标：关）"));
            return AIToolResult.success(r);
        }
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
        boolean connected = info != null && info.isConnected();
        String type = info != null ? info.getTypeName() : "无网络";
        Map<String, Object> r = new HashMap<>();
        r.put("status", "success");
        r.put("connected", connected);
        r.put("type", type);
        r.put("message", connected ? "网络已连接（" + type + "）" : "当前无网络连接");
        return AIToolResult.success(r);
    }

    private AIToolResult volume(Map<String, Object> p) {
        android.media.AudioManager am = (android.media.AudioManager)
                context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return AIToolResult.fail("音频服务不可用");
        String streamStr = p.get("stream") != null ? String.valueOf(p.get("stream")) : "music";
        int stream = android.media.AudioManager.STREAM_MUSIC;
        switch (streamStr) {
            case "ring": stream = android.media.AudioManager.STREAM_RING; break;
            case "alarm": stream = android.media.AudioManager.STREAM_ALARM; break;
            case "notification": stream = android.media.AudioManager.STREAM_NOTIFICATION; break;
            default: stream = android.media.AudioManager.STREAM_MUSIC; break;
        }
        int max = am.getStreamMaxVolume(stream);
        int cur = am.getStreamVolume(stream);
        String op = p.get("op") != null ? String.valueOf(p.get("op")) : "get";
        switch (op) {
            case "set": {
                int target = p.get("value") != null ? Integer.parseInt(String.valueOf(p.get("value"))) : 50;
                target = Math.max(0, Math.min(100, target));
                am.setStreamVolume(stream, (int) Math.round(target * max / 100f), 0);
                break;
            }
            case "up":
                am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, 0);
                break;
            case "down":
                am.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, 0);
                break;
            default:
                break;
        }
        cur = am.getStreamVolume(stream);
        int pct = max > 0 ? Math.round(cur * 100f / max) : 0;
        Map<String, Object> r = new HashMap<>();
        r.put("status", "success");
        r.put("stream", streamStr);
        r.put("percent", pct);
        r.put("message", streamStr + "音量: " + pct + "%");
        return AIToolResult.success(r);
    }

    private AIToolResult brightness(Map<String, Object> p) {
        String op = p.get("op") != null ? String.valueOf(p.get("op")) : "get";
        try {
            if ("auto".equals(op)) {
                android.provider.Settings.System.putInt(context.getContentResolver(),
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                Map<String, Object> r = new HashMap<>();
                r.put("status", "success");
                r.put("message", "已开启自动亮度");
                return AIToolResult.success(r);
            }
            if ("set".equals(op) && p.get("value") != null) {
                int value = Integer.parseInt(String.valueOf(p.get("value")));
                value = Math.max(0, Math.min(255, value));
                android.provider.Settings.System.putInt(context.getContentResolver(),
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                android.provider.Settings.System.putInt(context.getContentResolver(),
                        android.provider.Settings.System.SCREEN_BRIGHTNESS, value);
                Map<String, Object> r = new HashMap<>();
                r.put("status", "success");
                r.put("brightness", value);
                r.put("message", "亮度已设为 " + value);
                return AIToolResult.success(r);
            }
            int cur = android.provider.Settings.System.getInt(context.getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS, -1);
            int mode = android.provider.Settings.System.getInt(context.getContentResolver(),
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
            Map<String, Object> r = new HashMap<>();
            r.put("status", "success");
            r.put("brightness", cur);
            r.put("auto", mode == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            r.put("message", "亮度 " + cur + (r.get("auto") == Boolean.TRUE ? "（自动）" : ""));
            return AIToolResult.success(r);
        } catch (Exception e) {
            return AIToolResult.fail("亮度操作失败（可能需要系统写权限）: " + e.getMessage());
        }
    }

    // ==================== 系统级连接 ====================

    private AIToolResult wifi(Map<String, Object> p) {
        String op = p.get("op") != null ? String.valueOf(p.get("op")) : "status";
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return AIToolResult.fail("WiFi 服务不可用");
            if ("on".equals(op)) {
                if (Build.VERSION.SDK_INT >= 29) {
                    return AIToolResult.fail("Android 10+ 不允许应用直接开关 WiFi，请让用户手动在快捷设置开启");
                }
                wm.setWifiEnabled(true);
            } else if ("off".equals(op)) {
                if (Build.VERSION.SDK_INT >= 29) {
                    return AIToolResult.fail("Android 10+ 不允许应用直接开关 WiFi，请让用户手动在快捷设置关闭");
                }
                wm.setWifiEnabled(false);
            }
            boolean enabled = wm.isWifiEnabled();
            String ssid = "";
            android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
            if (wi != null && wi.getSSID() != null) {
                ssid = wi.getSSID().replace("\"", "");
            }
            Map<String, Object> r = new HashMap<>();
            r.put("status", "success");
            r.put("enabled", enabled);
            r.put("ssid", ssid);
            r.put("message", "WiFi " + (enabled ? "已开启" : "已关闭")
                    + (enabled && !ssid.isEmpty() ? "，连接: " + ssid : ""));
            return AIToolResult.success(r);
        } catch (Exception e) {
            return AIToolResult.fail("WiFi 操作失败: " + e.getMessage());
        }
    }

    private AIToolResult bluetooth(Map<String, Object> p) {
        String op = p.get("op") != null ? String.valueOf(p.get("op")) : "status";
        try {
            android.bluetooth.BluetoothAdapter ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ba == null) return AIToolResult.fail("设备不支持蓝牙");
            if ("on".equals(op)) {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                        android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return AIToolResult.fail("需要 BLUETOOTH_CONNECT 权限才能操作蓝牙，请先在设置中授权");
                }
                ba.enable();
            } else if ("off".equals(op)) {
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                        android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return AIToolResult.fail("需要 BLUETOOTH_CONNECT 权限才能操作蓝牙，请先在设置中授权");
                }
                ba.disable();
            }
            boolean enabled = ba.isEnabled();
            String name = "";
            if (enabled) {
                try {
                    name = ba.getName();
                } catch (SecurityException ignored) {
                }
            }
            Map<String, Object> r = new HashMap<>();
            r.put("status", "success");
            r.put("enabled", enabled);
            r.put("name", name);
            r.put("message", "蓝牙 " + (enabled ? "已开启" : "已关闭")
                    + (enabled && !name.isEmpty() ? "（" + name + "）" : ""));
            return AIToolResult.success(r);
        } catch (Exception e) {
            return AIToolResult.fail("蓝牙操作失败: " + e.getMessage());
        }
    }

    private AIToolResult hotspot(Map<String, Object> p) {
        boolean enable = p.get("enable") != null && Boolean.parseBoolean(String.valueOf(p.get("enable")));
        // 热点开关需要系统权限，直接返回引导
        Map<String, Object> r = new HashMap<>();
        r.put("status", "restricted");
        r.put("target", enable);
        r.put("message", "个人热点开关需要系统级权限，应用无法直接控制。"
                + "请让用户手动在 设置→个人热点 中" + (enable ? "开启" : "关闭") + "。"
                + "可用 system_resource(open_settings, setting=hotspot) 跳转到热点设置页。");
        return AIToolResult.success(r);
    }

    private AIToolResult usb() {
        try {
            android.hardware.usb.UsbManager um = (android.hardware.usb.UsbManager)
                    context.getSystemService(Context.USB_SERVICE);
            boolean connected = um != null && um.getDeviceList() != null && !um.getDeviceList().isEmpty();
            Map<String, Object> r = new HashMap<>();
            r.put("status", "success");
            r.put("usb_connected", connected);
            r.put("message", connected ? "检测到 USB 设备已连接" : "未检测到 USB 设备连接");
            return AIToolResult.success(r);
        } catch (Exception e) {
            return AIToolResult.fail("USB 状态查询失败: " + e.getMessage());
        }
    }

    private AIToolResult screen(Map<String, Object> p) {
        String op = p.get("op") != null ? String.valueOf(p.get("op")) : "status";
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.app.ActivityManager am = (android.app.ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            boolean isScreenOn = false;
            if (Build.VERSION.SDK_INT >= 20 && pm.hasSystemFeature("android.hardware.type.watch")) {
                // 手表类设备无直接 API
            }
            // 屏幕状态：用 PowerManager 判断（需要权限，降级用标志）
            Map<String, Object> r = new HashMap<>();
            r.put("status", "success");
            r.put("op", op);
            if ("keep_on".equals(op)) {
                r.put("message", "屏幕常亮需要 Activity 持有 WakeLock，聊天界面建议用 ui_component 提示用户"
                        + "或 system_resource(open_settings, setting=battery) 调整屏幕超时。");
            } else if ("keep_off".equals(op)) {
                r.put("message", "已取消常亮设置请求");
            } else {
                r.put("message", "屏幕状态查询（可配合 battery 电量/充电信息判断屏幕使用场景）");
            }
            return AIToolResult.success(r);
        } catch (Exception e) {
            return AIToolResult.fail("屏幕操作失败: " + e.getMessage());
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
