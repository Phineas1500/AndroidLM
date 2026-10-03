#!/usr/bin/env python3
"""Group a `simpleperf report --sort dso,symbol` listing into the speed-search note's categories."""
import re, sys
cats = [
    ('dense', r'q5k_u_r8|q6k_u_r8|iqk_unpack_dense|gemm_q5_K|gemm_q6_K|gemv_q5_K|gemv_q6_K|forward_mul_mat<|repack'),
    ('experts', r'q8_k_r8|convert_to_q8_k_r8|DequantizerIQ|iq4_xs|iq2_xs|iq3_xxs|iqk_moe|mul_mat_id|quantize_row_q8_K'),
    ('threads/barriers', r'graph_compute_thread|graph_compute_secondary|ggml_barrier'),
    ('attention', r'flash_attn'),
    ('gated delta net + conv', r'gated_delta|ssm_conv'),
    ('other', r'.'),
]
tot = {c: 0.0 for c, _ in cats}
for line in open(sys.argv[1]):
    m = re.match(r'\s*([\d.]+)%\s+\S+\s+(.*)', line)
    if not m:
        continue
    pct, sym = float(m.group(1)), m.group(2)
    for c, rx in cats:
        if re.search(rx, sym):
            tot[c] += pct
            break
cpu = float(sys.argv[2]) if len(sys.argv) > 2 else None
for c, _ in cats:
    print(f'{c:24s} {tot[c]:5.1f}%' + (f'  {tot[c] * cpu / 100:5.1f} cpu-s' if cpu else ''))
print(f'{"listed":24s} {sum(tot.values()):5.1f}%')
