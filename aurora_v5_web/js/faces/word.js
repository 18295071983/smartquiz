/* 极光时钟 v5 · faces/word.js —— 中文文字钟（外置表盘） */
(function () {
  function cnNum(x) {
    var cn = ['零', '一', '二', '三', '四', '五', '六', '七', '八', '九'];
    var ten = ['十', '十一', '十二', '十三', '十四', '十五', '十六', '十七', '十八', '十九'];
    if (x < 10) return cn[x];
    if (x < 20) return ten[x - 10];
    var a = Math.floor(x / 10), b = x % 10;
    return cn[a] + '十' + (b ? cn[b] : '');
  }
  ACFace('word', {
    n: '文字钟',
    i: '📝',
    build: function (host) {
      host.appendChild(el('div', 'face on words',
        '<div class="big" id="f-word">现在是 <b>凌晨</b> 十二点整</div>' +
        '<div class="wseq" id="f-wseq"></div><div class="daybar"><i id="dayBar" style="width:0%"></i></div>'));
    },
    paint: function (n, S) {
      var h = n.getHours(), m = n.getMinutes(), s = n.getSeconds();
      var dk = (h < 6) ? '凌晨' : (h < 12) ? '早上' : (h < 14) ? '中午' : (h < 18) ? '下午' : (h < 22) ? '晚上' : '深夜';
      var zh = (S.h24 ? cnNum(h) : cnNum(hour12(h))) + '点' + (m === 0 ? '整' : cnNum(m) + '分');
      var txt = '现在是 <b>' + dk + '</b> ' + zh;
      var we = $('#f-word');
      if (we) we.innerHTML = txt;
      var seq = $('#f-wseq');
      if (seq) {
        if (!seq.children.length) {
          ['凌晨', '早上', '中午', '下午', '晚上', '深夜'].forEach(function (x) { seq.appendChild(el('span', '', x)); });
        }
        $$('span', seq).forEach(function (sp) { sp.classList.toggle('hot', sp.textContent === dk); });
      }
      var db = $('#dayBar');
      if (db) db.style.width = (s / 60 * 100).toFixed(2) + '%';
    }
  });
})();
