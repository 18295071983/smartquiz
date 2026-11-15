/**
 * SmartQuiz Remote · 界面部分（DSH Web UI 插件）
 *
 * 槽位：conversation.input.activity（输入框工具条，与 progress 插件同一槽位）
 *
 * 2026-11-16 定稿要点：
 * 1) 弹层用原生 <dialog> + showModal()：**top layer** 渲染，天然不受祖先 transform/overflow/
 *    z-index 影响 —— 之前用 position:fixed 会因为输入区容器带 transform 而整体偏移（"锚点不对"），
 *    也会被其它浮层盖住（"显示在最上层"）。
 * 2) 位置：贴视口顶部 + 水平居中（top: 12px），高度不超过视口，内容内部滚动。
 * 3) ErrorBoundary 兜底：组件抛错时仍保留一个可点的入口，不会连按钮一起消失。
 * 4) 二维码由服务端 /pair/qr.svg 生成并内联显示；配对码走 /pair/code（5 分钟一次性）。
 * 5) 面板分「服务端 · 这台电脑」与「客户端 · 手机」两块，明确两边职责。
 * 6) 颜色全部继承宿主（无字面色值）；文案走 t()（有 locale 服务则多语言，否则中文兜底）。
 */
window.__ModuleLoader__.load({
  id: '@local/smartquiz-remote',
  factory(require) {
    const React = require('react');
    const h = React.createElement;

    const PULSE_STYLE = '@keyframes sqr-breathe{0%,100%{opacity:1}50%{opacity:.25}}'
      + '.sqr-breathe{animation:sqr-breathe 1.1s ease-in-out infinite}';

    function copyText(text) {
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(text);
          return true;
        }
      } catch { /* 落到兜底 */ }
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

    function ago(sec) {
      const s = Math.max(0, Math.floor(sec));
      if (s < 60) return s + ' 秒前';
      return Math.floor(s / 60) + ' 分钟前';
    }

    function fmtLeft(sec) {
      const s = Math.max(0, Math.floor(sec));
      const m = Math.floor(s / 60);
      const r = s % 60;
      return m > 0 ? (m + ':' + String(r).padStart(2, '0')) : (r + 's');
    }

    /** 错误兜底：slot 组件抛错时宿主会整条抹掉，这里保证入口还在。 */
    class Boom extends React.Component {
      constructor(props) {
        super(props);
        this.state = { error: null, open: false };
      }

      static getDerivedStateFromError(error) {
        return { error };
      }

      componentDidCatch(error, info) {
        try { console.error('[smartquiz-remote] panel crashed', error, info); } catch { /* ignore */ }
      }

      render() {
        if (!this.state.error) return this.props.children;
        const msg = String((this.state.error && this.state.error.message) || this.state.error);
        return h('span', { style: { display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12 } },
          h('button', {
            style: { border: '1px solid currentColor', borderRadius: 999, padding: '2px 10px',
              background: 'transparent', color: 'inherit', cursor: 'pointer' },
            onClick: () => this.setState({ open: !this.state.open }),
          }, '📱 手机远程 ⚠'),
          this.state.open && h('span', { style: { maxWidth: 360, whiteSpace: 'normal', opacity: 0.8, fontSize: 11 } },
            '面板出错：' + msg));
      }
    }

    function Panel(props) {
      const t = (props && typeof props.t === 'function') ? props.t : ((key, fallback) => fallback);

      const [open, setOpen] = React.useState(false);
      const [info, setInfo] = React.useState(null);
      const [st, setSt] = React.useState(null);
      const [bal, setBal] = React.useState(null);
      const [err, setErr] = React.useState('');
      const [note, setNote] = React.useState('');
      const [pubUrl, setPubUrl] = React.useState('');
      const [code, setCode] = React.useState(null);   // { code, expires_at }
      const [now, setNow] = React.useState(Date.now());
      const [updatedAt, setUpdatedAt] = React.useState(0);
      const dlgRef = React.useRef(null);

      // 每秒 tick：驱动「n 秒前」与配对码倒计时
      React.useEffect(() => {
        if (!open) return undefined;
        const timer = setInterval(() => setNow(Date.now()), 1000);
        return () => clearInterval(timer);
      }, [open]);

      const loadBalance = React.useCallback(() => {
        fetch('/balance').then((r) => (r.ok ? r.json() : null))
          .then((j) => setBal(j)).catch(() => setBal(null));
      }, []);

      const loadCode = React.useCallback(() => {
        fetch('/pair/code', { cache: 'no-store' })
          .then((r) => (r.ok ? r.json() : null))
          // 现有端点契约：{ ok, code, expires_in_ms }（5 分钟一次性码，CSPRNG 生成）
          .then((j) => { if (j && j.ok) setCode({ code: j.code, expires_at: Date.now() + Number(j.expires_in_ms || 0) }); })
          .catch(() => setCode(null));
      }, []);

      const load = React.useCallback(() => {
        setErr('');
        fetch('/pair.json', { cache: 'no-store' })
          .then((r) => (r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status))))
          .then((j) => { setInfo(j); setUpdatedAt(Date.now()); })
          .catch((e) => setErr(String(e && e.message ? e.message : e)));
        fetch('/status', { cache: 'no-store' })
          .then((r) => (r.ok ? r.json() : null)).then((j) => setSt(j)).catch(() => setSt(null));
        loadBalance();
        loadCode();
      }, [loadBalance, loadCode]);

      React.useEffect(() => { if (open) load(); }, [open, load]);
      React.useEffect(() => {
        if (!open) return undefined;
        const timer = setInterval(load, 5000);
        return () => clearInterval(timer);
      }, [open, load]);
      React.useEffect(() => {
        if (!open || !code) return undefined;
        if (code.expires_at && now > code.expires_at) loadCode();
        return undefined;
      }, [open, code, now, loadCode]);
      React.useEffect(() => { if (info) setPubUrl(info.public_url || ''); }, [info]);

      // 打开时 showModal()：进入 top layer，不受祖先 transform / overflow / z-index 影响
      React.useEffect(() => {
        const dlg = dlgRef.current;
        if (!dlg) return;
        try {
          if (open && !dlg.open) dlg.showModal();
          if (!open && dlg.open) dlg.close();
        } catch {
          // 老浏览器没有 showModal 就退化为普通元素显示
        }
      }, [open]);

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
      // 2026-11-16 修：客户端实际连着却显示"未连接"。
      // 旧逻辑只认 /status.connections，而旧版 /status 根本没有该字段 → 永远 false。
      // 现在按优先级取信号：连接快照 > ws_clients/session_streams > clients 列表。
      const connList = (st && Array.isArray(st.connections)) ? st.connections : [];
      const wsClients = Number(st && st.ws_clients) || 0;
      const streams = Number(st && st.session_streams) || 0;
      const clientRows = (st && Array.isArray(st.clients)) ? st.clients.length : 0;
      const online = connList.length > 0
        ? connList.some((c) => c && c.online !== false)
        : (wsClients > 0 || streams > 0 || clientRows > 0);
      // 传输方式（仅用于文案：WS / SSE），没有连接时为空
      const transport = (() => {
        if (connList.length > 0) {
          const kinds = connList.map((c) => String((c && c.kind) || '')).filter(Boolean);
          if (kinds.includes('ws')) return 'WS';
          if (kinds.length > 0) return kinds[0].toUpperCase();
        }
        if (wsClients > 0) return 'WS';
        if (streams > 0) return 'SSE';
        return '';
      })();
      const devices = (st && Number(st.devices)) || 0;
      const execAllowed = !!(st && st.exec && st.exec.allowed);
      const modeText = st && st.cli
        ? (st.cli.mode === 'in-process' ? t('panel.modeInProcess', '进程内（直连）') : t('panel.modeCli', 'CLI 子进程'))
        : '';
      const codeLeft = code && code.expires_at ? Math.max(0, Math.ceil((code.expires_at - now) / 1000)) : 0;

      const chipStyle = {
        display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12,
        padding: '2px 10px', borderRadius: 999, cursor: 'pointer',
        border: '1px solid currentColor', background: 'transparent', color: 'inherit',
      };
      const smallBtn = Object.assign({}, chipStyle, { padding: '1px 8px', fontSize: 11 });
      const label = { opacity: 0.7, fontSize: 11 };
      const mono = { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace', fontSize: 11.5, wordBreak: 'break-all' };
      const row = { display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 };
      const section = {
        marginTop: 8, padding: '8px 10px', borderRadius: 8,
        border: '1px solid currentColor', borderColor: 'color-mix(in srgb, currentColor 22%, transparent)',
      };
      // <dialog> 自带居中与边框；这里改成"贴顶 + 水平居中"，并让它继承宿主配色
      const dialogStyle = {
        position: 'fixed', top: '12px', left: '50%', transform: 'translateX(-50%)',
        margin: 0, padding: '14px 16px', width: 460, maxWidth: '92vw',
        maxHeight: 'calc(100vh - 24px)', overflowY: 'auto',
        borderRadius: 12, fontSize: 12, lineHeight: 1.6,
        background: 'Canvas', color: 'CanvasText',
        border: '1px solid currentColor',
        boxShadow: '0 10px 40px color-mix(in srgb, CanvasText 30%, transparent)',
      };

      const kindLabel = (it) => {
        if (!it) return '';
        if (it.kind === 'lan') return t('panel.kindLan', '局域网');
        if (it.kind === 'mesh') return t('panel.kindMesh', '组网（VPN）');
        return t('panel.kindPublic', '公网');
      };
      const addrRow = (it, strong) => h('div', { style: row, key: it.base_url },
        h('span', { style: Object.assign({}, label, { minWidth: 74 }) }, kindLabel(it)),
        h('span', { style: Object.assign({}, mono, { flex: 1 }) }, it.base_url),
        strong && it.recommended
          ? h('span', { style: Object.assign({}, smallBtn, { cursor: 'default' }) }, t('panel.recommended', '推荐'))
          : null,
        h('button', { style: smallBtn, onClick: () => { if (!copyText(it.base_url)) setNote(t('panel.copyFailed', '复制失败')); } },
          t('panel.copy', '复制')));

      const statusDot = h('span', {
        className: online ? 'sqr-breathe' : '',
        style: { display: 'inline-block', width: 8, height: 8, borderRadius: 4,
          background: 'currentColor', opacity: online ? 1 : 0.4 },
      });

      return h('span', { style: { position: 'relative', display: 'inline-flex' } },
        h('style', null, PULSE_STYLE),
        h('button', { style: chipStyle, onClick: () => setOpen(true), title: t('panel.title', '手机远程接入（答题宝）') },
          statusDot, ' 📱 ' + t('panel.title', '手机远程')),

        h('dialog', {
          ref: dlgRef,
          id: 'smartquiz-remote-panel',
          style: dialogStyle,
          onCancel: (e) => { e.preventDefault(); setOpen(false); },
          onClick: (e) => { if (e.target === dlgRef.current) setOpen(false); },
        },
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 } },
            h('b', { style: { fontSize: 13 } }, '📱 ' + t('panel.title', '手机远程')),
            h('span', { style: Object.assign({}, label, { flex: 1 }) },
              online ? (t('panel.connected', '在线') + (transport ? '（' + transport + '）' : '')) : t('panel.disconnected', '未连接')),
            h('button', { style: smallBtn, title: t('panel.close', '关闭'), onClick: () => setOpen(false) }, '✕')),

          // ---------- 服务端（这台电脑） ----------
          h('div', { style: section },
            h('div', { style: { fontWeight: 600 } },
              '🖥 ' + t('panel.serverTitle', '服务端 · 这台电脑'),
              h('span', { style: label }, '  ' + (err ? t('panel.loadFailed', '读取失败')
                : (info ? t('panel.enabled', '已启用 ✓') : t('panel.loading', '读取中…')))),
              updatedAt > 0 ? h('span', { style: label }, '  · ' + ago((now - updatedAt) / 1000)) : null),
            recommended
              ? addrRow(recommended, true)
              : h('div', { style: Object.assign({}, label, row) }, t('panel.addrNone', '还没有可用的局域网地址')),
            others.length > 0 && h('div', { style: Object.assign({}, label, { marginTop: 6 }) }, t('panel.otherAddrs', '其它可用地址')),
            others.map((it) => addrRow(it, false)),
            h('div', { style: row },
              h('span', { style: label }, t('panel.modeLabel', '执行位置')),
              h('span', null, modeText || '—'),
              h('span', { style: label }, '  ·  ' + t('panel.port', '端口') + ' ' + ((info && info.port) || '—'))),
            h('label', { style: Object.assign({}, row, { cursor: 'pointer' }) },
              h('input', { type: 'checkbox', checked: execAllowed, onChange: (e) => setAllowExec(e.target.checked) }),
              h('span', null, t('panel.execAllowed', '允许手机在电脑上执行命令')))),

          // ---------- 客户端（手机） ----------
          h('div', { style: section },
            h('div', { style: { fontWeight: 600 } }, '📱 ' + t('panel.clientTitle', '客户端 · 手机'),
              h('span', { style: label }, '  ' + (online ? (t('panel.connected', '在线') + (transport ? '（' + transport + '）' : '')) : t('panel.disconnected', '未连接'))
                + ' · ' + t('panel.devices', '已配对') + ' ' + devices + ' 台')),
            recommended && h('div', { style: Object.assign({}, row, { marginTop: 8, alignItems: 'flex-start' }) },
              h('img', {
                src: '/pair/qr.svg?text=' + encodeURIComponent(recommended.qr_text || '') + '&cell=4',
                alt: 'pairing qr',
                width: 132, height: 132,
                style: { imageRendering: 'pixelated', borderRadius: 6, background: '#fff' },
              }),
              h('div', { style: { flex: 1 } },
                h('div', { style: label }, t('panel.qrHint', '手机扫码即可配对（二维码由服务端生成，不用打开浏览器）')),
                h('div', { style: Object.assign({}, mono, { marginTop: 6 }) },
                  String(recommended.qr_text || '').replace(/token=.*/, 'token=***')),
                h('div', { style: row },
                  h('button', { style: smallBtn, onClick: () => { if (!copyText(recommended.qr_text || '')) setNote(t('panel.copyFailed', '复制失败')); } },
                    t('panel.copyPair', '复制配对串')),
                  h('button', { style: smallBtn, onClick: () => { if (!copyText(recommended.base_url || '')) setNote(t('panel.copyFailed', '复制失败')); } },
                    t('panel.copy', '复制'))))),
            h('div', { style: row },
              h('span', { style: label }, t('panel.codeTitle', '动态配对码')),
              code
                ? h('b', { style: Object.assign({}, mono, { fontSize: 15, letterSpacing: 2 }) }, code.code)
                : h('span', { style: label }, '—'),
              code ? h('span', { style: label }, ' · ' + (codeLeft > 0
                ? t('panel.codeValid', '有效') + ' ' + fmtLeft(codeLeft)
                : t('panel.codeExpired', '已过期'))) : null,
              h('button', { style: smallBtn, onClick: loadCode }, t('panel.refresh', '刷新'))),
            h('div', { style: Object.assign({}, label, { marginTop: 4 }) },
              t('panel.codeHint', '配对码由电脑签发，5 分钟内一次性有效；在手机「扫码 / 口令配对」里输入即可（后端用 CSPRNG 生成）。'))),

          // ---------- 对外地址（可选） ----------
          h('div', { style: section },
            h('div', { style: label }, t('panel.tunnelTitle', '对外地址（可选，不填也能用局域网）')),
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
              t('panel.tunnelHint', '只有真正的公网/隧道地址才填这里；Tailscale 这类组网地址会被自动识别成「组网（VPN）」，不用填。'))),

          // ---------- 余额 / 提示 ----------
          bal && h('div', { style: Object.assign({}, row, { marginTop: 8 }) },
            h('span', { style: label }, t('panel.balance', 'DeepSeek 余额：')),
            bal.ok ? h('b', null, (bal.total_balance || '?') + ' ' + (bal.currency || '')) : h('span', { style: label }, '—')),
          note && h('div', { style: label }, '✓ ' + note),
          err && h('div', { style: label }, '✗ ' + err)),
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
        ctx.slots.inject('conversation.input.activity', () => ctx.slots.register({
          name: 'conversation.input.activity',
          id: 'smartquiz-remote',
          order: 6,
        }, (slotProps) => h(Boom, null, h(Panel, Object.assign({}, slotProps, { t })))));
      },
    };
  },
});
