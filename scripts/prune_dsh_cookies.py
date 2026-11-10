"""清理 DSH 桌面端累积的 dsh-auth-* cookie。

背景（2026-11-10 定位）：每次启动都会新写一个 dsh-auth-* cookie，从没清理过，
Cookie 头把 Node http 的 16KB 请求头上限顶爆 → 431 → desktop welcome: Web authentication failed。

做法：
1. 备份 Cookies 到 <同目录>/Cookies.bak-<时间戳>
2. 每个 host 只保留 creation_utc 最大的那条 dsh-auth-*，其余删除
3. VACUUM 压缩
4. 打印前后条数
"""
import os
import shutil
import sqlite3
import sys
import time

DB = r"C:\Users\xiaocong\AppData\Roaming\@deepseek-ai\dsh-desktop\Network\Cookies"
if len(sys.argv) > 1:
    DB = sys.argv[1]

if not os.path.exists(DB):
    print("DB not found:", DB)
    sys.exit(1)

backup = DB + ".bak-" + time.strftime("%Y%m%d-%H%M%S")
shutil.copy2(DB, backup)
print("backup:", backup)

con = sqlite3.connect(DB)
cur = con.cursor()
cur.execute("select count(*) from cookies")
print("cookies before:", cur.fetchone()[0])
cur.execute("select count(*) from cookies where name like 'dsh-auth%'")
print("dsh-auth before:", cur.fetchone()[0])

cur.execute("""
    delete from cookies
    where name like 'dsh-auth%'
      and rowid not in (
        select rowid from (
          select rowid, row_number() over (partition by host_key order by creation_utc desc) as rn
          from cookies where name like 'dsh-auth%'
        ) where rn = 1
      )
""")
deleted = cur.rowcount
con.commit()
cur.execute("vacuum")
con.commit()
cur.execute("select count(*) from cookies where name like 'dsh-auth%'")
print("deleted:", deleted, " dsh-auth after:", cur.fetchone()[0])
con.close()
print("done")
