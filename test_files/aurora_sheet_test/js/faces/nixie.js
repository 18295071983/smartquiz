/* =====================================================================
   极光时钟 v5 · faces/nixie.js —— 辉光管（外置表盘 · 升级版）
   参考成熟实现思路（@glowbox/nixie 矢量丝 + UD5 Retro Clock）：
   - 每数字一枚"玻璃管"：径向渐变模拟凸起玻璃 + 内阴影
   - 数字暖橙多层发光（text-shadow 堆叠）＝ 真实辉光晕
   - 整管微闪烁动画（flicker）+ 数字切换瞬间灯丝强闪
   纯 CSS/DOM，零外部依赖，适配 WebView。
   ===================================================================== */
(function () {
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }

  function buildTubes(host) {
    var wrap = el('div', 'face on nix-wrap');
    var tubes = el('div', 'nix-tubes mono');
    tubes.id = 'f-nix';
    // 6 个数字管 + 1 个冒号管（HH:MM）
    var i;
    for (i = 0; i < 6; i++) {
      var t = el('div', 'nix-tube');
      t.id = 'nx-' + i;
      t.innerHTML = '<span class="nix-num">0</span><i class="nix-glass"></i>';
      tubes.appendChild(t);
    }
    var colon = el('div', 'nix-colon');
    colon.id = 'nx-colon';
    colon.innerHTML = '<i></i><i></i>';
    tubes.appendChild(colon);
    wrap.appendChild(tubes);
    wrap.appendChild(el('div', 'nix-sub', '<span id="f-nixs">AURORA CLOCK</span><span class="nix-sec mono" id="f-nixsec"></span>'));
    host.appendChild(wrap);
  }

  function setTube(id, ch, flick) {
    var t = document.getElementById(id);
    if (!t) return;
    var sp = t.querySelector('.nix-num');
    if (!sp) return;
    if (sp.textContent !== ch) {
      sp.textContent = ch;
      if (flick) { t.classList.remove('flick'); void t.offsetWidth; t.classList.add('flick'); }
    }
  }

  ACFace('nix', {
    n: '辉光管',
    i: '🔶',
    build: function (host) { buildTubes(host); },
    paint: function (n, S) {
      var h = n.getHours(), m = n.getMinutes(), s = n.getSeconds();
      var H = S.h24 ? h : hour12(h);
      var hs = pad2(H), ms = pad2(m), ss = pad2(s);
      var chars = (hs + ms).split('');
      chars.forEach(function (ch, i) { setTube('nx-' + i, ch, true); });
      var colon = $('#nx-colon');
      if (colon) colon.classList.toggle('off', !S.showSec);
      var sub = $('#f-nixs');
      if (sub) {
        var dk = (h < 6) ? '凌晨' : (h < 12) ? '早上' : (h < 14) ? '中午' : (h < 18) ? '下午' : (h < 22) ? '晚上' : '深夜';
        sub.textContent = dk + ' · NIXIE TUBE';
      }
      var sec = $('#f-nixsec');
      if (sec) sec.textContent = S.showSec ? (':' + ss) : '';
    }
  });
})();
