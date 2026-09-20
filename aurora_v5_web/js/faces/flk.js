/* 极光时钟 · faces/flk.js —— 立体（3D 斜切渐变数字） */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  ACFace('flk', {
    n: '立体',
    i: '📐',
    build: function (host) {
      host.appendChild(el('div', 'face on flk-face',
        '<div class="bv-time"><b id="b-t">00:00</b><em id="b-s">00</em></div>'));
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      var e = document.getElementById('b-t'), s = document.getElementById('b-s');
      var t = pad2(h) + ':' + pad2(n.getMinutes());
      if (e && e.textContent != t) e.textContent = t;
      if (s && s.textContent != pad2(n.getSeconds())) s.textContent = pad2(n.getSeconds());
    }
  });
})();
