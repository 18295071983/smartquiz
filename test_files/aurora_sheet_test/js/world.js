/* =====================================================================
   极光时钟 v5 · js/world.js —— 世界时钟模块
   ===================================================================== */
function tzOffsetMin(tz, date) {
  try {
    var f = new Intl.DateTimeFormat('en-US', { timeZone: tz, hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' });
    var p = {}; f.formatToParts(date).forEach(function (x) { p[x.type] = x.value; });
    var asUTC = Date.UTC(+p.year, +p.month - 1, +p.day, (+p.hour) % 24, +p.minute, +p.second);
    return Math.round((asUTC - Math.floor(date.getTime() / 1000) * 1000) / 60000);
  } catch (e) { return -date.getTimezoneOffset(); }
}
function tzTime(tz, d) {
  try {
    return new Intl.DateTimeFormat('zh-CN', { timeZone: tz, hour12: !S.h24, hour: '2-digit', minute: '2-digit', second: S.showSec ? '2-digit' : undefined })
      .format(d).replace('上午', 'AM ').replace('下午', 'PM ');
  } catch (e) { return pad(d.getHours()) + ':' + pad(d.getMinutes()); }
}
function tzHour(tz, d) { try { return +new Intl.DateTimeFormat('en-US', { timeZone: tz, hour12: false, hour: '2-digit' }).format(d); } catch (e) { return d.getHours(); } }

function renderWorld() {
  var box = $('#worldList'); if (!box) return; var now = new Date(), lo = -now.getTimezoneOffset();
  box.innerHTML = '';
  S.cities.forEach(function (tz, idx) {
    var c = CITIES.filter(function (x) { return x.tz === tz; })[0] || { n: tz, tz: tz };
    var off = tzOffsetMin(tz, now), diff = Math.round((off - lo) / 60 * 10) / 10;
    var hn = tzHour(tz, now), icon = (hn >= 6 && hn < 18) ? '☀️' : '🌙';
    var d = el('div', 'city');
    d.innerHTML = '<div class="city-l"><div class="city-n">' + icon + ' ' + c.n + '</div>' +
      '<div class="city-s">' + (diff === 0 ? '与本地同时区' : (diff > 0 ? '快 ' + diff + ' 小时' : '慢 ' + Math.abs(diff) + ' 小时')) + '</div></div>' +
      '<div class="city-r"><div class="city-t" data-tz="' + tz + '">' + tzTime(tz, now) + '</div>' +
      '<div class="city-d">' + (tz.split('/')[0] || '') + '</div></div>' +
      '<button class="city-del" data-del="' + idx + '">✕</button>';
    var del = $('[data-del]', d);
    del.addEventListener('click', function (e) { e.stopPropagation(); S.cities.splice(idx, 1); saveState(); renderWorld(); toast('已移除 ' + c.n); });
    box.appendChild(d);
  });
  if (!S.cities.length) box.appendChild(el('div', 'empty', '还没有城市 · 点右上角添加'));
}

function cityPicker() {
  var d = el('div'); var extra = CITIES.filter(function (c) { return S.cities.indexOf(c.tz) < 0; });
  d.innerHTML = '<div class="fld"><label>点击添加（' + (extra.length ? extra.length + ' 个可选' : '已全部添加') + '）</label>' +
    '<div class="list" id="cityPick" style="max-height:calc(var(--ch)*.56);overflow:auto">' +
    extra.map(function (c) {
      var now = new Date(); var off = tzOffsetMin(c.tz, now), lo = -now.getTimezoneOffset(), diff = Math.round((off - lo) / 60 * 10) / 10;
      return '<div class="city" data-tz="' + c.tz + '"><div class="city-l"><div class="city-n">' + c.n + '</div>' +
        '<div class="city-s">' + (diff === 0 ? '同时区' : (diff > 0 ? '快 ' + diff + 'h' : '慢 ' + Math.abs(diff) + 'h')) + '</div></div>' +
        '<div class="city-r"><div class="city-t">' + tzTime(c.tz, now) + '</div></div></div>';
    }).join('') + '</div></div>';
  openSheet('添加城市', d, function (root) {
    $$('#cityPick .city', root).forEach(function (c) {
      c.addEventListener('click', function () {
        S.cities.push(c.getAttribute('data-tz')); saveState(); closeSheet(); renderWorld(); AU.swipe(); toast('已添加');
      });
    });
  });
}
