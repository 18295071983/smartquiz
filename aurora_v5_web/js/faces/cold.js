/* 极光时钟 · faces/cold.js —— 冷光 */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  ACFace('cold', {
    n: '冷光',
    i: '❄️',
    build: function (host) {
      host.appendChild(el('div', 'face on cold-face',
        '<div class="cold-time"><b id="cd-h">00</b><em>:</em><b id="cd-m">00</b></div>' +
        '<div class="cold-sec"><b id="cd-s">00</b> 秒</div>'));
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      var e1 = document.getElementById('cd-h'), e2 = document.getElementById('cd-m'), e3 = document.getElementById('cd-s');
      if (e1 && e1.textContent != pad2(h)) e1.textContent = pad2(h);
      if (e2 && e2.textContent != pad2(n.getMinutes())) e2.textContent = pad2(n.getMinutes());
      if (e3 && e3.textContent != pad2(n.getSeconds())) e3.textContent = pad2(n.getSeconds());
    }
  });
})();
