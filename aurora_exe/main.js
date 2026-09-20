'use strict';
const { app, BrowserWindow, shell, Menu, globalShortcut } = require('electron');
const http = require('http');
const fs = require('fs');
const path = require('path');

const WEB_ROOT = path.join(__dirname, 'web');
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.webp': 'image/webp',
  '.ogg': 'audio/ogg',
  '.mp3': 'audio/mpeg',
  '.wav': 'audio/wav',
  '.m4a': 'audio/mp4',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf'
};

// ---- 内置本地静态服务（避免 file:// 的 CORS / fetch 限制） ----
function createServer() {
  return http.createServer((req, res) => {
    try {
      let urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
      if (urlPath === '/' || urlPath === '') urlPath = '/index.html';
      // 防目录穿越
      const filePath = path.normalize(path.join(WEB_ROOT, urlPath));
      if (!filePath.startsWith(WEB_ROOT)) {
        res.writeHead(403); res.end('Forbidden'); return;
      }
      fs.stat(filePath, (err, st) => {
        if (err || !st.isFile()) {
          res.writeHead(404); res.end('Not Found'); return;
        }
        res.writeHead(200, {
          'Content-Type': MIME[path.extname(filePath).toLowerCase()] || 'application/octet-stream',
          'Cache-Control': 'no-cache'
        });
        fs.createReadStream(filePath).pipe(res);
      });
    } catch (e) {
      res.writeHead(500); res.end('Server Error');
    }
  });
}

let server = null;
let win = null;

function createWindow(port) {
  win = new BrowserWindow({
    width: 1600,
    height: 1000,
    minWidth: 700,
    minHeight: 480,
    title: '极光时钟',
    backgroundColor: '#0a0e1a',
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false
    }
  });

  win.loadURL('http://127.0.0.1:' + port + '/index.html');
  win.once('ready-to-show', () => win.show());
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('http://127.0.0.1:' + port)) e.preventDefault();
  });
  win.on('closed', () => { win = null; });
  win.on('enter-full-screen', () => win.webContents.send('fullscreen', true));
  win.on('leave-full-screen', () => win.webContents.send('fullscreen', false));
}

app.whenReady().then(() => {
  Menu.setApplicationMenu(null);
  server = createServer();
  server.listen(0, '127.0.0.1', () => {
    const port = server.address().port;
    createWindow(port);
  });

  // F11 全屏切换（桌面端沉浸）
  globalShortcut.register('F11', () => {
    if (win) win.setFullScreen(!win.isFullScreen());
  });
  globalShortcut.register('F12', () => {
    if (win) win.webContents.toggleDevTools();
  });

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0 && server) {
      createWindow(server.address().port);
    }
  });
});

app.on('will-quit', () => {
  globalShortcut.unregisterAll();
  if (server) { try { server.close(); } catch (e) {} }
});

app.on('window-all-closed', () => {
  app.quit();
});
