"""Reference reader and writer for Clarity's encrypted backup file (spec section 9; docs/BACKUP-FORMAT.md).

Two jobs:

1. **The contract.** `contracts/backups/backup_vectors.json` names sample backups in `contracts/backups/`, made by this
   file, one for every backup format and database version the app has ever written. `tools/test_backup_format.py`
   opens them here, and the app's `BackupFormatTest` (JVM) and `RestoreTest` (emulator) open and restore the very
   same files, so a change to the app that could no longer read an old backup fails CI.

2. **Readable forever.** With Python 3 and the passphrase, a backup opens on any computer, without the app and
   without a phone:

       python3 tools/backup_format.py open clarity-backup-2026-10-14-0300.cbk clarity.json

   It asks for the passphrase and writes the whole database as JSON (one list of rows per table). It needs the
   Python standard library only, so it is slow on a very large backup (a few minutes for years of chats), but
   it will still work when the app no longer does.

Python 3.9+ standard library only. AES is written out below because the standard library has none; it is checked
against the published AES and GCM test vectors (FIPS-197 and the GCM specification) in test_backup_format.py.
"""
import base64
import getpass
import gzip
import hashlib
import io
import json
import struct
import sys
import zlib

MAGIC = b"CLARITY-BACKUP "
FORMAT = 1
FORMAT_STREAM = 2
CHUNK = 64 * 1024
MAX_HEADER = 4096
ITERATIONS = 600_000
MIN_ITERATIONS = 100_000
MAX_ITERATIONS = 10_000_000
KDF = "PBKDF2-HMAC-SHA256"
TAG = 16
IV_BYTES = 12


class BadBackup(Exception):
    """The same plain reasons the app shows (BackupFormat.BadBackup.why)."""

    def __init__(self, why):
        super().__init__(why)
        self.why = why


# ---------------------------------------------------------------- AES-256 (FIPS-197), encryption only

def _xtime(a):
    a <<= 1
    return (a ^ 0x11B) if a & 0x100 else a


def _make_sbox():
    sbox = [0] * 256
    p = q = 1
    while True:
        # p walks the multiplicative group by 3, q by its inverse; the affine map gives the S-box.
        p = p ^ _xtime(p)
        q ^= q << 1
        q ^= q << 2
        q ^= q << 4
        q &= 0xFF
        if q & 0x80:
            q ^= 0x09
        x = q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4))
        sbox[p] = (x ^ 0x63) & 0xFF
        if p == 1:
            break
    sbox[0] = 0x63
    return sbox


_SBOX = _make_sbox()
# T-tables: SubBytes, ShiftRows and MixColumns of one byte as one 32-bit word.
_T0 = []
for _s in _SBOX:
    _s2 = _xtime(_s) & 0xFF
    _s3 = _s2 ^ _s
    _T0.append((_s2 << 24) | (_s << 16) | (_s << 8) | _s3)
_T1 = [((t >> 8) | (t << 24)) & 0xFFFFFFFF for t in _T0]
_T2 = [((t >> 16) | (t << 16)) & 0xFFFFFFFF for t in _T0]
_T3 = [((t >> 24) | (t << 8)) & 0xFFFFFFFF for t in _T0]


