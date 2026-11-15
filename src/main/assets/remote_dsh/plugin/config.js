/**
 * 插件配置 schema（DSH 规范要求，2026-11-16 补）。
 *
 * <p>为什么要有：以前只在 `apply()` 里 `{...DEFAULTS, ...config}` 合并，
 * 用户在 `cordis.patch.yml` 把键名写错（比如 `cliMdoe`）不会有任何提示，
 * 插件照常启动、行为却按默认值走 —— 这类"配置没生效"极难排查。
 * 声明 `Config` 后，插件行在**激活时**校验，写错的键/类型会直接报出来。</p>
 *
 * <p><b>为什么用 try/catch 动态 import</b>：`@deepseek-ai/schemastery` 由 DSH 安装提供，
 * 但插件也可能是"workspace 里 link 进来"的（本仓库就是），此时从插件目录解析不到该包。
 * 解析得到就用真 schema；解析不到就退化成"不声明 Config"（插件照常启动，只是没校验），
 * 而不是让整个插件因为一条 import 失败而加载不起来。</p>
 */
let Config;

try {
  const mod = await import('@deepseek-ai/schemastery');
  const z = mod?.default ?? mod;
  if (z && typeof z.object === 'function') {
    Config = z.object({
      // ---- 身份与模型路由 ----
      token: z.string().default('').description('主令牌；空则用环境变量或插件目录 .token'),
      provider: z.string().default('deepseek-account').description('插件创建 agent 时使用的 provider'),
      model: z.string().default('deepseek-flash').description('插件创建 agent 时使用的 model'),
      preset: z.string().default('').description('可选的 agent preset id'),

      // ---- 回复与执行 ----
      pathPrefix: z.string().default('').description('对外路径前缀（反代到子路径时用）'),
      maxReplyChars: z.number().step(1).min(200).default(4000).description('单条回复的最大字符数'),
      allowExec: z.boolean().default(true).description('是否允许 /exec 直连执行命令（电脑面板开关优先）'),
      execTimeoutMs: z.number().step(1).min(1000).default(120000).description('/exec 超时（毫秒）'),

      // ---- 执行位置（直连 vs CLI）----
      cliMode: z.union([z.const('auto'), z.boolean()]).default('auto')
        .description('auto=宿主有 agent 能力就直连，否则起 CLI；true=强制 CLI；false=强制宿主内'),
      cliProfile: z.string().default('headless').description('CLI 子进程使用的 profile'),
      cliTimeoutMs: z.number().step(1).min(1000).default(300000).description('CLI 任务超时（毫秒）'),
      cliMaxConcurrent: z.number().step(1).min(1).default(1).description('同时运行的 CLI 子进程上限'),
      cliMaxQueued: z.number().step(1).min(0).default(3).description('CLI 排队上限，超限直接回"忙"'),
      cliPermissionMode: z.string().default('').description('CLI 子进程权限模式（空=插件默认）'),
      cliApprovalPolicy: z.string().default('').description('CLI 子进程审批策略（空=插件默认）'),
      dshPath: z.string().default('').description('dsh 可执行文件路径（空=自动解析）'),
      warmReuse: z.boolean().default(false).description('CLI 跑完是否后台 resume 成宿主内句柄'),
      workingDir: z.string().default('').description('CLI 任务 cwd（空=继承宿主 cwd）'),

      // ---- 网络与对外地址 ----
      lanEnabled: z.boolean().default(true).description('是否自建局域网入口'),
      lanPort: z.number().step(1).min(1).max(65535).default(8218).description('局域网入口端口'),
      extraLanPorts: z.string().default('80').description('附加监听端口（逗号分隔）'),
      publicUrl: z.string().default('').description('隧道/公网域名（留空则只出局域网二维码）'),
    });
    console.log('[smartquiz-remote] Config schema 已声明（配置会在激活时校验）');
  } else {
    console.warn('[smartquiz-remote] schemastery 不可用，跳过 Config 校验');
  }
} catch (e) {
  Config = undefined;
  console.warn('[smartquiz-remote] 无法加载 schemastery，跳过 Config 校验：' + (e?.message ?? e));
}

export { Config };
