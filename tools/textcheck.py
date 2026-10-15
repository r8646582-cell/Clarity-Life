"""Reference implementation of QuoteCheck and NumberCheck: the executable spec for the Kotlin versions.

QuoteCheck   a quote shown as his words must appear inside one of his own messages (ignoring case, spacing,
             Unicode width forms, curly versus straight quote marks and trailing punctuation), starting and
             ending on word boundaries ("tired" is not found inside "retired"). Ported from the old QuoteCheck.kt.
NumberCheck  every number in AI-written text (review, letter, chapter) must also appear in the inputs the model
             was given, whether it is written in digits ("37") or in words ("thirty-seven"). The model may not
             calculate or invent figures.
Both fail closed: the caller drops or regenerates the text, it never "fixes" it by guessing. Anything the
checker cannot read with certainty (an unbalanced quotation mark, a quotation too long to be a quote) fails.

Kotlin notes: lower-case with Locale.ROOT; normalize with java.text.Normalizer NFKC; isalnum = Character.isLetterOrDigit.
"""
import re
import unicodedata

MIN_CHARS = 6                     # for quotes STORED as his words (too short is too generic)
MAX_QUOTE = 400                   # a "quotation" longer than this is not a quote, it is a paraphrase in marks
_TRIM = " \t\n\"'.,;:!?…"


def norm(s):
    s = unicodedata.normalize("NFKC", s).lower()
    for a, b in (("’", "'"), ("‘", "'"), ("“", '"'), ("”", '"')):
        s = s.replace(a, b)
    return re.sub(r"\s+", " ", s).strip()


def _found_on_word_boundaries(q, hay):
    start = hay.find(q)
    while start != -1:
        end = start + len(q)
        left_ok = not q[0].isalnum() or start == 0 or not hay[start - 1].isalnum()
        right_ok = not q[-1].isalnum() or end == len(hay) or not hay[end].isalnum()
        if left_ok and right_ok:
            return start, end
        start = hay.find(q, start + 1)
    return None


def _word_dist(w1, w2):
    if w1 == w2:
        return 0
    if abs(len(w1) - len(w2)) > 1:
        return 2
    if w1.startswith(w2) or w2.startswith(w1) or w1.endswith(w2) or w2.endswith(w1):
        return 2
    if len(w1) == len(w2):
        return sum(1 for a, b in zip(w1, w2) if a != b)
    shorter, longer = (w1, w2) if len(w1) < len(w2) else (w2, w1)
    for i in range(len(longer)):
        if longer[:i] + longer[i + 1:] == shorter:
            return 1
    return 2


def _find_in_message(q_norm, m):
    # 1. Exact substring on word boundaries
    bounds = _found_on_word_boundaries(q_norm, norm(m))
    if bounds is not None:
        return m[bounds[0]:bounds[1]]

    # 2. Tolerant word-by-word match: allows punctuation variations and 1-letter typos
    q_words = re.findall(r"[\w']+", q_norm)
    if not q_words:
        return None
    hay_matches = list(re.finditer(r"[\w']+", m))
    if len(hay_matches) < len(q_words):
        return None

    k = len(q_words)
    for i in range(len(hay_matches) - k + 1):
        window = hay_matches[i:i + k]
        typos = 0
        match = True
        for qw, hw in zip(q_words, window):
            d = _word_dist(qw, hw.group().lower())
            if d > 1:
                match = False
                break
            typos += d
            if typos > 1:
                match = False
                break
        if match:
            start_idx = window[0].start()
            end_idx = window[-1].end()
            return m[start_idx:end_idx]
    return None


def find_his_quote(quote, user_messages, min_chars=MIN_CHARS):
    """Finds his quote in user messages, tolerating back-to-back joined messages, minor punctuation,
    case variations, and 1-letter typos. Returns his exact original words from user_messages, or None."""
    q_norm = norm(quote).strip(_TRIM)
    if len(q_norm) < min_chars:
        return None

    for m in user_messages:
        res = _find_in_message(q_norm, m)
        if res:
            return res

    for i in range(len(user_messages) - 1):
        for j in range(i + 1, min(i + 3, len(user_messages))):
            joined = " ".join(user_messages[i:j + 1])
            res = _find_in_message(q_norm, joined)
            if res:
                return res
    return None


def quote_is_his(quote, user_messages, min_chars=MIN_CHARS):
    return find_his_quote(quote, user_messages, min_chars) is not None


def verified_quotes(quotes, user_messages):
    out = []
    for q in quotes:
        matched = find_his_quote(q, user_messages)
        if matched is not None:
            out.append(matched)
    return out


