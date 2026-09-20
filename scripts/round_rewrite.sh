#!/usr/bin/env bash
# Experimental draft-then-rewrite mode over every eval question (32 head, 24 long-tail, 20 travel).
set -uo pipefail
cd "${ANDROIDLM_ROOT:-$HOME/androidlm}"
M=q2kxl=models/Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf
cat eval/questions_all.jsonl eval/questions_travel.jsonl > eval/questions_everything.jsonl
export RAG_DB=corpus/wiki.db RAG_MODE=auto VOYAGE_DB=corpus/voyage.db REWRITE=1 QUESTIONS=$PWD/eval/questions_everything.jsonl
RUN_SUFFIX=_rewrite ./scripts/eval_models.sh $M > logs/rewrite.log 2>&1
# same retrieval with the ordinary source check, as the like-for-like baseline (travel already has one)
unset REWRITE
QUESTIONS=$PWD/eval/questions_all.jsonl RUN_SUFFIX=_batchcheck ./scripts/eval_models.sh $M > logs/batchcheck.log 2>&1
echo done > logs/rewrite_done