class AES256:
    def __init__(self, key):
        if len(key) != 32:
            raise ValueError("AES-256 needs a 32-byte key")
        w = list(struct.unpack(">8I", key))
        rcon = 1
        for i in range(8, 60):
            t = w[i - 1]
            if i % 8 == 0:
                t = ((t << 8) | (t >> 24)) & 0xFFFFFFFF
                t = (_SBOX[t >> 24] << 24) | (_SBOX[(t >> 16) & 0xFF] << 16) | (_SBOX[(t >> 8) & 0xFF] << 8) | _SBOX[t & 0xFF]
                t ^= rcon << 24
                rcon = _xtime(rcon) & 0xFF
            elif i % 8 == 4:
                t = (_SBOX[t >> 24] << 24) | (_SBOX[(t >> 16) & 0xFF] << 16) | (_SBOX[(t >> 8) & 0xFF] << 8) | _SBOX[t & 0xFF]
            w.append(w[i - 8] ^ t)
        self._w = w

    def encrypt_block(self, block):
        w = self._w
        s0, s1, s2, s3 = struct.unpack(">4I", block)
        s0 ^= w[0]
        s1 ^= w[1]
        s2 ^= w[2]
        s3 ^= w[3]
        T0, T1, T2, T3 = _T0, _T1, _T2, _T3
        k = 4
        for _ in range(13):
            t0 = T0[s0 >> 24] ^ T1[(s1 >> 16) & 0xFF] ^ T2[(s2 >> 8) & 0xFF] ^ T3[s3 & 0xFF] ^ w[k]
            t1 = T0[s1 >> 24] ^ T1[(s2 >> 16) & 0xFF] ^ T2[(s3 >> 8) & 0xFF] ^ T3[s0 & 0xFF] ^ w[k + 1]
            t2 = T0[s2 >> 24] ^ T1[(s3 >> 16) & 0xFF] ^ T2[(s0 >> 8) & 0xFF] ^ T3[s1 & 0xFF] ^ w[k + 2]
            t3 = T0[s3 >> 24] ^ T1[(s0 >> 16) & 0xFF] ^ T2[(s1 >> 8) & 0xFF] ^ T3[s2 & 0xFF] ^ w[k + 3]
            s0, s1, s2, s3 = t0, t1, t2, t3
            k += 4
        S = _SBOX

        def last(a, b, c, d, rk):
            return ((S[a >> 24] << 24) | (S[(b >> 16) & 0xFF] << 16) | (S[(c >> 8) & 0xFF] << 8) | S[d & 0xFF]) ^ rk

        return struct.pack(
            ">4I",
            last(s0, s1, s2, s3, w[56]),
            last(s1, s2, s3, s0, w[57]),
            last(s2, s3, s0, s1, w[58]),
            last(s3, s0, s1, s2, w[59]),
        )


# ---------------------------------------------------------------- GCM (NIST SP 800-38D), 96-bit nonce, 128-bit tag

_R = 0xE1 << 120


class _GHash:
    """Multiplication by H in GF(2^128), with a table per 4-bit position of the input (Shoup's method)."""

    def __init__(self, h):
        h = int.from_bytes(h, "big")
        # mul[i][n] = (n placed at nibble i of the block) times H.
        powers = [h]
        for _ in range(127):
            v = powers[-1]
            powers.append((v >> 1) ^ _R if v & 1 else v >> 1)
        self._tables = []
        for i in range(32):
            row = [0] * 16
            for n in range(1, 16):
                acc = 0
                for b in range(4):
                    if n & (8 >> b):
                        acc ^= powers[4 * i + b]
                row[n] = acc
            self._tables.append(row)

    def mul(self, x):
        acc = 0
        tables = self._tables
        for i in range(32):
            acc ^= tables[i][(x >> (124 - 4 * i)) & 0xF]
        return acc

    def digest(self, aad, data):
        y = 0
        for part in (aad, data):
            for off in range(0, len(part), 16):
                block = part[off:off + 16]
                y = self.mul(y ^ int.from_bytes(block.ljust(16, b"\0"), "big"))
        return self.mul(y ^ ((len(aad) * 8) << 64 | (len(data) * 8)))


def _ctr(aes, j0, data):
    out = bytearray(len(data))
    counter = int.from_bytes(j0[12:], "big")
    prefix = j0[:12]
    for off in range(0, len(data), 16):
        counter = (counter + 1) & 0xFFFFFFFF
        ks = aes.encrypt_block(prefix + counter.to_bytes(4, "big"))
        block = data[off:off + 16]
        out[off:off + len(block)] = bytes(a ^ b for a, b in zip(block, ks))
    return bytes(out)


def gcm_seal(key, iv, plaintext, aad):
    aes = AES256(key) if not isinstance(key, AES256) else key
    gh = _GHash(aes.encrypt_block(b"\0" * 16))
    j0 = iv + b"\0\0\0\1"
    ct = _ctr(aes, j0, plaintext)
    s = gh.digest(aad, ct)
    tag = (s ^ int.from_bytes(aes.encrypt_block(j0), "big")).to_bytes(16, "big")
    return ct + tag


