#!/usr/bin/env python3
"""Retrieval-augmented answering over the corpus database (prototype of the on-device pipeline).

Usage:
  rag.py --db wiki.db "question"                      plan + search + answer via llama-server
  rag.py --db wiki.db --search-only "question"        print the plan and retrieved passages only
  rag.py --db wiki.db --questions q.jsonl --out a.jsonl   batch mode, resumable
  --mode bm25 reproduces the first baseline (keyword search over the raw question)
  --mode none answers closed-book (own prompt, no citation talk)
  --mode verify answers closed-book first, then appends a source check from retrieval
  --mode auto plans first and routes: little-read subject -> plan, otherwise verify

Pipeline (--mode plan, the default):
  1. The model names up to four Wikipedia articles likely to hold the answer. It knows article
     titles far better than keyword matching can guess them, and for multi-part questions it
     names one article per part, which also does the hop in multi-hop questions.
  2. Each title is resolved (exact match, then title search) and contributes its lead chunk plus
     the sections that best match the question.
  3. Remaining budget is filled from a BM25 search of the whole index, keeping only passages that
     carry enough of the question's rare terms (the v0 baseline was hurt most by passages that
     matched only generic words like "argument" or "evidence").
  4. The answer prompt treats sources as support for the model's own knowledge, not a cage.
"""
import argparse
import json
import math
import os
import re
import sqlite3
import time
import urllib.request

import zstandard as zstd

STOP = set("""a an the of in on at to for and or but is are was were be been by with from as that this these
those which what who whom whose how why when where did does do it its their his her he she they them i
me my we our you your can could should would will may might about into than then there here not no
know before after tell me explain describe summarize main roughly show reasoning""".split())

PLAN_SYSTEM = (
    "You plan lookups in an offline copy of English Wikipedia. Given a question, list the exact "
    "titles of up to 4 Wikipedia articles most likely to contain the answer, one per line, most "
    "important first. For comparisons or multi-part questions include an article for each part. "
    "For travel questions name the place itself (city, region or country) first. "
    "If the question depends on an intermediate fact you know, name the article for the final "
    "subject too. Output only the titles, nothing else."
)

ANSWER_SYSTEM = (
    "You are an offline research assistant. Answer the question directly and completely, using "
    "your own knowledge together with the numbered sources from an offline copy of Wikipedia. "
    "Cite a source like [1] where it supports a statement. Ignore sources that are off-topic. "
    "If something important is not covered by the sources, still answer it from your own "
    "knowledge; never withhold well-known facts or safety advice because a source is missing. "
    "If you are unsure of a specific name, date or number, say so instead of guessing. "
    "Address every part of the question in the first few lines, then elaborate. No preamble, "
    "no restating the question, no LaTeX, no visible deliberation. Be concise."
)


CLOSED_SYSTEM = (
    "You are an offline research assistant. Answer the question directly and completely from "
    "your own knowledge. If you are unsure of a specific name, date or number, say so instead "
    "of guessing. Address every part of the question in the first few lines, then elaborate. "
    "No preamble, no restating the question, no LaTeX, no citations or reference lists, no "
    "visible deliberation. Be concise."
)

# A question that needs arithmetic (a travel time, "how many times larger", an age at a date,
# interest): answering in the first line before working it out gave wrong results ("arrive at
# 3 PM", then 12:06 worked out below it). These prompts put a few lines of working first.
WORKED_SYSTEM = (
    "You are an offline research assistant. This question needs a calculation. First work it out in "
    "a few short lines: the facts and numbers you use (say which are approximate) and each step of the "
    "arithmetic. Then give the result on a last line starting with 'Answer:'. If you are unsure of a "
    "number, say so instead of guessing. No preamble, no LaTeX, no citations or reference lists."
)

WORKED_SOURCES_SYSTEM = (
    "You are an offline research assistant. This question needs a calculation. Use your own knowledge "
    "together with the numbered sources from an offline copy of Wikipedia, citing a source like [1] "
    "where it gives a number you use. First work it out in a few short lines: the facts and numbers "
    "(say which are approximate) and each step of the arithmetic. Then give the result on a last line "
    "starting with 'Answer:'. Ignore sources that are off-topic. No preamble, no LaTeX."
)

WORKED_WORDS = re.compile(
    r"\b(how many times|times (larger|bigger|smaller|more|heavier|longer|farther)|times as (big|large|heavy|long|far|many)|"
    r"how old (was|is|were|will)|by (roughly |about |approximately )?how (many|much)|percent|per ?cent|compound|interest rate|average speed)\b|%", re.I)
WORKED_WITH_NUMBER = re.compile(r"\b(how (long|far|much|many|fast)|what time|when (do|will|would|should) (i|we|you)|arrive)\b", re.I)
WORKED_IF = re.compile(r"(^|\b)if (the|i|we|you|a|an)\b.*\bhow (far|long|big|much|many|fast|heavy)\b", re.I)


def needs_working(question):
    """A question whose answer is a calculation (WORKED_SYSTEM)."""
    return bool(WORKED_WORDS.search(question) or WORKED_IF.search(question)
                or (re.search(r"\d", question) and WORKED_WITH_NUMBER.search(question)))

# Round three's wording plus two guards. Two stricter rewrites were tried in round four and
# graded worse: a "Corrected answer:" headline made the model invent corrections, and a
# conservative "most drafts need no correction" version threw away the useful additions.
VERIFY_SYSTEM = (
    "You check a draft answer against numbered sources from an offline copy of Wikipedia. Read "
    "all the sources before writing. Then write a short source check, at most 120 words, with "
    "two parts. Corrections: each statement in the draft that a source contradicts, with the "
    "correct fact and its citation like [2]. A statement is not wrong merely because the "
    "sources do not mention it. Additions: up to three important specifics that answer the "
    "question, that the sources provide and the draft lacks, with citations. If there is "
    "nothing to correct, write 'No corrections' and cite the sources that support the draft. "
    "Each source is about the subject named in its title; do not attach its facts to another "
    "subject. Ignore off-topic sources. Do not repeat the draft."
)

