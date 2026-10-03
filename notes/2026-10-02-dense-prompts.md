# Prompts read 14% faster: dense weights unpacked once per prompt (2026-10-02)

Follow-up to [2026-10-02-speed-search.md](2026-10-02-speed-search.md), which found that the dense
weights take about 40% of reading a prompt. That note listed two candidates; here both are measured.

## 1. Speculation with the model's own MTP head: ruled out

Qwen3.6-35B-A3B has a multi-token-prediction layer (`mtp_num_hidden_layers = 1`). Our model file
leaves it out, so I took the layer-40 tensors from bartowski's `mtp-Qwen_Qwen3.6-35B-A3B-Q4_0.gguf`
(0.48GB, fetched by byte range) and appended them to our file with
`eval/speed_2026-10-02/dense/merge_mtp.py`:
- the merge sets `block_count` 41 and `nextn_predict_layers` 1;
- the original tensors are byte-identical, at the same offsets.

The engine's `--mtp --draft 1` then ran on the Pixel 8 Pro with the app's flags:

| | Without | With MTP |
|---|---|---|
| 300-token answer-first draft | 6.99 tok/s | 3.96 |
| 1,216-token sources answer, 200 written | 4.94 tok/s | 2.75 |
| Drafts accepted | | 1 of 250, 0 of 199 |
| Lowest free memory | 1,078 MiB | 430 MiB |

- **Acceptance was almost zero.** The grafted head does not work with this file; I did not find out why.
- **The cost alone rules it out.** Each verify step, the confirmed token plus one draft, cost 1.77x a
  plain token. At the head's usual 70-80% acceptance a step would yield 1.7-1.8 tokens for 1.77 tokens'
  time, at best even.
- **The engine's author measured a loss too,** on a 12GB phone with this model and a working head
  (BigMoeOnEdge `docs/mtp.md`).

## 2. Dense weights unpacked once per prompt: shipped

llama.cpp keeps the dense Q5_K and Q6_K weights repacked, 8 rows interleaved, for its 8x4 kernels.
For a prompt, those kernels decode every 5- and 6-bit weight once for every 4 tokens, and the profile
showed that unpacking, not the multiplies, is where their time goes.

The new path (`patches/llama.cpp/0002-ggml-cpu-iqk-dense-prompts.patch`) is taken from 32 tokens
on:
- **Unpacking:** each 8-row group is unpacked once per prompt into int8: the 5-bit integers 0..31
  (Q5_K) or the 6-bit integers minus 32 (Q6_K), in the 8-row order of ik_llama.cpp's Q8_K_R8, with
  the blocks' own integer scales and mins beside them.
- **Multiplying:** a kernel derived from ik's `mul_mat_q8_k_r8_q8_k` does the integer arithmetic of
  llama.cpp's own Q5_K and Q6_K dot products. For Q5_K that is
  `d*d8*sum(sc*dot(q, x)) - dmin*d8*sum(m*sum(x))`, with the activation sums Q8_K already carries.
  Nothing is quantized again.
- **What it does not touch:** writing (one token) keeps llama.cpp's repacked matrix-vector kernels.
- **Switches:** `GGML_IQK_DENSE=0` turns the path off; `GGML_IQK_DENSE_NY` sets tokens per kernel
  call (default 4).

**Correctness:**
- **Kernel test** (`test-iqk-dense.cpp`, Mac M4):
  - setup: random Q5_K/Q6_K rows, repacked with a copy of llama.cpp's own repack functions;
  - coverage: both interleaves, rows of 512 and 2048, 32/33/100 tokens;
  - result: the largest error is 3-8e-7 of the output RMS against a double-precision reference,
    the same as ggml's own `vec_dot`.
- **Whole model** (Mac, `llama-perplexity`, 16 chunks of 512 tokens of these notes, KL divergence of
  the next-token distributions against the path off):

| Change | Mean KLD | Same top token | PPL ratio |
|---|---|---|---|
| Path off, run twice | 0.000000 | 100% | 1.0003 |
| A rounding-only change: ik's expert kernels off | 0.0183 | 92.0% | 0.9991 |
| **This path** | **0.0175** | 92.1% | 1.0013 |
| A version that re-quantized the weights to 8 bits | 0.0204 | 92.2% | 1.0044 |

  - **Why the floor is high:** any rounding change moves this model's outputs that much (256 experts,
    8 chosen per token, and a recurrent state that carries a flipped choice forward).
  - **Reading:** this path sits at that floor.
- **Not kept:**
  - an 8-bit version (Q8_K_R8, one scale per 256 weights) read prompts in 27.7 s but sits above the
    floor;
  - per-32 scales halved its error but could not reach exactness.

**Speed on the phone:**
- Engine alone, the app's flags, the 1,216-token sources-first prompt, 8 tokens written.
- Runs started at 30.5-32 C.
- Today's shipped-engine runs measured 36.3-36.6 s.

| Engine | Prompt read | CPU time |
|---|---|---|
| Shipped | 36.3 s | 175.7 s |
| **This path** | **31.1-31.9 s (-14%)** | 150 s |
| This path with SMMLA (i8mm) kernels | 31.0-32.1 s | 149-153 s |

**i8mm:**
- **What it does:** SMMLA does twice the multiply-adds of SDOT per instruction.
- **The result:** it gained nothing here, apparently running at half SDOT's rate on the Cortex-X3/A715
  (and slower than SDOT on the Mac M4).
- **What ships:** the kernels are built in, run-time detected, and used only with
  `GGML_IQK_DENSE_I8MM=1`.
- **What it needs:** the build itself stays ARMv8.2 + dotprod, so older phones still run it.

**In the app:**
- **Setup:** 1.3.0 against the same app with this engine (`eval/speed_2026-10-02/dense/app_ab`).
- **Method:** one question per cold start, in the order A B B A. The restaurant runs started at 31 C
  (the last one at 32 C), the crypto runs at 32 C. The phone, charging at 100%, no longer cooled to
  30.5 C within half an hour.

| Question | Prompt | First words, 1.3.0 | First words, this engine |
|---|---|---|---|
| "Tell me the best vegan restaurants in Lisbon" | 775 tokens | 25.3, 25.9 s | **21.5, 21.5 s** (-4.1 s, -16%) |
| "Which signature algorithms are quantum resistant?" (the Ethereum library's sources) | 995 tokens | 47.5, 48.4 s | **40.8, 41.8 s** (-6.6 s, -14%) |

- **Repeatable:** each build wrote the same answer twice.
- **The wording differs between builds,** as with any rounding-level change:
  - restaurants: 543 tokens against 419, so that answer finished later (142 s against 108-121 s);
  - crypto: 194 against 226 tokens, so it finished sooner (86-87 s against 100 s).
- **Writing speed is unchanged:** single tokens never take this path.
