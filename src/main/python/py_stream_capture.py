"""线程绑定的 stdout/stderr 捕获代理（替代进程级 redirect_stdout）。

为什么不能用 contextlib.redirect_stdout：
它替换的是**整个进程**的 sys.stdout。本 App 里脚本跑在临时线程上，而超时的线程
Python 层杀不掉（`PythonToolManager` 的注释写明了这点：daemon 线程 join 超时后只能弃）。
于是会出现这条串味路径：

    第 1 次调用超时 → 它的线程还停在 with redirect_stdout(buf1) 里；
    第 2 次调用进 with redirect_stdout(buf2)、退出时把 sys.stdout **还原成 buf1**；
    buf1 早已 close → 之后所有 print 都写进废掉的流。

模型看到的就是"脚本明明 print 了却没有输出"，而且无从判断原因。
（这条已在本机实测复现：残留线程持有 redirect 时，窗口外的一次 print 被吞进上一轮
那个再也没人读的 buffer；真实控制台拿不到，结果里也没有。）

这里的做法：进程里只装一次代理对象，代理按「当前线程 id」找这次调用的落点。
没登记过的线程落到默认目标（真实流），所以谁也不会覆盖谁的 buffer。

关于子线程：**不能只做线程级路由** —— 旧的全局替换是连用户脚本自己起的线程
（concurrent.futures / threading / tqdm 后台写）一起抓的，纯按线程 id 路由会让
那些 print 从此不进结果，是用户可见的回退。所以 install() 同时给
Thread.start 装一层"血缘继承"：在捕获窗口内启动的线程，自动继承父线程当时的
落点，并在自己结束时摘掉。跨调用残留的线程不在这条血缘上，因此仍然互不干扰。
"""

import contextlib
import sys
import threading
import weakref


class ThreadStreamProxy:
    """按线程路由 writes 的流代理；进程里只装一次，永不还原。"""

    def __init__(self, default_stream):
        self._default = default_stream
        self._sinks = {}
        self._threads = {}
        self._lock = threading.RLock()

    # ---- 登记 / 注销（只影响调用方所在线程） ----

    def register(self, sink):
        ident = threading.get_ident()
        with self._lock:
            self._prune_dead_locked()
            self._sinks[ident] = sink
            self._threads[ident] = weakref.ref(threading.current_thread())

    def unregister(self):
        ident = threading.get_ident()
        with self._lock:
            self._sinks.pop(ident, None)
            self._threads.pop(ident, None)

    def _prune_dead_locked(self):
        """僵尸线程结束后别把它的 buffer 一直挂在表里（否则超时越多漏得越多）。"""
        for ident in [k for k, ref in self._threads.items() if ref() is None]:
            self._sinks.pop(ident, None)
            self._threads.pop(ident, None)

    def _target(self):
        sink = self._sinks.get(threading.get_ident())
        return sink if sink is not None else self._default

    def current_sink(self):
        """当前线程登记的落点；None 表示本线程不在任何捕获窗口内。"""
        return self._sinks.get(threading.get_ident())

    # ---- 流协议 ----

    def write(self, s):
        try:
            return self._target().write(s)
        except Exception:
            # buffer 已被回收/关闭：绝不能把异常抛回正在 print 的脚本
            return 0

    def writelines(self, lines):
        for line in lines:
            self.write(line)

    def flush(self):
        try:
            self._target().flush()
        except Exception:
            pass

    def fileno(self):
        # 与旧实现同口径：当前落点没有 fileno（StringIO）就照常抛 UnsupportedOperation，
        # tqdm/rich 之类据此自行降级，而不是以为拿到了真终端。
        return self._target().fileno()

    def isatty(self):
        return False

    def close(self):
        # 代理本身不许被脚本关掉 —— 那会把别的调用的输出一起废掉。
        pass

    @property
    def encoding(self):
        try:
            return self._target().encoding
        except Exception:
            return 'utf-8'

    def __getattr__(self, item):
        return getattr(self._target(), item)


_out_proxy = None
_err_proxy = None
_install_lock = threading.Lock()
_inheritance_patched = False

_INHERITED_FLAG = '_qoder_sink_inherited'


def _install_thread_inheritance(out_proxy, err_proxy):
    """让"在捕获窗口内启动的线程"继承父线程的落点（等价于旧的全局 redirect 覆盖面）。

    只 patch Thread.start 一处，且给实例打标记防重复包装。子线程自己结束时摘掉登记，
    线程池里被复用的线程不会把后续调用的输出继续算到前一次头上。
    """
    global _inheritance_patched
    if _inheritance_patched:
        return
    original_start = threading.Thread.start

    def patched_start(self, *args, **kwargs):
        try:
            out_sink = out_proxy.current_sink()
            err_sink = err_proxy.current_sink()
        except Exception:
            out_sink = err_sink = None
        if (out_sink is not None or err_sink is not None) and not getattr(self, _INHERITED_FLAG, False):
            try:
                setattr(self, _INHERITED_FLAG, True)
                original_run = self.run

                def run_with_inherited_capture():
                    if out_sink is not None:
                        out_proxy.register(out_sink)
                    if err_sink is not None:
                        err_proxy.register(err_sink)
                    try:
                        original_run()
                    finally:
                        try:
                            err_proxy.unregister()
                        finally:
                            out_proxy.unregister()

                self.run = run_with_inherited_capture
            except Exception:
                pass
        return original_start(self, *args, **kwargs)

    threading.Thread.start = patched_start
    _inheritance_patched = True


def install():
    """装上代理（幂等）。返回 (stdout 代理, stderr 代理)。"""
    global _out_proxy, _err_proxy
    with _install_lock:
        if _out_proxy is None:
            base = sys.stdout if not isinstance(sys.stdout, ThreadStreamProxy) else sys.stdout._default
            _out_proxy = ThreadStreamProxy(base)
            sys.stdout = _out_proxy
        if _err_proxy is None:
            base = sys.stderr if not isinstance(sys.stderr, ThreadStreamProxy) else sys.stderr._default
            _err_proxy = ThreadStreamProxy(base)
            sys.stderr = _err_proxy
        _install_thread_inheritance(_out_proxy, _err_proxy)
    return _out_proxy, _err_proxy


@contextlib.contextmanager
def capture(out_sink, err_sink):
    """把**当前线程**的 stdout/stderr 写到给定 sink；等价于旧 with redirect_*，但只绑线程。"""
    out_proxy, err_proxy = install()
    out_proxy.register(out_sink)
    err_proxy.register(err_sink)
    try:
        yield
    finally:
        # 反注册顺序与注册相反；即便脚本内部异常也必须摘掉，否则同一线程复用时会串
        try:
            err_proxy.unregister()
        finally:
            out_proxy.unregister()
