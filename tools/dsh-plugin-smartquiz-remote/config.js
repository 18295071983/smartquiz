/**
 * 插件配置 schema（2026-11-16，第二版）。
 *
 * <p><b>为什么不用 schemastery</b>：上一版 `await import('@deepseek-ai/schemastery')` 使用了
 * 顶层 await，且该包在"workspace link 安装"的场景下解析不到 —— DSH 加载插件时直接起不来
 * （用户反馈"插件没起"）。配置校验不应该成为插件启动的前置依赖。</p>
 *
 * <p>这里改用 <b>Standard Schema</b> 最小实现：cordis 读 `Config["~standard"].validate(config)`，
 * 我们返回合并默认值后的对象即可。零依赖、无顶层 await、不会因为缺包而拖垮插件。</p>
 *
 * <p>效果：插件行写错的类型/键会被归一化（未知键保留、类型不对回退默认值），
 * 并且 `cfg` 始终拿到完整默认值。</p>
 */

const DEFAULTS = Object.freeze({
  token: '',
  provider: 'deepseek-account',
  model: 'deepseek-flash',
  preset: '',
  pathPrefix: '',
  maxReplyChars: 4000,
  allowExec: true,
  execTimeoutMs: 120000,
  cliMode: 'auto',
  cliProfile: 'headless',
  cliTimeoutMs: 300000,
  cliMaxConcurrent: 1,
  cliMaxQueued: 3,
  cliPermissionMode: '',
  cliApprovalPolicy: '',
  dshPath: '',
  warmReuse: false,
  workingDir: '',
  lanEnabled: true,
  lanPort: 8218,
  extraLanPorts: '80',
  publicUrl: '',
});

const NUMBER_KEYS = new Set([
  'maxReplyChars', 'execTimeoutMs', 'cliTimeoutMs', 'cliMaxConcurrent', 'cliMaxQueued', 'lanPort',
]);
const BOOLEAN_KEYS = new Set(['allowExec', 'warmReuse', 'lanEnabled']);
const STRING_KEYS = new Set([
  'token', 'provider', 'model', 'preset', 'pathPrefix', 'cliProfile', 'cliPermissionMode',
  'cliApprovalPolicy', 'dshPath', 'workingDir', 'extraLanPorts', 'publicUrl',
]);

/** 归一化：未知键保留、类型不符回退默认值、cliMode 三态校验。 */
export function normalizeConfig(input) {
  const raw = (input && typeof input === 'object') ? input : {};
  const out = Object.assign({}, DEFAULTS, raw);
  for (const key of NUMBER_KEYS) {
    const n = Number(out[key]);
    out[key] = Number.isFinite(n) ? n : DEFAULTS[key];
  }
  for (const key of BOOLEAN_KEYS) {
    if (typeof out[key] !== 'boolean') out[key] = DEFAULTS[key];
  }
  for (const key of STRING_KEYS) {
    if (typeof out[key] !== 'string') out[key] = DEFAULTS[key];
  }
  if (out.cliMode !== true && out.cliMode !== false && out.cliMode !== 'auto') {
    out.cliMode = 'auto';
  }
  return out;
}

/**
 * Standard Schema 形状（cordis 只依赖 `~standard.validate`）。
 * 不引入任何第三方依赖：插件启动绝不能因为"配置校验库缺失"而失败。
 */
export const Config = {
  '~standard': {
    version: 1,
    vendor: 'smartquiz-remote',
    validate(value) {
      try {
        return { value: normalizeConfig(value) };
      } catch (e) {
        return {
          issues: [{ message: e && e.message ? e.message : String(e), path: [] }],
        };
      }
    },
  },
  /** 便于人工/脚本查看默认值。 */
  defaults: DEFAULTS,
};
