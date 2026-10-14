"""Writes the sample backups in contracts/backups/ and contracts/backups/backup_vectors.json (docs/MAX-PLAN.md, Phase 1, step 3).

One sample per backup format and database version the app has written: format 1 (one sealed piece, before
streaming) at database versions 1 and 3, and format 2 (sealed in 64 KB chunks) at versions 3, 4, 5 and 6. Each holds
real-looking rows of that version's tables only (columns from app/schemas/.../N.json), so restoring it is what
restoring a backup made by that older app would be. The newest one is large enough to need several chunks.

The files are made the same way every time (fixed salt, nonce and gzip time); only another Python or zlib
version may pack the gzip bytes a little differently. Run it again only when a new sample is added, and commit
what it writes. Run: python3 tools/make_backup_fixtures.py
"""
import hashlib
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import backup_format as bf  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
SCHEMAS = os.path.join(ROOT, "app", "schemas", "com.clarity.life.data.ClarityDatabase")
OUT = os.path.join(ROOT, "contracts", "backups")
VECTORS = os.path.join(OUT, "backup_vectors.json")

PASSPHRASE = "paper passphrase — kept ✓"
ITERATIONS = 100_000
CREATED_AT = 1_791_600_000_000  # 10 Oct 2026, 00:00 UTC
DERIVED = {"search_doc", "embedding_row"}

SAMPLES = [
    # (file, format, schema, extra messages to make it span several chunks)
    ("format1-schema1.cbk", 1, 1, 0),
    ("format1-schema3.cbk", 1, 3, 0),
    ("format2-schema3.cbk", 2, 3, 0),
    ("format2-schema4.cbk", 2, 4, 0),
    ("format2-schema5.cbk", 2, 5, 450),
    ("format2-schema6.cbk", 2, 6, 0),
]


def columns(schema):
    with open(os.path.join(SCHEMAS, "%d.json" % schema), encoding="utf-8") as f:
        d = json.load(f)
    return {e["tableName"]: [c["columnName"] for c in e["fields"]] for e in d["database"]["entities"]}


