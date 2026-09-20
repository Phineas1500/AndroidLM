#!/usr/bin/env bash
# Round three: long-tail closed-book, then verify and plan modes over head + tail questions.
set -uo pipefail
cd "${ANDROIDLM_ROOT:-$HOME/androidlm}"
M=q2kxl=models/Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf
export RAG_DB=corpus/wiki.db
QUESTIONS=$PWD/eval/questions_tail.jsonl RAG_MODE=none   RUN_SUFFIX=_tail ./scripts/eval_models.sh $M > logs/r3_none_tail.log 2>&1
QUESTIONS=$PWD/eval/questions_all.jsonl  RAG_MODE=verify RUN_SUFFIX=_r3   ./scripts/eval_models.sh $M > logs/r3_verify.log 2>&1
QUESTIONS=$PWD/eval/questions_all.jsonl  RAG_MODE=plan   RUN_SUFFIX=_r3   ./scripts/eval_models.sh $M > logs/r3_plan.log 2>&1
echo ROUND3_DONE > logs/r3_done
