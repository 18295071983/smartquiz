package com.oilquiz.app.vnc;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.tool.TermuxEnvInstaller;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * 图形界面（VNC · noVNC 外壳）。
 *
 * <p><b>为什么换成这个</b>：自研 RFB 客户端协议没问题，但界面是"一行横向滚动按钮"那种土办法，
 * 用户明确说"你自己设计的不行，用别人的"。可选的现成客户端里 bVNC/aVNC 都是 GPLv3（搬进来整个
 * MIT 项目就得转 GPLv3 并开源），而 <b>noVNC 是 MPL-2.0</b>，可以合法使用。
 *
 * <p><b>分工</b>：
 * <ul>
 *   <li>noVNC（容器里 Ubuntu 的 {@code novnc} 包提供）：协议、渲染、输入、缩放、设置面板、剪贴板，
 *       连中文界面都是它自带的（{@code app/locale/zh_CN.json}，随设备语言生效）。</li>
 *   <li>{@code websockify --web}：同一个进程既把 noVNC 网页发出来，又把 WebSocket 桥到
 *       {@code 127.0.0.1:5900} 的 Xvnc。真机实测握手 101 + 收到 {@code RFB 003.008}。</li>
 *   <li>本页：只做外壳 —— 拉起/探测容器里的桌面服务、把页面装进 WebView、给一条中文状态条。</li>
 * </ul>
 *
 * <p>原来的原生客户端（{@link VncActivity}）保留为「原生模式」，noVNC 万一跑不起来还有退路。
 */
public class VncWebActivity extends AppCompatActivity {

    /** websockify 监听的端口（网页与 WebSocket 共用这一个端口） */
    public static final int WEB_PORT = 6080;
    /** Xvnc 的 RFB 端口 */
    public static final int VNC_PORT = 5900;
    private static final long BAR_AUTO_HIDE_MS = 8000L;

    private WebView web;
    private View bar;
    private View toggle;
    private TextView statusView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean probing = false;
    /** noVNC 全屏时由 WebView 交过来的自定义 View */
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private boolean webLoaded = false;
    private boolean destroyed = false;
    private boolean immersive = true;

