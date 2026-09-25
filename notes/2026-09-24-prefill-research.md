# Faster prompt reading on the Pixel 8 Pro: kernels, formats and experiments (2026-09-24)

This report covers four things: web research on 2025-2026 sources; a read of today's llama.cpp source (master
84e76d8, 2026-09-24) and of BigMoeOnEdge's `main`; GGUF headers read from Hugging Face; and CPU microbenchmarks
on the Mac M4 (ARM NEON, 4 threads) using upstream `test-backend-ops` with our model's shapes. No project files
were changed. The scratch artifacts (a `test-backend-ops` patch adding our shapes, raw benchmark outputs, a GGUF
header reader) were not added to the repository.

Labels: **[V]** means seen in the source or measured by me, with a URL or file given. **[I]** means my inference.

---

## TL;DR

1. **[V] UD-Q2_K_XL's routed experts are not Q2_K.** They are **IQ2_XS** (gate and up, 39 of 40 layers) and
   **IQ3_XXS** (down, 36 layers; IQ4_XS in the other 4).
   - Dense tensors are Q5_K (attn_qkv, attn_gate, attn_q, attn_output, shared gate and up) and Q6_K (ssm_out,
     attn_k and attn_v, shared down).
   - The grid-based IQ types are the slowest llama.cpp types on ARM CPUs.
2. **[V] BigMoeOnEdge loads with `use_extra_bufts = false` and mmap,** because repacked weights would break its
   pointer rebinding. So none of llama.cpp's ARM repack GEMMs and none of KleidiAI run for us.
   - Every matmul goes through per-row `vec_dot` kernels.
   - For experts (`MUL_MAT_ID`), upstream calls `vec_dot` with one row and one token at a time. Each
     IQ2_XS/IQ3_XXS weight is decoded again for every token.
   - M4 measurement [V]: IQ2_XS experts run at about 110 GFLOPS at 512 tokens and also at 1 token. Batching
     gives nothing.
