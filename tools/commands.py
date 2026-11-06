"""Reference implementation of the chat commands (docs/CLARITY-V2-BUILD-SPEC.md section 8): the executable spec for
ChatCommands.kt. contracts/command_vectors.json pins the behaviour.

Commands are detected by code before a message is sent; the AI never makes tool calls. The only command is a
deletion, and even that only opens a confirm sheet: nothing is deleted without a tap.
"""
import re

FORGET_PHRASES = {"forget that", "forget this", "forget that note", "forget this note"}


def match_command(text):
    """"forget" when the whole message is one of the forget phrases (case and surrounding spaces and a trailing
    full stop, comma or exclamation mark do not matter), otherwise None. "What do you remember about X" and
    "forget that I said X" are ordinary chat."""
    t = re.sub(r"\s+", " ", (text or "").strip().lower())
    t = re.sub(r"[\s.,!]+$", "", t)
    return "forget" if t in FORGET_PHRASES else None
