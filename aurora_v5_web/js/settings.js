/* =====================================================================
   极光时钟 v5 · js/settings.js —— 设置面板构建 / 顶栏 / 快捷按钮 / 系统按钮
   ===================================================================== */
/* ---------- 设置面板构建 ---------- */
function buildThemeGrid() {
  var g = $('#themeGrid'); if (!g) return; g.innerHTML = '';
  THEMES.forEach(function (t) {
    var i = el('i'); i.setAttribute('data-k', t.k); i.title = t.n;
    i.style.background = 'linear-gradient(135deg,' + t.c1 + ',' + t.c2 + ')';
    i.addEventListener('click', function () { applyTheme(t.k); toast('主题：' + t.n); haptic(); });
    g.appendChild(i);
  });
}
function buildFaceGrid() {
  var g = $('#faceGrid'); if (!g) return; g.innerHTML = '';
  FACES.forEach(function (f) {
    var b = el('button', 'opt', '<b>' + f.i + '</b>' + f.n); b.setAttribute('data-k', f.k);
    b.addEventListener('click', function () { applyFace(f.k); haptic(); toast('表盘：' + f.n); }); g.appendChild(b);
  });
  var t = $('#faceTabs'); if (t) {
    t.innerHTML = ''; FACES.forEach(function (f) {
      var b = el('button', 'ftab', f.i + ' ' + f.n);
      b.setAttribute('data-k', f.k); b.addEventListener('click', function () { applyFace(f.k); haptic(); }); t.appendChild(b);
    });
  }
}
function buildFxGrid() {
  var g = $('#fxGrid'); if (!g) return; g.innerHTML = '';
  FXS.forEach(function (f) {
    var b = el('button', 'opt', '<b>' + f.i + '</b>' + f.n); b.setAttribute('data-k', f.k);
    b.addEventListener('click', function () { applyFx(f.k); haptic(); toast('动效：' + f.n); }); g.appendChild(b);
  });
}
function buildSoundGrid() {
  var g = $('#soundGrid'); if (!g) return; g.innerHTML = '';
  SOUNDS.forEach(function (s) {
    var b = el('button', 'opt', '<b>' + s.i + '</b>' + s.n); b.setAttribute('data-k', s.k);
    if (AU.isPlaying(s.k)) b.classList.add('on');
    b.addEventListener('click', function () {
      AU.resume(); var on = AU.toggleBed(s.k); b.classList.toggle('on', on);
      S.beds = AU.activeBeds(); saveState();
      toast(on ? ('▶ 播放 ' + s.n) : ('⏹ 停止 ' + s.n)); haptic();
    }); g.appendChild(b);
  });
}
function buildChimeSelect() {
  var sel = $('#oChimeTone'); if (!sel) return; sel.innerHTML = '';
  CHIME_TONES.forEach(function (t) { var o = el('option', '', t.n); o.value = t.k; sel.appendChild(o); });
  sel.value = S.chimeTone;
}
function buildClickToneSelect() {
  var sel = $('#oClickTone'); if (!sel) return; sel.innerHTML = '';
  CLICK_TONES.forEach(function (t) { var o = el('option', '', t.n); o.value = t.k; sel.appendChild(o); });
  sel.value = S.clickTone; AU.setClickTone(S.clickTone);
}
function buildTickToneSelect() {
  var sel = $('#oTickTone'); if (!sel) return; sel.innerHTML = '';
  TICK_TONES.forEach(function (t) { var o = el('option', '', t.n); o.value = t.k; sel.appendChild(o); });
  sel.value = S.tickTone; AU.setTickTone(S.tickTone);
}

