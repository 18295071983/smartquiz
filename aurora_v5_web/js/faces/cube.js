/* 极光时钟 · faces/cube.js —— 3D 立方体翻页钟（源自 GitHub kanizadev/p-1 Cube Clock） */
(function () {
  var _t = null;
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  function Cube(el) {
    this.el = el;
    this.setFront = function (v) { el.children[0].textContent = pad2(v); };
    this.setUpper = function (v) { el.children[1].childNodes[0].nodeValue = pad2(v); };
    this.rotate = function () { el.children[1].children[0].classList.add('transparent'); el.classList.add('rotate'); };
    this.reset = function () { el.children[1].children[0].classList.remove('transparent'); el.classList.remove('rotate'); };
  }
  ACFace('cube', {
    n: '3D立方',
    i: '🧊',
    build: function (host) {
      if (_t) { clearInterval(_t); _t = null; }
      host.innerHTML =
        '<div class="face on cube-face">' +
        '<div class="cube-room"><div class="cube-back"></div><div class="cube-shade"></div><div class="cube-shade"></div></div>' +
        '<div class="cube-row">' +
        '<div class="cube" id="cub-h"><div class="front-surface">00</div><div class="upper-surface">00<div class="hidden-surface"></div></div><div class="left-surface"></div><div class="right-surface"></div></div>' +
        '<div class="colon">:</div>' +
        '<div class="cube" id="cub-m"><div class="front-surface">00</div><div class="upper-surface">00<div class="hidden-surface"></div></div><div class="left-surface"></div><div class="right-surface"></div></div>' +
        '<div class="colon">:</div>' +
        '<div class="cube" id="cub-s"><div class="front-surface">00</div><div class="upper-surface">00<div class="hidden-surface"></div></div><div class="left-surface"></div><div class="right-surface"></div></div>' +
        '</div></div>';
      var hour = new Cube(document.getElementById('cub-h'));
      var minute = new Cube(document.getElementById('cub-m'));
      var second = new Cube(document.getElementById('cub-s'));
      var flip = false, prevH = -1, prevM = -1;
      _t = setInterval(function () {
        var d = new Date(), h = d.getHours(), m = d.getMinutes(), s = d.getSeconds();
        if (!flip) {
          second.setUpper(s); second.setFront(s ? s - 1 : 59); second.rotate();
          if (m !== prevM) { minute.setUpper(m); minute.setFront(m ? m - 1 : 59); minute.rotate(); }
          if (h !== prevH) { hour.setUpper(h); hour.setFront(h ? h - 1 : 23); hour.rotate(); }
        } else {
          second.setFront(s); second.reset();
          if (m !== prevM) { minute.setFront(m); minute.reset(); }
          if (h !== prevH) { hour.setFront(h); hour.reset(); }
          prevH = h; prevM = m;
        }
        flip = !flip;
      }, 500);
    },
    paint: function () { }
  });
})();