# The source check as a follow-up turn of the draft's own conversation, so the engine can keep the
# question and the draft in its KV cache and read only the sources (--check-continue). On a Pixel 8
# Pro re-reading the 450-550-token draft cost about 60 s of every answer-first question.
CHECK_FOLLOWUP = (
    "Now check your answer above against these numbered sources from an offline copy of "
    "Wikipedia. Read all the sources before writing. Then write a short source check, at most "
    "120 words, with two parts. Corrections: each statement in your answer that a source "
    "contradicts, with the correct fact and its citation like [2]. A statement is not wrong "
    "merely because the sources do not mention it. Additions: up to three important specifics "
    "that answer the question, that the sources provide and your answer lacks, with citations. "
    "If there is nothing to correct, write 'No corrections' and cite the sources that support "
    "your answer. Each source is about the subject named in its title; do not attach its facts "
    "to another subject. Ignore off-topic sources. Do not repeat your answer."
)


def check_followup_user(context, followup=None):
    return f"{followup or CHECK_FOLLOWUP}\n\nSources:\n\n{context}"


# ---- the Ethereum and cryptography pack (--pack-db, scripts/build_pack.py) ---------------------
# Its sources are Ethereum's specifications and documentation and NIST's post-quantum standards,
# so the prompts that read them say so, and say when they date from: the model's own memory stops
# before much of what they record (it called Pectra "expected in mid-2025").
PACK_LIBRARY = ("an offline library: Ethereum's specifications (EIPs, ERCs, consensus specs) and "
                "documentation, NIST's cryptography standards, and Wikipedia")


def pack_answer_system(as_of, version=1):
    if version == 2:
        return (
            "You are an offline research assistant with expert knowledge of Ethereum and cryptography. Answer "
            f"the question directly and completely, using your own knowledge together with the numbered sources "
            f"from {PACK_LIBRARY}. The sources date from {as_of}. Where a source gives a specific name, number, "
            "date or status, use it rather than your memory, and cite it like [1]. Give the full answer an expert "
            "would: how it works, the key names and numbers, and where things stand now, from your own knowledge "
            "where the sources are silent. Never describe the sources or say what they do not contain. Ignore "
            "sources that are off-topic. If you are unsure of a specific name, date or number, say so instead of "
            "guessing. Address every part of the question in the first few lines, then elaborate. No preamble, no "
            "restating the question, no LaTeX, no visible deliberation."
        )
    return (
        "You are an offline research assistant. Answer the question directly and completely, using "
        f"your own knowledge together with the numbered sources from {PACK_LIBRARY}. The sources date "
        f"from {as_of}. Cite a source like [1] where it supports a statement. Where a source gives a "
        "specific name, number, date or status, use it rather than your memory. Ignore sources that are "
        "off-topic. If something important is not covered by the sources, still answer it from your own "
        "knowledge. If you are unsure of a specific name, date or number, say so instead of guessing. "
        "Address every part of the question in the first few lines, then elaborate. No preamble, no "
        "restating the question, no LaTeX, no visible deliberation. Be concise."
    )


def pack_check_followup(as_of):
    return CHECK_FOLLOWUP.replace(
        "these numbered sources from an offline copy of Wikipedia.",
        f"these numbered sources from {PACK_LIBRARY}, dating from {as_of}.")


# headings that answer an aspect the question asks about in other words
ASPECT_HEADINGS = {
    "treat": "treatment, management, therapy", "aid": "treatment, management, first aid",
    "prevent": "prevention, prophylaxis", "avoid": "prevention",
    "symptom": "signs, symptoms, presentation", "sign": "signs, symptoms, presentation",
    "caus": "cause, causes, etiology",
    # Wikivoyage's standard section names: Understand, Get in, Get around, See, Do, Buy, Eat,
    # Drink, Sleep, Stay safe, Stay healthy, Respect, Go next, Regions, Cities, Climate
    "see": "see, sights, attractions, landmarks, tourism", "visit": "see, sights, attractions, landmarks",
    "site": "see, sights, attractions, landmarks", "priorit": "see, sights, districts",
    "district": "districts, understand", "laid": "understand, orientation, districts",
    "food": "eat, cuisine", "eat": "eat, cuisine", "try": "eat, cuisine, drink",
    "etiquett": "respect, etiquette, customs", "custom": "respect, etiquette, customs",
    "weather": "climate", "season": "climate", "safeti": "stay safe, safety",
    "safe": "stay safe, safety", "hike": "do, hiking, trekking", "region": "regions",
    "sleep": "sleep, accommodation", "transport": "get around, get in",
    "scam": "stay safe, cope", "hassl": "stay safe, cope", "danger": "stay safe",
    "fee": "understand, get in, fees, permits", "permit": "understand, get in, fees, permits",
    "visa": "get in", "guid": "understand, get in", "cold": "climate", "car": "get around",
    "around": "get around", "move": "get around, get in", "train": "get in, get around",
    "base": "sleep, districts, cities", "neighbourhood": "districts, sleep, understand",
    "dress": "respect", "behaviour": "respect", "behavior": "respect",
    "trip": "go next", "nearbi": "go next", "accommod": "sleep", "hostel": "sleep",
}

# question stems that signal a travel question (used by --travel-route)
TRAVEL_STEMS = {"visit", "see", "eat", "food", "try", "etiquett", "custom", "hike", "trek", "stay",
                "base", "sleep", "hostel", "accommod", "scam", "hassl", "trip", "travel",
                "layov", "itinerari", "dress", "behaviour", "fee"}


def clean_check(text):
    """Drop the model's visible second-guessing from a source check: everything from the first
    line that starts deliberating ("But wait", "Let me re-read", ...) is cut."""
    kept = []
    for line in text.strip().splitlines():
        if re.match(r"\s*(?:[-*]\s*)?(?:But wait|Wait[,. ]|Hmm|Let me|Let's|Actually,|On second thought)", line):
            break
        kept.append(line)
    return "\n".join(kept).strip()


