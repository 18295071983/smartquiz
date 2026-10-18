const fs = require('fs');

/**
 * 从 MarkdownHtmlConverter.java 里把 bootstrapJs() 拼出的 JS 原文抽出来做校验。
 * 必须按字符扫（状态机）：Java 注释里也带英文双引号，正则抓字面量会串味。
 *
 * 校验两件事：
 * 1) 拼出来的整段 JS 语法合法（node --check）——这段是运行时注入的，写错一个引号
 *    就会让复制/图片/链接归一化一起静默失效，编译期完全发现不了。
 * 2) aNorm() 真把 target 摘掉了（带 target=_blank 的链接会走 window.open，
 *    绕开原生 shouldOverrideUrlLoading → 点了没反应）。
 */
function extractBootstrapJs(javaPath) {
  const src = fs.readFileSync(javaPath, 'utf8');
  const start = src.indexOf('private static String bootstrapJs()');
  if (start < 0) throw new Error('bootstrapJs() 未找到');
  const end = src.indexOf('\n    }', src.indexOf('DOMContentLoaded', start));
  const body = src.slice(start, end);

  let js = '';
  let i = 0;
  let inString = false, inLine = false, inBlock = false;
  while (i < body.length) {
    const c = body[i], n = body[i + 1];
    if (inLine) { if (c === '\n') inLine = false; i++; continue; }
    if (inBlock) { if (c === '*' && n === '/') { inBlock = false; i += 2; continue; } i++; continue; }
    if (inString) {
      if (c === '\\') {
        const nx = body[i + 1];
        if (nx === '"' || nx === '\\') { js += nx; i += 2; continue; }
        if (nx === 'n') { js += '\n'; i += 2; continue; }
        if (nx === 't') { js += '\t'; i += 2; continue; }
        if (nx === 'r') { js += '\r'; i += 2; continue; }
        js += c; i++; continue;                      // \uXXXX 等原样留给 JS
      }
      if (c === '"') { inString = false; i++; continue; }
      js += c; i++; continue;
    }
    if (c === '/' && n === '/') { inLine = true; i += 2; continue; }
    if (c === '/' && n === '*') { inBlock = true; i += 2; continue; }
    if (c === '"') { inString = true; i++; continue; }
    i++;
  }
  return js;
}

const js = extractBootstrapJs('src/main/java/com/oilquiz/app/ai/chat/render/MarkdownHtmlConverter.java');
fs.writeFileSync('build/tmp-boot-check.js', js);
console.log('js chars:', js.length);
console.log('桥调用仍只有 copy/previewImage:',
  js.indexOf('QZ.copy') >= 0 && js.indexOf('QZ.previewImage') >= 0);
console.log('JS 里不出现 QZ.openLink（不扩桥）:', js.indexOf('QZ.openLink') < 0);

// aNorm 行为烟测：假 DOM，验证 target 被摘、rel 被设
function anchor(attrs) {
  const a = {
    tagName: 'A', style: {}, _attrs: Object.assign({}, attrs),
    getAttribute(k) { return this._attrs[k] !== undefined ? this._attrs[k] : null; },
    removeAttribute(k) { delete this._attrs[k]; if (k === 'target') this.target = undefined; },
  };
  Object.assign(a, attrs || {});
  return a;
}
const a1 = anchor({ href: 'files/报告.md', target: '_blank' });
const a2 = anchor({ href: 'https://example.com' });
const documentStub = {
  // 真实 DOM 的 getElementsByTagName 对 HTML 标签大小写不敏感，这里同样处理
  getElementsByTagName: (tag) => (String(tag).toUpperCase() === 'A' ? [a1, a2] : []),
  addEventListener() {}, createTreeWalker: undefined,
  body: { scrollHeight: 10 }, documentElement: {},
};
const fnText = js.slice(js.indexOf('function aNorm()'), js.indexOf('try{math();'));
new Function('document', 'window', fnText + ';aNorm();')(documentStub, {});
console.log('target=_blank 已摘除:', a1.target === undefined);
console.log('rel 已设为 noopener:', a1.rel === 'noopener' && a2.rel === 'noopener');