/* ---------- 语音（TTS）设置：音色/引擎/状态 ---------- */
function buildVoiceSelect(){
  var sel=$('#oVoice'); if(!sel) return;
  sel.innerHTML=''; var o=el('option','','跟随系统'); o.value=''; sel.appendChild(o);
  if(window.VOX_LABEL){ Object.keys(VOX_LABEL).forEach(function(k){ var op=el('option','','内置·'+VOX_LABEL[k]); op.value='vox:'+k; sel.appendChild(op); }); }
  try{
    var b=B();
    if(b&&b.ttsVoices){
      var r=b.ttsVoices();
      if(r&&r.indexOf('"ok":true')>-1){
        var v=JSON.parse(r).voices||[];
        v.forEach(function(x){ var op=el('option','',x.name); op.value=x.id; sel.appendChild(op); });
        if(v.length===0){ sel.appendChild(el('option','','无可用语音')); }
      }
    }
  }catch(e){}
  sel.value=S.ttsVoice||'';
}
function buildEngineSelect(){
  var sel=$('#oEng'); if(!sel) return;
  try{
    var b=B();
    if(b&&b.ttsEngines){
      var r=b.ttsEngines();
      if(r&&r.indexOf('"ok":true')>-1){
        var es=JSON.parse(r).engines||[];
        es.forEach(function(e){ var op=el('option','',e.label||e.name); op.value=e.name; sel.appendChild(op); });
      }
    }
  }catch(e){}
  sel.value='';
}
function refreshTtsState(){
  var v=$('#vTtsState'); if(!v) return;
  try{
    var b=B();
    if(b&&b.ttsState){
      var r=b.ttsState();
      if(r&&r.indexOf('"ready"')>-1){
        var s=JSON.parse(r);
        v.textContent='就绪:'+s.ready+(s.error?' ｜ '+s.error:'')+(s.engines?' ｜ '+s.engines:'');
        return;
      }
    }
  }catch(e){}
  v.textContent='无语音引擎（该设备可能无 TTS）';
}

function bindSettings() {
  var map = [['o24', 'h24', function () { FACE.build(S.face); }], ['oSec', 'showSec', null], ['oMeta', 'meta', function () { var m = $('.cmeta'); if (m) m.style.display = S.meta ? '' : 'none'; }],
  ['oSmooth', 'smooth', null], ['oGlow', 'glow', function () { applyGlow(); }], ['oTick', 'tick', null], ['oChime', 'chime', null],
  ['oClick', 'click', null], ['oVib', 'vib', null], ['oKeep', 'keep', function () { applyKeep(); }], ['oFore', 'fore', function () { applyFore(); }],
  ['oSpeak', 'tts', null], ['oSysBright', 'sysBright', function () { applyBright(); }]];
  map.forEach(function (m) {
    var e = $('#' + m[0]); if (!e) return; e.checked = !!S[m[1]];
    e.addEventListener('change', function () {
      S[m[1]] = e.checked; saveState(); if (m[2]) m[2]();
      if (S.click) AU.click(); haptic();
      if (m[1] === 'tick') toast(e.checked ? '滴答声已开启（每次走秒）' : '滴答声已关闭');
      if (m[1] === 'chime') toast(e.checked ? '整点报时已开启' : '整点报时已关闭');
      if (m[1] === 'fore') toast(e.checked ? '已请求前台服务保活（后台更稳）' : '已关闭保活');
    });
  });
  var br = $('#oBright'); if (br) { br.value = S.bright; br.addEventListener('input', function () { S.bright = +br.value; applyBright(); saveState(); }); }
  var vl = $('#oVol'); if (vl) {
    vl.value = S.vol; vl.addEventListener('input', function () { S.vol = +vl.value; AU.setVol(S.vol / 100);
      var v = $('#vVol'); if (v) v.textContent = S.vol + '%'; saveState(); });
  }
  var ct = $('#oChimeTone'); if (ct) ct.addEventListener('change', function () { S.chimeTone = ct.value; saveState(); AU.resume(); AU.chime(ct.value); });
  var kt = $('#oClickTone'); if (kt) kt.addEventListener('change', function () { S.clickTone = kt.value; saveState(); AU.setClickTone(kt.value); AU.resume(); if (S.click) AU.click(); });
  var tt = $('#oTickTone'); if (tt) tt.addEventListener('change', function () { S.tickTone = tt.value; saveState(); AU.setTickTone(tt.value); AU.resume(); if (S.tick) AU.tick(); });
  var ov = $('#oVoice'); if (ov) ov.addEventListener('change', function () {
    S.ttsVoice = ov.value; saveState();
    if ((S.ttsVoice || '').indexOf('vox:') !== 0) { try { var b = B(); if (b && b.setTtsVoice) b.setTtsVoice(S.ttsVoice || null); } catch (e) {} }
    toast(S.ttsVoice ? (S.ttsVoice.indexOf('vox:') === 0 ? '已切换内置真人语音，点击表盘试听' : '已切换语音音色，点击表盘试听') : '已恢复跟随系统默认音色');
    setTimeout(preloadVox, 300);
  });
  var rv = $('#rowVoice'); if (rv && !hasBridgeFn('ttsVoices')) rv.style.display = 'none';
  var re = $('#rowEng'); if (re && !hasBridgeFn('ttsEngines')) re.style.display = 'none';
  var oe = $('#oEng'); if (oe) oe.addEventListener('change', function () {
    try { var b = B(); if (b && b.setTtsEngine) { b.setTtsEngine(oe.value || null); toast('语音引擎已切换，正在重新初始化…');
      setTimeout(function () { buildVoiceSelect(); refreshTtsState(); }, 1500); } } catch (e) {}
  });
  var rt = $('#rowTtsState'); if (rt && !hasBridgeFn('ttsState')) rt.style.display = 'none';
  var r1 = $('#rowSpeak'); if (r1 && !hasBridgeFn('speakText')) r1.style.display = 'none';
  var r2 = $('#rowSysBright'); if (r2 && !hasBridgeFn('setBrightness')) r2.style.display = 'none';
  var or = $('#oOrient'); if (or) { or.value = S.orient; or.addEventListener('change', function () { S.orient = or.value; saveState(); applyOrient(true); }); }
  $$('.tab').forEach(function (t) { t.addEventListener('click', function () { go(t.getAttribute('data-pg')); }); });
}

