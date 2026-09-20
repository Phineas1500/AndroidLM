#!/usr/bin/env python3
"""Per-article size stats for budgeting the corpus: how much text the top-N articles by
pageviews hold, in full and lead-only form.

Pass 1 (slow, once):  corpus_stats.py scan shard.parquet... > article_sizes.tsv
    columns: page_id, text_chars, lead_chars, table_chars
Pass 2 (fast):        corpus_stats.py budget article_sizes.tsv pageviews_en.tsv
"""
import sys


def lead_of(text, cap=2500):
    """Text before the first level-2 heading, capped."""
    cut = text.find("\n## ")
    lead = text if cut < 0 else text[:cut]
    return lead[:cap]


def scan(shards):
    import pyarrow.parquet as pq
    for shard in shards:
        f = pq.ParquetFile(shard)
        for g in range(f.metadata.num_row_groups):
            for r in f.read_row_group(g, columns=["page_id", "text"]).to_pylist():
                text = r["text"] or ""
                table = sum(len(line) + 1 for line in text.split("\n") if line.startswith("|"))
                print(r["page_id"], len(text), len(lead_of(text)), table, sep="\t")


def budget(sizes_path, views_path):
    views = {}
    for line in open(views_path):
        pid, v = line.split("\t")
        views[int(pid)] = int(v)
    rows = []
    for line in open(sizes_path):
        pid, n, lead, table = map(int, line.split("\t"))
        rows.append((views.get(pid, 0), n, lead, table))
    rows.sort(reverse=True)
    total = sum(r[1] for r in rows)
    total_views = sum(r[0] for r in rows) or 1
    lead_all = sum(r[2] for r in rows)
    print(f"{len(rows)} articles, {total/1e9:.1f}G chars, tables {sum(r[3] for r in rows)/1e9:.1f}G, "
          f"all leads {lead_all/1e9:.1f}G, with views {sum(1 for r in rows if r[0])}")
    print("top_n\tviews_share\tfull_chars_G\tno_tables_G\ttail_leads_G\tmin_views")
    marks = [250_000, 500_000, 1_000_000, 1_500_000, 2_000_000, 3_000_000, len(rows)]
    cv = cf = ct = cl = 0
    mi = 0
    for i, (v, n, lead, table) in enumerate(rows, 1):
        cv += v; cf += n; ct += table; cl += lead
        if i == marks[mi]:
            print(f"{i}\t{cv/total_views:.3f}\t{cf/1e9:.1f}\t{(cf-ct)/1e9:.1f}\t{(lead_all-cl)/1e9:.1f}\t{v}")
            mi += 1
            if mi >= len(marks):
                break


if __name__ == "__main__":
    if sys.argv[1] == "scan":
        scan(sys.argv[2:])
    else:
        budget(sys.argv[2], sys.argv[3])