def _prefix(stem):
    """Surface-form prefix for a porter stem (porter turns a final y into i: energy -> energi)."""
    return stem[:-1] if stem.endswith("i") and len(stem) > 3 else stem


def word_counts_path(path):
    """Where a database's word-count file lives (scripts/build_df.py): wiki.db -> wiki_df.db."""
    return path[:-3] + "_df.db" if path.endswith(".db") else path + "_df.db"


class Corpus:
    def __init__(self, path):
        self.db = sqlite3.connect(path)
        self.dctx = zstd.ZstdDecompressor()
        self._blocks = {}
        # vocabulary views: fts_v gives each stem's document frequency, qtok stems a query
        self.db.executescript("""
            CREATE VIRTUAL TABLE IF NOT EXISTS temp.fts_v USING fts5vocab(main, fts, row);
            CREATE VIRTUAL TABLE temp.qtok USING fts5(x, tokenize='porter unicode61 remove_diacritics 2');
            CREATE VIRTUAL TABLE temp.qtok_v USING fts5vocab(temp, qtok, row);
        """)
        # chunk count stands in for the indexed-chunk count; counting fts rows would scan 30M+ rows
        self.n_indexed = self.db.execute("select max(id) from chunks").fetchone()[0]
        self.has_redirects = self.db.execute(
            "select 1 from sqlite_master where name='redirects'").fetchone() is not None
        self.has_word_counts = self._attach_word_counts(word_counts_path(path))

    def _attach_word_counts(self, path):
        """Attach the word-count file (scripts/build_df.py) when it belongs to this database: the
        same max chunk id, and its check stems (small counts, so short posting lists) counted the
        same by the index. A common stem's document count is then read from it instead of from
        the stem's whole posting list; the counts are the index's own, so no result changes."""
        if not os.path.exists(path):
            return False
        try:
            self.db.execute("ATTACH DATABASE ? AS dfs", (path,))
        except sqlite3.Error:
            return False
        try:
            meta = dict(self.db.execute("select key, value from dfs.meta"))
            check = json.loads(meta.get("check", "[]"))
            ok = (int(meta.get("n_indexed", -1)) == self.n_indexed and len(check) > 0 and all(
                (self.db.execute("select doc from fts_v where term=?", (t,)).fetchone() or (None,))[0] == d
                for t, d in check))
        except (sqlite3.Error, ValueError):
            ok = False
        if not ok:
            self.db.execute("DETACH DATABASE dfs")
        return ok

    def article(self, aid):
        """(title, views, text); text lives at a byte range inside a compressed block."""
        title, views, block_id, off, length = self.db.execute(
            "select title, views, block_id, off, len from articles where id=?", (aid,)).fetchone()
        if block_id not in self._blocks:
            if len(self._blocks) >= 32:
                self._blocks.pop(next(iter(self._blocks)))
            zdata = self.db.execute("select zdata from blocks where id=?", (block_id,)).fetchone()[0]
            self._blocks[block_id] = self.dctx.decompress(zdata)
        return title, views, self._blocks[block_id][off:off + length].decode()

    @staticmethod
    def section_of(text, start):
        """Heading path in force at `start` (chunks store offsets only)."""
        path = []
        for m in re.finditer(r"^(#{1,6})\s+(.*)$", text[:start], re.M):
            level = len(m.group(1))
            del path[level - 1:]
            path.extend([""] * (level - 1 - len(path)))
            path.append(m.group(2).strip())
        return " > ".join(p for p in path[1:] if p)

    def stems(self, text):
        """[(stem, idf)] for the content words of `text`, stemmed by FTS5 itself: the text is
        run through a scratch table with the index's tokenizer and read back from its vocabulary."""
        words = [t for t in re.findall(r"[^\W_]+", text.lower()) if t not in STOP and len(t) > 1]
        if not words:
            return []
        self.db.execute("delete from temp.qtok")
        self.db.execute("insert into temp.qtok(x) values(?)", (" ".join(words),))
        out = []
        for (s,) in self.db.execute("select term from temp.qtok_v").fetchall():
            # a common stem's count from the word-count file; a rarer one's from its short posting list
            row = self.has_word_counts and self.db.execute("select doc from dfs.df where term=?", (s,)).fetchone()
            row = row or self.db.execute("select doc from fts_v where term=?", (s,)).fetchone()
            if row:
                out.append((s, math.log(self.n_indexed / row[0])))
        return out

    def query_terms(self, stems, max_df=0.02, keep_min=3):
        """Rarest-first stems without the very common ones. A term found in more than `max_df`
        of all chunks adds little to BM25 but its posting list is what makes a query slow on
        cold flash, so it is dropped as long as `keep_min` rarer terms remain."""
        ranked = sorted(stems, key=lambda x: -x[1])
        min_idf = math.log(1 / max_df)
        kept = [s for s, idf in ranked if idf >= min_idf]
        return kept if len(kept) >= keep_min else [s for s, _ in ranked[:keep_min]]

    @staticmethod
    def coverage(text, stems):
        """Share of the question's idf mass whose terms occur in `text`."""
        low = text.lower()
        total = sum(idf for _, idf in stems) or 1.0
        return sum(idf for s, idf in stems if re.search(r"\b" + re.escape(_prefix(s)), low)) / total

    def passage_score(self, text, start, end, stems, aspect_bonus=0.25):
        """BM25-like score of one chunk inside an already chosen article, normalised to 0..1:
        idf-weighted saturated term frequency, with question terms in the section heading
        counting double (the heading says what the section is about)."""
        body = text[start:end].lower()
        heading = self.section_of(text, start).lower()
        total = sum(idf for _, idf in stems) or 1.0
        score = 0.0
        for s, idf in stems:
            pat = r"\b" + re.escape(_prefix(s))
            tf = len(re.findall(pat, body)) + 2 * len(re.findall(pat, heading))
            score += idf * tf / (tf + 1.5)
        score /= total
        # "what first aid is appropriate" should find the Treatment section even though every
        # section of the article repeats the topic words
        # aspect values are comma-separated heading words or phrases ("get around" must not
        # match the "Get in" section)
        if any(re.search(r"\b" + re.escape(ph) + r"\b", heading)
               for s, _ in stems for ph in ASPECT_HEADINGS.get(s, "").split(", ") if ph):
            score += aspect_bonus
        return score

    def _hit(self, aid, start, end, score, via):
        title, views, text = self.article(aid)
        return {"aid": aid, "start": start, "title": title, "section": self.section_of(text, start),
                "text": text[start:end].strip(), "score": round(score, 2), "via": via}

    def resolve_title(self, title, fuzzy=True):
        """Article id for a title the model proposed: exact match, then Wikipedia's redirects
        (built by build_redirects.py), then a title search ranked by popularity."""
        row = self.db.execute("select id from articles where title = ? collate nocase", (title,)).fetchone()
        if row:
            return row[0]
        if self.has_redirects:
            row = self.db.execute("select article_id from redirects where title = ?", (title,)).fetchone()
            if row:
                return row[0]
        words = re.findall(r"[^\W_]+", title.lower())
        # a one-word title that is neither an article nor a redirect is more often a different
        # entity than a near miss ("Ger" -> Ger Canning, "Ella" -> Ella Mai), so it stays unresolved
        if len(words) < 2 or not fuzzy:
            return None
        match = "title:(" + " AND ".join(f'"{w}"' for w in words) + ")"
        best = None
        for (rid,) in self.db.execute(
                "select rowid from fts where fts match ? order by bm25(fts, 8.0, 0.0, 0.0) limit 30", (match,)):
            aid = self.db.execute("select article_id from chunks where id=?", (rid,)).fetchone()[0]
            t, views = self.db.execute("select title, views from articles where id=?", (aid,)).fetchone()
            # FineWiki has no redirects, so "Rent control" must land on a longer real title:
            # prefer the most-read candidate, with a mild penalty per extra title word
            extra = max(0, len(re.findall(r"[^\W_]+", t)) - len(words))
            key = (math.log10(10 + views) - 0.5 * extra, aid)
            if extra > 1:
                continue  # "Sultanahmet" must not land on "Sultanahmet Jail Museum Hotel"
            if best is None or key > best:
                best = key
        return best[1] if best else None

    def article_passages(self, aid, stems, n_sections=2, aspect_bonus=0.25):
        """Lead chunk plus the `n_sections` chunks of the article that best cover the question."""
        _, _, text = self.article(aid)
        rows = self.db.execute("select start, end from chunks where article_id=? order by id", (aid,)).fetchall()
        if not rows:
            return []
        lead = rows[0]
        # the first chunk is often just the infobox facts; the prose lead follows it
        if text[lead[0]:lead[1]].startswith("Key facts:") and len(rows) > 1:
            lead = rows[1]
        scored = sorted(((self.passage_score(text, s, e, stems, aspect_bonus), s, e)
                         for s, e in rows if (s, e) != lead),
                        reverse=True)
        picks = [(1.0, *lead)] + [x for x in scored[:n_sections] if x[0] > 0.15]
        hits = [self._hit(aid, s, e, cov, "title") for cov, s, e in picks]
        hits[0]["lead"] = True
        return hits

    def bm25(self, stems, pool=80, prior=2.0, per_article=2, min_coverage=0.0, terms=None):
        """Whole-index BM25 with a popularity prior; passages below `min_coverage` are dropped.
        `terms` replaces the query terms query_terms would pick."""
        terms = self.query_terms(stems) if terms is None else terms
        if not terms:
            return []
        rows = self.db.execute(
            "select rowid, bm25(fts, 8.0, 3.0, 1.0) s from fts where fts match ? order by s limit ?",
            (" OR ".join(f'"{t}"' for t in terms), pool)).fetchall()
        cands = []
        for rid, score in rows:
            aid, start, end = self.db.execute(
                "select article_id, start, end from chunks where id=?", (rid,)).fetchone()
            views = self.db.execute("select views from articles where id=?", (aid,)).fetchone()[0]
            cands.append((-score + prior * math.log10(1 + views), aid, start, end))
        cands.sort(reverse=True)
        hits, per = [], {}
        for score, aid, start, end in cands:
            if per.get(aid, 0) >= per_article:
                continue
            hit = self._hit(aid, start, end, score, "bm25")
            if min_coverage and self.coverage(hit["title"] + " " + hit["text"], stems) < min_coverage:
                continue
            per[aid] = per.get(aid, 0) + 1
            hits.append(hit)
        return hits

    def retrieve(self, question, titles, k=6, voyage=None):
        """Passages for the planned titles first (round-robin so every part of a comparison is
        represented), then gated BM25 hits."""
        stems = self.stems(question)
        per_title, seen_aid = [], set()
        for t in titles:
            aid = self.resolve_title(t)
            passages = []
            if aid is not None and aid not in seen_aid:
                seen_aid.add(aid)
                passages = self.article_passages(aid, stems)
            # A travel guide for the same place. Its section names are standardised (See, Eat,
            # Respect, Stay safe, Get around, ...), so the question's intent picks the section
            # (strong aspect bonus), and guide sections alternate with the encyclopedia's instead
            # of displacing them. Guide titles follow the same fuzzy rule as Wikipedia's.
            vaid = voyage.resolve_title(t) if voyage else None
            if vaid is not None and ("v", vaid) not in seen_aid:
                seen_aid.add(("v", vaid))
                guide = voyage.article_passages(vaid, stems, aspect_bonus=0.5)
                for h in guide:
                    h["title"] = "Wikivoyage: " + h["title"]
                    h["aid"] = ("v", h["aid"])
                lead = passages[:1] or guide[:1]
                ours, theirs = passages[1:], guide[1:]
                if theirs and (not ours or theirs[0]["score"] > ours[0]["score"]):
                    ours, theirs = theirs, ours
                rest = [h for pair in zip(ours, theirs) for h in pair]
                rest += ours[len(theirs):] + theirs[len(ours):]
                passages = lead + rest
            if passages:
                per_title.append(passages)
        hits = []
        if per_title:
            hits.extend(per_title[0][:2])
        for rank in range(5):
            hits.extend(p[rank] for p in per_title if len(p) > rank and p[rank] not in hits)
        limit = max(k, len(per_title) * 2)
        if len(hits) >= limit:  # the titles' passages fill the result: no BM25 hit could reach it
            return hits[:limit]
        seen = {(h["aid"], h["start"]) for h in hits}
        topic = self.stems(" ".join(titles)) or stems
        for h in self.bm25(stems):
            if (h["aid"], h["start"]) not in seen and \
                    self.coverage(h["title"] + " " + h["text"], topic) >= 0.5:
                hits.append(h)
        return hits[:limit]


