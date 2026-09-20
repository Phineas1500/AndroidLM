# Oracle A1 baseline: Qwen3.6-35B-A3B (2026-09-19)

Host: Oracle A1 VM, 4x Neoverse-N1 (dotprod, no i8mm), 24GB RAM, Ubuntu 22.04.
Disk: network block volume, measured 105 MB/s sequential O_DIRECT read (a phone's
UFS 3.1/4.0 is roughly 10-20x faster). Other services were running on the VM (about
2.5GB RSS, near-idle CPU), and model downloads overlapped some runs, so treat all
numbers as +-10%.

Workspace on the VM: `~/androidlm` (llama.cpp 60081bb, BigMoeOnEdge 74ba18f v0.23.0,
models in `models/`, logs in `logs/`). BigMoeOnEdge is built and run only through
`scripts/sbx.sh` (systemd sandbox: no network, home hidden except `~/androidlm`).

## llama.cpp, whole model in RAM, 4 threads (llama-bench pp512 / tg128)

| Quant | File | Prefill tok/s | Decode tok/s |
|---|---|---|---|
| UD-IQ3_XXS | 13.2GB | 14.3 | 5.8 |
| UD-Q2_K_XL | 12.3GB | 16.6 | 8.1 |

## 8.5GB memory cap (12GB-phone proxy), thinking off

| Engine | Quant | Expert cache | Decode tok/s | Cache hit | Flash read/token | Compute s/token |
|---|---|---|---|---|---|---|
| llama.cpp mmap | UD-Q2_K_XL | n/a | 0.49 (prefill 2.9) | n/a | n/a | n/a |
| BigMoeOnEdge stream | UD-Q2_K_XL | 5000 MiB, 128 tok | 3.9 | 85.9% | 23.7 MiB | 0.137 |
| BigMoeOnEdge stream | UD-Q2_K_XL | 5500 MiB, 256 tok | 4.7 | 91.3% | 16.5 MiB | 0.141 |
| BigMoeOnEdge stream | UD-Q4_K_M | 5500 MiB, 128 tok | 0.97 | 77.5% | 96.5 MiB | 0.126 |

Flags: `--moe-stream --cache-mb N --overlap --dense-weights anon --chatml --no-think -c 2048 --ubatch 512`.

## Reading

- Plain mmap collapses under the cap (0.49 tok/s); explicit expert streaming is required
  even for the 12GB 2-bit file.
- Decode compute is about 0.13-0.14 s/token on N1 regardless of quant, so about 7 tok/s is
  the ceiling on this CPU class once flash is fast. Projected on phone flash (>=1 GB/s):
  Q2_K_XL about 6.5-7 tok/s, Q4_K_M about 5-6 tok/s. These are projections, not measurements.
- Hit rate is portable across machines: 5.5GB cache gives 91% at 2-bit and 78% at Q4.
- Prefill is the main risk: about 15 tok/s compute-bound on N1, so a 1,000-token retrieved
  context costs about a minute. Needs i8mm-class phone cores, short contexts, and prompt caching.
- Cold model load is slow here only because of the 105 MB/s disk (170 s for 2-bit).
- Quality spot check (IQ3_XXS, no retrieval, Bronze Age collapse question): fluent and well
  structured, but it invented scholar names. Retrieval grounding is needed for attributions.
