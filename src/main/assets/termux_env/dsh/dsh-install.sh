#!/data/data/com.termux/files/usr/bin/bash
#==============================================================
# dsh-install v1.0.1 — Android/Termux 上的 @deepseek-ai/dsh 安装修补器
# 自包含 / 幂等 / 可重入：npm 升级把补丁冲掉后，再跑一次即可
# v1.0.1 修订：--check 在未安装时不再偷偷执行 npm 安装，直接中止
# 用法: dsh-install [--check|--reinstall|--smoke|--force]
#   --check      只体检，不动任何文件
#   --reinstall  强制重新 npm 安装（约10-15分钟）
#   --smoke      体检后顺带拉起 Web UI 实测
#   --force      即使已是补丁态也重新打补丁
#==============================================================
set -u
PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
PKG=@deepseek-ai/dsh
ROOT=$PREFIX/lib/node_modules/$PKG
BASE=$ROOT/node_modules
MIRROR=${DSH_NPM_MIRROR:-https://registry.npmmirror.com}
TAG=${DSH_TAG:-latest}
STATE=$HOME/.dsh
MODE=install; REINSTALL=0; SMOKE=0; FORCE=0
mkdir -p "$STATE"

for a in "$@"; do case "$a" in
  --check|-c) MODE=check ;;
  --reinstall) REINSTALL=1 ;;
  --smoke) SMOKE=1 ;;
  --force|-f) FORCE=1 ;;
  -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
esac; done

