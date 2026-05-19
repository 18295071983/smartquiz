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
    private MaterialButton btnRefresh;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.setTitle("设备信息");
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
            btnRefresh = findViewById(R.id.btn_refresh);
            
            loadDeviceInfo();
            
            if (btnRefresh != null) {
                btnRefresh.setOnClickListener(v -> {
                    btnRefresh.setEnabled(false);
                    btnRefresh.setText("刷新中...");
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
        Log.d(TAG, "onResume: refreshing device info");
        loadDeviceInfo();
    }
    
    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
    
    private void loadDeviceInfo() {
        new Thread(() -> {
            final StringBuilder deviceInfo = new StringBuilder();
            final StringBuilder openclSummary = new StringBuilder();
            final String[] openclDetail = new String[1];
            
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
                
                deviceInfo.append("品牌: ").append(brand != null ? brand : "未知").append("\n");
                deviceInfo.append("型号: ").append(model != null ? model : "未知").append("\n");
                deviceInfo.append("设备: ").append(device != null ? device : "未知").append("\n");
                deviceInfo.append("硬件: ").append(hardware != null ? hardware : "未知").append("\n");
                deviceInfo.append("GPU: ").append(gpu != null ? gpu : "未知").append("\n");
                
                if (isAdreno && adrenoSeries != null) {
                    deviceInfo.append("GPU系列: ").append(adrenoSeries).append("\n");
                }
                
                deviceInfo.append("CPU核心: ").append(cpuCores).append(" 核").append("\n");
                deviceInfo.append("总内存: ").append(formatMemory(totalMemory)).append("\n");
                deviceInfo.append("可用内存: ").append(formatMemory(freeMemory)).append("\n");
                deviceInfo.append("Android版本: ").append(androidVersion).append("\n");
                deviceInfo.append("ABI架构: ").append(abis != null ? abis : "未知").append("\n");
                
            } catch (Exception e) {
                Log.e(TAG, "Error getting device info: " + e.getMessage());
                deviceInfo.append("获取设备信息失败: ").append(e.getMessage()).append("\n");
            }
            
            try {
                boolean libLoaded = LlamaHelper.isLibraryLoaded();
                openclSummary.append("Native库: ").append(libLoaded ? "已加载 ✅" : "未加载 ❌").append("\n");
                
                if (libLoaded) {
                    boolean openclLoaded = LlamaHelper.isOpenCLLoaded();
                    boolean gpuWorking = LlamaHelper.isGPUWorking();
                    boolean modelInit = LlamaHelper.isModelInitialized();
                    
                    openclSummary.append("OpenCL库: ").append(openclLoaded ? "已加载 ✅" : "未加载 ❌").append("\n");
                    
                    if (openclLoaded) {
                        openclSummary.append("GPU加速: ");
                        if (gpuWorking) {
                            openclSummary.append("已启用 ✅\n");
                        } else {
                            openclSummary.append("已就绪（等待模型加载）⚠️\n");
                        }
                    } else {
                        openclSummary.append("GPU加速: 未启用（OpenCL不可用）❌\n");
                    }
                    
                    openclSummary.append("模型初始化: ").append(modelInit ? "已加载 ✅" : "未加载 ⚠️").append("\n");
                    
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
                    
                    openclSummary.append("\n📊 运行时配置:\n");
                    if (gpuLayers > 0) {
                        openclSummary.append("   GPU层数: ").append(gpuLayers).append(" / 全部\n");
                        openclSummary.append("   加速模式: GPU + CPU 混合\n");
                    } else {
                        openclSummary.append("   GPU层数: 0\n");
                        openclSummary.append("   加速模式: 纯CPU\n");
                    }
                    openclSummary.append("   线程数: ").append(threadCount).append("\n");
                    openclSummary.append("   批处理大小: ").append(batchSize).append("\n");
                    
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
                            
                            openclSummary.append("\n🎮 GPU硬件信息:\n");
                            openclSummary.append("   设备: ").append(gpuName).append("\n");
                            if (isAdreno) {
                                openclSummary.append("   厂商: Qualcomm Adreno\n");
                            }
                            
                            openclSummary.append("\n🔧 后端支持:\n");
                            openclSummary.append("   OpenCL: ").append(openclLoaded ? "已加载" : "未加载");
                            if (!openclVersion.isEmpty() && !openclVersion.equals("Unknown")) {
                                openclSummary.append(" (v").append(openclVersion).append(")");
                            }
                            openclSummary.append("\n");
                            openclSummary.append("   Vulkan: ").append(vulkanVersion).append("\n");
                            
                            if (globalMemoryMB > 0) {
                                openclSummary.append("\n💾 显存信息:\n");
                                openclSummary.append("   总显存: ").append(formatMemory(globalMemoryMB)).append("\n");
                            }
                            
                            openclSummary.append("\n⚡ 计算能力:\n");
                            if (maxComputeUnits > 0) {
                                openclSummary.append("   计算单元: ").append(maxComputeUnits).append(" EU\n");
                            }
                            if (maxFrequencyMHz > 0) {
                                openclSummary.append("   频率: ").append(maxFrequencyMHz).append(" MHz\n");
                            }
                            
                            openclSummary.append("\n✅ 浮点支持:\n");
                            if (supportsFP16) {
                                openclSummary.append("   FP16: 支持 ✅");
                                if (halfVectorWidth > 0) {
                                    openclSummary.append(" (向量宽度: ").append(halfVectorWidth).append(")");
                                }
                                openclSummary.append("\n");
                            }
                            if (supportsBF16) {
                                openclSummary.append("   BF16: 支持 ✅\n");
                            }
                            if (supportsFP32) {
                                openclSummary.append("   FP32: 支持 ✅");
                                if (floatVectorWidth > 0) {
                                    openclSummary.append(" (向量宽度: ").append(floatVectorWidth).append(")");
                                }
                                openclSummary.append("\n");
                            }
                            if (supportsFP64) {
                                openclSummary.append("   FP64: 支持 ✅\n");
                            }
                            if (!supportedFPtypes.isEmpty() && !supportedFPtypes.equals("Unknown")) {
                                openclSummary.append("\n支持类型: ").append(supportedFPtypes).append("\n");
                            }
                        } else {
                            openclSummary.append("GPU检测: 未检测到详细信息\n");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing GPU info: " + e.getMessage(), e);
                        openclSummary.append("GPU检测: 解析失败 (").append(e.getMessage()).append(")\n");
                    }
                    
                    try {
                        openclDetail[0] = LlamaHelper.getOpenCLInfo();
                    } catch (Exception e) {
                        Log.e(TAG, "Error getting OpenCL info: " + e.getMessage());
                        openclDetail[0] = "获取详细信息失败: " + e.getMessage();
                    }
                } else {
                    openclSummary.append("\nNative库尚未加载，无法获取GPU信息\n");
                    openclSummary.append("请先在AI服务页面初始化模型\n");
                    openclDetail[0] = "Native库未加载";
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting OpenCL info: " + e.getMessage(), e);
                openclSummary.append("\n获取加速信息失败: ").append(e.getMessage());
                openclDetail[0] = "获取加速信息失败: " + e.getMessage();
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
                if (btnRefresh != null) {
                    btnRefresh.setEnabled(true);
                    btnRefresh.setText("刷新");
                }
            });
        }).start();
    }
    
    private String formatMemory(long mb) {
        if (mb < 1024) {
            return mb + " MB";
        }
        float gb = mb / 1024.0f;
        return String.format("%.2f GB (%.0f MB)", gb, (float)mb);
    }
}