/* ---------- 顶栏 / 快捷按钮 ---------- */
function bindTop() {
  $('#btnOrient').addEventListener('click', function (e) { ripple(e, this); cycleOrient(); });
  $('#btnImmerse').addEventListener('click', function (e) { ripple(e, this); toggleImmerse(); });
  $('#btnKeep').addEventListener('click', function (e) {
    ripple(e, this); S.keep = !S.keep; this.classList.toggle('on', S.keep);
    $('#oKeep').checked = S.keep; applyKeep(); saveState(); toast(S.keep ? '屏幕常亮已开启' : '屏幕常亮已关闭');
  });
  $('#btnKeep').classList.toggle('on', S.keep);
  $('#btnTheme').addEventListener('click', function (e) { ripple(e, this); themePicker(); });
  $$('#quick .qbtn').forEach(function (b) {
    b.addEventListener('click', function (ev) {
      ripple(ev, b);
      var a = b.getAttribute('data-act');
      if (a === 'orient') { cycleOrient(); }
      else if (a === 'immerse') { toggleImmerse(); }
      else if (a === 'tick') { S.tick = !S.tick; $('#oTick').checked = S.tick; saveState(); AU.resume(); if (S.tick) AU.tick(); b.classList.toggle('on', S.tick); toast(S.tick ? '滴答声已开启' : '滴答声已关闭'); }
      else if (a === 'chime') { S.chime = !S.chime; $('#oChime').checked = S.chime; saveState(); AU.resume(); AU.chime(S.chimeTone); b.classList.toggle('on', S.chime); toast(S.chime ? '整点报时已开启' : '整点报时已关闭'); }
      else if (a === 'sound') { soundPicker(); }
      else if (a === 'fx') { var idx = 0; for (var i = 0; i < FXS.length; i++) if (FXS[i].k === S.fx) idx = i; var nx = FXS[(idx + 1) % FXS.length]; applyFx(nx.k); toast('动效：' + nx.n); }
    });
  });
  $$('.rbtn,.pill,.ibtn,.tab,.opt,.qbtn,.ftab').forEach(function (b) {
    b.addEventListener('click', function (e) { try { ripple(e, b); } catch (err) { } });
  });
}

