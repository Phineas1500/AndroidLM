#!/system/bin/sh
# Profile the engine while it reads the 1,233-token sources-first prompt: the app's flags, the
# shipped engine (bmoe-prefix). simpleperf samples the engine for 45 s from the moment it is ready.
bin=/data/local/tmp/bmoe-prefix; M=/data/local/tmp/bmoe-hold/Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf; D=/data/local/tmp/bench_tune
cd $bin
LD_LIBRARY_PATH=$bin BMOE_REPACK=1 BMOE_CPUMASK=1f0 BMOE_NICE=-16 ./libbmoe-cli.so -m $M -t 5 -c 4096 \
  --ubatch 1280 --chatml --session --moe-stream --cache-mb 5000 --io-threads 4 --overlap --dense-weights ahwb \
  < $D/prof_req.jsonl > $D/prof.out 2> $D/prof.err &
pid=$!
while kill -0 $pid 2>/dev/null; do grep -q "BMOE_READY" $D/prof.out && break; sleep 0.2; done
simpleperf record -p $pid --duration 45 -f 2000 -o $D/prefill.perf > $D/simpleperf.log 2>&1
wait $pid
grep -E "^BMOE_DONE" $D/prof.out | sed -E "s/\"text\":\"[^\"]*\",?//" | tr "," "\n" | grep -E "\"(prefill_s|n_prompt|prompt_tokens|io_|flash|cache_hit)" | tr "\n" " "; echo
simpleperf report -i $D/prefill.perf --sort dso,symbol --percent-limit 0.4 2>/dev/null | head -60
