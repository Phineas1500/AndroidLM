# Looking for more speed without changing the answers (2026-10-02)

Two measurements on the Pixel 8 Pro, and what the 2026 literature on MoE models on phones
suggests. Both measurements used the shipped engine (1.3.0's libraries) and the app's flags.

## Where a prompt's time goes

`simpleperf` while the engine reads the 1,216-token sources-first prompt (36-38 s), share of
CPU cycles (`eval/speed_2026-10-02/prefill_prof.sh`, report in `prefill_profile.txt`):

| Part | Share |
|---|---|
| Dense weights: `ggml_gemm_q5_K_8x4_q8_K` 28.9%, `q6_K_8x4` 8.7%, their repack wrappers and matrix-vector products 2.2% | **about 40%** |
| Expert weights (the ported ik kernels: `mul_mat_q8_k_r8_q8_k` 9.1%, `convert_to_q8_k_r8` 4.4%, the IQ2_XS/IQ3_XXS kernels about 6%, `vec_dot_iq4_xs` 2.3%) | about 21% |
| The thread pool's loop and barriers (`ggml_graph_compute_thread`) | 12.7% |
| Attention 4.5%, the gated delta net 3.6%, `vec_dot_f32` 2.6%, conv, elementwise ops, norms and copies | about 26% |

- **Experts:** they are not the main cost of reading a prompt; the dense weights are.
- **i8mm for the experts:** i8mm versions of the expert kernels would save at most about 9% of
  prompt reading.
- **i8mm for the dense weights:** the dense kernels run llama.cpp's dot-product variant (`8x4`).
  The i8mm build tried on 2026-09-25 gained 1%, because their time goes into unpacking 5- and
  6-bit weights rather than into the multiplies.
- **What is left:** converting the dense weights to 8 bits for each prompt (as the expert kernels
  already do) and multiplying them with i8mm. Estimated +25-35% prompt reading. It is not exact:
  the 8-bit step is a small numerical change, about +0.0006 KL divergence when measured for the
  experts, and it would need its own check.

The model's numbers that matter for this:
- **Dense weights read per written token:** 1.40 GB, of which the 248k-word output layer is
  286 MB (20%). The 350 MB embedding table is only looked up.
- **Bandwidth:** about 27 GB/s at the measured 0.13 s of compute per token, which is close to
  what the CPU gets from memory.
- **Experts:** 10.5 GB, streamed.

## Thread layouts under sustained load

Three 1,216-token requests back to back, 200 tokens written each, every layout starting from
30.5 C, in the order A B C A (`eval/speed_2026-10-02/sus_layout.sh` and `run_layouts.sh`, raw output in
`layouts.log`):

| Layout | Request 1 | Request 2 | Request 3 | Skin after |
|---|---|---|---|---|
| A: the app's, 5 threads on cpus 4-8 (four A715 + the X3) | 5.87 tok/s, prompt 36.3 s | 4.82, 47.8 s | 4.50, 51.2 s | 37.4 C |
| B: 4 threads, the A715s only | 3.98, 45.5 s | 3.59, 52.4 s | 3.66, 61.7 s | 37.2 C |
| C: 4 threads, the X3 + three A715 | 5.15, 41.9 s | 4.18, 53.9 s | 4.02, 56.0 s | 37.5 C |
| A again | 5.91, 36.6 s | 4.71, 47.9 s | 4.48, 51.9 s | 37.6 C |

- **The app's layout stays:** it is fastest on every request, and no layout ran cooler. Dropping
  the X3 loses a third of the speed and saves no heat.
- **Heat costs the same in every layout:** by the third request writing is about 23% slower and
  prompt reading about 41% slower, as on 2026-09-25. Cooling from 37.5 C back to 30.5 C took
  13-22 minutes.

## What the literature offers (arXiv, 2026)

**Speculative decoding with exact verification** is the main lossless lever proposed for MoE
models on phones. A cheap source proposes tokens, and the model checks them in one pass.
- **The papers:** BigMoMo (2609.14643), S2-MoE (2608.15018), DraftExpert (2607.24434) and EcoSpec
  (2607.12696) report 1.4-2x on other MoE models. They rely on NPUs, trained draft experts or a
  GPU.
- **On this model and phone class:** the engine's author measured Qwen3.6-35B-A3B's own MTP head
  on a 12GB phone (BigMoeOnEdge `docs/mtp.md`) at 5.59 tok/s with 2 drafts (69% accepted) and 4.38
  with 3, against 5.82-6.14 without.
  - The verify batch reads more experts.
  - The recurrent-state snapshots that rejected drafts need take memory from the expert cache, so
    major faults rise 3-9x.
  - N-gram drafts on copy-heavy text lost 29% for the same reason.
- **What is available:** the head exists for this model (`mtp_num_hidden_layers = 1`), and a
  quantised file of it is published (bartowski, `mtp-Qwen_Qwen3.6-35B-A3B-Q4_0.gguf`, 1.19GB).
- **A careful attempt** would draft one token and trim the draft's output layer to frequent words.
  FR-Spec (2502.14856) cuts that layer's cost by 75% with the output unchanged.
- **Expected gain:** 0 to +25% writing, uncertain.

Ruled out:
- **Drafting with the model's own linear-attention layers:** acceptance 3.8% on Qwen3.5
  (2605.01106).
- **The GPU, the TPU, the slow cores and a bigger expert cache:** see
  [2026-09-24-speed-levers.md](2026-09-24-speed-levers.md).
- **Graph reuse and a fused gated delta net:** already in the engine's llama.cpp.
