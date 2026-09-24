# Pixel 8 Pro, first day (2026-09-23)

Device: Pixel 8 Pro, Android 16, 11.85GB RAM, 102GB free; cores 0-3 A510 1.7GHz, 4-7 A715
2.37GHz, 8 X3 2.9GHz; i8mm present; USB 2.0 to the Mac mini. All three assets installed with
`scripts/install.sh` (after fixing a loop bug), the dev app installed, `DeviceGoldenTest` passed
(728 comparisons, 6 s), and the app ran a full research question end to end (about 9 minutes).

## The engine was 7-8x slower than plain llama.cpp on this phone

| Run (engine CLI over adb, UD-Q2_K_XL) | Decode | Compute/token |
|---|---|---|
| unpatched, cache 5000, 4 threads | 0.86 tok/s | 1.09 s |
| unpatched, cache 2000-3000, 4 threads | 0.69-0.84 | 1.2-1.3 s |
| unpatched, 8 threads | 0.19 | 5.2 s |
| unpatched, pinned to big cores | 0.28-0.44 | 2.1-3.4 s |
| plain llama-bench, dense Qwen3.5-4B Q4_K_M, 4 threads | 7.4 tok/s decode, 23 tok/s prefill | |

simpleperf on the engine: 60% of cycles in `ggml_graph_compute_thread` (barrier spin) and hundreds
of short-lived worker threads per token. Cause: the engine attached no ggml thread pool, so ggml
created a disposable pool (thread create + join) per graph compute, and its eval callback splits
each token into many computes. Cheap on a server, ruinous on Android's scheduler.

## Fix and tuning (clean runs, nothing else on the phone)

`patches/0001-bigmoeonedge-persistent-threadpool.patch`: one persistent `ggml_threadpool` per
session attached to the context; `BMOE_CPUMASK=<hex>` pins the compute threads.

| Patched engine, cache 2000, 4 threads, 2 lanes | Decode | Compute | Flash I/O |
|---|---|---|---|
| dense anon, unpinned | 0.94 tok/s | | |
| dense ahwb (pinned dma-buf), unpinned | 1.31 | | |
| dense ahwb + compute threads pinned to cpus 4-7 | **3.93** | 0.196 s | 0.140 s (573 MiB/s) |

Pinning compute to the big cluster also freed the I/O lanes: flash throughput tripled.

Memory: the engine gets about 5GB on this phone before the kernel swaps its own pages to zram
(kswapd prefers anonymous memory over file cache; only dma-buf allocations are exempt). Caches
above about 2500 MiB, or a 3000 cache with pinned dense weights, overcommit it.

Two bench mishaps to avoid: quitting a `screen` session does not stop an `adb shell` it already
launched, and several matrices overlapped as a result (those results were discarded); the phone
must be checked idle by process list before every measurement.

## Tuning after the fix (clean runs, patched engine, cache 2000, dense ahwb, 2 lanes)

| Compute threads | Decode |
|---|---|
| 3 on cpus 4-6 | 3.07 tok/s |
| 4 on cpus 4-7 (A715) | 3.81-3.93 |
| **5 on cpus 4-8 (A715 + X3)** | **4.41** |
| 4, 6 active experts (lossy) | 3.97 |
| 4, cache 1500 / 2500 | 3.15 / 3.81 |
| 4, 4 lanes | 2.99 |

The app now pins its compute threads itself (`CpuTopology`: every core above the little cluster,
passed as `BMOE_CPUMASK`), defaults to Auto threads (5 here), pinned dense weights, 2 lanes and
no expert dropping.

## Long prompts (the real ~1,216-token research prompt, session mode)

| Build, 5 threads on cpus 4-8 | Prompt read | Decode after |
|---|---|---|
| current (dotprod) | 147 s (8.3 tok/s) | 3.8 tok/s |
| i8mm | 123 s (9.9 tok/s) | 2.7 tok/s |

Prompt reading is compute-bound (CPU busy throughout; batch size 256 vs 512 and 2 vs 4 lanes made
no difference; a warm cache did not help because a long prompt touches far more experts than
2GB holds). The i8mm build reads prompts 16% faster but decodes 28% slower, which is a net loss
for a full research question, so the app keeps the current build.

## First end-to-end runs in the app (2026-09-24, Pixel 8 Pro, `scripts/app_timing.sh`)

Times from the moment the model was loaded (a cold app start adds about 28 s of model load).
Decode in the app ran at 3.1-3.6 tok/s.

| Question | Route | Plan | First word | Draft/answer done | Search | Check read / done | Total |
|---|---|---|---|---|---|---|---|
| 1983 Harrods bombing | retrieval-first | ~20 s | 151 s | 192 s | (in plan) | none | 192 s |
| Dead Sea salinity and level | answer-first | 17 s | 30 s | 156 s (448 tok) | 23 s | 1,710 tok, 173 s / 383 s | 383 s |
| Altitude in Leh, Ladakh | answer-first | 21 s | 35 s | 200 s (549 tok) | 21 s | 1,634 tok, 170 s / 420 s | 420 s |

All three answers were accurate; the source checks added correct sourced specifics (Dead Sea
34.2% salinity, 439.78 m below sea level, Jordan flow cut to about 2%; Leh at 3,524 m and the
acetazolamide dose) and made no false corrections.

Where the time goes: the source check's prompt reading (about 170 s, because its prompt repeats
the 450-550-token draft on top of about 1,000 tokens of sources), the draft's decode (140-180 s),
and search, which takes 21-23 s on the phone against about 3 s on the server.
