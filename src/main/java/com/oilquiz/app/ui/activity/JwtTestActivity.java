package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.oilquiz.app.R;
import com.oilquiz.app.ai.util.NetworkUtil;
import com.oilquiz.app.weather.QWeatherJwtGenerator;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.Request;
import okhttp3.Response;

/**
 * JWT 认证测试 Activity
 * 用于验证和风天气 JWT 认证是否正常工作，逐个测试所有 API 端点。
 */
public class JwtTestActivity extends AppCompatActivity {

    private static final String TAG = "JwtTestActivity";

    private static final String DEFAULT_HOST = "https://m278m2y7ak.re.qweatherapi.com";
    private static final String PROJECT_ID = "2B89AN9KXV";
    private static final String KID = "CAPR2BDUDV";
    private static final String PRIVATE_KEY =
            "-----BEGIN PRIVATE KEY-----\n" +
            "MC4CAQAwBQYDK2VwBCIEICwOvrfAlLBDEnFi+yhRLmCql0P1oXEgu7Jb2akwAQmJ\n" +
            "-----END PRIVATE KEY-----";

    private TextView tvConfig;
    private TextView tvToken;
    private EditText etLocation;
    private LinearLayout layoutApiResults;
    private Button btnGenerateToken;
    private Button btnRunAll;

    private QWeatherJwtGenerator jwtGenerator;
    private String currentToken;
    private boolean isRunning = false;

    /** 单个 API 端点定义 */
    private static class ApiEndpoint {
        final String name;
        final String path;
        final String extraQuery; // 额外的 query 参数（不含 location）

        ApiEndpoint(String name, String path, String extraQuery) {
            this.name = name;
            this.path = path;
            this.extraQuery = extraQuery;
        }
    }

