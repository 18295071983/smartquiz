/* 极光时钟 · faces/mtx.js —— 矩阵数字钟（源自 GitHub jsvaldezv/matrix-clock，数字 + 绿色字符雨） */
(function () {
  var _raf = null, _drops = null, _cols = 0, _w = 0, _h = 0;
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  function rain(cv, cx) {
    cx.fillStyle = 'rgba(0,12,0,.14)'; cx.fillRect(0, 0, _w, _h);
    cx.fillStyle = '#00ff60'; cx.font = '12px monospace';
    for (var i = 0; i < _cols; i++) {
      var ch = String.fromCharCode(0x30A0 + Math.floor(Math.random() * 96));
      cx.fillText(ch, i * 14, _drops[i] * 14);
      if (_drops[i] * 14 > _h && Math.random() > .975) _drops[i] = 0;
      _drops[i]++;
    }
    _raf = requestAnimationFrame(function () { rain(cv, cx); });
  }
  function size(cv) {
    _w = cv.width = cv.clientWidth; _h = cv.height = cv.clientHeight;
    _cols = Math.floor(_w / 14);
    _drops = []; for (var i = 0; i < _cols; i++) _drops[i] = Math.random() * -70;
  }
  ACFace('mtx', {
    n: '矩阵',
    i: '💚',
    build: function (host) {
      if (_raf) { cancelAnimationFrame(_raf); _raf = null; }
      host.appendChild(el('div', 'face on mtx-face',
        '<canvas class="mtx-bg" id="mtx-cv"></canvas><div class="mtx-txt" id="mtx-t">00 : 00</div>'));
      var cv = document.getElementById('mtx-cv'), cx = cv.getContext('2d');
      size(cv);
      window.addEventListener('resize', function () { size(cv); });
      rain(cv, cx);
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      var t = document.getElementById('mtx-t');
      if (t) t.textContent = pad2(h) + ' : ' + pad2(n.getMinutes());
    }
  });
})();
