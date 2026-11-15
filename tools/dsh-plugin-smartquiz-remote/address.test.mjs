/**
 * 地址分类/候选排序单测（2026-11-16）。
 *
 * 背景：用户反馈"我用的是局域网，面板却显示公网隧道"。根因是旧实现把任何
 * publicUrl 都叫"公网/隧道"，而 Tailscale 的 100.64/10 既不是公网也不是家庭局域网。
 */
import { isLanIp, isMeshIp, classifyAddress, buildCandidates } from './lib/address.js';

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

// ---- 分类 ----
ok('192.168.1.2 是局域网', isLanIp('192.168.1.2'));
ok('10.0.0.5 是局域网', isLanIp('10.0.0.5'));
ok('172.20.1.1 是局域网', isLanIp('172.20.1.1'));
ok('172.32.1.1 不是局域网', !isLanIp('172.32.1.1'));
ok('100.113.188.52 是组网地址', isMeshIp('100.113.188.52'));
ok('8.8.8.8 不是组网地址', !isMeshIp('8.8.8.8'));

ok('classify 192.168 → lan', classifyAddress('http://192.168.1.2:8218').kind === 'lan');
ok('classify 100.113 → mesh', classifyAddress('http://100.113.188.52:8218').kind === 'mesh');
ok('classify 域名 → public', classifyAddress('https://abc.vicp.fun').kind === 'public');
ok('classify 空 → public', classifyAddress('').kind === 'public');

// ---- 候选排序：局域网优先、组网其次、公网最后 ----
const items = buildCandidates(
  ['192.168.1.2', '10.0.0.9'],
  8218,
  'tok',
  'http://100.113.188.52:8218',
);
ok('候选数量 = 3', items.length === 3, JSON.stringify(items.map((i) => i.kind)));
ok('第一条是局域网', items[0].kind === 'lan', items[0] && items[0].base_url);
ok('第一条标记 recommended', items[0].recommended === true);
const mesh = items.find((i) => i.kind === 'mesh');
ok('组网候选被识别为 mesh', Boolean(mesh), JSON.stringify(items.map((i) => i.kind)));
ok('组网排在所有局域网之后', items.indexOf(mesh) === items.length - 1);
ok('组网 label 正确', mesh && mesh.label === '组网（VPN）', mesh && mesh.label);
ok('只有一条 recommended', items.filter((i) => i.recommended).length === 1);
ok('qr_text 带 token', items[0].qr_text.includes('tok'));
ok('https 公网用 scheme 形式', buildCandidates([], 8218, 't', 'https://x.vicp.fun')[0].qr_text.startsWith('dshpair://x.vicp.fun?scheme=https'));

// ---- 非法 publicUrl 被忽略，不抛异常 ----
ok('非法 publicUrl 不产生候选', buildCandidates(['192.168.1.2'], 8218, 't', 'not a url').length === 1);

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
