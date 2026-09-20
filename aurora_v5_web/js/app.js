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
    tts: false, ttsVoice: '', sysBright: false
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
  if (on) { go('clock'); } /* 进入沉浸自动回到时钟页 */
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

/* ---------- 全屏兜底：任何方式进入全屏都切回时钟页+沉浸态 ---------- */
setInterval(function () {
  try {
    var _b = B();
    if (_b && _b.isFullscreen && _b.isFullscreen()) {
      if (!document.body.classList.contains('immerse')) {
        document.body.classList.add('immerse');
        var _ib = $('#btnImmerse'); if (_ib) _ib.classList.add('on');
        setTimeout(function () { FX.resize(); }, 260);
      }
      var _pc = document.getElementById('pg-clock');
      if (_pc && !_pc.classList.contains('on')) go('clock');
    }
  } catch (e) { }
}, 2000);

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
  if (S.chime && (now.getMinutes() === 0 || now.getMinutes() === 30) && now.getSeconds() === 0) {
    var _hm = now.getMinutes();
    AU.chime(S.chimeTone, _hm === 0 ? now.getHours() : 0);
    if (S.tts) {
      if ((S.ttsVoice || '').indexOf('vox:') === 0) { speakVox(now.getHours(), _hm); }
      else {
        try {
          var _b = B();
          if (_b && _b.speakText) { _b.speakText(_hm === 0 ? ('现在是' + now.getHours() + '点整') : ('现在是' + now.getHours() + '点三十分'), S.ttsVoice || null); }
        } catch (e) {}
      }
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
var _swipeBlock = false;
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
  var _pg = document.getElementById('pg-clock');
  if (!_pg || !_pg.classList.contains('on')) return;
  _swipeBlock = true; setTimeout(function () { _swipeBlock = false; }, 350);
  if (dx < 0) nextFace(); else prevFace();
}, { passive: true });

/* ---------- 初始化 ---------- */

/* ---------- 内置真人语音拼接（vox: 音色，edge-tts 录音） ---------- */
function voxSeq(h, m) {
  var s = ['f_now'];
  if (h < 10) s.push('n' + h);
  else if (h < 20) { s.push('f_shi'); if (h > 10) s.push('n' + (h - 10)); }
  else { s.push('n' + (Math.floor(h / 10) * 10)); if (h % 10) s.push('n' + (h % 10)); }
  s.push('f_dian');
  if (m === 0) s.push('f_zheng');
  else if (m < 10) { s.push('n' + m); s.push('f_fen'); }
  else if (m < 20) { s.push('f_shi'); if (m > 10) s.push('n' + (m - 10)); s.push('f_fen'); }
  else if (m === 30) s.push('f_ban');
  else { s.push('n' + (Math.floor(m / 10) * 10)); if (m % 10) s.push('n' + (m % 10)); s.push('f_fen'); }
  return s;
}
function _b64buf(b64) {
  try {
    var bin = atob(b64), len = bin.length, ab = new ArrayBuffer(len), u = new Uint8Array(ab);
    for (var i = 0; i < len; i++) u[i] = bin.charCodeAt(i);
    return ab;
  } catch (e) { return null; }
}
function _trimSilence(audio, thr) {
  try {
    if (!window._voxAC) window._voxAC = new (window.AudioContext || window.webkitAudioContext)();
    var ac = window._voxAC;
    var ch = audio.numberOfChannels, len = audio.length, start = 0, end = len, i, j, c;
    outer: for (i = 0; i < len; i++) { for (c = 0; c < ch; c++) { if (Math.abs(audio.getChannelData(c)[i]) > thr) { start = i; break outer; } } }
    outer2: for (j = len - 1; j >= 0; j--) { for (c = 0; c < ch; c++) { if (Math.abs(audio.getChannelData(c)[j]) > thr) { end = j + 1; break outer2; } } }
    if (start >= end) return audio;
    var nl = end - start, out = ac.createBuffer(ch, nl, audio.sampleRate);
    for (c = 0; c < ch; c++) { out.getChannelData(c).set(audio.getChannelData(c).subarray(start, end)); }
    return out;
  } catch (e) { return audio; }
}
function playVox(seq, voiceKey) {
  try {
    if (!window._voxAC) window._voxAC = new (window.AudioContext || window.webkitAudioContext)();
    var ctx = window._voxAC;
    if (ctx.state === 'suspended') ctx.resume();
    var data = VOX_DATA && VOX_DATA[voiceKey]; if (!data) return;
    if (!window._voxCache) window._voxCache = {};
    var jobs = seq.map(function (k) {
      return new Promise(function (res) {
        var b64 = data[k]; if (!b64) { res(null); return; }
        var ck = voiceKey + '_' + k;
        if (window._voxCache[ck]) { res(window._voxCache[ck]); return; }
        var buf = _b64buf(b64); if (!buf) { res(null); return; }
        ctx.decodeAudioData(buf, function (audio) {
          var trimmed = _trimSilence(audio, 0.008);
          window._voxCache[ck] = trimmed; res(trimmed);
        }, function () { res(null); });
      });
    });
    Promise.all(jobs).then(function (list) {
      var t = ctx.currentTime + 0.04;
      list.forEach(function (audio) {
        if (!audio) return;
        var src = ctx.createBufferSource(); src.buffer = audio; src.connect(ctx.destination);
        src.start(t); t += audio.duration + 0.025;
      });
    });
  } catch (e) {}
}
function preloadVox() {
  try {
    if (!window.VOX_DATA) return;
    var keys = Object.keys(VOX_DATA);
    var vk = (S.ttsVoice || '').indexOf('vox:') === 0 ? S.ttsVoice.slice(4) : (keys[0] || '');
    if (!vk || !VOX_DATA[vk]) return;
    if (!window._voxAC) window._voxAC = new (window.AudioContext || window.webkitAudioContext)();
    var ctx = window._voxAC;
    if (!window._voxCache) window._voxCache = {};
    var data = VOX_DATA[vk];
    Object.keys(data).forEach(function (k) {
      var ck = vk + '_' + k;
      if (window._voxCache[ck]) return;
      var buf = _b64buf(data[k]); if (!buf) return;
      ctx.decodeAudioData(buf, function (audio) { window._voxCache[ck] = _trimSilence(audio, 0.008); }, function () {});
    });
  } catch (e) {}
}
function speakVox(h, m) { playVox(voxSeq(h, m), (S.ttsVoice || '').slice(4)); }

