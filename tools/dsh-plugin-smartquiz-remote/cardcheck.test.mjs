// cardcheck 自测：node tools/dsh-plugin-smartquiz-remote/cardcheck.test.mjs
import { checkCard, describe } from './cardcheck.js';

let pass = 0;
let fail = 0;

function check(name, card, expectOk, expectLevel) {
  const r = checkCard(card);
  const ok = r.ok === expectOk
    && (expectLevel ? r.diagnostics.some((d) => d.level === expectLevel) : true);
  if (ok) {
    pass += 1;
    console.log('ok   ' + name);
  } else {
    fail += 1;
    console.log('FAIL ' + name + ' → ok=' + r.ok + ' ' + describe(r.diagnostics));
  }
}

check('合法 alert_card', { type: 'alert_card', props: { title: 't', content: 'c' } }, true);
check('alert_card 只有 text 也合法', { type: 'alert_card', props: { text: 'c' } }, true);
check('alert_card 缺内容', { type: 'alert_card', props: { title: 't' } }, false, 'error');
check('code_card 用 command 别名', { type: 'code_card', props: { command: 'ls' } }, true);
check('code_card 空', { type: 'code_card', props: {} }, false, 'error');
check('缺 type', { props: {} }, false, 'error');
check('props 不是对象', { type: 'job', props: 'x' }, false, 'error');
check('合法 layout', {
  type: 'layout',
  props: { layout: { type: 'column', children: [{ type: 'text', text: 'hi' }] } },
}, true);
check('layout 缺 layout 字段', { type: 'layout', props: { title: 'x' } }, false, 'error');
check('layout 字符串 JSON 合法', { type: 'layout', props: { layout: '{"type":"text","text":"a"}' } }, true);
check('layout 字符串坏 JSON', { type: 'layout', props: { layout: '{oops' } }, false, 'error');
check('layout 节点缺 type 只警告', { type: 'layout', props: { layout: { type: 'column', children: [{ text: 'x' }] } } }, true, 'warn');

// 深层嵌套只 warn 不拒
let deep = { type: 'text', text: 'x' };
for (let i = 0; i < 12; i += 1) deep = { type: 'column', children: [deep] };
check('嵌套过深只警告', { type: 'layout', props: { layout: deep } }, true, 'warn');

// 体积超限
const big = { type: 'layout', props: { layout: { type: 'text', text: 'x' }, pad: 'y'.repeat(40 * 1024) } };
check('体积超限', big, false, 'error');

// 未知类型不拦（App 有动态兜底）
check('未知类型放行', { type: 'dsh_future_card', props: { title: 'x' } }, true);

// 2026-11-15 新增卡：key_value / compare（含别名归一）
check('合法 key_value（二元数组）', {
  type: 'key_value',
  props: { title: '账户', items: [['余额', '128.50'], ['状态', '正常']] },
}, true);
check('key_value 别名 keyValue 合法', {
  type: 'keyValue',
  props: { items: [{ label: '余额', value: '128.50' }] },
}, true);
check('key_value 缺 items', { type: 'key_value', props: { title: '账户' } }, false, 'error');
check('dsh_keyvalue 别名缺 items 也拦', { type: 'dsh_keyvalue', props: {} }, false, 'error');
check('合法 compare', {
  type: 'compare',
  props: { items: [{ title: 'A', rows: [['价格', '1']] }, { title: 'B', rows: { 价格: '2' } }] },
}, true);
check('compare 缺 items', { type: 'compare', props: { title: 'A' } }, false, 'error');

// data:image base64 载荷不计入结构体积（但总载荷仍有上限）
const dataImage = 'data:image/png;base64,' + 'A'.repeat(40 * 1024);
check('40 KiB data:image 不占结构预算', {
  type: 'layout',
  props: { layout: { type: 'column', children: [{ type: 'image', src: dataImage }] } },
}, true);
const tooMuchImageData = 'data:image/png;base64,' + 'A'.repeat(2 * 1024 * 1024 + 1);
check('data:image 总载荷超限', {
  type: 'layout',
  props: { layout: { type: 'column', children: [{ type: 'image', src: tooMuchImageData }] } },
}, false, 'error');

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
