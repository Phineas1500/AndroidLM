#!/usr/bin/env bash
# Stream one month of Wikimedia pageviews and keep per-page totals for English Wikipedia.
# Output: pageviews_en.tsv with "page_id<TAB>views" (all access types summed).
set -euo pipefail
MONTH=${1:-2026-08}
URL="https://dumps.wikimedia.org/other/pageview_complete/monthly/${MONTH%-*}/$MONTH/pageviews-${MONTH/-/}-user.bz2"
curl -sL "$URL" | bzcat | awk '$1=="en.wikipedia" && $3 ~ /^[0-9]+$/ {v[$3]+=$5} END{for(k in v) print k"\t"v[k]}' > pageviews_en.tsv.tmp
mv pageviews_en.tsv.tmp pageviews_en.tsv
echo "pages: $(wc -l < pageviews_en.tsv)"
