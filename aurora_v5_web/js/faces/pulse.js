/* 极光时钟 · faces/pulse.js —— 脉冲 */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  ACFace('pulse', {
    n: '脉冲',
    i: '💊',
    build: function (host) {
      host.appendChild(el('div', 'face on pulse-face',
        '<div class="pl-time"><div class="pl-pill"><b id="pl-h">00</b></div><div class="pl-dot"><i></i><i></i></div>' +
        '<div class="pl-pill"><b id="pl-m">00</b></div></div>' +
        '<div class="pl-foot" id="pl-d">9月21日 周一</div>'));
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      var e1 = document.getElementById('pl-h'), e2 = document.getElementById('pl-m');
      if (e1 && e1.textContent != pad2(h)) e1.textContent = pad2(h);
      if (e2 && e2.textContent != pad2(n.getMinutes())) e2.textContent = pad2(n.getMinutes());
      var d = document.getElementById('pl-d');
      if (d && !d.dataset.d) { d.dataset.d = 1; d.textContent = (n.getMonth() + 1) + '月' + n.getDate() + '日'; }
    }
  });
})();
