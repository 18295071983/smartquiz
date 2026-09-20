/* 极光时钟 · faces/binary.js —— 二进制 LED 矩阵钟（源自 GitHub AE0L/html5-binary-clock） */
(function () {
  function set(n) {
    var v = $('#b-' + n);
    if (v) { v.classList.remove('off'); }
  }
  function clr(n) {
    var v = $('#b-' + n);
    if (v) { v.classList.add('off'); }
  }
  /* 十进制 → 二进制位：ids=[bit4,bit2,bit1] 或 [bit8,bit4,bit2,bit1] */
  function writeBits(bits, val) {
    for (var i = 0; i < bits.length; i++) {
      var on = !!(val & (1 << i));
      if (on) set(bits[i]); else clr(bits[i]);
    }
  }
  ACFace('bin', {
    n: '二进制',
    i: '👾',
    build: function (host) {
      host.appendChild(el('div', 'face on bin-face',
        '<div class="bin-grid" id="bin-grid">' +
        '<div class="bulb off" id="b-h18"></div><div class="bulb off" id="b-h14"></div>' +
        '<div class="bulb off" id="b-h02"></div><div class="bulb off" id="b-h01"></div>' +
        '<div class="bulb off" id="b-h12"></div><div class="bulb off" id="b-h11"></div>' +
        '<div class="bulb off" id="b-m18"></div><div class="bulb off" id="b-m04"></div>' +
        '<div class="bulb off" id="b-m02"></div><div class="bulb off" id="b-m01"></div>' +
        '<div class="bulb off" id="b-m14"></div><div class="bulb off" id="b-m12"></div>' +
        '<div class="bulb off" id="b-m11"></div>' +
        '<div class="bulb off" id="b-s18"></div><div class="bulb off" id="b-s04"></div>' +
        '<div class="bulb off" id="b-s02"></div><div class="bulb off" id="b-s01"></div>' +
        '<div class="bulb off" id="b-s14"></div><div class="bulb off" id="b-s12"></div>' +
        '<div class="bulb off" id="b-s11"></div>' +
        '</div>' +
        '<div class="bin-hint"><b>H</b> 时 &nbsp;·&nbsp; <b>M</b> 分 &nbsp;·&nbsp; <b>S</b> 秒</div>'));
    },
    paint: function (n, S) {
      var h = S.h24 ? n.getHours() : hour12(n.getHours());
      var m = n.getMinutes(), s = n.getSeconds();
      var h1 = Math.floor(h / 10), h0 = h % 10;
      var m1 = Math.floor(m / 10), m0 = m % 10;
      var s1 = Math.floor(s / 10), s0 = s % 10;
      /* 十位最多 2 bit（0-2），个位 4 bit */
      writeBits(['h01', 'h02'], h0);
      writeBits(['h11', 'h12', 'h14', 'h18'], h1);
      writeBits(['m01', 'm02', 'm04'], m0);
      writeBits(['m11', 'm12', 'm14', 'm18'], m1);
      writeBits(['s01', 's02', 's04'], s0);
      writeBits(['s11', 's12', 's14', 's18'], s1);
    }
  });
})();
