/* =====================================================================
   极光时钟 v5 · js/alarm.js —— 闹钟模块（构建/编辑/响铃/贪睡/绑定）
   ===================================================================== */
var DAYN = ['日', '一', '二', '三', '四', '五', '六'];

function nextAlarm() {
  var list = S.alarms.filter(function (a) { return a.on; }), best = null;
  var now = new Date();
  list.forEach(function (a) {
    for (var d = 0; d < 8; d++) {
      var t = new Date(now.getTime() + d * 86400000); t.setHours(a.h, a.m, 0, 0);
      if (t <= now) continue;
      if (a.days && a.days.length && a.days.indexOf(t.getDay()) < 0) continue;
      if (!best || t < best.t) best = { t: t, a: a };
      break;
    }
  });
  return best ? { h: best.a.h, m: best.a.m, t: best.t, a: best.a } : null;
}

function renderAlarms() {
  var box = $('#alarmList'), em = $('#alarmEmpty'); if (!box) return;
  box.innerHTML = '';
  if (!S.alarms.length) { if (em) em.style.display = 'block'; return; }
  if (em) em.style.display = 'none';
  S.alarms.sort(function (a, b) { return (a.h * 60 + a.m) - (b.h * 60 + b.m); });
  S.alarms.forEach(function (a) {
    var c = el('div', 'card'); c.style.position = 'relative';
    var days = (a.days && a.days.length) ? a.days.map(function (d) { return DAYN[d]; }).join(' ') : '每天';
    var hint = '已关闭';
    if (a.on) {
      var now = new Date(), nx = null;
      for (var d = 0; d < 8; d++) {
        var t = new Date(now.getTime() + d * 86400000); t.setHours(a.h, a.m, 0, 0);
        if (t <= now) continue; if (a.days && a.days.length && a.days.indexOf(t.getDay()) < 0) continue; nx = t; break;
      }
      if (nx) {
        var diff = Math.round((nx - now) / 60000), hh = Math.floor(diff / 60), mm = diff % 60;
        hint = (diff < 60 ? diff + ' 分钟后' : (hh + ' 小时 ' + (mm ? mm + ' 分' : '') + '后')) + '（' + DAYN[nx.getDay()] + ' ' + (nx.getMonth() + 1) + '/' + nx.getDate() + '）';
      }
    }
    c.innerHTML = '<div class="card-h"><div><div class="card-t' + (a.on ? '' : ' off') + '">' + pad(a.h) + ':' + pad(a.m) + '</div>' +
      '<div class="card-m">' + (a.label || '闹钟') + '</div></div>' +
      '<label class="swi"><input type="checkbox" ' + (a.on ? 'checked' : '') + ' data-act="toggle"><i></i></label></div>' +
      '<div class="card-x"><span class="tag on">' + days + '</span><span class="tag">' + ringName(a.ring) + '</span>' +
      (a.vib ? '<span class="tag">震动</span>' : '') + '<span class="tag">贪睡 ' + a.snooze + ' 分</span></div>' +
      '<div class="card-m" style="margin-top:8px">⏳ ' + hint + '</div>';
    var tg = $('[data-act=toggle]', c);
    tg.addEventListener('change', function () { a.on = tg.checked; saveState(); renderAlarms(); updateChips(); haptic(); toast(a.on ? '闹钟已开启' : '闹钟已关闭'); });
    var t = $('.card-t', c); if (t) t.addEventListener('click', function () { alarmEditor(a); });
    c.addEventListener('click', function (e) { if (e.target.tagName !== 'INPUT') { ripple(e, c); } });
    box.appendChild(c);
  });
}

function ringName(k) { for (var i = 0; i < RINGERS.length; i++) if (RINGERS[i].k === k) return RINGERS[i].n; return '经典电子铃'; }

var RINGING = { a: null, snoozeUntil: 0, lastKey: '' };

