/* 极光时钟 · faces/wclk.js —— 极光大字 */
(function () {
  var WD = ['日', '一', '二', '三', '四', '五', '六'];
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  ACFace('wclk', {
    n: '极光大字',
    i: '✨',
    build: function (host) {
      host.appendChild(el('div', 'face on wclk-face',
        '<div class="aur-bg"><i></i><i></i><i></i></div>' +
        '<div class="aur-meta" id="aur-meta"></div>' +
        '<div class="aur-time"><b id="aur-h">00</b><em>:</em><b id="aur-m">00</b></div>' +
        '<div class="aur-sec"><b id="aur-s">00</b> 秒</div>'));
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      var e1 = document.getElementById('aur-h'), e2 = document.getElementById('aur-m');
      if (e1 && e1.textContent != pad2(h)) e1.textContent = pad2(h);
      if (e2 && e2.textContent != pad2(n.getMinutes())) e2.textContent = pad2(n.getMinutes());
      var e3 = document.getElementById('aur-s'); if (e3) e3.textContent = pad2(n.getSeconds());
      var em = document.getElementById('aur-meta');
      if (em && !em.dataset.d) { em.dataset.d = 1;
        em.textContent = (S.h24 ? '' : (h >= 12 ? '下午 ' : '上午 ')) + (n.getMonth() + 1) + '月' + n.getDate() + '日 · 星期' + WD[n.getDay()];
      }
    }
  });
})();
