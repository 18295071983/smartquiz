/* 极光时钟 · faces/led.js —— LED二进制数字钟（源自 GitHub DON-KD2QQV/binary-clock） */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  var ON = { h: '#3b82f6', m: '#e5e7eb', s: '#ef4444' };
  function ledUpd(id, cls, val) {
    var b = val.toString(2).padStart(6, '0');
    for (var i = 0; i < 6; i++) {
      var d = document.getElementById(id + i); if (!d) continue;
      d.style.background = (b[5 - i] === '1' ? ON[cls] : '#374151');
    }
  }
  function colHtml(id, cls) {
    var h = '';
    for (var i = 0; i < 6; i++) h += '<div class="led-dot" id="' + id + i + '"></div>';
    return '<div class="led-col">' + h + '</div>';
  }
  ACFace('led', {
    n: 'LED数字',
    i: '🟢',
    build: function (host) {
      host.appendChild(el('div', 'face on led-face',
        '<div class="led-cols">' + colHtml('lh', 'h') + colHtml('lm', 'm') + colHtml('ls', 's') + '</div>' +
        '<div class="led-tag"><b style="color:#3b82f6">HRS</b><b style="color:#e5e7eb">MIN</b><b style="color:#ef4444">SEC</b></div>' +
        '<div class="led-digi" id="led-d">--:--:--</div>'));
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      ledUpd('lh', 'h', h); ledUpd('lm', 'm', n.getMinutes()); ledUpd('ls', 's', n.getSeconds());
      var d = document.getElementById('led-d');
      if (d) d.textContent = pad2(h) + ':' + pad2(n.getMinutes()) + ':' + pad2(n.getSeconds());
    }
  });
})();
