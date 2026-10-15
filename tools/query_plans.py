#!/usr/bin/env python3
"""Every query the app runs (the @Query strings in the Room DAOs), planned by SQLite on the database the app
really creates: Room's exported schema (app/schemas/, newest version), which also holds the indexes Room adds for
foreign keys that db/schema.sql does not list.

Max plan Phase 5: at ten years some tables hold tens of thousands of rows, and a query that reads a whole one
of them where it meant to read a few rows costs a second on the phone. This lists, for each query, whether it
reads a big table from end to end (SCAN) instead of through an index (SEARCH), and fails when one does that is
not in ALLOWED_SCANS with a reason. Run: python3 tools/query_plans.py [--all]
"""
import json
import pathlib
import re
import sqlite3
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DAO_DIR = ROOT / "app/src/main/java/com/clarity/life/data/dao"
SCHEMAS = ROOT / "app/schemas/com.clarity.life.data.ClarityDatabase"

# Tables that grow with every day of use (ten years: tens of thousands of rows or more).
BIG = {"message", "conversation", "checkin", "promise", "card", "note_evidence", "event", "quote",
       "embedding_row", "search_doc", "reflection_item", "error_log", "job", "goal_revision"}

# Whole-table reads that are meant: (DAO function, table) -> why.
ALLOWED_SCANS = {
    ("allQuotes", "quote"): "Rebuild search: reads every quote once, by design",
    ("allEvents", "event"): "Rebuild search: reads every event once, by design",
    ("allPromises", "promise"): "Rebuild search: reads every promise once, by design",
    ("deleteOtherModels", "embedding_row"): "once, after the meaning model changes",
    ("clearAllPrefixes", "conversation"): "after Forget or a restore: every conversation's frozen part goes",
    ("clearPrefixesExcept", "conversation"): "after a note changes: every other conversation's frozen part goes",
    ("watchPast", "promise"): "Plan, past promises: ordered by when answered (an expression), at most a few hundred",
    ("promisesAnswered", "promise"): "the monthly story: a count over a few thousand rows, in the background",
    ("quoteCount", "quote"): "reflection, in the background: a few times per conversation",
}


def queries():
    """(file, function, sql) for every @Query in the DAOs."""
    out = []
    for path in sorted(DAO_DIR.glob("*.kt")):
        src = path.read_text()
        for m in re.finditer(r"@Query\(\s*((?:\"(?:[^\"\\]|\\.)*\"\s*\+?\s*)+)\)", src):
            sql = "".join(re.findall(r"\"((?:[^\"\\]|\\.)*)\"", m.group(1))).replace('\\"', '"')
            fn = re.search(r"fun\s+(\w+)", src[m.end():])
            out.append((path.name, fn.group(1) if fn else "?", sql))
    return out


def bindable(sql):
    """Room's :name parameters as SQLite ones; a list parameter IN (:ids) becomes IN (?, ?)."""
    sql = re.sub(r"\(\s*:(\w+)\s*\)", "(?, ?)", sql)
    return re.sub(r":(\w+)", "?", sql)


def room_database():
    """An empty in-memory database made exactly as Room makes the newest version: tables, then indexes."""
    newest = max(SCHEMAS.glob("*.json"), key=lambda f: int(f.stem))
    schema = json.loads(newest.read_text())["database"]
    db = sqlite3.connect(":memory:")
    for e in schema["entities"]:
        db.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for i in e.get("indices", []):
            db.execute(i["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    return db


def main():
    db = room_database()
    show_all = "--all" in sys.argv
    bad = []
    n = 0
    for file, fn, sql in queries():
        n += 1
        q = bindable(sql)
        try:
            plan = db.execute("EXPLAIN QUERY PLAN " + q, [None] * q.count("?")).fetchall()
        except sqlite3.Error as e:
            bad.append(f"{file} {fn}: SQLite cannot plan it ({e})")
            continue
        details = [row[3] for row in plan]
        scans = []
        # A scan in the table's own order (no sorting step) that stops at a LIMIT reads only those rows.
        stops_early = "LIMIT" in sql.upper() and not any("TEMP B-TREE" in d for d in details)
        for d in details:
            if stops_early:
                continue
            m = re.match(r"SCAN (\w+)(?: AS \w+)?(.*)", d)
            if m and m.group(1) in BIG and "VIRTUAL TABLE INDEX" not in m.group(2) and "COVERING INDEX" not in m.group(2):
                if "USING INDEX" not in m.group(2) and "USING INTEGER PRIMARY KEY" not in m.group(2):
                    scans.append(m.group(1))
                elif (fn, m.group(1)) not in ALLOWED_SCANS and "ORDER BY" not in sql.upper() and "COUNT(" not in sql.upper():
                    scans.append(m.group(1) + " (whole index)")
        for t in scans:
            key = (fn, t.split()[0])
            if key not in ALLOWED_SCANS:
                bad.append(f"{file} {fn}: reads all of {t}")
        if show_all:
            print(f"{file} {fn}: " + " | ".join(details))
    for line in bad:
        print("SLOW QUERY:", line)
    print(f"query plans: {n} queries, {len(bad)} reading a big table end to end without a reason")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
