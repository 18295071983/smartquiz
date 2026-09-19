/* =====================================================================
   极光时钟 v5 · js/app.js —— 状态 / 全局应用 / 导航 / 沉浸 / 方向 / 弹层 / 主循环 / 初始化
   加载顺序依赖：core → audio → fx → faces/registry → faces/* → alarm → timer → world → settings → app
   ===================================================================== */
/* ---------- ⑤ 状态与全局应用 ---------- */
var S = (function () {
  var def = {
    theme: 'aurora', face: 'flip', fx: 'stars', h24: true, showSec: true, meta: true, smooth: true, glow: true, bright: 100,
    tick: false, chime: false, click: true, vib: true, chimeTone: 'ding', clickTone: 'soft', tickTone: 'elec', vol: 70, keep: true, fore: false, orient: 'auto',
    alarms: [], cities: ['Asia/Shanghai', 'America/New_York', 'Europe/London', 'Asia/Tokyo'], beds: [], quote: 0,
    tts: false, sysBright: false
  };
  var o = store('state') || {}; Object.keys(def).forEach(function (k) { if (o[k] === undefined) o[k] = def[k]; });
  return o;
})();
function saveState() { store('state', S); }
function themeOf(k) { for (var i = 0; i < THEMES.length; i++) if (THEMES[i].k === k) return THEMES[i]; return THEMES[0]; }
function faceOf(k) { for (var i = 0; i < FACES.length; i++) if (FACES[i].k === k) return FACES[i]; return FACES[0]; }

function isLightBg(hex) {
  try {
    var h = String(hex || '').replace('#', ''); if (h.length < 6) h = h + h + h + h;
    var r = parseInt(h.slice(0, 2), 16), g = parseInt(h.slice(2, 4), 16), bl = parseInt(h.slice(4, 6), 16);
    return (0.299 * r + 0.587 * g + 0.114 * bl) > 150;
  } catch (e) { return false; }
}
function applyTheme(k, silent) {
  S.theme = k; document.documentElement.setAttribute('data-theme', k); saveState();
  var t = themeOf(k), n = $('#noteTheme'); if (n) n.textContent = t.n;
  $$('#themeGrid i').forEach(function (e) { e.classList.toggle('on', e.getAttribute('data-k') === k); });
  var mt = document.querySelector('meta[name=theme-color]'); if (mt) mt.setAttribute('content', t.bg);
  /* 壳 v8.1：仅设置状态栏图标明暗跟随主题背景亮度（状态栏背景保持壳默认透明，避免全屏黑条） */
  try { var b2 = B(); if (b2 && b2.setStatusBarStyle) { var dark = isLightBg(t.bg); b2.setStatusBarStyle(dark ? 'dark' : 'light'); } } catch (e) { }
  if (!silent) { var m = $('#mask'); if (m) { m.style.opacity = '.55'; setTimeout(function () { m.style.opacity = ''; }, 140); } }
  if (S.click && !silent) AU.swipe();
}
function applyFace(k, silent) {
  S.face = k; document.documentElement.setAttribute('data-face', k); saveState();
  FACE.build(k); var f = faceOf(k), n = $('#noteFace'); if (n) n.textContent = f.n;
  $$('#faceGrid .opt').forEach(function (e) { e.classList.toggle('on', e.getAttribute('data-k') === k); });
  $$('#faceTabs .ftab').forEach(function (e) { e.classList.toggle('on', e.getAttribute('data-k') === k); });
  if (!silent && S.click) AU.swipe();
}
function applyFx(k, silent) {
  S.fx = k; document.documentElement.setAttribute('data-fx', k); saveState(); FX.setMode(k);
  var f = $('#noteFx'); if (f) { for (var i = 0; i < FXS.length; i++) if (FXS[i].k === k) f.textContent = FXS[i].n; }
  $$('#fxGrid .opt').forEach(function (e) { e.classList.toggle('on', e.getAttribute('data-k') === k); });
  if (!silent && S.click) AU.swipe();
}
function applyGlow() { document.body.classList.toggle('noglow', !S.glow); }
function applyBright() {
  var m = $('#mask'); if (m) m.style.opacity = ((100 - S.bright) / 100 * 0.82).toFixed(3);
  var v = $('#vBright'); if (v) v.textContent = S.bright + '%';
  if (S.sysBright) { try { var b = B(); if (b && b.setBrightness) b.setBrightness(Math.round(S.bright / 100 * 255)); } catch (e) { } }
}
function applyKeep() { try { var b = B(); if (b && b.setKeepScreenOn) b.setKeepScreenOn(!!S.keep); } catch (e) { } }
function applyFore() {
  try {
    var b = B(); if (!b) return;
    if (S.fore && b.startForeground) b.startForeground('极光时钟运行中', '闹钟与声景保持后台运行');
    else if (b.cancelAllNotifications) { /* 前台服务无法直接停，仅提示 */ }
  } catch (e) { }
}

