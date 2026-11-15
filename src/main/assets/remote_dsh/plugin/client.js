/**
 * SmartQuiz Remote · 界面部分（DSH Web UI 插件）
 *
 * 槽位：conversation.composer.dock（输入框上方那一排）
 *
 * 2026-11-16 重做要点：
 * 1) **地址不再混为一谈**：局域网 / 组网（VPN，如 Tailscale 100.64/10）/ 公网 三类分开标注，
 *    各自带 badge；"公网隧道"单独一栏（可选），不再把组网地址叫成"公网隧道"。
 * 2) 推荐地址置顶并标注「推荐」，其余地址折在下面，每条都能一键复制。
 * 3) 颜色全部继承宿主（只用 currentColor / 无字面色值），主题切换自动跟随。
 * 4) 文案走 t()（DSH Client locale 服务可用时自动多语言，不可用则中文兜底）。
 */
window.__ModuleLoader__.load({
  id: '@local/smartquiz-remote',
  factory(require) {
    const React = require('react');
    const h = React.createElement;

    /** 小工具：复制到剪贴板（失败返回 false，由调用方提示） */
    function copyText(text) {
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text);
          return true;
        }
      } catch { /* 落到下面的兜底 */ }
      try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        const ok = document.execCommand('copy');
        document.body.removeChild(ta);
        return ok;
      } catch {
        return false;
      }
    }

    /** 判断一个地址是否是组网（VPN mesh）地址：Tailscale/ZeroTier 的 100.64.0.0/10。 */
    function meshPubUrl(url) {
      const m = /(?:^|\/\/)(\d{1,3})\.(\d{1,3})\./.exec(String(url || ''));
      if (!m) return false;
      return Number(m[1]) === 100 && Number(m[2]) >= 64 && Number(m[2]) <= 127;
    }

    function Panel(props) {
      const t = (props && typeof props.t === 'function') ? props.t : ((key, fallback) => fallback);

      const [open, setOpen] = React.useState(false);
      const [info, setInfo] = React.useState(null);
      const [st, setSt] = React.useState(null);
      const [bal, setBal] = React.useState(null);
      const [vw, setVw] = React.useState(null);
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
        fetch('/status').then((r) => (r.ok ? r.json() : null)).then((j) => setSt(j)).catch(() => setSt(null));
        fetch('/v2/view').then((r) => (r.ok ? r.json() : null)).then((j) => setVw(j)).catch(() => setVw(null));
        loadBalance();
      }, [loadBalance]);

      React.useEffect(() => { if (open) load(); }, [open, load]);
      React.useEffect(() => {
        if (!open) return undefined;
        const timer = setInterval(load, 5000);
        return () => clearInterval(timer);
      }, [open, load]);
      React.useEffect(() => { if (info) setPubUrl(info.public_url || ''); }, [info]);

      const setAllowExec = (v) => {
        setNote('');
        fetch('/config', { method: 'POST', headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ allowExec: v }) })
          .then((r) => r.json().then((j) => ({ ok: r.ok, j })))
          .then(({ ok, j }) => {
            if (ok && j && j.ok) {
              setNote(j.note || t('panel.saved', '已更新'));
              setSt((prev) => Object.assign({}, prev, { exec: Object.assign({}, prev && prev.exec, { allowed: v }) }));
            } else {
              setErr((j && j.error) || t('panel.saveFailed', '设置失败'));
            }
          })
          .catch((e) => setErr(t('panel.saveFailed', '设置失败') + ': ' + String(e && e.message ? e.message : e)));
      };

      const savePubUrl = () => {
        fetch('/pair/public-url', { method: 'POST', headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ url: pubUrl }) })
          .then((r) => r.json())
          .then((j) => { setErr(j && j.ok ? '' : ((j && j.error) || t('panel.saveFailed', '保存失败'))); load(); })
          .catch(() => setErr(t('panel.saveFailed', '保存失败')));
      };

      const items = (info && Array.isArray(info.items)) ? info.items : [];
      const recommended = items.find((i) => i.recommended) || items[0] || null;
      const others = items.filter((i) => i !== recommended);

      /** 通用次要按钮样式（全部继承文本色，不写字面色值） */
      const chipStyle = {
        display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12,
        padding: '3px 10px', borderRadius: 999, cursor: 'pointer',
        border: '1px solid currentColor', background: 'transparent', color: 'inherit', opacity: 0.92,
      };
      const smallBtn = Object.assign({}, chipStyle, { padding: '1px 8px', fontSize: 11 });
      const section = {
        marginTop: 8, padding: '8px 10px', borderRadius: 8,
        border: '1px solid currentColor', borderColor: 'color-mix(in srgb, currentColor 22%, transparent)',
      };
      const label = { opacity: 0.7, fontSize: 11 };
      const mono = {
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
        fontSize: 11.5, wordBreak: 'break-all',
      };
      const row = { display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 };

      /** 地址类型 badge 文案 */
      const kindLabel = (it) => {
        if (!it) return '';
        if (it.kind === 'lan') return t('panel.kindLan', '局域网');
        if (it.kind === 'mesh') return t('panel.kindMesh', '组网（VPN）');
        return t('panel.kindPublic', '公网');
      };

      const statusLine = () => {
        if (err) return t('panel.loadFailed', '读取失败');
        if (!info) return t('panel.loading', '读取中…');
        return t('panel.enabled', '已启用 ✓');
      };

      const execAllowed = !!(st && st.exec && st.exec.allowed);
      const modeText = st && st.cli
        ? (st.cli.mode === 'in-process'
          ? t('panel.modeInProcess', '进程内（直连）')
          : t('panel.modeCli', 'CLI 子进程'))
        : '';
      const vwHasError = !!(vw && (vw.cards || []).some((c) =>
        c.kind === 'checks' && (c.items || []).some((i) => i.state === 'error')));

      const addrRow = (it, strong) => h('div', { style: row, key: it.base_url },
        h('span', { style: Object.assign({}, label, { minWidth: 74 }) }, kindLabel(it)),
        h('span', { style: Object.assign({}, mono, { flex: 1 }) }, it.base_url),
        strong && it.recommended
          ? h('span', { style: Object.assign({}, smallBtn, { cursor: 'default', opacity: 1 }) },
            t('panel.recommended', '推荐'))
          : null,
        h('button', {
          style: smallBtn,
          onClick: () => { if (!copyText(it.base_url)) setNote(t('panel.copyFailed', '复制失败')); },
        }, t('panel.copy', '复制')),
      );

      return h('div', { style: { display: 'flex', flexDirection: 'column', gap: 4, padding: '2px 0' } },
        h('div', { style: { display: 'flex', alignItems: 'center', gap: 8 } },
          h('button', {
            style: chipStyle,
            title: t('panel.title', '手机远程接入（答题宝）'),
            onClick: () => setOpen((v) => !v),
          }, '📱 ' + t('panel.title', '手机远程') + (open ? ' ▲' : ' ▼')),
          h('span', { style: label }, statusLine()),
        ),

        open && h('div', {
          style: {
            fontSize: 12, lineHeight: 1.6, padding: '8px 12px', borderRadius: 8,
            border: '1px solid currentColor',
            borderColor: 'color-mix(in srgb, currentColor 20%, transparent)',
            minWidth: 300, maxWidth: 460,
          },
        },
          // ---- 状态 ----
          h('div', { style: section },
            h('div', null, t('panel.status', '状态：'), h('b', null, statusLine()),
              modeText ? h('span', { style: label }, '  ·  ' + modeText) : null),
            h('label', { style: Object.assign({}, row, { cursor: 'pointer' }) },
              h('input', { type: 'checkbox', checked: execAllowed, onChange: (e) => setAllowExec(e.target.checked) }),
              h('span', null, t('panel.execAllowed', '允许手机在电脑上执行命令'))),
            bal && h('div', { style: row },
              h('span', { style: label }, t('panel.balance', 'DeepSeek 余额：')),
              bal.ok
                ? h('b', null, (bal.total_balance || '?') + ' ' + (bal.currency || ''),
                  bal.is_available === false ? '（不可用）' : '')
                : h('span', { style: label }, t('panel.loadFailed', '读取失败')),
              h('button', { style: smallBtn, onClick: () => { setBal(null); loadBalance(); } },
                t('panel.refresh', '刷新'))),
          ),

          // ---- 地址 / 扫码 ----
          h('div', { style: section },
            h('div', { style: label }, t('panel.addrTitle', '手机连接地址')),
            recommended
              ? addrRow(recommended, true)
              : h('div', { style: Object.assign({}, label, row) }, t('panel.addrNone', '还没有可用的局域网地址')),
            others.length > 0 && h('div', { style: Object.assign({}, label, { marginTop: 6 }) },
              t('panel.otherAddrs', '其它可用地址')),
            others.map((it) => addrRow(it, false)),
            info && h('div', { style: Object.assign({}, row, { marginTop: 8 }) },
              h('span', { style: label }, t('panel.pairString', '配对串')),
              h('span', { style: Object.assign({}, mono, { flex: 1 }) },
                String(info.qr_text || '').replace(/token=.*/, 'token=***')),
              h('button', {
                style: smallBtn,
                onClick: () => { if (!copyText(info.qr_text)) setNote(t('panel.copyFailed', '复制失败')); },
              }, t('panel.copy', '复制'))),
            h('div', { style: row },
              h('button', {
                style: smallBtn,
                onClick: () => {
                  fetch('/pair/open', { method: 'POST' })
                    .then((r) => r.json())
                    .then((j) => setErr(j && j.ok ? '' : t('panel.openFailed', '打开失败')))
                    .catch(() => setErr(t('panel.openFailed', '打开失败')));
                },
              }, t('panel.openPairing', '在电脑上打开配对页（扫码用）'))),
          ),

          // ---- 公网隧道（可选） ----
          h('div', { style: section },
            h('div', { style: label }, t('panel.tunnelTitle', '公网隧道（可选，不填也能用局域网）')),
            h('div', { style: row },
              h('input', {
                value: pubUrl,
                placeholder: 'https://xxxx.vicp.fun',
                onChange: (e) => setPubUrl(e.target.value),
                style: { flex: 1, minWidth: 160, fontSize: 12, padding: '3px 6px',
                  color: 'inherit', background: 'transparent', border: '1px solid currentColor', borderRadius: 6 },
              }),
              h('button', { style: smallBtn, onClick: savePubUrl }, t('panel.save', '保存'))),
            h('div', { style: Object.assign({}, label, { marginTop: 4 }) },
              t('panel.tunnelHint', '只有真正的公网/隧道地址才填这里；Tailscale 这类组网地址会被自动识别成「组网（VPN）」，不用填。')),
            // 已填地址若其实是组网地址（100.64/10），明确点出来 —— 免得以为"开了隧道"
            meshPubUrl(pubUrl) && h('div', { style: Object.assign({}, label, { marginTop: 4 }) },
              'ℹ ' + t('panel.meshDetected', '这看起来是组网（VPN）地址，不是公网隧道；手机在组网内也能直连。')),
          ),

          // ---- 自检 ----
          vw && h('div', { style: section },
            h('div', null, t('panel.selfCheck', '电脑自检：'),
              h('b', null, vwHasError ? '✗ ' + t('panel.selfCheckErr', '有错误') : '✓ ' + t('panel.selfCheckOk', '正常')),
              h('span', { style: label }, '  v2 · ' + new Date(vw.generated_at || Date.now()).toLocaleTimeString())),
            (vw.cards || []).slice(0, 6).map((c, i) => h('div', { key: i, style: label },
              '· ' + (c.title || c.kind))),
          ),

          // ---- 提示 ----
          note && h('div', { style: label }, '✓ ' + note),
          err && h('div', { style: label }, '✗ ' + err),
          h('div', { style: Object.assign({}, label, { marginTop: 4 }) },
            t('panel.hint', '手机：答题宝 → 工具集 → 远程连接（电脑）→ 扫码配对')),
        ),
      );
    }

    return {
      inject: ['slots'],
      apply(ctx) {
        const localeSvc = (() => {
          try {
            return ctx.locale ?? (typeof ctx.get === 'function' ? ctx.get('locale') : null) ?? null;
          } catch { return null; }
        })();
        const t = (key, fallback) => {
          if (!localeSvc) return fallback;
          const candidates = [key, 'smartquiz-remote.' + key, '@local/smartquiz-remote.' + key];
          for (const k of candidates) {
            try {
              if (typeof localeSvc.t === 'function') {
                const v = localeSvc.t(k);
                if (typeof v === 'string' && v && v !== k) return v;
              }
              if (typeof localeSvc.translate === 'function') {
                const v = localeSvc.translate(k);
                if (typeof v === 'string' && v && v !== k) return v;
              }
              const direct = localeSvc[k];
              if (typeof direct === 'string' && direct) return direct;
            } catch { /* 单个形状失败不影响其它形状 */ }
          }
          return fallback;
        };
        ctx.slots.inject('conversation.composer.dock', () => ctx.slots.register({
          name: 'conversation.composer.dock',
          id: 'smartquiz-remote',
          order: 6,
        }, (slotProps) => h(Panel, Object.assign({}, slotProps, { t }))));
      },
    };
  },
});
