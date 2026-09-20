/* 极光时钟 · faces/flk.js —— 3D翻页·金（源自 GitHub Viyan852/fliko） */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  function slotHtml(id) {
    return '<div class="flk-slot" id="' + id + '"><div class="flk-top"><b>00</b></div><div class="flk-bot"><b>00</b></div></div>';
  }
  function flkSet(id, val) {
    var slot = document.getElementById(id); if (!slot) return;
    var top = slot.querySelector('.flk-top'), bot = slot.querySelector('.flk-bot');
    var cur = parseInt(slot.getAttribute('data-v') || '-1', 10);
    if (cur === val) return;
    function b(e2, v) { e2.querySelector('b').textContent = pad2(v); }
    b(top, cur === -1 ? val : cur); b(bot, val);
    if (cur !== -1) {
      top.classList.remove('anim'); bot.classList.remove('anim');
      void top.offsetWidth;
      top.classList.add('anim'); bot.classList.add('anim');
    }
    slot.setAttribute('data-v', val);
  }
  ACFace('flk', {
    n: '3D翻·金',
    i: '🧊',
    build: function (host) {
      host.appendChild(el('div', 'face on flk-face',
        slotHtml('fh2') + slotHtml('fh1') + '<div class="flk-sep"><i></i><i></i></div>' +
        slotHtml('fm2') + slotHtml('fm1') + '<div class="flk-sep"><i></i><i></i></div>' +
        slotHtml('fs2') + slotHtml('fs1')));
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      var v = [Math.floor(h / 10), h % 10, Math.floor(n.getMinutes() / 10), n.getMinutes() % 10,
        Math.floor(n.getSeconds() / 10), n.getSeconds() % 10];
      ['fh2', 'fh1', 'fm2', 'fm1', 'fs2', 'fs1'].forEach(function (id, i) { flkSet(id, v[i]); });
    }
  });
})();
