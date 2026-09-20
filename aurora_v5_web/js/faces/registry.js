/* =====================================================================
   极光时钟 v5 · faces/registry.js —— 表盘注册表 + FACE 调度器（表盘外置协议）
   ---------------------------------------------------------------------
   外置规则：
   1. 新增表盘 = 在 js/faces/ 下新建一个文件，调用 ACFace(k, def) 注册，
      并在 css/faces/ 下提供同名样式；主程序零改动。
   2. 删除表盘 = 删除对应文件并去掉 index.html 里的引用即可。
   3. def 结构：
      { n: 显示名, i: 图标,
        build(host)   —— 构建表盘 DOM 到 host
        paint(n, S)   —— 每秒渲染（n=Date 对象，S=全局状态）
        relayout()    —— 可选：横竖屏布局回调（flip 翻页钟需要）}
   ===================================================================== */
window.FACE_REGISTRY = window.FACE_REGISTRY || {};

/** 注册表盘（k 唯一） */
function ACFace(k, def) {
  def = def || {};
  def.k = k;
  window.FACE_REGISTRY[k] = def;
  return def;
}

/** 12 小时制转换（各表盘共用） */
function hour12(h) { var x = h % 12; return x === 0 ? 12 : x; }

/** FACE 调度器：只负责按当前表盘转发 build/paint/relayout */
var FACE = (function () {
  var cur = 'flip';
  function build(k) {
    cur = k;
    var host = $('#faceHost');
    if (!host) return;
    host.innerHTML = '';
    var f = window.FACE_REGISTRY[k];
    if (f && f.build) { try { f.build(host); } catch (e) { host.innerHTML = '<div class="face on" style="color:var(--acc)">表盘加载失败</div>'; } }
    var n = new Date();
    paint(n);
  }
  function paint(n) {
    var f = window.FACE_REGISTRY[cur];
    if (f && f.paint) { try { f.paint(n, S); } catch (e) { } }
  }
  function relayout() {
    var f = window.FACE_REGISTRY[cur];
    if (f && f.relayout) { try { f.relayout(); } catch (e) { } }
  }
  function list() {
    return Object.keys(window.FACE_REGISTRY).map(function (k) { return window.FACE_REGISTRY[k]; });
  }
  return { build: build, paint: paint, relayout: relayout, cur: function () { return cur; }, list: list };
})();

/** 表盘清单（可变数组：init 时调用 refreshFaces() 刷新为已注册表盘） */
var FACES = [];
function refreshFaces() {
  FACES.length = 0;
  FACE.list().forEach(function (f) { FACES.push(f); });
}