/* 点击报时：单击表盘播报当前时间（双击判定延迟，避免与沉浸切换冲突） */
var _tapLast = 0, _tapTimer = null, _tapDbl = false;
function speakNow() {
  var d = new Date(), h = d.getHours(), m = d.getMinutes();
  var txt = '现在是' + h + '点' + (m === 0 ? '整' : (m === 30 ? '三十分' : (m + '分')));
  AU.chime(S.chimeTone, m === 0 ? h : 0);
  toast('⏰ ' + txt);
  haptic(16);
  if ((S.ttsVoice || '').indexOf('vox:') === 0) { speakVox(h, m); }
  else { try { var b = B(); if (b && b.speakText) b.speakText(txt, S.ttsVoice || null); } catch (e) {} }
}
(function () {
  var host = document.getElementById('faceHost');
  if (!host) return;
  host.addEventListener('click', function (e) {
    if (_swipeBlock) { _swipeBlock = false; return; }
    var t = e.target;
    if (t.closest && t.closest('button,input,select,textarea,.tab,.qbtn,.chip,.swi,.ftab')) return;
    var now2 = Date.now();
    if (now2 - _tapLast < 300) { clearTimeout(_tapTimer); _tapLast = 0; _tapDbl = true;
      setTimeout(function () { _tapDbl = false; }, 60); return; }
    _tapLast = now2;
    clearTimeout(_tapTimer);
    _tapTimer = setTimeout(function () { if (_tapDbl) return; speakNow(); }, 320);
  });
})();


function init() {
  syncVV();
  refreshFaces();
  FX.start();
  document.documentElement.setAttribute('data-theme', S.theme);
  document.documentElement.setAttribute('data-face', S.face);
  document.documentElement.setAttribute('data-fx', S.fx);
  FACE.build(S.face);
  buildThemeGrid(); buildFaceGrid(); buildFxGrid(); buildSoundGrid(); buildChimeSelect();
  buildVoiceSelect(); buildEngineSelect(); refreshTtsState();
  setTimeout(preloadVox, 400); buildClickToneSelect(); buildTickToneSelect();
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
