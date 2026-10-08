# js_execute 增强 · 桥接坞实测与问题报告

> [!IMPORTANT]
> **本文件不是本项目代码/架构的一部分。**
>
> 它是一次性产出物的历史记录 —— 「极光时钟」HTML 经壳 APK 导出时的
> 调试、补丁与验收材料（2026-09-14/15/19）。保留仅供追溯，
> **不随项目维护，也不会被更新**。
>
> 壳能力的**现行权威文档**是 `APK_SOURCE_GUIDE.md`（壳源码指南）与
> `HTML_DESIGN_RULES.md`（HTML 生成规则），当前壳版本 v8.1。

---
> 测试日期：**2026-09-14**
> 测试设备：小米17（标准版），Android 16
> 执行引擎：**系统 WebView（Chromium 143.0.7499.192 / Blink + V8）**
> UA：`Mozilla/5.0 (Linux; Android 16; 25113PN0EC Build/BP2A.250605.031.A3; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/143.0.7499.192 Mobile Safari/537.36`
> 测试人：Agent（脚本化探针，非人工口述）

---

## 一、背景

js_execute 本次"增强"，据实测**新增了两个桥接坞**（schema 已列出，但升级记录未提及）：

- `window.__fs` —— 受限文件读写
- `window.__http` —— 受限网络请求

本文把端到端实测的**原始数据**与**发现的 5 个问题**汇总，供修复。

---

## 二、环境指纹

### 2.1 引擎 / 全局对象

**探针结果：**
```json
{"window":"object","document":"object","require":"undefined","process":"undefined",
 "fetch":"function","XMLHttpRequest":"function","WebAssembly":"object","crypto":"object",
 "localStorage":"blocked:SecurityError","indexedDB":"object","setTimeout":"function",
 "queueMicrotask":"function","Worker":"function","SharedArrayBuffer":"undefined",
 "Atomics":"object","performance":"object","TextEncoder":"function","AbortController":"function"}
```

要点：
- 真·WebView 环境（window/document/navigator 均在），**非 Node**（process/require 为 undefined）；
- `localStorage` 抛 **SecurityError**（沙箱隔离，符合预期）；
- `SharedArrayBuffer` 为 undefined（未开跨源隔离，正常）。

### 2.2 ES 特性实测（可用于矫正文档）

```json
{"Proxy":"function","BigInt":"function","WeakRef":"function","structuredClone":"function",
 "globalThis":"object","arrayAt":"function","replaceAll":"function","arrayFindLast":"function",
 "optChain":true}
```

结论：**可选链 `?.` 实测可用（optChain=true）**，BigInt / WeakRef / structuredClone / `.at()` / `replaceAll` / `findLast` 均可用 → 引擎实为现代 V8（ES2020+ 完全够用）。

### 2.3 顶层 return

```js
return 'TOP_LEVEL_RETURN_OK';
```
→ **失败：`Illegal return statement`**（与既往结论一致，须用 IIFE）。

---

## 三、桥接坞实测

### 3.1 `window.__fs` 文件坞

存在性：`__fs` = object，`read/write/list/delete/exists` 均为 function。

| 调用 | 结果（原始） | 判定 |
|---|---|---|
| `__fs.list()`（**无参**） | `{"ok":true,"path":".../agent_workspace/files/undefined","files":[]}` | ❌ 见问题 ① |
| `__fs.list('')` | `{"ok":true,"path":".../files","files":[...12 项...]}` | ✅ |
| `__fs.list('.')` | `{"ok":true,"path":".../files/.","files":[...12 项...]}` | ✅ |
| `__fs.write('js_probe_tmp.txt','hello-from-bridge 12345')` | `{"ok":true}` | ✅ |
| `__fs.read('js_probe_tmp.txt')` | `{"ok":true,"content":"hello-from-bridge 12345"}` | ✅ 真落盘 |
| `__fs.exists('js_probe_tmp.txt')` | `{"ok":true,"exists":true,"isDir":false}` | ✅ |
| `__fs.delete('js_probe_tmp.txt')` | `{"ok":true}` | ✅ |

