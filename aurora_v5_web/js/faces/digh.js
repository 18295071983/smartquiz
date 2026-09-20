/* 极光时钟 · faces/digh.js —— 数字指针表盘（源自 GitHub Moderrek/clock-digital-hands） */
(function () {
  ACFace('digh', {
    n: '数字针',
    i: '🕘',
    build: function (host) {
      host.appendChild(el('div', 'face on digh-face', '<canvas id="digh-cv"></canvas>'));
    },
    paint: function (n, S) {
      var cv = document.getElementById('digh-cv'); if (!cv) return;
      var sz = Math.min(cv.clientWidth, cv.clientHeight);
      var dpr = window.devicePixelRatio || 1;
      if (cv.width !== Math.round(sz * dpr)) { cv.width = Math.round(sz * dpr); cv.height = Math.round(sz * dpr); }
      var ctx = cv.getContext('2d');
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, sz, sz);
      var cx = sz / 2, cy = sz / 2, rel = function (x) { return sz / 400 * x; }, i, a, r, len;
      var g = ctx.createRadialGradient(cx, cy, 0, cx, cy, rel(60));
      g.addColorStop(0, 'rgba(0,240,255,.10)'); g.addColorStop(1, 'rgba(0,240,255,0)');
      ctx.fillStyle = g; ctx.beginPath(); ctx.arc(cx, cy, rel(60), 0, Math.PI * 2); ctx.fill();
      ctx.lineCap = 'round';
      for (i = 0; i < 60; i++) {
        var isH = i % 5 === 0;
        r = rel(200); len = rel(isH ? 170 : 190);
        a = Math.PI * 2 * i / 60 - Math.PI / 2;
        ctx.strokeStyle = isH ? 'rgba(125,240,255,.85)' : 'rgba(255,255,255,.25)';
        ctx.lineWidth = rel(isH ? 3 : 2);
        ctx.beginPath(); ctx.moveTo(cx + Math.cos(a) * r, cy + Math.sin(a) * r);
        ctx.lineTo(cx + Math.cos(a) * len, cy + Math.sin(a) * len); ctx.stroke();
      }
      function hand(from, fs, font, val, pct, seq, sp, color) {
        ctx.fillStyle = color; ctx.font = rel(fs) + 'px ' + font;
        ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        var a = Math.PI * 2 * pct - Math.PI / 2;
        var x = cx + Math.cos(a) * rel(from), y = cy + Math.sin(a) * rel(from);
        for (var j = 0; j < seq; j++) {
          x += Math.cos(a) * rel(fs + sp); y += Math.sin(a) * rel(fs + sp);
          ctx.fillText(String(val), x, y);
        }
      }
      var s = n.getSeconds(), m = n.getMinutes(), h = S.h24 ? n.getHours() : hour12(n.getHours());
      hand(12, 12, 'Arial Light', s, s / 60, 10, 4, 'rgba(255,110,120,.95)');
      hand(18, 17, 'Arial', m, m / 60, 7, 4, 'rgba(255,255,255,.92)');
      hand(18, 18, 'Arial Black', h, (h % 12) / 12, 5, 0, 'rgba(125,240,255,.95)');
    }
  });
})();
