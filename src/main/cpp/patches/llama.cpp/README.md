# llama.cpp 本地补丁

`src/main/cpp/llama.cpp/` 被主仓库 `.gitignore` 排除，是在本机维护的独立 git 仓库。
这里的 patch 用来把必须随主仓库留存的修改单独保存，换机器/重新 clone 后按顺序应用。

基线：`4e5f228d`（nested repo `master`，Merge upstream llama.cpp 0.9 into local master）

应用（在主仓库根目录执行）：

```bash
git -C src/main/cpp/llama.cpp am \
  "$(pwd)/src/main/cpp/patches/llama.cpp/0001-vulkan-skip-broken-device-fault-query-on-Adreno.patch"
```

## 0001-vulkan-skip-broken-device-fault-query-on-Adreno

- 对应 nested repo 提交：`3659cc36`
- 内容：Adreno 840 驱动在 device lost 后，`vkGetDeviceFaultInfoEXT` 会空指针崩溃；
  Qualcomm 设备跳过该诊断调用，只记 WARN。
- 同时把 pipeline 创建失败从 stderr 改到 `GGML_LOG_ERROR`（带 shader 名），
  GPU 能力行升到 INFO。
- 该修复只避免"错误处理二次崩溃"；`vkQueueSubmit: ErrorDeviceLost` 本身是驱动问题，
  主仓库侧由 `VulkanFaultGuard` 自动降级到 OpenCL。
