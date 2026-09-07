# -*- coding: utf-8 -*-
# 补丁：改造 ggml-opencl.cpp 的 build_program_from_source_ex，
# 让 FA lazy-compile kernels 也走 on-disk program cache，
# 避免每次启动重新编译全部 flash-attention kernels（实测 ~3.4s）。
import io, sys

path = r"D:\qzq\smartquiz\src\main\cpp\llama.cpp\ggml\src\ggml-opencl\ggml-opencl.cpp"
with io.open(path, "r", encoding="utf-8") as f:
    src = f.read()

# 1) 在 build_program_from_source_ex 定义前插入 find helper
anchor1 = '''// if retry_queue is provided
static cl_program build_program_from_source_ex('''
inject1 = '''// Locate the first initialized OpenCL backend context (for kernel cache).
static ggml_backend_opencl_context * find_first_opencl_backend_ctx() {
    for (auto & dev_ctx : g_ggml_backend_opencl_dev_ctxs) {
        if (dev_ctx && dev_ctx->backend_ctx) {
            return dev_ctx->backend_ctx;
        }
    }
    return nullptr;
}

// if retry_queue is provided
static cl_program build_program_from_source_ex('''

c1 = src.count(anchor1)
print("anchor1 count:", c1)
if c1 != 1:
    print("ABORT1")
    sys.exit(1)
src = src.replace(anchor1, inject1, 1)

# 2) 函数开头：编译前 try_load 缓存
anchor2 = '''static cl_program build_program_from_source_ex(cl_context ctx, cl_device_id dev, const char* program_buffer, const std::string &compile_opts, bool fatal, const char *tag = nullptr, cl_command_queue retry_queue = nullptr) {
    if (tag) { GGML_LOG_INFO("ggml_opencl: compiling %s\\n", tag); }
'''
inject2 = '''static cl_program build_program_from_source_ex(cl_context ctx, cl_device_id dev, const char* program_buffer, const std::string &compile_opts, bool fatal, const char *tag = nullptr, cl_command_queue retry_queue = nullptr) {
    // On-disk compiled-program cache for lazy-compiled FA kernels: without this,
    // every process start recompiles all flash-attention variants (~3.4s).
    ggml_backend_opencl_context * bctx = find_first_opencl_backend_ctx();
    if (bctx && bctx->program_cache_initialized && !bctx->program_cache.dir.empty()) {
        cl_program p_cached = cl_program_cache_try_load(
            bctx->program_cache, ctx, dev, program_buffer, compile_opts);
        if (p_cached != nullptr) {
            GGML_LOG_INFO("ggml_opencl: kernel cache HIT %s\\n", tag ? tag : "");
            return p_cached;
        }
    }

    if (tag) { GGML_LOG_INFO("ggml_opencl: compiling %s\\n", tag); }
'''
c2 = src.count(anchor2)
print("anchor2 count:", c2)
if c2 != 1:
    print("ABORT2")
    sys.exit(1)
src = src.replace(anchor2, inject2, 1)

# 3) 编译成功后写缓存
anchor3 = '''        err = clBuildProgram(p, 0, NULL, compile_opts.c_str(), NULL, NULL);
        if (err == CL_SUCCESS) {
            return p;
        }
'''
inject3 = '''        err = clBuildProgram(p, 0, NULL, compile_opts.c_str(), NULL, NULL);
        if (err == CL_SUCCESS) {
            if (bctx && bctx->program_cache_initialized && !bctx->program_cache.dir.empty()) {
                cl_program_cache_try_save(bctx->program_cache, p, dev, program_buffer, compile_opts);
            }
            return p;
        }
'''
c3 = src.count(anchor3)
print("anchor3 count:", c3)
if c3 != 1:
    print("ABORT3")
    sys.exit(1)
src = src.replace(anchor3, inject3, 1)

with io.open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
print("PATCH OK")