    /**
     * noVNC 页面地址。参数含义（noVNC 1.3 的 query 参数）：
     * {@code autoconnect=1} 打开就连、{@code reconnect=1} 断线自动重连、
     * {@code path=websockify} 走 websockify 的默认路径，
     * {@code resize=remote} 是**横竖屏适配的关键**：让 noVNC 用 SetDesktopSize 请求 Xvnc
     * 把远端桌面改成和手机窗口一样的分辨率（竖屏 → 竖屏桌面，XFCE 自动重排），
     * 而不是 {@code scale} 那种"把 1280x720 硬缩进屏幕"（竖屏只剩中间一条、字还发虚）。
     */
    public static String buildUrl() {
        return "http://127.0.0.1:" + WEB_PORT + "/vnc.html"
                + "?host=127.0.0.1&port=" + WEB_PORT + "&path=websockify"
                + "&autoconnect=1&resize=remote&reconnect=1&reconnect_delay=2000"
                + "&show_dot=1&bell=0";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(R.layout.activity_vnc_web);
        applyImmersive();

        web = findViewById(R.id.vnc_web);
        bar = findViewById(R.id.vnc_web_bar);
        toggle = findViewById(R.id.btn_vnc_web_toggle);
        statusView = findViewById(R.id.vnc_web_status);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // noVNC 用 localStorage 存设置
        s.setSupportZoom(false);           // 缩放交给 noVNC 自己（resize=scale）
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setBackgroundColor(0xFF000000);
        web.setWebViewClient(new WebViewClient());
        // noVNC 的界面会调用 Fullscreen API，而 WebView 必须由 App 接住 onShowCustomView 才算支持，
        // 否则 noVNC 会弹「noVNC 遇到一个错误：Fullscreen is not supported」（真机截图抓到过）。
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                ((ViewGroup) findViewById(R.id.vnc_web_root))
                        .addView(view, new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                bar.setVisibility(View.GONE);
                toggle.setVisibility(View.GONE);
                applyImmersive();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) {
                    return;
                }
                ((ViewGroup) findViewById(R.id.vnc_web_root)).removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                if (customCallback != null) {
                    customCallback.onCustomViewHidden();
                    customCallback = null;
                }
                showBar(false);   // 退出全屏后保持画面干净，只留左上角 ≡
            }
        });

        findViewById(R.id.btn_vnc_web_gui).setOnClickListener(v -> startGuiAndWait());
        findViewById(R.id.btn_vnc_web_reload).setOnClickListener(v -> {
            status("重新加载 noVNC…");
            webLoaded = true;
            web.loadUrl(buildUrl());
        });
        findViewById(R.id.btn_vnc_web_native).setOnClickListener(v ->
                startActivity(new Intent(this, VncActivity.class)));
        findViewById(R.id.btn_vnc_web_rotate).setOnClickListener(v -> {
            boolean land = getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            setRequestedOrientation(land
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            ((android.widget.TextView) v).setText(land ? "横屏" : "竖屏");
            status(land ? "切到竖屏，正在重新适配远端桌面…" : "切到横屏，正在重新适配远端桌面…");
            scheduleAutoHide();
        });
        findViewById(R.id.btn_vnc_web_bars).setOnClickListener(v -> {
            immersive = !immersive;
            applyImmersive();
            applyInsetsPadding(findViewById(R.id.vnc_web_root));
            status(immersive ? "已隐藏系统栏（画面占满屏幕）" : "已显示系统栏");
            scheduleAutoHide();
        });
        findViewById(R.id.btn_vnc_web_hide).setOnClickListener(v -> showBar(false));
        // 浮层可以拖：状态条拖"状态文字/空白"处，≡ 整个都能拖；位置会记住
        // （用户要求：「动态按钮不能拖动啊，为何是固定位置」）。没拖动的那一下仍当点击用。
        FloatingDrag.attach(bar, findViewById(R.id.vnc_web_root), "bar", null);
        FloatingDrag.attach(toggle, findViewById(R.id.vnc_web_root), "toggle", () -> showBar(true));
        resetTimerOnTouch(bar);

        applyInsetsPadding(findViewById(R.id.vnc_web_root));
        showBar(true);
        status("图形界面：检查中…");
        probeAsync(up -> {
            if (up) {
                openPage("桌面已就绪");
            } else {
                status("图形界面未运行，正在让 Termux 拉起桌面…");
                startGuiAndWait();
            }
        });
        handler.postDelayed(this::tickStatus, 3000);
    }

    private void openPage(String why) {
        status(why + "，正在打开画面…");
        webLoaded = true;
        web.loadUrl(buildUrl());
    }

    /** 让 Termux 起容器里的桌面，然后轮询 6080 直到就绪 */
    private void startGuiAndWait() {
        String err = TermuxEnvInstaller.startGuiInTermux(this);
        if (err != null) {
            status("启动失败：" + err);
            showBar(true);
            return;
        }
        status("已让 Termux 启动桌面，等待就绪…");
        waitForServer(45);
    }

    private void waitForServer(final int maxSeconds) {
        probeAsync(new Consumer<Boolean>() {
            int left = maxSeconds;

            @Override
            public void accept(Boolean up) {
                if (destroyed) {
                    return;
                }
                if (up) {
                    openPage("桌面已就绪");
                    return;
                }
                if (left-- <= 0) {
                    status("等不到图形界面：去「完整 Python 环境」点一次「一键准备」");
                    showBar(true);
                    return;
                }
                status("等待桌面就绪…（还剩 " + left + " 秒）");
                handler.postDelayed(() -> probeAsync(this), 1000);
            }
        });
    }

    /** 每秒看一眼 6080 通不通（在后台线程连，避免主线程做网络） */
    private void probeAsync(Consumer<Boolean> cb) {
        if (probing) {
            handler.postDelayed(() -> probeAsync(cb), 800);
            return;
        }
        probing = true;
        new Thread(() -> {
            boolean up = portOpen(WEB_PORT, 700);
            probing = false;
            runOnUiThread(() -> {
                if (!destroyed) {
                    cb.accept(up);
                }
            });
        }, "vnc-web-probe").start();
    }

    private static boolean portOpen(int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void tickStatus() {
        if (destroyed) {
            return;
        }
        probeAsync(up -> {
            if (destroyed) {
                return;
            }
            if (up && !webLoaded) {
                openPage("桌面已就绪");
            } else if (!up) {
                status("图形界面未运行（点「启动图形界面」）");
            } else {
                status("noVNC 已连接 127.0.0.1:" + VNC_PORT);
            }
        });
        handler.postDelayed(this::tickStatus, 4000);
    }

    private void status(String s) {
        statusView.setText(s);
    }

    private void showBar(boolean show) {
        bar.setVisibility(show ? View.VISIBLE : View.GONE);
        toggle.setVisibility(show ? View.GONE : View.VISIBLE);
        if (show) {
            scheduleAutoHide();
        } else {
            cancelAutoHide();
        }
    }

    private final Runnable autoHide = () -> showBar(false);

    private void scheduleAutoHide() {
        cancelAutoHide();
        handler.postDelayed(autoHide, BAR_AUTO_HIDE_MS);
    }

    private void cancelAutoHide() {
        handler.removeCallbacks(autoHide);
    }

    /** 状态条上任何一次按下都重新计时（返回 false，不拦截按钮点击） */
    private void resetTimerOnTouch(View v) {
        v.setOnTouchListener((view, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                scheduleAutoHide();
            }
            return false;
        });
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                resetTimerOnTouch(g.getChildAt(i));
            }
        }
    }

    private void applyImmersive() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.WindowInsetsControllerCompat c = androidx.core.view.WindowCompat
                .getInsetsController(getWindow(), getWindow().getDecorView());
        if (c == null) {
            return;
        }
        if (immersive) {
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            c.setSystemBarsBehavior(
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        }
    }

    private void applyInsetsPadding(final View root) {
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).top;
            int bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(0, immersive ? 0 : top, 0, immersive ? 0 : bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    /**
     * 横竖屏切换后的适配。
     *
     * <p>本页带 {@code configChanges=orientation|screenSize}，旋转时不会重建 Activity，
     * WebView 的窗口尺寸跟着变；noVNC 的 {@code resize=remote} 会按新窗口尺寸请求 Xvnc 改分辨率。
     * 但 WebView 里那次 resize 事件不一定触发重新协商，所以这里**显式重载一次页面**求稳
     * （本地连接，重连不到 2 秒），顺便把状态条叫回来告诉用户发生了什么。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean land = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        status((land ? "横屏" : "竖屏") + "：正在重新适配远端桌面…");
        showBar(true);
        handler.postDelayed(() -> {
            if (!destroyed) {
                webLoaded = true;
                web.loadUrl(buildUrl());
            }
        }, 700);
        // 浮层位置在新方向上可能越界，拉回屏幕内（用户的拖动偏好保留）
        handler.postDelayed(() -> {
            if (!destroyed) {
                View root = findViewById(R.id.vnc_web_root);
                FloatingDrag.reclamp(bar, root, "bar");
                FloatingDrag.reclamp(toggle, root, "toggle");
            }
        }, 400);
        applyImmersive();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (web != null) {
            web.onResume();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 离开页面主动断开：远程桌面在后台持续收帧既费电，也会占着服务端的一个客户端
        if (web != null) {
            web.evaluateJavascript("try{UI.disconnect()}catch(e){}", null);
            web.onPause();
        }
        cancelAutoHide();
        handler.removeCallbacksAndMessages(null);
    }

    /** 全屏中按返回键：先退出全屏，别直接把页面关掉 */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) {
            web.getWebChromeClient().onHideCustomView();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }
}
