---
license: cc-by-sa-4.0
language:
- en
pretty_name: AndroidLM offline corpus (English Wikipedia + Wikivoyage, SQLite FTS5)
size_categories:
- 1M<n<10M
---

# AndroidLM offline corpus

SQLite databases used by [AndroidLM](https://github.com/Phineas1500/AndroidLM), an offline research
assistant for Android. They are ordinary SQLite files (rollback-journal mode, FTS5) and can be
opened read-only with any SQLite build that includes FTS5.

| File | Size | Contents |
|---|---|---|
| `v2/wiki.db` | 30.1GB | English Wikipedia from the FineWiki extraction (August 2025 HTML dump): 6.06M articles, all in full; 57.0M passages of which 44.3M are in a BM25 full-text index; Wikipedia's redirect table (September 2026); August 2026 monthly pageviews per article |
| `voyage.db` | 0.33GB | English Wikivoyage (September 2026 dump): 34,004 travel guides with listing templates rendered as text, plus redirects |
| `v2/wiki_df.db` | 2.3MB | For `v2/wiki.db`: how many indexed passages contain each of the 155,285 stems found in at least 256 of them (`df(term, doc)`, the FTS5 index's own counts), so a search can rank a question's words without reading their posting lists; `meta` records what it was built from. Optional: a search returns the same results without it |

`wiki.db` and `wiki_df.db` at the top level are the previous build (21.3GB: the 1.87M most-read
articles in full and lead sections for the rest), which AndroidLM 1.6.1 and earlier download;
1.7.0 and newer use `v2/`.

Schema: `blocks(id, zdata)` holds zstd-compressed runs of article text; `articles(id, title,
views, block_id, off, len)` locates an article as a byte range in a block; `chunks(id,
article_id, start, end)` are passage offsets in Unicode code points; `fts(title, section,
body)` is a contentless FTS5 index whose rowid is the chunk id; `redirects(title, article_id)`.
The build scripts are in the AndroidLM repository (`scripts/build_corpus.py`,
`scripts/build_redirects.py`, `scripts/wikivoyage_to_parquet.py`, `scripts/build_df.py`).

## Sources and licence

- Text: English Wikipedia and English Wikivoyage contributors, CC BY-SA 4.0. Wikipedia text was
  taken from [HuggingFaceFW/finewiki](https://huggingface.co/datasets/HuggingFaceFW/finewiki).
- Redirects, titles and pageviews: Wikimedia dumps (https://dumps.wikimedia.org).

This corpus is a derivative of that text and is distributed under CC BY-SA 4.0. Each passage
shown by the app is attributed by its article title; the article's history and authors are at
`https://en.wikipedia.org/wiki/<title>` or `https://en.wikivoyage.org/wiki/<title>`.