`list('')` 返回的真实内容（证明直连工作区 files/）：
```
HTML_DESIGN_RULES.md (20393)   工具创建指南.md (8853)   使用速查表.md (8053)
APK_SOURCE_GUIDE.md (10118)    app.js (2230)           index.html (1293)
js_execute_add_0914.md (2579)  screenshots/ (dir)      time_display.js (5827)
time_display.js.ver1 (5229)    time_display.js.ver2 (2179)  time_display.html (2754)
```

### 3.2 `window.__http` 网络坞

存在性：`__http` = object，`get/post` 均为 function。

```js
const p = window.__http.get('https://api.github.com/zen');
typeof p.then === 'function'   // => false   ← 不是 Promise！
const raw = window.__http.get('https://api.github.com/zen');
typeof raw                     // => "string"  ← 是 JSON 字符串！
// raw === '{"ok":true,"status":200,"body":"Half measures are as bad as nothing at all."}'
```

- 网络本身**通**（status 200，正文正确抓到）；
- 但返回**类型/形态与文档不符**，且**同步阻塞** → 见问题 ② ③。

---

## 四、问题清单（建议修复）

| # | 现象 | 复现 / 证据 | 期望 | 严重度 |
|---|---|---|---|---|
| ① | `__fs.list()` **无参调用把 `undefined` 拼进路径**，返回空列表 | `list()` → path=`.../files/undefined`, files=`[]`；`list('')` → path=`.../files`, 正常 12 项 | 无参时默认列 `files/`（给参数默认值 `''`）；或明确报错提示"需传路径" | 中 |
| ② | `__http.get()` 文档称"返回 `{ok,status,body}`"，**实测返回 JSON 字符串**，须自行 `JSON.parse` | `typeof __http.get(u)` === `"string"`；`JSON.parse(raw)` 才拿到对象 | 直接返回对象；否则更新文档/加示例 | 中 |
| ③ | `__http` **同步阻塞、非 Promise** | `typeof p.then === 'function'` === `false` | 返回 `Promise`，以配合 `async/await` 与 timeout 机制（否则最长 10s 卡死执行线程） | **高** |
| ④ | **schema 与行为/记录自相矛盾**：新版 schema 说"较新特性不保证可用（`?.`/`??`/BigInt/ES2020+ 可能报错）"，但实测 `?.` 可用，且升级记录写明真机跑通 ES2020+BigInt | 见 §2.2 `optChain:true`；`js_execute_add_0914.md` 第三节 | 删除/放宽该保守声明，据实描述为"支持 ES2020+（现代 V8）" | 中 |
| ⑤ | **升级记录未同步**：`js_execute_add_0914.md` 只写"新增 + 纯内存沙箱、禁 file 访问"，**未提及新增的 `__http`/`__fs` 两个坞** | 该记录全文无 `__http`/`__fs` 字样 | 补写增强章节（接口、限制、示例） | 中 |

> 附（非缺陷，可选项）：顶层 `return` 仍被禁（须 IIFE）；并发上限仍为 2。

---

## 五、结论

- **两个新坞都是真货、功能可用**：文件坞直连工作区 `files/`（写→读回一致，真落盘）；网络坞能取回真实 HTTP 响应。
- **但存在 1 个高危 + 4 个中危问题**：`__http` 同步阻塞（③）建议最优先；`list()` 空参（①）、返回类型（②）、文档与实现不一致（④⑤）建议一并修。
- 修复后建议同步更新 schema 描述与 `js_execute_add_0914.md` 记录。

---
*本报告数据均来自 2026-09-14 的脚本化探针实测，可复现。*

---

# 六、复测结果（2026-09-14 晚 · App 更新后）

> 触发：开发已按本报告修复并更新 App。本节对 §四 的 5 个问题逐条复测（脚本化探针，可复现）。

## 6.1 逐条对表

