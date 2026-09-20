/* 极光时钟 · faces/led.js —— 霓虹像素（5×7 渐变像素块数字） */
(function () {
  var FONT = {
    '0': ['01110','10001','10011','10101','11001','10001','01110'],
    '1': ['00100','01100','00100','00100','00100','00100','01110'],
    '2': ['01110','10001','00001','00110','01000','10000','11111'],
    '3': ['11110','00001','00001','00110','00001','00001','11110'],
    '4': ['00010','00110','01010','10010','11111','00010','00010'],
    '5': ['11111','10000','10000','11110','00001','00001','11110'],
    '6': ['00110','01000','10000','11110','10001','10001','01110'],
    '7': ['11111','00001','00010','00100','01000','01000','01000'],
    '8': ['01110','10001','10001','01110','10001','10001','01110'],
    '9': ['01110','10001','10001','01111','00001','00010','01100']
  };
  var cols = [];
  function digitHtml(d, cls) {
    var rows = FONT[d], h = '';
    for (var r = 0; r < 7; r++) {
      for (var c = 0; c < 5; c++) h += '<div class="px-dot' + (rows[r][c] === '1' ? ' iso' : '') + '"></div>';
    }
    return '<div class="px-col ' + cls + '">' + h + '</div>';
  }
  function sepHtml() { return '<div class="px-sep"><i></i><i></i></div>'; }
  function setDigit(colEl, d, cls) {
    var rows = FONT[d];
    for (var r = 0; r < 7; r++) for (var c = 0; c < 5; c++) {
      var dot = colEl.children[r * 5 + c];
      var on = rows[r][c] === '1';
      dot.classList.toggle('iso', on);
      dot.classList.toggle('off', !on);
    }
    colEl.className = 'px-col' + (cls === 'hot' ? ' hot' : '');
  }
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  ACFace('led', {
    n: '霓虹像素',
    i: '🟪',
    build: function (host) {
      host.appendChild(el('div', 'face on led-face',
        '<div class="px-time">' + digitHtml('8', '') + digitHtml('8', '') + sepHtml() +
        digitHtml('8', '') + digitHtml('8', '') + sepHtml() + digitHtml('8', 'hot') + digitHtml('8', 'hot') + '</div>'));
      cols = host.querySelectorAll('.px-col');
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      var hm = pad2(h) + pad2(n.getMinutes()), s = pad2(n.getSeconds());
      for (var i = 0; i < 4; i++) setDigit(cols[i], hm.charAt(i), i < 2 ? '' : '');
      for (var j = 0; j < 2; j++) setDigit(cols[4 + j], s.charAt(j), 'hot');
    }
  });
})();
