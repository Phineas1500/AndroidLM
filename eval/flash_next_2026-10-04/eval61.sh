#!/usr/bin/env bash
# Flash-Next (10 experts) in the app on the 61 bounty-style questions, as 1.2.1 was asked on 10/01:
# 58 by phone_eval.sh, then the three "near me" questions with a test GPS position.
source ~/androidlm-tools/env.sh
S=scratchpad
until grep -q AB5_DONE $S/fn/ab5.log 2>/dev/null; do sleep 30; done
PHONE_EVAL_TIMEOUT=2400 PHONE_EVAL_GAP=120 bash $(dirname "$0")/../../scripts/phone_eval.sh \
  $S/fn/questions_vitalik_58.jsonl /Volumes/T7/AndroidLM-dev/work/eval-fn-vitalik
bash $S/fn/located_fn.sh
echo EVAL61_DONE