| # | 原问题 | 复测结果 | 状态 |
|---|---|---|---|
| ① | `__fs.list()` 空参拼入 `undefined` | 无参 `list()` 返回 `path=.../files`，不再含 `undefined` | ✅ 已修 |
| ② | `__http.get()` 返回 JSON 字符串（非对象） | 返回 Promise，`await` 后为对象，keys=`[ok,status,body]` | ✅ 已修 |
| ③ | `__http` 同步阻塞、非 Promise | `typeof p.then==='function'` === true | ✅ 已修 |
| ④ | schema 称 ES2020 不保证（与实测矛盾） | 已改为"以实测为准：现代机型支持 ES2020+" | ✅ 已修 |
| ⑤ | 升级记录漏写 `__http`/`__fs` | `js_execute_add_0914.md` 新增 §5，含两坞章节 | ✅ 已修 |

## 6.2 复测实证数据

> 以下为**第四轮实测原始返回**（单批 ≤4 路并发，脚本直接回传，未经人工改写）。

```json
{
  "meta": {
    "date": "2026-09-14",
    "round": "第四轮（复测后 + 并发纠正）",
    "device": "小米17（标准版）25113PN0EC / Android 16",
    "engine": "系统 WebView · Chromium 143.0.7499.192 (Blink + V8)",
    "probeMode": "脚本化探针 · 单批并发 ≤ 4 路 · 脚本内桥接串行"
  },
  "env": {
    "window": "object",
    "document": "object",
    "process": "undefined",
    "require": "undefined",
    "fetch": "function",
    "XMLHttpRequest": "function",
    "crypto": "object",
    "Worker": "function",
    "localStorage": "blocked:SecurityError",
    "SharedArrayBuffer": "undefined",
    "es2020": {
      "optChain": true,
      "BigInt": "function",
      "WeakRef": "function",
      "structuredClone": "function",
      "arrayAt": "function",
      "replaceAll": "function",
      "arrayFindLast": "function"
    }
  },
  "topLevelReturn": {
    "code": "return 'TOP_LEVEL_RETURN_OK';",
    "iifeRequired": false,
    "result": "TOP_LEVEL_RETURN_OK",
    "status": "FIXED"
  },
  "fs": {
    "list_no_arg": {
      "ok": true,
      "path": "/storage/emulated/0/Download/OilQuiz/agent_workspace/files",
      "files": 15,
      "undefinedInPath": false,
      "status": "FIXED"
    },
    "write": { "file": "probe_fs_0914_a.txt", "content": "BRIDGE-PROBE", "ok": true },
    "read": { "ok": true, "len": 12, "contentMatchesWrite": true },
    "exists_before_delete": { "ok": true, "exists": true, "isDir": false },
    "delete": { "ok": true },
    "exists_after_delete": { "ok": true, "exists": false },
    "limit": "files/ 目录 · ≤512KB"
  },
  "http": {
    "get_200": {
      "url": "https://api.github.com/zen",
      "ok": true,
      "status": 200,
      "body": "Avoid administrative distraction.",
      "typeofReturn": "object",
      "isPromiseResolved": true,
      "status": "FIXED"
    },
    "get_404": {
      "url": "https://api.github.com/no-such-path-xyz-0914",
      "ok": true,
      "status": 404,
      "bodyHead": "{\"message\": \"Not Found\", \"documentation_url\": ...}"
    },
    "get_bad_host": {
      "url": "http://this-host-does-not-exist-xyz0914.invalid/",
      "ok": false,
      "error": "Unable to resolve host \"this-host-does-not-exist-xyz0914.invalid\": No address associated with hostname"
    },
    "get_bad_scheme": {
      "url": "file:///etc/hosts",
      "ok": false,
      "error": "仅支持 http/https URL"
    },
    "limit": "仅 http/https · 6s 超时 · ≤512KB"
  },
  "concurrency": {
    "maxPerBatch": 4,
    "overLimitBehavior": "超限调用被静默丢弃，工具仍返回『执行成功』且无返回值；并会污染桥接状态，使后续脚本在首个 await 处悬停",
    "evidence": [
      { "batch": "8 路（超限）", "ret": "4 路返回、4 路无值（丢值比例 ≈ 超限部分）" },
      { "batch": "5 路（超限）", "ret": "5/5 无返回值" },
      { "batch": "3 路", "ret": "3/3 返回完整值 ✅" },
      { "batch": "4 路 × 单个脚本 6 次串行 await", "ret": "4/4 返回完整值 ✅" }
    ]
  },
  "issues": {
    "① list() 空参": "FIXED",
    "② __http 返回 JSON 字符串": "FIXED",
    "③ __http 同步阻塞": "FIXED",
    "④ schema 与实测矛盾": "FIXED",
    "⑤ 升级记录漏写两坞": "FIXED"
  }
}
```

