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
      const [sessions, setSessions] = React.useState([]);
      const [devList, setDevList] = React.useState([]);
      const [approvalRequired, setApprovalRequired] = React.useState(false);
      const [bal, setBal] = React.useState(null);
      const [err, setErr] = React.useState('');
      const [note, setNote] = React.useState('');
      const [pubUrl, setPubUrl] = React.useState('');
      const [code, setCode] = React.useState(null);   // { code, expires_at }
      const [now, setNow] = React.useState(Date.now());
      const [updatedAt, setUpdatedAt] = React.useState(0);
      // 2026-11-18：phone_ui / 插槽面板 —— 电脑端也能看到手机插槽并推送/移除
      const [slots, setSlots] = React.useState([]);
      const [conns, setConns] = React.useState([]);
      const [formKind, setFormKind] = React.useState('slot');
      const [formId, setFormId] = React.useState('phone_ui');
      const [formTitle, setFormTitle] = React.useState('');
      const [formDetail, setFormDetail] = React.useState('');
      const [formLevel, setFormLevel] = React.useState('p4');
      const [uiNote, setUiNote] = React.useState('');
      // 当前会话 id：优先用宿主 slot 给的，拿不到就按全局插槽查
      const sessionId = String(
        (props && (props.sessionId || props.session_id
          || (props.session && props.session.id) || props.id)) || '').trim();
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

      // 最近会话（2026-11-17）：电脑面板也能归档会话（/v2/session action=archive）
      const loadSessions = React.useCallback(() => {
        fetch('/sessions.json?limit=6', { cache: 'no-store' })
          .then((r) => (r.ok ? r.json() : null))
          .then((j) => setSessions(j && Array.isArray(j.sessions) ? j.sessions : []))
          .catch(() => setSessions([]));
      }, []);

      // 已配对设备 / 审批（2026-11-17）：面板里批准、吊销、切换"新设备需审批"
      const loadDevices = React.useCallback(() => {
        fetch('/devices', { cache: 'no-store' })
          .then((r) => (r.ok ? r.json() : null))
          .then((j) => {
            if (!j) return;
            setDevList(Array.isArray(j.devices) ? j.devices : []);
            setApprovalRequired(j.approval_required === true);
          })
          .catch(() => setDevList([]));
      }, []);
      const approveDevice = (id) => {
        fetch('/devices/approve', {
          method: 'POST', headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ device_id: id }),
        }).then((r) => (r.ok ? r.json() : null)).then((j) => {
          setNote(j && j.ok ? t('panel.approved', '已批准：这台手机现在可以连接') : t('panel.approveFailed', '批准失败'));
          loadDevices();
        }).catch(() => setNote(t('panel.approveFailed', '批准失败')));
      };
      const revokeDevice = (id) => {
        fetch('/devices/revoke', {
          method: 'POST', headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ device_id: id }),
        }).then((r) => (r.ok ? r.json() : null)).then((j) => {
          setNote(j && j.ok ? t('panel.revoked', '已吊销该设备令牌') : t('panel.revokeFailed', '吊销失败'));
          loadDevices();
        }).catch(() => setNote(t('panel.revokeFailed', '吊销失败')));
      };
      const toggleApproval = (required) => {
        fetch('/devices/approval', {
          method: 'POST', headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ required }),
        }).then((r) => (r.ok ? r.json() : null)).then((j) => {
          setApprovalRequired(Boolean(j && j.required));
          setNote(j && j.ok
            ? (j.required ? t('panel.approvalOn', '已开启：新设备配对后需在此批准') : t('panel.approvalOff', '已关闭：配对后直接可用'))
            : t('panel.saveFailed', '设置失败'));
        }).catch(() => setNote(t('panel.saveFailed', '设置失败')));
      };

      // 2026-11-18：手机插槽 / 连接状态（面板内实时刷新）
      const loadSlots = React.useCallback(() => {
        const q = sessionId ? ('?session_id=' + encodeURIComponent(sessionId)) : '';
        fetch('/v2/slots' + q, { cache: 'no-store' })
          .then((r) => (r.ok ? r.json() : null))
          .then((j) => setSlots(j && Array.isArray(j.slots) ? j.slots : []))
          .catch(() => setSlots([]));
      }, [sessionId]);
      const loadConns = React.useCallback(() => {
        fetch('/v2/pulse', { cache: 'no-store' })
          .then((r) => (r.ok ? r.json() : null))
          .then((j) => setConns(j && Array.isArray(j.connections) ? j.connections : []))
          .catch(() => setConns([]));
      }, []);

      /** 推送到手机 / 移除插槽：slot 走 ui.slot，toast/notification 走 ui.notify */
      const pushPhoneUi = (remove, onlyId) => {
        const kind = String(formKind || 'slot');
        const id = String(onlyId || formId || 'phone_ui').trim();
        const title = remove ? '' : String(formTitle || '').trim();
        const detail = remove ? '' : String(formDetail || '').trim();
        if (!remove && !title && !detail) {
          setUiNote(t('panel.slotNeedText', '标题或正文至少填一个'));
          return;
        }
        const body = kind === 'slot'
          ? { action: 'ui.slot', id, title, detail, level: formLevel,
            ...(sessionId ? { session_id: sessionId } : {}) }
          : { action: 'ui.notify', kind, title: title || detail, detail,
            ...(sessionId ? { session_id: sessionId } : {}) };
        setUiNote(t('panel.sending', '发送中…'));
        fetch('/v2/action', {
          method: 'POST', headers: { 'content-type': 'application/json' },
          body: JSON.stringify(body),
        }).then((r) => r.json().then((j) => ({ ok: r.ok, j }))).then(({ ok, j }) => {
          if (ok && j && j.ok) {
            setUiNote(remove ? t('panel.slotRemoved', '已移除') : t('panel.slotPushed', '已推送到手机'));
            if (!remove) { setFormTitle(''); setFormDetail(''); }
            loadSlots();
          } else {
            setUiNote((j && j.error) ? String(j.error) : t('panel.slotFailed', '推送失败'));
          }
        }).catch((e) => setUiNote(t('panel.slotFailed', '推送失败') + ': '
          + String(e && e.message ? e.message : e)));
      };

      // 2026-11-16：配对码本地缓存 —— 关闭面板再打开、甚至刷新页面，
      // 只要原码还没过期就继续显示同一个码（倒计时接着走），不再"每次点开都是 5:00"。
      // 服务端也改成复用未过期的码（/pair/code 返回 reused:true + 真实剩余毫秒），两边一致。
      const CODE_CACHE_KEY = 'smartquiz.pairCode';
      const readCodeCache = () => {
        try {
          const raw = window.localStorage.getItem(CODE_CACHE_KEY);
          if (!raw) return null;
          const v = JSON.parse(raw);
          if (!v || !v.code || !v.expires_at) return null;
          if (v.expires_at - Date.now() <= 3000) return null;   // 快过期就别用了
          return v;
        } catch { return null; }
      };
      const writeCodeCache = (v) => {
        try { window.localStorage.setItem(CODE_CACHE_KEY, JSON.stringify(v)); } catch { /* 隐私模式忽略 */ }
      };
      const loadCode = React.useCallback((force) => {
        if (!force) {
          const cached = readCodeCache();
          if (cached) {
            setCode({ code: cached.code, ttl_ms: cached.ttl_ms || (cached.expires_at - Date.now()), expires_at: cached.expires_at, reused: true });
            return;
          }
        }
        fetch('/pair/code', { cache: 'no-store' })
          .then((r) => (r.ok ? r.json() : null))
          // 现有端点契约：{ ok, code, expires_in_ms, reused }（5 分钟一次性码，CSPRNG 生成）
          .then((j) => {
            if (!j || !j.ok) return;
            const ttl = Number(j.expires_in_ms) || 300000;
            const v = { code: j.code, ttl_ms: ttl, expires_at: Date.now() + ttl, reused: j.reused === true };
            setCode(v);
            writeCodeCache(v);
          })
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
        loadSessions();
        loadDevices();
      }, [loadBalance, loadSessions, loadDevices]);

      /** 归档一条电脑会话：不删记录，只从两端列表隐藏（电脑端可恢复） */
      const archiveSession = (sid) => {
        fetch('/v2/session', {
          method: 'POST',
          headers: { 'content-type': 'application/json' },
          body: JSON.stringify({ action: 'archive', session_id: sid }),
        }).then((r) => (r.ok ? r.json() : null))
          .then((j) => {
            setNote(j && j.ok
              ? t('panel.archived', '已归档（可在电脑端恢复）')
              : t('panel.archiveFailed', '归档失败') + (j && j.error ? ': ' + j.error : ''));
            loadSessions();
            load();
          })
          .catch((e) => setNote(t('panel.archiveFailed', '归档失败') + ': ' + String(e && e.message ? e.message : e)));
      };

      React.useEffect(() => { if (open) { load(); loadCode(false); } }, [open, load, loadCode]);
      // 插槽 / 连接状态：打开时拉一次，之后每 4 秒刷新（关闭面板就停，不打扰宿主）
      React.useEffect(() => {
        if (!open) return undefined;
        loadSlots();
        loadConns();
        const timer = setInterval(() => { loadSlots(); loadConns(); }, 4000);
        return () => clearInterval(timer);
      }, [open, loadSlots, loadConns]);
      React.useEffect(() => {
        if (!open) return undefined;
        const timer = setInterval(load, 5000);
        return () => clearInterval(timer);
      }, [open, load]);
      // 2026-11-16 修：以前 load() 每 5 秒调一次 loadCode()，等于**每次轮询都签发新码** ——
      // 用户看到"配对码 2 秒就过期"，而且后端最多只保留 5 个待用码，频繁签发会把旧码挤掉。
      // 现在：打开时取一次；只在剩余 ≤3 秒或已过期时自动续期；也可手动点「刷新」。
      React.useEffect(() => {
        if (!open || !code || !code.expires_at) return undefined;
        const left = Math.ceil((code.expires_at - Date.now()) / 1000);
        if (left <= 3) loadCode(true);   // 真过期才换新（并覆盖缓存）
        return undefined;
      }, [open, code, now, loadCode]);
      React.useEffect(() => { if (info) setPubUrl(info.public_url || ''); }, [info]);

      // 可见性处理（2026-11-16）：面板/窗口在后台时定时器被节流，now 会落后真实时间。
      // 回到前台立刻把 now 对齐真实时钟，并（若已过期）续期一次 —— 避免"后台跑出 7 分钟"的错觉。
      React.useEffect(() => {
        if (!open) return undefined;
        const onVis = () => {
          if (document.visibilityState === 'visible') {
            setNow(Date.now());
            if (code && code.expires_at && Date.now() > code.expires_at) loadCode();
          }
        };
        document.addEventListener('visibilitychange', onVis);
        return () => document.removeEventListener('visibilitychange', onVis);
      }, [open, code, loadCode]);
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
      // 2026-11-16 修：倒计时会"跑过 TTL"（用户看到 7 分钟）。
      // 原因：now 是定时器驱动，窗口在后台时 Chromium 会把 setInterval 节流到 1 次/分钟，
      // now 落后于真实时间；若此期间又续签了新码，就会算出 (expires_at - 过期的 now) > TTL。
      // 现在：剩余时间夹紧到 [0, TTL]，并额外显示绝对到期时刻，后台漂移一眼可见。
      const codeTtlSec = code && code.ttl_ms ? Math.round(code.ttl_ms / 1000) : 300;
      const codeLeft = code && code.expires_at
        ? Math.min(codeTtlSec, Math.max(0, Math.ceil((code.expires_at - now) / 1000)))
        : 0;
      const codeExpireClock = code && code.expires_at
        ? new Date(code.expires_at).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
        : '';

      // 2026-11-18：加大点击热区（用户反馈"按钮不好点击、没定位好"）。
      // 统一最小高度 + 足够内边距，行内按钮不再 1px 贴着文字。
      const chipStyle = {
        display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 6,
        fontSize: 12.5, lineHeight: 1.2, minHeight: 28, padding: '5px 12px',
        borderRadius: 999, cursor: 'pointer', touchAction: 'manipulation',
        border: '1px solid currentColor', background: 'transparent', color: 'inherit',
      };
      const smallBtn = Object.assign({}, chipStyle, {
        minHeight: 24, padding: '3px 10px', fontSize: 11.5,
      });
      const fieldStyle = {
        minHeight: 26, padding: '3px 8px', fontSize: 12, borderRadius: 6,
        border: '1px solid currentColor', background: 'transparent', color: 'inherit',
        boxSizing: 'border-box', maxWidth: '100%',
      };
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

      // 呼吸灯 = 真实服务信号（2026-11-17）：只有"有在线连接"且 /status 是最近 12 秒内
      // 真拉回来的，才让它呼吸；数据陈旧（宿主卡死/网络断）就停 —— 不给动画骗人的机会。
      const fresh = updatedAt > 0 && (Date.now() - updatedAt) < 12000;
      const breathing = online && fresh;
      const statusDot = h('span', {
        className: breathing ? 'sqr-breathe' : '',
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
            // 护栏 / 协议（2026-11-17）：与手机「自检」页同一口径，电脑面板直接可见
            (() => {
              const g = (st && st.guard) || null;
              const guardOn = !g || g.enabled !== false;
              return h('div', { style: row },
                h('span', { style: label }, t('panel.guard', '护栏')),
                h('span', guardOn ? {} : { color: '#d9534f' },
                  guardOn ? t('panel.guardOn', 'Host/Origin 已开') : t('panel.guardOff', '已关闭')),
                h('span', { style: label }, '  ·  ' + t('panel.guardRejected', '已拦截') + ' '
                  + ((g && g.rejected) || 0) + (g && g.last ? '（' + g.last + '）' : '')),
                h('span', { style: label }, '  ·  ' + t('panel.protocol', '协议') + ' v2 · '
                  + t('panel.caps', '能力') + ' ' + ((st && st.caps && st.caps.server_count) || '—')));
            })(),
            sessions.length > 0 && h('div', { style: { marginTop: 8 } },
              h('div', { style: label }, t('panel.sessions', '最近会话（点右侧归档）')),
              sessions.slice(0, 5).map((s) => h('div', {
                key: String(s.session_id),
                style: { display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 },
              },
                h('span', {
                  style: { flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: 12 },
                  title: String(s.session_id || ''),
                }, (s.title || s.session_id || '')),
                h('button', { style: smallBtn, onClick: () => archiveSession(s.session_id) },
                  t('panel.archive', '归档'))))),

          // ---------- 插槽 · phone_ui（2026-11-18） ----------
          h('div', { style: section },
            h('div', { style: { fontWeight: 600 } },
              '📌 ' + t('panel.slotTitle', '插槽 · phone_ui'),
              h('span', { style: label }, '  ' + (conns.length > 0
                ? t('panel.slotPhoneOnline', '手机在线') + ' ' + conns.length + ' 台'
                : t('panel.slotPhoneOffline', '手机未连接'))),
              h('span', { style: label }, '  · ' + (slots.length > 0
                ? t('panel.slotCount', '当前') + ' ' + slots.length + ' 条'
                : t('panel.slotNone', '暂无插槽')))),
            h('div', { style: row },
              h('button', { style: smallBtn, onClick: () => { loadSlots(); loadConns(); } },
                t('panel.refresh', '刷新')),
              slots.length === 0
                ? h('span', { style: label }, t('panel.slotEmptyHint', '手机输入区上方还没有插槽'))
                : null),
            slots.slice(0, 8).map((s) => h('div', {
              key: String(s.id),
              style: { display: 'flex', alignItems: 'center', gap: 6, marginTop: 4, minWidth: 0 },
            },
              h('span', {
                style: { flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap', fontSize: 12 },
                title: String(s.id || ''),
              }, (s.level ? '[' + s.level + '] ' : '') + (s.title || s.id)
                + (s.detail ? ' · ' + s.detail : '')),
              h('span', { style: label }, s.ttl_ms > 0
                ? Math.max(1, Math.round(s.ttl_ms / 1000)) + 's'
                : t('panel.slotSticky', '常驻')),
              h('button', { style: smallBtn, onClick: () => pushPhoneUi(true, s.id) },
                t('panel.slotRemove', '移除')))),
            h('div', { style: Object.assign({}, row, { marginTop: 8, flexWrap: 'wrap' }) },
              h('select', {
                style: Object.assign({}, fieldStyle, { minWidth: 92 }),
                value: formKind, onChange: (e) => setFormKind(e.target.value),
              },
                h('option', { value: 'slot' }, 'slot 插槽'),
                h('option', { value: 'toast' }, 'toast 提示'),
                h('option', { value: 'notification' }, 'notification 通知')),
              h('input', {
                style: Object.assign({}, fieldStyle, { width: 96 }),
                value: formId, placeholder: 'id（默认 phone_ui）',
                onChange: (e) => setFormId(e.target.value),
              }),
              h('select', {
                style: Object.assign({}, fieldStyle, { minWidth: 76 }),
                value: formLevel, onChange: (e) => setFormLevel(e.target.value),
              },
                h('option', { value: 'p0' }, 'p0'),
                h('option', { value: 'p1' }, 'p1'),
                h('option', { value: 'p2' }, 'p2'),
                h('option', { value: 'p3' }, 'p3'),
                h('option', { value: 'p4' }, 'p4'),
                h('option', { value: 'p5' }, 'p5'))),
            h('div', { style: row },
              h('input', {
                style: Object.assign({}, fieldStyle, { flex: 1, minWidth: 0 }),
                value: formTitle, placeholder: t('panel.slotTitlePh', '标题'),
                onChange: (e) => setFormTitle(e.target.value),
              })),
            h('div', { style: row },
              h('input', {
                style: Object.assign({}, fieldStyle, { flex: 1, minWidth: 0 }),
                value: formDetail, placeholder: t('panel.slotDetailPh', '正文 / 副标题'),
                onChange: (e) => setFormDetail(e.target.value),
              }),
              h('button', { style: smallBtn, onClick: () => pushPhoneUi(false) },
                t('panel.slotPush', '推送'))),
            uiNote ? h('div', { style: Object.assign({}, label, { marginTop: 4 }) }, uiNote) : null),

          // ---------- 客户端（手机） ----------
          h('div', { style: section },
            h('div', { style: { fontWeight: 600 } }, '📱 ' + t('panel.clientTitle', '客户端 · 手机'),
              h('span', { style: label }, '  ' + (online ? (t('panel.connected', '在线') + (transport ? '（' + transport + '）' : '')) : t('panel.disconnected', '未连接'))
                + ' · ' + t('panel.devices', '已配对') + ' ' + devices + ' 台')),
            // 多张二维码（2026-11-17）：每个可达地址一张，手机挑能通的那张扫
            (() => {
              const items = (info && Array.isArray(info.items) && info.items.length)
                ? info.items
                : (recommended ? [recommended] : []);
              if (!items.length) return null;
              const kindLabel = (k) => (k === 'lan' ? t('panel.kindLan', '局域网')
                : (k === 'mesh' ? t('panel.kindMesh', '组网（VPN）') : t('panel.kindPublic', '公网')));
              return h('div', { style: { marginTop: 8 } },
                h('div', { style: label }, t('panel.qrHint', '手机扫码即可配对（每个可达地址一张码，挑能通的那张）')),
                items.slice(0, 4).map((it, i) => h('div', {
                  key: String(it.base_url || i),
                  style: Object.assign({}, row, { marginTop: 8, alignItems: 'flex-start' }),
                },
                  h('img', {
                    src: '/pair/qr.svg?text=' + encodeURIComponent(it.qr_text || '') + '&cell=4',
                    alt: 'pairing qr',
                    width: 120, height: 120,
                    style: { imageRendering: 'pixelated', borderRadius: 6, background: '#fff' },
                  }),
                  h('div', { style: { flex: 1, minWidth: 0 } },
                    h('div', { style: label }, kindLabel(it.kind) + ' · ' + String(it.base_url || '')),
                    h('div', { style: Object.assign({}, mono, { marginTop: 4, fontSize: 10, wordBreak: 'break-all' }) },
                      String(it.qr_text || '').replace(/token=.*/, 'token=***')),
                    h('div', { style: row },
                      h('button', {
                        style: smallBtn,
                        onClick: () => { if (!copyText(it.qr_text || '')) setNote(t('panel.copyFailed', '复制失败')); },
                      }, t('panel.copyPair', '复制配对串')),
                      h('button', {
                        style: smallBtn,
                        onClick: () => { if (!copyText(it.base_url || '')) setNote(t('panel.copyFailed', '复制失败')); },
                      }, t('panel.copy', '复制')))))));
            })(),
            h('div', { style: row },
              h('span', { style: label }, t('panel.codeTitle', '动态配对码')),
              code
                ? h('b', { style: Object.assign({}, mono, { fontSize: 15, letterSpacing: 2 }) }, code.code)
                : h('span', { style: label }, '—'),
              code ? h('span', { style: label }, ' · ' + (codeLeft > 0
                ? t('panel.codeValid', '有效') + ' ' + fmtLeft(codeLeft) + (codeExpireClock ? ('，至 ' + codeExpireClock) : '')
                : t('panel.codeExpired', '已过期') + (codeExpireClock ? ('（' + codeExpireClock + '）') : ''))) : null,
              h('button', { style: smallBtn, onClick: () => loadCode(true) }, t('panel.refresh', '刷新'))),
            h('div', { style: Object.assign({}, label, { marginTop: 4 }) },
              t('panel.codeHint', '配对码由电脑签发，5 分钟内一次性有效；在手机「扫码 / 口令配对」里输入即可（后端用 CSPRNG 生成）。'))),
            // 连接审批 + 设备令牌吊销（2026-11-17）
            h('div', { style: Object.assign({}, row, { marginTop: 8, cursor: 'pointer' }) },
              h('input', { type: 'checkbox', checked: approvalRequired, onChange: (e) => toggleApproval(e.target.checked) }),
              h('span', null, t('panel.approvalLabel', '新设备配对后需在电脑端批准'))),
            devList.length > 0 && h('div', { style: { marginTop: 6 } },
              h('div', { style: label }, t('panel.devicesTitle', '已配对设备') + '（' + devList.length + '）'),
              devList.slice(0, 6).map((d) => h('div', {
                key: String(d.id),
                style: { display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 },
              },
                h('span', {
                  style: {
                    flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap', fontSize: 12,
                    color: d.approved === false ? '#d9534f' : undefined,
                  },
                  title: String(d.id || ''),
                }, (d.name || d.id) + (d.approved === false ? ' · ' + t('panel.pending', '待批准') : '')),
                (d.approved === false) && h('button', { style: smallBtn, onClick: () => approveDevice(d.id) },
                  t('panel.approve', '批准')),
                h('button', { style: smallBtn, onClick: () => revokeDevice(d.id) },
                  t('panel.revoke', '吊销'))))),

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
