import sqlite3
import json

conn = sqlite3.connect('fifo_local.db')
cursor = conn.cursor()
tables = [r[0] for r in cursor.execute("SELECT name FROM sqlite_master WHERE type='table';").fetchall()]

result = {}
for t in tables:
    count = cursor.execute(f"SELECT count(*) FROM {t};").fetchone()[0]
    rows = cursor.execute(f"SELECT * FROM {t} ORDER BY rowid DESC LIMIT 20;").fetchall()
    col_names = [description[0] for description in cursor.description]
    result[t] = {
        "count": count,
        "columns": col_names,
        "rows": [dict(zip(col_names, r)) for r in rows]
    }

with open("db_dump.json", "w", encoding="utf-8") as f:
    json.dump(result, f, ensure_ascii=False, indent=2)

print("Dumped db to db_dump.json successfully")
