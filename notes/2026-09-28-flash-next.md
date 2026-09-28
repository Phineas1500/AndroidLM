# Qwen3.8-Flash-Next on the Pixel 8 Pro (2026-09-28)

Qwen3.8-Flash-Next (125B total, about 6B active per token, 10 of 512 experts, plus a 51B n-gram
lookup table) is a newer and larger model than Qwen3.6-35B-A3B (3B active). The smallest file,
ISTA-DASLab's GSQ-RCO Q2_0, is 66.4GB: 37.6GB of weights and the 28.8GB table, which the engine
keeps memory-mapped (a few KB read per token). Our engine (BigMoeOnEdge v0.24.0 on llama.cpp
b10666) already has the `qwen4exp` architecture and the Q2_0 type, so it ran unchanged. It does
not fit the bounty's 50GB total; this was a test of what it would buy.

## Speed (engine alone over adb, the app's settings, `eval/bench_engine_*`)

Same four prompts, 120 tokens written each, from a cool start. Flash-Next with a 2,000 MiB expert
cache (the engine refuses less than 1,500 without forcing; free memory fell to 1.05GB at the
lowest, as with the current model's 5,000 MiB):

| Prompt | Current model: read | Flash-Next: read | Current: write | Flash-Next: write |
|---|---|---|---|---|
| plan, 131 tokens | 7.0 s | 36.7 s | 5.6-6.2 tok/s | 1.48 tok/s |
| places, 426 | 15.4-15.9 s | 137.4 s | 5.7 | 1.20 |
| check, 719 | 24.4-25.2 s | 261.4 s | 5.5-5.8 | 1.18 |
| sources-first answer, 1,233 | 40-49 s | 451.4 s | 4.6-5.7 | 1.19 |

Load 50 s against 20 s. Prompt reading is compute-bound (for the 131-token prompt, 171 CPU-seconds
on five cores): Q2_0 has no optimised ARM kernel in the engine (the ported ik kernels cover the
current model's types). Writing waits on flash: 1.0-1.1 s of I/O per token against 0.45-0.57 s of
compute, with only 15-35% of the experts found in the cache; UFS 3.1 is slower than the UFS 4 of
the phone the engine's author measured 2-3.5 tok/s on. In the app an answer-first question would
show its answer after about 6 minutes and finish its check after about 11 (about 50 s and 100 s
now).

## Accuracy (`eval/*flashnext_12*`)

Closed-book answers (the app's CLOSED_SYSTEM prompt, greedy, up to 600 tokens) to 12 red-team
questions: 10 where the current model's answer had an error its source check left standing, and 2
it answered correctly. Graded blind against the current model's answers:

| | Current model | Flash-Next |
|---|---|---|
| Factual errors | 31 | 8 |
| Mean score (0-10) | 5.3 | 7.4 |
| Preferred | 4 | 8 |

Flash-Next had none of the current model's errors on Kazakhstan's capital, the World Cup (the
current model even named the wrong winner), Shor's algorithm and elliptic curves, Argentina's
plugs or Monero, one where it had four (Georgia) and two where it had four to six (Verkle trees,
the Lisbon metro); it was worse on altitude sickness (acetazolamide "over the counter") and no
better on proposer-builder separation. Both answered the two controls correctly. Its answers are
about half as long (median about 210 tokens against 400). The ten questions were chosen for the
current model's errors, so this overstates the gap on questions in general; the direction is
clear all the same.

## Decision

Not the app's model on a 12GB phone with UFS 3.1: four to five times slower writing and about nine
times slower prompt reading. What would narrow the gap: an ARM kernel for Q2_0 (prompt reading is
compute-bound), fewer active experts (`--n-expert-used`, less I/O and compute per token, at a
quality cost to measure), and faster storage.
