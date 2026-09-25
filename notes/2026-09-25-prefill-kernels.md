# Faster prompt reading: repacked dense weights, and what ik_llama.cpp shows (2026-09-25)

Prompt reading ("prefill") is the largest cost of a research question on the phone: 55-120 s for
the 650-1,250-token prompts of an answer or a source check. `notes/2026-09-24-prefill-research.md`
found why: the engine turned off llama.cpp's repacked CPU kernels, and computes the experts (IQ2_XS
and IQ3_XXS in this file) one row and one token at a time.

## Repacked dense weights (patches/0006, BMOE_REPACK=1)

llama.cpp may now repack the dense matmul weights it has interleaved kernels for (here Q5_K and
Q6_K: attention, the recurrent layers' projections, the shared experts, the output head) into its
CPU_REPACK buffer at load. The experts are pinned to the plain CPU buffer type
(`tensor_buft_overrides`), so they stay in the file mapping for the streamer; a repacked expert
fails the load. Repacked tensors leave the dense-weights policy (their bytes are no longer the
file's) and, under the pinned policy, are copied into pinned memory, the originals released.

First run on the Pixel 8 Pro (1,216-token research prompt, 200 tokens written, cache 5000 MiB,
dotprod build): 251 tensors, 1,238 MiB, all pinned; prompt read in 73.8 s (16.5 tok/s) against
88-94 s in earlier runs without repacking; writing 4.63 tok/s; coherent, cited answer.

Controlled comparison, same request, two runs of each configuration in the order A B C D D C B A,
every run started at 30.5 C:

| Build | Prompt read (1,216 tokens) | Writing (200 tokens) |
|---|---|---|
| A: dotprod | 93.8 s, 94.2 s (12.9 tok/s) | 4.56, 4.52 tok/s |
| B: dotprod + repack | 72.7 s, 72.6 s (16.7 tok/s) | 4.77, 4.70 |
| C: i8mm | 90.1 s, 90.0 s (13.5 tok/s) | 4.50, 4.53 |
| D: i8mm + repack | 71.9 s, 71.9 s (16.9 tok/s) | 4.61, 4.59 |

Repacking reads prompts 23% faster and writes about 4% faster. With it, the i8mm build adds 1% to
prompt reading and nothing to writing; the "i8mm writes 28% slower" of 2026-09-23 was one run of
each on a phone that was heating up, not the build. The app stays on the dotprod build, which
also runs on phones without i8mm, and turns repacking on by default (RunService.ENGINE_REPACK).

## ik_llama.cpp against mainline, same model file, same CPU (Oracle A1, Neoverse-N1, 4 threads, in RAM)

| | pp512 | pp1024 | tg32 |
|---|---|---|---|
| llama.cpp master (f805c57, 2026-09-25) | 16.8 tok/s | 16.5 | 8.3 |
| ik_llama.cpp (20f7a72, 2026-09-24) | 35.0 | 35.3 | 7.0 |
| ik_llama.cpp, run-time repack (not compatible with streaming) | 33.0 | | 10.1 |

ik_llama.cpp reads this model's prompts 2.1x faster on ARM cores without i8mm, from GEMM kernels
that convert IQ2_XS/IQ3_XXS rows to an 8-row interleaved Q8 layout on the fly for batches of 32
tokens or more. It writes 16% slower without its run-time repack, so the part worth porting is the
batched path only: prompt reading, with generation left on the current kernels.

## Porting ik_llama.cpp's prompt kernels

Its fast code is `ggml/src/iqk` (about 44,000 lines), reached from ggml's MUL_MAT and MUL_MAT_ID
through two C functions, `iqk_mul_mat` and `iqk_mul_mat_moe`. A port into the engine's llama.cpp
fork would vendor the GEMM parts for our types (IQ2_XS and IQ3_XXS experts, Q5_K/Q6_K dense) and
call them from mul_mat_id only for batches of 32 or more tokens per expert, after the engine's
expert-ready hook, leaving generation on the current kernels. Estimated 2-5 days with testing on
the phone; the measured ceiling on this file is about 2x prompt reading.
