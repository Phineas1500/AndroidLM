#!/usr/bin/env bash
# Run the eval set against one or more GGUF models, one llama-server at a time.
# Usage: eval_models.sh TAG=model.gguf [TAG=model.gguf ...]
# Set RAG_DB=path/to/wiki.db to answer through scripts/rag.py; RAG_MODE=plan|bm25|none picks its
# retrieval mode (none = closed-book with the same answer prompt).
# This measures answer quality, not streaming speed.
set -euo pipefail

ROOT=${ANDROIDLM_ROOT:-$HOME/androidlm}
PORT=8091
QUESTIONS=${QUESTIONS:-$ROOT/eval/questions_v0.jsonl}

for pair in "$@"; do
  tag=${pair%%=*}
  model=${pair#*=}
  echo "=== $tag ($model)"
  # Hard memory cap: the VM is shared and has no swap, so an oversized server must be
  # killed by its cgroup instead of thrashing the host. --cache-ram 0 disables the
  # server's host-memory prompt cache, which otherwise grows by GBs across requests.
  sudo systemd-run --scope --quiet --uid="$(id -u)" --gid="$(id -g)" \
    -p MemoryMax="${MEM_MAX:-15G}" -p MemorySwapMax=0 \
    "$ROOT/llama.cpp/build/bin/llama-server" -m "$model" -t ${THREADS:-4} -c 4096 -np 1 --jinja \
    --cache-ram 0 --chat-template-kwargs '{"enable_thinking":false}' \
    --host 127.0.0.1 --port $PORT > "$ROOT/logs/server_$tag.log" 2>&1 &
  pid=$!
  trap 'sudo kill $pid 2>/dev/null || true' EXIT
  until curl -sf "http://127.0.0.1:$PORT/health" >/dev/null; do
    sudo kill -0 $pid 2>/dev/null || { echo "server died, see logs/server_$tag.log"; exit 1; }
    sleep 3
  done
  if [ -n "${RAG_DB:-}" ]; then
    "$ROOT/venv/bin/python" "$ROOT/scripts/rag.py" --db "$RAG_DB" --mode "${RAG_MODE:-plan}" \
      --questions "$QUESTIONS" --url "http://127.0.0.1:$PORT" \
      --out "$ROOT/eval/answers_${tag}_${RAG_MODE:-plan}${RUN_SUFFIX:-}.jsonl"
  else
    python3 "$ROOT/scripts/run_eval.py" --questions "$QUESTIONS" \
      --out "$ROOT/eval/answers_$tag.jsonl" --url "http://127.0.0.1:$PORT"
  fi
  sudo kill $pid; wait $pid 2>/dev/null || true
  trap - EXIT
done
echo EVAL_DONE