def _quoted_phrases(text):
    """Every phrase the text presents as a quotation, or None when the quotation marks cannot be read with certainty.
    Straight double quotes pair in order. Curly double quotes must open with “ and close with ”.
    A left single curly quote ‘ always opens a quotation (an apostrophe is ’); it closes at the next ’ that is
    not followed by a letter or digit. Straight single quotes are apostrophes and are never quotations."""
    phrases = []
    if text.count('"') % 2:
        return None
    phrases += re.findall(r'"([^"]*)"', text)
    depth, buf = 0, []
    for ch in text:
        if ch == "“":
            if depth:
                return None
            depth, buf = 1, []
        elif ch == "”":
            if not depth:
                return None
            phrases.append("".join(buf))
            depth = 0
        elif depth:
            buf.append(ch)
    if depth:
        return None
    i = text.find("‘")
    while i != -1:
        j = i + 1
        while True:
            j = text.find("’", j)
            if j == -1:
                return None
            if j + 1 >= len(text) or not text[j + 1].isalnum():
                break
            j += 1
        phrases.append(text[i + 1:j])
        i = text.find("‘", j + 1)
    return phrases


def verify_quotes_in_text(text, user_messages):
    """Check every quoted phrase in AI text. Fragments separated by an ellipsis are each checked."""
    phrases = _quoted_phrases(text)
    if phrases is None:
        return {"ok": False, "bad": ["unbalanced quotation mark"]}
    bad = []
    for phrase in phrases:
        if len(phrase) > MAX_QUOTE:
            bad.append(phrase[:40] + "...")
            continue
        for frag in re.split(r"\.\.\.|…", phrase):
            frag = frag.strip(_TRIM)
            if not frag:
                continue
            if not quote_is_his(frag, user_messages, min_chars=1):
                bad.append(phrase)
                break
    return {"ok": not bad, "bad": bad}


# ---------- NumberCheck ----------

_NUM = re.compile(r"\d+(?:[.,]\d+)*")
_SMALL = {"zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8,
          "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15,
          "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19}
_TENS = {"twenty": 20, "thirty": 30, "forty": 40, "fifty": 50, "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90}
_ORD = {"third": 3, "fourth": 4, "fifth": 5, "sixth": 6, "seventh": 7, "eighth": 8, "ninth": 9, "tenth": 10,
        "eleventh": 11, "twelfth": 12}
_MULT = {"twice": 2, "thrice": 3}
# "one", "once", "first", "second", "half" and "a couple" are too common as ordinary words to treat as figures,
# unless they are part of a larger number ("twenty-one").


def _canon(tok):
    tok = tok.replace(",", "")
    whole, _, frac = tok.partition(".")
    whole = whole.lstrip("0") or "0"
    frac = frac.rstrip("0")
    return whole + ("." + frac if frac else "")


def _word_numbers(text):
    toks = re.findall(r"[a-z]+", norm(text))
    out, i = set(), 0
    while i < len(toks):
        w = toks[i]
        if w in _MULT:
            out.add(str(_MULT[w]))
            i += 1
            continue
        if w in _ORD:
            out.add(str(_ORD[w]))
            i += 1
            continue
        if w not in _SMALL and w not in _TENS and w not in ("hundred", "thousand", "dozen"):
            i += 1
            continue
        total, cur, words, last = 0, 0, [], None
        while i < len(toks):
            w = toks[i]
            if w in _SMALL:
                if last in ("small", "tens") and not (last == "tens" and _SMALL[w] < 10):
                    break
                cur += _SMALL[w]
                last = "small"
            elif w in _TENS:
                if last in ("small", "tens"):
                    break
                cur += _TENS[w]
                last = "tens"
            elif w == "hundred":
                cur = max(cur, 1) * 100
                last = "mult"
            elif w == "dozen":
                cur = max(cur, 1) * 12
                last = "mult"
            elif w == "thousand":
                total += max(cur, 1) * 1000
                cur, last = 0, "mult"
            elif w == "and" and last == "mult" and i + 1 < len(toks) and (toks[i + 1] in _SMALL or toks[i + 1] in _TENS):
                i += 1
                continue
            else:
                break
            words.append(w)
            i += 1
        if words == ["one"]:
            continue
        out.add(str(total + cur))
    return out


def numbers_in(text):
    return {_canon(t) for t in _NUM.findall(text)} | _word_numbers(text)


def number_check(text, inputs):
    """inputs: list of strings the model was given. Numbers in words count, in the text and in the inputs."""
    allowed = set()
    for i in inputs:
        allowed |= numbers_in(i)
    bad = sorted(numbers_in(text) - allowed)
    return {"ok": not bad, "bad": bad}