step(){ printf '\n[\033[36m%s\033[0m] %s\n' "$1" "$2"; }
ok(){   printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad(){  printf '  \033[31m✗\033[0m %s\n' "$1"; }
skip(){ printf '  \033[33m·\033[0m %s\n' "$1"; }
FAILED=0

# ---------- 1. 环境自检 ----------
step 1/7 "环境自检"
miss=0
for c in node npm curl; do
  if command -v $c >/dev/null 2>&1; then ok "$c $( (node -v 2>/dev/null; npm -v 2>/dev/null) | head -1 >/dev/null; echo -n '')$(case $c in node) node -v;; npm) npm -v;; curl) curl --version|head -1|cut -d' ' -f2;; esac)"
  else bad "缺少 $c"; miss=1; fi
done
[ $miss -eq 1 ] && { echo; echo "先装：pkg install nodejs-lts curl"; exit 1; }
FREE=$(df -Pk "$PREFIX" 2>/dev/null | awk 'NR==2{print int($4/1024/1024)}')
[ "${FREE:-99}" -lt 1 ] && bad "剩余空间仅 ${FREE}GB，npm 安装可能失败（建议 >1GB）" || ok "剩余空间 ${FREE}GB"

# ---------- 2. npm 源 ----------
step 2/7 "npm 源"
CUR=$(npm config get registry 2>/dev/null)
if [ "$CUR" = "$MIRROR/" ] || [ "$CUR" = "$MIRROR" ]; then ok "已是镜像源 $CUR"
else
  npm config set registry "$MIRROR" >/dev/null 2>&1
  if npm config get registry | grep -q npmmirror; then ok "已切换 $MIRROR（原 $CUR）"; else bad "切换失败，保持 $CUR"; fi
fi
if curl -s -m 8 -o /dev/null -w '%{http_code}' "$MIRROR" | grep -qE '^2|^3'; then ok "镜像连通性正常"
else bad "镜像不可达，可改 DSH_NPM_MIRROR 环境变量"; fi

# ---------- 3. npm 安装 ----------
step 3/7 "安装 $PKG"
if [ -f "$ROOT/lib/bin.js" ] && [ $REINSTALL -eq 0 ]; then
  VER=$(node -e "try{console.log(require('$ROOT/package.json').version)}catch(e){console.log('?')}" 2>/dev/null)
  skip "已安装 v$VER，跳过（--reinstall 可强制重装）"
else
  if [ "$MODE" = check ]; then
    bad "未安装 $PKG —— 体检模式不做安装，请直接运行 dsh-install"
    echo; echo "体检中止，未改动任何文件"; exit 1
  fi
  echo "  开始 npm 安装，约 10-15 分钟，日志 $STATE/npm-install.log"
  if npm i -g "$PKG@$TAG" --loglevel=warn > "$STATE/npm-install.log" 2>&1; then
    ok "安装完成 v$(node -e "try{console.log(require('$ROOT/package.json').version)}catch(e){console.log('?')}")"
  else
    bad "npm 安装失败，末尾日志："; tail -8 "$STATE/npm-install.log" | sed 's/^/      /'; exit 1
  fi
fi
[ "$MODE" = check ] && { echo; echo "体检模式：跳过后续修改"; }

# ---------- 4. 启动器 ----------
step 4/7 "启动器 $PREFIX/bin/dsh"
if [ "$MODE" != check ]; then
cat > "$PREFIX/bin/dsh" <<'LAUNCHER'
#!/data/data/com.termux/files/usr/bin/bash
exec node --expose-internals /data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh/lib/bin.js "$@"
LAUNCHER
chmod 700 "$PREFIX/bin/dsh"
fi
if head -1 "$PREFIX/bin/dsh" 2>/dev/null | grep -q bash && grep -q expose-internals "$PREFIX/bin/dsh" 2>/dev/null
then ok "bash wrapper + --expose-internals（绕过 /usr/bin/env node 缺失）"
else bad "启动器异常"; FAILED=1; fi

# ---------- 5. 打补丁 ----------
patch_flock(){
  local f=$BASE/@deepseek-ai/node-addon-system/lib/flock.js
  [ -f "$f" ] || { bad "flock.js 缺失（包结构变了？）"; return 1; }
  if grep -q 'patched for android-arm64' "$f" && [ $FORCE -eq 0 ]; then skip "flock 已是补丁态"; return 0; fi
  grep -q 'tryLockExclusive' "$f" || { bad "flock.js 未导出 tryLockExclusive，上游可能改版，为避免写坏已中止"; return 1; }
  [ -f "$f.orig" ] || cp "$f" "$f.orig"
  cat > "$f" <<'FLOCK_TPL'
// [patched for android-arm64: no native flock binding -> degrade to no-op lock]
export async function tryLockExclusive(fd) {
    const { platform, arch } = process;
    if (platform !== 'linux' && platform !== 'darwin') {
        // Android: libuv/node has no flock addon prebuilt; single-user local use, skip lock.
        return;
    }
    const { createRequire } = await import('node:module');
    const { dirname, join } = await import('node:path');
    const { getSystemErrorName } = await import('node:util');
    let binding;
    try {
        const require = createRequire(import.meta.url);
        const report = process.report.getReport();
        let filename = 'system.node';
        if (platform === 'linux') filename = join(report.header.glibcVersionRuntime ? 'glibc' : 'musl', filename);
        const manifest = require.resolve(`@deepseek-ai/node-addon-system-${platform}-${arch}/package.json`);
        binding = require(join(dirname(manifest), 'bin', filename));
    } catch { return; }
    const errno = await new Promise((resolve) => { binding.tryLock(fd, resolve); });
    if (errno === 0) return;
    const code = getSystemErrorName(-errno);
    throw Object.assign(new Error(`${code}: flock failed`), { code, errno, syscall: 'flock' });
}
FLOCK_TPL
  ok "flock → 无原生绑定时自动降级" ; return 0
}

patch_reqbuiltin(){
  local f=$BASE/node-addon-require-builtin/lib/index.js
  [ -f "$f" ] || { bad "require-builtin 缺失"; return 1; }
  if grep -q '_tryReal' "$f" && [ $FORCE -eq 0 ]; then skip "require-builtin 已是补丁态"; return 0; fi
  grep -q 'requireBuiltin' "$f" || { bad "上游 API 变了，已中止"; return 1; }
  [ -f "$f.orig" ] || cp "$f" "$f.orig"
  cat > "$f" <<'RQB_TPL'
"use strict";
// [patched for android-arm64] no prebuilt native binding -> use --expose-internals + require()
let _real = null;
function _tryReal() {
  if (_real !== null) return _real;
  try {
    const { createEntryApi } = require('node-addon-native-custom-loader');
    const path = require('node:path');
    _real = createEntryApi(path.resolve(__dirname, '..'));
  } catch (e) { _real = false; }
  return _real;
}
function requireBuiltin(moduleId) {
  const real = _tryReal();
  if (real) return real.requireBuiltin(moduleId);
  return require(moduleId);
}
function isAllowedInternalId(moduleId) { const r=_tryReal(); return r ? r.isAllowedInternalId(moduleId) : true; }
function getBindingInfo() { const r=_tryReal(); return r ? r.getBindingInfo() : { available:false, mode:'expose-internals' }; }
module.exports = { requireBuiltin, isAllowedInternalId, getBindingInfo, default: { requireBuiltin, isAllowedInternalId, getBindingInfo } };
RQB_TPL
  ok "require-builtin → 退回 require + --expose-internals"; return 0
}

patch_hardlink(){
  local f=$BASE/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js
  [ -f "$f" ] || { bad "persistence 模块缺失"; return 1; }
  cat > "$STATE/_hl.js" <<'HL_JS'
const fs=require('fs');const p=process.argv[2];
let s=fs.readFileSync(p,'utf8');const o=s;let h=0;
const MAP=[['await internals.fs.link(staged, currentPath);','await __linkFallback(staged, currentPath);'],
           ['await link(tmp, finalPath);','await __linkFallback(tmp, finalPath);']];
for(const [a,b] of MAP){ if(s.includes(a)){ s=s.split(a).join(b); h++; } }
if(!s.includes('async function __linkFallback')){
  s+=`
/* [patched for android-arm64] hardlink() is denied (EACCES) under Android app data dirs. */
async function __linkFallback(src, dst) {
    try { await link(src, dst); }
    catch (e) {
        if (e && (e.code === 'EACCES' || e.code === 'EPERM' || e.code === 'ENOTSUP' || e.code === 'EXDEV' || e.code === 'EOPNOTSUPP')) {
            const { copyFile } = await import('node:fs/promises');
            await copyFile(src, dst); return;
        }
        throw e;
    }
}
`; h++; }
if(s===o){ console.log('ALREADY'); process.exit(0); }
if(!fs.existsSync(p+'.orig')) fs.copyFileSync(p,p+'.orig');
fs.writeFileSync(p,s); console.log('PATCHED '+h);
HL_JS
  local r; r=$(node "$STATE/_hl.js" "$f" 2>&1)
  case "$r" in
    ALREADY) skip "硬链接兜底已是补丁态" ;;
    PATCHED*) ok "硬链接 → EACCES/EPERM 时改用 copyFile（$r）" ;;
    *) bad "硬链接补丁失败: $r"; return 1 ;;
  esac
}

