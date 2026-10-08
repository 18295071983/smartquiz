/**
 * Progress Bar · 界面部分（DSH Web UI 插件）
 *
 * 槽位：conversation.input.activity —— 官方说明：
 *   "hosts one control between the model selector and Send. Its onActiveChange
 *    callback expands that control across the toolbar"
 *
 * 数据：轮询宿主侧 /dsh-progress/state（同源，无 CORS）。
 * 行为：
 *   · 有条目在跑 → onActiveChange(true) 展开成整条工具栏宽度，显示标签/百分比/进度条/细节
 *   · 全部结束 → 收起，只留一个极小的完成态提示（几秒后自动消失）
 *   · 我在干活但久未更新 → 显示「N 秒未更新」，你能立刻看出我卡住了
 */
window.__ModuleLoader__.load({
  id: '@local/dsh-progress',
  factory(require) {
    const React = require('react');
    const h = React.createElement;

    const POLL_MS = 500;

    /** 拉一次进度快照 */
    function useProgress() {
      const [tasks, setTasks] = React.useState([]);
      const [err, setErr] = React.useState('');

      React.useEffect(() => {
        let alive = true;
        let timer = null;

        const tick = () => {
          fetch('/dsh-progress/state', { cache: 'no-store' })
            .then((r) => (r.ok ? r.json() : Promise.reject(new Error('HTTP ' + r.status))))
            .then((j) => {
              if (!alive) return;
              setTasks(Array.isArray(j && j.tasks) ? j.tasks : []);
              setErr('');
            })
            .catch((e) => { if (alive) setErr(String(e && e.message ? e.message : e)); })
            .finally(() => { if (alive) timer = setTimeout(tick, POLL_MS); });
        };

        tick();
        return () => { alive = false; if (timer) clearTimeout(timer); };
      }, []);

      return { tasks, err };
    }

    /** 秒 → 人能读的短文本 */
    const secs = (ms) => (ms < 1000 ? '0s' : Math.round(ms / 1000) + 's');

    function Bar({ t }) {
      const pct = t.total > 0 ? Math.min(100, Math.max(0, (t.value / t.total) * 100)) : 0;
      const stalled = t.state === 'running' && t.ageMs > 20000;   // 20 秒没更新 → 提示可能卡住
      const color = t.state === 'error' ? '#e5484d'
        : t.state === 'done' ? '#30a46c'
          : stalled ? '#f5a524' : '#0090ff';

      return h('div', {
        style: { display: 'flex', alignItems: 'center', gap: 8, width: '100%', minWidth: 0 },
        title: t.detail || t.label,
      },
        // 标签 + 计数
        h('span', {
          style: { fontSize: 12, whiteSpace: 'nowrap', overflow: 'hidden',
                   textOverflow: 'ellipsis', maxWidth: 240, opacity: 0.95 },
        }, (t.state === 'done' ? '✓ ' : t.state === 'error' ? '✗ ' : '⏳ ') + t.label),

        // 进度条本体
        h('div', {
          style: { flex: 1, minWidth: 80, height: 6, borderRadius: 999,
                   background: 'currentColor', opacity: 0.18, position: 'relative', overflow: 'hidden' },
        }, h('div', {
          style: { position: 'absolute', inset: 0, width: pct + '%', background: color,
                   borderRadius: 999, transition: 'width 240ms ease' },
        })),

        // 百分比
        h('span', { style: { fontSize: 12, fontVariantNumeric: 'tabular-nums', opacity: 0.9,
                             whiteSpace: 'nowrap' } },
          Math.round(pct) + '%'),

        // 计数 N/M（未给 total 时省略）
        h('span', { style: { fontSize: 11, opacity: 0.6, whiteSpace: 'nowrap' } },
          t.value + '/' + t.total),

        // 细节（有才显示）
        t.detail && h('span', {
          style: { fontSize: 11, opacity: 0.7, whiteSpace: 'nowrap', overflow: 'hidden',
                   textOverflow: 'ellipsis', maxWidth: 320 },
        }, t.detail),

        // 久未更新 → 明确提示（让我被卡住这件事可见）
        stalled && h('span', { style: { fontSize: 11, color: '#f5a524', whiteSpace: 'nowrap' } },
          '· ' + secs(t.ageMs) + ' 未更新'),
      );
    }

    function Progress() {
      const { tasks, err } = useProgress();
      const active = tasks.some((t) => t.state === 'running');

      // 有条目在跑 → 展开；否则收起（不占工具栏）
      if (typeof window !== 'undefined' && window.__dshProgressActive !== active) {
        window.__dshProgressActive = active;
      }

      // 无进度且无错误 → 不占用空间（返回 null 让槽位塌陷）
      if (!tasks.length && !err) return null;

      return h('div', { style: { display: 'flex', flexDirection: 'column', gap: 4, width: '100%' } },
        tasks.map((t) => h(Bar, { key: t.id, t })),
        err && h('div', { style: { fontSize: 11, opacity: 0.6 } }, '进度端点不可达：' + err),
      );
    }

    return {
      inject: ['slots'],
      apply(ctx) {
        ctx.slots.inject('conversation.input.activity', () => ctx.slots.register({
          name: 'conversation.input.activity',
          id: 'dsh-progress',
          order: 5,
        }, Progress));
      },
    };
  },
});
