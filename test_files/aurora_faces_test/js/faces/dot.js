/* 极光时钟 v5 · faces/dot.js —— 点阵 LED 表盘（外置表盘，5×7 点阵字模） */
(function () {
  var DOTF = {
    '0': ['01110', '10001', '10011', '10101', '11001', '10001', '01110'],
    '1': ['00100', '01100', '00100', '00100', '00100', '00100', '01110'],
    '2': ['01110', '10001', '00001', '00010', '00100', '01000', '11111'],
    '3': ['11111', '00010', '00100', '00010', '00001', '10001', '01110'],
    '4': ['00010', '00110', '01010', '10010', '11111', '00010', '00010'],
    '5': ['11111', '10000', '11110', '00001', '00001', '10001', '01110'],
    '6': ['00110', '01000', '10000', '11110', '10001', '10001', '01110'],
    '7': ['11111', '00001', '00010', '00100', '01000', '01000', '01000'],
    '8': ['01110', '10001', '10001', '01110', '10001', '10001', '01110'],
    '9': ['01110', '10001', '10001', '01111', '00001', '00010', '01100'],
    ':': ['0', '0', '1', '0', '1', '0', '0']
  };
  ACFace('dot', {
    n: '点阵',
    i: '⚫',
    build: function (host) {
      var dw = el('div', 'face on dotface');
      dw.id = 'f-dot';
      ['h1', 'h2', ':', 'm1', 'm2'].forEach(function (id) {
        if (id === ':') {
          var c = el('div', 'dcolon');
          c.innerHTML = '<i></i><i></i>';
          dw.appendChild(c);
          return;
        }
        var g = el('div', 'dchar');
        g.id = 'dd-' + id;
        for (var i = 0; i < 35; i++) g.appendChild(el('i', 'dot'));
        dw.appendChild(g);
      });
      host.appendChild(dw);
    },
    paint: function (n, S) {
      var h = n.getHours(), m = n.getMinutes(), s = n.getSeconds();
      var H = S.h24 ? h : hour12(h);
      var hs = (H < 10 ? '0' + H : '' + H), ms = (m < 10 ? '0' + m : '' + m);
      var ids = { h1: hs[0], h2: hs[1], m1: ms[0], m2: ms[1] };
      Object.keys(ids).forEach(function (id) {
        var g = $('#dd-' + id);
        if (!g) return;
        var pat = DOTF[ids[id]] || DOTF['0'];
        var dots = $$('.dot', g);
        for (var r = 0; r < 7; r++)
          for (var c = 0; c < 5; c++) {
            var on = pat[r].charAt(c) === '1';
            var d = dots[r * 5 + c];
            if (d) d.classList.toggle('on', on);
          }
      });
      if (s !== undefined && S.showSec) {
        var colon = $('#f-dot .dcolon');
        if (colon) colon.classList.toggle('blink', s % 2 === 0);
      }
    }
  });
})();
