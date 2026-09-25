function buildData(place,d1,fb){
var sk=(d1&&d1.sk)||{},cd=((d1&&d1.city)&&d1.city.weatherinfo)||{};
var fd=(fb&&fb.data)||{},f0=(fd.forecast&&fd.forecast[0])||{};
var now=new Date();
function p2(x){return (x<10?'0':'')+x;}
var iso=now.getFullYear()+'-'+p2(now.getMonth()+1)+'-'+p2(now.getDate())+' '+p2(now.getHours())+':'+p2(now.getMinutes());
var temp=numOf(sk.temp);if(temp===null){temp=numOf(cd.temp);}if(temp===null){temp=numOf(fd.wendu);}
var wtext=sk.weather||cd.weather||f0.type||'';
var ws=numOf(sk.wse);
if(ws===null){var lv=parseInt(String(sk.WS||cd.ws||f0.fl||'').replace(/[^0-9]/g,''),10);ws=isNaN(lv)?null:Math.round(lv*3.3);}
var hum=numOf(sk.SD);if(hum===null){hum=numOf(String(fd.shidu||'').replace(/[^0-9]/g,''));}
var njd=numOf(sk.njd),vis=(njd===null)?null:(/km/i.test(String(sk.njd))?njd*1000:njd);
var hour=now.getHours();
var cur={time:iso,temperature_2m:temp,relative_humidity_2m:hum,apparent_temperature:temp,is_day:(hour>=6&&hour<19)?1:0,weather_code:cnWmo(wtext),wind_speed_10m:ws,wind_direction_10m:0,wind_name:(sk.WD||cd.wd||f0.fx||''),wind_scale:(sk.WS||cd.ws||f0.fl||''),pressure_msl:numOf(sk.qy),visibility:vis,cloud_cover:null,pm25:numOf(fd.pm25),quality:(fd.quality||''),aqi:numOf(f0.aqi),_wtext:wtext,_zs:((d1&&d1.zs&&d1.zs.zs)||null)};
var daily={time:[],weather_code:[],temperature_2m_max:[],temperature_2m_min:[],sunrise:[],sunset:[],precipitation_probability_max:[],uv_index_max:[]};
if(fd.forecast){fd.forecast.forEach(function(f,i){if(i>=7){return;}daily.time.push(f.ymd||'');daily.weather_code.push(cnWmo(f.type));daily.temperature_2m_max.push(numOf(String(f.high||'').replace(/[^0-9\-]/g,'')));daily.temperature_2m_min.push(numOf(String(f.low||'').replace(/[^0-9\-]/g,'')));daily.sunrise.push(f.sunrise||'');daily.sunset.push(f.sunset||'');daily.precipitation_probability_max.push(null);daily.uv_index_max.push(null);});}
return {current:cur,hourly:{},daily:daily,_cn:true,_multi:!!(fd.forecast&&fd.forecast.length)};
}
function fetchCn(place,cb){
var code=place.code||cityCode(place.name);if(!code){return cb(new Error('没有该城市的天气代码'));}
var st={d1:null,fb:null,done:false,d1done:false};
function ok(){return !!st.d1||!!(st.fb&&st.fb.data&&st.fb.data.forecast);}
function fin(){if(st.done||!st.d1done){return;}st.done=true;clearTimeout(st.tm);clearTimeout(st.tm2);if(!ok()){return cb(new Error('国内天气接口无响应'));}cb(null,buildData(place,st.d1,st.fb));}
st.tm=setTimeout(function(){st.d1done=true;fin();},8000);
netGet('https://d1.weather.com.cn/weather_index/'+code+'.html',function(e,t){if(!e&&t){try{var p=parseD1(t);if(p&&(p.sk||p.city)){st.d1=p;}}catch(x){}}st.d1done=true;if(st.fb){fin();}else{st.tm2=setTimeout(fin,2000);}},{'Referer':CN_REF});
netGet('http://t.weather.itboy.net/api/weather/city/'+code,function(e,t){if(!e&&t){try{st.fb=JSON.parse(t);}catch(x){}}if(st.d1done){fin();}},{});
}