def life(extra_messages):
    """Rows as the newest database holds them; older versions get the columns and tables they had."""
    t0 = CREATED_AT - 40 * 86_400_000
    day = lambda n: "2026-09-%02d" % n if n <= 30 else "2026-10-%02d" % (n - 30)
    rows = {
        "focus": [{"id": 1, "name": "Exams in November", "why": "So the year counts.", "status": "active", "summary": "", "created_at": t0, "updated_at": t0}],
        "conversation": [
            {"id": 1, "title": "Mornings", "chat_type": "free", "focus_id": 1, "started_at": t0, "ended_at": t0 + 3_600_000,
             "summary": "He studies best before ten.", "reflected_up_to_message_id": 2, "off_the_record": 0,
             "opened_from": None, "opened_from_id": None, "prefix_json": "{\"frozen\":true}", "prefix_day": day(1), "history_start_id": 0},
            {"id": 2, "title": "", "chat_type": "untangle", "focus_id": None, "started_at": t0 + 86_400_000, "ended_at": None,
             "summary": "", "reflected_up_to_message_id": 0, "off_the_record": 1,
             "opened_from": None, "opened_from_id": None, "prefix_json": None, "prefix_day": None, "history_start_id": 0},
        ],
        "message": [
            {"id": 1, "conversation_id": 1, "role": "user", "content": "I only study well in the morning — before 10, with tea ✓\nAfter lunch nothing sticks. \"Every time.\"",
             "created_at": t0, "status": "complete", "model": None, "tokens_in": None, "tokens_out": None},
            {"id": 2, "conversation_id": 1, "role": "coach", "content": "Then the hard chapter goes first. Shall we try 2 lectures before ten? 🌅",
             "created_at": t0 + 60_000, "status": "complete", "model": "deepseek-flash, thinking low", "tokens_in": 4210, "tokens_out": 312},
            {"id": 3, "conversation_id": 2, "role": "user", "content": "Off the record: my brother and I argued.",
             "created_at": t0 + 86_400_000, "status": "complete", "model": None, "tokens_in": None, "tokens_out": None},
            {"id": 4, "conversation_id": 2, "role": "coach", "content": "That sounds heavy. What was it ab",
             "created_at": t0 + 86_460_000, "status": "interrupted", "model": "deepseek-flash, thinking low", "tokens_in": None, "tokens_out": None},
        ],
        "card": [{"id": 1, "message_id": 2, "type": "promise", "payload_json": "{\"text\":\"Two lectures before ten\",\"when\":\"tomorrow 08:00\"}",
                  "status": "saved", "saved_ref": "promise:1", "created_at": t0 + 60_000, "decided_at": t0 + 120_000}],
        "goal": [{"id": 1, "title": "Clear the chemistry lectures", "area": "studies", "horizon": "custom", "deadline": "2026-11-20",
                  "status": "active", "focus_id": 1, "created_at": t0}],
        "goal_revision": [{"id": 1, "goal_id": 1, "text": "Clear the chemistry lectures", "reason": "", "created_at": t0}],
        "plan": [{"id": 1, "goal_id": 1, "version": 1, "rationale": "Mornings work; two a day leaves a week spare.", "review_day": "2026-10-17",
                  "status": "active", "source_conversation_id": 1, "created_at": t0}],
        "backlog": [{"id": 1, "goal_id": 1, "name": "Chemistry lectures", "total": 50.0, "unit": "lectures", "start_done": 4.5,
                     "deadline": "2026-11-20", "status": "active", "created_at": t0, "start_day": day(1)}],
        "target": [{"id": 1, "goal_id": 1, "plan_id": 1, "backlog_id": 1, "name": "Lectures", "unit": "lectures", "minimum": 1.0, "target": 2.0,
                    "schedule": "[0,1,2,3,4]", "start_day": day(1), "end_day": None, "status": "active"}],
        "rest_day": [{"day_key": day(7), "note": "Cousin's wedding", "created_at": t0}],
        "checkin": [
            {"id": i, "target_id": 1, "day_key": day(i), "level": lvl, "amount": amt, "reason": reason, "note": None, "entered_at": t0 + i * 86_400_000,
             "entered_minute": 21 * 60 + 40 if i % 2 else None, "entered_day": day(i) if i % 2 else None}
            for i, (lvl, amt, reason) in enumerate(
                [("target", 2.0, None), ("minimum", 1.0, "slept late"), ("not", 0.0, "family visit"), ("beyond", 3.0, None),
                 ("target", 2.0, None), ("rest", 0.0, None), ("target", 2.5, None)], start=1)
        ],
        "promise": [
            {"id": 1, "text": "Two lectures before ten", "why": "Mornings work.", "due_at": t0 + 86_400_000 + 8 * 3_600_000, "due_text": "tomorrow 08:00",
             "remind": 1, "status": "kept", "reason": None, "source_conversation_id": 1, "created_at": t0 + 120_000, "resolved_at": t0 + 2 * 86_400_000},
            {"id": 2, "text": "Call the tutor", "why": "", "due_at": CREATED_AT + 30 * 86_400_000, "due_text": "9 Nov",
             "remind": 1, "status": "open", "reason": None, "source_conversation_id": None, "created_at": t0, "resolved_at": None},
        ],
        "note": [
            {"id": 1, "kind": "what_helps", "text": "Mornings before ten are when studying sticks", "confidence": "likely", "status": "active",
             "times_seen": 3, "first_seen": day(1), "last_seen": day(9), "edited_by_user": 0, "created_at": t0},
            {"id": 2, "kind": "pattern", "text": "A note he deleted", "confidence": "guess", "status": "deleted_by_user",
             "times_seen": 1, "first_seen": day(2), "last_seen": day(2), "edited_by_user": 0, "created_at": t0},
        ],
        "note_evidence": [{"id": 1, "note_id": 1, "kind": "quote", "text": "I only study well in the morning", "day": day(1), "conversation_id": 1}],
        "profile_entry": [{"key": "studies", "value": "Second-year chemistry", "confirmed": 1, "updated_at": t0, "since_day": day(3)}],
        "profile_past": [{"id": 1, "key": "studies", "value": "First-year chemistry", "since_day": None, "until_day": day(3), "created_at": t0}],
        "person": [{"id": 1, "name": "Sam", "relation": "brother", "notes": "Close; they argue about chores.", "updated_at": t0}],
        "event": [{"id": 1, "conversation_id": 1, "when_text": "after lunch", "situation": "desk", "feeling_before": "heavy", "action": "scrolled",
                   "payoff": "relief", "outcome": "nothing studied", "created_at": t0}],
        "review": [{"id": 1, "kind": "week", "period_start": day(1), "period_end": day(7), "facts_json": "{\"answered\":6,\"pace\":1.9}",
                    "narrative": "A steady week.", "revisit_json": "[]", "replan_flag": 0, "read_at": None, "key": "review_week:" + day(1), "created_at": t0 + 7 * 86_400_000}],
        "chapter": [{"id": 1, "period_start": day(1), "period_end": day(30), "title": "The plan that held", "body": "September, in his words.", "created_at": t0 + 30 * 86_400_000}],
        "letter": [{"id": 1, "period_start": day(1), "period_end": day(7), "title": "Your first week", "body": "Dear Alex,\n\nSix days answered.", "ready_at": t0 + 7 * 86_400_000,
                    "read_at": None, "deleted_at": None, "key": "letter:" + day(7)}],
        "quote": [{"id": 1, "conversation_id": 1, "text": "I only study well in the morning", "said_day": day(1)}],
        "story": [{"id": 1, "period_start": day(1), "period_end": day(30), "short": "From late nights to early mornings.", "then_quote_id": 1, "now_quote_id": None,
                   "overcame_json": "[\"late nights\"]", "created_at": t0 + 30 * 86_400_000, "key": "story:2026-09"}],
        "brief": [{"id": 1, "day_key": day(38), "body": "Today asks for 2 lectures.", "created_at": t0 + 38 * 86_400_000, "key": "brief:" + day(38)}],
        "reflection_item": [{"id": 1, "conversation_id": 1, "kind": "idea", "text": "if-then plan", "his_words": "before 10, with tea", "day": day(1), "created_at": t0}],
        "settings": [
            {"key": "user_name", "value": "Alex"},
            {"key": "day_boundary_hour", "value": "4"},
            # Belongs to the old phone: a restore keeps this phone's own folder instead.
            {"key": "backup_folder", "value": "content://old.phone/tree/primary%3ABackups"},
        ],
        "prompt_override": [],
        "error_log": [{"id": 1, "at": t0, "area": "chat", "code": "card_dropped", "detail": ""}],
        "usage_month": [{"month": "2026-09", "calls": 41, "tokens_in": 180_000, "tokens_out": 9_000, "tokens_cached": 150_000}],
        "job": [{"id": 1, "key": "reflection:1:2", "type": "reflection", "status": "succeeded", "attempts": 1, "last_error": None,
                 "created_at": t0, "started_at": t0 + 1000, "finished_at": t0 + 9000}],
    }
    # Long, varied conversations, so the newest sample needs several sealed chunks.
    rnd = random.Random(20261014)
    words = ["morning", "lecture", "tea", "tired", "brother", "exam", "walk", "plan", "late", "phone", "quiet", "library",
             "chapter", "kinetics", "sleep", "rain", "bus", "mother", "focus", "notes", "panic", "calm", "today", "again"]
    for i in range(extra_messages):
        n = 5 + i
        # Made-up words too: gzip packs plain repeated words so well that the file would fit one chunk.
        made_up = lambda: "".join(rnd.choice("abcdefghijklmnopqrstuvwxyz") for _ in range(rnd.randint(3, 8)))
        text = " ".join(rnd.choice(words) if rnd.random() < 0.4 else made_up() for _ in range(rnd.randint(60, 140)))
        rows["message"].append({"id": n, "conversation_id": 1, "role": "user" if i % 2 == 0 else "coach", "content": text,
                                "created_at": t0 + 3_600_000 + i * 1000, "status": "complete",
                                "model": None if i % 2 == 0 else "deepseek-flash, thinking low", "tokens_in": None, "tokens_out": None})
    return rows