function alarmEditor(a, isNew) {
  var d = el('div');
  var hh = a ? a.h : 7, mm = a ? a.m : 0, days = a ? (a.days || []) : [], ring = a ? a.ring : 'classic', snooze = a ? a.snooze : 5, label = a ? (a.label || '') : '起床啦', vib = a ? a.vib : true;
  d.innerHTML =
    '<div class="fld"><label>时间</label><input type="time" id="edTime" value="' + pad(hh) + ':' + pad(mm) + '"></div>' +
    '<div class="fld"><label>标签</label><input type="text" id="edLabel" value="' + label + '" maxlength="12" placeholder="起床啦 / 开会 / 吃药"></div>' +
    '<div class="fld"><label>重复（不选＝每天一次）</label><div class="wk" id="edDays">' +
    DAYN.map(function (x, i) { return '<button data-d="' + i + '" class="' + (days.indexOf(i) >= 0 ? 'on' : '') + '">' + x + '</button>'; }).join('') +
    '<button data-d="all" style="flex:2">每天</button><button data-d="work" style="flex:2">工作日</button></div></div>' +
    '<div class="fld"><label>铃声</label><div class="ring-list" id="edRings">' +
    RINGERS.map(function (r) { return '<div class="ring-opt' + (r.k === ring ? ' on' : '') + '" data-k="' + r.k + '"><span>' + r.n + '</span>' +
      '<span class="p" data-play="' + r.k + '">▶</span></div>'; }).join('') + '</div></div>' +
    '<div class="fld"><label>贪睡时长（分钟）</label><input type="number" id="edSnooze" min="1" max="30" value="' + snooze + '"></div>' +
    '<div class="row"><span>响铃时震动</span><label class="swi"><input type="checkbox" id="edVib" ' + (vib ? 'checked' : '') + '><i></i></label></div>' +
    '<div class="brow" style="margin-top:16px"><button class="rbtn ghost" id="edSave">' + (isNew ? '添加' : '保存') + '</button>' +
    (isNew ? '' : '<button class="rbtn danger" id="edDel">删除</button>') + '</div>';
  openSheet(isNew ? '新建闹钟' : '编辑闹钟', d, function (root) {
    $$('#edDays button', root).forEach(function (b) {
      b.addEventListener('click', function () {
        var k = b.getAttribute('data-d');
        if (k === 'all') { b.classList.add('on'); $$('#edDays button', root).forEach(function (x) { if (x.getAttribute('data-d') !== 'all' && x.getAttribute('data-d') !== 'work') x.classList.remove('on'); }); }
        else if (k === 'work') { b.classList.add('on'); $$('#edDays button', root).forEach(function (x) { if (x.getAttribute('data-d') !== 'all' && x.getAttribute('data-d') !== 'work') x.classList.remove('on'); }); }
        else b.classList.toggle('on');
        haptic();
      });
    });
    $$('#edRings .ring-opt', root).forEach(function (o) {
      o.addEventListener('click', function (e) {
        if (e.target.getAttribute('data-play')) { var k = e.target.getAttribute('data-play'); AU.resume(); AU.ringOnce(k); return; }
        $$('#edRings .ring-opt', root).forEach(function (x) { x.classList.remove('on'); }); o.classList.add('on'); haptic();
      });
    });
    $('#edSave', root).addEventListener('click', function () {
      var tv = $('#edTime', root).value.split(':'), n = parseInt($('#edSnooze', root).value, 10);
      var pl = $$('#edDays button.on', root).map(function (b) { return b.getAttribute('data-d'); });
      var dd = [];
      if (pl.indexOf('all') >= 0) dd = [];
      else if (pl.indexOf('work') >= 0) dd = [1, 2, 3, 4, 5];
      else dd = pl.filter(function (x) { return x !== 'all' && x !== 'work'; }).map(Number);
      var ro = $('#edRings .ring-opt.on', root), obj = {
        id: a ? a.id : 'a' + Date.now(), h: parseInt(tv[0], 10) || 0, m: parseInt(tv[1], 10) || 0,
        label: $('#edLabel', root).value || '闹钟', days: dd,
        ring: ro ? ro.getAttribute('data-k') : 'classic', snooze: (n >= 1 && n <= 30) ? n : 5,
        vib: $('#edVib', root).checked, on: true
      };
      if (a) { for (var i = 0; i < S.alarms.length; i++) if (S.alarms[i].id === a.id) S.alarms[i] = obj; }
      else S.alarms.push(obj);
      saveState(); renderAlarms(); updateChips(); closeSheet(); AU.swipe();
      toast('闹钟 ' + pad(obj.h) + ':' + pad(obj.m) + ' 已保存');
    });
    var dl = $('#edDel', root); if (dl) dl.addEventListener('click', function () {
      S.alarms = S.alarms.filter(function (x) { return x.id !== a.id; }); saveState(); renderAlarms(); updateChips(); closeSheet(); toast('已删除闹钟');
    });
  });
}

function fireAlarm(a) {
  RINGING.a = a; $('#roTime').textContent = pad(a.h) + ':' + pad(a.m); $('#roLabel').textContent = a.label || '闹钟';
  $('#ringOv').classList.add('on');
  AU.resume(); AU.startRing(a.ring);
  try {
    var b = B(); if (b) { if (b.showNotification) b.showNotification('⏰ ' + pad(a.h) + ':' + pad(a.m) + ' ' + (a.label || '闹钟'), '点开应用可停止响铃'); if (a.vib && b.vibrate) b.vibrate(600); }
  } catch (e) { }
}
function stopAlarm() { AU.stopRing(); $('#ringOv').classList.remove('on'); RINGING.a = null; RINGING.lastKey = ''; }
function snoozeAlarm() { var a = RINGING.a; if (!a) return; RINGING.snoozeUntil = Date.now() + a.snooze * 60000; stopAlarm(); toast('已贪睡 ' + a.snooze + ' 分钟'); }

function checkAlarms() {
  var now = new Date(), key = now.toDateString() + ' ' + now.getHours() + ':' + now.getMinutes();
  if (RINGING.snoozeUntil && Date.now() >= RINGING.snoozeUntil) { RINGING.snoozeUntil = 0; fireAlarm(RINGING.a || { h: now.getHours(), m: now.getMinutes(), label: '贪睡', ring: 'classic', vib: true }); return; }
  if (now.getSeconds() > 2) return;
  if (RINGING.lastKey === key || RINGING.a) return;
  for (var i = 0; i < S.alarms.length; i++) {
    var a = S.alarms[i]; if (!a.on) continue;
    if (a.h !== now.getHours() || a.m !== now.getMinutes()) continue;
    if (a.days && a.days.length && a.days.indexOf(now.getDay()) < 0) continue;
    RINGING.lastKey = key; fireAlarm(a); return;
  }
}

function bindAlarm() {
  $('#btnAddAlarm').addEventListener('click', function () { alarmEditor(null, true); });
  $('#roStop').addEventListener('click', function () { stopAlarm(); toast('闹钟已停止'); });
  $('#roSnooze').addEventListener('click', snoozeAlarm);
  $('#sheetClose').addEventListener('click', closeSheet);
  $('#sheetMask').addEventListener('click', closeSheet);
  updateChips(); setInterval(updateChips, 20000);
}
