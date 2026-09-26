// 配对页渲染回归测试：把桥接 /pair 页面里的 <script> 抠出来，在 node 里用假 DOM 跑一遍，
// 断言每个候选都真的生成了二维码图片（<img>）。
// 背景：2026-09-27 实测踩到——Python 源里写了 '\n'（非 raw 三引号字符串被 Python 解释成真换行），
// 服务出去的 JS 字符串里出现裸换行 → 整段脚本 SyntaxError → 页面上一个二维码都不显示。
// 用法：node tools/tests/pair_page_render_test.js <page.html 或 URL>
'use strict';
const fs = require('fs');
const http = require('http');

function fetchBody(target) {
  return new Promise((resolve, reject) => {
    if (!/^https?:\/\//.test(target)) return resolve(fs.readFileSync(target, 'utf8'));
    http.get(target, (res) => {
      let b = '';
      res.on('data', (d) => (b += d));
      res.on('end', () => resolve(b));
    }).on('error', reject);
  });
}

(async () => {
  const target = process.argv[2] || 'http://127.0.0.1:8218/pair';
  const html = await fetchBody(target);
  const start = html.lastIndexOf('<script>');
  const end = html.indexOf('</script>', start);
  if (start < 0 || end < 0) { console.log('FAIL: 页面里没有 <script> 段'); process.exit(1); }
  const js = html.substring(start + '<script>'.length, end);

  const created = [];
  const mk = (tag) => ({
    tag, className: '', textContent: '', innerHTML: '', children: [],
    appendChild(c) { this.children.push(c); created.push(c); },
  });
  global.document = { getElementById: () => mk('div'), createElement: mk };
  global.window = global;

  try {
    eval(js);
  } catch (e) {
    console.log('FAIL: 页面 JS 执行报错 → ' + e.name + ': ' + e.message);
    process.exit(1);
  }

  const boxes = created.filter((e) => e.className === 'qrbox');
  const imgs = boxes.filter((b) => /<img/i.test(b.innerHTML));
  const labels = created.filter((e) => e.className === 'ip').map((e) => e.textContent);
  console.log('qrbox 数量 = ' + boxes.length + '，其中含 <img> 的 = ' + imgs.length);
  console.log('候选标签 = ' + JSON.stringify(labels));
  if (boxes.length === 0) { console.log('FAIL: 一个二维码都没渲染'); process.exit(1); }
  if (imgs.length !== boxes.length) { console.log('FAIL: 有候选没渲染出二维码图片'); process.exit(1); }
  console.log('OK: ' + imgs.length + ' 张二维码都已生成');
})();
