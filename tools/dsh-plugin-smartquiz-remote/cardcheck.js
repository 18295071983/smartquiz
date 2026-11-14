/**
 * 动态卡校验器（2026-11-14）—— 思路抄自 dsh-Go 的 `sdk/plugin-kit`（view-check / limits / effects）：
 *
 * 插件推卡之前先在**服务端**把结构校一遍，出带路径的诊断，坏卡不进手机。
 * App 侧还有一层降级（单节点坏 → 灰色占位），两层互不替代：
 * 这里是"别让明显错的卡发出去"，那边是"万一错了也别毁掉整条消息"。
 *
 * 约定：
 * - error 级 = 拒收（/v2/card 返回 422），除非请求带 force:true；
 * - warn 级 = 照发，但把诊断回给调用方（例如不认识的节点类型，App 会画灰占位）。
 *
 * 预算与 dsh-Go 对齐：视图 32 KiB、节点 120、深度 8；我们的通道再放宽一档（节点 400）。
 */

const MAX_CHARS = 32 * 1024;
/** data:image base64 总载荷上限（4 张 × 512 KiB）；结构体积与图片载荷分开算。 */
const MAX_DATA_IMAGE_CHARS = 4 * 512 * 1024;
const MAX_NODES = 400;
const MAX_DEPTH = 8;

/** 组件类型 → 必填字段（缺一个就 error）。只列我们 App 真有的卡。 */
const REQUIRED = {
  alert_card: [['content', 'text', 'message']], // 标题可选，正文必须有（三者任一）
  code_card: [['code', 'command']],
  job: [['job_id', 'status']],
  tool_call: [['name']],
  todo_card: [['items']],
  file_list: [['items', 'files']],
  steps: [['items']],
  list_card: [['items']],
  table_card: [['rows']],
  chart: [['kind']],
  metric_card: [['label', 'value']],
  progress_card: [['value']],
  image_grid: [['items', 'images']],
  markdown_card: [['content', 'text']],
  markdown_doc: [['content', 'text']],
  layout: [['layout', 'render']],
  subagent_card: [['label', 'name', 'title']],
  quiz_card: [['question', 'title']],
  json_viewer: [['data', 'json']],
  diff: [['before', 'diff', 'patch']],
  key_value: [['items']],
  compare: [['items']],
};

/** 别名 → canonical（用于必填规则；App 侧 ComponentTypes.resolve 同口径）。 */
const TYPE_ALIASES = {
  keyvalue: 'key_value',
  dsh_keyvalue: 'key_value',
  compare_card: 'compare',
};

const hasAny = (obj, keys) => keys.some((k) => obj[k] !== undefined && obj[k] !== null && obj[k] !== '');

/**
 * 校验一张推送卡。
 * @param {{type?: string, props?: object}} card
 * @returns {{ok: boolean, diagnostics: Array<{path: string, level: 'error'|'warn', message: string}>}}
 */
export function checkCard(card) {
  const diagnostics = [];
  const add = (level, path, message) => diagnostics.push({ path, level, message });

  const type = typeof card?.type === 'string' ? card.type.trim() : '';
  if (!type) add('error', '/type', '缺 type（组件类型名）');

  const props = card?.props;
  if (props !== undefined && (props === null || typeof props !== 'object' || Array.isArray(props))) {
    add('error', '/props', 'props 必须是对象');
  }
  const safeProps = (props && typeof props === 'object' && !Array.isArray(props)) ? props : {};

  let json = '';
  try {
    json = JSON.stringify(safeProps);
  } catch {
    add('error', '/props', 'props 不能序列化（不要放循环引用/函数）');
  }
  // data:image base64 不计入 32 KiB 结构预算（否则一张 512 KiB 的图永远推不出去），
  // 但总载荷单独设上限，防止一张卡塞几百张图把手机内存打爆。
  const dataImageChars = countDataImagePayload(json);
  if (json.length - dataImageChars > MAX_CHARS) {
    add('error', '/props', `结构体积 ${json.length - dataImageChars} 超过上限 ${MAX_CHARS}（data:image 载荷不计入），请拆分或精简`);
  }
  if (dataImageChars > MAX_DATA_IMAGE_CHARS) {
    add('error', '/props', `data:image base64 总载荷 ${dataImageChars} 超过上限 ${MAX_DATA_IMAGE_CHARS}，请拆分到多张卡`);
  }

  // 必填字段（keyValue / dsh_keyvalue / compare_card 先归一到 canonical）
  const rule = REQUIRED[TYPE_ALIASES[type.toLowerCase()] || type];
  if (rule) {
    for (const group of rule) {
      if (!hasAny(safeProps, group)) {
        add('error', '/props', `缺少必填字段：${group.join(' 或 ')}`);
      }
    }
  }

  // layout 树：深度 / 节点数 / 未知节点（warn）
  const layout = safeProps.layout ?? safeProps.render;
  if (type === 'layout' && layout !== undefined) {
    walk(layout, '/props/layout', 0, { nodes: 0 }, add);
  }

  const ok = !diagnostics.some((d) => d.level === 'error');
  return { ok, diagnostics };
}

function walk(node, path, depth, counter, add) {
  if (node == null) return;
  if (typeof node === 'string') {
    // layout 允许写成 JSON 字符串：解析后再走一遍
    try {
      walk(JSON.parse(node), path, depth, counter, add);
    } catch {
      add('error', path, 'layout 字符串不是合法 JSON');
    }
    return;
  }
  if (Array.isArray(node)) {
    node.forEach((child, i) => walk(child, `${path}/${i}`, depth, counter, add));
    return;
  }
  if (typeof node !== 'object') {
    add('error', path, '节点必须是对象、数组或 JSON 字符串');
    return;
  }
  counter.nodes += 1;
  if (counter.nodes > MAX_NODES) {
    if (counter.nodes === MAX_NODES + 1) {
      add('warn', path, `节点数超过 ${MAX_NODES}，App 会截断渲染`);
    }
    return;
  }
  if (depth > MAX_DEPTH) {
    add('warn', path, `嵌套超过 ${MAX_DEPTH} 层，App 会显示"层级过深"`);
    return;
  }
  const t = node.type;
  if (t === undefined && node.root === undefined) {
    add('warn', path, '节点缺 type（App 按 column 渲染）');
  } else if (t !== undefined && (typeof t !== 'string' || !t.trim())) {
    add('error', `${path}/type`, 'type 必须是非空字符串');
  }
  // root 也可以是 {root: {...}} 结构
  if (node.root !== undefined && t === undefined) {
    walk(node.root, `${path}/root`, depth, counter, add);
  }
  for (const key of ['children', 'items', 'tabs', 'defines']) {
    if (node[key] !== undefined) walk(node[key], `${path}/${key}`, depth + 1, counter, add);
  }
}

/** 统计 props JSON 里所有 data:image base64 的 payload 字符数（含所有嵌套层级）。 */
function countDataImagePayload(json) {
  if (!json) return 0;
  const re = /data:image\/[^;,]*;base64,([A-Za-z0-9+/=]*)/g;
  let total = 0;
  let match;
  while ((match = re.exec(json)) !== null) {
    total += match[1].length;
  }
  return total;
}

/** 把诊断拼成一行给人看的文字（日志/响应都用它） */
export function describe(diagnostics) {
  if (!diagnostics || diagnostics.length === 0) return '';
  return diagnostics.map((d) => `${d.level === 'error' ? '✗' : '!'} ${d.path}: ${d.message}`).join('; ');
}