    /** 要测试的所有 API 端点 */
    private final List<ApiEndpoint> endpoints = new ArrayList<ApiEndpoint>() {{
        add(new ApiEndpoint("Geo 城市查询", "/geo/v2/city/lookup", ""));
        add(new ApiEndpoint("实时天气", "/v7/weather/now", ""));
        add(new ApiEndpoint("24小时预报", "/v7/weather/24h", ""));
        add(new ApiEndpoint("7天预报", "/v7/weather/7d", ""));
        add(new ApiEndpoint("空气质量", "/v7/air/now", ""));
        add(new ApiEndpoint("天气预警", "/v7/warning/now", ""));
        add(new ApiEndpoint("生活指数 (v7)", "/v7/indices/1d", "&type=1"));
        add(new ApiEndpoint("分钟级降水", "/v7/minutely/5m", ""));
        add(new ApiEndpoint("日出日落 (v7)", "/v7/astronomy/sun", "&date=" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date())));
    }};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_jwt_test);

        initViews();
        showConfig();
        initJwt();
    }

    private void initViews() {
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        tvConfig = findViewById(R.id.tv_config);
        tvToken = findViewById(R.id.tv_token);
        etLocation = findViewById(R.id.et_location);
        layoutApiResults = findViewById(R.id.layout_api_results);
        btnGenerateToken = findViewById(R.id.btn_generate_token);
        btnRunAll = findViewById(R.id.btn_run_all);

        btnGenerateToken.setOnClickListener(v -> generateToken());
        btnRunAll.setOnClickListener(v -> runAllTests());

        // 从 SharedPreferences 读取缓存的位置（与天气横幅共用）
        loadSavedLocation();

        // 初始化各端点占位卡片
        for (ApiEndpoint ep : endpoints) {
            layoutApiResults.addView(createApiCard(ep));
        }
    }

    /** 从 SharedPreferences 读取上次缓存的位置 */
    private void loadSavedLocation() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("weather_location_cache", MODE_PRIVATE);
            double lat = Double.longBitsToDouble(prefs.getLong("cached_lat", 0));
            double lon = Double.longBitsToDouble(prefs.getLong("cached_lon", 0));
            if (lat != 0 && lon != 0) {
                etLocation.setText(String.format(Locale.US, "%.2f,%.2f", lon, lat));
            }
        } catch (Exception e) {
            Log.w(TAG, "No saved location, using default");
        }
    }

    /** 显示 JWT 配置信息 */
    private void showConfig() {
        StringBuilder sb = new StringBuilder();
        sb.append("Project ID: ").append(PROJECT_ID).append("\n");
        sb.append("Key ID (kid): ").append(KID).append("\n");
        sb.append("API Host: ").append(DEFAULT_HOST).append("\n");
        sb.append("算法: EdDSA (Ed25519)\n");
        sb.append("私钥: ").append(PRIVATE_KEY.length() > 40
                ? PRIVATE_KEY.substring(0, 40) + "..." : PRIVATE_KEY);

        // 从私钥推导公钥并计算 SHA-256，用于与控制台对比
        try {
            String pubKeySha256 = derivePublicKeySha256();
            sb.append("\n\n公钥 SHA-256（与控制台对比）:\n").append(pubKeySha256);
        } catch (Exception e) {
            sb.append("\n\n公钥推导失败: ").append(e.getMessage());
        }

        tvConfig.setText(sb.toString());
    }

    /** 从私钥推导出 SPKI 格式公钥的 SHA-256 */
    private String derivePublicKeySha256() throws Exception {
        // 解析私钥 seed
        String cleaned = PRIVATE_KEY
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT);

        byte[] seed = new byte[32];
        if (keyBytes.length == 48) {
            System.arraycopy(keyBytes, 16, seed, 0, 32);
        } else {
            for (int i = 0; i < keyBytes.length - 32; i++) {
                if (keyBytes[i] == 0x04 && keyBytes[i + 1] == 0x20) {
                    System.arraycopy(keyBytes, i + 2, seed, 0, 32);
                    break;
                }
            }
        }

        // 从 seed 推导公钥
        org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters privParams =
                new org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0);
        org.bouncycastle.crypto.params.Ed25519PublicKeyParameters pubParams = privParams.generatePublicKey();

        // 构建 SPKI 格式公钥（与控制台上传格式一致）
        // SPKI: 302a 300506032b6570 0321 00 [32 bytes public key]
        byte[] pubKeyRaw = pubParams.getEncoded();
        byte[] spki = new byte[12 + pubKeyRaw.length];
        spki[0] = 0x30; spki[1] = 0x2a;
        spki[2] = 0x30; spki[3] = 0x05;
        spki[4] = 0x06; spki[5] = 0x03; spki[6] = 0x2b; spki[7] = 0x65; spki[8] = 0x70;
        spki[9] = 0x03; spki[10] = 0x21; spki[11] = 0x00;
        System.arraycopy(pubKeyRaw, 0, spki, 12, pubKeyRaw.length);

        // 计算 SHA-256
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(spki);

        // 转为十六进制字符串
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** 初始化 JWT 生成器 */
    private void initJwt() {
        new Thread(() -> {
            try {
                jwtGenerator = new QWeatherJwtGenerator(PRIVATE_KEY, PROJECT_ID, KID);
                runOnUiThread(() -> {
                    tvToken.setText("JWT 生成器初始化成功，点击按钮生成 Token");
                    btnGenerateToken.setEnabled(true);
                    btnRunAll.setEnabled(true);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to init JWT generator", e);
                runOnUiThread(() -> {
                    tvToken.setText("JWT 初始化失败: " + e.getMessage());
                    btnGenerateToken.setEnabled(false);
                    btnRunAll.setEnabled(false);
                });
            }
        }).start();
    }

    /** 生成 / 刷新 JWT Token */
    private void generateToken() {
        if (jwtGenerator == null) {
            Toast.makeText(this, "JWT 生成器未初始化", Toast.LENGTH_SHORT).show();
            return;
        }
        btnGenerateToken.setEnabled(false);

        new Thread(() -> {
            try {
                String token = jwtGenerator.refreshToken();
                currentToken = token;
                runOnUiThread(() -> {
                    if (token != null) {
                        // 解析 payload 显示过期时间
                        String display = token;
                        try {
                            String[] parts = token.split("\\.");
                            if (parts.length >= 2) {
                                String payload = new String(android.util.Base64.decode(
                                        parts[1], android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING));
                                JSONObject payloadJson = new JSONObject(payload);
                                long exp = payloadJson.optLong("exp", 0) * 1000;
                                String expStr = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(exp));
                                display = "Header.Payload.Signature\n\n" +
                                        "Payload: " + payloadJson.toString() + "\n" +
                                        "过期时间: " + expStr + "\n\n" +
                                        "完整 Token:\n" + token;
                            }
                        } catch (Exception e) {
                            display = token + "\n\n(payload 解析失败: " + e.getMessage() + ")";
                        }
                        tvToken.setText(display);
                        Toast.makeText(this, "Token 生成成功", Toast.LENGTH_SHORT).show();
                    } else {
                        tvToken.setText("Token 生成失败，请查看日志");
                        Toast.makeText(this, "Token 生成失败", Toast.LENGTH_SHORT).show();
                    }
                    btnGenerateToken.setEnabled(true);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to generate token", e);
                runOnUiThread(() -> {
                    tvToken.setText("Token 生成异常: " + e.getMessage());
                    btnGenerateToken.setEnabled(true);
                });
            }
        }).start();
    }

    /** 运行所有 API 端点测试 */
    private void runAllTests() {
        if (isRunning) {
            Toast.makeText(this, "测试进行中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        if (jwtGenerator == null) {
            Toast.makeText(this, "JWT 生成器未初始化", Toast.LENGTH_SHORT).show();
            return;
        }

        isRunning = true;
        btnRunAll.setEnabled(false);
        btnRunAll.setText("测试中...");

        // 确保 token 可用
        new Thread(() -> {
            try {
                // 强制刷新 token，避免使用过期缓存
                currentToken = jwtGenerator.refreshToken();
                if (currentToken == null) {
                    runOnUiThread(() -> {
                        tvToken.setText("无法获取 Token，测试终止");
                        isRunning = false;
                        btnRunAll.setEnabled(true);
                        btnRunAll.setText("全部测试");
                    });
                    return;
                }

                String location = etLocation.getText().toString().trim();
                if (location.isEmpty()) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "请输入测试位置（经度,纬度）", Toast.LENGTH_LONG).show();
                        isRunning = false;
                        btnRunAll.setEnabled(true);
                        btnRunAll.setText("全部测试");
                    });
                    return;
                }

                final String finalLocation = location;

                // 逐个测试端点
                for (int i = 0; i < endpoints.size(); i++) {
                    final int index = i;
                    final ApiEndpoint ep = endpoints.get(i);
                    final String loc = finalLocation;

                    // 更新为"测试中"
                    runOnUiThread(() -> updateApiCard(index, "⏳", "测试中...", ""));

                    try {
                        // 构建完整 URL
                        String url;
                        if ("V1_PATH".equals(ep.extraQuery)) {
                            // v1 API 使用路径参数格式: /{service}/v1/{endpoint}/{lat}/{lon}
                            String[] parts = loc.split(",");
                            if (parts.length >= 2) {
                                String lon = parts[0].trim();
                                String lat = parts[1].trim();
                                url = DEFAULT_HOST + ep.path + "/" + lat + "/" + lon;
                            } else {
                                // 默认北京坐标
                                url = DEFAULT_HOST + ep.path + "/39.92/116.41";
                            }
                        } else if (ep.path.startsWith("/geo")) {
                            // Geo API 使用城市名或经纬度
                            url = DEFAULT_HOST + ep.path + "?location=" + loc + ep.extraQuery;
                        } else {
                            url = DEFAULT_HOST + ep.path + "?location=" + loc + ep.extraQuery;
                        }

                        // 刷新 token（每次请求前确保有效）
                        String token = jwtGenerator.getToken();
                        if (token == null) {
                            token = jwtGenerator.refreshToken();
                        }
                        currentToken = token;

                        // 构建请求
                        Request request = new Request.Builder()
                                .url(url)
                                .addHeader("Authorization", "Bearer " + token)
                                .addHeader("User-Agent", "SmartQuiz/1.0")
                                .addHeader("Accept", "application/json")
                                .addHeader("Accept-Language", "zh-CN")
                                .addHeader("Accept-Encoding", "gzip, deflate")
                                .build();

                        // 调试：显示 token 前缀
                        Log.d(TAG, "Request URL: " + url);
                        Log.d(TAG, "Token prefix: " + (token != null ? token.substring(0, Math.min(30, token.length())) : "null"));

                        long startTime = System.currentTimeMillis();
                        try (Response response = NetworkUtil.getClient().newCall(request).execute()) {
                            long elapsed = System.currentTimeMillis() - startTime;
                            int code = response.code();
                            String body = response.body() != null ? response.body().string() : "";

                            // 判断结果
                            String statusIcon;
                            if (code == 200) {
                                statusIcon = "✅";
                            } else if (code == 401 || code == 403) {
                                statusIcon = "🔒";
                            } else if (code == 404) {
                                statusIcon = "❓";
                            } else {
                                statusIcon = "❌";
                            }

                            // 截断过长的响应
                            String preview = body.length() > 800 ? body.substring(0, 800) + "\n... (已截断)" : body;
                            
                            // 403 时显示响应体帮助诊断
                            if (code == 403) {
                                preview = "【403 诊断】\n响应体: " + body + "\n\nURL: " + url +
                                        "\nToken前缀: " + (token != null ? token.substring(0, Math.min(30, token.length())) : "null");
                            }

                            final String fStatus = statusIcon + " HTTP " + code + " (" + elapsed + "ms)";
                            final String fPreview = preview;
                            runOnUiThread(() -> updateApiCard(index, fStatus, fPreview, url));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Test failed for " + ep.name, e);
                        final String errMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                        runOnUiThread(() -> updateApiCard(index, "❌ 异常", errMsg, ""));
                    }

                    // 请求间隔 600ms 避免触发限流
                    try {
                        Thread.sleep(600);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                runOnUiThread(() -> {
                    isRunning = false;
                    btnRunAll.setEnabled(true);
                    btnRunAll.setText("全部测试");
                    Toast.makeText(this, "测试完成", Toast.LENGTH_SHORT).show();
                });

            } catch (Exception e) {
                Log.e(TAG, "runAllTests failed", e);
                runOnUiThread(() -> {
                    isRunning = false;
                    btnRunAll.setEnabled(true);
                    btnRunAll.setText("全部测试");
                    Toast.makeText(this, "测试失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** 创建单个 API 端点的结果卡片 */
    @NonNull
    private View createApiCard(ApiEndpoint ep) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF232945);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dpToPx(10);
        card.setLayoutParams(cardParams);
        card.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));

        // 端点名称 + 路径
        TextView tvName = new TextView(this);
        tvName.setText(ep.name + "  " + ep.path);
        tvName.setTextColor(0xFF90CAF9);
        tvName.setTextSize(13);
        tvName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(tvName);

        // 状态
        TextView tvStatus = new TextView(this);
        tvStatus.setText("⏳ 等待测试");
        tvStatus.setTextColor(0xFFB0BEC5);
        tvStatus.setTextSize(12);
        tvStatus.setTag("status");
        card.addView(tvStatus);

        // URL
        TextView tvUrl = new TextView(this);
        tvUrl.setTextColor(0xFF78909C);
        tvUrl.setTextSize(10);
        tvUrl.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvUrl.setTag("url");
        card.addView(tvUrl);

        // 响应预览
        TextView tvPreview = new TextView(this);
        tvPreview.setTextColor(0xFFE0E0E0);
        tvPreview.setTextSize(11);
        tvPreview.setTypeface(android.graphics.Typeface.MONOSPACE);
        tvPreview.setMovementMethod(ScrollingMovementMethod.getInstance());
        tvPreview.setBackgroundColor(0xFF1A1A2E);
        tvPreview.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        previewParams.topMargin = dpToPx(6);
        tvPreview.setLayoutParams(previewParams);
        tvPreview.setMaxLines(15);
        tvPreview.setTag("preview");
        card.addView(tvPreview);

        return card;
    }

    /** 更新指定位置的 API 卡片内容 */
    private void updateApiCard(int index, String status, String preview, String url) {
        if (index >= layoutApiResults.getChildCount()) return;
        View card = layoutApiResults.getChildAt(index);
        TextView tvStatus = card.findViewWithTag("status");
        TextView tvPreview = card.findViewWithTag("preview");
        TextView tvUrl = card.findViewWithTag("url");

        if (tvStatus != null) {
            tvStatus.setText(status);
            // 根据状态设置颜色
            if (status.startsWith("✅")) {
                tvStatus.setTextColor(0xFF81C784);
            } else if (status.startsWith("🔒")) {
                tvStatus.setTextColor(0xFFFFD54F);
            } else if (status.startsWith("❌")) {
                tvStatus.setTextColor(0xFFEF5350);
            } else {
                tvStatus.setTextColor(0xFFB0BEC5);
            }
        }
        if (tvPreview != null) {
            tvPreview.setText(preview.isEmpty() ? "(无响应)" : preview);
        }
        if (tvUrl != null) {
            tvUrl.setText(url.isEmpty() ? "" : "URL: " + url);
        }

        // 滚动到当前测试项（向上遍历找到 ScrollView）
        try {
            View parent = (View) card.getParent();
            while (parent != null && !(parent instanceof ScrollView)) {
                parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
            }
            if (parent instanceof ScrollView) {
                ScrollView scrollView = (ScrollView) parent;
                scrollView.post(() -> scrollView.smoothScrollTo(0, card.getBottom()));
            }
        } catch (Exception e) {
            // 忽略滚动错误
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