def database_json(schema, extra_messages):
    """{"format":"clarity-db","schema":N,"tables":{...}} as BackupRepository.writeDump writes it: tables by name."""
    cols = columns(schema)
    rows = life(extra_messages)
    tables = {}
    for name in sorted(cols):
        if name in DERIVED:
            continue
        keep = cols[name]
        tables[name] = [{c: r.get(c) for c in keep} for r in rows.get(name, [])]
        for r in rows.get(name, []):
            missing = set(r) - set(keep)
            # A column the old version did not have is simply not in its backup (backlog.start_day before v3).
            # (backlog.start_day before v3; profile_entry.since_day and the check-in times before v6)
            assert not missing or name in ("backlog", "profile_entry", "checkin"), (name, missing)
    unknown = set(rows) - set(cols)
    # Tables that did not exist yet are left out (reflection_item before v2, story before v4, brief before v5,
    # profile_past before v6).
    assert unknown <= {"reflection_item", "story", "brief", "profile_past"}, unknown
    return json.dumps({"format": "clarity-db", "schema": schema, "tables": tables}, ensure_ascii=False, separators=(",", ":"))


def sample(name, fmt, schema, extra):
    text = database_json(schema, extra)
    seed = hashlib.sha256(name.encode()).digest()
    salt, iv = seed[:16], seed[16:28]
    seal = bf.seal_v1 if fmt == 1 else bf.seal_v2
    data = seal(text, PASSPHRASE, schema, CREATED_AT, ITERATIONS, salt, iv)
    tables = json.loads(text)["tables"]
    return data, {
        "file": name,
        "format": fmt,
        "schema": schema,
        "created_at": CREATED_AT,
        "iterations": ITERATIONS,
        "json_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
        "chunks": None if fmt == 1 else -(-len(bf.gzip_bytes(text.encode("utf-8"))) // bf.CHUNK),
        "rows": {t: len(v) for t, v in tables.items() if v},
    }


def main():
    os.makedirs(OUT, exist_ok=True)
    samples = []
    for name, fmt, schema, extra in SAMPLES:
        data, info = sample(name, fmt, schema, extra)
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(data)
        samples.append(info)
    vectors = {
        "_comment": "Sample backups in contracts/backups/, one per format and database version the app has written, made by "
                    "tools/make_backup_fixtures.py. tools/test_backup_format.py (Python), BackupFormatTest (JVM) and "
                    "RestoreTest (emulator) all open these same files. 'damaged' cases change one sample and expect the "
                    "plain reason the app shows (flip_byte at: an offset after the header line). 'probes' are exact values a restore must bring back.",
        "passphrase": PASSPHRASE,
        "min_iterations": bf.MIN_ITERATIONS,
        "samples": samples,
        "damaged": [
            {"file": "format2-schema5.cbk", "change": "wrong_passphrase", "why": "wrong passphrase, or the file was changed"},
            {"file": "format1-schema1.cbk", "change": "wrong_passphrase", "why": "wrong passphrase, or the file was changed"},
            {"file": "format2-schema5.cbk", "change": "flip_byte", "at": 70000, "why": "wrong passphrase, or the file was changed"},
            {"file": "format1-schema3.cbk", "change": "flip_byte", "at": 100, "why": "wrong passphrase, or the file was changed"},
            {"file": "format2-schema5.cbk", "change": "cut_end", "bytes": 40, "why": "the file is cut short"},
            {"file": "format2-schema5.cbk", "change": "drop_last_record", "why": "wrong passphrase, or the file was changed"},
            {"file": "format2-schema5.cbk", "change": "repeat_first_record", "why": "wrong passphrase, or the file was changed"},
            {"file": "format2-schema4.cbk", "change": "body_only_header", "why": "the file is cut short"},
            {"file": "format1-schema1.cbk", "change": "cut_end", "bytes": 1, "why": "wrong passphrase, or the file was changed"},
            {"file": "format2-schema3.cbk", "change": "header_replace", "from": "\"schema\":3", "to": "\"schema\":4",
             "why": "wrong passphrase, or the file was changed"},
            {"file": "format2-schema3.cbk", "change": "header_replace", "from": "\"iterations\":100000", "to": "\"iterations\":99999",
             "why": "too few iterations"},
            {"file": "format2-schema3.cbk", "change": "header_replace", "from": "\"format\":2", "to": "\"format\":3",
             "why": "format 3 is newer than this app"},
            {"file": "format2-schema3.cbk", "change": "header_replace", "from": "CLARITY-BACKUP ", "to": "CLARITY-BACKUP!",
             "why": "not a Clarity backup"},
            {"file": "format2-schema3.cbk", "change": "header_replace", "from": "\"kdf\":\"PBKDF2-HMAC-SHA256\"", "to": "\"kdf\":\"MD5\"",
             "why": "unknown key derivation"},
        ],
        "probes": [
            {"table": "message", "id": 1, "column": "content",
             "value": "I only study well in the morning — before 10, with tea ✓\nAfter lunch nothing sticks. \"Every time.\""},
            {"table": "message", "id": 2, "column": "content",
             "value": "Then the hard chapter goes first. Shall we try 2 lectures before ten? 🌅"},
            {"table": "message", "id": 4, "column": "status", "value": "interrupted"},
            {"table": "backlog", "id": 1, "column": "start_done", "value": 4.5},
            {"table": "checkin", "id": 7, "column": "amount", "value": 2.5},
            {"table": "promise", "id": 2, "column": "status", "value": "open"},
            {"table": "note", "id": 2, "column": "status", "value": "deleted_by_user"},
            {"table": "conversation", "id": 2, "column": "off_the_record", "value": 1},
        ],
        # Exact values only the newest samples hold (schema 6: facts with time).
        "probes_from_schema_6": [
            {"table": "checkin", "id": 1, "column": "entered_minute", "value": 1300},
            {"table": "profile_past", "id": 1, "column": "value", "value": "First-year chemistry"},
        ],
    }
    with open(VECTORS, "w", encoding="utf-8") as f:
        json.dump(vectors, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("wrote %d samples to contracts/backups/ and contracts/backups/backup_vectors.json" % len(samples))


if __name__ == "__main__":
    main()
