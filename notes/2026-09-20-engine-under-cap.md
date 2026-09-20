# Whole pipeline on the streaming engine under an 8.5GB cap (2026-09-20)

Six questions, `scripts/rag.py --mode auto --engine-cli bmoe-cli` (BigMoeOnEdge session mode,
UD-Q2_K_XL, 5,000 MiB expert cache, ctx 4096, ubatch 512, greedy), with the engine AND the
retrieval process inside one 8.5GB no-network sandbox on the Oracle VM. The VM's disk reads
about 105 MB/s, 10-20x slower than phone flash, so wall times here are far worse than a phone's;
the volumes read and the compute times are what carry over. Raw: `eval/answers_engine_cap.jsonl`.

| Step | Under the cap (VM) | Same step, whole model in RAM |
|---|---|---|
| Model load to ready | 170 s | n/a |
| Planning call (~150-token prompt) | 45-67 s | about 9 s |
| Search | 5-34 s | about 3 s |
| Closed-book draft | 99-143 s | about 63 s |
| Final call: prefill | 6.1-6.6 tok/s (935-1,594 tokens: 154-257 s) | 16.7 tok/s |
| Final call: decode | 3.1-4.7 tok/s | 7.8 tok/s |
| Whole question | 250-510 s | 95-165 s |

Engine counters for a 1,535-token prefill: 21.0 GB read from disk (about 14 MiB per prompt
token), 568 s of summed I/O time across 4 lanes against 233 s wall. Decode: compute 0.14 s/token,
I/O 0.57 s/token (0.18 s of it stalling compute), expert cache hit 81%, no major faults.

## Reading

- It works: the engine, a 5GB expert cache, and the 21GB corpus database coexist in 8.5GB with
  no OOM and no page-fault storm; answers are sane and cited.
- Under streaming, prefill is I/O-bound on slow storage: a 5GB cache cannot hold the ~10GB of
  experts a long prompt touches, so each 512-token batch re-reads most of them. About 14 MiB of
  reads per prompt token is the portable figure: at 1.5 GB/s (UFS 3.1) that is roughly 100
  prompt tokens/s of I/O headroom, so on a phone prefill should fall back to being compute-bound.
  This is a projection, not a measurement.
- Decode compute is unchanged (0.14 s/token); with phone-class flash the I/O share should shrink
  from 0.57 to a few hundredths of a second per token.
- Search is slow here only because the sandbox leaves well under 1GB of page cache for the
  index on a 105 MB/s disk.
- Every model call pays the prefill read volume again, including the short planning call, so
  the number of model calls per question matters more under streaming than it did in RAM.
  Levers if the phone confirms this: smaller source budget, a larger expert cache if RAM allows,
  ubatch tuning, or fewer calls (skip the plan call when the router is not needed).
