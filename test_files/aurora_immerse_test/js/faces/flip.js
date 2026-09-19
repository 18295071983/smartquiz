/* =====================================================================
   极光时钟 v5 · faces/flip.js —— 翻页钟（外置表盘）
   经典翻页叶动画：上半页翻下 + 下半页翻起（CSS 3D rotateX，参考 flip.js 成熟思路）
   ===================================================================== */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }

  function flipSet(id, val) {
    var d = $('#fd-' + id);
    if (!d) return;
    var old = d.getAttribute('data-v');
    if (old === val) return;
    d.setAttribute('data-v', val);
    if (old === null) { $$('.half span', d).forEach(function (s) { s.textContent = val; }); return; }
    $('.leaf-u span', d).textContent = old;
    $('.leaf-l span', d).textContent = val;
    $('.half.up span', d).textContent = val;
    $('.half.dn span', d).textContent = val;
    d.classList.remove('go');
    void d.offsetWidth;
    d.classList.add('go');
  }

  function flipBar(s) {
    var bi = document.querySelector('#f-flip .flip-bar i');
    if (bi) bi.style.width = (s / 60 * 100).toFixed(1) + '%';
  }

  function flipRelayout() {
    var w = document.getElementById('f-flip');
    if (!w) return;
    var sec = document.getElementById('f-sec');
    var row = w.querySelector('.flip-row'), strip = w.querySelector('.flip-strip');
    if (document.body.classList.contains('landscape')) {
      if (!row) {
        row = el('div', 'flip-row');
        strip = el('div', 'flip-strip');
        Array.prototype.slice.call(w.children).forEach(function (n) { if (n !== sec) row.appendChild(n); });
        w.appendChild(row);
        w.appendChild(strip);
        if (sec) strip.appendChild(sec);
        strip.appendChild(el('div', 'flip-bar', '<i></i>'));
      }
    } else if (row) {
      Array.prototype.slice.call(row.children).forEach(function (n) { w.appendChild(n); });
      if (sec) w.appendChild(sec);
      row.parentNode.removeChild(row);
      if (strip && strip.parentNode) strip.parentNode.removeChild(strip);
    }
  }

  ACFace('flip', {
    n: '翻页钟',
    i: '🎴',
    build: function (host) {
      var w = el('div', 'face on flip-wrap');
      w.id = 'f-flip';
      var ci = ['h1', 'h2', ':', 'm1', 'm2'];
      ci.forEach(function (id) {
        if (id === ':') { w.appendChild(el('div', 'flip-colon', ':')); return; }
        var d = el('div', 'fd');
        d.id = 'fd-' + id;
        d.innerHTML = '<div class="half up"><span>0</span></div><div class="half dn"><span>0</span></div>' +
          '<div class="leaf leaf-u"><span>0</span></div><div class="leaf leaf-l"><span>0</span></div>';
        w.appendChild(d);
      });
      var sc = el('div', 'flip-sec mono');
      sc.id = 'f-sec';
      w.appendChild(sc);
      host.appendChild(w);
      flipRelayout();
    },
    paint: function (n, S) {
      var h = n.getHours(), m = n.getMinutes(), s = n.getSeconds();
      var H = S.h24 ? h : hour12(h);
      var hs = pad2(H), ms = pad2(m), ss = pad2(s);
      flipSet('h1', hs[0]); flipSet('h2', hs[1]);
      flipSet('m1', ms[0]); flipSet('m2', ms[1]);
      var sc = $('#f-sec');
      if (sc) sc.textContent = S.showSec ? (ss + 's') : '';
      flipBar(s);
    },
    relayout: function () { flipRelayout(); }
  });
})();