def gcm_open(key, iv, sealed, aad):
    """Returns the plaintext, or None when the tag does not check out."""
    if len(sealed) < TAG:
        return None
    aes = AES256(key) if not isinstance(key, AES256) else key
    gh = _GHash(aes.encrypt_block(b"\0" * 16))
    j0 = iv + b"\0\0\0\1"
    ct, tag = sealed[:-TAG], sealed[-TAG:]
    s = gh.digest(aad, ct)
    want = (s ^ int.from_bytes(aes.encrypt_block(j0), "big")).to_bytes(16, "big")
    diff = 0
    for a, b in zip(want, tag):
        diff |= a ^ b
    if diff:
        return None
    return _ctr(aes, j0, ct)


# ---------------------------------------------------------------- the backup file

def derive_key(passphrase, salt, iterations):
    # The app hands Java's PBKDF2 a char array, which it turns into UTF-8 bytes.
    return hashlib.pbkdf2_hmac("sha256", passphrase.encode("utf-8"), salt, iterations, 32)


def header_line(fmt, schema, created_at, iterations, salt, iv, sha):
    """Exactly the bytes BackupFormat.headerJson writes: field order and spacing are part of the format."""
    b64 = lambda b: base64.b64encode(b).decode("ascii")
    text = (
        '{"format":%d,"schema":%d,"created_at":%d,"kdf":"%s","iterations":%d,"salt":"%s","iv":"%s","sha256":"%s"}'
        % (fmt, schema, created_at, KDF, iterations, b64(salt), b64(iv), sha)
    )
    return MAGIC + text.encode("utf-8")


def gzip_bytes(raw):
    # mtime 0 so the same input always makes the same file.
    return gzip.compress(raw, mtime=0)


def seal_v1(json_text, passphrase, schema, created_at, iterations, salt, iv):
    zipped = gzip_bytes(json_text.encode("utf-8"))
    line = header_line(FORMAT, schema, created_at, iterations, salt, iv, hashlib.sha256(zipped).hexdigest())
    return line + b"\n" + gcm_seal(derive_key(passphrase, salt, iterations), iv, zipped, line)


def chunk_nonce(iv, index):
    nonce = bytearray(iv)
    for i in range(4):
        nonce[IV_BYTES - 1 - i] ^= (index >> (8 * i)) & 0xFF
    return bytes(nonce)


def chunk_aad(line, index, last):
    return line + struct.pack(">iB", index, 1 if last else 0)


def seal_v2(json_text, passphrase, schema, created_at, iterations, salt, iv):
    zipped = gzip_bytes(json_text.encode("utf-8"))
    line = header_line(FORMAT_STREAM, schema, created_at, iterations, salt, iv, "")
    aes = AES256(derive_key(passphrase, salt, iterations))
    # Full chunks, then the rest; the app never writes an empty chunk (gzip always has bytes).
    pieces = [zipped[i:i + CHUNK] for i in range(0, len(zipped), CHUNK)] or [b""]
    out = bytearray(line + b"\n")
    for index, piece in enumerate(pieces):
        last = index == len(pieces) - 1
        sealed = gcm_seal(aes, chunk_nonce(iv, index), piece, chunk_aad(line, index, last))
        out += struct.pack(">i", len(sealed)) + sealed
    return bytes(out)


