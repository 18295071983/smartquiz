/* 极光时钟 · faces/nix2.js —— 毛玻璃数字 */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  ACFace('nix2', {
    n: '毛玻璃',
    i: '🧊',
    build: function (host) {
      host.appendChild(el('div', 'face on nix2-face',
        '<div class="gl-bg"><i></i><i></i><i></i></div>' +
        '<div class="gl-cards">' +
        '<div class="gl-card"><b id="g-h">00</b><span>时</span></div>' +
        '<div class="gl-dot"><i></i><i></i></div>' +
        '<div class="gl-card"><b id="g-m">00</b><span>分</span></div>' +
        '<div class="gl-dot"><i></i><i></i></div>' +
        '<div class="gl-card small"><b id="g-s">00</b><span>秒</span></div>' +
        '</div>'));
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      var e1 = document.getElementById('g-h'), e2 = document.getElementById('g-m'), e3 = document.getElementById('g-s');
      if (e1 && e1.textContent != pad2(h)) e1.textContent = pad2(h);
      if (e2 && e2.textContent != pad2(n.getMinutes())) e2.textContent = pad2(n.getMinutes());
      if (e3 && e3.textContent != pad2(n.getSeconds())) e3.textContent = pad2(n.getSeconds());
    }
  });
})();
