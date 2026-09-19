/* 极光时钟 v5 · fx.js：Canvas 背景动效（星空/极光/流星/涟漪/纯净） */
/* ---------- ③ 背景动效（Canvas） ---------- */
var FX=(function(){
  var cv,ctx2,W,H,DPR=1,mode='stars',stars=[],meteors=[],t=0,raf=null,gx=[];
  function resize(){ if(!cv)return; DPR=Math.min(window.devicePixelRatio||1,2);
    W=cv.clientWidth||window.innerWidth; H=cv.clientHeight||window.innerHeight;
    cv.width=W*DPR; cv.height=H*DPR; ctx2.setTransform(DPR,0,0,DPR,0,0); init(); }
  function init(){ stars=[]; meteors=[]; gx=[];
    var n=Math.round(W*H/9000); if(n>190)n=190; if(n<40)n=40;
    for(var i=0;i<n;i++) stars.push({x:Math.random()*W,y:Math.random()*H,r:Math.random()*1.5+.3,p:Math.random()*Math.PI*2,v:Math.random()*.28+.04});
    for(var j=0;j<9;j++) gx.push({x:Math.random(),y:Math.random(),s:Math.random()*1.4+.5,p:Math.random()*6});
  }
  function css(v){ return getComputedStyle(document.documentElement).getPropertyValue(v).trim(); }
  function setMode(m){ mode=m||'stars'; if(cv) resize(); }
  function frame(){
    if(!ctx2){ raf=requestAnimationFrame(frame); return; }
    var acc=css('--acc')||'#5ef2c9', acc2=css('--acc2')||'#7c9cff';
    t+=.016;
    ctx2.clearRect(0,0,W,H);
    if(mode==='stars'||mode==='meteor'){
      for(var i=0;i<stars.length;i++){ var s=stars[i]; s.p+=.02; s.y+=s.v; if(s.y>H+2){s.y=-2;s.x=Math.random()*W;}
        var a=.35+.5*Math.abs(Math.sin(s.p));
        ctx2.globalAlpha=a; ctx2.fillStyle=i%17===0?acc:'#ffffff';
        ctx2.beginPath(); ctx2.arc(s.x,s.y,s.r,0,6.283); ctx2.fill(); }
      ctx2.globalAlpha=1;
      if(mode==='meteor'){
        if(Math.random()<.012) meteors.push({x:Math.random()*W*.9,y:-40,l:110+Math.random()*130,v:4.5+Math.random()*3.4,life:1});
        for(var m=meteors.length-1;m>=0;m--){ var mt=meteors[m]; mt.x+=mt.v*1.5; mt.y+=mt.v; mt.life-=.007;
          if(mt.life<=0||mt.y>H+120){ meteors.splice(m,1); continue; }
          var g=ctx2.createLinearGradient(mt.x,mt.y,mt.x-mt.l*.9,mt.y-mt.l);
          g.addColorStop(0,'rgba(255,255,255,'+(.85*mt.life)+')'); g.addColorStop(.4,acc2+''); g.addColorStop(1,'rgba(0,0,0,0)');
          ctx2.globalAlpha=mt.life*.9; ctx2.strokeStyle=g; ctx2.lineWidth=2; ctx2.lineCap='round';
          ctx2.beginPath(); ctx2.moveTo(mt.x,mt.y); ctx2.lineTo(mt.x-mt.l*.9,mt.y-mt.l); ctx2.stroke(); }
        ctx2.globalAlpha=1;
      }
    } else if(mode==='aurora'){
      for(var b=0;b<4;b++){ var yb=H*(.18+b*.17);
        var g2=ctx2.createLinearGradient(0,yb-90,W,yb+90);
        g2.addColorStop(0,'rgba(0,0,0,0)'); g2.addColorStop(.5,(b%2?acc:acc2)); g2.addColorStop(1,'rgba(0,0,0,0)');
        ctx2.globalAlpha=.13; ctx2.fillStyle=g2;
        ctx2.beginPath(); ctx2.moveTo(0,H);
        for(var x=0;x<=W;x+=24){ var y=yb+Math.sin(x/W*3.1+t*(.5+b*.16)+b)*52+Math.sin(x/W*7+t)*17; ctx2.lineTo(x,y); }
        ctx2.lineTo(W,H); ctx2.closePath(); ctx2.fill(); }
      ctx2.globalAlpha=1;
    } else if(mode==='ripple'){
      var sp=52, cx=W/2, cy=H*.46;
      for(var ix=0;ix<gx.length;ix++){}
      for(var px=sp/2;px<W;px+=sp){ for(var py=sp/2;py<H;py+=sp){
        var d=Math.hypot(px-cx,py-cy), w=Math.sin(d/58 - t*2.1);
        var r=1.1+Math.max(0,w)*2.3; ctx2.globalAlpha=.16+Math.max(0,w)*.5;
        ctx2.fillStyle=acc; ctx2.beginPath(); ctx2.arc(px,py,r,0,6.283); ctx2.fill();
        if(Math.floor(d/58 - t*2.1)%6===0){ ctx2.globalAlpha=.5; }
      } }
      ctx2.globalAlpha=1;
    }
    raf=requestAnimationFrame(frame);
  }
  function start(){ cv=$('#fx'); if(!cv)return; ctx2=cv.getContext('2d'); resize(); if(!raf) frame();
    window.addEventListener('resize',function(){ setTimeout(resize,120); }); }
  return {start:start,setMode:setMode,resize:resize,get:function(){return mode;}};
})();
