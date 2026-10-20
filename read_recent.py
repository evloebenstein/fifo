import json

with open('db_dump.json', 'r', encoding='utf-8') as f:
    d = json.load(f)

with open('recent_output.txt', 'w', encoding='utf-8') as out:
    for table, info in d.items():
        out.write(f"\n=== {table} ({info['count']} rows) ===\n")
        for row in info['rows'][:5]:
            out.write(f"  {json.dumps(row, ensure_ascii=False)}\n")

print("Done")