def _parse_header(line):
    if not line.startswith(MAGIC):
        raise BadBackup("not a Clarity backup")
    try:
        obj = json.loads(line[len(MAGIC):].decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        raise BadBackup("the header is damaged")
    if not isinstance(obj, dict):
        raise BadBackup("the header is damaged")

    def num(k):
        v = obj.get(k)
        if isinstance(v, bool) or not isinstance(v, (int, float)):
            raise BadBackup("the header has no " + k)
        return int(v)

    def text(k):
        v = obj.get(k)
        if not isinstance(v, str):
            raise BadBackup("the header has no " + k)
        return v

    if text("kdf") != KDF:
        raise BadBackup("unknown key derivation")
    try:
        salt = base64.b64decode(text("salt"), validate=True)
        iv = base64.b64decode(text("iv"), validate=True)
    except ValueError:
        raise BadBackup("the header is damaged")
    return {
        "format": num("format"),
        "schema": num("schema"),
        "created_at": num("created_at"),
        "iterations": num("iterations"),
        "salt": salt,
        "iv": iv,
        "sha256": text("sha256"),
    }


def header_of(data):
    nl = data.find(b"\n", 0, MAX_HEADER + 1)
    if nl < 0:
        raise BadBackup("not a Clarity backup")
    return _parse_header(data[:nl]), data[:nl], data[nl + 1:]


def _gunzip(zipped):
    try:
        return gzip.decompress(zipped)
    except (OSError, EOFError, zlib.error):
        raise BadBackup("the contents are damaged")


def open_backup(data, passphrase, min_iterations=MIN_ITERATIONS):
    """Opens a backup of either format. Returns (header, json_text) or raises BadBackup with the app's reason."""
    header, line, rest = header_of(data)
    if header["format"] not in (FORMAT, FORMAT_STREAM):
        raise BadBackup("format %d is newer than this app" % header["format"])
    if header["iterations"] < min_iterations:
        raise BadBackup("too few iterations")
    if header["iterations"] > MAX_ITERATIONS:
        raise BadBackup("too many iterations")
    if header["format"] == FORMAT:
        key = derive_key(passphrase, header["salt"], header["iterations"])
        zipped = gcm_open(key, header["iv"], rest, line)
        if zipped is None:
            raise BadBackup("wrong passphrase, or the file was changed")
        if hashlib.sha256(zipped).hexdigest() != header["sha256"]:
            raise BadBackup("checksum does not match")
        return header, _gunzip(zipped).decode("utf-8")
    if len(header["iv"]) != IV_BYTES:
        raise BadBackup("the header is damaged")
    aes = AES256(derive_key(passphrase, header["salt"], header["iterations"]))
    stream = io.BytesIO(rest)

    def record():
        size = stream.read(4)
        if len(size) == 0:
            return None
        if len(size) < 4:
            raise BadBackup("the file is cut short")
        length = struct.unpack(">i", size)[0]
        if length < TAG or length > CHUNK + TAG:
            raise BadBackup("the file is damaged")
        body = stream.read(length)
        if len(body) < length:
            raise BadBackup("the file is cut short")
        return body

    # One record read ahead, as the app does, so the last chunk is known (it is sealed as the last).
    nxt = record()
    if nxt is None:
        raise BadBackup("the file is cut short")
    zipped = bytearray()
    index = 0
    while nxt is not None:
        current = nxt
        nxt = record()
        last = nxt is None
        plain = gcm_open(aes, chunk_nonce(header["iv"], index), current, chunk_aad(line, index, last))
        if plain is None:
            raise BadBackup("wrong passphrase, or the file was changed")
        zipped += plain
        index += 1
    return header, _gunzip(bytes(zipped)).decode("utf-8")


def tables_of(json_text):
    """The rows of a backup's JSON, checked the way the app's restore checks them."""
    try:
        doc = json.loads(json_text)
    except ValueError:
        raise BadBackup("that is not a Clarity backup")
    if not isinstance(doc, dict) or doc.get("format") != "clarity-db":
        raise BadBackup("that is not a Clarity backup")
    tables = doc.get("tables")
    if not isinstance(tables, dict):
        raise BadBackup("that backup has no data in it")
    return tables


def main(argv):
    if len(argv) != 4 or argv[1] != "open":
        print("usage: python3 tools/backup_format.py open BACKUP.cbk OUT.json", file=sys.stderr)
        return 2
    with open(argv[2], "rb") as f:
        data = f.read()
    passphrase = getpass.getpass("Backup passphrase: ")
    try:
        header, text = open_backup(data, passphrase)
        tables = tables_of(text)
    except BadBackup as e:
        print("Could not open the backup: %s." % e.why, file=sys.stderr)
        return 1
    with open(argv[3], "w", encoding="utf-8") as f:
        f.write(text)
    rows = sum(len(v) for v in tables.values() if isinstance(v, list))
    print("Opened a backup of database version %d: %d tables, %d rows, written to %s." % (header["schema"], len(tables), rows, argv[3]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
