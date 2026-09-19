/* 极光时钟 v5 · faces/digital.js —— 极简大数字（外置表盘） */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  ACFace('dig', {
    n: '极简',
    i: '🔢',
    build: function (host) {
      host.appendChild(el('div', 'face on',
        '<div class="dgt mono" id="f-dig">00:00<span class="sm" id="f-digs">00</span></div>' +
        '<div class="dgt-line"></div><div class="dgt-date" id="f-digd"></div>'));
    },
    paint: function (n, S) {
      var h = n.getHours(), m = n.getMinutes(), s = n.getSeconds();
      var H = S.h24 ? h : hour12(h);
      var hs = pad2(H), ms = pad2(m), ss = pad2(s);
      var dig = $('#f-dig');
      if (dig) dig.firstChild.nodeValue = hs + ':' + ms;
      var ds = $('#f-digs');
      if (ds) { ds.textContent = S.showSec ? ss : ''; ds.style.display = S.showSec ? '' : 'none'; }
      var dd = $('#f-digd');
      if (dd) dd.textContent = n.getFullYear() + '年' + (n.getMonth() + 1) + '月' + n.getDate() + '日 星期' + '日一二三四五六'[n.getDay()];
    }
  });
})();
