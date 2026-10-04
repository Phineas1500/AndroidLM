# Qwen3.8-Flash-Next as a second, slower model (2026-10-03/04)

On 2026-09-28 Qwen3.8-Flash-Next ran on the Pixel 8 Pro but was 5-9 times slower than Qwen3.6
([2026-09-28-flash-next.md](2026-09-28-flash-next.md)). This round makes it an option in the app for
people who want the better model and can wait. Qwen3.6-35B-A3B stays the main model. Flash-Next
(66.4GB with its 28.8GB n-gram table) is over the bounty's 50GB, so it is an extra, not the
submission.

## On the phone

- **Storage:** the phone has 110GB. The two models and the Wikipedia, places and travel files
  together need about 112GB, so on this phone only one model fits at a time; a 256GB phone holds
  both.
- **Choosing a model:** the app already lists every MoE model in `/data/local/tmp/bmoe` (or
  imported) and shows a picker when there are two.
- **Its memory:** its dense weights are 3.1GB against Qwen3.6's 2GB, and it reserves a 1.3GB
  compute buffer. The app therefore caps its expert cache at 2,000 MiB (`ModelProfile.kt`, keyed
  on the model's architecture, `qwen4exp`). Qwen3.6 keeps 5,000 MiB.

## Engine: four kernels for its weight types

Flash-Next stores its experts as Q2_0 (64 weights per block, codes for -1, 0, 1, 2), its
attention and shared experts partly as Q3_K, IQ4_XS and Q2_0, and its hyper-connection and router
matrices (1.4GB) as BF16. llama.cpp has batched ARM kernels for none of these, and ran BF16 one
element at a time. `patches/llama.cpp/0003-ggml-cpu-iqk-flash-next.patch` adds:

1. **Q2_0 experts, several tokens:** each group of 8 rows unpacked once to int8 and multiplied
   with the integer arithmetic of ggml's own Q2_0 dot product (`mul_mat_q2_0_u_r8_q8_0`).
2. **BF16 dot product:** NEON fp32 instead of the scalar loop (`vec.cpp`).
3. **Dense Q3_K, IQ4_XS, Q2_0 and BF16 for prompts:** rows unpacked once per prompt instead of
   decoded once per token (a hook in `ggml_compute_forward_mul_mat`). Q3_K and IQ4_XS go into the
   Q6_K kernel of patch 0002, whose formula they share. BF16 gets an fp32 kernel.
4. **Q2_0 experts, one to three tokens (writing):** the codes are read in place, four rows at a
   time, against activations rearranged once.

**Checks:** `test-iqk-q2.cpp`, `test-iqk-bf16.cpp`, `test-iqk-dense2.cpp` (Mac M4):
- Q3_K and IQ4_XS results are bit-identical to ggml's own dot products;
- Q2_0 and BF16 differ from them at float rounding (1e-6 and 1e-5 of the output).

Qwen3.6 has none of these weight types, so the engine computes it exactly as 1.3.1 does.

**On the whole model** (Mac, `llama-perplexity`, 16 x 512 tokens of these notes, KL divergence of
the next-token distributions against the engine before kernels 3 and 4):

| Change | Mean KLD | Same top token | Perplexity |
|---|---|---|---|
| Rounding only: kernel 1 off (ggml's own Q2_0 dot product) | 0.0225 | 92.2% | -0.4% |
| Kernels 3 and 4 | 0.0224 | 91.9% | -0.5% |

The kernels sit at the model's rounding floor.

**Speed on the phone** (engine alone, the app's flags, from 32 C):

| Engine | 426-token places prompt | Writing after it | 1,216-token prompt |
|---|---|---|---|
| 1.3.1 (as shipped) | 141.3 s | 1.04 tok/s | about 450 s (9/28) |
| + 1, 2 | 69.7 / 71.0 s | 1.33 / 1.30 | 209.6 s |
| + 3 | 41.9 / 40.2 s | 1.41 / 1.44 | 111.4 s |

- The 32 written tokens were identical across engines.
- **Kernel 4** cut the compute of writing by 7% (0.36-0.37 against 0.39 s per token), with tokens
  per second unchanged within noise.
- **Writing a short closed-book answer** (64 tokens): 1.8-1.9 tok/s.

**Where writing goes now** (simpleperf, 64 tokens):
- the 2-bit experts' dot products 21%, threads waiting on each other 26%, BF16 13%, Q3_K/IQ4_XS
  11%;
- per token it waits about 0.1 s for flash and computes about 0.36 s, against a memory-bandwidth
  floor of about 0.14 s for its 3.1GB of dense weights and 0.6GB of experts.

**Cache and compute buffer:**
- a 2,800 MiB cache with a 512-token compute buffer wrote a short answer 8% faster (2.02 against
  1.82-1.88 tok/s);
- but it read the 1,216-token prompt 24% slower (138 against 111 s: 56GB of experts read instead
  of 25GB), and free memory fell to 621 MiB;
- 3,300 MiB added 1.6% and left 875 MiB free;
- the defaults stay: 2,000 MiB and 1,280 tokens, with free memory at about 2GB at the lowest.

## Fewer experts per token

Flash-Next routes 10 of 512 experts per token. The app's "Active experts" setting can lower that;
it now offers 8.

**KL divergence** of the next-token distributions against 10 experts (Mac, `llama-perplexity`,
16 x 512 tokens of these notes; base perplexity 11.24):

| Experts | Mean KLD | Same top token | Perplexity |
|---|---|---|---|
| 10, generic Q2_0 kernel (rounding only) | 0.022 | 92.2% | -0.4% |
| 8 | 0.101 | 85.1% | +1.6% |
| 6 | 0.275 | 76.0% | +13.3% |
| 4 | 0.612 | 65.8% | +36.0% |

**Speed on the phone** (same session, 426-token places prompt):

| Experts | Prompt | Writing |
|---|---|---|
| 10 | 40.1 s | 1.57 tok/s |
| 8 | 36.2 s | 1.71 tok/s (+9%) |
| 6 | 32.5 s | 1.83 tok/s (+16%) |

Fewer experts buy little: per token the dense weights, not the experts, take most of the time.

**Answers with fewer experts:**
- **The questions:** the 12 red-team questions of 2026-09-28, answered closed-book on the phone
  with the app's prompt (greedy, up to 600 tokens), with 10, 8 and 6 experts.
- **Grading:** one blind grader saw the three next to Qwen3.6's answers of 9/28, in a random order
  per question, and could check facts on the web (`eval/sets_flashnext_experts_12.json`, key and
  grades alongside, answers in `eval/answers_phone_flashnext_k*_12.jsonl`).

| Answers | Mean /10 | Factual errors | Best of the four |
|---|---|---|---|
| Flash-Next, 10 experts | 7.00 | 8 | 1 |
| Flash-Next, 8 experts | 7.08 | 8 | 3 |
| Flash-Next, 6 experts | 6.92 | 5 | 3 |
| Qwen3.6 | 5.00 | 33 | 1 |
| Tie | | | 4 |

- **Per question,** the three Flash-Next settings are within one or two points of each other, with
  no direction.
- **Shorter answers with fewer experts:** 2,126, 1,764 and 1,441 tokens in all.
- **Twelve questions cannot show a loss of a few percent,** and the perplexity numbers show one at
  6 experts (+13%).
- **Reading:** 8 experts is close to free in quality and gains 9%; 6 is a gamble for 16%.
- **The 12 were chosen for Qwen3.6's errors,** so its share here understates it on questions in
  general.
- **No answer knew the 2026 World Cup winner** (Spain, 19 July 2026, as the grader found). Both
  models' knowledge ends before it.

## In the app

The test APK (engine with the four kernels, the cache cap) on the phone with only Flash-Next
installed; one question per cold start, as the evaluations ask them:

| Question | Route | First words | Done |
|---|---|---|---|
| Tell me the best vegan restaurants in Lisbon | places, 775-token prompt | 72 s | 519 s (596 tokens) |
| What is the maximum effective balance of an Ethereum validator after EIP-7251? | Ethereum library sources, 1,143 tokens | 166 s | 301 s |
| How does Shor's algorithm threaten elliptic curve cryptography? | Ethereum library sources, 1,094 tokens | 162 s | 297 s |

- **Times include a cold start:** loading the model takes about 52 s, and planning another 38 s
  (a 124-131-token prompt and 18-19 words written at 1.4 tok/s).
- **The answers:** all three are correct and cited. The restaurant answer names eight places from
  the list with street, cuisine and hours, plus the note that map data can be out of date.

## The 61 bounty-style questions: Flash-Next in the app against Qwen3.6

**The run:**
- The 61 questions of [2026-10-01-vitalik-bar-121.md](2026-10-01-vitalik-bar-121.md), asked in the
  app with Flash-Next (10 experts), one per cold start.
- The three "near me" questions had a test GPS position (`eval/flash_next_2026-10-04/`; answers in
  `eval/answers_phone_flashnext_vitalik.jsonl`).
- The phone stayed on its charger. For the first 22 questions it started each one at 39-41 C. From
  the 23rd it sat on a fan cooling pad and started at about 30.5 C.
- All 61 finished.

**Grading:** in one sitting, as on 10/01. One blind grader per group saw three answers in a random
order: Qwen3.6's from 1.2.1 (10/01), Flash-Next's, and the web search + frontier AI reference. They
could check facts on the web (`eval/sets_flashnext_vitalik_*.json`, keys and grades alongside).

| Group | Flash-Next | Qwen3.6 (1.2.1) | Flash-Next per question | Errors Flash-Next / Qwen3.6 / reference |
|---|---|---|---|---|
| Restaurants (20) | 60% | 58% | better on 9, worse on 4 | 42 / 46 / 12 |
| Crypto (20) | 56% | 57% | better on 8, worse on 7 | 8 / 7 / 0 |
| Travel, emergencies, arithmetic (21) | 59% | 69% | better on 5, worse on 11 | 25 / 26 / 2 |
| **All 61** | **58%** | **62%** | | 75 / 79 / 14 |

The reference was the best answer on all 61.

**In the app, Flash-Next is not better than Qwen3.6:**
- **Restaurants and crypto:** a draw. These answers come from the places list and the Ethereum
  library, and both models use them about as well.
- **Emergencies:** Flash-Next writes half as much (a median of 1,161 against 2,507 characters), and
  the grader marked the steps it left out (ice and alcohol warnings for a snakebite, insulation from
  the ground for hypothermia, what to do during an earthquake).
- **Travel:** it states wrong specifics with confidence: Thailand's emergency numbers, a Thai
  licence for tourists, and Mexico City's tap water as "generally safe to drink" (Qwen3.6 says not
  to drink it).
- **The source check** often found nothing in the sources to correct with, so these errors stayed.
- **Why the 12 questions disagree:** they favoured Flash-Next by 7.0 to 5.0 because they were
  chosen for Qwen3.6's errors.
- **The prompts were tuned on Qwen3.6:** the app's prompts, lengths and checks were developed with
  it.
- **Grader variance:** Qwen3.6's 1.2.1 answers came to 62% here and 65% on 10/01, with other
  graders.

**Speed** (medians, from a cold start; "hot" = 39-41 C at the start, "pad" = 30.5 C):

| Group | First words | Done | Qwen3.6 on 10/01: first words / done |
|---|---|---|---|
| Restaurants (17 hot, 3 pad) | 88 s hot, 54 s pad | 485 s hot, 373 s pad | 30 s / 127 s |
| Crypto (6 hot, 14 pad) | 175 s hot, 132 s pad | 481 s hot, 256 s pad | 63 s / 122 s |
| Travel (pad) | 44 s | 276 s | 18 s / 103 s |
| Emergencies (pad) | 45 s | 251 s | 20 s / 181 s |
| Arithmetic (pad) | 48 s | 263 s | 41 s / 107 s |

- **Overall:** first words after a median 89 s and done after 5.4 minutes, about 2.5 times
  Qwen3.6.
- **The cooling pad:** writing went from 1.15-1.24 tok/s to 1.47-1.53.

## What to make of it

- **Running it:** Flash-Next runs in the app on a 12GB phone, at 2-4 times the original speed of
  9/28, and it is the better model from memory on hard questions.
- **Inside the app's research pipeline:** it does not beat Qwen3.6 (58% against 62% of the
  reference) and takes about 2.5 times as long. As things stand it is not worth offering as the
  better option.
- **What could change that:** prompts tuned for it (longer, more complete practical answers; a
  check that flags unsupported specifics). It would then need the same comparison again.
