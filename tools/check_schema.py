"""Loads db/schema.sql into SQLite and proves its constraints work; checks contracts/states.json against it.
Run: python3 tools/check_schema.py   (exit code 1 on any failure)"""
import json, os, re, sqlite3, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
errors = []

def err(m): errors.append(m)

schema = open(os.path.join(ROOT, "db", "schema.sql"), encoding="utf-8").read()
db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys = ON")
try:
    db.executescript(schema)
except Exception as e:  # noqa
    print("ERROR schema does not load:", e); sys.exit(1)

def fails(sql, args=()):
    try:
        db.execute(sql, args); return False
    except sqlite3.IntegrityError:
        return True

NOW = 1760000000000
db.execute("INSERT INTO conversation(started_at) VALUES (?)", (NOW,))
db.execute("INSERT INTO message(conversation_id,role,content,created_at) VALUES (1,'user','hi',?)", (NOW,))
db.execute("INSERT INTO target(name,target,schedule,start_day) VALUES ('Lectures',2,'Sat-Thu','2026-10-10')")
db.execute("INSERT INTO checkin(target_id,day_key,level,entered_at) VALUES (1,'2026-10-10','target',?)", (NOW,))

checks = [
    ("bad message status rejected", fails("INSERT INTO message(conversation_id,role,content,created_at,status) VALUES (1,'user','x',?, 'sending')", (NOW,))),
    ("bad role rejected", fails("INSERT INTO message(conversation_id,role,content,created_at) VALUES (1,'system','x',?)", (NOW,))),
    ("message needs a real conversation", fails("INSERT INTO message(conversation_id,role,content,created_at) VALUES (99,'user','x',?)", (NOW,))),
    ("second answer for the same target and day rejected", fails("INSERT INTO checkin(target_id,day_key,level,entered_at) VALUES (1,'2026-10-10','not',?)", (NOW,))),
    ("bad check-in level rejected", fails("INSERT INTO checkin(target_id,day_key,level,entered_at) VALUES (1,'2026-10-11','done',?)", (NOW,))),
    ("negative amount rejected", fails("INSERT INTO checkin(target_id,day_key,level,amount,entered_at) VALUES (1,'2026-10-12','target',-1,?)", (NOW,))),
    ("minimum above target rejected", fails("INSERT INTO target(name,minimum,target,schedule,start_day) VALUES ('x',3,2,'daily','2026-10-10')")),
    ("zero backlog total rejected", fails("INSERT INTO backlog(name,total,unit,deadline,created_at) VALUES ('x',0,'u','2026-11-20',?)", (NOW,))),
    ("bad promise status rejected", fails("INSERT INTO promise(text,due_at,due_text,status,created_at) VALUES ('x',?, 'now','done',?)", (NOW, NOW))),
    ("bad note confidence rejected", fails("INSERT INTO note(kind,text,confidence,first_seen,last_seen,created_at) VALUES ('pattern','x','sure','2026-10-10','2026-10-10',?)", (NOW,))),
    ("bad card type rejected", fails("INSERT INTO card(message_id,type,payload_json,created_at) VALUES (1,'reminder','{}',?)", (NOW,))),
    ("duplicate letter key rejected", None),
    ("duplicate job key rejected", None),
]
db.execute("INSERT INTO letter(period_start,period_end,title,body,ready_at,key) VALUES ('2026-10-05','2026-10-11','t','b',?,'letter:week:2026-10-05')", (NOW,))
checks[-2] = ("duplicate letter key rejected", fails("INSERT INTO letter(period_start,period_end,title,body,ready_at,key) VALUES ('2026-10-05','2026-10-11','t2','b2',?,'letter:week:2026-10-05')", (NOW,)))
db.execute("INSERT INTO job(key,type,created_at) VALUES ('reflection:1:abc','reflection',?)", (NOW,))
checks[-1] = ("duplicate job key rejected", fails("INSERT INTO job(key,type,created_at) VALUES ('reflection:1:abc','reflection',?)", (NOW,)))
db.execute("INSERT INTO conversation(started_at) VALUES (?)", (NOW,))  # id 2
db.execute("INSERT INTO note(kind,text,first_seen,last_seen,created_at) VALUES ('value','x','2026-10-10','2026-10-10',?)", (NOW,))
db.execute("INSERT INTO note_evidence(note_id,kind,text,day,conversation_id) VALUES (1,'quote','his words','2026-10-10',2)")
db.execute("INSERT INTO rest_day(day_key,created_at) VALUES ('2026-10-16',?)", (NOW,))
db.execute("INSERT INTO reflection_item(conversation_id,kind,text,day,created_at) VALUES (2,'idea','if-then plan','2026-10-10',?)", (NOW,))
checks += [
    ("a target with check-ins cannot be deleted", fails("DELETE FROM target WHERE id=1")),
    ("negative start_done rejected", fails("INSERT INTO backlog(name,total,unit,deadline,start_done,created_at) VALUES ('x',10,'u','2026-11-20',-1,?)", (NOW,))),
    ("start_done above total rejected", fails("INSERT INTO backlog(name,total,unit,deadline,start_done,created_at) VALUES ('x',10,'u','2026-11-20',11,?)", (NOW,))),
    ("bad reflection item kind rejected", fails("INSERT INTO reflection_item(kind,text,day,created_at) VALUES ('note','x','2026-10-10',?)", (NOW,))),
    ("one rest day per date", fails("INSERT INTO rest_day(day_key,created_at) VALUES ('2026-10-16',?)", (NOW,))),
]
db.execute("DELETE FROM conversation WHERE id=2")
if db.execute("SELECT COUNT(*) FROM note_evidence").fetchone()[0] != 0:
    err("forgetting a conversation did not remove the evidence taken from it")
