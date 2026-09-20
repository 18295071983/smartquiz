/* 极光时钟 · faces/digh.js —— 光环（极光渐变秒环 + 中心大字） */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  var CIRC = 2 * Math.PI * 54;
  ACFace('digh', {
    n: '光环',
    i: '⭕',
    build: function (host) {
      host.appendChild(el('div', 'face on digh-face',
        '<div class="ha-ring"><svg viewBox="0 0 120 120">' +
        '<defs><linearGradient id="haGrad" x1="0%" y1="0%" x2="100%" y2="100%">' +
        '<stop offset="0%" stop-color="#00d8ff"/><stop offset="55%" stop-color="#7a5cff"/>' +
        '<stop offset="100%" stop-color="#ff4fa0"/></linearGradient></defs>' +
        '<circle class="ha-track" cx="60" cy="60" r="54"/>' +
        '<circle class="ha-prog" id="ha-s" cx="60" cy="60" r="54" stroke-dasharray="' + CIRC + '" stroke-dashoffset="0"/>' +
        '</svg><div class="ha-center"><b id="ha-t">00:00</b><span id="ha-d">9月21日</span><em id="ha-s2">00 秒</em></div></div>'));
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      var e = document.getElementById('ha-t');
      var t = pad2(h) + ':' + pad2(n.getMinutes());
      if (e && e.textContent != t) e.textContent = t;
      var ring = document.getElementById('ha-s');
      if (ring) ring.style.strokeDashoffset = (CIRC * (1 - n.getSeconds() / 60)).toFixed(1);
      var d = document.getElementById('ha-d');
      if (d && !d.dataset.d) { d.dataset.d = 1; d.textContent = (n.getMonth() + 1) + '月' + n.getDate() + '日'; }
      var s2 = document.getElementById('ha-s2'); if (s2) s2.textContent = pad2(n.getSeconds()) + ' 秒';
    }
  });
})();
