/* 极光时钟 v5 · audio.js：Web Audio 实时合成引擎（零外部文件） */
/* ---------- ② 音频引擎（全实时合成，零外部文件） ---------- */
var AU=(function(){
  var ctx=null,master=null,vol=.7,beds={},ringTimer=null,ringNode=null,nb=null,chirpTimer=null,clickTone='soft';
  /* 内置音效文件（HTML5 Audio 播放；文件缺失/加载失败时自动回落 Web Audio 合成） */
  /* 内置音效文件（AOSP 系统开源音效，Apache-2.0 许可；文件缺失/加载失败时自动回落 Web Audio 合成） */
  var AF = {
    chime_west:'audio/chime_west.ogg', chime_bell:'audio/chime_bell.ogg', chime_piano:'audio/chime_piano.ogg',
    chime_glass:'audio/chime_glass.ogg', chime_beep:'audio/chime_beep.ogg',
    click_soft:'audio/click_soft.ogg', click_pop:'audio/click_pop.ogg', click_wood:'audio/click_wood.ogg',
    click_digital:'audio/click_digital.ogg', click_drop:'audio/click_drop.ogg',
    swipe:'audio/swipe.ogg', ok:'audio/ok.ogg', err:'audio/err.ogg', tick:'audio/tick.ogg',
    tick_key:'audio/click_soft.ogg', tick_soft:'audio/tick_soft.ogg', tick_wood:'audio/tick.ogg', tick_drop:'audio/click_drop.ogg'
  }, _audio = {};
  function playFile(name){
    var url = AF[name]; if (!url) return false;
    try {
      var a = _audio[name];
      if (!a) { a = new Audio(url); a.preload = 'auto'; _audio[name] = a; }
      a.currentTime = 0;
      var p = a.play(); if (p && p.catch) p.catch(function () { });
      return true;
    } catch (e) { return false; }
  }
  function fileFallback(name){ return !(AF[name] && _audio[name] && !_audio[name].error && _audio[name].readyState >= 1); }
  function ac(){ if(!ctx){ var C=window.AudioContext||window.webkitAudioContext; if(!C) return null;
      ctx=new C(); master=ctx.createGain(); master.gain.value=vol; master.connect(ctx.destination); }
    if(ctx.state==='suspended')ctx.resume(); return ctx; }
  function noise(){ var c=ac(); if(!c)return null; if(nb)return nb;
    var len=c.sampleRate*3,b=c.createBuffer(1,len,c.sampleRate),d=b.getChannelData(0),last=0;
    for(var i=0;i<len;i++){var w=Math.random()*2-1;last=(last+.02*w)/1.02;d[i]=last*3.2;} nb=b; return b; }
  function env(g,t,a,peak,d){ g.gain.setValueAtTime(0.0001,t); g.gain.exponentialRampToValueAtTime(peak,t+a); g.gain.exponentialRampToValueAtTime(0.0001,t+a+d); }
  function beep(f,dur,type,gain,delay){ var c=ac(); if(!c)return; var t=c.currentTime+(delay||0);
    var o=c.createOscillator(),g=c.createGain(); o.type=type||'sine'; o.frequency.setValueAtTime(f,t);
    env(g,t,.012,gain||.16,dur||.18); o.connect(g); g.connect(master); o.start(t); o.stop(t+(dur||.18)+.08); }
  /* 走秒滴答（多音色；纯合成 6 种 + 文件 4 种，文件失败回落合成） */
  var tickTone = 'elec';
  var SYNTH_TONES = ['elec','ping','blip','tick2','knock','click'];
  function tick(){
    var tn = tickTone || 'elec';
    if (SYNTH_TONES.indexOf(tn) < 0 && playFile('tick_' + tn)) return;
    var c = ac(); if (!c) return; var t = c.currentTime; synthTick(tn, c, t);
  }
  function synthTick(tn, c, t) {
    if (tn === 'tick2') { /* 机械双响：嘀-嗒 */
      var o1 = c.createOscillator(), g1 = c.createGain();
      o1.type = 'square'; o1.frequency.setValueAtTime(1500, t); o1.frequency.exponentialRampToValueAtTime(900, t + .02);
      g1.gain.setValueAtTime(.0001, t); g1.gain.exponentialRampToValueAtTime(.05, t + .003); g1.gain.exponentialRampToValueAtTime(.0001, t + .04);
      o1.connect(g1); g1.connect(master); o1.start(t); o1.stop(t + .05);
      var t2 = t + .1, o2 = c.createOscillator(), g2 = c.createGain();
      o2.type = 'square'; o2.frequency.setValueAtTime(1600, t2); o2.frequency.exponentialRampToValueAtTime(950, t2 + .02);
      g2.gain.setValueAtTime(.0001, t2); g2.gain.exponentialRampToValueAtTime(.045, t2 + .003); g2.gain.exponentialRampToValueAtTime(.0001, t2 + .04);
      o2.connect(g2); g2.connect(master); o2.start(t2); o2.stop(t2 + .05);
      return;
    }
    var o = c.createOscillator(), g = c.createGain();
    var f0 = 1800, f1 = 700, type = 'square';
    if (tn === 'ping') { type = 'sine'; f0 = 2300; f1 = 1500; }        /* 叮：高频清脆 */
    else if (tn === 'blip') { type = 'sine'; f0 = 880; f1 = 880; }     /* 哔：单音短促 */
    else if (tn === 'knock') { type = 'sine'; f0 = 340; f1 = 165; }    /* 敲击：低频衰减 */
    else if (tn === 'click') { type = 'triangle'; f0 = 2600; f1 = 1200; } /* 咔哒：极短点击 */
    else if (tn === 'key') { type = 'triangle'; f0 = 1250; f1 = 520; }
    else if (tn === 'soft') { type = 'sine'; f0 = 980; f1 = 420; }
    else if (tn === 'wood') { type = 'square'; f0 = 430; f1 = 210; }
    else if (tn === 'drop') { type = 'sine'; f0 = 1900; f1 = 720; }
    o.type = type; o.frequency.setValueAtTime(f0, t);
    if (f1 !== f0) o.frequency.exponentialRampToValueAtTime(f1, t + .03);
    g.gain.setValueAtTime(.0001, t); g.gain.exponentialRampToValueAtTime(.075, t + .003); g.gain.exponentialRampToValueAtTime(.0001, t + .055);
    o.connect(g); g.connect(master); o.start(t); o.stop(t + .07);
  }
  function setTickTone(t){ tickTone = t || 'elec'; }
  /* 按键音（多音色；内置文件优先） */
  function click(){
    var tn = clickTone || 'soft';
    if (playFile('click_' + tn)) return;
    if (tn === 'pop') { beep(660,.05,'sine',.1); beep(990,.045,'sine',.06,.04); }
    else if (tn === 'wood') { beep(430,.05,'square',.065); beep(210,.07,'sine',.09,.02); }
    else if (tn === 'digital') { beep(1420,.03,'square',.055); beep(900,.04,'square',.045,.025); }
    else if (tn === 'drop') { beep(1900,.06,'sine',.08); beep(720,.09,'sine',.055,.05); }
    else { beep(1180,.06,'triangle',.09,0); beep(1760,.05,'sine',.05,.03); }
  }
  /* 表盘切换 / 提示：嗖声（内置文件优先） */
  function swipe(){ if (playFile('swipe')) return;
    var c = ac(); if (!c) return;
    var t = c.currentTime, o = c.createOscillator(), g = c.createGain();
    o.type = 'sine'; o.frequency.setValueAtTime(340, t); o.frequency.exponentialRampToValueAtTime(760, t + .1);
    env(g, t, .012, .11, .11); o.connect(g); g.connect(master); o.start(t); o.stop(t + .15);
    beep(920, .1, 'sine', .055, .05);
  }
  /* 成功 / 错误提示音（内置文件优先） */
  function ok(){ if (playFile('ok')) return; beep(784,.07,'sine',.11); beep(1175,.1,'sine',.09,.07); }
  function err(){ if (playFile('err')) return; beep(240,.13,'sawtooth',.08); beep(185,.17,'sawtooth',.065,.11); }
  function setClickTone(t){ clickTone = t || 'soft'; }
  /* 报时音色 */
  function chime(tone,hours){ var _fm={ding:'chime_west',bell:'chime_bell',piano:'chime_piano',glass:'chime_glass',beep:'chime_beep'};
    if (_fm[tone] && playFile(_fm[tone])) return; var c=ac(); if(!c)return; var i;
    if(tone==='gong'){ beep(98,.9,'sine',.22); beep(147,.7,'sine',.1,.02);
      if(hours){ for(i=0;i<hours;i++){ (function(i){ setTimeout(function(){ beep(65,1.2,'sine',.15); },i*950); })(i); } } return; }
    if(tone==='organ'){ var oseq=[[261.6,.7],[329.6,.7],[392,.9],[523.3,1.1]]; var od=0;
      oseq.forEach(function(s){ beep(s[0],s[1],'sine',.09,od); beep(s[0]*1.5,s[1]*.7,'sine',.05,od); od+=s[1]*.6; }); return; }
    if(tone==='flute'){ [523.3,587.3,659.3,784].forEach(function(f,i){ setTimeout(function(){ beep(f,.28,'sine',.13); beep(f*1.01,.24,'sine',.04,.012); },i*180); }); return; }
    if(tone==='harp'){ [523.3,659.3,784,1046.5,1318.5].forEach(function(f,i){ setTimeout(function(){ beep(f,.5,'sine',.1); },i*140); }); return; }
    if(tone==='xyl'){ [784,988,1174.7,1568].forEach(function(f,i){ setTimeout(function(){ beep(f,.13,'square',.08); beep(f*1.5,.07,'sine',.035,.02); },i*105); }); return; }
    if(tone==='bird'){ [1500,1900,1400,2100,1700].forEach(function(f,i){ setTimeout(function(){ beep(f,.07,'sine',.11); },i*90); }); setTimeout(function(){ beep(2400,.1,'sine',.07); },520); return; }
    if(tone==='melody'){ var mseq=[[659.3,.18],[493.9,.18],[587.3,.18],[784,.5]]; var md=0;
      mseq.forEach(function(s){ beep(s[0],s[1],'triangle',.12,md); beep(s[0]*2,s[1]*.5,'sine',.035,md); md+=s[1]*.8; }); return; }
    if(tone==='soft'){ [659.3,783.99,987.77].forEach(function(f,i){ setTimeout(function(){ beep(f,.34,'sine',.08); },i*200); }); return; }
    if(tone==='pulse'){ for(i=0;i<3;i++){ (function(i){ setTimeout(function(){ beep(1320,.05,'square',.09); },i*130); })(i); } return; }
    if(tone==='arcade'){ [523.3,523.3,784,784,1046.5,1046.5,1568].forEach(function(f,i){ setTimeout(function(){ beep(f,.09,'square',.09); },i*95); }); return; }
    if(tone==='chime'){ [1046.5,1318.5,1568,2093].forEach(function(f,i){ setTimeout(function(){ beep(f,.6,'sine',.065); },i*160); }); return; }
    var seq;
    if(tone==='bell'){ seq=[[196,.9],[294,.9],[392,1.2]]; }
    else if(tone==='piano'){ seq=[[523,.5],[659,.5],[784,.7],[1046,.9]]; }
    else if(tone==='beep'){ seq=[[880,.14],[880,.14],[880,.3]]; }
    else if(tone==='glass'){ seq=[[1318,.5],[1760,.5],[2093,.8]]; }
    else { seq=[[1046,.45],[1318,.45],[1568,.8]]; }
    var d=0; seq.forEach(function(s){ if(tone==='bell'){ [1,2.02,3.01].forEach(function(m,i){ beep(s[0]*m,s[1],'sine',.13/(i+1),d); }); }
      else beep(s[0],s[1],tone==='piano'?'triangle':'sine',.16,d); d+=s[1]*.62; });
    if(hours&&tone==='bell'){ setTimeout(function(){beep(196,1.6,'sine',.12);},d*1000+120); } }
  /* 闹钟铃声 */
  function ringOnce(style){ var c=ac(); if(!c)return;
    if(style==='bird'){ [1400,1750,2100,1850].forEach(function(f,i){ setTimeout(function(){ beep(f,.09,'sine',.14);},i*110); }); setTimeout(function(){beep(1200,.12,'sine',.1);},520); }
    else if(style==='piano'){ [[784,.32],[988,.32],[1175,.5]].forEach(function(s,i){ setTimeout(function(){ beep(s[0],s[1],'triangle',.18); beep(s[0]*2,s[1]*.6,'sine',.06); },i*230); }); }
    else if(style==='drum'){ [0,120,240,360,600].forEach(function(d){ setTimeout(function(){ beep(72,.2,'sine',.3); beep(180,.09,'square',.06); },d); }); }
    else if(style==='siren'){ for(var i=0;i<5;i++){ (function(i){ setTimeout(function(){ beep(i%2?980:640,.26,'sawtooth',.2); },i*220); })(i); } }
    else if(style==='gentle'){ [523,659,784,988].forEach(function(f,i){ setTimeout(function(){ beep(f,.5,'sine',.13); },i*190); }); }
    else if(style==='bell'){ [1,2.02,2.98,4.1].forEach(function(m,i){ beep(174*m,1.5,'sine',.16/(i*0.6+1)); }); }
    else if(style==='arcade'){ [523,523,784,784,1046,1046,1568].forEach(function(f,i){ setTimeout(function(){ beep(f,.1,'square',.13); },i*105); }); }
    else { for(var j=0;j<4;j++){ (function(j){ setTimeout(function(){ beep(1568,.11,'square',.2); beep(1046,.11,'square',.12); },j*260); })(j); } } }
  function startRing(style){ ac(); var n=0; stopRing(); ringOnce(style);
    ringTimer=setInterval(function(){ n++; ringOnce(style); try{ var b=B(); if(b&&b.vibrate&&n%2===1) b.vibrate(340); }catch(e){} },1500); }
  function stopRing(){ if(ringTimer){clearInterval(ringTimer);ringTimer=null;} }
  /* 声景（可叠加） */
  function makeBed(type){ var c=ac(); if(!c)return null; var src=c.createBufferSource(); src.buffer=noise(); src.loop=true;
    var f=c.createBiquadFilter(), g=c.createGain(), lfo=c.createOscillator(), lg=c.createGain();
    var peak=.3; f.type='lowpass'; f.frequency.value=900; g.gain.value=0;
    if(type==='rain'){ f.type='highpass'; f.frequency.value=1100; peak=.24; }
    else if(type==='sea'){ f.type='lowpass'; f.frequency.value=420; peak=.42; lfo.frequency.value=.09; lg.gain.value=.3; }
    else if(type==='fire'){ f.type='bandpass'; f.frequency.value=520; f.Q.value=.7; peak=.3; lfo.frequency.value=6.5; lg.gain.value=.18; }
    else if(type==='wind'){ f.type='lowpass'; f.frequency.value=620; peak=.34; lfo.frequency.value=.14; lg.gain.value=.34; }
    else if(type==='forest'){ f.type='bandpass'; f.frequency.value=1500; f.Q.value=.5; peak=.2; }
    else if(type==='night'){ f.type='highpass'; f.frequency.value=2600; peak=.13; }
    else if(type==='fan'){ f.type='lowpass'; f.frequency.value=300; peak=.4; }
    else if(type==='brown'){ f.type='lowpass'; f.frequency.value=200; peak=.5; }
    src.connect(f); f.connect(g);
    lfo.connect(lg); lg.connect(g.gain); lfo.start();
    var extra=[];
    if(type==='heart'){ f.type='lowpass'; f.frequency.value=160; peak=.0; }
    if(type==='pad_star'||type==='pad_deep'||type==='pad_dawn'){
      var chords={pad_star:[130.8,196,261.6,329.6],pad_deep:[87.3,130.8,174.6,261.6],pad_dawn:[146.8,220,293.7,440]};
      peak=.055; src.disconnect(); f.disconnect();
      chords[type].forEach(function(fr,i){ var o=c.createOscillator(),og=c.createGain(),ol=c.createOscillator(),olg=c.createGain();
        o.type=i%2?'sine':'triangle'; o.frequency.value=fr*(type==='pad_deep'?.5:1); og.gain.value=0;
        ol.frequency.value=.05+i*.017; olg.gain.value=.05; ol.connect(olg); olg.connect(og.gain); ol.start();
        o.connect(og); og.connect(g); o.start(); extra.push(o,ol); });
    }
    g.connect(master); src.start();
    if(type==='heart'){ var hi=setInterval(function(){ beep(58,.13,'sine',.34); setTimeout(function(){beep(50,.11,'sine',.22);},170); },1100); extra.push({stop:function(){clearInterval(hi);}}); }
    if(type==='night'||type==='forest'){ chirpTimer=setInterval(function(){ if(Math.random()<.55) beep(2400+Math.random()*1400,.05,'sine',.045); },420); extra.push({stop:function(){clearInterval(chirpTimer);}}); }
    var o2={g:g,src:src,lfo:lfo,extra:extra,on:true};
    g.gain.setTargetAtTime(peak,c.currentTime,.9);
    return o2; }
  function toggleBed(type){ if(beds[type]){ stopBed(type); return false; } var b=makeBed(type); if(!b)return false; beds[type]=b; return true; }
  function stopBed(type){ var b=beds[type]; if(!b)return; try{ b.g.gain.setTargetAtTime(0,ctx.currentTime,.4);
   setTimeout(function(){ try{b.src.stop(); b.lfo.stop(); b.extra.forEach(function(x){try{x.stop();}catch(e){}});}catch(e){} },900); }catch(e){} delete beds[type]; }
  function stopAllBeds(){ Object.keys(beds).forEach(stopBed); }
  function setVol(v){ vol=v; if(master&&ctx) master.gain.setTargetAtTime(v,ctx.currentTime,.03); }
  function resume(){ ac(); }
  function isPlaying(type){ return !!beds[type]; }
  function activeBeds(){ return Object.keys(beds); }
  return {tick:tick,click:click,swipe:swipe,ok:ok,err:err,setClickTone:setClickTone,setTickTone:setTickTone,chime:chime,startRing:startRing,stopRing:stopRing,ringOnce:ringOnce,
   toggleBed:toggleBed,stopAllBeds:stopAllBeds,setVol:setVol,resume:resume,isPlaying:isPlaying,activeBeds:activeBeds,ctx:function(){return ctx;}};
})();