/* ---------- 导航 / 沉浸 / 方向 ---------- */
function go(pg) {
  $$('.page').forEach(function (p) { p.classList.toggle('on', p.id === 'pg-' + pg); });
  $$('.tab').forEach(function (t) { t.classList.toggle('on', t.getAttribute('data-pg') === pg); });
  if (pg === 'world') renderWorld();
  if (S.click) AU.click(); haptic();
}
function syncVV() {
  var rs = document.documentElement.style;
  rs.setProperty("--cw", window.innerWidth + "px");
  rs.setProperty("--ch", window.innerHeight + "px");
  syncSafe(); FACE.relayout();
}
function readSA() {
  var p = document.getElementById("__saProbe");
  if (!p) {
    p = document.createElement("div"); p.id = "__saProbe";
    p.style.cssText = "position:fixed;top:0;left:0;width:0;height:0;visibility:hidden;pointer-events:none;"
      + "padding-top:env(safe-area-inset-top,0px);padding-right:env(safe-area-inset-right,0px);"
      + "padding-bottom:env(safe-area-inset-bottom,0px);padding-left:env(safe-area-inset-left,0px);";
    document.body.appendChild(p);
  }
  var cs = getComputedStyle(p);
  return { t: parseFloat(cs.paddingTop) || 0, r: parseFloat(cs.paddingRight) || 0, b: parseFloat(cs.paddingBottom) || 0, l: parseFloat(cs.paddingLeft) || 0 };
}
function syncSafe() {
  var dpr = window.devicePixelRatio || 1;
  var t = 0, b = 0, r = 0, l = 0;
  /* 壳 v8.1 桥：返回物理像素，除以 DPR 转 CSS px；桥为权威来源 */
  if (hasBridgeFn('getStatusBarHeight')) {
    try { t = (parseInt(B().getStatusBarHeight(), 10) || 0) / dpr; } catch (e) { }
  }
  if (hasBridgeFn('getNavBarHeight')) {
    try { b = (parseInt(B().getNavBarHeight(), 10) || 0) / dpr; } catch (e) { }
  }
  /* 无桥回退 env(safe-area) */
  if (!t && !b) { var s2 = readSA(); t = s2.t; b = s2.b; r = s2.r; l = s2.l; }
  /* 防御：部分 WebView env() 返回异常大值（物理像素混入），限制到合理范围 */
  if (t > 64) t = 64; if (b > 64) b = 64;
  if (t < 0) t = 0; if (b < 0) b = 0;
  var rs = document.documentElement.style;
  rs.setProperty("--sa-t", t + "px");
  rs.setProperty("--sa-r", r + "px");
  rs.setProperty("--sa-b", b + "px");
  rs.setProperty("--sa-l", l + "px");
}

function B() { return window.AndroidApp || null; }
function hasBridgeFn(n) { var b = B(); return !!(b && typeof b[n] === 'function'); }
var _lastOrient = null;

function toggleImmerse() {
  var on = !document.body.classList.contains('immerse');
  var native = hasBridgeFn('enterFullscreen');
  if (native) { try { B().enterFullscreen(!!on); } catch (e) { } }
  document.body.classList.toggle('immerse', !!on);
  var b = $('#btnImmerse'); if (b) b.classList.toggle('on', !!on);
  setTimeout(function () { FX.resize(); }, 260);
  toast(on ? (native ? '已进入沉浸全屏（点 ⛶ 退出）' : '已进入沉浸模式（点 ⛶ 退出）') : '已退出全屏');
}

function cycleOrient() {
  S.orient = S.orient === 'auto' ? 'landscape' : (S.orient === 'landscape' ? 'portrait' : 'auto');
  applyOrient(true); saveState();
  var sel = $('#oOrient'); if (sel) sel.value = S.orient;
  toast('屏幕方向：' + (S.orient === 'auto' ? '跟随系统' : S.orient === 'landscape' ? '横屏' : '竖屏'));
}

