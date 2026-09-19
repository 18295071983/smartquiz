/* 极光时钟 v5 · faces/analog.js —— 指针表盘（外置表盘，SVG 时针/分针/秒针） */
(function () {
  ACFace('ana', {
    n: '指针',
    i: '🕰️',
    build: function (host) {
      var svg = '<div class="face on"><div class="analog"><svg viewBox="0 0 300 300"><circle class="dial" cx="150" cy="150" r="140"></circle><g id="ticks"></g>' +
        '<line class="hand h-hour" id="hh" x1="150" y1="150" x2="150" y2="68"></line>' +
        '<line class="hand h-min" id="mh" x1="150" y1="150" x2="150" y2="40"></line>' +
        '<line class="hand h-sec" id="sh" x1="150" y1="164" x2="150" y2="34"></line>' +
        '<circle class="cap" cx="150" cy="150" r="6"></circle>' +
        '<text class="dg" id="dgText" x="150" y="196">MON 14</text></svg></div></div>';
      host.appendChild(el('div', '', svg));
      var g = $('#ticks');
      if (!g) return;
      var s = '';
      for (var i = 0; i < 60; i++) {
        var maj = i % 5 === 0;
        var a = i * 6 * Math.PI / 180, r1 = maj ? 120 : 127, r2 = 135;
        var x1 = 150 + Math.sin(a) * r1, y1 = 150 - Math.cos(a) * r1, x2 = 150 + Math.sin(a) * r2, y2 = 150 - Math.cos(a) * r2;
        s += '<line class="tick' + (maj ? ' maj' : '') + '" x1="' + x1.toFixed(1) + '" y1="' + y1.toFixed(1) + '" x2="' + x2.toFixed(1) + '" y2="' + y2.toFixed(1) + '"></line>';
      }
      for (var h = 1; h <= 12; h++) {
        var a2 = h * 30 * Math.PI / 180, R = 100;
        s += '<text class="num" x="' + (150 + Math.sin(a2) * R).toFixed(1) + '" y="' + (150 - Math.cos(a2) * R).toFixed(1) + '">' + h + '</text>';
      }
      g.innerHTML = s;
    },
    paint: function (n, S) {
      var h = n.getHours(), m = n.getMinutes(), s = n.getSeconds(), mi = n.getMilliseconds();
      var secF = S.smooth ? s + mi / 1000 : s;
      var hAng = (h % 12) * 30 + m * 0.5, mAng = m * 6 + (S.smooth ? s * 0.1 : 0), sAng = secF * 6;
      var hh = $('#hh'), mh = $('#mh'), sh = $('#sh');
      if (hh) hh.style.transform = 'rotate(' + hAng + 'deg)';
      if (mh) mh.style.transform = 'rotate(' + mAng + 'deg)';
      if (sh) {
        sh.style.transition = S.smooth ? 'none' : 'transform .18s cubic-bezier(.3,1.6,.5,1)';
        sh.style.transform = 'rotate(' + sAng + 'deg)';
      }
      var dt = $('#dgText');
      if (dt) dt.textContent = '日一二三四五六'[n.getDay()] + ' ' + n.getDate();
    }
  });
})();
