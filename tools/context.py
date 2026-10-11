"""Reference implementation of the context packet: the executable spec for ContextBuilder.kt.
contracts/context.md explains the why; contracts/context_vectors.json pins the behaviour.

Layout of every chat request (cache-friendly: the longest stable prefix first):
  system   = Part 1 (persona, examples, cards, chat-type file)  +  Part 2 (frozen context blocks)
  messages = the history window (user messages prefixed with their time stamp, escaped)
             + the new user message = Part 3 (volatile blocks) + "## MESSAGE" + his text
Part 1 + Part 2 are built when the conversation starts (and again only when he switches the chat type) and
are stored in conversation.prefix_json, so they are byte-identical for every request in the conversation and
the provider's prefix cache works. Part 1 alone is identical across conversations of the same chat type.

Pure functions only. No Android, no clock, no database.
"""
import math

PART2_ORDER = ["FLAGS", "PROFILE", "PEOPLE", "NOTES", "FOCUS", "CHAPTERS", "RECENT_SESSIONS", "IDEAS_RECENT",
               "LETTER", "REVIEW"]
PART3_ORDER = ["NOW", "PLAN_STATE", "OPEN_PROMISES", "CHECKIN_FACTS", "CARD_RESULTS", "EARLIER", "RELEVANT_PAST"]
NEVER_TRIM = {"FLAGS", "FOCUS", "LETTER", "REVIEW", "NOW", "PLAN_STATE", "OPEN_PROMISES", "CHECKIN_FACTS",
              "CARD_RESULTS"}
MESSAGE_HEADER = "## MESSAGE"

PART2_BUDGET = 9000      # tokens, estimated
PART3_BUDGET = 3000
HISTORY_BUDGET = 24000
HISTORY_MIN_KEEP = 6     # the newest messages are always sent, even over budget
HISTORY_REFILL = 0.75    # when the window must move, move it until history is at most 75% of the budget,
                         # so it moves rarely and the cached prefix survives many turns


class ContextError(ValueError):
    pass


def estimate_tokens(text):
    """Deliberately pessimistic (3 characters per token) so a real tokenizer never finds us over a limit."""
    return math.ceil(len(text) / 3)


def escape(text):
    """Make text from him or from the model safe inside a block: it can never open a card or a block."""
    while "[[" in text or "]]" in text:
        text = text.replace("[[", "[ [").replace("]]", "] ]")
    out = []
    for line in text.split("\n"):
        out.append("  " + line if line.lstrip().startswith("## ") or line.lstrip() == "##" else line)
    return "\n".join(out)


def render_block(name, items):
    return "## %s\n%s" % (name, "\n".join(escape(i["text"]) for i in items))


def _render(blocks, order):
    present = {b["name"]: b for b in blocks if b["items"]}
    for name in present:
        if name not in order:
            raise ContextError("unknown block %s" % name)
    return "\n\n".join(render_block(n, present[n]["items"]) for n in order if n in present)


def trim_blocks(blocks, order, budget):
    """Drop items until the rendered blocks fit the budget. Each item has a "drop" rank: 0 means never drop,
    higher is dropped first; ties drop the item that comes first (block order, then position). Blocks in
    NEVER_TRIM are never touched. Returns (blocks, dropped_count, over_budget)."""
    blocks = [{"name": b["name"], "items": list(b["items"])} for b in blocks]
    dropped = 0
    while estimate_tokens(_render(blocks, order)) > budget:
        best = None
        for b in sorted(blocks, key=lambda x: order.index(x["name"])):
            if b["name"] in NEVER_TRIM:
                continue
            for idx, it in enumerate(b["items"]):
                if it["drop"] > 0 and (best is None or it["drop"] > best[2]):
                    best = (b, idx, it["drop"])
        if best is None:
            return blocks, dropped, True
        best[0]["items"].pop(best[1])
        dropped += 1
    return blocks, dropped, False


def build_prefix(persona, examples, cards, mode, part2_blocks):
    """System message: Part 1 then Part 2. mode is None for free talk."""
    part1 = "\n\n".join(t for t in (persona, examples, cards, mode) if t)
    kept, dropped, over = trim_blocks(part2_blocks, PART2_ORDER, PART2_BUDGET)
    part2 = _render(kept, PART2_ORDER)
    return {"system": part1 + ("\n\n" + part2 if part2 else ""), "dropped": dropped, "over_budget": over}


def next_history_start(token_counts, start, budget=HISTORY_BUDGET, min_keep=HISTORY_MIN_KEEP,
                       refill=HISTORY_REFILL):
    """Index of the first history message to send. It only moves forward, and only when the window is over
    budget; then it moves until the window is at most refill x budget (but never past the newest min_keep)."""
    n = len(token_counts)
    start = max(0, min(start, n))
    if sum(token_counts[start:]) <= budget:
        return start
    limit = max(0, n - min_keep)
    while start < limit and sum(token_counts[start:]) > refill * budget:
        start += 1
    return start


def build_user_turn(part3_blocks, text):
    kept, dropped, over = trim_blocks(part3_blocks, PART3_ORDER, PART3_BUDGET)
    blocks = _render(kept, PART3_ORDER)
    body = MESSAGE_HEADER + "\n" + escape(text)
    return {"content": (blocks + "\n\n" if blocks else "") + body, "dropped": dropped, "over_budget": over}


def history_message(role, text, stamp=None):
    """A stored message as it is sent again later. His messages carry the stamp the app wrote, for example
    [Fri 9 Oct, 21:40]; coach messages are sent as they were shown (cards already removed). Both are escaped."""
    if role == "user":
        return {"role": "user", "content": "[%s] %s" % (stamp, escape(text)) if stamp else escape(text)}
    if role == "coach":
        return {"role": "assistant", "content": escape(text)}
    raise ContextError("bad role")
