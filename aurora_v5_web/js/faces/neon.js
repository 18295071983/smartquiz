/* 极光时钟 v5 · faces/neon.js —— 霓虹描边表盘（外置表盘） */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  ACFace('neon', {
    n: '霓虹',
    i: '🌆',
    build: function (host) {
      host.appendChild(el('div', 'face on',
        '<div class="neon-wrap"><div class="neon mono" id="f-neon">00:00</div></div><div class="neon-sub" id="f-neons">AURORA · NIGHT</div>'));
    },
    paint: function (n, S) {
      var h = n.getHours(), m = n.getMinutes(), s = n.getSeconds();
      var H = S.h24 ? h : hour12(h);
      var hs = pad2(H), ms = pad2(m), ss = pad2(s);
      var ne = $('#f-neon');
      if (ne) ne.textContent = hs + ':' + ms + (S.showSec ? ':' + ss : '');
      var ns = $('#f-neons');
      if (ns) {
        var dk = (h < 6) ? '凌晨' : (h < 12) ? '早上' : (h < 14) ? '中午' : (h < 18) ? '下午' : (h < 22) ? '晚上' : '深夜';
        ns.textContent = dk + ' · AURORA NEON';
      }
    }
  });
})();