if db.execute("SELECT COUNT(*) FROM reflection_item").fetchone()[0] != 0:
    err("forgetting a conversation did not remove what reflection learned from it")
for name, ok in checks:
    if not ok:
        err("constraint did not hold: " + name)

# cascade: deleting a conversation removes its messages
db.execute("DELETE FROM conversation WHERE id=1")
if db.execute("SELECT COUNT(*) FROM message").fetchone()[0] != 0:
    err("deleting a conversation did not remove its messages")

# search works
db.execute("INSERT INTO search_doc(kind,ref_id,title,body) VALUES ('note','1','t','I start properly from Monday')")
if db.execute("SELECT COUNT(*) FROM search_doc WHERE search_doc MATCH 'monday'").fetchone()[0] != 1:
    err("full text search did not match")

# state machines agree with the schema
states = json.load(open(os.path.join(ROOT, "contracts", "states.json"), encoding="utf-8"))["machines"]
for name, m in states.items():
    sql = db.execute("SELECT sql FROM sqlite_master WHERE name=?", (m["table"],)).fetchone()[0]
    mm = re.search(r"%s TEXT NOT NULL[^,]*CHECK \(%s IN \(([^)]*)\)" % (m["column"], m["column"]), sql)
    if not mm:
        err("%s: no CHECK list found for %s.%s" % (name, m["table"], m["column"])); continue
    allowed = set(re.findall(r"'([a-z_]+)'", mm.group(1)))
    if allowed != set(m["states"]):
        err("%s: states %s differ from the schema %s" % (name, sorted(m["states"]), sorted(allowed)))
    for a, outs in m["transitions"].items():
        if a not in m["states"] or any(o not in m["states"] for o in outs):
            err("%s: transition mentions an unknown state (%s)" % (name, a))
    for a, b in m["repair_on_start"].items():
        if b not in m["transitions"].get(a, []):
            err("%s: repair %s -> %s is not an allowed transition" % (name, a, b))
    for s in m["start"]:
        if s not in m["states"]:
            err("%s: unknown start state %s" % (name, s))

tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'search_doc_%' AND name NOT LIKE 'sqlite_%'")]
if "fallbackToDestructiveMigration" in schema:
    err("schema mentions destructive migration")
if errors:
    print("\n".join("ERROR " + e for e in errors)); print("\n%d problem(s)" % len(errors)); sys.exit(1)
print("schema checks passed: %d tables, %d constraint checks, %d state machines" % (len(tables), len(checks), len(states)))