function themePicker() {
  var d = el('div'); d.innerHTML = '<div class="grid g-theme" id="tpGrid" style="grid-template-columns:repeat(4,1fr)"></div>' +
    '<div class="bt small">表盘样式</div><div class="grid g-face" id="tpFace"></div>';
  openSheet('外观主题', d, function (root) {
    var g = $('#tpGrid', root); THEMES.forEach(function (t) {
      var i = el('i'); i.style.height = '54px'; i.style.background = 'linear-gradient(135deg,' + t.c1 + ',' + t.c2 + ')';
      i.style.borderRadius = '14px'; i.style.display = 'block'; i.style.border = t.k === S.theme ? '2px solid #fff' : '2px solid transparent'; i.title = t.n;
      i.addEventListener('click', function () { applyTheme(t.k); $$('i', g).forEach(function (x) { x.style.border = '2px solid transparent'; }); i.style.border = '2px solid #fff'; toast('主题：' + t.n); });
      g.appendChild(i);
    });
    var f = $('#tpFace', root); FACES.forEach(function (x) {
      var b = el('button', 'opt', '<b>' + x.i + '</b>' + x.n); if (x.k === S.face) b.classList.add('on');
      b.addEventListener('click', function () { applyFace(x.k); $$('.opt', f).forEach(function (y) { y.classList.remove('on'); }); b.classList.add('on'); }); f.appendChild(b);
    });
  });
}

var SOUNDTIMER = null;
function soundPicker() {
  var d = el('div'); d.innerHTML = '<div class="bt small">可多选叠加 · 定时关闭</div><div class="grid g-sound" id="spGrid"></div>' +
    '<div class="bt small">定时器</div><div class="chips" id="spTimer"><button data-min="15">15 分钟</button><button data-min="30">30 分钟</button><button data-min="60">60 分钟</button><button data-min="0">不限时</button></div>';
  openSheet('助眠 / 专注声景', d, function (root) {
    var g = $('#spGrid', root);
    SOUNDS.forEach(function (s) {
      var b = el('button', 'opt', '<b>' + s.i + '</b>' + s.n); if (AU.isPlaying(s.k)) b.classList.add('on');
      b.addEventListener('click', function () { AU.resume(); var on = AU.toggleBed(s.k); b.classList.toggle('on', on); S.beds = AU.activeBeds(); saveState(); haptic(); });
      g.appendChild(b);
    });
    $$('#spTimer button', root).forEach(function (b) {
      b.addEventListener('click', function () {
        var m = +b.getAttribute('data-min'); $$('#spTimer button', root).forEach(function (x) { x.classList.remove('on'); }); b.classList.add('on');
        if (SOUNDTIMER) clearTimeout(SOUNDTIMER);
        if (m > 0) {
          SOUNDTIMER = setTimeout(function () { AU.stopAllBeds(); buildSoundGrid(); $$('#spGrid .opt', root).forEach(function (x) { x.classList.remove('on'); }); toast('声景已自动停止'); }, m * 60000);
          toast(m + ' 分钟后自动停止');
        }
        else toast('不限时播放'); haptic();
      });
    });
  });
}

