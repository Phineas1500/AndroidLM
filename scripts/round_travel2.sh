#!/usr/bin/env bash
# Travel questions (16 new + the 4 original) after the retrieval batch: Wikivoyage on, with
# the normal router and then with travel questions routed retrieval-first.
set -uo pipefail
cd "${ANDROIDLM_ROOT:-$HOME/androidlm}"
M=q2kxl=models/Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf
cat eval/questions_travel.jsonl > eval/questions_travel_all.jsonl
grep '"cat":"travel"' eval/questions_v0.jsonl >> eval/questions_travel_all.jsonl
export RAG_DB=corpus/wiki.db RAG_MODE=auto VOYAGE_DB=corpus/voyage.db QUESTIONS=$PWD/eval/questions_travel_all.jsonl
RUN_SUFFIX=_trv2_router ./scripts/eval_models.sh $M > logs/trv2_router.log 2>&1
TRAVEL_ROUTE=1 RUN_SUFFIX=_trv2_travelroute ./scripts/eval_models.sh $M > logs/trv2_travelroute.log 2>&1
echo done > logs/trv2_done
