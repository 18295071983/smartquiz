/* =====================================================================
   极光时钟 v5 · js/timer.js —— 秒表 / 倒计时 / 番茄钟 + 绑定
   ===================================================================== */
/* ---------- 秒表 ---------- */
var SW = { run: false, t0: 0, acc: 0, laps: [], splits: [] };
function swTime() { return SW.acc + (SW.run ? (Date.now() - SW.t0) : 0); }
function swFmt(ms) { var m = Math.floor(ms / 60000), s = Math.floor(ms % 60000 / 1000), c = Math.floor(ms % 1000 / 10);
  return pad(m) + ':' + pad(s) + '.' + pad(c); }
function swPaint() { var d = $('#swDisp'); if (d) d.textContent = swFmt(swTime()); }
function swToggle() {
  SW.run = !SW.run; if (SW.run) { SW.t0 = Date.now(); AU.resume(); } else SW.acc = swTime();
  $('#swRun').textContent = SW.run ? '暂停' : '开始'; $('#swRun').classList.toggle('danger', SW.run);
  haptic(); if (S.click) AU.click();
}
function swLap() {
  var t = swTime(); SW.splits.push(t); var box = $('#swLaps'); if (!box) return;
  var lap = el('div', 'lap'); lap.innerHTML = '<span>计次 ' + SW.splits.length + '</span><span>' + swFmt(t) + '</span>'; box.insertBefore(lap, box.firstChild); haptic();
}
function swReset() {
  SW.run = false; SW.acc = 0; SW.t0 = 0; SW.splits = []; $('#swRun').textContent = '开始'; $('#swRun').classList.remove('danger');
  $('#swLaps').innerHTML = ''; swPaint(); haptic();
}

/* ---------- 倒计时 ---------- */
var CD = { run: false, total: 300000, left: 300000, end: 0 };
function cdPresets() {
  var box = $('#cdPresets'); if (!box) return; box.innerHTML = '';
  [1, 3, 5, 10, 15, 25, 30, 45, 60].forEach(function (m) {
    var b = el('button', '', m + ' 分');
    b.addEventListener('click', function () {
      CD.run = false; CD.total = m * 60000; CD.left = CD.total;
      $('#cdMin').value = m; $('#cdSec').value = 0; $('#cdRun').textContent = '开始'; $('#cdRun').classList.remove('danger');
      $$('#cdPresets button').forEach(function (x) { x.classList.remove('on'); }); b.classList.add('on'); cdPaint(); haptic(); if (S.click) AU.click();
    });
    box.appendChild(b);
  });
}
function cdPaint() {
  var l = Math.max(0, CD.left), s = Math.ceil(l / 1000);
  var d = $('#cdDisp'); if (d) d.textContent = pad(Math.floor(s / 60)) + ':' + pad(s % 60);
  var ring = $('#cdRing'); if (ring) ring.setAttribute('stroke-dashoffset', (603 * (1 - (CD.total ? l / CD.total : 0))).toFixed(1));
  var sub = $('#cdSub'); if (sub) sub.textContent = CD.run ? '计时中…' : '暂停/待机';
}
function cdToggle() {
  if (!CD.run && CD.left <= 0) CD.left = CD.total || 300000;
  CD.run = !CD.run; if (CD.run) { CD.end = Date.now() + CD.left; AU.resume(); }
  $('#cdRun').textContent = CD.run ? '暂停' : '开始'; $('#cdRun').classList.toggle('danger', CD.run); haptic(); if (S.click) AU.click();
}
function cdReset() {
  CD.run = false; CD.total = ((parseInt($('#cdMin').value, 10) || 0) * 60 + (parseInt($('#cdSec').value, 10) || 0)) * 1000; CD.left = CD.total;
  $('#cdRun').textContent = '开始'; $('#cdRun').classList.remove('danger'); cdPaint(); haptic();
}
function cdFinish() {
  CD.run = false; CD.left = 0; cdPaint(); AU.resume(); AU.startRing('gentle');
  try { var b = B(); if (b && b.showNotification) b.showNotification('⏳ 倒计时结束', '时间到啦'); if (b && b.vibrate) b.vibrate(500); } catch (e) { }
  toast('⏳ 倒计时结束'); setTimeout(function () { AU.stopRing(); }, 9000);
}