/* ---------- 系统按钮 ---------- */
function bindSystem() {
  $('#btnAddCity').addEventListener('click', cityPicker);
  $('#btnShot').addEventListener('click', function () {
    try {
      var b = B(); if (!b || !b.screenshot) { toast('当前环境不支持截图'); return; }
      toast('正在截图…'); window.__shot = function (r) {
        try {
          var d = el('div');
          d.innerHTML = '<img src="data:image/png;base64,' + r.dataBase64 + '" style="width:100%;border-radius:14px">';
          openSheet('截图预览 (' + (r.width || '?') + '×' + (r.height || '?') + ')', d);
        } catch (e) { toast('截图失败'); }
      };
      b.screenshot('__shot');
    } catch (e) { toast('截图不可用'); }
  });
  $('#btnInfo').addEventListener('click', function () {
    var d = el('div'), rows = [];
    try {
      var b = B(); if (b) {
        var _sv = '—', _sa = '?';
        try { if (b.getVersion) { var _j = JSON.parse(b.getVersion()); _sv = _j.shellVersion || _sv; if (_j.bridgeApi !== undefined) _sa = _j.bridgeApi; } } catch (e) { }
        if (_sv === '—' && b.getShellVersion) { try { _sv = b.getShellVersion(); } catch (e) { } }
        if (_sa === '?' && b.getBridgeApi) { try { _sa = b.getBridgeApi(); } catch (e) { } }
        rows.push(['壳版本', _sv + ' (API ' + _sa + ')']);
        if (b.getDeviceInfo) {
          var j = JSON.parse(b.getDeviceInfo());
          rows.push(['机型', (j.manufacturer || '') + ' ' + (j.model || '')]);
          rows.push(['Android', j.sdkInt || '—']); rows.push(['屏幕', (j.screenWidth || '?') + '×' + (j.screenHeight || '?') + ' @' + (j.densityDpi || '?') + 'dpi']);
          rows.push(['启动模式', j.launchMode || '—']);
        }
        if (b.getNetworkType) rows.push(['网络', b.getNetworkType()]);
        if (b.getSystemInfo) { try { var si = JSON.parse(b.getSystemInfo()); if (si.language) rows.push(['语言', si.language]); if (si.timeZone) rows.push(['系统时区', si.timeZone]); if (si.androidVersion) rows.push(['Android版本', si.androidVersion]); if (si.statusBarHeight) rows.push(['状态栏', si.statusBarHeight + 'px']); } catch (e) { } }
        var _bn = Object.keys(b).filter(function (k) { return typeof b[k] === 'function'; }).length; rows.push(['原生桥', _bn + ' 个']);
      }
    } catch (e) { rows.push(['壳', '不可用']); }
    rows.push(['主题', themeOf(S.theme).n]); rows.push(['表盘', faceOf(S.face).n]); rows.push(['动效', S.fx]);
    rows.push(['闹钟数', S.alarms.length + ' 个']); rows.push(['时区', Intl.DateTimeFormat().resolvedOptions().timeZone || '本地']);
    rows.push(['声音引擎', 'Web Audio 实时合成']); rows.push(['本地时间', new Date().toLocaleString('zh-CN')]);
    d.innerHTML = '<div class="blk">' + rows.map(function (r) { return '<div class="row"><span>' + r[0] + '</span><span style="color:var(--acc)">' + r[1] + '</span></div>'; }).join('') + '</div>' +
      '<button class="pill wide" id="infOk">知道了</button>';
    openSheet('设备与版本信息', d, function (root) { $('#infOk', root).addEventListener('click', closeSheet); });
  });
  $('#btnReset').addEventListener('click', function () {
    var doReset = function () {
      try { Object.keys(localStorage).forEach(function (k) { if (k.indexOf('ac_') === 0) localStorage.removeItem(k); }); } catch (e) { }
      location.reload();
    };
    try {
      var b = B(); if (b && b.showDialog) {
        b.showDialog('恢复默认设置', '将清空已保存的主题、表盘、闹钟与设置，恢复到初始状态。此操作不可撤销。', '__resetCb');
        window.__resetCb = function (r) { try { if (r && r.result === 'ok') doReset(); } catch (e) { } }; return;
      }
    } catch (e) { }
    var d = el('div', '', '<div class="fld" style="text-align:center;font-size:13.5px;line-height:1.7">将清空已保存的主题、表盘、闹钟与设置，恢复到初始状态。<br>此操作不可撤销。</div>' +
      '<div class="brow"><button class="rbtn ghost" id="rsNo">取消</button><button class="rbtn danger" id="rsYes">确认重置</button></div>');
    openSheet('恢复默认设置', d, function (root) {
      $('#rsNo', root).addEventListener('click', closeSheet);
      $('#rsYes', root).addEventListener('click', doReset);
    });
  });
}
