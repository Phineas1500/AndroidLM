#!/usr/bin/env bash
# Travel questions in auto mode, without and then with the Wikivoyage corpus.
set -uo pipefail
cd "${ANDROIDLM_ROOT:-$HOME/androidlm}"
M=q2kxl=models/Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf
export RAG_DB=corpus/wiki.db RAG_MODE=auto QUESTIONS=$PWD/eval/questions_travel.jsonl
RUN_SUFFIX=_trv_off ./scripts/eval_models.sh $M > logs/trv_off.log 2>&1
VOYAGE_DB=corpus/voyage.db RUN_SUFFIX=_trv_on ./scripts/eval_models.sh $M > logs/trv_on.log 2>&1
echo done > logs/trv_done