function applyOrient(notify) {
  var vw = window.innerWidth, vh = window.innerHeight, vp = (vw >= vh) ? 'landscape' : 'portrait';
  var _ang = (window.orientation != null ? window.orientation : ((window.screen && screen.orientation) ? screen.orientation.angle : 0));
  _ang = Math.abs(Number(_ang) || 0);
  var natural = (_ang >= 45) ? ((_ang >= 135) ? 'portrait' : 'landscape') : 'portrait';
  if (_ang < 45 && vp === 'landscape') natural = 'landscape';
  var want = (S.orient === 'auto') ? natural : S.orient;

  /* 页面只做响应式布局切换（body.landscape），不再旋转 DOM —— 旧伪横屏方案已删除 */
  document.body.classList.toggle('landscape', want === 'landscape');

  /* 壳 v8 真方向桥：交系统转屏（推荐，全屏/方向/亮度均为原生接管） */
  if (hasBridgeFn('setOrientation')) {
    if (_lastOrient !== S.orient) {
      _lastOrient = S.orient;
      try { B().setOrientation(S.orient === 'auto' ? 'auto' : S.orient); } catch (e) { }
    }
    syncVV();
    setTimeout(function () { FX.resize(); }, 320);
    setTimeout(function () { FX.resize(); }, 1000);
    if (notify) toast(want === 'landscape' ? '横屏（系统转屏）' : '竖屏（系统转屏）');
    return;
  }

  /* 无桥环境：跟随系统方向，页面自适应横竖屏（不旋转、不锁定） */
  syncVV();
  setTimeout(function () { FX.resize(); }, 180);
  if (notify) toast(want === 'landscape' ? '横屏布局' : '竖屏布局');
}

/* ③ 启动时与壳真实沉浸态对齐 */
(function () {
  if (!hasBridgeFn('isFullscreen')) return;
  try {
    if (B().isFullscreen() && !document.body.classList.contains('immerse')) {
      document.body.classList.add('immerse');
      var b = $('#btnImmerse'); if (b) b.classList.add('on');
      setTimeout(function () { FX.resize(); }, 260);
    }
  } catch (e) { }
})();

/* ---------- 底部弹层 ---------- */
var SHEET = { open: false, onClose: null };
function openSheet(title, body, onMount) {
  $('#sheetTitle').textContent = title; var bd = $('#sheetBody'); bd.innerHTML = '';
  if (typeof body === 'string') bd.innerHTML = body; else if (body) bd.appendChild(body);
  $('#sheetMask').classList.add('on'); $('#sheet').classList.add('on'); SHEET.open = true;
  if (onMount) onMount(bd);
}
function closeSheet() {
  $('#sheetMask').classList.remove('on'); $('#sheet').classList.remove('on'); SHEET.open = false;
  if (SHEET.onClose) { var f = SHEET.onClose; SHEET.onClose = null; f(); }
}

/* ---------- 顶栏状态 ---------- */
function updateChips() {
  try {
    var b = B(); if (b && b.getBatteryLevel) {
      var lv = b.getBatteryLevel(); var ch = (b.isCharging && b.isCharging() === 'true');
      var c = $('#chipBat'); if (c) { c.textContent = (ch ? '⚡' : '🔋') + ' ' + lv + '%'; c.classList.toggle('hot', ch); }
    }
  } catch (e) { }
  var nxt = nextAlarm(), c2 = $('#chipAlarm');
  if (c2) {
    if (nxt) { c2.textContent = '⏰ ' + pad(nxt.h) + ':' + pad(nxt.m); c2.classList.add('hot'); }
    else { c2.textContent = '无闹钟'; c2.classList.remove('hot'); }
  }
}

/* ---------- 主循环 ---------- */
var lastSec = -1, lastMin = -1, lastChip = 0;
function onSecond(now) {
  if (S.tick) AU.tick();
  if (S.chime && now.getMinutes() === 0 && now.getSeconds() === 0) {
    AU.chime(S.chimeTone, now.getHours());
    if (S.tts) {
      try {
        var _b = B();
        if (_b && _b.speakText) {
          var _r = _b.speakText('现在是' + now.getHours() + '点整');
          var _rs = (_r === undefined || _r === null) ? '' : String(_r);
          /* 新壳返回 JSON 状态：非 ok 视为 TTS 失败 → 钟声 + 文本兜底；老壳返回 undefined → 视为成功保持原行为 */
          if (_rs !== '' && _rs.indexOf('"ok"') < 0) { AU.chime(S.chimeTone, now.getHours()); toast('现在是' + now.getHours() + '点整'); }
        }
        else { AU.chime(S.chimeTone, now.getHours()); toast('现在是' + now.getHours() + '点整'); }
      } catch (e) { AU.chime(S.chimeTone, now.getHours()); toast('现在是' + now.getHours() + '点整'); }
    }
  }
  checkAlarms();
  var pg = $('#pg-world'); if (pg && pg.classList.contains('on')) renderWorld();
  if (Date.now() - lastChip > 15000) { lastChip = Date.now(); updateChips(); }
}
function loop() {
  var now = new Date();
  FACE.paint(now);
  var s = now.getSeconds();
  if (s !== lastSec) { lastSec = s; onSecond(now); }
  if (SW.run) swPaint();
  if (CD.run) { CD.left = Math.max(0, CD.end - Date.now()); cdPaint(); if (CD.left <= 0) cdFinish(); }
  if (PM.run) { PM.left = Math.max(0, PM.end - Date.now()); pmPaint(); if (PM.left <= 0) pmFinish(); }
  requestAnimationFrame(loop);
}

