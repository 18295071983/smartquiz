package com.oilquiz.app.ui.activity;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.ActionBar;
import com.google.android.material.button.MaterialButton;
import com.oilquiz.app.R;
import com.oilquiz.app.ai.optimization.DeviceDetector;
import com.oilquiz.app.ai.jni.LlamaHelper;

public class DeviceInfoActivity extends AppCompatActivity {

    private static final String TAG = "DeviceInfoActivity";
    private TextView deviceInfoTextView;
    private TextView openclInfoTextView;
    private TextView openclStatusTextView;
    private TextView runtimeInfoTextView;
    private MaterialButton btnRefresh;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 刷新按钮文案复位（无论成功/失败都在 finally 调用，避免永久禁用）。 */
    private final Runnable refreshDone = () -> {
        if (btnRefresh != null) {
            btnRefresh.setEnabled(true);
            btnRefresh.setText(getString(R.string.h_694fc5ef));
        }
    };

    /** 在途加载标记：onCreate 与 onResume 都会触发，避免重复跑两遍检测。 */
    private volatile boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setTitle(getString(R.string.h_b967fdef));
                actionBar.setDisplayHomeAsUpEnabled(true);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to set ActionBar: " + e.getMessage());
        }
        
        setContentView(R.layout.activity_device_info);

        try {
            deviceInfoTextView = findViewById(R.id.device_info_text_view);
            openclInfoTextView = findViewById(R.id.opencl_info_text_view);
            openclStatusTextView = findViewById(R.id.opencl_status_text_view);
            runtimeInfoTextView = findViewById(R.id.runtime_info_text_view);
            btnRefresh = findViewById(R.id.btn_refresh);
            
            if (btnRefresh != null) {
                btnRefresh.setOnClickListener(v -> {
                    btnRefresh.setEnabled(false);
                    btnRefresh.setText(getString(R.string.h_6441bd25));
                    loadDeviceInfo();
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during onCreate: " + e.getMessage(), e);
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // onCreate 已触发过一次；此处仅在未在途时补刷（避免每次进入跑两遍检测）
        if (!loading) {
            Log.d(TAG, "onResume: refreshing device info");
            loadDeviceInfo();
        }
    }
    
    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
    
    private void loadDeviceInfo() {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            final StringBuilder deviceInfo = new StringBuilder();
            final StringBuilder openclSummary = new StringBuilder();
            final String[] openclDetail = new String[1];
            final StringBuilder runtimeInfo = new StringBuilder();
            
            try {
                String brand = DeviceDetector.getDeviceBrand();
                String model = DeviceDetector.getDeviceModel();
                String device = DeviceDetector.getDeviceName();
                String hardware = DeviceDetector.getHardware();
                String gpu = DeviceDetector.getGPUModel();
                int androidVersion = DeviceDetector.getAndroidVersion();
                int cpuCores = DeviceDetector.getCPUCores();
                long totalMemory = DeviceDetector.getTotalMemoryMB();
                long freeMemory = DeviceDetector.getFreeMemoryMB();
                String abis = DeviceDetector.getSupportedABIs();
                boolean isAdreno = DeviceDetector.isAdrenoGPU();
                String adrenoSeries = isAdreno ? DeviceDetector.getAdrenoSeries() : null;
                
                deviceInfo.append(getString(R.string.h_9690fbfc)).append(brand != null ? brand : getString(R.string.h_1622dc9b)).append("\n");
                deviceInfo.append(getString(R.string.h_3bab779c)).append(model != null ? model : getString(R.string.h_1622dc9b)).append("\n");
                deviceInfo.append(getString(R.string.h_963669d9)).append(device != null ? device : getString(R.string.h_1622dc9b)).append("\n");
                deviceInfo.append(getString(R.string.h_49bfe273)).append(hardware != null ? hardware : getString(R.string.h_1622dc9b)).append("\n");
                deviceInfo.append("GPU: ").append(gpu != null ? gpu : getString(R.string.h_1622dc9b)).append("\n");
                
                if (isAdreno && adrenoSeries != null) {
                    deviceInfo.append(getString(R.string.h_d70c3110)).append(adrenoSeries).append("\n");
                }
                
                deviceInfo.append(getString(R.string.h_99950eb0)).append(cpuCores).append(" 核").append("\n");
                deviceInfo.append(getString(R.string.h_5a86cc25)).append(formatMemory(totalMemory)).append("\n");
                deviceInfo.append(getString(R.string.h_df803b12)).append(formatMemory(freeMemory)).append("\n");
                deviceInfo.append(getString(R.string.h_0d0b5e32)).append(androidVersion).append("\n");
                deviceInfo.append(getString(R.string.h_53254fc6)).append(abis != null ? abis : getString(R.string.h_1622dc9b)).append("\n");
                
            } catch (Exception e) {
                Log.e(TAG, "Error getting device info: " + e.getMessage());
                deviceInfo.append(getString(R.string.h_b4258c60)).append(e.getMessage()).append("\n");
            }
            
            try {
                boolean libLoaded = LlamaHelper.isLibraryLoaded();
                openclSummary.append(getString(R.string.h_92824b02)).append(libLoaded ? getString(R.string.h_c0c2057f) : getString(R.string.h_5b0b0982)).append("\n");
                
                if (libLoaded) {
                    boolean openclLoaded = LlamaHelper.isOpenCLLoaded();
                    boolean gpuWorking = LlamaHelper.isGPUWorking();
                    boolean modelInit = LlamaHelper.isModelInitialized();
                    
                    openclSummary.append(getString(R.string.h_d3af0136)).append(openclLoaded ? getString(R.string.h_c0c2057f) : getString(R.string.h_5b0b0982)).append("\n");
                    
                    if (openclLoaded) {
                        openclSummary.append(getString(R.string.h_4beb4f75));
                        if (gpuWorking) {
                            openclSummary.append(getString(R.string.h_7cffd96e));
                        } else {
                            openclSummary.append(getString(R.string.h_921ae75f));
                        }
                    } else {
                        openclSummary.append(getString(R.string.h_a13d4391));
                    }
                    
                    openclSummary.append(getString(R.string.h_98053d3c)).append(modelInit ? getString(R.string.h_c0c2057f) : getString(R.string.h_09fe0fff)).append("\n");

                    // ACCEL-BACKEND(2026-10-09)：本页原先只报 OpenCL/GPU，NPU 成为默认后端后
                    // 会出现"后端: NPU (Hexagon)"与"OpenCL: 已加载/未启用"自相矛盾的组合。
                    // 说明：isOpenCLLoaded() 只表示 /vendor/lib64/libOpenCL.so 能被 dlopen
                    // （AIService 预加载时置位），与"OpenCL 后端在用"无关，故这里明确措辞为
                    // "OpenCL 库"，并把加速后端与 NPU 可用性分开报。
                    boolean hasNpu = LlamaHelper.hasNpuDevice();
                    String resolved = LlamaHelper.getResolvedBackend();      // 实际选中（auto 也会解析出具体设备）
                    String pref = LlamaHelper.getEffectiveBackend();         // 偏好（下次加载将用）
                    String shown = (resolved != null && !resolved.isEmpty()) ? resolved : pref;
                    openclSummary.append("加速后端: ").append(backendLabelOf(shown));
                    if (resolved != null && !resolved.isEmpty() && pref != null && !pref.equals(resolved)) {
                        openclSummary.append("（偏好 ").append(backendLabelOf(pref)).append("，需重载生效）");
                    }
                    openclSummary.append("\n");
                    openclSummary.append("OpenCL 库: ").append(openclLoaded ? "已加载" : "未加载")
                            .append("（仅表示可 dlopen，不等于在用 OpenCL 加速）").append("\n");
                    openclSummary.append("Hexagon NPU (HTP): ").append(hasNpu ? "可用" : "未检测到").append("\n");
                    
                    int gpuLayers = 0;
                    int threadCount = 0;
                    int batchSize = 0;
                    try {
                        gpuLayers = LlamaHelper.getGPULayers();
                        threadCount = LlamaHelper.getThreadCount();
                        batchSize = LlamaHelper.getBatchSize();
                    } catch (Exception e) {
                        Log.e(TAG, "Error getting GPU layers/threads: " + e.getMessage());
                    }
                    
                    // 卸载层数的目标设备可能是 NPU，措辞不再写死 "GPU"
                    openclSummary.append("卸载层数: ");
                    if (gpuLayers > 0) {
                        openclSummary.append(gpuLayers).append(" 层（目标 ")
                                .append(backendLabelOf(shown)).append("）\n");
                    } else {
                        openclSummary.append("0 层（纯 CPU 推理）\n");
                    }
                    openclSummary.append(getString(R.string.h_e245509d)).append(threadCount).append("\n");
                    openclSummary.append(getString(R.string.h_2e8e2dff)).append(batchSize).append("\n");
                    
                    try {
                        String gpuInfoJson = LlamaHelper.detectGPUInfo();
                        Log.d(TAG, "detectGPUInfo result: " + gpuInfoJson);
                        if (gpuInfoJson != null && !gpuInfoJson.isEmpty() && !gpuInfoJson.equals("{}")) {
                            org.json.JSONObject json = new org.json.JSONObject(gpuInfoJson);
                            boolean supportsFP16 = json.optBoolean("supportsFP16", false);
                            boolean supportsFP32 = json.optBoolean("supportsFP32", false);
                            boolean supportsFP64 = json.optBoolean("supportsFP64", false);
                            boolean supportsBF16 = json.optBoolean("supportsBF16", false);
                            String supportedFPtypes = json.optString("supportedFloatingPointTypes", "Unknown");
                            String gpuName = json.optString("name", "Unknown");
                            String openclVersion = json.optString("openclVersion", "Unknown");
                            String vulkanVersion = json.optString("vulkanVersion", "Unknown");
                            long globalMemoryMB = json.optLong("globalMemoryMB", 0);
                            int maxComputeUnits = json.optInt("maxComputeUnits", 0);
                            int maxFrequencyMHz = json.optInt("maxFrequencyMHz", 0);
                            int halfVectorWidth = json.optInt("halfVectorWidth", 0);
                            int floatVectorWidth = json.optInt("floatVectorWidth", 0);
                            int doubleVectorWidth = json.optInt("doubleVectorWidth", 0);
                            boolean isAdreno = json.optBoolean("isAdreno", false);
                            
                            // CAPS-OPENCL-ONLY(2026-10-09)：这一段的数字全部来自
                            // clGetDeviceInfo，因此**只覆盖 OpenCL 设备**。Vulkan / Hexagon(NPU)
                            // 的能力在下方「加速设备检测」区块由 ggml 接口统一报出，这里明确标注
                            // 归属，避免被读成"所有加速设备的能力"。
                            openclSummary.append("\n🎮 OpenCL 设备信息（仅 OpenCL 后端）:\n");
                            openclSummary.append(getString(R.string.h_96078d40)).append(gpuName).append("\n");
                            if (isAdreno) {
                                openclSummary.append(getString(R.string.h_2f2a469a));
                            }
                            
                            openclSummary.append(getString(R.string.h_cecbcbbb));
                            // ACCEL-BACKEND：加速后端与 NPU 已在上面统一报出，此处只留
                            // OpenCL/Vulkan 的**库版本**信息（它们决定后端是否可用）。
                            openclSummary.append("   OpenCL 版本: ")
                                    .append(openclVersion.isEmpty() ? "驱动未上报" : openclVersion).append("\n");
                            openclSummary.append("   Vulkan 版本: ")
                                    .append(vulkanVersion.isEmpty() || "Unknown".equals(vulkanVersion) ? "未知" : vulkanVersion).append("\n");
                            
                            if (globalMemoryMB > 0) {
                                openclSummary.append(getString(R.string.h_a33eb547));
                                openclSummary.append(getString(R.string.h_73a1214e)).append(formatMemory(globalMemoryMB)).append("\n");
                            }
                            
                            openclSummary.append(getString(R.string.h_c199f08c));
                            if (maxComputeUnits > 0) {
                                openclSummary.append(getString(R.string.h_9604bfc3)).append(maxComputeUnits).append(" EU\n");
                            }
                            if (maxFrequencyMHz > 0) {
                                openclSummary.append(getString(R.string.h_c1d852fa)).append(maxFrequencyMHz).append(" MHz\n");
                            }
                            
                            // FP-SUPPORT(2026-10-09)：原先靠 " (向量宽度: " 这类只有左括号的
                            // 字符串片段拼接，每种精度各占一行，输出成
                            //   "FP16: 支持 ✅（向量宽度:" / "64)" 两行
                            // 既错位又松散。改为紧凑表格：一行一种精度，宽度不适用就不显示。
                            openclSummary.append(getString(R.string.h_cee4b0b1));
                            if (supportsFP16) {
                                openclSummary.append("  FP16: 支持");
                                if (halfVectorWidth > 0) openclSummary.append(" (向量宽度 ").append(halfVectorWidth).append(")");
                                openclSummary.append("\n");
                            }
                            if (supportsBF16) {
                                openclSummary.append("  BF16: 支持\n");
                            }
                            if (supportsFP32) {
                                openclSummary.append("  FP32: 支持");
                                if (floatVectorWidth > 0) openclSummary.append(" (向量宽度 ").append(floatVectorWidth).append(")");
                                openclSummary.append("\n");
                            }
                            if (supportsFP64) {
                                openclSummary.append("  FP64: 支持");
                                if (doubleVectorWidth > 0) openclSummary.append(" (向量宽度 ").append(doubleVectorWidth).append(")");
                                openclSummary.append("\n");
                            }
                            if (!supportsFP16 && !supportsBF16 && !supportsFP32 && !supportsFP64) {
                                openclSummary.append("  无浮点能力上报\n");
                            }
                            if (!supportedFPtypes.isEmpty() && !supportedFPtypes.equals("Unknown")) {
                                openclSummary.append(getString(R.string.h_0c7ce23a)).append(supportedFPtypes).append("\n");
                            }
                        } else {
                            openclSummary.append(getString(R.string.h_9c69dc4d));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing GPU info: " + e.getMessage(), e);
                        openclSummary.append(getString(R.string.h_60df6a17)).append(e.getMessage()).append(")\n");
                    }
                    
                    try {
                        openclDetail[0] = LlamaHelper.getOpenCLInfo();
                    } catch (Exception e) {
                        Log.e(TAG, "Error getting OpenCL info: " + e.getMessage());
                        openclDetail[0] = "获取详细信息失败: " + e.getMessage();
                    }
                } else {
                    openclSummary.append(getString(R.string.h_3bbc5f19));
                    openclSummary.append(getString(R.string.h_a27e9f97));
                    openclDetail[0] = "Native库未加载";
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting OpenCL info: " + e.getMessage(), e);
                openclSummary.append(getString(R.string.h_02e1d6c4)).append(e.getMessage());
                openclDetail[0] = "获取加速信息失败: " + e.getMessage();
            }

            // -------- 加速设备列表（ggml 设备枚举：CPU / OpenCL / Vulkan / HTP…）--------
            try {
                openclDetail[0] = buildDeviceList();
            } catch (Exception e) {
                Log.e(TAG, "Error building device list: " + e.getMessage(), e);
                openclDetail[0] = "设备枚举失败: " + e.getMessage();
            }

            // -------- 推理运行时（KV 缓存 / 上下文占用 / native 内存账本）--------
            try {
                runtimeInfo.append(buildRuntimeInfo());
            } catch (Exception e) {
                Log.e(TAG, "Error building runtime info: " + e.getMessage(), e);
                runtimeInfo.append("读取失败: ").append(e.getMessage()).append("\n");
            }
            
            mainHandler.post(() -> {
                if (deviceInfoTextView != null) {
                    deviceInfoTextView.setText(deviceInfo.toString());
                }
                if (openclStatusTextView != null) {
                    openclStatusTextView.setText(openclSummary.toString());
                }
                if (openclInfoTextView != null) {
                    openclInfoTextView.setText(openclDetail[0] != null ? openclDetail[0] : "");
                }
                if (runtimeInfoTextView != null) {
                    runtimeInfoTextView.setText(runtimeInfo.toString());
                }
                loading = false;
                // 按钮复位统一走 refreshDone（原实现只在 post 里复位，若工作线程在 post
                // 之前抛异常就永久禁用）
                refreshDone.run();
            });
        }).start();
    }

    /** 后端键 -> 显示名，复用 LlamaHelper 的单一映射；空值按"未知/纯 CPU"处理。 */
    private String backendLabelOf(String backend) {
        if (backend == null || backend.isEmpty()) return "未使用加速设备（CPU）";
        return LlamaHelper.backendLabel(backend);
    }

    /**
     * 加速设备能力列表：对**每个** ggml 设备展示显存与能力位。
     *
     * <p>CAPS-ALL-DEVICES(2026-10-09)：此前只查 OpenCL（它直接调 clGetDeviceInfo），
     * Vulkan / Hexagon(NPU) / CPU 的能力完全缺失。现改用 ggml 的设备无关接口
     * （getDeviceCaps），全部设备一视同仁。</p>
     */
    private String buildDeviceList() {
        StringBuilder sb = new StringBuilder();
        String json = LlamaHelper.getDeviceCaps();
        org.json.JSONArray arr;
        try {
            arr = new org.json.JSONArray(json);
        } catch (org.json.JSONException e) {
            sb.append("设备能力解析失败: ").append(e.getMessage()).append("\n");
            return sb.toString();
        }
        sb.append("ggml 设备数: ").append(arr.length()).append("\n");
        if (arr.length() == 0) {
            sb.append("\n（未枚举到设备，Native 库可能未加载）\n");
            return sb.toString();
        }

        // 汇总已注册后端（同一后端的多个设备只算一个），便于与"库"对照：
        // 本机验证 ggml_backend_load_all() 后为 OpenCL / Vulkan / HTP / CPU 四类。
        java.util.LinkedHashSet<String> backends = new java.util.LinkedHashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject d = arr.optJSONObject(i);
            if (d == null) continue;
            String b = d.optString("backend", "");
            if (!b.isEmpty()) backends.add(b);
        }
        sb.append("已注册后端 (").append(backends.size()).append("): ")
          .append(android.text.TextUtils.join(", ", backends)).append("\n");
        sb.append("说明: 本工程四个后端均静态编入 libllama-jni.so，故没有独立的\n")
          .append("      libggml-vulkan / -opencl / -hexagon.so；运行时使用系统\n")
          .append("      libvulkan.so 与 libOpenCL.so；HTP 另需 libggml-htp-vNN.so\n")
          .append("      （DSP skel，不是 ggml 后端，不产生设备）。\n");
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject d = arr.optJSONObject(i);
            if (d == null) continue;
            String name = d.optString("name", "?");
            String type = d.optString("type", "?");
            String desc = d.optString("desc", "");
            String backend = d.optString("backend", "?");
            boolean isNpu = backend.contains("HTP") || backend.contains("Hexagon");

            sb.append("\n[").append(i).append("] ").append(name);
            if (isNpu) sb.append("   <= NPU");
            sb.append("\n");
            sb.append("    backend : ").append(backend).append("\n");
            sb.append("    type    : ").append(type);
            if (type.equals("GPU") && isNpu) sb.append("（NPU 作为 GPU 设备参与 -ngl）");
            sb.append("\n");
            if (!desc.isEmpty()) sb.append("    desc    : ").append(desc).append("\n");
        }
        return sb.toString();
    }

    /** 推理运行时：聊天会话上下文 + 生成阶段 + 内存账本 + native KVC 统计。 */
    private String buildRuntimeInfo() {
        StringBuilder sb = new StringBuilder();
        boolean modelInit = LlamaHelper.isModelInitialized();
        sb.append("模型已加载: ").append(modelInit ? "是" : "否").append("\n");

        // CONTEXT-SOURCE(2026-10-09)：本页此前把两个**不同对象**的读数混在一起显示，
        // 且都不标归属，读起来自相矛盾：
        //   nativeGetContextSize/UsedTokens 读 NativeChatContext（聊天会话上下文）
        //   nativeGetKvCacheStats            读 s_helperContext（helper 上下文）
        // 于是出现"上下文: 0/0 tokens"与"上下文窗口: 12288 已用 47"并存。
        // 现在按归属分组显示。
        sb.append("\n[聊天会话上下文]\n");
        sb.append("  窗口: ").append(LlamaHelper.getContextSize())
          .append("   已用: ").append(LlamaHelper.getContextUsedTokens())
          .append("   剩余: ").append(LlamaHelper.getContextRemainingTokens()).append("\n");

        sb.append("\n[生成状态]\n");
        String phase = LlamaHelper.getGenPhase();
        if (phase != null && !phase.isEmpty()) {
            try {
                org.json.JSONObject g = new org.json.JSONObject(phase);
                sb.append("  阶段: ").append(g.optString("phase", "?"))
                  .append("   停止原因: ").append(g.optString("stop_cause", "-"))
                  .append("   运行中: ").append(g.optBoolean("running", false) ? "是" : "否").append("\n");
            } catch (Exception e) {
                sb.append("  阶段: 解析失败\n");
            }
        } else {
            sb.append("  阶段: 无数据\n");
        }

        // helper 上下文的 KV 增量缓存统计（反映 Agent 多轮 prefill 复用情况）。
        // 未跑过该路径时全为 0，属正常，故显式说明，避免被误读成"缓存坏了"。
        sb.append("\n[prefill 增量缓存 · helper 上下文]\n");
        String kv = LlamaHelper.getKvCacheStats();
        if (kv != null && !kv.isEmpty()) {
            try {
                org.json.JSONObject j = new org.json.JSONObject(kv);
                if (j.has("error")) {
                    sb.append("  状态: ").append(j.optString("error")).append("\n");
                } else {
                    int plans = j.optInt("plans", 0);
                    sb.append("  策略: ").append(j.optString("strategy", "?"))
                      .append("   命中率: ").append(String.format("%.1f%%", j.optDouble("hit_rate_pct", 0.0)))
                      .append("\n");
                    sb.append("  已缓存: ").append(j.optInt("cached_npast", 0)).append(" tokens")
                      .append("   匹配: ").append(j.optInt("matched_len", 0)).append("\n");
                    sb.append("  计划数: ").append(plans)
                      .append("（inc=").append(j.optInt("inc", 0))
                      .append(" part=").append(j.optInt("part", 0))
                      .append(" full=").append(j.optInt("full", 0)).append("）\n");
                    if (plans == 0) {
                        sb.append("  说明: 尚未产生缓存计划（未走 Agent 多轮 prefill 路径）\n");
                    }
                    String reason = j.optString("full_reason", "");
                    if (!reason.isEmpty() && !"unknown".equals(reason)) {
                        sb.append("  上次全量原因: ").append(reason).append("\n");
                    }
                }
            } catch (Exception e) {
                sb.append("  状态: 解析失败 ").append(e.getMessage()).append("\n");
            }
        } else {
            sb.append("  状态: 无数据（模型未加载）\n");
        }

        sb.append("\n[运行时参数]\n");
        sb.append("  线程数: ").append(LlamaHelper.getThreadCount())
          .append("   batch: ").append(LlamaHelper.getBatchSize()).append("\n");
        sb.append("  内存池预算: ").append(LlamaHelper.getMemoryPoolSize()).append(" MB\n");
        sb.append("  模型架构: ").append(LlamaHelper.getModelArchitecture())
          .append("   n_ctx_train: ").append(LlamaHelper.getModelNctx()).append("\n");
        return sb.toString();
    }
    
    private String formatMemory(long mb) {
        if (mb < 1024) {
            return mb + " MB";
        }
        float gb = mb / 1024.0f;
        return String.format("%.2f GB (%.0f MB)", gb, (float)mb);
    }
}
