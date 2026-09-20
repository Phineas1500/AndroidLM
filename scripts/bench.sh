#!/usr/bin/env bash
# Phone-proxy benchmark for a GGUF model on the Oracle A1 (4x Neoverse-N1).
# Usage: bench.sh <model.gguf> [mem_limit_mb|none] [extra llama-bench args...]
# Runs llama-bench with 4 threads, optionally inside a memory-capped cgroup so
# page cache for mmap'd weights counts against the cap, like a 12GB phone with
# ~8.5GB usable.
set -euo pipefail

MODEL=$1
MEM=${2:-none}
shift $(( $# > 1 ? 2 : 1 ))

ROOT=${ANDROIDLM_ROOT:-$HOME/androidlm}
BENCH=$ROOT/llama.cpp/build/bin/llama-bench
OUT=$ROOT/logs/bench_$(basename "$MODEL" .gguf)_${MEM}_$(date +%Y%m%d_%H%M%S).md

CMD=("$BENCH" -m "$MODEL" -t 4 -p 512 -n 128 -r 2 -o md "$@")

if [ "$MEM" = none ]; then
  "${CMD[@]}" | tee "$OUT"
else
  # drop the page cache so the capped run starts cold, as after a phone reboot
  sync && echo 3 | sudo tee /proc/sys/vm/drop_caches >/dev/null
  sudo systemd-run --scope --quiet --uid="$(id -u)" --gid="$(id -g)" \
    -p MemoryMax="${MEM}M" -p MemorySwapMax=0 \
    "${CMD[@]}" | tee "$OUT"
fi
echo "saved: $OUT"
