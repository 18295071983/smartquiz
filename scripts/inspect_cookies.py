import sqlite3, os, sys

db = r"C:\Users\xiaocong\.workbuddy\app\session\Network\Cookies"
uri = "file:" + db.replace("\\", "/") + "?immutable=1"
con = sqlite3.connect(uri, uri=True)
cur = con.cursor()
cur.execute("select name from sqlite_master where type='table'")
print("tables:", [r[0] for r in cur.fetchall()])
cur.execute("select host_key, count(*) from cookies group by host_key order by 2 desc limit 10")
print("top hosts:", cur.fetchall())
cur.execute("select count(*) from cookies where name like 'dsh-auth%'")
print("dsh-auth cookies:", cur.fetchone()[0])
cur.execute("select host_key, name, length(value), length(encrypted_value), creation_utc from cookies where name like 'dsh-auth%' order by creation_utc desc limit 5")
for row in cur.fetchall():
    print(" ", row)
con.close()
