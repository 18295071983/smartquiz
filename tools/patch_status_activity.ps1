# 给 AIServiceStatusActivity 加 GPU 后端开关绑定（ASCII 锚，避免中文编码问题）
$ErrorActionPreference = 'Stop'
$p = "D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ui\activity\AIServiceStatusActivity.java"
$bytes = [IO.File]::ReadAllBytes($p)
$hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
$text = [IO.File]::ReadAllText($p)
$n = "`n"

$a2 = "        gpuLayersInput = findViewById(R.id.gpu_layers_input);"
$block = @"
        // GPU backend switch (OpenCL / Vulkan / auto): saved to default SP key gpu_backend, applied to native on model load
        gpuBackendGroup = findViewById(R.id.gpu_backend_group);
        backendAuto = findViewById(R.id.backend_auto);
        backendOpencl = findViewById(R.id.backend_opencl);
        backendVulkan = findViewById(R.id.backend_vulkan);
        if (gpuBackendGroup != null) {
            // restore last choice
            String savedBackend = android.preference.PreferenceManager.getDefaultSharedPreferences(this)
                    .getString("gpu_backend", "auto");
            if ("opencl".equals(savedBackend)) backendOpencl.setChecked(true);
            else if ("vulkan".equals(savedBackend)) backendVulkan.setChecked(true);
            else backendAuto.setChecked(true);
            gpuBackendGroup.setOnCheckedChangeListener((group, checkedId) -> {
                String choice = "auto";
                if (checkedId == R.id.backend_opencl) choice = "opencl";
                else if (checkedId == R.id.backend_vulkan) choice = "vulkan";
                android.preference.PreferenceManager.getDefaultSharedPreferences(this)
                        .edit().putString("gpu_backend", choice).apply();
                LlamaHelper.setBackend(choice);
                android.widget.Toast.makeText(this, "GPU backend switched to " + choice + ", reload model to take effect", android.widget.Toast.LENGTH_SHORT).show();
            });
        }

        gpuLayersInput = findViewById(R.id.gpu_layers_input);
"@
$block = $block.Replace("`r`n", $n)
if ($text.Contains($a2)) {
    $text = $text.Replace($a2, $block)
    Write-Output "BIND=OK"
} else {
    Write-Output "BIND=MISS"
}
$enc = New-Object Text.UTF8Encoding($hasBom)
[IO.File]::WriteAllText($p, $text, $enc)
