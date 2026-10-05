#!/usr/bin/env bash
# Overnight, after the 21 practical questions: the other 40 of the 61 with Flash-Next IQ3_XXS in the
# app (37 by phone_eval.sh, then the three "near me" with a test GPS position), and one more pass
# for any question that did not complete.
source ~/androidlm-tools/env.sh
S=scratchpad
until grep -q PLACES_BACK_DONE $S/fn/places_back.log 2>/dev/null; do sleep 30; done
grep -q 0323f51cb99b7b7bf9288a3558371978178f88b2f2afb372b66b6c6f469746d7 $S/fn/places_back.log || { echo "PLACES SHA MISMATCH, stopping"; exit 1; }
mkdir -p /Volumes/T7/AndroidLM-dev/work/eval-fn-iq3-rest
PHONE_EVAL_TIMEOUT=2400 PHONE_EVAL_GAP=120 bash $(dirname "$0")/../../scripts/phone_eval.sh $S/fn/q_iq3_rest37.jsonl /Volumes/T7/AndroidLM-dev/work/eval-fn-iq3-rest
bash $S/fn/located_iq3.sh
echo "== second pass for anything that did not complete"
PHONE_EVAL_TIMEOUT=2400 PHONE_EVAL_GAP=120 bash $(dirname "$0")/../../scripts/phone_eval.sh $S/fn/q_iq3_rest37.jsonl /Volumes/T7/AndroidLM-dev/work/eval-fn-iq3-rest
echo OVERNIGHT_DONE
