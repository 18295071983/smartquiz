#!/data/data/com.termux/files/usr/bin/bash
# dsh-serve 已并入 dsh-phone：老命令继续可用，但启动的是带桥接的 phone profile
exec /data/data/com.termux/files/usr/bin/dsh-phone "$@"
