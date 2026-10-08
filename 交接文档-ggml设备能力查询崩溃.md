# 交接文档：ggml 设备能力查询（get_props）原生崩溃

**状态：未定位，未启用。** 归档于 2026-10-09，对应 commit `e9d1b212`。
**完整技术记录见 [`docs/research/遗留问题-ggml设备能力查询崩溃.md`](docs/research/遗留问题-ggml设备能力查询崩溃.md)。**

---

## 一句话

`ggml_backend_dev_get_props()` 在本机（Xiaomi 25113PN0EC / SM8850 / Android 16）会触发
**SIGSEGV**。API 用法已逐字核实**无误**，崩溃在某个后端设备的 `iface.get_props`
实现内部。定位前，设备信息页**不展示显存与能力位**。

---

## 崩溃摘要

```
#03  libllama-jni.so (+0)                                    <-- 无符号，崩溃点
#04  libllama-jni.so ..._nativeGetDeviceCaps+384
#10  base.apk com.oilquiz.app.ai.jni.LlamaHelper.getDeviceCaps(...)
```

触发路径：进入 `DeviceInfoActivity` → `loadDeviceInfo()` 后台线程 → `nativeGetDeviceCaps`。
现象：`signal 11 (Segmentation fault)`，进程 `fg TOP` 被杀。

---

## 已排除（有依据，勿重复试错）

| 假设 | 排除依据 |
|---|---|
| API 写错 | 签名与 C 层实现逐字核对无误（`GGML_ASSERT` → `memset` → `iface.get_props`）|
| `props` 未初始化 | 已零初始化，C 层还会自己 `memset` |
| `host_buffer_type` 为 NULL | 该 C 包装**自带 NULL 防护** |
| **`ggml-virtgpu` remoting 设备** | **`GGML_VIRTGPU` 默认 OFF，本项目未编入** —— 此为当时的**错误结论，已撤回** |
| `libggml-vulkan.so` 缺失 | 与本问题无关：四后端**静态编入** `libllama-jni.so`（ELF `DT_NEEDED` 已证实无任何 ggml/llama 依赖）|

---

## 下一步方向（按优先级）

1. **线程上下文**（首要怀疑）：`nativeGetDeviceCaps` 跑在后台线程，而设备上下文在模型加载线程创建。验证：改到主线程/加载线程调用是否仍崩。
2. **逐设备隔离**：只对 device *i* 调 `get_props`，确定是 HTP0 / GPUOpenCL / Vulkan0 / CPU 中的哪一个。
3. **审查该后端 `get_props` 实现**是否解引用未初始化的 `dev->context`。
4. **带符号调试**：Release `-O2` 已剥离符号，需 `RelWithDebInfo` 或保留 `.symtab` 后用 `ndk-stack` / LLDB 解析 tombstone。

---

## 当前代码状态（已合入）

`nativeGetDeviceCaps` 只用已验证可用的接口：
`ggml_backend_dev_count / _get / _name / _description / _type / _backend_reg`
⇒ 返回 `backend` / `name` / `desc` / `type` 四项。

界面只展示这四项，**不显示"未上报"之类占位行**。

修复时需动的点、逐项隔离探针的构造、环境信息，均见
[`docs/research/遗留问题-ggml设备能力查询崩溃.md`](docs/research/遗留问题-ggml设备能力查询崩溃.md)。