/* ---------- 元信息 ---------- */
function renderMeta() {
  var d = new Date(), W = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
  var de = $('#cmDate'), ge = $('#cmGreet');
  if (de) de.textContent = (d.getMonth() + 1) + '月' + d.getDate() + '日 ' + W[d.getDay()];
  if (ge) {
    var h = d.getHours(), g = '';
    if (h < 5) g = '夜深了，早点休息';
    else if (h < 7) g = '清晨好，新的一天开始啦';
    else if (h < 9) g = '早上好，今天也要加油';
    else if (h < 11) g = '上午好，状态在线';
    else if (h < 13) g = '中午好，记得吃午饭';
    else if (h < 14) g = '午后好，歇一会儿';
    else if (h < 17) g = '下午好，继续加油';
    else if (h < 19) g = '傍晚好，今天辛苦了';
    else if (h < 23) g = '晚上好，放松一下';
    else g = '夜深了，早点休息';
    ge.textContent = g;
  }
}

/* ---------- 操作逻辑：滑动切换表盘；双击切换 设计界面 / 全屏沉浸 ---------- */
function nextFace() {
  var list = FACES; if (!list || !list.length) return;
  var cur = FACE.cur(), idx = 0;
  list.forEach(function (f, i) { if (f.k === cur) idx = i; });
  applyFace(list[(idx + 1) % list.length].k);
}
function prevFace() {
  var list = FACES; if (!list || !list.length) return;
  var cur = FACE.cur(), idx = 0;
  list.forEach(function (f, i) { if (f.k === cur) idx = i; });
  applyFace(list[(idx - 1 + list.length) % list.length].k);
}
function onDbl() { toggleImmerse(); }
/* 滑动切换表盘（弹层打开时不响应） */
var _swipe = { x: null, y: null, t: 0 };
document.addEventListener('touchstart', function (e) {
  if (e.touches.length !== 1 || document.querySelector('.sheet.on')) return;
  _swipe.x = e.touches[0].clientX; _swipe.y = e.touches[0].clientY; _swipe.t = Date.now();
}, { passive: true });
document.addEventListener('touchend', function (e) {
  if (_swipe.x === null) return;
  var dx = e.changedTouches[0].clientX - _swipe.x;
  var dy = e.changedTouches[0].clientY - _swipe.y;
  var dt = Date.now() - _swipe.t;
  _swipe.x = null; _swipe.y = null;
  if (dt > 800 || Math.abs(dx) < 60 || Math.abs(dy) > Math.abs(dx) * 1.15) return;
  if (dx < 0) nextFace(); else prevFace();
}, { passive: true });

/* ---------- 初始化 ---------- */
function init() {
  syncVV();
  refreshFaces();
  FX.start();
  document.documentElement.setAttribute('data-theme', S.theme);
  document.documentElement.setAttribute('data-face', S.face);
  document.documentElement.setAttribute('data-fx', S.fx);
  FACE.build(S.face);
  buildThemeGrid(); buildFaceGrid(); buildFxGrid(); buildSoundGrid(); buildChimeSelect(); buildClickToneSelect(); buildTickToneSelect();
  applyTheme(S.theme); applyFace(S.face, true); applyFx(S.fx, true);
  applyGlow(); applyBright(); applyKeep(); applyOrient(false);
  var cm = document.querySelector('.cmeta'); if (cm) cm.style.display = S.meta ? '' : 'none';
  $('#quick .qbtn[data-act=tick]').classList.toggle('on', S.tick);
  $('#quick .qbtn[data-act=chime]').classList.toggle('on', S.chime);
  window.addEventListener('resize', function () { applyOrient(false); });
  window.addEventListener('orientationchange', function () { setTimeout(function () { applyOrient(false); }, 320); });
  document.addEventListener('gesturestart', function (e) { e.preventDefault(); });
  document.addEventListener('dblclick', onDbl);
  var q = $('#cmTip'); if (q) q.textContent = QUOTES[(new Date().getDate()) % QUOTES.length];
  renderMeta(); setInterval(renderMeta, 20000);
  bindTop(); bindSettings(); bindTimers(); bindAlarm(); bindSystem();
  renderAlarms(); renderWorld(); updateChips();
  cdPaint(); pmPaint(); swPaint();
  requestAnimationFrame(loop);
  try { var b = B(); if (b && b.toast) b.toast('极光时钟已就绪'); } catch (e) { }
}
init();
