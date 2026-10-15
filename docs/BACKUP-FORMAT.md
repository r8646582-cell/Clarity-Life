# Your backup file, written down so it can be opened forever

Clarity's backups (`clarity-backup-YYYY-MM-DD-HHMM.cbk`) are encrypted with your backup passphrase. This page says exactly how, so that your life record never depends on this app still existing. If Clarity stops working one day, a backup still opens on any computer with Python 3:

```
python3 tools/backup_format.py open clarity-backup-2026-10-14-0300.cbk clarity.json
```

It asks for your passphrase and writes everything (every conversation, plan, check-in, promise and note) as one JSON file. It uses only Python's own standard library, so nothing has to be installed; on a very large backup it takes a few minutes.

**Without the passphrase nobody can open a backup, including you.** Keep it written on paper somewhere safe. The database key on the phone is lost when the phone is reset or replaced; the passphrase is what makes a backup open on a new phone.

## What is inside

The whole database as JSON, one list of rows per table:

```json
{"format": "clarity-db", "schema": 5, "tables": {"conversation": [{"id": 1, "title": "Mornings", ...}], "message": [...], ...}}
```

- `schema` is the database version (`app/schemas/.../N.json` describes every table and column of version N; `db/schema.sql` explains them).
- Numbers are JSON numbers, text is JSON text, empty values are `null`. A binary value (none today) would be `{"b64": "..."}`.
- Left out, because the app rebuilds them from the rows: the search index (`search_doc`) and the meaning vectors (`embedding_row`). Your AI key and passphrase are never in a backup.

## How the file is sealed

The file starts with one line of text, then binary data:

```
CLARITY-BACKUP {"format":2,"schema":5,"created_at":1791600000000,"kdf":"PBKDF2-HMAC-SHA256","iterations":600000,"salt":"...","iv":"...","sha256":""}\n
```

- **The key** is PBKDF2-HMAC-SHA256 of the passphrase (as UTF-8 bytes) with the header's `salt` (16 bytes, base64) and `iterations` (600,000), 32 bytes long. A reader refuses fewer than 100,000 or more than 10,000,000 iterations.
- **The data** is the JSON above, compressed with gzip, then encrypted with AES-256-GCM (16-byte tag). The whole header line (without its newline) is bound to the data as GCM associated data, so changing any of it makes the file fail to open.

**Format 2** (every backup the released app has made) splits the gzip bytes into pieces of 65,536 bytes (the last may be shorter). Each piece is sealed on its own and written as a 4-byte big-endian length followed by the sealed piece (ciphertext and tag). Piece number *i* (from 0):

- uses the header's `iv` (12 bytes) with *i*, as 4 big-endian bytes, XORed into its last four bytes;
- has as associated data the header line, then *i* as 4 big-endian bytes, then one byte: 1 for the last piece, 0 otherwise.

So a piece that is changed, missing, moved, repeated, or added at the end, or a file cut short, fails to open. `sha256` is empty in format 2; the app checks a new backup by reading it back and comparing what comes out with what went in.

**Format 1** (only test builds before 11 Oct 2026) sealed the gzip bytes as one piece with the header's `iv`, and `sha256` is the SHA-256 of the gzip bytes.

## How this is kept true

`contracts/backups/` holds sample backups of every format and database version the app has written, made by `tools/make_backup_fixtures.py`, with `backup_vectors.json` saying what each holds and how damaged copies must fail. On every push, CI opens them three ways: with `tools/backup_format.py` (`tools/test_backup_format.py`), with the app's own code on a computer (`BackupSamplesTest`), and by restoring each one into today's database on an emulator (`RestoreTest`). A change that could no longer open an old backup fails the build. `tools/mutation_check.py` plants bugs in the reader to prove the samples would catch them.

When a future version changes the backup format or the database, it adds a new sample here and keeps the old ones.
