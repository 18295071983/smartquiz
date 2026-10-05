/**
 * SmartQuiz Remote · 界面部分（DSH Web UI 插件）
 * 槽位：conversation.composer.dock（输入框上方那一排，模板确认可用 ✓）
 * 作用：显示"手机远程"状态 + 一键打开配对页（二维码在电脑屏幕上 ✓）
 */
window.__ModuleLoader__.load({
  id: '@local/smartquiz-remote',
  factory(require) {
    const React = require('react');
    const h = React.createElement;

    function Panel() {
      const [open, setOpen] = React.useState(false);
      const [info, setInfo] = React.useState(null);
      const [err, setErr] = React.useState('');
      const [pubUrl, setPubUrl] = React.useState('');

      const load = React.useCallback(() => {
        setErr('');
        fetch('/pair.json').then((r) => (r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status))))
          .then((j) => setInfo(j))
          .catch((e) => setErr(String(e && e.message ? e.message : e)));
      }, []);

      React.useEffect(() => { if (open) load(); }, [open, load]);
      React.useEffect(() => { if (info) setPubUrl(info.public_url || ''); }, [info]);
      const savePubUrl = () => {
        fetch('/pair/public-url', { method: 'POST', headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ url: pubUrl }) })
          .then((r) => r.json())
          .then((j) => { setErr(j && j.ok ? '' : ((j && j.error) || '保存失败')); load(); })
          .catch(() => setErr('保存失败'));
      };

      const chip = {
        display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12,
        padding: '3px 10px', borderRadius: 999, cursor: 'pointer',
        border: '1px solid currentColor', opacity: 0.9,
      };
      return h('div', { style: { display: 'flex', alignItems: 'center', gap: 8, padding: '2px 0' } },
        h('button', { style: chip, onClick: () => setOpen((v) => !v), title: '手机远程接入（答题宝）' },
          '📱 手机远程' + (open ? ' ▲' : ' ▼')),
        open && h('div', {
          style: { fontSize: 12, lineHeight: 1.6, padding: '6px 10px', borderRadius: 8,
                   border: '1px solid currentColor', minWidth: 260 },
        },
          h('div', null, '状态：', h('b', null, err ? '读取失败' : (info ? '已启用 ✓' : '读取中…'))),
          info && h('div', null, '局域网：', h('code', null, info.base_url)),
          info && h('div', null, '令牌：', h('code', null, String(info.token).slice(0, 8) + '…')),
info && h('div', null, '二维码：', h('button', {
            style: { ...chip, padding: '2px 8px', fontSize: 12 },
            onClick: () => { fetch('/pair/open', { method: 'POST' }).then((r) => r.json()).then((j) => setErr(j && j.ok ? '' : '打开失败')).catch(() => setErr('打开失败')); },
          }, '在电脑上打开配对页（扫码用）')),
          info && h('div', { style: { display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 } },
            h('span', null, '隧道域名：'),
            h('input', { value: pubUrl, placeholder: 'https://xxxx.vicp.fun（留空=只用局域网）',
              onChange: (e) => setPubUrl(e.target.value),
              style: { flex: 1, minWidth: 180, fontSize: 12, padding: '2px 6px' } }),
            h('button', { style: { padding: '2px 8px', fontSize: 12, cursor: 'pointer' }, onClick: savePubUrl }, '保存')),
          info && h('div', { style: { opacity: 0.9, wordBreak: 'break-all' } }, '配对串：', h('code', null, info.qr_text)),
          h('div', { style: { opacity: 0.75, marginTop: 4 } },
            '手机：答题宝 → 工具集 → 远程连接（电脑）→ 扫码配对'),
          err && h('div', { style: { opacity: 0.75 } }, '（需在电脑本机打开此页面才能读取配对信息）'),
        ));
    }

    return {
      inject: ['slots'],
      apply(ctx) {
        ctx.slots.inject('conversation.composer.dock', () => ctx.slots.register({
          name: 'conversation.composer.dock',
          id: 'smartquiz-remote',
          order: 6,
        }, Panel));
      },
    };
  },
});