# 遗留问题归档：ggml 设备能力查询（get_props）原生崩溃

> 状态：**未定位，未启用**。归档日期 2026-10-09。
> 结论一句话：`ggml_backend_dev_get_props()` 在本机会触发 SIGSEGV，API 用法已核实无误，
> 崩溃位于某个后端设备的 `iface.get_props` 实现内部。在定位原因前，设备信息页不展示
> 显存与能力位。

---

## 1. 现象

设备信息页打开「加速设备检测」需要枚举 ggml 设备并读取显存/能力位时，进程立刻崩溃：

```
Zygote: Process <pid> exited due to signal 11 (Segmentation fault)
ActivityManager: Process com.oilquiz.app (pid <pid>) has died: fg TOP
```

崩溃发生在进入 `DeviceInfoActivity` 后约 50 ms，界面已创建但内容尚未填充。

---

## 2. 崩溃栈（真机，Android 16 / arm64）

```
#00  libsigchain.so  LogStack()+208
#01  libsigchain.so  art::SignalChain::Handler(int, siginfo*, void*)+1172
#02  [vdso]
#03  libllama-jni.so (+0)                                    <-- 无符号：崩溃发生在 native
#04  libllama-jni.so Java_com_oilquiz_app_ai_jni_LlamaHelper_nativeGetDeviceCaps+384
#05  libart.so art_quick_generic_jni_trampoline+144
...
#10  base.apk com.oilquiz.app.ai.jni.LlamaHelper.getDeviceCaps(...)
#11  ... AIChatActivity / DeviceInfoActivity 调用链
```

关键点：`#03` 与 `#04` 都在我们自己的 `libllama-jni.so` 内，`#04` 的偏移 `+384` 指向
新增的 `nativeGetDeviceCaps`，`#03` 是它调用进去的下一层。

---

## 3. 已排除的可能（均有依据）

| 假设 | 排除依据 |
|---|---|
| API 用法写错 | 签名逐字核对无误：`GGML_API void ggml_backend_dev_get_props(ggml_backend_dev_t, struct ggml_backend_dev_props *)`；C 层实现是 `GGML_ASSERT(device); memset(props, 0, sizeof(*props)); device->iface.get_props(device, props);`，我们传的是栈上零初始化结构体地址 |
| `props` 未初始化 | 已 `= {}` 零初始化；C 层还会自己 `memset` |
| `ggml_backend_dev_host_buffer_type` 为 NULL | 该 C 包装函数**自带 NULL 防护**（`if (device->iface.get_host_buffer_type == NULL) return NULL;`） |
| `ggml-virtgpu` remoting 设备 | **`GGML_VIRTGPU` 默认 `OFF`，本项目未编入**（见 `ggml/CMakeLists.txt` 与工程 CMake 均未开启）。这是当时的**错误结论**，已撤回 |
| `libggml-vulkan.so` 缺失 | 与本问题无关：四个后端都**静态编入** `libllama-jni.so`（见下节证据） |

### 静态链接的决定性证据

解析 APK 内 `libllama-jni.so` 的 ELF `DT_NEEDED`：

```
ggml/llama 依赖: 无
其他依赖: liblog, libdl, libandroid, libz, libm, libOpenCL.so, libvulkan.so, libomp.so
```

⇒ CPU / OpenCL / Vulkan / Hexagon 四个后端**全部静态编入单一 `libllama-jni.so`**，
因此**不存在** `libggml-vulkan.so` / `libggml-opencl.so`（我们自己的那份）。
APK 里出现的 `libggml-opencl.so`、`libggml-hexagon.so`、`libggml-base.so`、`libggml-cpu.so`
来自 **GenieX AAR**（`libs/geniex-android-0.8.0.aar`，其自身 NEEDED `libggml-base.so`），
我们的 `libllama-jni.so` **完全不依赖**它们。

---

## 4. 尚未排除的可能（下一步要查的方向）

1. **线程上下文问题（首要怀疑）**
   `nativeGetDeviceCaps` 由 `DeviceInfoActivity.loadDeviceInfo()` 的**后台线程**调用。
   而设备上下文（HTP 会话、OpenCL/Vulkan 上下文）是在模型加载线程创建的。
   若某后端的 `get_props` 需要线程本地（TLS）或已初始化的设备上下文，跨线程调用即可能解引用野指针。
   - 验证方法：在**主线程**（或模型加载线程）调用同一个函数，对比是否仍崩。
