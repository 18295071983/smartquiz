# Adreno 840 Vulkan device lost：llama.cpp 崩溃点与降级口径

- 设备：25113PN0EC / SM8850 / Adreno 840 / 驱动 `0842.44.1` / Vulkan API `1.4.295`
- 复现：`gpu_backend=vulkan`，加载 Qwen3.5-2B-Q4_0，prefill 一个约 4.5k token 的 Agent prompt
- 现象：prefill 阶段 SIGSEGV（`Fatal signal 11 during inference`），同一 prompt 走 OpenCL 正常

## 1. 真正的崩溃点

关闭 SIGSEGV 兜底取 tombstone，关键栈：

```text
#00 vulkan.adreno.so  (+192)
#01 vulkan.adreno.so  (qglinternal::vkGetDeviceFaultInfoEXT +108)
...
#09 libllama-jni.so   (ggml_backend_sched_graph_compute_async)
#10 libllama-jni.so   (llama_context::graph_compute)
#12 libllama-jni.so   (llama_context::decode)
#14 libllama-jni.so   (InferenceContext::generateStreamIncremental)
```

崩溃前实际发生的是：

```text
ggml_vulkan: device lost on Vulkan0
Exception in generateStreamIncremental: vk::Queue::submit: ErrorDeviceLost
```

即：GPU 先 device lost；ggml-vulkan 在错误处理里调用 `vkGetDeviceFaultInfoEXT`
采集诊断，而 Adreno 840 驱动在该状态下执行这个函数时空指针写（fault addr 0x0），
把一个可捕获的提交失败升级成进程级 SIGSEGV。

## 2. 容易误判的日志

同一进程里的：

```text
AdrenoVK: Shader compilation failed for shaderType: 0
AdrenoVK: Pipeline create failed
```

**不是 ggml-vulkan**。给 `ggml_vk_create_pipeline_*` 补上带 shader 名的
`GGML_LOG_ERROR` 后，ggml 侧没有任何 pipeline 失败记录；这些来自同一进程的
WebView/Chromium Vulkan 实例（HWUI 也走 Vulkan）。不要按这几行去关 ggml 的 shader。

## 3. 已做的 llama.cpp 修复

文件：`src/main/cpp/llama.cpp/ggml/src/ggml-vulkan/ggml-vulkan.cpp`

1. `ggml_vk_print_device_fault_info`：Qualcomm/Adreno 直接跳过
   `vkGetDeviceFaultInfoEXT`，改为一行 WARN。驱动扩展仍可枚举，但不再调用。
2. pipeline 创建失败从 `std::cerr` 改为 `GGML_LOG_ERROR("%s ...", pipeline->name)`；
   Android 上 stderr 不进日志，原先完全看不到失败的是哪条管线。
3. GPU 能力行从 DEBUG 提升到 INFO（fp16/matrix cores/int dot 等）。

该修复以 patch 形式随主仓库留存：
`src/main/cpp/patches/llama.cpp/0001-vulkan-skip-broken-device-fault-query-on-Adreno.patch`
（nested repo 提交 `3659cc36`，基线 `4e5f228d`）。

修复后同一 prompt 不再 SIGSEGV，错误路径变为：

```text
device lost on Vulkan0 (device fault info query skipped on Adreno)
Exception in generateStreamIncremental: vk::Queue::submit: ErrorDeviceLost
[GenSM] PREPROCESS -> ERROR
```

## 4. 已排除的变量（单变量 A/B）

- `GGML_VK_DISABLE_COOPMAT=1`：仍 device lost。
- `GGML_VK_DISABLE_ASYNC=1` + `GGML_VK_SERIALIZE_SUBMISSIONS=1`：仍失败，且 prefill 直接卡住。
- 原有 `GGML_VK_DISABLE_BFLOAT16 / INTEGER_DOT_PRODUCT / DOT2` 保持不变。

结论：残余的 `ErrorDeviceLost` 在驱动执行阶段，不是 llama.cpp 能配置掉的；
公开同款案例也是 Adreno 840 + `vulkan.adreno.so` 崩溃（Tencent ncnn #6449）。

## 5. 产品层降级

新增 `VulkanFaultGuard`（`ai/jni`）：

- `chatJson` 回调里观测到 `ErrorDeviceLost` / `device lost` → 置位 `vulkan_fault_guard`；
- 下一次模型装载把**本次下发**的 `vulkan` 替换为 `opencl`，不写回 `gpu_backend` 偏好；
- 用户在设置页重新选择任意后端 → `clear()`，允许再次验证驱动；
- `auto` 在 native 侧本来就不再选 Vulkan（2026-11-08 起），所以只处理显式 `vulkan`。

## 6. 结论

- llama.cpp 的**错误处理调用**是 bug，已修：不再调 Adreno 坏掉的故障查询扩展。
- 驱动的 Vulkan 计算失败（`vkQueueSubmit: ErrorDeviceLost`）修不了；Vulkan 仍只应作为显式选项，
  默认继续走 OpenCL/HTP。要真正启用 Vulkan，需要等厂商驱动更新，或 root 后用 Mesa Turnip。
