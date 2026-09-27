package com.oilquiz.app.vnc;

import android.graphics.Bitmap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 极简 RFB（VNC）客户端 —— 自研，不依赖任何第三方库。
 *
 * <p>为什么自己写：android-vnc-viewer / LibVNC 都是 GPL，链进答题宝会把整个 App 拖成 GPL；
 * 而我们**自己控制服务端**（容器里的 x11vnc），所以只需实现会用到的编码：
 * Raw(0) / CopyRect(1) / Hextile(5) / DesktopSize(-223)，以及 KeyEvent/PointerEvent/剪贴板。
 *
 * <p>像素格式固定协商为 32bpp / depth24 / 小端 / true-colour（R&lt;&lt;16 | G&lt;&lt;8 | B），
 * 小端下恰好等于 Android 的 ARGB 低 24 位，解码时可以零转换。
 *
 * <p>线程模型：{@link #start} 起一条 reader 线程负责握手与收帧；输入方法可被 UI 线程随时调用，
 * 内部用写锁串行化。位图内容与 UI 绘制通过 {@link #frameLock} 互斥。
 */
public class VncClient {

    /** 回调（都在 reader 线程上，UI 操作请自己 post 到主线程） */
    public interface Listener {
        void onConnected(int width, int height, String serverName);

        /** 一帧已解码进 {@link #getBitmap()}，可以刷新界面了 */
        void onFrameReady();

        void onClipboard(String text);

        void onDisconnected(String reason);
    }

    private static final String CLIENT_VERSION = "RFB 003.008\n";
    private static final int ENC_RAW = 0;
    private static final int ENC_COPY_RECT = 1;
    private static final int ENC_HEXTILE = 5;
    private static final int ENC_DESKTOP_SIZE = -223;

    /**
     * 握手阶段读超时（毫秒）。必须给足：x11vnc 刚启动时，第一个客户端要先经过一次 websockets 探测，
     * 实测约 5 秒才收到版本横幅（真机上把这里调到 4 秒会 5 次重连全失败）。
     * 启动脚本那边会先做一次预热，所以用户点进来通常是秒连。
     */
    private static final int HANDSHAKE_TIMEOUT_MS = 12000;

    private static final int MSG_FRAMEBUFFER_UPDATE = 0;
    private static final int MSG_SET_COLOUR_MAP = 1;
    private static final int MSG_BELL = 2;
    private static final int MSG_SERVER_CUT_TEXT = 3;

    /** 帧缓冲互斥锁：reader 写 bitmap，UI 线程读 bitmap */
    public final Object frameLock = new Object();

    private final Listener listener;

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private volatile boolean running;

    /**
     * 输入事件（指针/按键/剪贴板）的发送队列 + 独立写线程。
     * 必须在非主线程写 socket：UI 线程直接 write/flush 会触发
     * {@code NetworkOnMainThreadException} 把 App 打死（真机实测踩到过）。
     */
    private final BlockingQueue<byte[]> outQueue = new LinkedBlockingQueue<>(512);
    private Thread writer;

    private int width;
    private int height;
    private Bitmap bitmap;
    private int[] pixels;
    private byte[] scratch = new byte[0];
    private int hextileBg;
    private int hextileFg;
    private String serverName = "";

    public VncClient(Listener listener) {
        this.listener = listener;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getServerName() {
        return serverName;
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    public boolean isRunning() {
        return running;
    }

    // ---------------- 生命周期 ----------------

    /** 起 reader 线程：连接 → 握手 → 收帧循环。重复调用会先断开旧连接。 */
    public void start(final String host, final int port, final int connectTimeoutMs) {
        stop();
        running = true;
        outQueue.clear();
        startWriter();
        Thread t = new Thread(() -> {
            String reason = null;
            try {
                open(host, port, connectTimeoutMs);
                handshake();
                loop();
            } catch (IOException e) {
                reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } catch (Throwable e) {
                reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            running = false;
            closeQuietly();
            Listener l = listener;
            if (l != null) {
                l.onDisconnected(reason == null ? "服务端关闭了连接" : reason);
            }
        }, "vnc-reader");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        Thread w = writer;
        if (w != null) {
            w.interrupt();
            writer = null;
        }
        closeQuietly();
    }

    /** 独立写线程：把队列里的输入消息真正写进 socket（UI 线程永不碰 socket） */
    private void startWriter() {
        Thread t = new Thread(() -> {
            while (true) {
                byte[] m;
                try {
                    m = outQueue.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (m == null) {
                    if (!running) {
                        break;
                    }
                    continue;
                }
                OutputStream o = out;
                if (o == null) {
                    continue;
                }
                try {
                    synchronized (VncClient.this) {
                        o.write(m);
                        if (outQueue.isEmpty()) {
                            o.flush();
                        }
                    }
                } catch (IOException e) {
                    // 连接断了：reader 线程会回调 onDisconnected
                    break;
                }
            }
        }, "vnc-writer");
        t.setDaemon(true);
        writer = t;
        t.start();
    }

    private void open(String host, int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setTcpNoDelay(true);
        // 握手阶段必须有读超时：x11vnc 偶发"接了连接但不发版本横幅"，
        // 不设超时 reader 会永久卡住，Activity 连"已断开"都收不到（真机踩过）。
        s.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        socket = s;
        in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
        out = new BufferedOutputStream(s.getOutputStream(), 1 << 16);
    }

    private void closeQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
        socket = null;
    }

    // ---------------- 握手 ----------------

    private void handshake() throws IOException {
        byte[] ver = new byte[12];
        try {
            in.readFully(ver);
        } catch (java.net.SocketTimeoutException e) {
            throw new IOException("服务端 " + HANDSHAKE_TIMEOUT_MS + "ms 内没发 VNC 版本横幅（x11vnc 偶发，重连即可）");
        }
        String serverVersion = new String(ver, StandardCharsets.US_ASCII).trim();
        if (!serverVersion.startsWith("RFB ")) {
            throw new IOException("对方不是 VNC 服务端（收到: " + serverVersion.replace("\n", "") + "）");
        }
        boolean v33 = serverVersion.startsWith("RFB 003.003");
        writeBytes((v33 ? "RFB 003.003\n" : CLIENT_VERSION).getBytes(StandardCharsets.US_ASCII));

        if (v33) {
            int sec = readInt();
            if (sec == 0) {
                throw new IOException("服务端拒绝连接（3.3 握手失败）");
            }
            if (sec == 2) {
                throw new IOException("服务端要求 VNC 密码（请用 -nopw 启动 x11vnc）");
            }
        } else {
            int count = in.readUnsignedByte();
            if (count == 0) {
                throw new IOException("服务端拒绝连接：" + readReason());
            }
            byte[] types = new byte[count];
            in.readFully(types);
            boolean hasNone = false;
            boolean hasVncAuth = false;
            for (byte b : types) {
                if (b == 1) {
                    hasNone = true;
                }
                if (b == 2) {
                    hasVncAuth = true;
                }
            }
            if (!hasNone) {
                throw new IOException(hasVncAuth
                        ? "服务端要求 VNC 密码（请用 -nopw 启动 x11vnc）"
                        : "服务端不支持无密码连接（安全类型: " + java.util.Arrays.toString(types) + "）");
            }
            writeBytes(new byte[]{1});
            int result = readInt();
            if (result != 0) {
                String why = serverVersion.startsWith("RFB 003.008") ? readReason() : "";
                throw new IOException("无密码连接被拒：" + why);
            }
        }

        // ClientInit: shared=1（允许与其他客户端共享同一桌面）
        writeBytes(new byte[]{1});

        // ServerInit
        width = in.readUnsignedShort();
        height = in.readUnsignedShort();
        byte[] pf = new byte[16];
        in.readFully(pf);
        int nameLen = in.readInt();
        if (nameLen < 0 || nameLen > 1 << 20) {
            throw new IOException("ServerInit 名称长度异常: " + nameLen);
        }
        byte[] nameBytes = new byte[nameLen];
        in.readFully(nameBytes);
        serverName = new String(nameBytes, StandardCharsets.UTF_8);
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
            throw new IOException("桌面尺寸异常: " + width + "x" + height);
        }
        synchronized (frameLock) {
            pixels = new int[width * height];
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xFF101010);
        }
        sendSetPixelFormat();
        sendSetEncodings();
        requestFrame(false, 0, 0, width, height);
        if (socket != null) {
            socket.setSoTimeout(0);      // 握手完成：进入常阻塞收帧
        }
        Listener l = listener;
        if (l != null) {
            l.onConnected(width, height, serverName);
        }
    }

    private String readReason() throws IOException {
        int len = readInt();
        if (len < 0 || len > 1 << 20) {
            return "(原因长度异常)";
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private void sendSetPixelFormat() throws IOException {
        byte[] m = new byte[20];
        m[0] = 0;                      // message-type = SetPixelFormat
        m[4] = 32;                     // bits-per-pixel
        m[5] = 24;                     // depth
        m[6] = 0;                      // big-endian = false
        m[7] = 1;                      // true-colour = true
        m[8] = 0; m[9] = (byte) 255;   // red-max
        m[10] = 0; m[11] = (byte) 255; // green-max
        m[12] = 0; m[13] = (byte) 255; // blue-max
        m[14] = 16;                    // red-shift
        m[15] = 8;                     // green-shift
        m[16] = 0;                     // blue-shift
        writeBytes(m);
    }

    private void sendSetEncodings() throws IOException {
        int[] encs = {ENC_COPY_RECT, ENC_HEXTILE, ENC_RAW, ENC_DESKTOP_SIZE};
        byte[] m = new byte[4 + encs.length * 4];
        m[0] = 2;                                  // message-type = SetEncodings
        m[2] = (byte) (encs.length >> 8);
        m[3] = (byte) encs.length;
        for (int i = 0; i < encs.length; i++) {
            int v = encs[i];
            m[4 + i * 4] = (byte) (v >> 24);
            m[5 + i * 4] = (byte) (v >> 16);
            m[6 + i * 4] = (byte) (v >> 8);
            m[7 + i * 4] = (byte) v;
        }
        writeBytes(m);
    }

    private void requestFrame(boolean incremental, int x, int y, int w, int h) throws IOException {
        byte[] m = new byte[10];
        m[0] = 3;
        m[1] = (byte) (incremental ? 1 : 0);
        m[2] = (byte) (x >> 8); m[3] = (byte) x;
        m[4] = (byte) (y >> 8); m[5] = (byte) y;
        m[6] = (byte) (w >> 8); m[7] = (byte) w;
        m[8] = (byte) (h >> 8); m[9] = (byte) h;
        writeBytes(m);
    }

    // ---------------- 收帧 ----------------

    private void loop() throws IOException {
        while (running) {
            int type = in.readUnsignedByte();
            switch (type) {
                case MSG_FRAMEBUFFER_UPDATE:
                    readFramebufferUpdate();
                    break;
                case MSG_SET_COLOUR_MAP:
                    skipColourMap();
                    break;
                case MSG_BELL:
                    break;
                case MSG_SERVER_CUT_TEXT:
                    readServerCutText();
                    break;
                default:
                    throw new IOException("未知的服务端消息类型: " + type);
            }
        }
    }

    private void readFramebufferUpdate() throws IOException {
        in.readUnsignedByte();                       // padding
        int rects = in.readUnsignedShort();
        boolean resized = false;
        for (int i = 0; i < rects; i++) {
            int x = in.readUnsignedShort();
            int y = in.readUnsignedShort();
            int w = in.readUnsignedShort();
            int h = in.readUnsignedShort();
            int enc = in.readInt();
            if (enc == ENC_DESKTOP_SIZE) {
                resizeFramebuffer(w, h);
                resized = true;
            } else if (enc == ENC_RAW) {
                readRaw(x, y, w, h);
            } else if (enc == ENC_COPY_RECT) {
                int sx = in.readUnsignedShort();
                int sy = in.readUnsignedShort();
                copyRect(sx, sy, x, y, w, h);
            } else if (enc == ENC_HEXTILE) {
                readHextile(x, y, w, h);
            } else {
                throw new IOException("服务端用了未实现的编码 " + enc + "（客户端只支持 Raw/Hextile/CopyRect）");
            }
        }
        synchronized (frameLock) {
            if (bitmap != null && pixels != null) {
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            }
        }
        Listener l = listener;
        if (l != null && rects > 0) {
            l.onFrameReady();
        }
        if (rects == 0 && !resized) {
            // 空更新（画面没变）：不要立刻再请求，否则会和 x11vnc 形成忙循环，
            // 既烧 CPU 又让 UI 线程一直重绘（真机上把仪器化测试都堵住了）。
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        requestFrame(!resized, 0, 0, width, height);
    }

    private void resizeFramebuffer(int w, int h) {
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) {
            return;
        }
        width = w;
        height = h;
        synchronized (frameLock) {
            pixels = new int[w * h];
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xFF101010);
        }
    }

    private int readPixel32() throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return (b2 << 16) | (b1 << 8) | b0;          // 忽略 alpha（b3），小端 0x00RRGGBB
    }

    private void readRaw(int x, int y, int w, int h) throws IOException {
        int need = w * h * 4;
        if (scratch.length < need) {
            scratch = new byte[need];
        }
        in.readFully(scratch, 0, need);
        putRawPixels(x, y, w, h, scratch, 0);
    }

    private void putRawPixels(int x, int y, int w, int h, byte[] buf, int offset) {
        int[] px = pixels;
        if (px == null) {
            return;
        }
        for (int row = 0; row < h; row++) {
            int dst = (y + row) * width + x;
            int src = offset + row * w * 4;
            for (int col = 0; col < w; col++) {
                int v = (buf[src] & 0xFF) | ((buf[src + 1] & 0xFF) << 8) | ((buf[src + 2] & 0xFF) << 16);
                int idx = dst + col;
                if (idx >= 0 && idx < px.length) {
                    px[idx] = 0xFF000000 | v;
                }
                src += 4;
            }
        }
    }

    private void copyRect(int sx, int sy, int dx, int dy, int w, int h) {
        int[] px = pixels;
        if (px == null) {
            return;
        }
        boolean down = dy > sy;
        boolean right = (dy == sy) && (dx > sx);
        for (int i = 0; i < h; i++) {
            int row = down ? (h - 1 - i) : i;
            int srcRow = (sy + row) * width + sx;
            int dstRow = (dy + row) * width + dx;
            for (int j = 0; j < w; j++) {
                int col = right ? (w - 1 - j) : j;
                int s = srcRow + col;
                int d = dstRow + col;
                if (s >= 0 && s < px.length && d >= 0 && d < px.length) {
                    px[d] = px[s];
                }
            }
        }
    }

    /** Hextile 解码。bg/fg 颜色在 tile 之间、乃至 update 之间保持（与 libvncclient 一致）。 */
    private void readHextile(int x, int y, int w, int h) throws IOException {
        for (int ty = 0; ty < h; ty += 16) {
            int th = Math.min(16, h - ty);
            for (int tx = 0; tx < w; tx += 16) {
                int tw = Math.min(16, w - tx);
                int subenc = in.readUnsignedByte();
                if ((subenc & 0x01) != 0) {                   // Raw tile
                    int need = tw * th * 4;
                    if (scratch.length < need) {
                        scratch = new byte[need];
                    }
                    in.readFully(scratch, 0, need);
                    putRawPixels(x + tx, y + ty, tw, th, scratch, 0);
                    continue;
                }
                if ((subenc & 0x02) != 0) {
                    hextileBg = readPixel32();
                }
                if ((subenc & 0x04) != 0) {
                    hextileFg = readPixel32();
                }
                fillRect(x + tx, y + ty, tw, th, 0xFF000000 | hextileBg);
                if ((subenc & 0x08) != 0) {
                    int subrects = in.readUnsignedByte();
                    boolean coloured = (subenc & 0x10) != 0;
                    for (int s = 0; s < subrects; s++) {
                        int color = coloured ? (0xFF000000 | readPixel32()) : (0xFF000000 | hextileFg);
                        int xy = in.readUnsignedByte();
                        int wh = in.readUnsignedByte();
                        int sx = (xy >> 4) & 0x0F;
                        int sy = xy & 0x0F;
                        int sw = ((wh >> 4) & 0x0F) + 1;
                        int sh = (wh & 0x0F) + 1;
                        fillRect(x + tx + sx, y + ty + sy, Math.min(sw, tw - sx), Math.min(sh, th - sy), color);
                    }
                }
            }
        }
    }

    private void fillRect(int x, int y, int w, int h, int argb) {
        int[] px = pixels;
        if (px == null || w <= 0 || h <= 0) {
            return;
        }
        for (int row = 0; row < h; row++) {
            int base = (y + row) * width + x;
            for (int col = 0; col < w; col++) {
                int idx = base + col;
                if (idx >= 0 && idx < px.length) {
                    px[idx] = argb;
                }
            }
        }
    }

    private void skipColourMap() throws IOException {
        in.readUnsignedByte();
        in.readUnsignedShort();
        int n = in.readUnsignedShort();
        int skip = n * 6;
        while (skip > 0) {
            int s = (int) in.skipBytes(skip);
            if (s <= 0) {
                throw new IOException("读取调色板失败");
            }
            skip -= s;
        }
    }

    private void readServerCutText() throws IOException {
        in.readUnsignedByte();
        in.readUnsignedByte();
        in.readUnsignedByte();
        int len = readInt();
        if (len < 0 || len > 8 << 20) {
            throw new IOException("剪贴板内容长度异常: " + len);
        }
        byte[] b = new byte[len];
        in.readFully(b);
        Listener l = listener;
        if (l != null) {
            l.onClipboard(new String(b, StandardCharsets.UTF_8));
        }
    }

    // ---------------- 输入 ----------------

    /** buttonMask: bit0=左键, bit1=中键, bit2=右键；滚轮用 bit3(上)/bit4(下) 瞬时按下 */
    public void sendPointer(int buttonMask, int x, int y) {
        byte[] m = new byte[6];
        m[0] = 5;
        m[1] = (byte) (buttonMask & 0xFF);
        m[2] = (byte) (x >> 8);
        m[3] = (byte) x;
        m[4] = (byte) (y >> 8);
        m[5] = (byte) y;
        enqueue(m);
    }

    public void sendKey(int keysym, boolean down) {
        byte[] m = new byte[8];
        m[0] = 4;
        m[1] = (byte) (down ? 1 : 0);
        m[4] = (byte) (keysym >> 24);
        m[5] = (byte) (keysym >> 16);
        m[6] = (byte) (keysym >> 8);
        m[7] = (byte) keysym;
        enqueue(m);
    }

    public void sendKeyTap(int keysym) {
        sendKey(keysym, true);
        sendKey(keysym, false);
    }

    public void sendClipboard(String text) {
        byte[] body = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        byte[] m = new byte[8 + body.length];
        m[0] = 6;
        m[4] = (byte) (body.length >> 24);
        m[5] = (byte) (body.length >> 16);
        m[6] = (byte) (body.length >> 8);
        m[7] = (byte) body.length;
        System.arraycopy(body, 0, m, 8, body.length);
        enqueue(m);
    }

    /** 把一条消息丢进发送队列（UI 线程调用安全）；队列满时丢最旧的一条，避免输入延迟堆积 */
    private void enqueue(byte[] m) {
        if (!running) {
            return;
        }
        if (!outQueue.offer(m)) {
            outQueue.poll();
            outQueue.offer(m);
        }
    }

    private void writeBytes(byte[] m) throws IOException {
        OutputStream o = out;
        if (o == null) {
            throw new IOException("连接已关闭");
        }
        synchronized (this) {
            o.write(m);
            o.flush();
        }
    }

    private int readInt() throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }
}
