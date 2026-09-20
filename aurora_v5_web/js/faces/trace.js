/* 极光时钟 · faces/trace.js —— 轨迹（Trace Line：内圈小时+外圈分钟，单线相连） */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  ACFace('trace', {
    n: '轨迹',
    i: '〰️',
    build: function (host) {
      host.appendChild(el('div', 'face on trace-face',
        '<svg class="tr-svg" viewBox="0 0 200 200">' +
        '<defs><linearGradient id="trGrad" x1="0%" y1="0%" x2="100%" y2="100%">' +
        '<stop offset="0%" stop-color="#7df9ff"/><stop offset="100%" stop-color="#9a7bff"/></linearGradient></defs>' +
        '<circle class="tr-ring hl" cx="100" cy="100" r="88"/>' +
        '<circle class="tr-ring" cx="100" cy="100" r="44"/>' +
        '' +
        '<circle class="tr-dot" id="tr-d1" cx="100" cy="56" r="4"/>' +
        '<circle class="tr-dot" id="tr-d2" cx="100" cy="12" r="4"/>' +
        '</svg><div class="tr-center"><em class="tr-sec" id="tr-s">00</em><div class="tr-t"><b id="tr-t">00:00</b><span id="tr-d">9月21日</span></div></div>'));
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      var e = document.getElementById('tr-t'), d = document.getElementById('tr-d'), s = document.getElementById('tr-s');
      var t = pad2(h) + ':' + pad2(n.getMinutes());
      if (e && e.textContent != t) e.textContent = t;
      if (d && !d.dataset.d) { d.dataset.d = 1; d.textContent = (n.getMonth() + 1) + '月' + n.getDate() + '日'; }
      if (s) { s.textContent = pad2(n.getSeconds()); s.style.left = (((x1 + x2) / 2) / 2).toFixed(1) + '%'; s.style.top = (((y1 + y2) / 2) / 2).toFixed(1) + '%'; }
      var a1 = (h % 12) / 12 * Math.PI * 2 - Math.PI / 2;
      var a2 = n.getMinutes() / 60 * Math.PI * 2 - Math.PI / 2;
      var x1 = 100 + Math.cos(a1) * 44, y1 = 100 + Math.sin(a1) * 44;
      var x2 = 100 + Math.cos(a2) * 88, y2 = 100 + Math.sin(a2) * 88;
      var p1 = document.getElementById('tr-d1'), p2 = document.getElementById('tr-d2');
      
      if (p1) { p1.setAttribute('cx', x1.toFixed(1)); p1.setAttribute('cy', y1.toFixed(1)); }
      if (p2) { p2.setAttribute('cx', x2.toFixed(1)); p2.setAttribute('cy', y2.toFixed(1)); }
    }
  });
})();
