/* 极光时钟 v5 · core.js：主题/声景/铃声/城市/语录数据 + 通用工具 + 状态 */

/* =====================================================================
   极光时钟 Aurora Clock —— 多文件工程（v5 · 适配 v8 壳 59 桥：全屏/方向/亮度/TTS/原生选择器/对话框/系统信息/壳内文件）
   模块：① 数据/工具 ② 音频引擎 ③ 背景动效 ④ 表盘（外置） ⑤ 功能页 ⑥ 初始化
   ===================================================================== */
/* ---------- ① 数据 ---------- */
var THEMES=[
 {k:'aurora',n:'极光',c1:'#5ef2c9',c2:'#7c9cff',bg:'#0b2233'},
 {k:'space', n:'深空',c1:'#dfe9ff',c2:'#7aa2ff',bg:'#0b0f22'},
 {k:'cyber', n:'赛博',c1:'#ff2fb9',c2:'#21e6ff',bg:'#25063a'},
 {k:'sunset',n:'日落',c1:'#ffb26b',c2:'#ff5f6d',bg:'#3b1220'},
 {k:'sakura',n:'樱花',c1:'#ffc2dd',c2:'#ff8fb1',bg:'#331428'},
 {k:'forest',n:'森林',c1:'#8ef0a0',c2:'#35c98a',bg:'#0d2a1e'},
 {k:'ocean', n:'海洋',c1:'#7fe6ff',c2:'#2e9dff',bg:'#082c47'},
 {k:'lava',  n:'熔岩',c1:'#ff8a3d',c2:'#ff3b2f',bg:'#30100a'},
 {k:'mint',  n:'薄荷',c1:'#b6ffe0',c2:'#45e0b0',bg:'#0a2e26'},
 {k:'rose',  n:'玫瑰金',c1:'#ffd9b0',c2:'#e8a1a1',bg:'#2c1414'},
 {k:'mono',  n:'黑白',c1:'#ffffff',c2:'#c9c9c9',bg:'#111111'},
 {k:'nebula',n:'星云',c1:'#c9a6ff',c2:'#7a5cff',bg:'#1a0d44'}
];
/* FACES 表盘清单由 faces/registry.js 动态生成（表盘外置：新增表盘文件即注册） */
var FXS=[{k:'stars',n:'星空',i:'✨'},{k:'aurora',n:'极光',i:'🌈'},{k:'meteor',n:'流星',i:'☄️'},{k:'ripple',n:'涟漪',i:'🔵'},{k:'snow',n:'飘雪',i:'❄️'},{k:'firefly',n:'萤火',i:'🧚'},{k:'wave',n:'声波',i:'〰️'},{k:'pulse',n:'脉冲',i:'💫'},{k:'none',n:'纯净',i:'⬛'}];
var SOUNDS=[
 {k:'rain',n:'雨声',i:'🌧️'},{k:'sea',n:'海浪',i:'🌊'},{k:'fire',n:'篝火',i:'🔥'},{k:'wind',n:'风声',i:'🍃'},
 {k:'forest',n:'森林',i:'🌲'},{k:'night',n:'夜虫',i:'🦗'},{k:'fan',n:'风扇',i:'🌀'},{k:'brown',n:'棕噪',i:'🎚️'},
 {k:'pad_star',n:'星空乐',i:'🎵'},{k:'pad_deep',n:'深海乐',i:'🐋'},{k:'pad_dawn',n:'晨光乐',i:'🌅'},{k:'heart',n:'心跳',i:'💓'}
];
var RINGERS=[
 {k:'classic',n:'经典电子铃'},{k:'bird',n:'清晨鸟鸣'},{k:'piano',n:'钢琴轻奏'},{k:'drum',n:'鼓点渐强'},
 {k:'siren',n:'警报强醒'},{k:'gentle',n:'柔和渐强'},{k:'bell',n:'寺庙钟声'},{k:'arcade',n:'8bit 街机'}
];
var CHIME_TONES=[{k:'ding',n:'清脆铃'},{k:'bell',n:'铜钟'},{k:'piano',n:'钢琴'},{k:'beep',n:'电子哔'},{k:'glass',n:'玻璃杯'}];
var CLICK_TONES=[{k:'soft',n:'柔和'},{k:'pop',n:'气泡'},{k:'wood',n:'木鱼'},{k:'digital',n:'电子'},{k:'drop',n:'水滴'}];
var TICK_TONES=[{k:'elec',n:'电子'},{k:'key',n:'按键'},{k:'soft',n:'柔和'},{k:'wood',n:'木鱼'},{k:'drop',n:'水滴'}];
var CITIES=[
 {n:'北京',tz:'Asia/Shanghai'},{n:'东京',tz:'Asia/Tokyo'},{n:'首尔',tz:'Asia/Seoul'},
 {n:'新加坡',tz:'Asia/Singapore'},{n:'迪拜',tz:'Asia/Dubai'},{n:'孟买',tz:'Asia/Kolkata'},
 {n:'莫斯科',tz:'Europe/Moscow'},{n:'柏林',tz:'Europe/Berlin'},{n:'巴黎',tz:'Europe/Paris'},
 {n:'伦敦',tz:'Europe/London'},{n:'纽约',tz:'America/New_York'},{n:'芝加哥',tz:'America/Chicago'},
 {n:'丹佛',tz:'America/Denver'},{n:'洛杉矶',tz:'America/Los_Angeles'},{n:'圣保罗',tz:'America/Sao_Paulo'},
 {n:'开罗',tz:'Africa/Cairo'},{n:'约翰内斯堡',tz:'Africa/Johannesburg'},{n:'悉尼',tz:'Australia/Sydney'},
 {n:'奥克兰',tz:'Pacific/Auckland'},{n:'火奴鲁鲁',tz:'Pacific/Honolulu'},{n:'温哥华',tz:'America/Vancouver'},
 {n:'墨西哥城',tz:'America/Mexico_City'},{n:'伊斯坦布尔',tz:'Europe/Istanbul'},{n:'曼谷',tz:'Asia/Bangkok'}
];
var QUOTES=['把时间花在喜欢的事上，就不算浪费。','慢慢来，比较快。','今天也要好好吃饭、好好睡觉。','所有的自律，都会在未来回报你。',
 '专注一件事，胜过忙碌一整天。','睡前放下手机，明天会更清醒。','时间不会辜负认真生活的人。','先完成，再完美。'];

