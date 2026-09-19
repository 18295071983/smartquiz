/* 极光时钟 v5 · faces/ring.js —— 环形进度表盘（外置表盘，时/分/秒三环） */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  ACFace('ring', {
    n: '环形',
    i: '💫',
    build: function (host) {
      host.appendChild(el('div', 'face on',
        '<div class="ringface"><svg viewBox="0 0 200 200">' +
        '<circle class="rc-core" cx="100" cy="100" r="62"></circle>' +
        '<circle class="rc-bg" cx="100" cy="100" r="88" stroke-width="7"></circle>' +
        '<circle class="rc-h" id="rh" cx="100" cy="100" r="88" stroke-width="7" stroke-dasharray="553"></circle>' +
        '<circle class="rc-bg" cx="100" cy="100" r="76" stroke-width="8"></circle>' +
        '<circle class="rc-m" id="rm" cx="100" cy="100" r="76" stroke-width="8" stroke-dasharray="477"></circle>' +
        '<circle class="rc-bg" cx="100" cy="100" r="64" stroke-width="6"></circle>' +
        '<circle class="rc-s" id="rs" cx="100" cy="100" r="64" stroke-width="6" stroke-dasharray="402"></circle>' +
        '</svg><div class="rf-txt"><div class="t mono" id="rfT">00:00</div><div class="s" id="rfS">AURORA</div></div></div>'));
    },
    paint: function (n, S) {
      var h = n.getHours(), m = n.getMinutes(), s = n.getSeconds();
      var H = S.h24 ? h : hour12(h);
      var hs = pad2(H), ms = pad2(m), ss = pad2(s);
      var dk = (h < 6) ? '凌晨' : (h < 12) ? '早上' : (h < 14) ? '中午' : (h < 18) ? '下午' : (h < 22) ? '晚上' : '深夜';
      var C = [553, 477, 402];
      var rh = $('#rh'), rm = $('#rm'), rs = $('#rs');
      if (rh) rh.setAttribute('stroke-dashoffset', C[0] * (1 - ((h % 12) + m / 60) / 12));
      if (rm) rm.setAttribute('stroke-dashoffset', C[1] * (1 - (m + (S.smooth ? s / 60 : 0)) / 60));
      if (rs) rs.setAttribute('stroke-dashoffset', C[2] * (1 - s / 60));
      var rt = $('#rfT');
      if (rt) rt.textContent = hs + ':' + ms;
      var rsl = $('#rfS');
      if (rsl) rsl.textContent = S.showSec ? (ss + ' 秒 · ' + dk) : dk;
    }
  });
})();
