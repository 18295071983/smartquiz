# APK 导出器安装报错 -124：根因定位与修复方案

> 测试机型：小米17（Android 16 / HyperOS 3）
> 测试包：daily_quiz_demo.apk（2.36 MB，targetSdk 34）
> 结论：**不是手机不兼容，是 resources.arsc 打包方式违规，100% 必现**

---

## 一、错误现象

用户真机安装导出包，提示「**不兼容**」，附带代码 **-124**。

这个中文提示具有误导性 —— 它让人以为是设备/CPU/系统版本不匹配，实际完全不是。

错误码 -124 的真实含义（Android PackageManager 原文）：

```
-124: Failed parse during installPackageLI:
Targeting R+ (version 30 and above) requires the resources.arsc of installed APKs
to be stored uncompressed and aligned on a 4-byte boundary
```

翻译：**因为你的 targetSdk ≥ 30，Android 11+ 强制要求 `resources.arsc` 必须以「未压缩」方式存储，且数据起始偏移必须是 4 的整数倍。**

---

## 二、根因

导出器缺了 **zipalign（对齐）** 这一步。

只做对了一半：

| 要求 | 导出器现状 | 判定 |
|---|---|---|
| `resources.arsc` 不压缩（STORED） | 已是 STORED | ✅ 满足 |
| `resources.arsc` 数据偏移 4 字节对齐 | 偏移 2278531 → %4 = **3** | ❌ **不满足** |

**« 偏差只有 3 个字节，却让整个包装不上 »**

对比 manifest 里的实际配置（已拆包核实）：

| 项目 | 值 | 是否合规 |
|---|---|---|
| minSdkVersion | 26 | ✅ 不影响 |
| targetSdkVersion | 34 | ⚠️ **≥30，正是它触发了强校验** |
| compileSdkVersion | 34 | ✅ |
| 签名方案 | v1 + v2 + v3 齐全 | ✅ 没问题 |
| 原生库 so | 0 个（纯 Java） | ✅ 无 ABI 问题 |

所以：SDK 版本、ABI、签名全部正常，**唯一的问题就是 3 个字节的对齐偏差**。

---

## 三、影响面

因为壳模板的 targetSdk 固定为 34（≥30），**所有导出的 APK 都命中这条校验**：

- 影响设备：**Android 11 及以上全部机型**（覆盖当前绝大多数在用的手机）
- 复现概率：**100%**，不是偶发、不是兼容性差异
- 表现差异：部分 ROM 提示「解析包错误 / 安装包损坏」，小米/澎湃 OS 提示「不兼容」+ 代码 -124

> 这也解释了一个此前被忽略的事实：之前做的 14 个包 + 本次的 demo，全程只验证了"包内结构正确"，**没有一个真正装机跑过**，所以这个致命问题一直没暴露。

---

## 四、修复方案

### 核心原则：先对齐，后签名

```
打包 ZIP（arsc 已 4 字节对齐）
        ↓
apksigner 签名（写入 v2/v3 签名块）
        ↓
成品 APK
```

**绝不能反过来** —— 签名后再改动文件会让 v2/v3 签名失效。

### 方案 A：打包阶段直接对齐（推荐，纯代码，无需外部工具）

写 ZIP 条目时，给 `resources.arsc` 的 local header 塞一个 extra field 做 padding：

```python
import struct, zipfile

ALIGN_ID = 0xD935   # Android zipalign 官方使用的 extra field ID

with zipfile.ZipFile(dst, 'w') as zout:
    for item in zin.infolist():
        name = item.filename
        data = zin.read(name)

        zi = zipfile.ZipInfo(name, date_time=item.date_time)
        zi.external_attr = item.external_attr
        zi.compress_type = (zipfile.ZIP_STORED if name == "resources.arsc"
                            else zipfile.ZIP_DEFLATED)

        if name == "resources.arsc":
            # ① 必须是 STORED（不压缩）—— 上面已设
            # ② 数据偏移对齐到 4 字节边界
            cur = zout.fp.tell()
            A = cur + 30 + len(name.encode('utf-8')) + 4   # local header 末尾
            pad = (4 - A % 4) % 4
            zi.extra = struct.pack('<HH', ALIGN_ID, pad) + b'\x00' * pad

        zout.writestr(zi, data, compress_type=zi.compress_type)
```

### 方案 B：调用标准 zipalign（若有二进制）

```bash
zipalign -f -p 4 input.apk output.apk
```

`-p` 用于 .so 页对齐（当前无 so，但保留无害），`4` 是 4 字节边界。
执行后再用 `apksigner sign` 重新签名。

### 顺便建议

- 修复后**加一条自检**：打包完在签名前断言 `arsc_offset % 4 == 0`，不满足直接报错，避免带病出厂；
- 可考虑把 `targetSdkVersion` 提升到 35，以适配后续系统要求（非必需，但更稳）。

---

## 五、修复验证（已实测）

用方案 A 对同一个包重新对齐，结果：

| 检查项 | 修复前 | 修复后 |
|---|---|---|
| arsc 压缩方式 | STORED | STORED |
| arsc 数据偏移 | 2278531 | 2283904 |
| `偏移 % 4` | **3 ❌** | **0 ✅** |
| 文件体积 | 2360935 B | 2359385 B（-1.5 KB） |
| v1 签名摘要 | — | **完全一致，未破坏** ✅ |

验证方法：MANIFEST.MF 中记录的 `resources.arsc` SHA-256 摘要为
`TrahBW2L4kHyBDWvnoGAO43+MKRJdaZR1BnF2jykSHI=`，
对齐后重新计算仍为该值 —— 说明重排数据不会改变条目内容，v1 签名天然保持有效。

**结论：方案 A 可行，能把 -124 的触发条件彻底消除。**

---

## 六、修复后回归清单

- [ ] `resources.arsc` 为 STORED 且偏移 %4 == 0
- [ ] v2 / v3 签名块存在（Android 11+ 对 targetSdk≥30 强制要求）
- [ ] 真机安装通过（Android 11+ 各版本各测一台）
- [ ] 图标、应用名、内置页面功能正常
- [ ] 附：包名唯一化问题（当前所有包固定为 `com.cjhtmldemo.scedzxdz`，会导致互相覆盖）另行处理