3. **[I] Per-token time on the M4 splits about 54% routed experts and 45% dense Q5_K/Q6_K** (per-type
   rates × per-token multiply-adds from the GGUF header). Both halves have cheap fixes:
   - **Dense half:** upstream ARM repack GEMMs for Q5_K/Q6_K give 2-3x prompt processing on Exynos 2400,
     Raspberry Pi 5 and M4 [V]. We can turn them on for non-expert tensors only.
   - **Expert half:** Q2_K/Q3_K experts are 1.8-2.0x faster than IQ2_XS/IQ3_XXS in the same `vec_dot` path, at
     about 1.3 GB more file size [V, M4]. A real GEMM for IQ experts (the ik_llama.cpp approach, or a NEON port
     of upstream's new x86-only IQ panel GEMM) gives more but costs real work.
4. **[V+I] i8mm does not change the decode kernels.**
   - With the i8mm flag, one-token (`nrc == 1`) kernels compile to the same code. The flag only adds 2-row
     SMMLA paths for Q4_0, Q4_1, Q8_0, Q4_K and Q6_K, which run in prompt processing. It also turns off
     llamafile's tinyBLAS.
   - On the M4, a first sequential pass also showed a 13-36% "i8mm decode loss". It vanished when I re-ran the
     two builds alternately; it was heat. The phone's 28% decode loss was one run each on a phone whose later
     runs were slower. **Re-measure alternately before rejecting i8mm** (+16% prompt speed, measured).
5. **[V] State of the art:** ik_llama.cpp on a **Raspberry Pi 5** (4x A76) reads prompts of Qwen3.5-35B-A3B
   at **30.9 tok/s** (pp512), about 2.5-3x our 10-13 tok/s on a faster SoC.
   - On an Oracle A1 like ours it reaches 28.9 tok/s on Qwen3.6-35B-A3B UD-IQ4_XS, against 16.6 for mainline
     on UD-Q2_K_XL.
   - Mainline on a Pi 5 gets 10.9 (Qwen3-30B-A3B Q3_K_S), about where we are.
   - Nothing is published for Tensor G3/G4. The fast phone numbers (400-2,000 tok/s) all come from the
     Snapdragon Hexagon NPU, which we don't have.
6. **[I] Realistic target without writing kernels: about 2x (20-25 tok/s)** from four changes: i8mm, dense
   repack, K-quant experts, and top-6 experts for prompt tokens. With a NEON GEMM for experts, about 2.5-3x.
   Section 7 ranks the experiments.

---

## 1. What our prompt reading actually runs

### 1.1 Tensor types in `Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf` [V]

I read the header with a range request to the HF `resolve` URL (script: `gguf_types.py`). Parameters are in
millions.

| Tensor (per layer) | Types (layer count) | Params total |
|---|---|---|
| ffn_gate_exps, ffn_up_exps | IQ2_XS (39), IQ3_XXS (1) | 10,737 each |
| ffn_down_exps | IQ3_XXS (36), IQ4_XS (4) | 10,737 |
| attn_qkv (30 GDN layers) | Q5_K (29), Q6_K (1) | 503 |
| attn_gate (GDN) | Q5_K (29), Q6_K (1) | 252 |
| ssm_out (GDN) | Q6_K | 252 |
| attn_q / attn_output (10 attention layers) | Q5_K | 168 / 84 |
| attn_k, attn_v | Q6_K | 10.5 each |
| shared expert gate/up / down | Q5_K / Q6_K (one Q8_0) | 42 each |
| ffn_gate_inp (router), ssm_alpha/beta | F32 | 21 / 4 |
| output (lm_head) / token_embd | Q4_K / Q5_K | 509 each |

Hyperparameters: 40 layers, hidden 2048, 256 experts, 8 used, expert and shared FF 512, full attention every
4th layer.

**Every Unsloth UD quant of this model at 4 bits or less also has IQ-grid experts** [V, headers read]:

| File | Size | Expert types |
|---|---|---|
| UD-IQ2_M | 11.5 GB | IQ2_XXS gate/up, IQ3_XXS down |
| UD-IQ3_XXS | 13.2 GB | IQ2_S / IQ3_XXS |
| UD-Q3_K_S | 15.4 GB | IQ3_XXS / IQ3_S |
| UD-Q3_K_XL | 16.8 GB | IQ3_XXS / IQ4_XS |
| UD-IQ4_NL, UD-IQ4_XS | 18.0 / 17.7 GB | IQ3_S / IQ4_NL or IQ4_XS |

The exception is **MXFP4_MOE** (21.7 GB): MXFP4 gate/up and Q5_K down. The UD-Q3/IQ4 files carry Q8_0
attention.

Files with K-quant experts do exist [V, via sub-research, headers read]:

| File | Size | Expert types |
|---|---|---|
| bartowski Qwen3.6 Q2_K | 13.51 GB | Q2_K gate/up, Q2_K/Q3_K down |
| Intel AutoRound Qwen3.5-35B-A3B Q2_K_S | 12.51 GB | all Q2_K; attention Q4_K |
| bartowski Q4_0 | 20.84 GB | Q4_0 |

Unsloth's older Qwen3-30B-A3B-2507 UD-Q2_K_XL used Q2_K/Q3_K experts.

### 1.2 Work per prompt token [V, arithmetic from the header]

| Part | Multiply-adds per token |
|---|---|
| Routed experts (8 × 40 × 3 × 2048 × 512) | 1.01 B (gate+up 0.67 B, down 0.34 B) |
| Gated DeltaNet projections (30 layers × 33.7 M) | 1.01 B |
| Attention projections (10 × 27.3 M) | 0.27 B |
| Shared expert | 0.13 B |
| Router | 0.02 B |
| **Total** | **≈ 2.44 B** |

The lm_head (0.51 B) runs only for the last prompt token. **Dense weights are 59% of the multiply-adds of
prompt reading.** Speeding up only the experts can therefore at most about double prompt speed.

### 1.3 The kernel path in BigMoeOnEdge [V]

**Loading.** `core/src/engine/session.cpp` sets `mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
mparams.use_extra_bufts = false;` with the comment "Load with the layout the streamer requires: file-backed
mmap, no repack". `dense_weights.cpp` then rebinds dense `tensor->data` to private anon/AHWB copies of the file
bytes, and the streamer rebinds expert data to its cache slots.
[session.cpp](https://github.com/Helldez/BigMoeOnEdge/blob/main/core/src/engine/session.cpp),
[architecture.md](https://github.com/Helldez/BigMoeOnEdge/blob/main/docs/architecture.md). The llama.cpp
submodule is Helldez/llama.cpp @0e8c83e (2026-08-28, "expert-ready hook for mul_mat_id").

**Experts.** In upstream `ggml_compute_forward_mul_mat_id` (ggml-cpu.c), each chunk calls
`vec_dot(ne00, …, src1_col, 0, 1)`: always one row and one column (`nrc = 1`). The i8mm 2-row path and
llamafile are never used for experts.

**Dense weights.** Dense `MUL_MAT` uses `vec_dot` with `nrows = 2` (SMMLA) only for Q4_0, Q4_1, Q8_0, Q4_K and
Q6_K, and only in i8mm builds (ggml-cpu.c type traits). Q5_K, which covers about 1.06 B of our 1.43 B dense
multiply-adds, always takes the 1×1 path.

### 1.4 Microbenchmark: per-type throughput on ARM NEON (Mac M4) [V, measured today]

Setup:
- Upstream master 84e76d8; `-DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` (with and without
  `+i8mm`); Metal and BLAS off; 4 threads.
- `test-backend-ops perf` has no repack, so this is the same path BigMoeOnEdge runs.
- The MoE case is 64 experts with 2 used, m=512, k=2048. That gives the same tokens per expert as 256/8
  (n/32).
- Caveat: the M4 is in a fanless MacBook Air, and absolute numbers are M4 P-cores, not X3/A715. Use the
  *ratios*.

**Experts (`MUL_MAT_ID`), GFLOPS, dotprod build:**

| Type | 1 token | 512 tokens | 1,280 tokens |
|---|---|---|---|
| **IQ2_XS** (our gate/up) | 109 | **111** | 92 |
| **IQ3_XXS** (our down) | 77 | **75** | 62 |
| IQ4_XS | 197 | 205 | 207 |
| Q2_K | 190 | **197** | 197 |
| Q3_K | 149 | **147** | 146 |
| Q4_K | 239 | 258 | 261 |
| Q4_0 | 195 | 209 | 198 |
| IQ4_NL | 195 | 167 | 179 |
| MXFP4 | 194 | 163 | 185 |
| Q8_0 | 214 | 235 | 240 |

**Dense (`MUL_MAT` 8192×2048), GFLOPS, dotprod build → i8mm build.** Single sequential pass; the i8mm run came
second, while hotter.

| Type | 1 token | 512 tokens |
|---|---|---|
| Q5_K | 169 → 157 | 174 → 177 |
| Q6_K | 149 → 151 | **144 → 180** (2-row SMMLA) |
| Q4_K | 249 → 258 | 254 → 268 |
| Q4_0 | 197 → 198 | **295 → 221** (llamafile off under i8mm) |
| Q8_0 | 197 → 205 | **371 → 233** (llamafile off under i8mm) |

Reading [V]:
- Expert throughput is flat from 1 to 1,280 tokens, so prompt tokens cost the same compute as decode tokens.
- IQ2_XS and IQ3_XXS are the two slowest types. Q2_K/Q3_K run 1.8x/2.0x faster, and Q4_K is 2.3-3.4x faster.

[I] Time model per prompt token on the M4 (4 threads), from these rates:

| Part | Time |
|---|---|
| Experts (IQ2_XS 12.1 ms + down 8.4 ms) | 20.5 ms (54%) |
| Q5_K dense | 12.2 ms |
| Q6_K dense | 4.7 ms |
| F32 matmuls | ~0.5 ms |
| **Matmuls only** | **≈ 38 ms, about 26 tok/s** |

The down projection's k=512 shape was not measured separately; I assumed the same rate.

### 1.5 A gap worth checking first [I]

The phone reads prompts at 10-13 tok/s. Mainline `llama-bench` on the 4-core Neoverse-N1 VM reads the same
file at 16.6 tok/s, whole model in RAM (`notes/2026-09-19-oracle-baseline.md`). An X3 plus 4x A715 should not
be slower than 4 N1 cores. Some of the phone's time is probably not kernel time: flash I/O or stall during
prefill, per-expert hook synchronization, and heat over 100+ s of full load.

BigMoeOnEdge already reports `prefill_cpu_s / prefill_io_s / prefill_stall_s / prefill_mgmt_s` in `BMOE_DONE`
([CHANGELOG](https://github.com/Helldez/BigMoeOnEdge/blob/main/CHANGELOG.md), "Prefill-phase attribution").
Read those before optimizing kernels.

---

## 2. Q1: which CPU kernels and formats give the fastest MoE prompt processing on ARM

### 2.1 llama.cpp ARM "repack" buffer types, today [V: `ggml/src/ggml-cpu/repack.cpp` @84e76d8]

| Type | dotprod layout | i8mm layout | Notes |
|---|---|---|---|
| Q4_0 | 4x4 | 4x8 | 8x8 needs SVE-256 + i8mm or AVX2 |
| Q4_K | 8x4 | 8x8 | i8mm [#16739], dotprod [#17494] |
| Q5_K | 8x4 | 8x8 | i8mm [#18860] (merged 2026-01-23), dotprod [#19356] (2026-02-23) |
| Q6_K | 8x4 | 8x8 | i8mm [#18888] (2026-01-27), dotprod [#19360] (2026-02-10) |
| Q8_0 | 4x4 | 4x8 | [#18096] (2025-12-17) |
| IQ4_NL | 4x4 | (4x4) | no i8mm variant |
| MXFP4 | 4x4 | (4x4) | [#19738] (2026-02-27); benchmarked on AVX2 only |
| Q1_0 | 4x4 | 4x8 | 2026-09-21 |
| **Q2_K** | none on ARM | none on ARM | AVX-512 and RISC-V only |
| Q3_K, IQ2_*, IQ3_*, IQ4_XS | none | none | |

- **i8mm is a compile-time choice**: `ggml_cpu_has_matmul_int8()` returns `__ARM_FEATURE_MATMUL_INT8`.
- **`MUL_MAT_ID` is supported** for 3D expert tensors in a repack buffer. **But it runs one GEMV per routed
  token**: `forward_mul_mat_id` loops `for ir1 < cne1: gemv<…>(…, 1, …)`, with no GEMM. ggerganov's
  "repack : optimize mul_mat_id path" to use GEMM,
  [#14918](https://github.com/ggml-org/llama.cpp/pull/14918), has been open since 2025-07-28. So repacked
  formats help MoE prompt reading much less than dense. A third-party IQ4_XS SMMLA repack on Neoverse gets
  2.12x dense but **1.13x on OLMoE**
  ([Marc-Dvci/fastpath64](https://github.com/Marc-Dvci/fastpath64), 2026-07).
- **Repack happens at load, into a separate CPU_REPACK buffer.** The loader mmaps a tensor only when its buffer
  type is the default CPU type (`src/llama-model.cpp`, `is_default_buft`). A streamed or rebound weight cannot
  be in a repacked layout unless the engine writes repacked bytes. ggerganov, on #27402, 2026-08-24:
  "Repacking is just for load-time. It uses an extra buffer type and modifies the contents of the weight
  tensors."
- **Measured repack gains on phone-class ARM** (pp512, repack off → on):
  - Q5_K ([#18860](https://github.com/ggml-org/llama.cpp/pull/18860), [#19356](https://github.com/ggml-org/llama.cpp/pull/19356)):
    - Exynos 2400, i8mm, 3 threads: LFM2-1.2B 29.6 → 67.8 (2.29x).
    - Raspberry Pi 5, dotprod: LFM2-700M 47.1 → 89.7 (1.91x).
    - M4 as A76, dotprod: Qwen3-8B 35.2 → 70.0 (1.99x).
    - Token generation 0.97-1.15x.
  - Q6_K ([#18888](https://github.com/ggml-org/llama.cpp/pull/18888), [#19360](https://github.com/ggml-org/llama.cpp/pull/19360)):
    - Exynos 2400: LFM2-1.2B 28.8 → 70.4 (2.45x).
    - Pi 5: 39.9 → 105.8 (2.65x).
    - M4 as A76: Qwen3-8B 30.1 → 94.8 (3.15x).
    - Token generation 0.93-1.29x.
  - Q4_0 with i8mm repack on Snapdragon 8+ Gen 1 (X2/A710), 4 threads, Llama-3.2-3B
    ([issue #10662](https://github.com/ggml-org/llama.cpp/issues/10662), 2024-12): pp512 **55.8** against
    IQ4_NL 16.6 without dotprod.

[#16739]: https://github.com/ggml-org/llama.cpp/pull/16739
[#17494]: https://github.com/ggml-org/llama.cpp/pull/17494
[#18860]: https://github.com/ggml-org/llama.cpp/pull/18860
[#19356]: https://github.com/ggml-org/llama.cpp/pull/19356
[#18888]: https://github.com/ggml-org/llama.cpp/pull/18888
[#19360]: https://github.com/ggml-org/llama.cpp/pull/19360
[#18096]: https://github.com/ggml-org/llama.cpp/pull/18096
[#19738]: https://github.com/ggml-org/llama.cpp/pull/19738

### 2.2 KleidiAI in llama.cpp [V: `ggml/src/ggml-cpu/kleidiai/kleidiai.cpp` @84e76d8]

- **Types:** Q4_0, Q8_0 and F32/F16 only. For anything else it logs "no kernel for tensor type %s … (kernels
  available for Q4_0 and Q8_0)".
- **Ops:** `MUL_MAT` and `GET_ROWS` only, on **2D** tensors (`ggml_n_dims(src0) == 2`). **No `MUL_MAT_ID`,**
  so experts are never KleidiAI.
- **Loading:** same load-time extra-buffer model as repack.
- **Kernel families:** dotprod, i8mm (+dotprod), SVE+i8mm, SME and SME2. Tensor G3 (X3/A715) has no SME.
- A KleidiAI build decoded 18% slower on Neoverse-N2
  ([#25976](https://github.com/ggml-org/llama.cpp/issues/25976), via sub-research).
- **Verdict:** irrelevant for our experts. Only useful if the dense weights were Q4_0/Q8_0.

### 2.3 Runtime (streaming-compatible) GEMM paths in upstream [V]

These work on any weight buffer, including our streamed and rebound ones.

**llamafile tinyBLAS** (`ggml/src/ggml-cpu/llamafile/sgemm.cpp`):
- On ARM it covers F32, F16, Q8_0 and Q4_0, for `n >= 2` tokens only; it never runs for decode.
- **Compiled out whenever `__ARM_FEATURE_MATMUL_INT8` or SVE is defined** (ggml-cpu.c lines 46-48).
- Not used for `MUL_MAT_ID`.

**IQ panel GEMM ("iqp")**, [#27402](https://github.com/ggml-org/llama.cpp/pull/27402) by bartowski, merged
2026-08-31:
- Decodes 8 weight rows of IQ1_S/IQ1_M/IQ2_XXS/IQ2_XS/IQ2_S/IQ3_XXS/IQ3_S/IQ4_XS into an int8 tile, then runs
  an integer GEMM. Covers `MUL_MAT` and `MUL_MAT_ID`, with a per-expert threshold of 8 tokens.
- Measured on **Qwen3.6-35B-A3B**, pure IQ quants, EPYC 9654, 24 threads, ub512: IQ2_XS 110 → 270 (2.46x),
  IQ2_XXS 2.59x, IQ3_XXS 85 → 262 (3.07x), IQ4_XS 1.90x. At ub128: 1.94-2.28x. Perplexity unchanged.
- **Gated on `ggml_cpu_has_avx2()`,** so it is off on ARM. The author: "The generic kernels in `iqp.cpp` are
  technically dead code … for a future implementation on other architectures." Off switch:
  `GGML_NO_IQ_PANEL=1`.

**"Tiled mul_mat for k-quants"**, [#27851](https://github.com/ggml-org/llama.cpp/pull/27851), open since
2026-08-28:
- A generic int8 tile GEMM for K-quants and IQ quants, including `MUL_MAT_ID`. Author: "the vec_dot approach
  … duplicates the work of unpacking quants"; "One microtile kernel and 3 tiny bit unpackers are all a new
  architecture needs".
- x86 only for now. VNNI: q2_K 3.8x, q3_K 7.0x, q5_K 6.9x on big matmuls; AVX2 up to 2x. An ARM port is
  planned after review.
- Its threshold is 32 rows per expert, and bartowski notes a regression for MoE at small batches.

### 2.4 ik_llama.cpp (ikawrakow)

Verified via sub-research; a shallow clone at 20f7a72 (2026-09-24) was grepped.

**Mechanism.** For batches of 32 tokens or more on ARM (64 for Q6_K), weight rows are converted on the fly
into 8-row-interleaved Q8_K_R8, Q8_0_R8 or Q8_1. The NEON kernel then runs a GEMM with `vdotq_laneq_s32`
(8 rows × up to 8 columns). For MoE, `iqk_mul_mat_moe` passes each expert's token count to that decision.
Code: `is_dequant_better` in `iqk_mul_mat.cpp`. There are **no i8mm, SMMLA or SVE kernels**; everything is
dotprod.

**ARM prompt-processing numbers** (M2 Max, LLaMA-3.1-8B, pp512):

| Type | Mainline b4384 → ik | ik: before → after on-the-fly repack |
|---|---|---|
| IQ2_XS | 20.4 → 70.1 | 46.4 → 166.7 |
| IQ2_XXS | 18.5 → 87.9 | |
| IQ3_XXS | 13.4 → 78.3 | 51.8 → 165.6 |
| Q2_K_S | 33.0 → 109.0 | Q2_K 85.7 → 168.1 |
| Q3_K | | 45.7 → 170.8 |
| IQ4_XS | 37.9 → 134.0 | 71.2 → 167.8 |
| Q4_0 | 114.6 → 122.5 | |

Sources: first column from [disc #164](https://github.com/ikawrakow/ik_llama.cpp/discussions/164)
(2024-12-24); second from [#550](https://github.com/ikawrakow/ik_llama.cpp/pull/550) and
[#552](https://github.com/ikawrakow/ik_llama.cpp/pull/552) (2025-06-23/24). After #550/#552 all K and IQ
types land at about 162-170 on the same test.

**MoE caveat.** ikawrakow, [#531](https://github.com/ikawrakow/ik_llama.cpp/pull/531): Qwen3-30B-A3B Q3_K
gains about 1.4x at peak "and nothing significant below 64 tokens". With 256 experts and 8 routed, an expert
sees about ubatch/32 tokens on average, so the path mostly needs ubatch ≥ 1024-2048.

**Options:**
- **`-fmoe`** (fused up/gate plus activation, activations quantized once;
  [#229](https://github.com/ikawrakow/ik_llama.cpp/pull/229)): +3-4% prompt processing on CPU. On by default
  since 2025-10.
- **`-rtr` turns off mmap** and repacks all host tensors, including experts, at load
  ([#147](https://github.com/ikawrakow/ik_llama.cpp/pull/147)). Not compatible with streaming. The offline
  alternative is `llama-quantize --repack` ([#272](https://github.com/ikawrakow/ik_llama.cpp/pull/272)).

**Qwen3.5/3.6 (qwen35moe) support:**
- Supported ([#1288](https://github.com/ikawrakow/ik_llama.cpp/pull/1288), 2026-02-21).
- Fused delta-net on NEON, [#1361](https://github.com/ikawrakow/ik_llama.cpp/pull/1361): Qwen3-Next IQ4_XS on
  the M2 Max CPU, **pp512 200 vs mainline 67; decode 36 vs 12.3**.
- Fused ssm_conv on NEON: [#1425](https://github.com/ikawrakow/ik_llama.cpp/pull/1425).
- Loads Unsloth and bartowski Qwen3.6 GGUFs
  ([#2041](https://github.com/ikawrakow/ik_llama.cpp/issues/2041)).

**Android:**
- Termux builds need `-DGGML_ARCH_FLAGS="-march=armv8.2-a+dotprod+fp16"`. Without it the fast kernels are
  silently dropped and you get garbage at fake speeds
  ([#347](https://github.com/ikawrakow/ik_llama.cpp/pull/347),
  [#345](https://github.com/ikawrakow/ik_llama.cpp/issues/345)).
- **Use a build from 2026-09-22 or later.** [#2503](https://github.com/ikawrakow/ik_llama.cpp/pull/2503) fixes
  degenerate output from aarch64 Q8_0/Q4_0/IQ4_NL kernels at fewer than 32 columns, which is exactly the MoE
  case.

### 2.5 Other engines

- **MNN / MNN-LLM (Alibaba)** [V, via sub-research]:
  - CPU kernels quantize activations to int8 and multiply against int4/int8 weights, with **SMMLA on i8mm
    cores** (tile 10×8×8) and SDOT otherwise.
  - Paper [arXiv 2506.10443](https://arxiv.org/abs/2506.10443): 8.6x faster prefill than 2024-era llama.cpp.
  - Releases: Qwen3 MoE in 3.2.0; Qwen3.5-MoE with Gated-DeltaNet in 3.4.1 (2026-03, "performance to be
    optimized"); fused GDN prefill and **2/3-bit ARM weight kernels in 3.6.0** (2026-06).
  - Qwen3.6-35B-A3B-MNN is 4-bit, 19.9 GiB, which does not fit 12 GB, and MNN has no expert streaming from
    flash. No published MNN MoE phone numbers.
  - [I] Not a near-term option.
- **PowerInfer-2** ([arXiv 2406.06282](https://arxiv.org/abs/2406.06282)): OnePlus 12, TurboSparse-Mixtral-47B,
  512-token prefill about 30 tok/s vs llama.cpp about 4, using the Snapdragon NPU for prefill.
  **PowerInfer/SmallThinker** ([arXiv 2507.20984](https://arxiv.org/abs/2507.20984)): OnePlus 13,
  Qwen3-30B-A3B Q4_0 in RAM, decode 20.2; prefill not reported.
- **ExecuTorch/XNNPACK:** a fused `quantized_moe_ffn` op was upstreamed in July-August 2026
  ([arXiv 2605.27358](https://arxiv.org/abs/2605.27358)). Galaxy S25 (8 Elite), a 0.92B-active MoE,
  INT4/INT8, 4 threads: prefill about 125 / 114 / 79 tok/s at 512 / 1K / 2K. Not GGUF; a model port would be
  needed.
- **Hexagon NPU:**
  - llama.cpp Hexagon backend on Galaxy S26+ (8 Elite Gen 5): OLMoE pp1024 **1,263** tok/s
    ([#23989](https://github.com/ggml-org/llama.cpp/pull/23989)).
  - EStream (research): streams Qwen3-30B-A3B Q4_0 experts from UFS 4.1 into the NPU at 400-600 tok/s for
    2-4K prompts ([arXiv 2609.06551](https://arxiv.org/abs/2609.06551)). It also measured the NPU at 4.4x the
    CPU and 4.6x the Adreno GPU on expert matmuls.
  - **None of this exists for Tensor G3** (Mali-G715, Google TPU).
- **Mali:** Google ML Drift runs only dense models (Pixel 9 Llama-3.2-3B prefill 516 tok/s,
  [arXiv 2505.00232](https://arxiv.org/abs/2505.00232)); no MoE engine for Mali was found. llama.cpp's OpenCL
  backend targets Adreno only.
- **mllm:** no MoE phone prefill numbers found.

---

## 3. Q2: why an i8mm build could read prompts faster but decode slower

What `+i8mm` changes in upstream ggml-cpu [V, source @84e76d8]:

1. **2-row SMMLA vec_dot paths.** Type traits get `nrows = 2` for Q4_0, Q4_1, Q8_0, Q4_K and Q6_K. They run
   only when the token count and the row chunk are both even, so in prefill; `mul_mat` falls back to 1 row
   otherwise. `mul_mat_id` always passes `nrc = 1`. **The one-row kernels are the same code in both builds.**
   The `nrc == 2` block is an early `if` inside the same function.
2. **llamafile tinyBLAS is compiled out** (`#if defined(__ARM_FEATURE_SVE) || defined(__ARM_FEATURE_MATMUL_INT8)
   #undef GGML_USE_LLAMAFILE`). It only ever ran for 2 or more tokens, so decode is unaffected. Prompt reading
   loses it for F32/F16/Q8_0/Q4_0. For us that is only the F32 router and ssm_alpha/beta (about 1% of
   multiply-adds). For files with Q8_0 attention it matters: M4 Q8_0 dense 512 tokens 371 → 233 GFLOPS.
3. **Repack layouts switch** to 4x8/8x8 i8mm GEMM/GEMV. BigMoeOnEdge doesn't repack, so this has no effect for
   us today.

So for BigMoeOnEdge, **decode runs the same kernels in both builds**. The +16% prompt speed fits Q6_K's
SMMLA path (M4: Q6_K 512 tokens 144 → 180 GFLOPS) on ssm_out, attn_k/v and shared down, plus Q4_K.

**The 28% decode loss is most likely a measurement artifact** [I, with supporting evidence]:
- `notes/2026-09-23-pixel-first-day.md` compared one run each ("3.8 vs 2.7 tok/s", decoding right after a
  123-147 s full-load prompt). The same notes say the phone ran slower in later runs, and 1.5 °C costs about
  15%.
- On the M4 I reproduced the trap. The first sequential pass (dotprod build, then i8mm) showed i8mm decode-shaped
  `MUL_MAT_ID` 13-36% slower. Alternating A/B/A/B re-runs of the same cases showed no difference:

| Case | i8mm, two runs | dotprod, two runs |
|---|---|---|
| IQ2_XS | 114.6 / 118.6 | 121.2 / 117.7 |
| Q2_K | 194.9 / 211.6 | 208.7 / 209.3 |
| Q3_K | 157.5 / 162.8 | 157.7 / 158.4 |

Other reports:
- ikawrakow says mainline got slower on his M2 Max once i8mm was enabled
  ([ik #361](https://github.com/ikawrakow/ik_llama.cpp/issues/361), 2025-05).
- An RK3588 user found ik faster with `+noi8mm+nosve+nosme`
  ([ik #345](https://github.com/ikawrakow/ik_llama.cpp/issues/345)).
- [I] Both are about repacked layouts or llamafile loss, not the one-row kernels.

**A configuration that gets both** [I]:
1. **Re-measure first:** two binaries in ABAB order from the same skin temperature, each one decode run plus
   one 1,216-token prompt. If decode matches, ship i8mm. The i8mm binary crashes on cores without i8mm
   (Snapdragon 865 and older), so either keep a dotprod fallback binary and choose at runtime from
   `/proc/cpuinfo` `i8mm`, or use ggml's multi-variant CPU backend.
2. If we later turn on dense repack (experiment E2), the i8mm build picks the 8x8 SMMLA GEMMs for Q5_K/Q6_K.
   Those gave 2.2-2.45x prompt speed and 1.04-1.25x decode on Exynos 2400.
3. If we move to a file with Q8_0/Q4_0 dense weights, re-check: i8mm loses llamafile there.

---

## 4. Q3: published MoE numbers on phones and SBCs

All [V] (URLs given); "pp" means prompt processing. Nothing was found for Tensor G3/G4/G5, Dimensity or
Exynos running a MoE.

| Device (SoC) | Engine | Model / quant | Prefill tok/s | Decode tok/s | Date / source |
|---|---|---|---|---|---|
| **Pixel 8 Pro (Tensor G3), ours** | BigMoeOnEdge (mainline kernels), 5 threads, streamed | Qwen3.6-35B-A3B UD-Q2_K_XL, ~1.2K prompt | **10-13** | 5.8-6.3 | notes/2026-09-24 |
| Raspberry Pi 5 16GB (4x A76) | **ik_llama.cpp**, `-t 3 -tb 4`, mlock | Qwen3.5-35B-A3B, iq2_ks/iq4_ks experts, q8_0 attention, 11.4 GiB | **30.9** (pp512) | 4.5 | 2026-03, [HF mtrpires](https://huggingface.co/mtrpires/Qwen3.5-35B-A3B-IQK-RPi5-16GB) |
| Raspberry Pi 5 16GB | mainline b7651 | Qwen3-30B-A3B Q3_K_S | 10.9 (pp512), 8.6 (pp4096) | 8.0 | 2026-01, [geerlingguy #47](https://github.com/geerlingguy/ai-benchmarks/issues/47) |
| Oracle A1 (4x Neoverse-N1) | **ik_llama.cpp** | Qwen3.6-35B-A3B UD-IQ4_XS (~17 GB) | **28.9** | 9.9 | 2026-07, [ik #2094](https://github.com/ikawrakow/ik_llama.cpp/pull/2094) |
| Oracle A1 (same class, our VM) | mainline llama-bench | Qwen3.6-35B-A3B UD-Q2_K_XL | 16.6 (pp512) | 8.1 | notes/2026-09-19 |
| Radxa Orion O6 (CIX P1) | mainline b5857 → **ik** | Qwen3-30B-A3B Q4_K_M | 23.3-24.3 → **50.5** (pp512) | 15-16 | 2025-07/08, [Radxa forum](https://forum.radxa.com/t/llama-cpp-benchmarks/27813) |
| Orange Pi 5 (RK3588) | mainline → ik | Qwen3.5-35B-A3B UD-Q4_K_M | n/a | 3.6 → **8.2** | 2026-03, reddit 1rjc60i |
| Galaxy S25 (8 Elite) | llama.cpp CPU | LFM2-8B-A1B Q4_0 (1.5B active) | 85 (1K) / 76 (4K) | 48.6 / 41.9 | 2025-11, [arXiv 2511.23404](https://arxiv.org/abs/2511.23404) |
| Galaxy S25 (8 Elite) | ExecuTorch+XNNPACK fused MoE | MobileMoE-L (0.92B active), INT4 | 125 (512) / 79 (2K) | 43 / 23 | 2026-05, [arXiv 2605.27358](https://arxiv.org/abs/2605.27358) |
| OnePlus 15 (8 Elite Gen 5) | EStream, Hexagon NPU, experts streamed from UFS 4.1 | Qwen3-30B-A3B Q4_0 | 212 / 406 / 599 (1K / 2K / 4K) | n/a | 2026-09, [arXiv 2609.06551](https://arxiv.org/abs/2609.06551) |
| Galaxy S26+ (8 Elite Gen 5) | llama.cpp Hexagon | OLMoE-1B-7B Q4_0 | 1,263 (pp1024) | 32.5 | 2026-06, [#23989](https://github.com/ggml-org/llama.cpp/pull/23989) |
| OnePlus 12 (8 Gen 3) | PowerInfer-2 (NPU prefill) | TurboSparse-Mixtral-47B | ~30 (512) | 10-11.7 | 2024-06, [arXiv 2406.06282](https://arxiv.org/abs/2406.06282) |
| OnePlus 13 (8 Elite) | PowerInfer | Qwen3-30B-A3B Q4_0, in RAM | n/a | 20.2 | 2025-07, [arXiv 2507.20984](https://arxiv.org/abs/2507.20984) |

**Where we stand** [I]:
- On a CPU, this model class reads prompts at 10-11 tok/s with mainline kernels on a Pi 5 and at 30-50 tok/s
  with ik_llama.cpp on Pi 5, Oracle A1 or Orion O6 hardware.
- Tensor G3's X3 plus 4x A715 is faster per core than a Pi 5's A76. **20-30 tok/s should be reachable with
  ik-class kernels.** The sub-research reached the same estimate by scaling the S25 CPU figure.
- The 100-1,000+ tok/s phone numbers need a Hexagon NPU and do not transfer to the Pixel.

---

## 5. Q4: quantization choices for MoE on phones

### 5.1 Speed on ARM CPU by expert type [V]

- **My M4 microbenchmark** (§1.4, mainline `vec_dot` path, no repack): IQ3_XXS 75 < IQ2_XS 111 < Q3_K 147 <
  MXFP4 163 ≈ IQ4_NL 167 < Q2_K 197 ≈ IQ4_XS 205 ≈ Q4_0 209 < Q8_0 235 < Q4_K 258 GFLOPS, experts at 512
  tokens.
- **Mainline b4384, M2 Max, LLaMA-3.1-8B, pp512** ([ik disc #164](https://github.com/ikawrakow/ik_llama.cpp/discussions/164)):

| Type | pp512 | Note |
|---|---|---|
| Q4_0 | 114.6 | repacked |
| IQ4_NL | 92.6 | repacked |
| Q8_0 | 54.5 | |
| Q4_K_S | 43.3 | |
| IQ4_XS | 37.9 | |
| Q2_K_S | 33.0 | |
| Q3_K_S | 25.0 | |
| IQ2_XS | 20.4 | |
| IQ2_XXS | 18.5 | |
| IQ3_XXS | 13.4 | |

- bartowski's model cards: "I-quants … can also be used on CPU and Apple Metal, but will be slower than their
  K-quant equivalent" ([example card](https://huggingface.co/bartowski/Qwen_QwQ-32B-GGUF)).
- Unsloth's "I-quants are only 5-10% slower" comes from a GPU table (pp512 about 1,970) and does not apply to
  ARM CPUs (via sub-research,
  [unsloth Qwen3.5 GGUF benchmarks](https://unsloth.ai/docs/models/qwen3.5/gguf-benchmarks)).
- In ik_llama.cpp every type reaches about 162-170 pp512 on M2 Max after on-the-fly repack. For decode on
  NEON, Q2_K (32.4 tg) > IQ2_KL (26.8) > IQ2_S (15.7), and trellis `_KT` types are worst
  ([ik #602](https://github.com/ikawrakow/ik_llama.cpp/pull/602)).
- **MXFP4** on ARM: mainline repack is dotprod 4x4 only. ikawrakow says its accuracy on models not trained in
  MXFP4 is "about the same as IQ3_K" at 4.25 bpw ([ik #682](https://github.com/ikawrakow/ik_llama.cpp/pull/682)).
  Unsloth dropped MXFP4 from its Q2/Q3/Q4_K_XL recipes on 2026-03-05.

### 5.2 Quality at similar size (Qwen3.5-35B-A3B; Unsloth table; wiki PPL; BF16 PPL 6.53) [V, via sub-research]

| Quant | Size | PPL | Mean KLD |
|---|---|---|---|
| Unsloth Q2_K_XL (IQ experts, our recipe) | 12.04 GB | 7.044 | 0.097 |
| bartowski Q2_K_L (K-quant experts) | 11.98 GB | 7.550 | 0.156 |
| Unsloth IQ3_XXS | 13.12 GB | 6.783 | 0.050 |
| Unsloth Q3_K_XL | 16.06 GB | 6.725 | 0.031 |
| Unsloth MXFP4_MOE | 18.17 GB | 6.600 | 0.027 |
| ubergarm Q4_0 (Q4_0/Q4_1 experts, Q8_0 rest) | 19.79 GB | 6.578 | **0.014** |

[I] **At equal size, K-quant experts lose quality.** Swapping IQ2_XS/IQ3_XXS for Q2_K/Q3_K at about +1.3 GB (so
about 0.3 bpw more) may get back to parity, but that has to be measured.

Intel AutoRound on Qwen3-30B-A3B-2507 keeps 95.8% (q2_k_s) and 98.2% (q3_k_s) of BF16 over 10 lm-eval tasks
([AutoRound docs](https://github.com/intel/auto-round/blob/main/docs/gguf_alg_ext_acc.md)).

Unsloth's sensitivity analysis: expert gate/up tolerate 3 bits; down is more sensitive; ssm_out and attention
are the most sensitive. The shared expert is about 53x more sensitive per parameter than routed experts
([crucible-labs card](https://huggingface.co/crucible-labs/Qwen3.6-35B-A3B-REAP-48-v2-GGUF)).

### 5.3 Pruning and fewer active experts [V, via sub-research]

**REAP (Cerebras)** ([arXiv 2510.13999](https://arxiv.org/abs/2510.13999)):
- Qwen3-30B-A3B, 25% pruned: MMLU 0.779 → 0.673.
- Table A5 concludes quantization beats pruning down to 4 bits.
- MoE-XBench ([arXiv 2608.21693](https://arxiv.org/abs/2608.21693), 2026-08), Qwen3.6-35B-A3B: 25% REAP gives
  MMLU 0.866 → 0.847, PPL 6.72 → 8.67. Its conclusion: "expert pruning is the dominant degradation source".
- [arXiv 2606.17609](https://arxiv.org/abs/2606.17609): pruned models can pass multiple choice but fail the
  same question in open generation.
- No official Cerebras REAP exists for Qwen3.5/3.6. **Avoid for long-tail factual QA.** REAP also doesn't cut
  per-token compute, only size.

**Fewer active experts** (this cuts prompt compute directly):
- [arXiv 2609.04575](https://arxiv.org/abs/2609.04575) (2026-09-04), **on Qwen3.6-35B-A3B**: keep the top k1
  experts but normalize by the probability sum of the top k2.

| Setting | MMLU | GSM8K |
|---|---|---|
| k=8 | 81.65 | 95.0 |
| k=6, plain renormalization | 79.05 | 92.8 |
| **k=6, top-8 denominator** | **80.55** | **95.0** |
| k=4, top-16 denominator | 81.30 | 94.4 |

- OEA ([arXiv 2511.02237](https://arxiv.org/abs/2511.02237)), Qwen3-30B-A3B plain top-k: GPQA 60.2 (k=8),
  58.3 (k=6), 54.3 (k=4). AIME24 flat down to k=5.
- ik's `-ser` ([#239](https://github.com/ikawrakow/ik_llama.cpp/pull/239)): DeepSeek-Lite, 5 of 6 experts,
  +1.36% PPL.
- BigMoeOnEdge already has `--n-expert-used N` (global) and `--drop-cold-experts F --drop-in-prefill`. Its
  docs say that in prefill, with a cold cache, the drop rule discards about 42% of the routing weight rather
  than 9%, so it is off by default there
  ([expert-dropping.md](https://github.com/Helldez/BigMoeOnEdge/blob/main/docs/expert-dropping.md)).
- [I] None of this is measured on long-tail knowledge. Limiting the cut to *prompt* tokens, which are mostly
  retrieved text, while answer tokens keep k=8 should be safer than a global cut. That still needs our eval.

**Mixed precision per expert** (papers; not in llama.cpp): HOBBIT
([2411.01433](https://arxiv.org/abs/2411.01433)), DynaExq ([2511.15015](https://arxiv.org/abs/2511.15015)),
SliceMoE ([2512.12990](https://arxiv.org/abs/2512.12990)), MxMoE ([2505.05799](https://arxiv.org/abs/2505.05799)).
In GGUF, the practical form is per-tensor or per-layer types via `llama-quantize --tensor-type`. The Pi 5 file
above keeps layers 0-7 experts at iq4_ks and the rest at iq2_ks.

---

## 6. Threads worth reading in a browser (Reddit blocks fetching)

All under reddit.com/r/LocalLLaMA/comments/:

| Thread | About |
|---|---|
| 1rv6jyh | Qwen3.5-35B GGUF quants, 16-22 GiB, KLD and speed |
| 1rjc60i | Qwen3.5-35B-A3B at 8 t/s on Orange Pi 5 with ik_llama.cpp |
| 1rg87bj | Qwen3.5-35B-A3B on a Raspberry Pi 5 16GB |
| 1rlvn8m/comment/o9j2vqv | ARM user: ik 15-20% faster on Q4_K_M |
| 1qq9n5f | Orange Pi 6 Plus, Qwen3-VL-30B-A3B IQ4_XS pp512 52.8 with ik |
| 1oamnb9 | "Is there a way to effectively run MoE models in a smartphone?" |
| 1lpzvtx | 14B LLMs on Snapdragon 8 Elite (MNN Chat) |
| 1nvtfy1 | Dimensity 9500 or Snapdragon 8 Elite |
| 1rfds1h, 1rlkptk | Qwen3.5 quant discussions |

Also: [llama.cpp #27851](https://github.com/ggml-org/llama.cpp/pull/27851), the tiled-GEMM thread with the ARM
port plan, and [ik discussion #2028](https://github.com/ikawrakow/ik_llama.cpp/discussions/2028) (ik roadmap,
"GEMM for ARM CPUs supporting SVE").

---

## 7. Ranked experiments

Prompt-speed gains are for the ~1,200-token research prompt. "Model" means my M4 time model (§1.4), which
ignores non-matmul ops and engine overhead, so real gains will be smaller [I].

| # | Experiment | Expected prompt gain | Decode effect | Size / RAM | Effort |
|---|---|---|---|---|---|
| E0 | Diagnose the split (BMOE_DONE prefill fields + simpleperf) | none; decides E2-E6 | none | none | 30 min |
| E1 | Re-test the i8mm build, alternating runs; ship it if decode matches | **+16% (measured)** | expected unchanged (§3) | none | trivial |
| E2 | Repack dense (non-expert) weights: `use_extra_bufts=true` plus an override keeping `_exps` in the plain CPU buffer; AHWB copies taken from repacked bytes | **+25-35% (model)**: Q5_K ×2.0-2.3, Q6_K ×2.5-3.1 (PR data) | 0 to +5% (repack GEMV 0.93-1.29x) | none | small engine patch |
| E3 | K-quant experts: gate/up Q2_K, down Q3_K, rest as Unsloth, using Unsloth's imatrix | **+25-35% (model)**; experts 1.8-2.0x | expert compute per token −45%; +10% flash bytes on misses; [I] net decode likely ≥0 | file +1.3 GB (~13.6 GB); hit rate down a little | small (quantize on the VM); quality check needed |
| E4 | Prompt-only top-6 with top-8 denominator (2609.04575) | +10-15% (routed experts −25%) | none (decode keeps k=8) | none | small llama.cpp patch |
| E5 | Whole prompt in one ubatch (`--ubatch 1280`, or 1024) | +6% (measured) | none | +750 MB reserved at 1280 (measured) | flag |
| E6 | Real GEMM for expert matmuls: NEON port of upstream iqp (#27402) or of #27851's microkernel, or port ik's Q8_K_R8 path into `mul_mat_id` | experts 2-3x at ub ≥1024 → **+40-60% on its own**; with E2, **~2.5x total (model)** | none | none | medium-high; develop and test on the M4 first |
| E7 | ik_llama.cpp as engine base (port the streaming hooks into `iqk_mul_mat_moe` and the fused MoE) | **2-3x** (Pi 5, Orion O6, Oracle evidence) | ik decode was 2x mainline on RK3588; unknown with streaming | none | high |
| E8 | Shorter prompts or reused prefix state: static instructions first, save and restore the KV plus GDN state after them (`llama_state_seq_*`) | proportional to tokens not re-read | none | small state file | app + engine, small-medium |

Combined [I, model]:
- E1+E2+E3+E4+E5: prompt matmul time about 38 → 16 ms/token on the M4, about **2-2.3x**. Expect about 1.8-2x
  on the phone: **10-13 → 20-25 tok/s, about 60-120 s → 30-60 s per question**.
- Keep an eye on flash: at ubatch 1280 a prompt reads about 9 GB of experts (measured). At 20-25 tok/s that is
  about 50 s of compute against about 5-7 s of reads at UFS 3.1 speeds, still compute-bound. Watch
  `prefill_io_s` and `prefill_stall_s`.

### First measurement for each, on the phone, under two hours

**E0.** Run the 1,216-token research prompt through `bmoe-cli` from adb and read the `prefill_*` fields in
`BMOE_DONE`. Then `simpleperf record -g -p <pid>` over one prefill and
`simpleperf report --sort symbol`. Look at the share of `ggml_vec_dot_iq2_xs_q8_K`, `_iq3_xxs_`, `_q5_K_`,
`_q6_K_`, gated_delta_net and quantize_row. That confirms or corrects the 54/45 split. Also compare against
mainline on the same phone: `llama-bench -m UD-Q2_K_XL.gguf -p 1024 -n 0 -ub 1024 -t 5 -C 1f0` (mmap; each
ubatch streams the experts once). If that beats BigMoeOnEdge clearly, the gap is engine overhead, not kernels.

**Kernel table for the Tensor G3.** Apply `the `test-backend-ops` shape patch (not in the repository)` to upstream llama.cpp,
cross-compile `test-backend-ops` twice (dotprod and +i8mm) with the existing NDK setup, push, and run:

```
TBO_THREADS=5 taskset 1f0 ./test-backend-ops perf -b CPU -o MUL_MAT_ID -p 'n_mats=64,n_used=2,b=0,m=512'
TBO_THREADS=5 taskset 1f0 ./test-backend-ops perf -b CPU -o MUL_MAT -p 'm=8192'
```

About 15 minutes per build. This gives the G3's own §1.4 table: how much Q2_K/Q3_K beat IQ2_XS/IQ3_XXS, and
the Q6_K SMMLA gain.

**E1.** Two engine binaries (dotprod, +i8mm). Order ABAB, each run from 29-30 °C skin temperature, each run
being the Dead Sea draft (decode) plus the 1,216-token prompt. Four runs, about 1 hour.

**E2.** No engine change needed to size it. On the phone, run upstream `llama-batched-bench -m UD-Q2_K_XL.gguf
-c 2048 -b 1024 -ub 1024 -npp 1024 -ntg 1 -npl 1 -t 5`, pinned with `taskset 1f0`, once with `--no-repack`
and once without. The IQ experts have no ARM repack, so the difference is exactly the dense repack gain on our
model. The load log shows `CPU_REPACK` buffer MiB.
- Engine patch afterwards: set `use_extra_bufts = true`, pass `tensor_buft_overrides = {{"_exps",
  ggml_backend_cpu_buffer_type()}}` so experts stay mmap'd and streamable, and have `dense_weights.cpp` copy
  from `tensor->data` (already repacked) instead of from file offsets for tensors whose buffer is
  CPU_REPACK.
- [I] Rebinding `tensor->data` keeps `tensor->buffer` and `tensor->extra`, which is what `supports_op`
  checks.

**E3.** Start with the kernel table above. Then `llama-batched-bench` as in E2 on a ready-made K-quant-expert
file: bartowski Qwen3.6 Q2_K (13.5 GB) or Intel AutoRound Q2_K_S. Download and push take about 30 min.
- If the gain holds, make the real file on the Oracle VM: `llama-quantize --imatrix imatrix_unsloth.gguf_file
  --tensor-type ffn_gate_exps=q2_K --tensor-type ffn_up_exps=q2_K --tensor-type ffn_down_exps=q3_K …`, from
  Unsloth's Q8_0 or BF16, keeping Unsloth's types for all other tensors.
- Then run the 72-question eval against UD-Q2_K_XL, since §5.2 says equal-size K-quants lose quality.

**E4.** Speed bound first, 20 min: phone prompt read with `--n-expert-used 6`. This is global, so it is only a
speed bound. Quality needs the prompt-only patch: in the fork's MoE graph builder, when `n_tokens > 1`,
select top-6 but divide by the top-8 probability sum. Run the eval set on the VM.

**E6.** First measurement on the Mac, not the phone. Write the NEON microkernel against #27851's
kernel/driver split, or fill in iqp's generic path with `vdotq_laneq_s32` or SMMLA. Validate with
`test-backend-ops -o MUL_MAT_ID` for correctness and `perf` with the patch above. Then repeat the phone
kernel-table run.

**E7.** Size the prize before any porting. Build ik_llama.cpp for Android: a build from 2026-09-22 or later,
with `-DGGML_ARCH_FLAGS="-march=armv8.2-a+dotprod+fp16"`, **no `-rtr`**. Run `llama-bench -m UD-Q2_K_XL.gguf
-p 1280 -n 0 -ub 1280 -b 1280 -t 5 -mmp 1` pinned with `taskset 1f0`, next to mainline `llama-bench` with the
same flags. About 1.5-2 h including the build.
- Cheaper sanity check first: the same on the Oracle VM, which already has the model. ik reported 28.9 tok/s
  there on IQ4_XS, against our mainline 16.6.

### What not to do [V/I]
- KleidiAI: no `MUL_MAT_ID`, Q4_0/Q8_0 only.
- Mali GPU or TPU: measured slower or unavailable; no MoE engine for Mali exists.
- REAP: knowledge loss.
- MNN: no flash streaming; the 4-bit file doesn't fit.
- ik `-rtr`: turns off mmap.
- Q4_0 experts purely for repack: in mainline `MUL_MAT_ID` repack is GEMV per token (OLMoE +13%), and it
  would double the expert bytes, which cuts the cache hit rate and decode speed.

---

## Sources (primary)

**llama.cpp source @84e76d8 (2026-09-24):**
- [repack.cpp](https://github.com/ggml-org/llama.cpp/blob/84e76d8a23162eca70490da131945ebec1f09bf4/ggml/src/ggml-cpu/repack.cpp)
- [ggml-cpu.c](https://github.com/ggml-org/llama.cpp/blob/84e76d8a23162eca70490da131945ebec1f09bf4/ggml/src/ggml-cpu/ggml-cpu.c)
- [arch/arm/quants.c](https://github.com/ggml-org/llama.cpp/blob/84e76d8a23162eca70490da131945ebec1f09bf4/ggml/src/ggml-cpu/arch/arm/quants.c)
- [kleidiai.cpp](https://github.com/ggml-org/llama.cpp/blob/84e76d8a23162eca70490da131945ebec1f09bf4/ggml/src/ggml-cpu/kleidiai/kleidiai.cpp)
- [iqp.cpp](https://github.com/ggml-org/llama.cpp/blob/84e76d8a23162eca70490da131945ebec1f09bf4/ggml/src/ggml-cpu/iqp.cpp)
- [llamafile/sgemm.cpp](https://github.com/ggml-org/llama.cpp/blob/84e76d8a23162eca70490da131945ebec1f09bf4/ggml/src/ggml-cpu/llamafile/sgemm.cpp)

**llama.cpp PRs and issues:** [#27402](https://github.com/ggml-org/llama.cpp/pull/27402),
[#27851](https://github.com/ggml-org/llama.cpp/pull/27851), [#14918](https://github.com/ggml-org/llama.cpp/pull/14918),
[#18860](https://github.com/ggml-org/llama.cpp/pull/18860), [#18888](https://github.com/ggml-org/llama.cpp/pull/18888),
[#19356](https://github.com/ggml-org/llama.cpp/pull/19356), [#19360](https://github.com/ggml-org/llama.cpp/pull/19360),
[#17494](https://github.com/ggml-org/llama.cpp/pull/17494), [#16739](https://github.com/ggml-org/llama.cpp/pull/16739),
[#18096](https://github.com/ggml-org/llama.cpp/pull/18096), [#19738](https://github.com/ggml-org/llama.cpp/pull/19738),
[#13388](https://github.com/ggml-org/llama.cpp/pull/13388), [#28068](https://github.com/ggml-org/llama.cpp/pull/28068),
[#23989](https://github.com/ggml-org/llama.cpp/pull/23989), [issue #10662](https://github.com/ggml-org/llama.cpp/issues/10662),
[issue #25976](https://github.com/ggml-org/llama.cpp/issues/25976). #28068 is a GDN q/k normalization fix merged
2026-09-06, after BigMoeOnEdge's 2026-08-28 llama.cpp pin; its measured KLD effect is tiny.

**BigMoeOnEdge:** [README](https://github.com/Helldez/BigMoeOnEdge),
[session.cpp](https://github.com/Helldez/BigMoeOnEdge/blob/main/core/src/engine/session.cpp),
[CHANGELOG](https://github.com/Helldez/BigMoeOnEdge/blob/main/CHANGELOG.md),
[expert-dropping.md](https://github.com/Helldez/BigMoeOnEdge/blob/main/docs/expert-dropping.md).

**ik_llama.cpp:** [disc #164](https://github.com/ikawrakow/ik_llama.cpp/discussions/164),
PRs [#147](https://github.com/ikawrakow/ik_llama.cpp/pull/147), [#229](https://github.com/ikawrakow/ik_llama.cpp/pull/229),
[#239](https://github.com/ikawrakow/ik_llama.cpp/pull/239), [#272](https://github.com/ikawrakow/ik_llama.cpp/pull/272),
[#347](https://github.com/ikawrakow/ik_llama.cpp/pull/347), [#531](https://github.com/ikawrakow/ik_llama.cpp/pull/531),
[#550](https://github.com/ikawrakow/ik_llama.cpp/pull/550), [#552](https://github.com/ikawrakow/ik_llama.cpp/pull/552),
[#602](https://github.com/ikawrakow/ik_llama.cpp/pull/602), [#682](https://github.com/ikawrakow/ik_llama.cpp/pull/682),
[#1288](https://github.com/ikawrakow/ik_llama.cpp/pull/1288), [#1361](https://github.com/ikawrakow/ik_llama.cpp/pull/1361),
[#1425](https://github.com/ikawrakow/ik_llama.cpp/pull/1425), [#2094](https://github.com/ikawrakow/ik_llama.cpp/pull/2094),
[#2503](https://github.com/ikawrakow/ik_llama.cpp/pull/2503); issues [#345](https://github.com/ikawrakow/ik_llama.cpp/issues/345),
[#361](https://github.com/ikawrakow/ik_llama.cpp/issues/361), [#2041](https://github.com/ikawrakow/ik_llama.cpp/issues/2041).

**Devices:** [HF mtrpires RPi5](https://huggingface.co/mtrpires/Qwen3.5-35B-A3B-IQK-RPi5-16GB),
[geerlingguy #47](https://github.com/geerlingguy/ai-benchmarks/issues/47),
[Radxa forum](https://forum.radxa.com/t/llama-cpp-benchmarks/27813),
[fastpath64](https://github.com/Marc-Dvci/fastpath64).

**Papers:** 2609.06551 (EStream), 2511.23404, 2605.27358, 2507.20984, 2609.14643 (BigMoMo), 2406.06282
(PowerInfer-2), 2506.10443 (MNN-LLM), 2505.00232 (ML Drift), 2510.13999 (REAP), 2608.21693 (MoE-XBench),
2606.17609, 2609.04575, 2511.02237 (OEA), 2411.01433 (HOBBIT), 2511.15015, 2512.12990, 2505.05799. All at
arxiv.org/abs/<id>.

**Quant quality:** [Unsloth Qwen3.5 GGUF benchmarks](https://unsloth.ai/docs/models/qwen3.5/gguf-benchmarks),
[Intel AutoRound docs](https://github.com/intel/auto-round/blob/main/docs/gguf_alg_ext_acc.md),
[bartowski card](https://huggingface.co/bartowski/Qwen_QwQ-32B-GGUF).

**GGUF headers:** read from
`https://huggingface.co/unsloth/Qwen3.6-35B-A3B-GGUF/resolve/main/Qwen3.6-35B-A3B-<quant>.gguf` with
a small GGUF header reader (not in the repository).
