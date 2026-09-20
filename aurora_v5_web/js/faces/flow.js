/* 极光时钟 · faces/flow.js —— 流彩 */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  ACFace('flow', {
    n: '流彩',
    i: '🌈',
    build: function (host) {
      host.appendChild(el('div', 'face on flow-face',
        '<div class="fw-bg"></div><div class="fw-time"><b id="fw-t">00:00</b></div>' +
        '<div class="fw-sec" id="fw-s">00 秒</div>'));
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      var e = document.getElementById('fw-t'), s = document.getElementById('fw-s');
      var t = pad2(h) + ':' + pad2(n.getMinutes());
      if (e && e.textContent != t) e.textContent = t;
      if (s && s.textContent != pad2(n.getSeconds()) + ' 秒') s.textContent = pad2(n.getSeconds()) + ' 秒';
    }
  });
})();
