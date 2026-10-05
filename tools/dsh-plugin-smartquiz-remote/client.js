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
      const [st, setSt] = React.useState(null);
      const [bal, setBal] = React.useState(null);
      const [err, setErr] = React.useState('');
      const [note, setNote] = React.useState('');
      const [pubUrl, setPubUrl] = React.useState('');

      const loadBalance = React.useCallback(() => {
        fetch('/balance').then((r) => (r.ok ? r.json() : null))
          .then((j) => setBal(j)).catch(() => setBal(null));
      }, []);

      const load = React.useCallback(() => {
        setErr('');
        fetch('/pair.json').then((r) => (r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status))))
          .then((j) => setInfo(j))
          .catch((e) => setErr(String(e && e.message ? e.message : e)));
        // /status 走主令牌读取在浏览器里拿不到，改为本地读取一次即可（同源、仅本机）
        fetch('/status').then((r) => (r.ok ? r.json() : null)).then((j) => setSt(j)).catch(() => setSt(null));
        loadBalance();
      }, [loadBalance]);

      React.useEffect(() => { if (open) load(); }, [open, load]);
      React.useEffect(() => { if (info) setPubUrl(info.public_url || ''); }, [info]);

      /** 切换"允许远程执行命令"（仅本机可调） */
      const setAllowExec = (v) => {
        setNote('');
        fetch('/config', { method: 'POST', headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ allowExec: v }) })
          .then((r) => r.json().then((j) => ({ ok: r.ok, j })))
          .then(({ ok, j }) => {
            if (ok && j && j.ok) {
              setNote(j.note || '已更新');
              setSt((prev) => Object.assign({}, prev, {
                channels: Object.assign({}, prev && prev.channels, { exec: j.allowExec }),
                permission_policy: j.permission_policy,
              }));
            } else { setErr((j && j.error) || '设置失败'); }
          })
          .catch((e) => setErr('设置失败: ' + String(e && e.message ? e.message : e)));
      };

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
          // DeepSeek 账户余额（仅本机可查；走插件 /balance → api.deepseek.com/user/balance，缓存 60 秒）
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 6 } },
            h('span', null, 'DeepSeek 余额：'),
            !bal && h('span', { style: { opacity: 0.7 } }, '读取中…'),
            bal && bal.ok && h('b', null,
              (bal.total_balance || '?') + ' ' + (bal.currency || ''),
              bal.is_available === false ? '（不可用）' : ''),
            bal && bal.ok && h('button', {
              style: { ...chip, padding: '1px 8px', fontSize: 11 },
              onClick: () => { setBal(null); loadBalance(); },
              title: '重新查询（结果缓存 60 秒）',
            }, '刷新'),
            bal && !bal.ok && h('span', { style: { opacity: 0.8 }, title: String(bal.detail || bal.hint || '') },
              bal.error === 'no_api_key' ? '未配置 API Key' : '读取失败（' + (bal.error || '?') + '）')),
          info && h('div', null, '局域网：', h('code', null, info.base_url)),
          info && h('div', null, '已配对设备：', h('b', null, String((st && st.devices) || 0) + ' 台')),
          // ★ 远程执行命令开关（2026-10-06 用户要求做成 UI）。仅本机可改：手机端只显示状态。
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 8, marginTop: 4 } },
            h('label', { style: { display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer' } },
              h('input', {
                type: 'checkbox',
                checked: !!(st && st.channels && st.channels.exec),
                onChange: (e) => setAllowExec(e.target.checked),
              }),
              h('span', null, '允许手机在电脑上执行命令（/exec）')),
            h('span', { style: { opacity: 0.7 } },
              (st && st.channels && st.channels.exec) ? '当前：允许' : '当前：禁止')),
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
          note && h('div', { style: { opacity: 0.85, marginTop: 4 } }, '✓ ' + note),
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