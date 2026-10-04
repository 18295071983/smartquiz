// 探针：验证 dsh 0.2.0-rc.2 的 acp profile 是否能通过 stdio 讲 ACP（HTTP serve 已移除后的替代通道）
// 用法：node acp_stdio_probe.mjs [dsh-path]
import { spawn } from 'node:child_process';

const dsh = process.argv[2] || String.raw`C:\Users\xiaocong\AppData\Local\Programs\DeepSeek Harness\resources\runtime\cli\bin\dsh.cmd`;

const child = spawn('cmd.exe', ['/c', dsh, '--profile', 'acp'], {
  stdio: ['pipe', 'pipe', 'pipe'],
  windowsHide: true,
});

const seen = [];
let buffered = '';
child.stdout.on('data', (d) => {
  buffered += d.toString('utf8');
  let i;
  while ((i = buffered.indexOf('\n')) >= 0) {
    const line = buffered.slice(0, i).trim();
    buffered = buffered.slice(i + 1);
    if (line) {
      seen.push(line);
      console.log('[stdout]', line.slice(0, 300));
    }
  }
});
child.stderr.on('data', (d) => {
  const s = d.toString('utf8').trim();
  if (s) console.log('[stderr]', s.slice(0, 300));
});

const send = (obj) => child.stdin.write(JSON.stringify(obj) + '\n');

setTimeout(() => {
  console.log('--- send initialize ---');
  send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: 1, clientCapabilities: {} } });
}, 1500);

setTimeout(() => {
  const gotInit = seen.some((l) => l.includes('"id":1') || l.includes('"id": 1'));
  console.log('=== initialize answered:', gotInit);
  if (gotInit) {
    console.log('--- send session/new ---');
    send({ jsonrpc: '2.0', id: 2, method: 'session/new', params: { cwd: process.cwd(), mcpServers: {} } });
  }
}, 7000);

setTimeout(() => {
  const gotNew = seen.some((l) => l.includes('"id":2') || l.includes('"id": 2'));
  console.log('=== session/new answered:', gotNew);
  console.log('=== total stdout lines:', seen.length);
  try { child.kill(); } catch {}
  process.exit(0);
}, 15000);