2. **具体哪个设备崩**
   逐设备隔离（只对 device i 调用 `get_props`），确定是 HTP0 / GPUOpenCL / Vulkan0 / CPU 中的哪一个。
   注意 `HTP0` 实测 `type=GPU`（不是 ACCEL），并非按类型可区分。
3. **某个后端的 `get_props` 实现缺 NULL 防护**
   各后端均有 `get_props` 实现（`ggml-hexagon.cpp:7841`、`ggml-opencl.cpp:13007`、
   `ggml-vulkan.cpp:16079`、`ggml-cpu.cpp:499`），需逐个审查其内部是否解引用了未初始化的
   `dev->context`。
4. **`GGML_ASSERT` 在 Release 下的行为**
   若断言被关掉，非法 `device` 会一路带到 `device->iface.get_props` 才崩。

---

## 5. 复现与定位手段

### 逐项隔离探针（曾实现，已按用户要求移除）

按步进调用，定位到具体 API：

| step | 累计调用 |
|---|---|
| 0 | 仅 `ggml_backend_dev_count` / `_get` |
| 1 | + `ggml_backend_dev_name` |
| 2 | + `ggml_backend_dev_description` |
| 3 | + `ggml_backend_dev_type` |
| 4 | + `ggml_backend_dev_memory` |
| 5 | + `ggml_backend_dev_get_props` |
| 6 | + `ggml_backend_dev_buffer_type` |
| 7 | + `ggml_backend_dev_host_buffer_type` |

用 `__android_log_print` 在每步前后打点（不要用封装的 `LOGI`，避免线程/TAG 干扰），
崩溃前最后一条日志即指向出问题的 API。

### 带符号调试

`ndk-stack` / LLDB 需要未 strip 的 `libllama-jni.so`。
当前 Release `-O2` 构建下 `#03` 显示为 `(+0)`，无法定位到函数。
建议：`-DCMAKE_BUILD_TYPE=RelWithDebInfo` 或保留 `.symtab`，再取 tombstone 解析。

---

## 6. 当前代码状态（已合入，commit e9d1b212）

`src/main/cpp/native-lib.cpp` 的 `Java_..._nativeGetDeviceCaps` 当前**只用已验证可用的接口**：

```cpp
ggml_backend_dev_count / _get / _name / _description / _type / _backend_reg
```

返回字段：`backend` / `name` / `desc` / `type`。
**不包含** `memory_*` / `mem_type` / `mem_align` / `caps_*` —— 这些依赖
`ggml_backend_dev_get_props` 等接口，未启用。

界面（`DeviceInfoActivity.buildDeviceList()`）据此只展示四个字段，
**不显示"未上报"之类的占位行**（早期版本曾有，已移除）。

### 真机验证结果（正常，不崩）

```
ggml 设备数: 4
已注册后端 (4): Vulkan, OpenCL, HTP, CPU
[0] Vulkan0     backend: Vulkan  type: IGPU  desc: Adreno (TM) 840
[1] GPUOpenCL   backend: OpenCL  type: GPU   desc: QUALCOMM Adreno(TM) 840
[2] HTP0  <= NPU  backend: HTP  type: GPU（NPU 作为 GPU 设备参与 -ngl）  desc: Hexagon
[3] CPU         backend: CPU     type: CPU   desc: CPU
```

与 native 日志 `ggml backend detected 4 devices after ggml_backend_load_all()` 一致。

---

## 7. 若将来修复，需要动的点

1. `native-lib.cpp`：恢复 `ggml_backend_dev_get_props` + `ggml_backend_buft_name` +
   `ggml_backend_buft_get_alignment` + `ggml_backend_dev_host_buffer_type`，
   并填回 `memory_free_mb` / `memory_total_mb` / `mem_type` / `mem_align` /
   `caps_async` / `caps_host_buffer` / `caps_from_host` / `caps_events` / `caps_mmap` /
   `host_buft` 字段。
2. `DeviceInfoActivity.buildDeviceList()`：恢复显存与能力位的展示分支。
3. 建议同时加一个**运行时开关**（如 `nativeSetCapsProbeEnabled(bool)`），
   便于在出问题的设备上关闭该查询而不必重新发版。

---

## 8. 环境

| 项 | 值 |
|---|---|
设备 | Xiaomi 25113PN0EC（codename `canoe`，`ro.product.board=canoe`）|
SoC | SM8850，Adreno 840，Hexagon v81 |
系统 | Android 16（API 36）|
ABI | arm64-v8a |
后端 | CPU / OpenCL / Vulkan / Hexagon(HTP0)，全部静态编入 `libllama-jni.so` |
构建 | Release，`-O2`，符号已剥离 |
