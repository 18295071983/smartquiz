/**
 * SmartQuiz Remote · 界面部分（DSH Web UI 插件）
 *
 * 槽位：conversation.input.activity（输入框工具条，与 progress 插件同一槽位 —— 该槽位在本机 DSH 确认可用）
 *
 * 2026-11-16 重做要点：
 * 1) **不再弹外部浏览器**：二维码由服务端 /pair/qr.svg 渲染，面板内联 <img> 显示；
 * 2) **动态配对码**：服务端 /pair/code 签发 30 秒轮换码，面板显示倒计时并自动续期；
 * 3) **服务端 / 客户端分开呈现**：服务端=这台电脑（监听地址、端口、执行位置、在线连接），
 *    客户端=手机（已配对设备数、配对入口）；
 * 4) **呼吸点**：连接正常时状态点做 1.1s 呼吸，旁边的"n 秒前"每秒重算 —— 不再像卡死；
 * 5) 颜色全部继承宿主（无字面色值）；文案走 t()（locale 可用时多语言，否则中文兜底）。
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

    /** 秒 → mm:ss（配对码 5 分钟有效，用分秒比纯秒数好读）。 */
    function fmtLeft(sec) {
      const s = Math.max(0, Math.floor(sec));
      const m = Math.floor(s / 60);
      const r = s % 60;
      return m > 0 ? (m + ':' + String(r).padStart(2, '0')) : (r + 's');
    }
    function ago(sec) {
      const s = Math.max(0, Math.floor(sec));
      if (s < 60) return s + ' 秒前';
      return Math.floor(s / 60) + ' 分钟前';
    }

    function Panel(props) {
      const t = (props && typeof props.t === 'function') ? props.t : ((key, fallback) => fallback);

      const [info, setInfo] = React.useState(null);
      const [st, setSt] = React.useState(null);
      const [bal, setBal] = React.useState(null);
      const [err, setErr] = React.useState('');
      const [note, setNote] = React.useState('');
      const [pubUrl, setPubUrl] = React.useState('');
      const [code, setCode] = React.useState(null);       // { code, expires_at }
      const [now, setNow] = React.useState(Date.now());
      const [updatedAt, setUpdatedAt] = React.useState(0);

      // 每秒 tick：驱动"n 秒前"与配对码倒计时（呼吸由 CSS 动画负责）
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

      // Esc 关闭浮层（居中弹出后常用的关闭方式）
      React.useEffect(() => {
        if (!open) return undefined;
        const onKey = (e) => { if (e && (e.key === 'Escape' || e.keyCode === 27)) setOpen(false); };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
      }, [open]);

      React.useEffect(() => { if (open) load(); }, [open, load]);
      React.useEffect(() => {
        if (!open) return undefined;
        const timer = setInterval(load, 5000);
        return () => clearInterval(timer);
      }, [open, load]);
      // 配对码过期就地续期（服务端签发，客户端只显示）
      React.useEffect(() => {
        if (!open || !code) return undefined;
        if (code.expires_at && now > code.expires_at) loadCode();
        return undefined;
      }, [open, code, now, loadCode]);
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
      const online = !!(st && st.connections && st.connections.some && st.connections.some((c) => c.online));
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
      // 2026-11-16：弹出层改为**屏幕正中的固定浮层**。
      // 之前是 absolute + bottom:100% 挂在输入区工具条里 —— 会被输入区容器裁掉/盖住
      // （用户："弹出就被遮挡了"）。fixed 定位 + 极高 z-index + 半透明遮罩，保证不被遮挡。
      // 2026-11-16：改为**贴着按钮锚定**的浮层（popover）。
      // 前两版分别居中/靠上，用户反馈"锚点不对"——它应该从按钮旁边弹出来。
      // 做法：open 时测量按钮的 getBoundingClientRect，用视口坐标算位置：
      //   · 按钮在上半屏 → 面板向下弹；在下半屏 → 向上弹（优先空间大的一侧）；
      //   · 水平对齐按钮左缘，并夹紧在视口内（左右各留 8px）；
      //   · 高度不超过可用空间，内容内部滚动 —— 既贴着按钮，又不会被裁/触底。
      const btnRef = React.useRef(null);
      const [anchor, setAnchor] = React.useState(null);
      const [open, setOpen] = React.useState(false);
      const measure = React.useCallback(() => {
        const el = btnRef.current;
        if (!el) return;
        const r = el.getBoundingClientRect();
        setAnchor({
          left: r.left, top: r.top, bottom: r.bottom, right: r.right,
          vw: window.innerWidth || document.documentElement.clientWidth || 1920,
          vh: window.innerHeight || document.documentElement.clientHeight || 1080,
        });
      }, []);

      React.useEffect(() => {
        if (!open) return undefined;
        measure();
        const onWin = () => measure();
        window.addEventListener('resize', onWin);
        window.addEventListener('scroll', onWin, true);
        return () => {
          window.removeEventListener('resize', onWin);
          window.removeEventListener('scroll', onWin, true);
        };
      }, [open, measure]);

      // 点击浮层/按钮之外关闭
      React.useEffect(() => {
        if (!open) return undefined;
        const onDoc = (e) => {
          const t = e && e.target;
          if (!t) return;
          if (btnRef.current && btnRef.current.contains(t)) return;
          const panel = document.getElementById('smartquiz-remote-panel');
          if (panel && panel.contains(t)) return;
          setOpen(false);
        };
        document.addEventListener('mousedown', onDoc, true);
        return () => document.removeEventListener('mousedown', onDoc, true);
      }, [open]);

      const WIDTH = 460;
      const panelRef = React.useRef(null);
      // 自适应校正：DSH 输入区容器可能带 transform，此时 position:fixed 的包含块变成该容器，
      // 用视口坐标(left/top)会整体偏移（用户说的"锚点不对"）。这里渲染后量一次实际位置，
      // 把差值作为 transform 补上 —— 无论包含块是谁，最终都落在按钮旁边。
      const [fix, setFix] = React.useState({ dx: 0, dy: 0 });
      // 2026-11-16（用户明确要求）：面板**贴视口顶部、水平居中、永远在最上层**。
      // 不再相对按钮锚定 —— 输入区容器带 transform 时定位会被带偏（就是"锚点不对"）。
      // 位置用视口坐标给出，渲染后再用 transform 自校正一次，保证无论包含块是谁都贴在顶部。
      const layout = (() => {
        const a = anchor || { vw: (typeof window !== 'undefined' ? window.innerWidth : 1920),
          vh: (typeof window !== 'undefined' ? window.innerHeight : 1080) };
        const margin = 10;
        const width = Math.min(WIDTH, a.vw - margin * 2);
        const left = Math.max(margin, Math.round((a.vw - width) / 2));
        const top = margin;
        return {
          style: {
            position: 'fixed', left, top, width,
            maxHeight: Math.max(200, a.vh - margin * 2), overflowY: 'auto',
            zIndex: 2147483647,
            padding: '14px 16px', borderRadius: 12, fontSize: 12, lineHeight: 1.6,
            background: 'Canvas', color: 'CanvasText', border: '1px solid currentColor',
            boxShadow: '0 10px 40px color-mix(in srgb, CanvasText 30%, transparent)',
            transform: 'translate(' + fix.dx + 'px,' + fix.dy + 'px)',
          },
          target: { left, top },
        };
      })();
      const panelBox = layout.style;
      // 渲染后校正一次（只在偏差 >1px 时 setState，避免循环）
      React.useLayoutEffect(() => {
        if (!open || !layout.target) return;
        const el = panelRef.current;
        if (!el) return;
        const r = el.getBoundingClientRect();
        const dx = layout.target.left - r.left;
        const dy = ('top' in layout.target) ? (layout.target.top - r.top) : (layout.target.bottom - r.bottom);
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1) {
          setFix({ dx: fix.dx + dx, dy: fix.dy + dy });
        }
      }, [open, anchor, layout.target && layout.target.left, layout.target && layout.target.top,
        layout.target && layout.target.bottom, fix.dx, fix.dy]);

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
        // 呼吸用的 keyframes（随组件挂载/卸载，不会污染全局）
        h('style', null, PULSE_STYLE),
        h('button', { ref: btnRef, style: chipStyle, onClick: () => setOpen((v) => !v), title: t('panel.title', '手机远程接入（答题宝）') },
          statusDot, ' 📱 ' + t('panel.title', '手机远程') + (open ? ' ▲' : ' ▼')),

        // 遮罩 + 居中浮层（点遮罩或按 Esc 关闭）
        open && h('div', { id: 'smartquiz-remote-panel', ref: panelRef, style: panelBox },
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 } },
            h('b', { style: { fontSize: 13 } }, '📱 ' + t('panel.title', '手机远程')),
            h('span', { style: Object.assign({}, label, { flex: 1 }) },
              online ? t('panel.connected', '在线') : t('panel.disconnected', '未连接')),
            h('button', {
              style: smallBtn,
              title: t('panel.close', '关闭'),
              onClick: () => setOpen(false),
            }, '✕')),
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
              h('span', null, t('panel.execAllowed', '允许手机在电脑上执行命令'))),
          ),

          // ---------- 客户端（手机） ----------
          h('div', { style: section },
            h('div', { style: { fontWeight: 600 } }, '📱 ' + t('panel.clientTitle', '客户端 · 手机'),
              h('span', { style: label }, '  ' + (online ? t('panel.connected', '在线') : t('panel.disconnected', '未连接'))
                + ' · ' + t('panel.devices', '已配对') + ' ' + devices + ' 台')),
            recommended && h('div', { style: Object.assign({}, row, { marginTop: 8 }) },
              h('img', {
                src: '/pair/qr.svg?text=' + encodeURIComponent(recommended.qr_text || '') + '&cell=4',
                alt: 'pairing qr',
                width: 132, height: 132,
                style: { imageRendering: 'pixelated', borderRadius: 6, background: '#fff' },
              }),
              h('div', { style: { flex: 1 } },
                h('div', { style: label }, t('panel.qrHint', '手机扫码即可配对（二维码由服务端生成）')),
                h('div', { style: Object.assign({}, mono, { marginTop: 6 }) },
                  String(recommended.qr_text || '').replace(/token=.*/, 'token=***')),
                h('div', { style: row },
                  h('button', { style: smallBtn, onClick: () => { if (!copyText(recommended.qr_text || '')) setNote(t('panel.copyFailed', '复制失败')); } },
                    t('panel.copyPair', '复制配对串')),
                  h('button', { style: smallBtn, onClick: () => { if (!copyText(recommended.base_url || '')) setNote(t('panel.copyFailed', '复制失败')); } },
                    t('panel.copy', '复制'))))),
            // 动态配对码（30s 轮换，服务端签发）
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
              t('panel.codeHint', '配对码由电脑签发，5 分钟内一次性有效；在手机「扫码 / 口令配对」里输入即可（后端用 CSPRNG 生成）。')),
          ),

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
              t('panel.tunnelHint', '只有真正的公网/隧道地址才填这里；Tailscale 这类组网地址会被自动识别成「组网（VPN）」，不用填。')),
          ),

          // ---------- 余额 / 提示 ----------
          bal && h('div', { style: Object.assign({}, row, { marginTop: 8 }) },
            h('span', { style: label }, t('panel.balance', 'DeepSeek 余额：')),
            bal.ok ? h('b', null, (bal.total_balance || '?') + ' ' + (bal.currency || '')) : h('span', { style: label }, '—')),
          note && h('div', { style: label }, '✓ ' + note),
          err && h('div', { style: label }, '✗ ' + err),
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
        // 2026-11-16：换到 conversation.input.activity —— 用户反馈"电脑上没看到"，
        // 根因是 composer.dock 在本机 DSH 不存在（同目录 progress 插件用 input.activity 可见）。
        ctx.slots.inject('conversation.input.activity', () => ctx.slots.register({
          name: 'conversation.input.activity',
          id: 'smartquiz-remote',
          order: 6,
        }, (slotProps) => h(Panel, Object.assign({}, slotProps, { t }))));
      },
    };
  },
});
