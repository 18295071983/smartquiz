/**
 * 配对地址分类与排序（2026-11-16，从 index.js 抽出）。
 *
 * <p><b>为什么单独成模块</b>：用户反馈"我用的是局域网，面板却显示成公网隧道"——
 * 根因是旧实现只分"局域网 / 公网"两类，任何填进 publicUrl 的地址都被叫"公网/隧道"，
 * 而 Tailscale / ZeroTier 这类组网地址（100.64.0.0/10）既不是公网也不是家里局域网。
 * 分类错 → 文案错 → 用户以为要开隧道。这里把"怎么判断"集中到一处，可单测。</p>
 */

/** 内网/家庭局域网：10/8、172.16/12、192.168/16，以及回环。 */
export function isLanIp(ip) {
  const s = String(ip ?? '').trim();
  if (!s) return false;
  if (s === '127.0.0.1' || s === 'localhost') return true;
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(s);
  if (!m) return false;
  const a = Number(m[1]);
  const b = Number(m[2]);
  if (a === 10) return true;
  if (a === 172 && b >= 16 && b <= 31) return true;
  if (a === 192 && b === 168) return true;
  return false;
}

/** 组网（VPN mesh）：Tailscale/ZeroTier 常用的 100.64.0.0/10；不是公网、也不是家庭局域网。 */
export function isMeshIp(ip) {
  const m = /^(\d{1,3})\.(\d{1,3})\./.exec(String(ip ?? '').trim());
  if (!m) return false;
  return Number(m[1]) === 100 && Number(m[2]) >= 64 && Number(m[2]) <= 127;
}

/**
 * 给一个地址（IP 或域名，可带端口/协议）分类。
 *
 * @returns {{kind: 'lan'|'mesh'|'public', label: string}}
 */
export function classifyAddress(address) {
  const raw = String(address ?? '').trim();
  if (!raw) return { kind: 'public', label: '公网' };
  let host = raw;
  try {
    host = new URL(raw.includes('://') ? raw : `http://${raw}`).hostname;
  } catch {
    host = raw.replace(/^[a-z]+:\/\//i, '').split('/')[0].split(':')[0];
  }
  if (isLanIp(host)) return { kind: 'lan', label: '局域网' };
  if (isMeshIp(host)) return { kind: 'mesh', label: '组网（VPN）' };
  return { kind: 'public', label: '公网' };
}

/** 排序权重：局域网最先（用户多数场景），其次组网，最后公网。 */
export function kindRank(kind) {
  if (kind === 'lan') return 0;
  if (kind === 'mesh') return 1;
  return 2;
}

/**
 * 组装配对候选：局域网多网卡 → 组网/公网（publicUrl）。
 *
 * @param {string[]} ips      本机 IPv4 列表
 * @param {number}   port     监听端口
 * @param {string}   token    主令牌
 * @param {string}   publicUrl 可选：用户在面板里填的地址（可能是组网地址，也可能是真公网域名）
 * @returns {Array<{ip:string,base_url:string,qr_text:string,label:string,kind:string,recommended:boolean}>}
 */
export function buildCandidates(ips, port, token, publicUrl) {
  const out = (ips || []).map((ip) => {
    const c = classifyAddress(ip);
    return {
      ip,
      base_url: `http://${ip}:${port}`,
      qr_text: `dshpair://${ip}:${port}?token=${token}`,
      label: c.label,
      kind: c.kind,
    };
  });
  const pub = String(publicUrl ?? '').trim();
  if (pub) {
    try {
      const u = new URL(pub);
      const https = u.protocol === 'https:';
      const c = classifyAddress(pub);
      out.push({
        ip: u.hostname,
        base_url: pub.replace(/\/+$/, ''),
        qr_text: https
          ? `dshpair://${u.hostname}?scheme=https&port=443&token=${token}`
          : `dshpair://${u.hostname}:${u.port || 80}?token=${token}`,
        label: c.label,
        kind: c.kind,
      });
    } catch {
      // 非法地址直接忽略：面板会提示"地址需以 http:// 或 https:// 开头"
    }
  }
  // 稳定排序：kind 权重 → 原始顺序；第一条标 recommended（手机扫码默认用它）
  // 去重（2026-11-16）：本机可能同时有多张网卡/组网接口指向同一地址
  // （真机见过 100.113.188.52 出现两次），按 base_url 去重后再排序。
  const seen = new Set();
  const unique = out.filter((c) => {
    const key = String(c.base_url || c.ip || '');
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  const withIndex = unique.map((c, i) => ({ c, i }));
  withIndex.sort((x, y) => kindRank(x.c.kind) - kindRank(y.c.kind) || x.i - y.i);
  return withIndex.map(({ c }, idx) => ({ ...c, recommended: idx === 0 }));
}
