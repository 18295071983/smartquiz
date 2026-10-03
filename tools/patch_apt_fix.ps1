# step4/step5 apt 加固：DNS 写死 + 源直接覆盖（不依赖 sed 匹配格式）
$ErrorActionPreference = 'Stop'
$nl = "`n"
$p = "D:\qzq\smartquiz\src\main\java\com\oilquiz\app\ai\tool\TermuxEnvInstaller.java"
$t = [IO.File]::ReadAllText($p)

# ---- step4：替换源处理段 ----
# 旧：sed 逐个替换 + HAVE_SRC 判断
$old4 = @'
                + "  proot-distro login ubuntu -- /bin/bash -lc 'for f in /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; do if [ -f \"$f\" ]; then sed -i \"s|https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports|http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports|g; s|http://ports.ubuntu.com/ubuntu-ports|http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports|g; s|http://archive.ubuntu.com/ubuntu|http://mirrors.tuna.tsinghua.edu.cn/ubuntu|g; s|http://security.ubuntu.com/ubuntu|http://mirrors.tuna.tsinghua.edu.cn/ubuntu|g\" \"$f\"; fi; done; HAVE_SRC=0; [ -f /etc/apt/sources.list ] && grep -q ubuntu /etc/apt/sources.list && HAVE_SRC=1; [ -f /etc/apt/sources.list.d/ubuntu.sources ] && grep -q URIs /etc/apt/sources.list.d/ubuntu.sources && HAVE_SRC=1; if [ \"$HAVE_SRC\" = 0 ]; then mkdir -p /etc/apt/sources.list.d; cat > /etc/apt/sources.list.d/quiz-ubuntu.list <<QSRC
'@
$old4 += $nl
$old4 += @'
                + "deb [trusted=yes] http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble main universe multiverse restricted
'@ + $nl
$old4 += @'
                + "deb [trusted=yes] http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble-updates main universe multiverse restricted
'@ + $nl
$old4 += @'
                + "deb [trusted=yes] http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble-security main universe multiverse restricted
'@ + $nl
$old4 += @'
                + "QSRC
'@ + $nl
$old4 += @'
                + "fi; apt-get update -y
'@

$new4 = @'
                + "  proot-distro login ubuntu -- /bin/bash -lc 'printf \"nameserver 8.8.8.8\\nnameserver 223.5.5.5\\nnameserver 114.114.114.114\\n\" > /etc/resolv.conf; "
                + "mkdir -p /etc/apt/sources.list.d; "
                + "cat > /etc/apt/sources.list.d/quiz-ubuntu.list <<QSRC
'@ + $nl
$new4 += @'
                + "deb [trusted=yes] http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble main universe multiverse restricted
'@ + $nl
$new4 += @'
                + "deb [trusted=yes] http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble-updates main universe multiverse restricted
'@ + $nl
$new4 += @'
                + "deb [trusted=yes] http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble-security main universe multiverse restricted
'@ + $nl
$new4 += @'
                + "QSRC
'@ + $nl
$new4 += @'
                + "rm -f /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; "
                + "apt-get update -y
'@

if ($t.Contains($old4)) { $t = $t.Replace($old4, $new4); Write-Output "step4: OK" } else { Write-Output "step4: MISS" }

# ---- step5：GUI 安装前同样 DNS + 源覆盖（幂等） ----
$old5 = @'
                + "  proot-distro login ubuntu -- /bin/bash -lc 'export DEBIAN_FRONTEND=noninteractive; apt-get update -y && apt-get install -y --no-install-recommends tigervnc-standalone-server
'@
$new5 = @'
                + "  proot-distro login ubuntu -- /bin/bash -lc 'printf \"nameserver 8.8.8.8\\nnameserver 223.5.5.5\\nnameserver 114.114.114.114\\n\" > /etc/resolv.conf; "
                + "mkdir -p /etc/apt/sources.list.d; "
                + "cat > /etc/apt/sources.list.d/quiz-ubuntu.list <<QSRC
'@ + $nl
$new5 += @'
                + "deb [trusted=yes] http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble main universe multiverse restricted
'@ + $nl
$new5 += @'
                + "deb [trusted=yes] http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble-updates main universe multiverse restricted
'@ + $nl
$new5 += @'
                + "deb [trusted=yes] http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble-security main universe multiverse restricted
'@ + $nl
$new5 += @'
                + "QSRC
'@ + $nl
$new5 += @'
                + "rm -f /etc/apt/sources.list /etc/apt/sources.list.d/ubuntu.sources; "
                + "export DEBIAN_FRONTEND=noninteractive; apt-get update -y && apt-get install -y --no-install-recommends tigervnc-standalone-server
'@
if ($t.Contains($old5)) { $t = $t.Replace($old5, $new5); Write-Output "step5: OK" } else { Write-Output "step5: MISS" }

[IO.File]::WriteAllText($p, $t, (New-Object Text.UTF8Encoding $false))
Write-Output "已写回"
