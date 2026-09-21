/* =====================================================================
   极光时钟 v5 · js/weather.js —— 天气模块
   数据源：中国天气网（内置城市码，AB 桥无 CORS）→ open-meteo 兜底
   依赖：core.js / app.js（$、$$、el、B、toast、haptic、store、openSheet、closeSheet）
   ===================================================================== */
var WEATHER = (function () {
  'use strict';

  /* ---------- 内置城市（省会 + 常用，含坐标可兜底 open-meteo） ---------- */
  var PRESET = [
    { n: '银川', lat: 38.421392, lon: 106.263461, code: '101170101' }, { n: '中卫', lat: 37.5002, lon: 105.1968, code: '101170501' },
    { n: '吴忠', lat: 37.9869, lon: 106.1988, code: '101170301' }, { n: '石嘴山', lat: 38.9843, lon: 106.3844, code: '101170201' },
    { n: '固原', lat: 36.0154, lon: 106.2423, code: '101170401' }, { n: '北京', lat: 39.9042, lon: 116.4074, code: '101010100' },
    { n: '上海', lat: 31.2304, lon: 121.4737, code: '101020100' }, { n: '天津', lat: 39.3434, lon: 117.3616, code: '101030100' },
    { n: '重庆', lat: 29.5630, lon: 106.5516, code: '101040100' }, { n: '广州', lat: 23.1291, lon: 113.2644, code: '101280101' },
    { n: '深圳', lat: 22.5431, lon: 114.0579, code: '101280601' }, { n: '成都', lat: 30.5728, lon: 104.0668, code: '101270101' },
    { n: '西安', lat: 34.3416, lon: 108.9398, code: '101110101' }, { n: '杭州', lat: 30.2741, lon: 120.1551, code: '101210101' },
    { n: '武汉', lat: 30.5928, lon: 114.3055, code: '101200101' }, { n: '南京', lat: 32.0603, lon: 118.7969, code: '101190101' },
    { n: '乌鲁木齐', lat: 43.8256, lon: 87.6168, code: '101130101' }, { n: '兰州', lat: 36.0611, lon: 103.8343, code: '101160101' },
    { n: '郑州', lat: 34.7466, lon: 113.6254, code: '101180101' }, { n: '长沙', lat: 28.2282, lon: 112.9388, code: '101250101' },
    { n: '昆明', lat: 24.8801, lon: 102.8329, code: '101290101' }, { n: '哈尔滨', lat: 45.8038, lon: 126.5350, code: '101050101' },
    { n: '三亚', lat: 18.2528, lon: 109.5119, code: '101310201' }, { n: '青岛', lat: 36.0662, lon: 120.3826, code: '101120201' },
    { n: '厦门', lat: 24.4798, lon: 118.0894, code: '101230201' }, { n: '拉萨', lat: 29.6520, lon: 91.1721, code: '101140101' },
    { n: '呼和浩特', lat: 40.8414, lon: 111.7519, code: '101080101' }, { n: '西宁', lat: 36.6171, lon: 101.7782, code: '101150101' },
    { n: '沈阳', lat: 41.8057, lon: 123.4315, code: '101070101' }, { n: '石家庄', lat: 38.0428, lon: 114.5149, code: '101090101' },
    { n: '太原', lat: 37.8706, lon: 112.5489, code: '101100101' }, { n: '济南', lat: 36.6512, lon: 117.1201, code: '101120101' },
    { n: '长春', lat: 43.8171, lon: 125.3235, code: '101060101' }, { n: '合肥', lat: 31.8206, lon: 117.2272, code: '101220101' },
    { n: '福州', lat: 26.0745, lon: 119.2965, code: '101230101' }, { n: '南昌', lat: 28.6820, lon: 115.8579, code: '101240101' },
    { n: '南宁', lat: 22.8170, lon: 108.3665, code: '101300101' }, { n: '海口', lat: 20.0442, lon: 110.1999, code: '101310101' },
    { n: '贵阳', lat: 26.6470, lon: 106.6302, code: '101260101' }, { n: '香港', lat: 22.3193, lon: 114.1694, code: '101320101' }
  ];

  /* ---------- 网络：优先壳原生桥（无 CORS），否则 fetch ---------- */
  var wxSeq = 0;
  function wxNet(url, cb) {
    var done = false;
    var timer = setTimeout(function () { if (!done) { done = true; cb(new Error('请求超时')); } }, 10000);
    function fin(err, text) { if (done) return; done = true; clearTimeout(timer); cb(err, text); }
    var b = B();
    if (b && typeof b.request === 'function') {
      var name = '__wxcb' + (++wxSeq) + '_' + Date.now();
      window[name] = function (r) {
        try { delete window[name]; } catch (e) { window[name] = undefined; }
        if (r && r.status >= 200 && r.status < 300) { fin(null, r.body); }
        else { fin(new Error((r && (r.error || ('HTTP ' + r.status))) || '网络错误')); }
      };
      try { b.request(url, 'GET', '{}', '', name); } catch (e) { fin(e); }
    } else if (window.fetch) {
      fetch(url, { cache: 'no-store' }).then(function (res) {
        return res.text().then(function (txt) {
          if (res.ok) { fin(null, txt); } else { fin(new Error('HTTP ' + res.status)); }
        });
      }).catch(function (e) { fin(e); });
    } else { fin(new Error('当前环境不支持网络请求')); }
  }
  function wxJSON(url, cb) {
    wxNet(url, function (err, text) {
      if (err) { return cb(err); }
      try { cb(null, JSON.parse(text)); } catch (e) { cb(new Error('数据解析失败')); }
    });
  }

  /* ---------- 天气文字 → 图标 ---------- */
  var WMO = { 0: ['晴', '☀️'], 1: ['晴间多云', '🌤️'], 2: ['多云', '⛅'], 3: ['阴', '☁️'], 45: ['有雾', '🌫️'], 48: ['雾凇', '🌫️'],
    51: ['小毛毛雨', '🌦️'], 53: ['毛毛雨', '🌦️'], 55: ['大毛毛雨', '🌧️'], 56: ['冻毛毛雨', '🌧️'], 57: ['冻毛毛雨', '🌧️'],
    61: ['小雨', '🌦️'], 63: ['中雨', '🌧️'], 65: ['大雨', '🌧️'], 66: ['冻雨', '🌧️'], 67: ['冻雨', '🌧️'],
    71: ['小雪', '🌨️'], 73: ['中雪', '🌨️'], 75: ['大雪', '❄️'], 77: ['雪粒', '🌨️'],
    80: ['小阵雨', '🌦️'], 81: ['阵雨', '🌧️'], 82: ['强阵雨', '⛈️'], 85: ['小阵雪', '🌨️'], 86: ['强阵雪', '❄️'],
    95: ['雷阵雨', '⛈️'], 96: ['雷阵雨伴冰雹', '⛈️'], 99: ['强雷暴冰雹', '⛈️'] };
  function wx(code) { return WMO[code] || ['未知', '🌡️']; }
  function cnWmo(t) {
    t = String(t || '');
    if (t.indexOf('雷') >= 0) return 95;
    if (t.indexOf('雪') >= 0) { if (t.indexOf('暴') >= 0 || t.indexOf('大') >= 0) return 75; if (t.indexOf('中') >= 0) return 73; return 71; }
    if (t.indexOf('雨') >= 0) { if (t.indexOf('暴') >= 0 || t.indexOf('大') >= 0) return 65; if (t.indexOf('中') >= 0) return 63; if (t.indexOf('阵') >= 0) return 81; return 61; }
    if (t.indexOf('雾') >= 0 || t.indexOf('霾') >= 0 || t.indexOf('沙') >= 0 || t.indexOf('尘') >= 0) return 45;
    if (t.indexOf('阴') >= 0) return 3;
    if (t.indexOf('云') >= 0) return t.indexOf('晴') >= 0 ? 1 : 2;
    if (t.indexOf('晴') >= 0) return 0;
    return 2;
  }
  function numOf(x) { var v = parseFloat(String(x == null ? '' : x).replace(/[^0-9.\-]/g, '')); return isNaN(v) ? null : v; }
  function grabVar(txt, name) {
    var m = new RegExp('var ' + name + '\\s*=\\s*(\\{[\\s\\S]*?\\});').exec(txt);
    if (!m) return null;
    try { return JSON.parse(m[1]); } catch (e) { return null; }
  }
  function r0(v) { return (v === null || v === undefined || isNaN(v)) ? '--' : Math.round(v); }

  /* ---------- 状态 ---------- */
  var K_PLACE = 'wx_place';
  var W = { place: null, data: null, busy: false, show: true };

  function readPlace() {
    try {
      var s = store(K_PLACE); if (s) { var p = JSON.parse(s); if (p && (p.code || p.lat)) return p; }
    } catch (e) { }
    return null;
  }
  function savePlace(p) { try { store(K_PLACE, JSON.stringify(p)); } catch (e) { } }

  /* ---------- 数据获取：itboy 取预报 + open-meteo 取当前温度（有坐标时） ---------- */
  function fetchCn(place, cb) {
    var code = place.code;
    if (!code) { return cb(new Error('无城市码')); }
    wxJSON('http://t.weather.itboy.net/api/weather/city/' + code, function (e, d) {
      if (e || !d || d.status !== 200 || !d.data) { return cb(e || new Error('国内接口无响应')); }
      var dd = d.data;
      var fc = (dd.forecast || [])[0] || {};
      var sk = { temp: dd.wendu || fc.high, weather: fc.type || '', SD: dd.shidu || '', WS: fc.fl || '' };
      var fb = { data: { forecast: (dd.forecast || []).map(function (f) {
        return { type: f.type, high: f.high, low: f.low };
      }) } };
      var extra = {
        shidu: dd.shidu, pm25: dd.pm25, pm10: dd.pm10, quality: dd.quality, ganmao: dd.ganmao,
        sunrise: fc.sunrise, sunset: fc.sunset, fx: fc.fx, fl: fc.fl, aqi: fc.aqi, notice: fc.notice,
        city: d.cityInfo && d.cityInfo.city, parent: d.cityInfo && d.cityInfo.parent
      };
      /* 有坐标时，用 open-meteo 取更准的当前温度 */
      if (place.lat != null && place.lon != null) {
        wxJSON('https://api.open-meteo.com/v1/forecast?latitude=' + place.lat + '&longitude=' + place.lon
          + '&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1',
          function (e2, d2) {
            if (!e2 && d2 && d2.current) {
              sk.temp = d2.current.temperature_2m;
              sk.weather = wx(d2.current.weather_code)[0];
              if (d2.daily && d2.daily.temperature_2m_max) {
                fb.data.forecast[0].high = d2.daily.temperature_2m_max[0];
                fb.data.forecast[0].low = d2.daily.temperature_2m_min[0];
              }
            }
            cb(null, { sk: sk, fb: fb, extra: extra });
          });
      } else {
        cb(null, { sk: sk, fb: fb, extra: extra });
      }
    });
  }

  /* ---------- 兜底：open-meteo（需坐标） ---------- */
  function fetchOm(place, cb) {
    if (place.lat == null || place.lon == null) { return cb(new Error('无坐标')); }
    var u = 'https://api.open-meteo.com/v1/forecast?latitude=' + place.lat + '&longitude=' + place.lon
      + '&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m'
      + '&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=3';
    wxJSON(u, function (e, d) {
      if (e || !d || !d.current) { return cb(e || new Error('取数失败')); }
      cb(null, {
        sk: { temp: d.current.temperature_2m, weather: wx(d.current.weather_code)[0], SD: d.current.relative_humidity_2m, WS: d.current.wind_speed_10m },
        fb: { data: { forecast: (d.daily && d.daily.time || []).map(function (t, i) {
          return { type: wx((d.daily.weather_code || [])[i])[0], high: (d.daily.temperature_2m_max || [])[i], low: (d.daily.temperature_2m_min || [])[i] };
        }) } }
      });
    });
  }

  function fetchWeather(place, cb) {
    fetchCn(place, function (err, st) {
      if (!err && st && st.sk) { return cb(null, st); }
      fetchOm(place, function (e2, d2) {
        if (!e2 && d2) { return cb(null, d2); }
        cb(err || e2 || new Error('取数失败'));
      });
    });
  }

  /* ---------- 城市码表（js/citycodes.js，中国天气网 2501 城） ---------- */
  var _codeMap = null;
  function codeMap() {
    if (_codeMap) return _codeMap;
    _codeMap = {};
    if (typeof CN_CITYCODES === 'string') {
      CN_CITYCODES.split('|').forEach(function (kv) {
        var i = kv.indexOf(':');
        if (i > 0) _codeMap[kv.slice(0, i)] = kv.slice(i + 1);
      });
    }
    return _codeMap;
  }
  /* 按城市名匹配城市码（精确 → 去行政区后缀 → 包含） */
  function cityCode(name) {
    if (!name) return '';
    var m = codeMap();
    if (m[name]) return m[name];
    var short = String(name).replace(/(省|市|自治区|自治州|地区|盟|县|区|自治县|自治旗|特别行政区)$/g, '');
    if (short && m[short]) return m[short];
    for (var k in m) { if (k.indexOf(name) >= 0 || name.indexOf(k) >= 0) return m[k]; }
    return '';
  }
  function searchCities(q) {
    var out = [], seen = {}, m = codeMap();
    q = String(q || '').trim();
    if (!q) return out;
    for (var k in m) {
      if (k.indexOf(q) >= 0 && !seen[m[k]]) { seen[m[k]] = 1; out.push({ n: k, code: m[k] }); }
    }
    return out.slice(0, 60);
  }

  /* ---------- GPS 定位（壳已自动授权 WebView 定位） ---------- */
  function locate(cb) {
    if (!navigator.geolocation) { return cb(new Error('当前环境不支持定位')); }
    var to = setTimeout(function () { cb(new Error('定位超时')); }, 10000);
    try {
      navigator.geolocation.getCurrentPosition(function (pos) {
        clearTimeout(to);
        cb(null, { lat: pos.coords.latitude, lon: pos.coords.longitude });
      }, function (err) {
        clearTimeout(to);
        cb(err || new Error('定位失败'));
      }, { enableHighAccuracy: true, timeout: 9000, maximumAge: 300000 });
    } catch (e) { clearTimeout(to); cb(e); }
  }
  function reverseGeo(lat, lon, cb) {
    wxJSON('https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=' + lat + '&longitude=' + lon + '&localityLanguage=zh', function (err, d) {
      if (err || !d) { return cb(err || new Error('逆地理编码失败')); }
      var city = d.city || d.locality || d.principalSubdivision || d.countryName || '';
      cb(null, city);
    });
  }
  function locateWeather(cb) {
    locate(function (err, pos) {
      if (err || !pos) { return cb(err || new Error('定位失败')); }
      reverseGeo(pos.lat, pos.lon, function (e2, city) {
        if (e2) { return cb(e2); }
        /* 城市名匹配中国天气网城市码：命中就走国内接口（更准），否则按坐标 open-meteo 兜底 */
        var code = cityCode(city);
        cb(null, { name: city || '当前位置', lat: pos.lat, lon: pos.lon, code: code || undefined });
      });
    });
  }

  /* ---------- 渲染 ---------- */
  function render() {
    var box = $('#cmWeather'); if (!box) return;
    var p = W.place, d = W.data;
    if (!p || !d || !d.sk) { return; }
    var sk = d.sk;
    var temp = numOf(sk.temp);
    var wtxt = sk.weather || '';
    var w = wx(cnWmo(wtxt));
    var hi = null, lo = null;
    var fd = (d.fb && d.fb.data && d.fb.data.forecast) || [];
    if (fd[0]) { hi = numOf(fd[0].high); lo = numOf(fd[0].low); }
    var ico = $('#wIco'); if (ico) ico.textContent = w[1];
    var t = $('#wTemp'); if (t) t.textContent = (temp === null || temp === undefined || isNaN(temp)) ? '--°' : r0(temp) + '°';
    var c = $('#wCity'); if (c) c.textContent = p.name || p.n || '';
    var hl = $('#wHL');
    if (hl) {
      if (hi !== null && lo !== null) { hl.textContent = r0(lo) + '~' + r0(hi) + '°'; }
      else if (wtxt) { hl.textContent = w[0]; }
      else { hl.textContent = ''; }
    }
    box.classList.toggle('ready', temp !== null);
    /* wx-extra：小字段横排，无数据隐藏 */
    var ex = d.extra || {};
    var extra = $('#wxExtra');
    if (extra) {
      var items = [];
      if (ex.shidu) items.push('<span class="wx-item">湿度<b>' + ex.shidu + '</b></span>');
      if (ex.fx || ex.fl) items.push('<span class="wx-item">' + (ex.fx || '') + '<b>' + (ex.fl || '') + '</b></span>');
      if (ex.quality) items.push('<span class="wx-item">空气<b>' + ex.quality + '</b></span>');
      if (ex.pm25) items.push('<span class="wx-item">PM2.5<b>' + r0(ex.pm25) + '</b></span>');
      if (ex.sunrise) items.push('<span class="wx-item">日出<b>' + ex.sunrise + '</b></span>');
      if (ex.sunset) items.push('<span class="wx-item">日落<b>' + ex.sunset + '</b></span>');
      extra.innerHTML = items.join('');
      extra.style.display = items.length ? '' : 'none';
    }
  }
  function setLoading() {
    var t = $('#wTemp'); if (t) t.textContent = '…';
  }

  /* ---------- 加载 ---------- */
  function load(place, silent) {
    if (!place) { place = readPlace() || PRESET[0]; }
    if (W.busy) return;
    W.busy = true; W.place = place;
    savePlace(place);
    if (!silent) setLoading();
    fetchWeather(place, function (err, d) {
      W.busy = false;
      if (err || !d || !d.sk) {
        var t = $('#wTemp'); if (t) t.textContent = '--';
        if (!silent) toast('天气获取失败');
        return;
      }
      W.data = d;
      render();
    });
  }

  /* ---------- 详情显示 ---------- */
  function showDetail() {
    var p = W.place, d = W.data;
    if (!p || !d || !d.sk) { toast('暂无天气数据'); return; }
    var sk = d.sk, ex = d.extra || {};
    var temp = numOf(sk.temp);
    var wtxt = sk.weather || '';
    var w = wx(cnWmo(wtxt));
    var hi = null, lo = null;
    var fd = (d.fb && d.fb.data && d.fb.data.forecast) || [];
    if (fd[0]) { hi = numOf(fd[0].high); lo = numOf(fd[0].low); }
    var cityName = p.name || p.n || ex.city || '';
    var rows = [
      ['当前温度', (temp === null ? '--' : r0(temp) + '°')],
      ['天气状况', w[1] + ' ' + w[0]],
      ['温度范围', (hi !== null && lo !== null) ? r0(lo) + '° ~ ' + r0(hi) + '°' : '--'],
      ['湿度', ex.shidu || '--'],
      ['风向风力', (ex.fx ? ex.fx + ' ' : '') + (ex.fl || '--')],
      ['空气质量', ex.quality ? (ex.quality + (ex.aqi ? ' (AQI ' + ex.aqi + ')' : '')) : '--'],
      ['PM2.5', ex.pm25 ? r0(ex.pm25) : '--'],
      ['PM10', ex.pm10 ? r0(ex.pm10) : '--'],
      ['日出', ex.sunrise || '--'],
      ['日落', ex.sunset || '--']
    ];
    var html = '<div class="wx-detail" style="padding:4px 2px">' +
      '<div style="text-align:center;margin-bottom:14px">' +
        '<div style="font-size:13px;color:var(--dim);margin-bottom:2px">' + (ex.parent ? ex.parent + ' · ' : '') + cityName + '</div>' +
        '<div style="font-size:48px;font-weight:200;line-height:1.1">' + (temp === null ? '--' : r0(temp) + '°') + '</div>' +
        '<div style="font-size:14px;opacity:.8;margin-top:2px">' + w[1] + ' ' + w[0] + (hi !== null ? ' · ' + r0(lo) + '~' + r0(hi) + '°' : '') + '</div>' +
      '</div>' +
      '<div style="display:grid;grid-template-columns:1fr 1fr;gap:1px;background:rgba(255,255,255,.06);border-radius:12px;overflow:hidden">' +
      rows.map(function (r) {
        return '<div style="background:rgba(0,0,0,.25);padding:11px 12px">' +
          '<div style="font-size:11px;color:var(--dim);margin-bottom:3px">' + r[0] + '</div>' +
          '<div style="font-size:14px;font-weight:500">' + r[1] + '</div></div>';
      }).join('') +
      '</div>' +
      (ex.notice ? '<div style="margin-top:12px;padding:10px 12px;background:rgba(var(--glow),.08);border-radius:10px;font-size:12.5px;line-height:1.5;color:var(--txt);opacity:.85">' + ex.notice + '</div>' : '') +
      (ex.ganmao ? '<div style="margin-top:8px;padding:10px 12px;background:rgba(255,200,100,.08);border-radius:10px;font-size:12.5px;line-height:1.5;opacity:.85">感冒：' + ex.ganmao + '</div>' : '') +
      '<div style="margin-top:14px;text-align:center;font-size:11px;color:var(--dim)">长按切换城市 · 数据来源：itboy/open-meteo</div>' +
      '</div>';
    openSheet('天气详情 · ' + cityName, html);
    var sh = $('#sheet'); if (sh) sh.classList.add('bottom');
  }

  /* ---------- 城市选择 ---------- */
  function pickCity() {
    var d = el('div');
    var cur = W.place;
    var gps = cur && cur.lat && !cur.code;
    d.innerHTML = '<div class="fld"><label>定位 / 搜索 / 常用城市</label>' +
      '<div class="city" id="wxGps" style="border-color:rgba(var(--glow),.5);margin-bottom:6px">' +
      '<div class="city-l"><div class="city-n">📍 ' + (gps ? '使用当前位置（' + cur.name + '）' : '使用当前位置') + '</div>' +
      '<div class="city-s">GPS 精确定位 · 自动匹配中国天气网城市码</div></div>' +
      (gps ? '<div class="city-r"><span style="color:var(--acc);font-size:11.5px">当前</span></div>' : '') + '</div>' +
      '<div class="wx-search" style="display:flex;gap:6px;margin-bottom:8px">' +
      '<input id="wxQ" placeholder="输入城市名搜索，如：固原 / 敦煌" style="flex:1;min-width:0;background:rgba(255,255,255,.07);border:1px solid var(--line);border-radius:11px;color:var(--txt);font-size:13px;padding:9px 12px;outline:none">' +
      '</div>' +
      '<div class="list" id="wxPick" style="max-height:calc(var(--ch)*.5);overflow:auto">' +
      PRESET.map(function (c) {
        var isCur = cur && ((cur.code && cur.code === c.code) || cur.name === c.n);
        return '<div class="city" data-n="' + c.n + '"><div class="city-l"><div class="city-n">' + c.n + '</div>' +
          '<div class="city-s">' + (c.code ? '中国天气网' : '') + '</div></div>' +
          (isCur ? '<div class="city-r"><span style="color:var(--acc);font-size:11.5px">当前</span></div>' : '') + '</div>';
      }).join('') + '</div></div>';
    openSheet('切换城市 · 天气', d, function (root) {
      var sh = $('#sheet'); if (sh) sh.classList.remove('bottom');
      var gp = $('#wxGps', root);
      if (gp) gp.addEventListener('click', function () {
        closeSheet(); locateWeather(function (err, p) {
          if (err || !p) { toast('定位失败，已保持原城市'); return; }
          W.place = p; W.data = null; load(p); AU.swipe(); toast('已定位：' + p.name);
        });
      });
      var pick = $('#wxPick', root);
      var q = $('#wxQ', root);
      function renderList() {
        var kw = q.value.trim();
        var hits = kw ? searchCities(kw) : [];
        pick.innerHTML = kw
          ? (hits.length
            ? hits.map(function (h) {
                var isCur = cur && cur.code === h.code;
                return '<div class="city" data-n="' + h.n + '" data-code="' + h.code + '"><div class="city-l"><div class="city-n">' + h.n + '</div>' +
                  '<div class="city-s">中国天气网 · 搜索命中</div></div>' +
                  (isCur ? '<div class="city-r"><span style="color:var(--acc);font-size:11.5px">当前</span></div>' : '') + '</div>';
              }).join('')
            : '<div class="empty" style="padding:18px 6px;color:var(--dim);font-size:12px;text-align:center">未找到匹配城市，试试去掉“市/县”后缀</div>')
          : PRESET.map(function (c) {
              var isCur = cur && ((cur.code && cur.code === c.code) || cur.name === c.n);
              return '<div class="city" data-n="' + c.n + '" data-code="' + (c.code || '') + '"><div class="city-l"><div class="city-n">' + c.n + '</div>' +
                '<div class="city-s">' + (c.code ? '中国天气网' : '') + '</div></div>' +
                (isCur ? '<div class="city-r"><span style="color:var(--acc);font-size:11.5px">当前</span></div>' : '') + '</div>';
            }).join('');
      }
      q.addEventListener('input', renderList);
      pick.addEventListener('click', function (e) {
        var c = e.target.closest('.city'); if (!c) return;
        var n = c.getAttribute('data-n');
        var code = c.getAttribute('data-code');
        closeSheet();
        var p = null;
        for (var i = 0; i < PRESET.length; i++) { if (PRESET[i].n === n) { p = PRESET[i]; break; } }
        if (!p) p = code ? { name: n, code: code } : { name: n };
        W.place = p; W.data = null; load(p); AU.swipe(); toast('已切换：' + n);
      });
    });
  }

  /* ---------- 开关 ---------- */
  function applyShow() {
    var box = $('#cmWeather'); if (!box) return;
    box.style.display = W.show ? '' : 'none';
    var sw = $('#oWx'); if (sw) sw.checked = W.show;
  }

  /* ---------- 初始化 ---------- */
  function init() {
    var box = $('#cmWeather'); if (!box) return;
    var pressTimer = null, pressed = false;
    box.addEventListener('touchstart', function () {
      pressed = false;
      pressTimer = setTimeout(function () { pressed = true; pickCity(); haptic(20); }, 500);
    }, { passive: true });
    box.addEventListener('touchend', function () {
      clearTimeout(pressTimer);
      if (!pressed) showDetail();
    });
    box.addEventListener('click', function (e) {
      if (e.target.closest && e.target.closest('button')) return;
      if (pressed) { pressed = false; return; }
    });
    if (S.wx === undefined) S.wx = true;
    W.show = !!S.wx;
    applyShow();
    var sw = $('#oWx');
    if (sw) sw.addEventListener('change', function () {
      S.wx = sw.checked; saveState(); W.show = !!S.wx; applyShow();
      if (S.wx && !W.data) load();
      if (S.click) AU.click(); haptic();
      toast(S.wx ? '已显示天气' : '已隐藏天气');
    });
    /* 首次加载：已有保存城市直接用；从未选过才尝试 GPS 定位（壳已授权），失败回退默认银川；之后 10 分钟自动刷新 */
    var saved = readPlace();
    if (saved) { load(saved, true); }
    else {
      locateWeather(function (err, p) {
        if (!err && p) { load(p, true); }
        else { load(null, true); }
      });
    }
    setInterval(function () { if (W.show) load(null, true); }, 10 * 60 * 1000);
    /* 进入/退出沉浸时顺手刷新一次（位置变化后让天气保持最新） */
    var _ob = document.body;
    if (_ob && window.MutationObserver) {
      var mo = new MutationObserver(function () {
        if (W.show && !W.busy && Date.now() - (W._last || 0) > 60000) { W._last = Date.now(); load(null, true); }
      });
      mo.observe(_ob, { attributes: true, attributeFilter: ['class'] });
    }
  }

  return { init: init, load: load, pick: pickCity, render: render };
})();

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', function () { WEATHER.init(); });
} else {
  setTimeout(WEATHER.init, 60);
}