if [ "$MODE" != check ]; then
  step 5/7 "打 Android 补丁"
  patch_flock || FAILED=1
  patch_reqbuiltin || FAILED=1
  patch_hardlink || FAILED=1
else
  step 5/7 "补丁状态（只读检查）"
  for pair in "flock:$BASE/@deepseek-ai/node-addon-system/lib/flock.js:patched for android-arm64" \
              "require-builtin:$BASE/node-addon-require-builtin/lib/index.js:_tryReal" \
              "硬链接兜底:$BASE/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js:__linkFallback"; do
    n=${pair%%:*}; rest=${pair#*:}; p=${rest%:*}; m=${rest##*:}
    if [ -f "$p" ] && grep -q "$m" "$p"; then ok "$n 已打"; else bad "$n 未打"; FAILED=1; fi
  done
fi

# ---------- 6. 运行时验证 ----------
step 6/7 "运行时验证（真跑，不看文件）"
V=$(timeout 90 dsh --version 2>&1 | head -1)
case "$V" in
  *[0-9].[0-9]*) ok "dsh --version → $V" ;;
  *) bad "dsh 启动失败：$(echo "$V" | head -2)"; FAILED=1 ;;
esac
FR=$(node --expose-internals --input-type=module -e "import('file://$BASE/@deepseek-ai/node-addon-system/lib/flock.js').then(m=>m.tryLockExclusive(1)).then(()=>console.log('OK')).catch(e=>{console.error('FAIL:'+e.message);process.exit(1)})" 2>&1 | tail -1)
case "$FR" in OK) ok "flock 调用链路 → OK（android 上安全降级）" ;; *) bad "flock 验证失败：$FR"; FAILED=1 ;; esac
PR=$(node --expose-internals --input-type=module -e "import('file://$BASE/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js').then(()=>console.log('OK')).catch(e=>{console.error('FAIL:'+e.message);process.exit(1)})" 2>&1 | tail -1)
case "$PR" in OK) ok "session-persistence 模块加载 → OK" ;; *) bad "persistence 加载失败：$PR"; FAILED=1 ;; esac

# ---------- 7. 收尾 ----------
step 7/7 "收尾"
if [ "$MODE" != check ]; then
  printf 'installed=%s\npatched_at=%s\nver=%s\n' "$(date -Iseconds)" "$(date -Iseconds)" "$V" > "$STATE/install-state"
  ok "状态写入 $STATE/install-state"
fi
if command -v dsh-serve >/dev/null 2>&1; then ok "dsh-serve 可用（start/stop/status/url/open/log）"
else skip "未装 dsh-serve（Web UI 启动器，可选）"; fi

echo
if [ $FAILED -eq 0 ]; then
  printf '\033[32m═══ 全部通过 ═══\033[0m\n'
  echo "启动 Web UI: dsh-serve start"
  [ $SMOKE -eq 1 ] && { echo; echo "--- smoke: 拉起 Web UI ---"; dsh-serve start; }
  exit 0
else
  printf '\033[31m═══ 有项失败，见上方 ✗ ═══\033[0m\n'
  echo "原始文件已备份为 *.orig：$BASE/.../flock.js.orig 等"
  exit 1
fi
