/* 极光时钟 · faces/mtx.js —— 流体 */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  ACFace('mtx', {
    n: '流体',
    i: '🌊',
    build: function (host) {
      host.appendChild(el('div', 'face on mtx-face',
        '<div class="fl-bg"></div><div class="fl-bg2"></div>' +
        '<div class="fl-time"><b id="f-t">00:00</b><em id="f-s">00</em></div>'));
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      var e = document.getElementById('f-t'), s = document.getElementById('f-s');
      var t = pad2(h) + ':' + pad2(n.getMinutes());
      if (e && e.textContent != t) e.textContent = t;
      if (s && s.textContent != pad2(n.getSeconds())) s.textContent = pad2(n.getSeconds());
    }
  });
})();