## 6.3 第四轮补充发现（并发）

### 6.3.1 关键更正：并发上限是 **4 路**，不是 8 路

本轮一度按「上限已解除、单批 8 路全通」执行，出现大面积「执行成功但无返回值」。经用户纠正 + 对照实验确认：**单批最多 4 路并发**。§四 附注中「并发上限仍为 2」的旧结论同样作废。

| 批次规模 | 实测结果 |
|---|---|
| 8 路 | 部分返回、部分静默丢值 |
| 5 路 | 5/5 无返回值 ❌ |
| 3 路 | 3/3 完整返回值 ✅ |
| 4 路（每脚本 6 次串行 await） | 4/4 完整返回值 ✅ |

### 6.3.2 现象还原：「执行成功但无返回值」的真凶

- **不是** try/catch 的锅（带 try/catch 的写法单跑同样正常）；
- **不是** 返回对象过大或字段含中文的锅；
- 实为**并发超限**：超出的调用被丢弃，工具层却统一回「执行成功」，于是表现为「代码跑了、值没回来」；
- 附带副作用：多次超限后桥接状态被污染，**单个轻量脚本也会在首个 `await` 之后中断**（`console.log` 只打出 `S1-start` 一行即停），且该脚本的返回值同样丢失——此时必须冷却后再以 ≤4 路上重试。

### 6.3.3 规避清单（建议写入工具文档）

1. 单批 `js_execute` **不超过 4 路**；
2. 单个脚本内部多次桥接调用**串行 `await`**，不要 `Promise.all` 并发打桥；
3. 排查丢值时用 `console.log` 逐点打桩，一眼看出卡在第几个 `await`；
4. 出现「无返回值」先怀疑并发超限，冷却 + 降到 ≤4 路复跑，不要急着改代码。

---
*§6.2 / §6.3 数据均来自 2026-09-14 第四轮脚本化探针实测，可复现。*

## 6.4 文档缺口：描述未声明并发上限（第五项新增）

**现象**：`js_execute` 的工具描述里**通篇没有「并发 / 上限 / 批量」任何字样**，但实测单批并发上限为 **4 路**；且超限调用被静默丢弃、工具仍返回「执行成功」。即：**上限只存在于行为里，既未文档化，也不在超限时报错。**

**对照（同 App 内两套执行器行为不一致）**

| 执行器 | 描述是否写明并发上限 | 实测上限 | 超限时表现 |
|---|---|---|---|
| `js_execute` | ❌ 未声明 | 4 路 | 静默丢弃，仍回「执行成功」 |
| `create_dynamic_tool` 的 JS 执行器 | ✅ 已写明 | 2 路 | 明确报错「JS 执行并发已满」 |

**影响**：调用方（含 AI）无法从描述得知上限，一旦超限即表现为「脚本跑完了但值没回来」，极易被误判为代码缺陷；叠加 §6.3.2 的桥接状态污染后，连**单个轻量脚本**也会无值返回，排障成本陡增。

**建议（按优先级）**
1. **描述补写**：单批 ≤4 路并发；单个脚本内部桥接调用请串行 `await`，勿 `Promise.all` 打桥；
2. **行为层（更重要）**：超限时明确报错（如「并发超过上限(4)，请分批调用」）或排队等待——**不要静默丢弃、不要回「执行成功」**；
3. **健壮性**：桥接状态可自愈（批次结束复位 / 超限后重置），避免污染后续调用。

**验收方式**：修改后回归一次——5 路应返回明确错误；4 路 × 脚本内 6 次串行 `await` 应 4/4 正常回值。
