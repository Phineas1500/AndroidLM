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

## Less compression: the IQ3_XXS build (2026-10-04 evening)

The publisher's own numbers ([model page](https://huggingface.co/ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-GGUF)),
average of three reasoning benchmarks:
- full precision 93.1;
- our Q2_0 build (66.4GB) 89.1;
- their IQ3_XXS build (75.8GB) 92.6. It keeps 18 of 48 expert layers at 4 bits (IQ4_NL), mixes 2-
  and 3-bit formats in the rest, and keeps the attention weights at 5-6 bits instead of 3.

The n-gram table is byte-identical in every build, so only the 47.0GB first part was downloaded.

**On the phone:**
- **Storage:** the IQ3_XXS build plus the full corpus leaves 3.0GB free on the 110GB phone. This
  took removing the 14 demo takes (all on the Mac) and AICore's on-device model data.
- **A bug it found:** with the engine's dense repacking on, llama.cpp moved IQ4_NL experts into its
  CPU_REPACK buffer despite the engine's override to the plain CPU buffer, about 8GB of RAM. The
  phone ran out of memory and restarted (boot reason: kernel panic on OOM).
  - **Fix:** `patches/llama.cpp/0004` adds `LLAMA_OVERRIDE_CPU_PLAIN`, which keeps such overrides
    on the plain CPU buffer, and `patches/0008` makes the engine set it whenever it repacks.
    Streamed experts now always stay in the file mapping.
- **Prompt kernels:** IQ4_NL, IQ3_S, IQ2_XXS and IQ2_S experts get an fp32 path for prompts in patch
  0003. Each group of rows is decoded once by ggml's own to_float and multiplied in fp32;
  `test-iqk-f32.cpp` matches ggml's dot products to 1e-5 of the output.
- **Speed:** about 15% slower than the Q2_0 build. A 64-token answer was written at 1.60 against
  1.82-1.95 tok/s; in the app, 1.2-1.3 tok/s with the cooling pad, against about 1.5. Loading
  takes 66 s against 52 s.

**Perplexity** (Mac, same text): 10.71 against the Q2_0 build's 11.23 (-4.7% +/- 1.8%). The two builds
pick the same top token 71% of the time (KLD 0.48 between them).

**The 21 practical questions,** same app, prompts and cooling pad as the Q2_0 run, graded four-way
in one sitting by one blind grader (`eval/sets_flashnext_iq3xxs_other.json`, key and grades
alongside):

| Answers | Share of the reference | Errors | Travel / emergencies / arithmetic |
|---|---|---|---|
| **Flash-Next IQ3_XXS** | **69%** | 21 | 65% / 71% / 75% |
| Qwen3.6 (1.2.1) | 66% | 24 | 59% / 71% / 75% |
| Flash-Next Q2_0 | 57% | 24 | 49% / 51% / 75% |

- **IQ3_XXS against Q2_0:** better on 14, worse on 3.
- **IQ3_XXS against Qwen3.6:** better on 8, worse on 6.
- **The compression caused most of the Q2_0 build's losses,** the short answers included. Medians
  of the answer before its check:

| Group | IQ3_XXS | Q2_0 | Qwen3.6 |
|---|---|---|---|
| Emergencies | 1,619 characters | 606 | 1,652 |
| Travel | 967 | 674 | 770 |

- **It fixed the costly travel specifics:** Thailand's emergency numbers, Brazil's 127 V, Mexico
  City's tap water.
- **IQ3_XXS is level with or slightly ahead of Qwen3.6** on these questions. The 3-point margin is
  within one grader's noise.

## All 61 questions with the IQ3_XXS build (overnight, 2026-10-04/05)

**The run:**
- The other 40 questions (restaurants, crypto, the three "near me" with a test GPS position) in
  the app with the IQ3_XXS build, on the cooling pad, `places.db` back on the phone.
- All 61 finished (answers: `eval/answers_phone_flashnext_iq3xxs_vitalik.jsonl`).
- **Four launches got stuck** in the 61 IQ3_XXS runs (dng-002, food-008, cry-004, food-018): the app
  opened from adb with the question, started its engine, but never began the research run. The
  phone itself was fine.
  - Three re-asks worked. cry-004 got stuck again, so it and food-018 were finally asked with the
    app already open and its model loaded (`eval/flash_next_2026-10-04/iq3_warm_ask.sh`), which
    worked first time.
  - This looks like a race between the app's model preload and a question delivered at cold
    start. It is not understood yet; a person typing a question is unlikely to hit it.

**Grading:** four-way and blind, one grader per group, every answer next to the others for the same
question: IQ3_XXS, Q2_0, Qwen3.6 (1.2.1) and the web reference (`eval/sets_flashnext_iq3xxs_*.json`,
keys and grades alongside). Web search was out of quota for the graders, so they checked facts by
fetching pages (OpenStreetMap, HappyCow, restaurants' sites, eips.ethereum.org, NIST, CDC) and from
their own knowledge.

| Group | Flash-Next IQ3_XXS | Qwen3.6 | Flash-Next Q2_0 | IQ3_XXS against Qwen3.6, per question |
|---|---|---|---|---|
| Restaurants (20) | 72% | 68% | 71% | better on 10, worse on 3 |
| Crypto (20) | 60% | 55% | 54% | better on 9, worse on 1 |
| Travel, emergencies, arithmetic (21) | 69% | 66% | 57% | better on 8, worse on 6 |
| **All 61** | **67%** | **63%** | **60%** | **better on 27, worse on 10** |

- **Errors:** IQ3_XXS 41, Qwen3.6 48, Q2_0 44, reference 2.
- **The reference** was the best answer on all 61.
- **Qwen3.6's 63% here** matches its 62-65% in the earlier sittings with other graders.

**Speed** (medians, cooling pad, from a cold start; leaving out the unplugged run and the two warm
starts):

| Group | IQ3_XXS first words / done | Q2_0 on the pad | Qwen3.6 (10/01) |
|---|---|---|---|
| Restaurants | 106 s / 490 s | 54 s / 376 s | 30 s / 126 s |
| Crypto | 199 s / 494 s | 130 s / 259 s | 64 s / 121 s |
| Travel | 65 s / 424 s | 44 s / 276 s | 18 s / 103 s |
| Emergencies | 124 s / 416 s | 45 s / 249 s | 21 s / 177 s |
| Arithmetic | 75 s / 362 s | 48 s / 263 s | 41 s / 107 s |

- **Overall:** first words after a median 124 s and done after 7.8 minutes, about 3.5 times
  Qwen3.6.
- **Why so long:** it writes about 1.2-1.3 tok/s, and it writes longer, more complete answers.

## What to make of it (updated)

- **The compression was the problem.** In the app, the 2-bit Q2_0 build was behind Qwen3.6 (60%
  against 63% in this sitting). The 75.8GB IQ3_XXS build is ahead: 67%, better than Qwen3.6 on 27
  questions and worse on 10, with fewer factual errors.
- **It is ahead in every group,** most clearly in crypto (60% against 55%).
- **It costs time:** about 3.5 times as long per answer.
- **It also costs space:** 75.8GB, so on a 128GB phone it fits with the corpus only without
  Qwen3.6. It is over the bounty's 50GB, so it is an option, not the submission.
- **As a "slower, more thorough" second model,** it is now worth offering: the first configuration
  of this project to beat Qwen3.6 on the bounty-style questions.
