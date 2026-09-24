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
