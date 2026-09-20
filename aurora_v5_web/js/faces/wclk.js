/* 极光时钟 · faces/wclk.js —— 文字钟（源自 GitHub f1337/wordclock，去 jQuery 重写） */
(function () {
  /* 12×12 字母网格（词内重复字母占据位） */
  var GRID = [
    'ONETHREEFIVE', 'TWOFOURSEVEN', 'SIXEIGHTNINE', 'ELEVENTWELVE',
    'TENTEELFMTEN', 'TWENTYTWELVE', 'THIRTYZFORTY', 'FIFTY’CLOCK',
    'ONETHREEFIVE', 'TWOFOURSEVEN', 'SIXEIGHTNINE', 'ELEVENX—TEEN'
  ];
  /* [行, 左起, 长度]（1-based） */
  var HOURS = [[1,1,3],[2,1,3],[1,4,5],[2,4,4],[1,9,4],[3,1,3],[2,8,5],[3,4,5],[3,9,4],[5,1,3],[4,1,6],[4,7,6]];
  var MTENS = [[8,6,1],[12,8,5],[6,1,6],[7,1,6],[7,8,5],[8,1,5]];
  var MONES = [[8,7,6],[9,1,3],[10,1,3],[9,4,5],[10,4,4],[9,9,4],[11,1,3],[10,8,5],[11,4,5],[11,9,4],
    [5,10,3],[12,1,6],[6,7,6],[7,1,5],[10,4,4],[8,1,3],[11,1,3],[10,8,5],[11,4,5],[11,9,4]];
  var cells = [];
  function idxs(h, mt, mo) {
    var out = [], r, c, l, i;
    function add(a) { for (i = 0; i < a[2]; i++) out.push((a[0] - 1) * 12 + (a[1] - 1) + i); }
    add(h);
    if (!(mt && mo === 0)) add(mo);
    if (mo < 10 || mo > 12) add(mt);
    return out;
  }
  ACFace('wclk', {
    n: '文字钟',
    i: '🔤',
    build: function (host) {
      cells = [];
      var html = '<div class="face on wclk-face"><div class="wclk-grid">';
      GRID.forEach(function (row) {
        for (var i = 0; i < row.length; i++) {
          cells.push(i);
          html += '<span>' + row.charAt(i) + '</span>';
        }
      });
      html += '</div></div>';
      host.appendChild(el('div', '', html));
      cells = host.querySelectorAll('.wclk-grid span');
    },
    paint: function (n) {
      var h = n.getHours() - 1; if (h === -1) h = 11; if (h > 11) h -= 12;
      var m = n.getMinutes(), mt = Math.floor(m / 10), mo = m % 10;
      if (mt === 1) { mt = (mo < 3 ? 0 : 1); mo += 10; }
      var on = idxs(HOURS[h], MTENS[mt], MONES[mo]);
      for (var i = 0; i < cells.length; i++) {
        cells[i].classList.toggle('on', on.indexOf(i) > -1);
      }
    }
  });
})();