/* ---------- 番茄钟 ---------- */
var PM = { run: false, phase: 'work', left: 0, total: 0, end: 0, rounds: 0 };
function pmDurations() { return { w: (parseInt($('#pmWork').value, 10) || 25) * 60000, r: (parseInt($('#pmRest').value, 10) || 5) * 60000 }; }
function pmPaint() {
  var d = $('#pmDisp'); if (d) d.textContent = pad(Math.floor(PM.left / 60000)) + ':' + pad(Math.floor(PM.left % 60000 / 1000));
  var ring = $('#pmRing'); if (ring) ring.setAttribute('stroke-dashoffset', (603 * (1 - (PM.total ? PM.left / PM.total : 0))).toFixed(1));
  var p = $('#pmPhase'); if (p) p.textContent = PM.phase === 'work' ? '专注' : '休息';
  var r = $('#pmRounds'); if (r) r.textContent = '已完成 ' + PM.rounds + ' 轮' + (PM.run ? ' · ' + (PM.phase === 'work' ? '保持专注' : '放松一下') : '');
}
function pmToggle() {
  var D = pmDurations();
  if (!PM.run && PM.left <= 0) { PM.phase = 'work'; PM.total = D.w; PM.left = D.w; }
  PM.run = !PM.run; if (PM.run) { PM.end = Date.now() + PM.left; AU.resume(); }
  $('#pmRun').textContent = PM.run ? '暂停' : '开始'; $('#pmRun').classList.toggle('danger', PM.run); pmPaint(); haptic(); if (S.click) AU.click();
}
function pmReset() {
  var D = pmDurations(); PM.run = false; PM.phase = 'work'; PM.total = D.w; PM.left = D.w; PM.rounds = 0;
  $('#pmRun').textContent = '开始'; $('#pmRun').classList.remove('danger'); pmPaint(); haptic();
}
function pmFinish() {
  var D = pmDurations(); AU.resume(); AU.ringOnce(PM.phase === 'work' ? 'gentle' : 'arcade');
  try { var b = B(); if (b && b.vibrate) b.vibrate(400); if (b && b.showNotification) b.showNotification(PM.phase === 'work' ? '🍅 专注结束' : '☕ 休息结束', PM.phase === 'work' ? '去休息一下吧' : '回来继续专注'); } catch (e) { }
  if (PM.phase === 'work') { PM.rounds++; PM.phase = 'rest'; PM.total = D.r; PM.left = D.r; } else { PM.phase = 'work'; PM.total = D.w; PM.left = D.w; }
  if (PM.run) PM.end = Date.now() + PM.left;
  var hl = $('#pmPhase'); if (hl) { hl.animate ? hl.animate([{ opacity: .2 }, { opacity: 1 }], { duration: 600, iterations: 3 }) : 0; }
  pmPaint();
}

/* ---------- 绑定 ---------- */
function bindTimers() {
  $$('#timerSeg button').forEach(function (b) {
    b.addEventListener('click', function () {
      $$('#timerSeg button').forEach(function (x) { x.classList.remove('on'); }); b.classList.add('on');
      var t = b.getAttribute('data-t'); $$('.tpanel').forEach(function (p) { p.classList.remove('on'); });
      var tp = $('#tp-' + t); if (tp) tp.classList.add('on'); haptic(); if (S.click) AU.click();
    });
  });
  $('#swRun').addEventListener('click', swToggle); $('#swLap').addEventListener('click', swLap); $('#swReset').addEventListener('click', swReset);
  $('#cdRun').addEventListener('click', cdToggle); $('#cdReset').addEventListener('click', cdReset);
  $('#cdMin').addEventListener('change', cdReset); $('#cdSec').addEventListener('change', cdReset);
  $('#pmRun').addEventListener('click', pmToggle); $('#pmReset').addEventListener('click', pmReset);
  $('#pmWork').addEventListener('change', pmReset); $('#pmRest').addEventListener('change', pmReset);
  cdPresets(); CD.total = 300000; CD.left = 300000; cdPaint();
  PM.total = 25 * 60000; PM.left = PM.total; pmPaint();
}
