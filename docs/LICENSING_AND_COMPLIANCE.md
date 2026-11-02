# 依赖许可与内容合规规则

> 适用对象：smartquiz（`com.oilquiz.app`）随 APK 分发的全部二进制、库、模型权重与运行期下载物，
> 以及 `network_search` / `webpage_reader` 这类会抓取第三方网页的能力。
> 配套文件：[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)（逐项声明）、
> [`../src/main/assets/apk_shell/guides/THIRD_PARTY_NOTICES.md`](../src/main/assets/apk_shell/guides/THIRD_PARTY_NOTICES.md)（随包副本，两处必须同步）。

---

## 一、两条底线

0. **只用境内模型与服务**（产品决策，不是许可证问题）：模型、搜索、抓取服务一律选国内的
   （DeepSeek、秘塔等）；境外服务慢且不可靠，也不引入。**开源库**不受此限——jsoup / OkHttp /
   trafilatura 这类是本地代码，不联网、不出境，按许可证规则走即可。
1. **进程内只允许宽松许可**（MIT / BSD / Apache-2.0 / MPL-2.0）：可以闭源分发，只需要保留版权与许可声明。
2. **GPL / AGPL 只允许"独立程序"形态**：独立可执行文件或独立 APK、未修改、能提供源码获取方式。
   和 App 链接进同一进程（Java 静态/动态链接、Chaquopy `import`、JNI 调用）都算"结合成一个作品"，
   会触发源码开放义务 —— 这条线不要试。

## 二、分档与准入

| 档 | 许可 | 能否进 APK 进程 | 要求 |
|---|---|---|---|
| A | MIT、BSD-2/3、Apache-2.0、ISC、Zlib、MPL-2.0、Public Domain | ✅ 可以 | 保留版权声明 + 许可全文；Apache-2.0 另需保留 NOTICE、注明修改过的文件；MPL 只需公开被修改的文件 |
| B | LGPL-2.1 / LGPL-3.0 | ⚠️ 有条件 | 动态链接、允许用户替换该库、提供源码获取方式（现例：ffmpeg-kit） |
| C | GPL-2.0 / GPL-3.0 | ❌ 进程内不行 | 只能作为独立可执行文件/独立 APK 随包分发，且未修改、提供源码（现例：BusyBox、Termux） |
| D | AGPL-3.0 | ❌ 不行（含服务端） | 对外提供网络服务即触发开源义务；除非整个服务端开源，否则只在内部离线使用 |
| E | 专有 / 厂商条款 | ⚠️ 逐条审 | 例：Qualcomm QNN 预编译库、模型权重（非代码许可），必须逐项确认再分发条款 |

**判定口诀**：先看是不是"进程内"，再看是不是"对外提供网络服务"，最后才看许可证名字。

## 三、新增依赖的登记流程（每次都要走）

1. **查**：`./gradlew dependencies --configuration debugRuntimeClasspath`（JVM）、
   `pip show <pkg>` / PyPI `requires_dist`（Python）、`unzip -l` 看原生库。
2. **判**：按上表定档。C/D 档直接放弃，或改成独立进程/独立 APK 方案。
3. **记**：在 `THIRD_PARTY_NOTICES.md` 增加一条（组件、坐标/版本、用途、许可、版权、许可全文链接、源码获取方式）。
   **`docs/` 与 `src/main/assets/apk_shell/guides/` 两份必须同步**（可用 MD5 比对）。
4. **留痕**：在下方"变更记录"写一行（日期、组件、档位、结论）。
5. **审**：发布前按第五节过一遍。

> 只加代码不加模型时，注意**模型权重是另一套许可**（HuggingFace 上各自标注），不能按代码许可推断。

## 四、抓取与内容合规（比许可证更容易出事）

