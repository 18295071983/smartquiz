import sqlite3, sys, shutil, os, time

db = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\xiaocong\AppData\Roaming\@deepseek-ai\dsh-desktop\Network\Cookies"
print("db exists:", os.path.exists(db), "size:", os.path.getsize(db) if os.path.exists(db) else -1)

con = sqlite3.connect(db)
cur = con.cursor()
cur.execute("select host_key, count(*) from cookies group by host_key order by 2 desc limit 8")
print("top hosts:", cur.fetchall())
cur.execute("select count(*) from cookies where name like 'dsh-auth%'")
print("dsh-auth total:", cur.fetchone()[0])
cur.execute("select host_key, name, length(value)+length(encrypted_value), creation_utc from cookies where name like 'dsh-auth%' order by creation_utc desc")
rows = cur.fetchall()
for r in rows[:5]:
    print("  newest:", r)
print("  oldest:", rows[-1] if rows else None)
con.close()
