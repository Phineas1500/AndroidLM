# Writing speed without changing the answers (2026-09-25)

After the prompt kernels and the search changes, writing is the largest cost of a question: in
the 24-question run (`notes/2026-09-25-iqk-port.md`) a sources-first answer spent a median 41 s
writing 152 tokens, and an answer-first question about 145 s writing its draft and check. The
constraint here: no change to what the model writes beyond rounding-level numerics, so no
shorter answers, no expert dropping, no speculation that could accept a different token.

## Where a written token's time goes

`simpleperf` on the engine while it writes (Pixel 8 Pro, the app's flags, Dead Sea prompt, 30 s
of a 400-token answer from 30.5 C, 6.85 tok/s), share of CPU cycles:

| | |
|---|---|
| Dense matrix-vector products (repacked Q5_K 22.3%, Q6_K 8.5%, Q4_K 7.9%) | 38.7% |
| Expert dot products (IQ2_XS 15.8%, IQ3_XXS 11.0%) | 26.8% |
| The thread pool's own loop and barrier (mostly spinning while other threads finish or the next graph arrives) | 21.0% |
| Everything else (attention, recurrent layers, copies, quantizing activations) | 13.5% |

`compute_s_tok` 0.131 s against `io_s_tok` 0.040 s: about three quarters of a token is compute.

## Tried

**ik_llama.cpp's kernels for single tokens too.** The port (patches/llama.cpp/0001) had left
one-token experts, i.e. generation, on llama.cpp's `vec_dot`. With `GGML_IQK_MOE_MIN_ROWS=1` they
take ik's kernel (each row decoded once, exact integer arithmetic per block, as for prompts):

| Same binary, Dead Sea prompt, 400 tokens, each from 30.5 C | tok/s |
|---|---|
| llama.cpp's kernel (min rows 2) | 6.51, 6.55 |
| ik's kernel (min rows 1) | 6.93, 6.90 |

+5.8% (order A B B A). The kernel test covers one token (errors 1.5-2.9e-7 of the output RMS, the
same as `vec_dot`'s). Now the default: min rows 1.

**No idle polling (`poll` 0 instead of llama.cpp's 50).** Spinning is a fifth of the cycles, and
heat is what slows a phone in use, so the first check was whether spinning less keeps it cooler.
Three research-sized requests back to back (1,216-token prompt, 200 tokens written, each), from
30.5 C, with the single-token kernels:

| Request | poll 50 (default) | poll 0 |
|---|---|---|
| 1 | 5.97 tok/s, prompt 36.5 s | 5.66 tok/s, prompt 36.4 s |
| 2 | 5.02 tok/s, 46.4 s | 5.08 tok/s, 43.4 s |
| 3 | 4.63 tok/s, 51.5 s | 4.45 tok/s, 51.7 s |
| skin after | 36.9 C | 36.8 C |

No difference in heat, a small loss on the first request (the threads wake later): not adopted,
and not worth a lower-power barrier either, since spinning is evidently not what heats the phone.

**What heat costs.** The same table is the clearest measurement of it: by the third request in a
row, writing is 22% slower and prompt reading 41% slower than on a cool phone. The app's
24-question run, one question every few minutes from about 32 C, wrote at a median 3.9 tok/s for
that reason, against 5.5-7 tok/s from a cool start.

## Not tried

- MTP drafting: BigMoeOnEdge measured it slower on a 12GB phone (drafts pull extra experts from
  flash, the draft context causes page-fault storms), and our model file has no MTP layer.
- A larger expert cache: +3.6% at 6000 MiB in an earlier measurement, but the one-slice prompt
  reading now reserves 750 MiB more, leaving 0.9-1.3 GB free during a question.
- Porting ik's dense kernels: see `notes/2026-09-25-iqk-port.md` (about a wash, since it would
  give up llama.cpp's repacked matrix-vector products, which writing uses).
