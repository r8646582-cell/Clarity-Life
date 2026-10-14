-- Clarity: database schema, version 7 (the design reference; Room entities must match it exactly).
-- Rules: no table is ever dropped by a migration; every change adds a migration plus a MigrationTest.
-- Times: *_at columns are UTC epoch milliseconds. Day columns (day_key, *_day, deadline, period_*) are local dates 'YYYY-MM-DD'.
-- Derived data (search_doc, embedding_row, review.facts_json) can be rebuilt from the raw rows.
-- History is never deleted by a cascade from goals or targets: those are ended or archived, not deleted.
-- Cascades exist only for "Forget this conversation" and "Erase everything", which he confirms.
PRAGMA foreign_keys = ON;

CREATE TABLE settings (                -- plain preferences only; the API keys live in SecretStore (Keystore), never here
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL
);

CREATE TABLE focus (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  why TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','paused','done')),
  summary TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE conversation (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL DEFAULT '',
  chat_type TEXT NOT NULL DEFAULT 'free' CHECK (chat_type IN ('free','untangle','decide','plan')),
  focus_id INTEGER REFERENCES focus(id) ON DELETE SET NULL,
  started_at INTEGER NOT NULL,
  ended_at INTEGER,
  summary TEXT NOT NULL DEFAULT '',
  reflected_up_to_message_id INTEGER NOT NULL DEFAULT 0,
  off_the_record INTEGER NOT NULL DEFAULT 0 CHECK (off_the_record IN (0,1)),
  opened_from TEXT CHECK (opened_from IN ('letter','review') OR opened_from IS NULL),
  opened_from_id INTEGER,
  prefix_json TEXT,                     -- the frozen Part 1 + Part 2 of the context packet (contracts/context.md)
  prefix_day TEXT,                      -- day key the prefix was built on; a later day rebuilds it
  history_start_id INTEGER NOT NULL DEFAULT 0  -- first message id in the history window; only moves forward
);

CREATE INDEX idx_conversation_started ON conversation(started_at);  -- schema 7: the drawer, newest first

CREATE TABLE message (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
  role TEXT NOT NULL CHECK (role IN ('user','coach')),
  content TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'complete' CHECK (status IN ('streaming','complete','interrupted','failed')),
  model TEXT,
  tokens_in INTEGER,
  tokens_out INTEGER
);
CREATE INDEX idx_message_conv ON message(conversation_id, id);
CREATE INDEX idx_message_status ON message(status);           -- schema 7: the start-up sweep finds 'streaming' replies

CREATE TABLE goal (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL,
  area TEXT NOT NULL DEFAULT 'general' CHECK (area IN ('health','studies','relationships','money','habits','mind','meaning','rest_joy','general')),
  horizon TEXT NOT NULL DEFAULT 'custom',
  deadline TEXT,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','done','archived')),
  focus_id INTEGER REFERENCES focus(id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE goal_revision (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  goal_id INTEGER NOT NULL REFERENCES goal(id) ON DELETE CASCADE,
  text TEXT NOT NULL,
  reason TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);

CREATE TABLE plan (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  goal_id INTEGER NOT NULL REFERENCES goal(id) ON DELETE CASCADE,
  version INTEGER NOT NULL,
  rationale TEXT NOT NULL,
  review_day TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','replaced','ended')),
  source_conversation_id INTEGER REFERENCES conversation(id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL,
  UNIQUE (goal_id, version)
);

CREATE TABLE backlog (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  goal_id INTEGER REFERENCES goal(id) ON DELETE SET NULL,
  name TEXT NOT NULL,
  total REAL NOT NULL CHECK (total > 0),
  unit TEXT NOT NULL,
  start_done REAL NOT NULL DEFAULT 0 CHECK (start_done >= 0),  -- already done before tracking began
  deadline TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','done','archived')),
  created_at INTEGER NOT NULL,
  start_day TEXT,                       -- his local day at creation (schema 3); null rows are filled once on read
  CHECK (start_done <= total)
);
CREATE INDEX idx_backlog_status ON backlog(status);
-- done = start_done + the sum of check-in amounts of the targets linked to the backlog (target.backlog_id)

CREATE TABLE target (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  goal_id INTEGER REFERENCES goal(id) ON DELETE SET NULL,
  plan_id INTEGER REFERENCES plan(id) ON DELETE SET NULL,
  backlog_id INTEGER REFERENCES backlog(id) ON DELETE SET NULL,
  name TEXT NOT NULL,
  unit TEXT NOT NULL DEFAULT '',
  minimum REAL NOT NULL DEFAULT 0 CHECK (minimum >= 0),
  target REAL NOT NULL CHECK (target > 0),
  schedule TEXT NOT NULL,               -- daily | weekdays | Mon,Wed,Fri | Sat-Thu | 3x/week
  start_day TEXT NOT NULL,
  end_day TEXT,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','paused','ended')),
  CHECK (minimum <= target)
);
CREATE INDEX idx_target_status ON target(status);

CREATE TABLE rest_day (                -- a planned day off for every target: neither done nor missed, not counted in days left
  day_key TEXT PRIMARY KEY NOT NULL,
  note TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);

CREATE TABLE checkin (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  target_id INTEGER NOT NULL REFERENCES target(id) ON DELETE RESTRICT,  -- a target with history can never be deleted
  day_key TEXT NOT NULL,
  level TEXT NOT NULL CHECK (level IN ('rest','not','minimum','target','beyond')),
  amount REAL NOT NULL DEFAULT 0 CHECK (amount >= 0),
  reason TEXT,
  note TEXT,
  entered_at INTEGER NOT NULL,
  entered_minute INTEGER,               -- schema 6: his local time when he answered, minutes after midnight (0..1439), stored at write time
  entered_day TEXT,                     -- schema 6: his day key when he answered; NULL for answers from before schema 6
  UNIQUE (target_id, day_key)           -- a missing row is "no answer"; one answer per target per day
);
CREATE INDEX idx_checkin_day ON checkin(day_key);

CREATE TABLE promise (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  text TEXT NOT NULL,
  why TEXT NOT NULL DEFAULT '',
  due_at INTEGER NOT NULL,
  due_text TEXT NOT NULL,               -- the wording as resolved, shown back to you
  remind INTEGER NOT NULL DEFAULT 0 CHECK (remind IN (0,1)),
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','kept','not_done','moved','let_go')),
  reason TEXT,
  source_conversation_id INTEGER REFERENCES conversation(id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL,
  resolved_at INTEGER
);
CREATE INDEX idx_promise_status ON promise(status, due_at);
CREATE INDEX idx_promise_due ON promise(due_at);              -- schema 7: promises due in a period, answered since a day

CREATE TABLE card (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  message_id INTEGER NOT NULL REFERENCES message(id) ON DELETE CASCADE,
  type TEXT NOT NULL CHECK (type IN ('promise','plan','target_change','backlog','focus','note','mode')),
  payload_json TEXT NOT NULL,           -- the validated, normalised card
  status TEXT NOT NULL DEFAULT 'offered' CHECK (status IN ('offered','saved','dismissed')),
  saved_ref TEXT,                       -- e.g. promise:12, the real result shown as "Saved"
  created_at INTEGER NOT NULL,
  decided_at INTEGER
);
CREATE INDEX idx_card_message ON card(message_id);

CREATE TABLE note (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL CHECK (kind IN ('pattern','what_helps','what_doesnt','strength','value','thread')),
  text TEXT NOT NULL,
  confidence TEXT NOT NULL DEFAULT 'guess' CHECK (confidence IN ('guess','likely','confirmed')),
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active','retired','deleted_by_user')),
  times_seen INTEGER NOT NULL DEFAULT 1 CHECK (times_seen >= 1),
  first_seen TEXT NOT NULL,
  last_seen TEXT NOT NULL,
  edited_by_user INTEGER NOT NULL DEFAULT 0 CHECK (edited_by_user IN (0,1)),
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_note_active ON note(status, kind);

CREATE TABLE note_evidence (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  note_id INTEGER NOT NULL REFERENCES note(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('quote','count')),
  text TEXT NOT NULL,
  day TEXT NOT NULL,
  conversation_id INTEGER REFERENCES conversation(id) ON DELETE CASCADE  -- "Forget" removes his words from that chat
);
CREATE INDEX idx_evidence_note ON note_evidence(note_id);
CREATE INDEX idx_evidence_conv ON note_evidence(conversation_id);
-- ForgetConversation (one transaction): delete the conversation (messages, cards, quotes, events and evidence
-- cascade), then delete every note left with no evidence, then remove its search_doc and embedding_row rows.

CREATE TABLE profile_entry (
  key TEXT PRIMARY KEY NOT NULL,
  value TEXT NOT NULL,
  confirmed INTEGER NOT NULL DEFAULT 0 CHECK (confirmed IN (0,1)),
  updated_at INTEGER NOT NULL,
  since_day TEXT                        -- schema 6: the day this value was first said (his day key); NULL = before schema 6, not known
);

CREATE TABLE profile_past (            -- schema 6: a profile value that was replaced by a newer one, so an old fact never poses as current
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  key TEXT NOT NULL,
  value TEXT NOT NULL,
  since_day TEXT,                       -- the day it was first said, when known
  until_day TEXT NOT NULL,              -- the day it was replaced (the day the newer value was said)
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_profile_past_key ON profile_past(key, id);
-- Written by code only when reflection replaces a value with a different one; a correction in his own words
-- (the old value was wrong, not old) and his own edit write none. Deleting the entry deletes its past too.

CREATE TABLE person (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL COLLATE NOCASE UNIQUE,
  relation TEXT NOT NULL DEFAULT '',
  notes TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL
);

CREATE TABLE event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER REFERENCES conversation(id) ON DELETE CASCADE,
  when_text TEXT NOT NULL DEFAULT '',
  situation TEXT NOT NULL DEFAULT '',
  feeling_before TEXT NOT NULL DEFAULT '',
  action TEXT NOT NULL DEFAULT '',
  payoff TEXT NOT NULL DEFAULT '',
  outcome TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL
);

CREATE TABLE review (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL CHECK (kind IN ('week','month')),
  period_start TEXT NOT NULL,
  period_end TEXT NOT NULL,
  facts_json TEXT NOT NULL,             -- derived, rebuildable
  narrative TEXT NOT NULL DEFAULT '',
  revisit_json TEXT NOT NULL DEFAULT '[]',
  replan_flag INTEGER NOT NULL DEFAULT 0 CHECK (replan_flag IN (0,1)),
  read_at INTEGER,
  key TEXT NOT NULL UNIQUE,             -- review:week:2026-10-05 (one per period)
  created_at INTEGER NOT NULL
);

CREATE TABLE chapter (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  period_start TEXT NOT NULL,
  period_end TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE (period_start, period_end)
);

CREATE TABLE letter (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  period_start TEXT NOT NULL,
  period_end TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  ready_at INTEGER NOT NULL,
  read_at INTEGER,
  deleted_at INTEGER,                   -- on delete, title and body are blanked; the row stays only to hold the key
  key TEXT NOT NULL UNIQUE              -- letter:week:2026-10-05; a deleted letter keeps its key, so it is never regenerated
);

CREATE TABLE story (                   -- Your story: the monthly story job's text (schema version 4)
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  period_start TEXT NOT NULL,           -- the month written after, first and last day
  period_end TEXT NOT NULL,
  short TEXT NOT NULL,                  -- "The short version"
  then_quote_id INTEGER,                -- quote.id; no foreign key: a forgotten conversation takes its quote
  now_quote_id INTEGER,                 -- with it, and the page then simply leaves that quote out
  overcame_json TEXT NOT NULL DEFAULT '[]',  -- [{"title","then_quote_id","did","now"}], at most 3, checked
  created_at INTEGER NOT NULL,
  key TEXT NOT NULL UNIQUE              -- story:2026-10, one per month
);

CREATE TABLE brief (                   -- the morning brief (schema version 5): a few checked lines each morning
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  day_key TEXT NOT NULL,                -- the local day it is for
  body TEXT NOT NULL,                   -- passed check_text_job kind brief (quotes his, numbers from the inputs)
  created_at INTEGER NOT NULL,
  key TEXT NOT NULL UNIQUE              -- brief:2026-10-20, one per day
);

CREATE TABLE quote (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER REFERENCES conversation(id) ON DELETE CASCADE,
  text TEXT NOT NULL,                   -- only inserted after QuoteCheck passes
  said_day TEXT NOT NULL
);
CREATE INDEX idx_quote_day ON quote(said_day, id);            -- schema 7: Your story and letters read quotes by day

CREATE TABLE reflection_item (         -- what reflection learns that is neither a note nor a quote (schema version 2)
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER REFERENCES conversation(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('idea','disagreement','contradiction')),
  text TEXT NOT NULL,                   -- the idea tag, the coach's claim, or his first quote
  his_words TEXT NOT NULL DEFAULT '',   -- his position, or his second quote (both passed QuoteCheck); '' for an idea
  day TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX idx_reflection_item_kind ON reflection_item(kind, id);   -- IDEAS_RECENT: the last 20 idea tags
CREATE INDEX index_reflection_item_conversation_id ON reflection_item(conversation_id);
-- search (derived; rebuildable from message, note, review, letter, chapter, conversation.summary)
CREATE VIRTUAL TABLE search_doc USING fts4(kind, ref_id, title, body, tokenize=unicode61);

CREATE TABLE embedding_row (
  doc_kind TEXT NOT NULL,
  doc_id INTEGER NOT NULL,
  model TEXT NOT NULL,
  dim INTEGER NOT NULL,
  vector BLOB NOT NULL,
  PRIMARY KEY (doc_kind, doc_id, model)
);

CREATE TABLE prompt_override (
  name TEXT PRIMARY KEY NOT NULL,
  text TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE error_log (               -- never any message text, key or personal content
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  at INTEGER NOT NULL,
  area TEXT NOT NULL,                  -- chat, reflection, review, letter, card, quote, number, backup ...
  code TEXT NOT NULL,                  -- http_429, json_rejected, card_dropped, quote_rejected ...
  detail TEXT NOT NULL DEFAULT ''
);
CREATE INDEX idx_error_at ON error_log(at);                   -- schema 7: Health and the yearly clean-up

CREATE TABLE usage_month (
  month TEXT PRIMARY KEY NOT NULL,     -- 2026-10
  calls INTEGER NOT NULL DEFAULT 0,
  tokens_in INTEGER NOT NULL DEFAULT 0,
  tokens_out INTEGER NOT NULL DEFAULT 0,
  tokens_cached INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE job (                     -- idempotence: one row per unit of background work
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  key TEXT NOT NULL UNIQUE,            -- type + period or conversation + hash of inputs
  type TEXT NOT NULL CHECK (type IN ('reflection','review_week','review_month','letter','chapter','story','brief','gardening','backup','index','maintenance')),
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','running','succeeded','failed','skipped')),
  attempts INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  created_at INTEGER NOT NULL,
  started_at INTEGER,
  finished_at INTEGER
);
CREATE INDEX idx_job_status ON job(status);                  -- schema 7: waiting and running jobs
