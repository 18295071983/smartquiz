/* 极光时钟 · faces/nix2.js —— 超写实辉光管（源自 GitHub joeparadiso/nixie-tube-clock） */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  ACFace('nix2', {
    n: '辉光管·写实',
    i: '🕰️',
    build: function (host) {
      function tube(id) {
        var h = '';
        for (var i = 0; i < 10; i++) h += '<div class="nix2-digit" data-d="' + i + '">' + i + '</div>';
        return '<div class="nix2-tube" id="' + id + '">' + h + '</div>';
      }
      host.appendChild(el('div', 'face on nix2-face',
        tube('nh2') + tube('nh1') + '<div class="nix2-sep"><i></i><i></i></div>' +
        tube('nm2') + tube('nm1') + '<div class="nix2-sep"><i></i><i></i></div>' +
        tube('ns2') + tube('ns1')));
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      var p = [Math.floor(h / 10), h % 10, Math.floor(n.getMinutes() / 10), n.getMinutes() % 10,
        Math.floor(n.getSeconds() / 10), n.getSeconds() % 10];
      ['nh2', 'nh1', 'nm2', 'nm1', 'ns2', 'ns1'].forEach(function (id, i) {
        var t = document.getElementById(id); if (!t) return;
        var prev = t.getAttribute('data-v');
        var ds = t.children, j;
        for (j = 0; j < 10; j++) {
          var on = (j === p[i]);
          ds[j].classList.toggle('on', on);
          if (on) {
            if (prev !== null && prev != j) { ds[j].style.animation = 'none'; void ds[j].offsetWidth; ds[j].style.animation = ''; }
            ds[j].style.animationDuration = (Math.random() * 1.8 + 1.4).toFixed(2) + 's';
          }
        }
        t.setAttribute('data-v', p[i]);
      });
    }
  });
})();