def build_context(hits, budget_chars, passage_chars=650, lead_chars=1000):
    """Pack passages into the prompt budget. Returns (context, used_hits): callers must report
    `used_hits`, not `hits`, as the sources, because whatever does not fit never reaches the
    model (round three lost questions to passages that were listed but silently dropped).
    Long passages are cut at a sentence end so that more distinct passages fit."""
    parts, used, used_hits = [], 0, []
    for h in hits:
        text = h["text"]
        # an article's lead carries its defining facts (trimming it to 650 cost answers in round
        # four), so it gets a longer limit than secondary sections
        limit = lead_chars if h.get("lead") else passage_chars
        if len(text) > limit:
            cut = max(text.rfind(". ", 0, limit), text.rfind("\n", 0, limit))
            text = text[:cut + 1 if cut > limit // 2 else limit].rstrip()
        head = f"[{len(parts) + 1}] {h['title']}" + (f" — {h['section']}" if h["section"] else "")
        block = f"{head}\n{text}"
        if used + len(block) > budget_chars and parts:
            continue  # a shorter later passage may still fit
        parts.append(block)
        used_hits.append(h)
        used += len(block)
    return "\n\n".join(parts), used_hits


# ---- a shorter context for the source check ---------------------------------------------------
# The check tests the draft's statements, so what it needs are the passages (in the excerpt form,
# the sentences) that share the draft's names and numbers, and the question's, not six passages in
# full: on the phone reading them is most of the check's time (about 1,100 tokens at 23/s).
CHECK_NUMBER = re.compile(r"\d[\d,.]*\d|\d")
CHECK_WORD = re.compile(r"[^\W\d_][\w'\u2019-]*")
# a sentence ends after a word of two lowercase letters or a number (not "U.S." or "H. W.")
CHECK_SENTENCE = re.compile(r"(?<=[a-z0-9)\]][a-z0-9%)\]][.!?])\s+(?=[A-Z\"\u201c(])|\n+")


def check_terms(text):
    """What a check tests in [text], term -> weight: numbers 3 (without thousands commas), words
    that start with a capital 2, other words of four letters or more 1 (stopwords left out)."""
    terms = {}
    for m in CHECK_NUMBER.finditer(text):
        t = m.group(0).replace(",", "").rstrip(".")
        terms[t] = max(terms.get(t, 0), 3)
    for m in CHECK_WORD.finditer(text):
        w = m.group(0)
        low = w.lower()
        if low in STOP or (len(low) < 4 and not w[0].isupper()):
            continue
        terms[low] = max(terms.get(low, 0), 2 if w[0].isupper() else 1)
    return terms


def check_overlap(text, terms):
    """How much of [terms] [text] contains: the weights of the distinct terms it has."""
    have = set(check_terms(text))
    return sum(w for t, w in terms.items() if t in have)


def check_excerpt(text, terms, limit):
    """A passage cut to its first sentence and the sentences that share most with [terms], in their
    order, within [limit] characters."""
    sents = [x.strip() for x in CHECK_SENTENCE.split(text) if x.strip()]
    if not sents:
        return text
    keep = {0}
    used = len(sents[0])
    ranked = sorted(range(1, len(sents)), key=lambda i: (-check_overlap(sents[i], terms), i))
    for i in ranked:
        if check_overlap(sents[i], terms) == 0:
            break
        if used + 1 + len(sents[i]) > limit:
            continue
        keep.add(i)
        used += 1 + len(sents[i])
    out, last = [], -1
    for i in sorted(keep):
        if last >= 0 and i != last + 1:
            out.append("…")
        out.append(sents[i])
        last = i
    return " ".join(out)


def check_context(hits, draft, question, budget_chars, excerpt=False):
    """build_context for the source check: the passages that share most with the draft and the
    question first, within [budget_chars]; with [excerpt], each cut to its sentences that do."""
    terms = check_terms(draft)
    for t, w in check_terms(question).items():
        terms[t] = max(terms.get(t, 0), w)
    scored = []
    for i, h in enumerate(hits):
        text = h["text"]
        if excerpt:
            text = check_excerpt(text, terms, 500 if h.get("lead") else 400)
        scored.append((-check_overlap(text, terms), i, dict(h, text=text)))
    return build_context([h for _, _, h in sorted(scored, key=lambda x: (x[0], x[1]))], budget_chars)


VOYAGE = None  # optional second Corpus built from Wikivoyage (--voyage-db)
PACK = None  # optional Corpus of the Ethereum and cryptography pack (--pack-db)


def pack_as_of():
    row = PACK.db.execute("select value from meta where key='as_of'").fetchone()
    return row[0] if row else "2026"


# Which questions the pack answers: one of the question's words is at least e**3 = 20 times more
# common in the pack than in Wikipedia (ethereum, eip, rollup, signature, quantum), and less than a
# quarter of the question's weight is in words the pack uses no more than Wikipedia does ("passport"
# in "is my passport still valid?", "fish" in "which fork for fish?"). Weights and ratios come from
# the two indexes' document counts; tuned on the eval sets' 222 questions and 28 probes.
PACK_MIN_RATIO = 3.0
PACK_FOREIGN_RATIO = 0.5
PACK_MAX_FOREIGN = 0.25


def pack_affinity(question, wiki_stems):
    """(largest log ratio, foreign share) of the question's words: a word's log ratio is how much
    more common it is in the pack than in Wikipedia (ln of the ratio of the shares of chunks that
    hold it, = Wikipedia idf - pack idf); the foreign share is the Wikipedia-idf weight of the
    words below PACK_FOREIGN_RATIO or not in the pack, over the weight of all. A word the pack has
    and Wikipedia has not counts as if Wikipedia had it in one chunk. None when no word is known."""
    ps = dict(PACK.stems(question))
    ws = dict(wiki_stems)
    if not ws and not ps:
        return None
    unseen = math.log(WIKI_N_INDEXED[0]) if WIKI_N_INDEXED[0] else 0.0
    best, foreign, total = None, 0.0, 0.0
    for s in sorted(set(ws) | set(ps)):
        widf = ws.get(s, unseen)
        total += widf
        if s in ps:
            lr = widf - ps[s]
            best = lr if best is None else max(best, lr)
            if lr < PACK_FOREIGN_RATIO:
                foreign += widf
        else:
            foreign += widf
    return best, (foreign / total if total else 1.0)


def pack_route(question, wiki_stems):
    """Whether the question goes to the pack (see PACK_MIN_RATIO)."""
    a = pack_affinity(question, wiki_stems)
    return a is not None and a[0] is not None and a[0] >= PACK_MIN_RATIO and a[1] < PACK_MAX_FOREIGN


WIKI_N_INDEXED = [0]  # the encyclopedia's chunk count, for words it does not have (set in main)


def pack_terms(pack_stems, wiki_stems, max_df=0.02, keep_min=3):
    """The pack search's query terms: the question's words, rarest in the pack first, without the
    ones common in English, which is to say in Wikipedia (more than max_df of its chunks: "between",
    "differ", "work"). Unlike query_terms, a word common only in the pack stays ("signature" in a
    question about signatures). Fewer than keep_min left: the keep_min rarest in the pack."""
    ws = dict(wiki_stems)
    ranked = sorted(pack_stems, key=lambda s: -s[1])  # stable, like query_terms
    min_idf = math.log(1 / max_df)
    kept = [s for s, _ in ranked if ws.get(s, min_idf) >= min_idf]
    return kept if len(kept) >= keep_min else [s for s, _ in ranked[:keep_min]]


def pack_hits(question, wiki_stems, n=4):
    """The pack's best passages for the question (whole-index BM25 over pack_terms, at most two
    per document). A document's key facts (an EIP's status and the network upgrade that shipped
    it) lead the first passage taken from it."""
    hits, seen = [], set()
    stems = PACK.stems(question)
    for h in PACK.bm25(stems, terms=pack_terms(stems, wiki_stems))[:n]:
        _, _, text = PACK.article(h["aid"])
        if h["aid"] not in seen and text.startswith("Key facts:"):
            facts = text.split("\n\n", 1)[0]
            if facts not in h["text"]:
                h["text"] = facts + "\n" + h["text"]
                h["lead"] = True  # build_context's longer limit, so the facts do not crowd out the passage
        seen.add(h["aid"])
        h["aid"] = ("p", h["aid"])
        hits.append(h)
    return hits
ENGINE = None  # a BmoeSession when --engine-cli is given; otherwise llama-server at --url


def chat_messages(url, messages, max_tokens, temperature=0.0):
    """A multi-turn request to llama-server (used for the follow-up source check)."""
    body = {"messages": messages, "max_tokens": max_tokens, "temperature": temperature, "top_p": 0.8,
            "top_k": 20, "presence_penalty": 0.0, "seed": 1234}
    req = urllib.request.Request(url + "/v1/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=3600) as r:
        resp = json.load(r)
    tm = resp.get("timings", {})
    return {"text": resp["choices"][0]["message"]["content"],
            "finish": resp["choices"][0].get("finish_reason"), "wall_s": round(time.time() - t0, 1),
            "prompt_tokens": tm.get("prompt_n"), "prompt_tps": round(tm.get("prompt_per_second", 0), 2),
            "gen_tokens": tm.get("predicted_n"), "gen_tps": round(tm.get("predicted_per_second", 0), 2)}


def chat(url, system, user, max_tokens, temperature=0.7):
    if ENGINE is not None:
        # the session protocol takes one user message, so the system text leads the prompt;
        # sampling is the engine's (greedy)
        r = ENGINE.generate(f"{system}\n\n{user}", max_tokens)
        d = r["done"]
        return {"text": r["text"], "finish": "length" if d.get("tokens", 0) >= max_tokens else "stop",
                "wall_s": r["wall_s"], "ttft_s": r["ttft_s"], "prompt_tokens": d.get("n_prompt"),
                "prompt_tps": round((d.get("n_prompt") or 0) / r["ttft_s"], 2) if r["ttft_s"] else 0,
                "gen_tokens": d.get("tokens"), "gen_tps": round(d.get("tok_s", 0), 2), "engine": d}
    body = {"messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "max_tokens": max_tokens, "temperature": temperature, "top_p": 0.8, "top_k": 20,
            "presence_penalty": 1.5 if temperature else 0.0, "seed": 1234}
    req = urllib.request.Request(url + "/v1/chat/completions", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=3600) as r:
        resp = json.load(r)
    tm = resp.get("timings", {})
    return {"text": resp["choices"][0]["message"]["content"],
            "finish": resp["choices"][0].get("finish_reason"), "wall_s": round(time.time() - t0, 1),
            "prompt_tokens": tm.get("prompt_n"), "prompt_tps": round(tm.get("prompt_per_second", 0), 2),
            "gen_tokens": tm.get("predicted_n"), "gen_tps": round(tm.get("predicted_per_second", 0), 2)}


# Experimental alternative to the source check: the model revises its own draft with the sources
# in view, so its knowledge is kept and the sources' corrections are applied (--rewrite).
REWRITE_SYSTEM = (
    "You revise a draft answer using numbered sources from an offline copy of Wikipedia and "
    "Wikivoyage. Write the final answer to the question. Keep everything in the draft that is "
    "correct or that the sources do not contradict, even if the sources do not mention it. "
    "Where a source contradicts the draft, use the source's fact. Add specifics from the sources "
    "that help answer the question. Cite a source like [2] after each statement it supports; "
    "statements from the draft alone carry no citation or marker at all. Each source is about the subject named "
    "in its title; do not attach its facts to another subject, and ignore off-topic sources. "
    "Never mention the draft, the sources' gaps, or your revisions. Address every part of the "
    "question first, then elaborate. No preamble, no LaTeX. Be concise."
)


# a list marker is a bullet, or one or two digits followed by "." or ")". Anything else that
# starts with digits is part of the title ("1983 Harrods bombing", "1984 (novel)").
LIST_MARKER = re.compile(r"^\s*(?:[-*\u2022]\s+|\d{1,2}[.)]\s+)?")


def parse_plan_output(text):
    titles = [LIST_MARKER.sub("", line).strip().strip('"') for line in text.splitlines()]
    # a line of four or more words found verbatim in the planning prompt is the model echoing its
    # instructions ("Output only the titles, nothing else."), never an article title
    return [t for t in titles if 1 < len(t) < 80 and not is_prompt_echo(t)][:4]


def is_prompt_echo(line):
    return len(line.split()) >= 4 and line.lower() in PLAN_SYSTEM.lower()


def plan(url, question):
    res = chat(url, PLAN_SYSTEM, question, max_tokens=60, temperature=0.0)
    return parse_plan_output(res["text"]), res


def answer(corpus, args, question):
    """One question through the chosen mode.

    verify: answer from the model's own knowledge first (the measured quality floor for things
            it knows, and a first token within seconds), then check that draft against sources.
    plan:   retrieval-first; the model answers with the sources in context.
    auto:   plan the lookups, then route on how widely read the subject article is. Below
            --route-views monthly views the model's own draft is usually fabricated (21 of 24
            in the long-tail eval), so go retrieval-first; otherwise verify."""
    rec = {}
    hits, titles, draft = [], [], None
    mode = args.mode
    if mode in ("plan", "verify", "auto"):
        titles, pres = plan(args.url, question)
        rec.update(plan_titles=titles, plan_s=pres["wall_s"])
    if mode == "auto":
        aid = corpus.resolve_title(titles[0]) if titles else None
        views = corpus.db.execute("select views from articles where id=?", (aid,)).fetchone()[0] if aid else None
        mode = "plan" if views is not None and views < args.route_views else "verify"
        # optional: a travel question about a place that has a travel guide goes retrieval-first
        if args.travel_route and VOYAGE is not None and titles and VOYAGE.resolve_title(titles[0]) is not None \
                and any(st in TRAVEL_STEMS for st, _ in corpus.stems(question)):
            mode = "plan"
        rec.update(route=mode, route_views=views)
    # the pack's questions: sources first with its passages, or answer first and a check against them
    route = getattr(args, "pack_route", "off")
    wiki_stems = corpus.stems(question) if PACK is not None and route != "off" else None
    use_pack = PACK is not None and (route == "always" or (route == "auto" and pack_route(question, wiki_stems)))
    if use_pack:
        mode = "plan" if args.pack_mode == "sources" else "verify"
        rec.update(route=mode, pack=True)
    worked = getattr(args, "worked", False) and needs_working(question)
    closed = WORKED_SYSTEM if worked else CLOSED_SYSTEM
    rec["worked"] = worked
    if mode == "verify":
        draft = chat(args.url, closed, question, args.max_tokens)
        rec.update(draft=draft["text"], draft_s=draft["wall_s"], draft_tokens=draft["gen_tokens"],
                   draft_finish=draft["finish"])
    t0 = time.time()
    if mode in ("plan", "verify"):
        hits = corpus.retrieve(question, titles, k=args.k, voyage=VOYAGE)
        if use_pack:
            hits = pack_hits(question, wiki_stems, args.pack_passages) + hits
    elif mode == "bm25":
        hits = corpus.bm25(corpus.stems(question))[:args.k]
    rec["search_ms"] = round((time.time() - t0) * 1000)
    if draft is not None and args.check_chars and not args.rewrite:
        # the source check of a draft reads the passages that share most with it (the app's default)
        context, used_hits = check_context(hits, draft["text"], question, args.check_chars)
    else:
        context, used_hits = build_context(hits, args.context_chars)
    rec["sources"] = [f"{h['title']} — {h['section']} ({h['via']})" for h in used_hits]
    rec["sources_dropped"] = len(hits) - len(used_hits)
    if draft is not None and context and args.rewrite:
        user = f"Question: {question}\n\nDraft answer:\n{draft['text']}\n\nSources:\n\n{context}"
        res = chat(args.url, REWRITE_SYSTEM, user, args.max_tokens + 100, temperature=0.0)
        rec["answer"] = res.pop("text").strip()
    elif draft is not None and context:
        if args.check_continue:
            res = chat_messages(args.url, [
                {"role": "system", "content": closed}, {"role": "user", "content": question},
                {"role": "assistant", "content": draft["text"]},
                {"role": "user", "content": check_followup_user(
                    context, pack_check_followup(pack_as_of()) if use_pack else None)}], 260)
        else:
            user = f"Question: {question}\n\nDraft answer:\n{draft['text']}\n\nSources:\n\n{context}"
            res = chat(args.url, VERIFY_SYSTEM, user, 260, temperature=0.0)
        check = clean_check(res.pop("text"))
        rec["check"] = check
        # a check that was nothing but deliberation cleans to "": show the draft alone
        rec["answer"] = draft["text"] + (f"\n\n**Source check**\n{check}" if check else "")
    elif draft is not None:
        res = {k: v for k, v in draft.items() if k != "text"}
        rec["answer"] = draft["text"]
    elif context:
        system = (WORKED_SOURCES_SYSTEM if worked else pack_answer_system(pack_as_of(), args.pack_prompt) if use_pack
                  else ANSWER_SYSTEM)
        res = chat(args.url, system, f"Sources:\n\n{context}\n\nQuestion: {question}", args.max_tokens)
        rec["answer"] = res.pop("text")
    else:
        res = chat(args.url, closed, question, args.max_tokens)
        rec["answer"] = res.pop("text")
    rec.update(res)
    return rec, context


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("question", nargs="?")
    ap.add_argument("--db", required=True)
    ap.add_argument("--url", default="http://127.0.0.1:8091")
    ap.add_argument("--mode", default="plan", choices=["auto", "verify", "plan", "bm25", "none"])
    ap.add_argument("--search-only", action="store_true")
    ap.add_argument("--questions")
    ap.add_argument("--out")
    ap.add_argument("-k", type=int, default=6)
    ap.add_argument("--context-chars", type=int, default=4000, help="about 1000 tokens, six passages")
    ap.add_argument("--max-tokens", type=int, default=600)
    ap.add_argument("--route-views", type=int, default=5000,
                    help="auto mode: go retrieval-first when the subject article has fewer monthly views")
    ap.add_argument("--check-chars", type=int, default=0,
                    help="answer-first questions: the source check reads at most this many characters of the "
                         "passages that share most with the draft (check_context; the app uses 2000); 0: --context-chars")
    ap.add_argument("--check-continue", action="store_true",
                    help="answer-first questions: ask for the source check as a follow-up turn of the draft")
    ap.add_argument("--rewrite", action="store_true",
                    help="answer-first questions: revise the draft with the sources instead of appending a source check")
    ap.add_argument("--worked", action="store_true",
                    help="questions that need a calculation get a few lines of working before the answer")
    ap.add_argument("--travel-route", action="store_true",
                    help="auto mode: travel questions about a place with a Wikivoyage guide go retrieval-first")
    ap.add_argument("--voyage-db", help="Wikivoyage corpus database; adds travel-guide sections")
    ap.add_argument("--pack-db", help="the Ethereum and cryptography pack (build_pack.py + build_corpus.py)")
    ap.add_argument("--pack-route", default="off", choices=["off", "auto", "always"],
                    help="auto: the questions pack_route picks use the pack; always: every question does "
                         "(for evaluating it on its own questions)")
    ap.add_argument("--pack-mode", default="sources", choices=["sources", "check"],
                    help="sources: answer with the pack's passages in context; check: answer first, then "
                         "check against them")
    ap.add_argument("--pack-passages", type=int, default=4, help="pack passages ahead of Wikipedia's")
    ap.add_argument("--pack-prompt", type=int, default=1, choices=[1, 2],
                    help="the sources-first answer prompt: 1 as for Wikipedia's sources, 2 asks for an expert's full answer")
    ap.add_argument("--engine-cli", help="path to bmoe-cli: stream the model through BigMoeOnEdge "
                    "session mode instead of calling llama-server")
    ap.add_argument("--engine-model")
    ap.add_argument("--cache-mb", type=int, default=5000)
    args = ap.parse_args()
    if args.engine_cli:
        global ENGINE
        from bmoe_session import BmoeSession
        ENGINE = BmoeSession(args.engine_cli, args.engine_model, cache_mb=args.cache_mb)
        print(f"engine ready in {ENGINE.load_s}s: {ENGINE.ready}", flush=True)
    corpus = Corpus(args.db)
    if args.voyage_db:
        global VOYAGE
        VOYAGE = Corpus(args.voyage_db)
    if args.pack_db:
        global PACK
        PACK = Corpus(args.pack_db)
        WIKI_N_INDEXED[0] = corpus.n_indexed

    if args.questions:
        done = set()
        if os.path.exists(args.out):
            done = {json.loads(line)["id"] for line in open(args.out) if line.strip()}
        for q in (json.loads(line) for line in open(args.questions) if line.strip()):
            if q["id"] in done:
                continue
            rec, _ = answer(corpus, args, q["q"])
            with open(args.out, "a") as f:
                f.write(json.dumps({**q, **rec}, ensure_ascii=False) + "\n")
            print(f"{q['id']}: plan {rec.get('plan_titles')} | search {rec['search_ms']} ms | "
                  f"prompt {rec['prompt_tokens']} tok @ {rec['prompt_tps']}/s | "
                  f"gen {rec['gen_tokens']} tok @ {rec['gen_tps']}/s", flush=True)
        return

    if args.search_only:
        planned = args.mode in ("plan", "verify", "auto")
        titles, _ = plan(args.url, args.question) if planned else ([], None)
        t0 = time.time()
        hits = (corpus.retrieve(args.question, titles, k=args.k, voyage=VOYAGE) if planned
                else corpus.bm25(corpus.stems(args.question))[:args.k])
        print(f"plan: {titles}\nsearch: {(time.time() - t0) * 1000:.0f} ms")
        for h in hits:
            print(f"  {h['via']:5} {h['score']:6} {h['title']} — {h['section']}")
        return
    rec, _ = answer(corpus, args, args.question)
    print(rec["answer"])
    print(f"\n[plan {rec.get('plan_titles')} | prompt {rec['prompt_tokens']} tok @ {rec['prompt_tps']}/s | "
          f"gen {rec['gen_tokens']} tok @ {rec['gen_tps']}/s]\nsources: {rec['sources']}")


if __name__ == "__main__":
    main()
