# GPU 后端界面去 OpenCL 硬编码补丁
$ErrorActionPreference = 'Stop'

# 1. 布局标题 OpenCL -> GPU 后端
$p1 = "D:\qzq\smartquiz\src\main\res\layout\activity_ai_service_status.xml"
$t1 = [IO.File]::ReadAllText($p1)
$o1 = '                            android:text="OpenCL"'
$n1 = '                            android:text="GPU 后端"'
if ($t1.Contains($o1)) { $t1 = $t1.Replace($o1, $n1); [IO.File]::WriteAllText($p1, $t1, (New-Object Text.UTF8Encoding $false)); Write-Output "布局标题: OK" } else { Write-Output "布局标题: MISS" }

# 2. AIServiceStatusActivity.updateOpenCLStatus 后端感知
$p2 = "D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ui\activity\AIServiceStatusActivity.java"
$t2 = [IO.File]::ReadAllText($p2)
$o2 = @"
            if (openclStatus != null) {
                openclStatus.setText(openclLoaded ? getString(R.string.h_bec33d31) : getString(R.string.h_467b3e03));
                openclStatus.setTextColor(openclLoaded ? getResources().getColor(R.color.success) : getResources().getColor(R.color.error));
            }
"@
$o2 = $o2.Replace("`r`n", "`n")
$n2 = @"
            if (openclStatus != null) {
                // 后端感知：显示当前 GPU 后端选择 + 引擎状态，不再硬编码 OpenCL
                String backendPref = android.preference.PreferenceManager.getDefaultSharedPreferences(this)
                        .getString("gpu_backend", "auto");
                String backendName = "vulkan".equals(backendPref) ? "Vulkan"
                        : ("opencl".equals(backendPref) ? "OpenCL" : "自动");
                openclStatus.setText(backendName + (openclLoaded ? " · 已启用" : " · 未启用"));
                openclStatus.setTextColor(openclLoaded ? getResources().getColor(R.color.success) : getResources().getColor(R.color.error));
            }
"@
$n2 = $n2.Replace("`r`n", "`n")
if ($t2.Contains($o2)) { $t2 = $t2.Replace($o2, $n2); [IO.File]::WriteAllText($p2, $t2, (New-Object Text.UTF8Encoding $false)); Write-Output "状态文本: OK" } else { Write-Output "状态文本: MISS" }

# 3. DeviceInfoActivity 硬编码 OpenCL 行改后端感知
$p3 = "D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ui\activity\DeviceInfoActivity.java"
$t3 = [IO.File]::ReadAllText($p3)
$o3 = '                            openclSummary.append("   OpenCL: ").append(openclLoaded ? getString(R.string.h_bec33d31) : getString(R.string.h_467b3e03));'
$n3 = '                            String backendPref = android.preference.PreferenceManager.getDefaultSharedPreferences(DeviceInfoActivity.this).getString("gpu_backend", "auto");' + "`n" + '                            String backendName = "vulkan".equals(backendPref) ? "Vulkan" : ("opencl".equals(backendPref) ? "OpenCL" : "自动");' + "`n" + '                            openclSummary.append("   后端: ").append(backendName).append(" · OpenCL: ").append(openclLoaded ? getString(R.string.h_bec33d31) : getString(R.string.h_467b3e03));'
if ($t3.Contains($o3)) { $t3 = $t3.Replace($o3, $n3); [IO.File]::WriteAllText($p3, $t3, (New-Object Text.UTF8Encoding $false)); Write-Output "DeviceInfo: OK" } else { Write-Output "DeviceInfo: MISS" }
