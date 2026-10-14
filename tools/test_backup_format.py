"""Runs contracts/backups/backup_vectors.json against tools/backup_format.py. Run: python3 tools/test_backup_format.py

Checks, in order: the AES and GCM code against published test vectors; every sample backup opens with the
passphrase and holds what the vectors say; every damaged copy fails with the app's own reason; and the data
the generator writes is the data in the committed files.
"""
import hashlib
import json
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import backup_format as bf  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
fails = []


def check(cond, what):
    if not cond:
        fails.append(what)


def known_answers():
    # FIPS-197, appendix C.3 (AES-256).
    key = bytes(range(32))
    block = bytes.fromhex("00112233445566778899aabbccddeeff")
    check(bf.AES256(key).encrypt_block(block).hex() == "8ea2b7ca516745bfeafc49904b496089", "AES-256 FIPS-197 C.3")
    # The GCM specification (McGrew and Viega), test cases 13, 14 and 16 (256-bit key).
    zero = bytes(32)
    check(bf.gcm_seal(zero, bytes(12), b"", b"").hex() == "530f8afbc74536b9a963b4f1c4cb738b", "GCM test case 13")
    check(bf.gcm_seal(zero, bytes(12), bytes(16), b"").hex() ==
          "cea7403d4d606b6e074ec5d3baf39d18" "d0d1c8a799996bf0265b98b5d48ab919", "GCM test case 14")
    k = bytes.fromhex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
    iv = bytes.fromhex("cafebabefacedbaddecaf888")
    p = bytes.fromhex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72"
                      "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39")
    a = bytes.fromhex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
    want = ("522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa"
            "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662"
            "76fc6ece0f4e1768cddf8853bb2d551b")
    check(bf.gcm_seal(k, iv, p, a).hex() == want, "GCM test case 16")
    check(bf.gcm_open(k, iv, bytes.fromhex(want), a) == p, "GCM test case 16 opens")
    bad = bytearray.fromhex(want)
    bad[-1] ^= 1
    check(bf.gcm_open(k, iv, bytes(bad), a) is None, "GCM refuses a changed tag")


def records(body):
    out, off = [], 0
    while off < len(body):
        n = struct.unpack(">i", body[off:off + 4])[0]
        out.append(body[off:off + 4 + n])
        off += 4 + n
    return out


def damage(data, case):
    nl = data.index(b"\n") + 1
    head, body = data[:nl], data[nl:]
    c = case["change"]
    if c == "flip_byte":
        b = bytearray(body)
        b[case["at"]] ^= 0x01
        return head + bytes(b)
    if c == "cut_end":
        return data[:-case["bytes"]]
    if c == "drop_last_record":
        return head + b"".join(records(body)[:-1])
    if c == "repeat_first_record":
        return data + records(body)[0]
    if c == "body_only_header":
        return head
    if c == "header_replace":
        assert case["from"].encode() in head, case
        return head.replace(case["from"].encode(), case["to"].encode(), 1) + body
    if c == "wrong_passphrase":
        return data
    raise ValueError(c)


def main():
    known_answers()
    v = json.load(open(os.path.join(ROOT, "contracts", "backups", "backup_vectors.json"), encoding="utf-8"))
    files = {}
    for s in v["samples"]:
        data = open(os.path.join(ROOT, "contracts", "backups", s["file"]), "rb").read()
        files[s["file"]] = data
        header, text = bf.open_backup(data, v["passphrase"], v["min_iterations"])
        name = s["file"]
        for k in ("format", "schema", "created_at", "iterations"):
            check(header[k] == s[k], "%s: header %s" % (name, k))
        check(hashlib.sha256(text.encode("utf-8")).hexdigest() == s["json_sha256"], name + ": the JSON inside")
        tables = bf.tables_of(text)
        check({t: len(r) for t, r in tables.items() if r} == s["rows"], name + ": rows per table")
        if s["format"] == 2:
            body = data[data.index(b"\n") + 1:]
            check(len(records(body)) == s["chunks"], name + ": chunk count")
        for p in v["probes"]:
            rows = [r for r in tables.get(p["table"], []) if r.get("id") == p["id"]]
            check(len(rows) == 1 and rows[0][p["column"]] == p["value"], "%s: probe %s.%s" % (name, p["table"], p["column"]))
    check(any(s["chunks"] and s["chunks"] >= 3 for s in v["samples"]), "one sample must span three chunks or more")
    check({(s["format"], s["schema"]) for s in v["samples"]} >= {(1, 1), (2, 6)}, "the oldest and newest formats")
    for case in v["damaged"]:
        data = damage(files[case["file"]], case)
        pw = "paper passphrase — kept ✗" if case["change"] == "wrong_passphrase" else v["passphrase"]
        try:
            bf.open_backup(data, pw, v["min_iterations"])
            fails.append("damaged %s opened" % json.dumps(case))
        except bf.BadBackup as e:
            check(e.why == case["why"], "damaged %s: got %r" % (json.dumps(case), e.why))
    # The committed samples hold exactly what the generator writes into them. (Not the same bytes: gzip packs
    # a little differently from one Python or zlib version to the next.)
    import make_backup_fixtures as mk
    by_name = {s["file"]: s for s in v["samples"]}
    for name, fmt, schema, extra in mk.SAMPLES:
        text = mk.database_json(schema, extra)
        check(hashlib.sha256(text.encode("utf-8")).hexdigest() == by_name[name]["json_sha256"],
              name + ": the committed file holds other data than make_backup_fixtures.py writes")
    if fails:
        print("BACKUP FORMAT FAILED:")
        for f in fails:
            print("  -", f)
        sys.exit(1)
    print("backup format: %d samples, %d damaged copies, known answers OK" % (len(v["samples"]), len(v["damaged"])))


if __name__ == "__main__":
    main()
