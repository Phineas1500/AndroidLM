# Speed levers on the Pixel 8 Pro (2026-09-24)

Question: are we using the phone's full potential (cores, RAM, GPU, TPU)? Each lever measured on
the phone, one run at a time, each run started at a skin temperature of 29-30 C.

Engine runs use the app's flags (`-t 5 -c 4096 --ubatch 512 --session --moe-stream --cache-mb 2000
--io-threads 2 --overlap --dense-weights ahwb`, compute threads pinned to cpus 4-8) from an adb
shell. Requests: the Dead Sea draft (110-token prompt, 448 tokens generated) and a real
retrieval-first answer prompt (1,216 tokens).

## Results

| Lever | Setting | Result | Verdict |
|---|---|---|---|
| Slow cores for prompt reading | separate 9-thread pool on all cores | 97.6 s vs 94.1 s | slower |
| | 7 threads (fast cores + 2 slow) | 99.2 s vs 94.1 s | slower |
| GPU (Mali-G715, llama.cpp Vulkan) | Qwen3.5-4B Q4_K_M, all layers on GPU | prompt 9.1 tok/s vs 30.5 on CPU; generation 6.6 vs 7.8 | slower |
| TPU | see below | not reachable for our model | no |
| N-gram self-speculation | `--ngram --draft 3`, 200 tokens after the 1,216-token prompt | 3.51 vs 3.92 tok/s; output not identical | slower |
| Whole prompt in one chunk | `--ubatch 1280` | 88.4 s vs 94.1 s; flash read 9.1GB vs 22.0GB | small gain |
| Bigger expert cache | 3000 MiB | 5.05 vs 4.64 tok/s (hit 87.7% vs 78.8%) | faster |
| | 4000 MiB | 5.56 vs 4.64 tok/s (hit 91.8%) | faster |

Cache runs produced byte-identical text (the cache changes where weights come from, not the
arithmetic). Swap-out during each run was the same with a 2000, 3000 or 4000 MiB cache (about
530MB per run, other processes' pages), so the larger caches did not push the engine into zram.
This supersedes the 2026-09-23 note that caches above about 2500 MiB overcommit memory; those
measurements were taken while overlapping benchmark runs were still on the phone.

## The app decodes slower than the engine alone

The same Dead Sea draft: 4.64-4.71 tok/s from an adb shell, 3.49-3.65 tok/s inside the app, from
the same starting temperature. During an in-app run, four compute threads are pinned one per core
(cpus 4-7) and the engine's main thread (also a compute thread) floats over cpus 4-8, where the
app's UI and render threads run at nice -10 against the engine's 0. ggml's compute threads meet at
a barrier after every operation, so one preempted thread stalls all five.

Engine priority (`BMOE_NICE`, a new engine option; every engine thread inherits it), same question
in the app, each run from 29 C:

| In-app setting | Draft (448 tokens) | Source check (100 tokens after a 1,238-token prompt) |
|---|---|---|
| default (nice 0) | 3.49 tok/s | 2.54 tok/s |
| nice -10 (equal to the UI threads) | 3.74 | 2.78 |
| nice -16 | 3.85 | 2.91 |
| screen updates throttled to 4 per second, nice 0 | 3.56 | 2.55 |
| throttled and nice -16 | 3.95 | 2.85 |
| throttled, nice -16, cache 5000 MiB | 5.71 | 3.71 |
| throttled, nice -16, cache 6000 MiB | 5.90 | 3.91 |

Whole question (plan, draft, search, check) from sending to done: 340 s at the old defaults, 268 s
with the last two rows. Memory still available with the engine loaded after the question: about
1.0GB with a 5000 MiB cache, 0.23GB with 6000 MiB. The app now defaults to 5000 MiB on phones that
report at least 11 GiB of RAM (2000 MiB otherwise), runs the engine at nice -16, and updates the
screen at most four times a second while a research answer streams.
| engine alone from adb, for reference | 4.64-4.71 | |

Per token the service also parsed a JSON line, read a thermal zone, and pushed three state
updates, each of which recomposed the screen and re-rendered the whole growing answer as Markdown on
the UI thread. Throttling those to four per second is worth only about 2-3%; the priority is what
matters.

## TPU

The Tensor G3 TPU is reachable by third-party apps only through NNAPI (deprecated; no published
transformer performance on G3 worth using). Google's LiteRT NPU path officially supports only
Tensor G5/G6, needs a gated compiler whose licence forbids redistribution, and the TPU service
admits only allowlisted apps. Routed experts streamed from flash cannot run on it at all.

## The app in the background

With the app sent to the home screen after the first answer token (the foreground service keeps
running), the draft fell to 0.41 tok/s and search took 53 s. Android moves the app and the engine
process (which shares the app's process cgroup) from the `top-app` cpuset (cpus 0-8) to
`foreground` (cpus 0-7), and the move resets every thread's affinity to the new cpuset: the compute
threads spread over the little cores and each barrier waits for the slowest one. Re-pinning alone
was not enough: with the prime core (cpu 8) no longer allowed, five compute threads on four fast
cores put two spinning threads on one core, which stalls every barrier (still under 0.75 tok/s).

`patches/0004` records the pool's placement when it is created (workers are the threads that
appear during pool creation) and checks the main thread's affinity before every prefill chunk and
decode step (one syscall). If a thread has escaped the mask it re-pins the workers, and when the
cpuset allows fewer fast cores than there are compute threads it computes with one thread per
allowed fast core (`llama_set_n_threads`), parks the idle worker and the helper threads on the slow
cores, and restores the full placement when the app returns to the screen. While the threads stay
within the mask (on screen) it changes nothing.

## Final build (patches 0001-0004, app defaults), same question, cool start

| | Old defaults (morning) | Final build, on screen | Final build, sent to the home screen after the first token |
|---|---|---|---|
| First answer words | 24.5 s | 22.1 s | 22.0 s |
| Draft | 3.49 tok/s | 5.77 tok/s | 3.99 tok/s (0.41 before patch 0004) |
| Search | 19.6 s | 20.2 s | 51.8 s |
| Source check | 2.54 tok/s | 4.23 tok/s | 2.07 tok/s |
| Whole question | 340 s | 263 s | 375 s |

Big Motor (sources first, 654-token source prompt), final build on screen: first words at 82 s,
done at 105 s (130 s in the morning), same answer. Memory during an answer: engine 5.6GB resident
(including the 5000 MiB cache), pinned dense weights 2.05GB, app 0.15GB; 1.1GB still available.

Runs started between 29 and 30.5 C skin temperature; on this phone a 1.5 C warmer start costs up
to about 15% of decode speed, which is the size of the run-to-run differences above.