| 风险 | 规则 | 落地位置 |
|---|---|---|
| 著作权 | 对外只给 **摘要 + 短引用 + 原文链接 + 抓取时间**；不做整篇转载、不做"去水印/去广告后重排全文" | `webpage_reader` 返回 `summary`/`text` 时，界面侧只渲染摘要 |
| robots.txt / ToS | 抓取前检查 robots；明令禁止批量抓取的站点进 deny 列表；不绕登录墙、付费墙、验证码 | ✅ `WebFetchPolicy.checkRobots`（所有抓取入口调用） |
| 频率 | 单域名 ≤ 1 req/s、并发 ≤ 2；遇 429/403 退避并停 | ✅ `WebFetchPolicy.acquire`（同域名信号量 2 + 1s 间隔，排队超 8s 报错） |
| 个人信息 | **本期不做**（用户口径）：`phones`/`emails` 只作为当轮上下文给模型，不落长期库；如将来要入库，必须在此补脱敏规则（`138****1234`） | — |
| 存储策略 | 默认只存 `摘要 + url + 抓取时间 + quality/verdict`；`verdict != pass` 的页面不入库 | `extract_info` 的 `quality` / `verdict` 字段 |
| 上游 API | 遵守秘塔/搜索服务的条款：缓存有时效（现有 5 分钟 TTL）、不转售原始结果、不承诺实时性 | `OnlineToolManager` 缓存 |
| 数据出境 | **不适用**：产品决策是只接境内模型与服务（见第一节第 0 条）。开源库（jsoup/OkHttp/trafilatura）是本地代码，不产生出境 | — |

**判定"能不能用"用已有字段**：`page_type=article` 且 `verdict=pass` 才可入库/引用；
`degraded` 只做临时上下文；`not_article` / `rejected` / `fail` 一律丢弃并记录原因。

## 五、发布前检查（release gate）

- [ ] `THIRD_PARTY_NOTICES.md` 两份一致（MD5 相同）
- [ ] 依赖树里没有 C/D 档（`./gradlew dependencies` + `pip list` 各扫一遍）
- [ ] 新增的模型权重已单独确认许可
- [ ] 抓取相关改动有 robots/限速/脱敏的处理
- [ ] 上游 API 条款没有变化（缓存、转售、留存）

## 六、已评估候选的结论

| 候选 | 许可 | 结论 | 说明 |
|---|---|---|---|
| **trafilatura** | Apache-2.0 | ✅ 可用（进程内） | 正文提取 F1 0.926（官方 990 页基准），端侧可作为 Jsoup 的补充引擎 |
| **MarkItDown** | MIT | ✅ 可用 | 转 Markdown（PDF/Office/HTML）；需逐项确认其可选依赖（pdfminer.six、mammoth、python-pptx 等） |
| **Docling** | MIT（代码） | ⚠️ 看模型 | 代码 MIT 可进；**版面/表格模型权重另许许可**，不打包权重就不受影响 |
| **readability-lxml / Readability4J** | Apache-2.0 | ✅ 可用 | Readability4J 已停更（2021 后），不建议新依赖 |
| **jusText / magic-html / resiliparse** | BSD / Apache-2.0 | ✅ 可用 | resiliparse 快但召回低（F1 0.811），适合批量归档 |
| **GNE** | **GPL-3.0** | ❌ 进程内不行 | 中文新闻提取效果好，但 GPL 传染；要做得放到服务端并开源该服务 |
| **Firecrawl / Maxun** | **AGPL-3.0** | ❌ 不作服务端 | 对外提供网络服务即触发开源；自托管内部离线用也需谨慎 |
| **SearXNG** | **AGPL-3.0** | ❌ 不作服务端 | 同上；若只在本机离线调试、不对外提供服务，风险较低 |
| **Crawl4AI** | Apache-2.0 | ✅ 可用（服务端） | 需要 Playwright/JS 渲染时用它，端侧不现实 |

## 七、变更记录

| 日期 | 组件 / 事项 | 档位 | 结论 |
|---|---|---|---|
| 2026-11-02 | `HtmlContentExtractor`（自研，Jsoup + Readability） | A（jsoup MIT） | 采用，作为 trafilatura 不可用时的 fallback |
| 2026-11-02 | trafilatura 2.0.0 试验引入 → **放弃** | A（Apache-2.0 / BSD-2） | 试装发现：2.0.0 的 htmldate/justext 依赖 `lxml>=6.1.3` 与 `lxml.html.clean`，而 chaquopy 只有 lxml 5.3.0；退回 1.8 需再拉 babel（30MB）/dateparser/tld 等约 40MB，收益不抵成本。结论：**端侧维持 JVM 抽取器**，硬页面交给 read_url（服务端渲染） |
| 2026-11-02 | 评估 GNE | C（GPL-3.0） | 不引入进程内 |
| 2026-11-02 | 评估 Firecrawl / SearXNG | D（AGPL-3.0） | 不作对外服务；如需自建只在内部离线 |
