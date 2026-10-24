package com.html2apk.wrapper.tv;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 启动直播播放器的统一入口（桥、原生直播首页共用）。
 *
 * <p>频道列表通过内部文件传给播放器：几百上千个频道直接塞 Intent extra 会触发
 * Binder TransactionTooLargeException。会话文件在播放器 onDestroy 时删除；
 * 进程被杀留下的旧文件在下次启动时只保留最近 2 个。</p>
 */
public final class LiveSessionLauncher {

    /** 交给播放器的频道上限，超出部分不传 */
    public static final int MAX_PLAYER_CHANNELS = 3000;

    private LiveSessionLauncher() {
    }

    /**
     * @return true=已发出启动请求；false=参数/IO 失败（调用方可提示错误）
     */
    public static boolean launch(Context context, List<LiveChannel> channels,
                                 int index, String sourceName) {
        if (context == null || channels == null || channels.isEmpty()) return false;
        try {
            int limit = Math.min(channels.size(), MAX_PLAYER_CHANNELS);
            int idx = Math.max(0, Math.min(index, limit - 1));

            JSONArray arr = new JSONArray();
            for (int i = 0; i < limit; i++) arr.put(channels.get(i).toJson());

            File dir = new File(context.getFilesDir(), "live_tv");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建直播会话目录");
            JSONObject session = new JSONObject();
            session.put("channels", arr);
            session.put("index", idx);
            session.put("source", sourceName == null ? "" : sourceName);
            File f = new File(dir, "session_" + System.currentTimeMillis() + ".json");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(session.toString().getBytes(StandardCharsets.UTF_8));
            }
            cleanupOldSessions(dir, f);

            Intent intent = new Intent(context, LivePlayerActivity.class);
            intent.putExtra(LivePlayerActivity.EXTRA_SESSION, f.getAbsolutePath());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void cleanupOldSessions(File dir, File keep) {
        try {
            File[] old = dir.listFiles((d, n) -> n.startsWith("session_") && n.endsWith(".json"));
            if (old == null || old.length <= 2) return;
            java.util.Arrays.sort(old, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < old.length - 2; i++) {
                if (!old[i].getAbsolutePath().equals(keep.getAbsolutePath())) old[i].delete();
            }
        } catch (Exception ignored) {
        }
    }
}
