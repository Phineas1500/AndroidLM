---
license: cc-by-sa-4.0
language:
- en
pretty_name: AndroidLM offline corpus (English Wikipedia + Wikivoyage, SQLite FTS5)
size_categories:
- 1M<n<10M
---

# AndroidLM offline corpus

Two SQLite databases used by [AndroidLM](REPLACE_WITH_REPOSITORY_URL), an offline research
assistant for Android. They are ordinary SQLite files (rollback-journal mode, FTS5) and can be
opened read-only with any SQLite build that includes FTS5.

| File | Size | Contents |
|---|---|---|
| `wiki.db` | 21.3GB | English Wikipedia from the FineWiki extraction (August 2025 HTML dump): 6.05M articles, the 1.87M most-read in full and lead sections for the rest; 38.9M passages of which 31.9M are in a BM25 full-text index; Wikipedia's redirect table (September 2026); August 2026 monthly pageviews per article |
| `voyage.db` | 0.33GB | English Wikivoyage (September 2026 dump): 34,004 travel guides with listing templates rendered as text, plus redirects |

Schema: `blocks(id, zdata)` holds zstd-compressed runs of article text; `articles(id, title,
views, block_id, off, len)` locates an article as a byte range in a block; `chunks(id,
article_id, start, end)` are passage offsets in Unicode code points; `fts(title, section,
body)` is a contentless FTS5 index whose rowid is the chunk id; `redirects(title, article_id)`.
The build scripts are in the AndroidLM repository (`scripts/build_corpus.py`,
`scripts/build_redirects.py`, `scripts/wikivoyage_to_parquet.py`).

## Sources and licence

- Text: English Wikipedia and English Wikivoyage contributors, CC BY-SA 4.0. Wikipedia text was
  taken from [HuggingFaceFW/finewiki](https://huggingface.co/datasets/HuggingFaceFW/finewiki).
- Redirects, titles and pageviews: Wikimedia dumps (https://dumps.wikimedia.org).

This corpus is a derivative of that text and is distributed under CC BY-SA 4.0. Each passage
shown by the app is attributed by its article title; the article's history and authors are at
`https://en.wikipedia.org/wiki/<title>` or `https://en.wikivoyage.org/wiki/<title>`.
