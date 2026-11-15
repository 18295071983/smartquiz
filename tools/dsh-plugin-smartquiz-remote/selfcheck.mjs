/**
 * 插件自检（2026-11-16）。
 *
 * <p>用途：在**不启动 DSH** 的前提下，验证"DSH 加载插件时会做的事"能不能过：
 * 1) 模块能 import（无语法错误、无顶层 await 阻塞、无缺失依赖）；
 * 2) 导出了 `apply(ctx, config)` 函数；
 * 3) `Config` 是合法 Standard Schema，且能把配置归一化（cordis 的 resolveConfig 同款调用）；
 * 4) 子模块（lib/*）能被单独加载。</p>
 *
 * <p>背景：用户反馈"插件没起"，而 DSH 桌面版没有可读日志 —— 只能靠这种自检提前拦住。
 * 用法：`node tools/dsh-plugin-smartquiz-remote/selfcheck.mjs`（任一断言失败退出码 1）。</p>
 */
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
let pass = 0;
let fail = 0;
function ok(name, cond, extra) {
  if (cond) {
    pass += 1;
    console.log('  ok  ' + name);
  } else {
    fail += 1;
    console.log('  FAIL ' + name + (extra ? ' :: ' + extra : ''));
  }
}

// 1) 主模块
let mod;
try {
  mod = await import(pathToFileURL(join(here, 'index.js')).href);
  ok('index.js 可导入', true);
} catch (e) {
  ok('index.js 可导入', false, e && e.message);
  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(1);
}

ok('导出 apply(ctx, config) 函数', typeof mod.apply === 'function', typeof mod.apply);

// 2) Config 走一遍 cordis 的调用方式：runtime.Config['~standard'].validate(config)
const cfg = mod.Config;
ok('Config 存在', Boolean(cfg));
ok('Config 是 Standard Schema', Boolean(cfg && cfg['~standard'] && typeof cfg['~standard'].validate === 'function'));
if (cfg && cfg['~standard'] && typeof cfg['~standard'].validate === 'function') {
  const r1 = cfg['~standard'].validate({ cliMode: 'auto', lanPort: 8218 });
  ok('空配置归一化成功', Boolean(r1 && r1.value && !r1.issues), JSON.stringify(r1 && r1.issues));
  ok('默认值已填充', Boolean(r1 && r1.value && r1.value.provider === 'deepseek-account'));
  const r2 = cfg['~standard'].validate({ lanPort: '9999', allowExec: 'yes', cliMode: 'weird' });
  ok('类型不符回退默认（数字串→数字）', Boolean(r2 && r2.value && r2.value.lanPort === 9999));
  ok('类型不符回退默认（布尔）', Boolean(r2 && r2.value && r2.value.allowExec === true));
  ok('非法 cliMode 回退 auto', Boolean(r2 && r2.value && r2.value.cliMode === 'auto'));
}

// 3) 子模块
for (const rel of ['lib/util.js', 'lib/slots.js', 'lib/address.js', 'lib/qr.js']) {
  try {
    await import(pathToFileURL(join(here, rel)).href);
    ok(rel + ' 可导入', true);
  } catch (e) {
    ok(rel + ' 可导入', false, e && e.message);
  }
}

// 4) 二维码实际能出图（服务端能力，不是仅声明）
try {
  const qr = await import(pathToFileURL(join(here, 'lib/qr.js')).href);
  const svg = qr.svgFor('dshpair://192.168.1.2:8218?token=selfcheck');
  ok('二维码 SVG 可生成', typeof svg === 'string' && svg.includes('<svg') && svg.length > 500,
    svg ? svg.length + ' bytes' : 'null');
} catch (e) {
  ok('二维码 SVG 可生成', false, e && e.message);
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
