/* 极光时钟 · faces/ext.js —— 外部源码表盘（iframe 渲染 GitHub 开源时钟 HTML） */
(function () {
  /* 每个扩展表盘 = ext/<dir>/index.html */
  var EXTS = [
    { k: 'ext_word', n: '文字钟·EXT', i: '🔤', dir: 'word' },
    { k: 'ext_nix',  n: '辉光管·EXT', i: '🕰️', dir: 'nixie' },
    { k: 'ext_mtx',  n: '矩阵·EXT',   i: '💚', dir: 'matrix' },
    { k: 'ext_dgt',  n: '数字针·EXT', i: '🕘', dir: 'digits' },
    { k: 'ext_flk',  n: '3D翻·EXT',   i: '🧊', dir: 'fliko' },
    { k: 'ext_led',  n: 'LED数字·EXT',i: '🟢', dir: 'ledbin' }
  ];
  function makeFace(cfg) {
    ACFace(cfg.k, {
      n: cfg.n,
      i: cfg.i,
      build: function (host) {
        var f = el('div', 'face on ext-face');
        var fr = document.createElement('iframe');
        fr.src = 'ext/' + cfg.dir + '/index.html';
        fr.setAttribute('loading', 'eager');
        fr.setAttribute('scrolling', 'no');
        f.appendChild(fr);
        host.appendChild(f);
        /* 通知外部页面自适应容器（部分扩展页用 vw/vh，iframe 内 vh 即容器高） */
        try {
          fr.onload = function () {
            try { fr.contentWindow.document.documentElement.style.background = 'transparent'; } catch (e) { }
          };
        } catch (e) { }
      },
      paint: function () { }
    });
  }
  EXTS.forEach(makeFace);
})();
