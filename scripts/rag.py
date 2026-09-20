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

# headings that answer an aspect the question asks about in other words
ASPECT_HEADINGS = {
    "treat": "treatment management therapy", "aid": "treatment management first aid",
    "prevent": "prevention prophylaxis", "avoid": "prevention",
    "symptom": "signs symptoms presentation", "sign": "signs symptoms presentation",
    "caus": "cause causes etiology", "see": "attractions landmarks sights tourism",
    "visit": "attractions landmarks sights tourism", "food": "cuisine", "eat": "cuisine",
    "etiquett": "etiquette customs", "weather": "climate", "season": "climate",
}


def _prefix(stem):
    """Surface-form prefix for a porter stem (porter turns a final y into i: energy -> energi)."""
    return stem[:-1] if stem.endswith("i") and len(stem) > 3 else stem


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
            row = self.db.execute("select doc from fts_v where term=?", (s,)).fetchone()
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

    def passage_score(self, text, start, end, stems):
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
        if any(w in heading for s, _ in stems for w in ASPECT_HEADINGS.get(s, "").split()):
            score += 0.25
        return score

    def _hit(self, aid, start, end, score, via):
        title, views, text = self.article(aid)
        return {"aid": aid, "start": start, "title": title, "section": self.section_of(text, start),
                "text": text[start:end].strip(), "score": round(score, 2), "via": via}

    def resolve_title(self, title):
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
        if not words:
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

    def article_passages(self, aid, stems, n_sections=2):
        """Lead chunk plus the `n_sections` chunks of the article that best cover the question."""
        _, _, text = self.article(aid)
        rows = self.db.execute("select start, end from chunks where article_id=? order by id", (aid,)).fetchall()
        if not rows:
            return []
        lead = rows[0]
        # the first chunk is often just the infobox facts; the prose lead follows it
        if text[lead[0]:lead[1]].startswith("Key facts:") and len(rows) > 1:
            lead = rows[1]
        scored = sorted(((self.passage_score(text, s, e, stems), s, e) for s, e in rows if (s, e) != lead),
                        reverse=True)
        picks = [(1.0, *lead)] + [x for x in scored[:n_sections] if x[0] > 0.15]
        hits = [self._hit(aid, s, e, cov, "title") for cov, s, e in picks]
        hits[0]["lead"] = True
        return hits

    def bm25(self, stems, pool=80, prior=2.0, per_article=2, min_coverage=0.0):
        """Whole-index BM25 with a popularity prior; passages below `min_coverage` are dropped."""
        terms = self.query_terms(stems)
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

    def retrieve(self, question, titles, k=6):
        """Passages for the planned titles first (round-robin so every part of a comparison is
        represented), then gated BM25 hits."""
        stems = self.stems(question)
        per_title, seen_aid = [], set()
        for t in titles:
            aid = self.resolve_title(t)
            if aid is not None and aid not in seen_aid:
                seen_aid.add(aid)
                per_title.append(self.article_passages(aid, stems))
        hits = []
        if per_title:
            hits.extend(per_title[0][:2])
        for rank in range(3):
            hits.extend(p[rank] for p in per_title if len(p) > rank and p[rank] not in hits)
        seen = {(h["aid"], h["start"]) for h in hits}
        topic = self.stems(" ".join(titles)) or stems
        for h in self.bm25(stems):
            if (h["aid"], h["start"]) not in seen and \
                    self.coverage(h["title"] + " " + h["text"], topic) >= 0.5:
                hits.append(h)
        return hits[:max(k, len(per_title) * 2)]


def build_context(hits, budget_chars, passage_chars=650):
    """Pack passages into the prompt budget. Returns (context, used_hits): callers must report
    `used_hits`, not `hits`, as the sources, because whatever does not fit never reaches the
    model (round three lost questions to passages that were listed but silently dropped).
    Long passages are cut at a sentence end so that more distinct passages fit."""
    parts, used, used_hits = [], 0, []
    for h in hits:
        text = h["text"]
        # an article's lead carries its defining facts; trimming it cost answers in round four,
        # so only secondary sections are shortened
        if len(text) > passage_chars and not h.get("lead"):
            cut = max(text.rfind(". ", 0, passage_chars), text.rfind("\n", 0, passage_chars))
            text = text[:cut + 1 if cut > passage_chars // 2 else passage_chars].rstrip()
        head = f"[{len(parts) + 1}] {h['title']}" + (f" — {h['section']}" if h["section"] else "")
        block = f"{head}\n{text}"
        if used + len(block) > budget_chars and parts:
            continue  # a shorter later passage may still fit
        parts.append(block)
        used_hits.append(h)
        used += len(block)
    return "\n\n".join(parts), used_hits


ENGINE = None  # a BmoeSession when --engine-cli is given; otherwise llama-server at --url


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


def plan(url, question):
    res = chat(url, PLAN_SYSTEM, question, max_tokens=60, temperature=0.0)
    titles = [re.sub(r"^[\s\-\*\d\.\)]+", "", line).strip().strip('"') for line in res["text"].splitlines()]
    return [t for t in titles if 1 < len(t) < 80][:4], res


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
        rec.update(route=mode, route_views=views)
    if mode == "verify":
        draft = chat(args.url, CLOSED_SYSTEM, question, args.max_tokens)
        rec.update(draft=draft["text"], draft_s=draft["wall_s"], draft_tokens=draft["gen_tokens"],
                   draft_finish=draft["finish"])
    t0 = time.time()
    if mode in ("plan", "verify"):
        hits = corpus.retrieve(question, titles, k=args.k)
    elif mode == "bm25":
        hits = corpus.bm25(corpus.stems(question))[:args.k]
    rec["search_ms"] = round((time.time() - t0) * 1000)
    context, used_hits = build_context(hits, args.context_chars)
    rec["sources"] = [f"{h['title']} — {h['section']} ({h['via']})" for h in used_hits]
    rec["sources_dropped"] = len(hits) - len(used_hits)
    if draft is not None and context:
        user = f"Question: {question}\n\nDraft answer:\n{draft['text']}\n\nSources:\n\n{context}"
        res = chat(args.url, VERIFY_SYSTEM, user, 260, temperature=0.0)
        check = res.pop("text").strip()
        rec["check"] = check
        rec["answer"] = draft["text"] + f"\n\n**Source check**\n{check}"
    elif draft is not None:
        res = {k: v for k, v in draft.items() if k != "text"}
        rec["answer"] = draft["text"]
    elif context:
        res = chat(args.url, ANSWER_SYSTEM, f"Sources:\n\n{context}\n\nQuestion: {question}", args.max_tokens)
        rec["answer"] = res.pop("text")
    else:
        res = chat(args.url, CLOSED_SYSTEM, question, args.max_tokens)
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
        hits = (corpus.retrieve(args.question, titles, k=args.k) if planned
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