/* ---------- 工具 ---------- */
function $(s,r){return (r||document).querySelector(s);}
function $$(s,r){return Array.prototype.slice.call((r||document).querySelectorAll(s));}
function pad(n,l){n=''+n;l=l||2;while(n.length<l)n='0'+n;return n;}
function el(t,c,h){var e=document.createElement(t);if(c)e.className=c;if(h!=null)e.innerHTML=h;return e;}
function B(){return window.AndroidApp||null;}
function toast(m){var t=$('#toast');t.textContent=m;t.classList.add('on');clearTimeout(t._t);t._t=setTimeout(function(){t.classList.remove('on');},1900);}
function haptic(ms){try{var b=B();if(!b)return;if(ms)b.vibrate(ms);else b.haptic();}catch(e){}}
function store(k,v){
  try{
    if(v===undefined)return JSON.parse(localStorage.getItem('ac_'+k));
    localStorage.setItem('ac_'+k,JSON.stringify(v));return null;
  }catch(e){
    /* localStorage 不可用（WebView 限制/隐私模式）时回退：用壳桥读写应用内置文件（files/）持久化状态 */
    try{
      var b=B(); if(!b||!b.saveFile||!b.readFile)return null;
      var fn='ac_state_'+k+'.json';
      if(v===undefined){
        var r=JSON.parse(b.readFile(fn));
        if(!r||!r.ok)return null;
        return JSON.parse(decodeURIComponent(escape(atob(r.dataBase64))));
      }
      b.saveFile(fn,btoa(unescape(encodeURIComponent(JSON.stringify(v)))));
      return null;
    }catch(e2){return null;}
  }
}
function ripple(ev,node){try{var r=node.getBoundingClientRect();var s=el('i','ripple');var d=Math.max(r.width,r.height);s.style.width=s.style.height=d+'px';
 s.style.left=(ev.clientX-r.left-d/2)+'px';s.style.top=(ev.clientY-r.top-d/2)+'px';node.appendChild(s);setTimeout(function(){s.remove();},560);}catch(e){}}
