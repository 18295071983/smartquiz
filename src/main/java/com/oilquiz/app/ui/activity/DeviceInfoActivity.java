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
            btnRefresh = findViewById(R.id.btn_refresh);
            
            loadDeviceInfo();
            
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
                    
                    openclSummary.append(getString(R.string.h_790b0f05));
                    if (gpuLayers > 0) {
                        openclSummary.append(getString(R.string.h_4e1617ff)).append(gpuLayers).append(getString(R.string.h_0c717857));
                        openclSummary.append(getString(R.string.h_97c01f1d));
                    } else {
                        openclSummary.append(getString(R.string.h_689a7244));
                        openclSummary.append(getString(R.string.h_91808686));
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
                            
                            openclSummary.append(getString(R.string.h_f986d5f8));
                            openclSummary.append(getString(R.string.h_96078d40)).append(gpuName).append("\n");
                            if (isAdreno) {
                                openclSummary.append(getString(R.string.h_2f2a469a));
                            }
                            
                            openclSummary.append(getString(R.string.h_cecbcbbb));
                            String backendPref = android.preference.PreferenceManager.getDefaultSharedPreferences(DeviceInfoActivity.this).getString("gpu_backend", "auto");
                            String backendName = "vulkan".equals(backendPref) ? "Vulkan" : ("opencl".equals(backendPref) ? "OpenCL" : "自动");
                            openclSummary.append("   后端: ").append(backendName).append(" · OpenCL: ").append(openclLoaded ? getString(R.string.h_bec33d31) : getString(R.string.h_467b3e03));
                            if (!openclVersion.isEmpty() && !openclVersion.equals("Unknown")) {
                                openclSummary.append(" (v").append(openclVersion).append(")");
                            }
                            openclSummary.append("\n");
                            openclSummary.append("   Vulkan: ").append(vulkanVersion).append("\n");
                            
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
                            
                            openclSummary.append(getString(R.string.h_cee4b0b1));
                            if (supportsFP16) {
                                openclSummary.append(getString(R.string.h_23a8aa37));
                                if (halfVectorWidth > 0) {
                                    openclSummary.append(getString(R.string.h_238ba2fa)).append(halfVectorWidth).append(")");
                                }
                                openclSummary.append("\n");
                            }
                            if (supportsBF16) {
                                openclSummary.append(getString(R.string.h_8d87e7f3));
                            }
                            if (supportsFP32) {
                                openclSummary.append(getString(R.string.h_27574b57));
                                if (floatVectorWidth > 0) {
                                    openclSummary.append(getString(R.string.h_238ba2fa)).append(floatVectorWidth).append(")");
                                }
                                openclSummary.append("\n");
                            }
                            if (supportsFP64) {
                                openclSummary.append(getString(R.string.h_89d76665));
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
                    btnRefresh.setText(getString(R.string.h_694fc5ef));
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
