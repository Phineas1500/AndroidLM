#!/system/bin/sh
# Flash-Next engine run with the app's flags. Args: <label> <request file> <simpleperf seconds, 0 = none> [env...]
# Model: /data/local/tmp/bmoe/Qwen3.8-Flash-Next-*-00001-of-00002.gguf. Engine dir: $BIN (default the 1.3.1 engine).
label=$1; req=$2; dur=$3; shift 3
bin=${BIN:-/data/local/tmp/bmoe-dfinal}; D=/data/local/tmp/bench_fn2
M=/data/local/tmp/bmoe/Qwen3.8-Flash-Next-GSQ-RCO-Q2_0-00001-of-00002.gguf
skin() { dumpsys thermalservice | sed -n "/Current temperatures/,\$p" | grep -m1 "mName=VIRTUAL-SKIN," | sed -E "s/.*mValue=([0-9.]+).*/\1/"; }
T0=$(skin)
cd $bin
env "$@" LD_LIBRARY_PATH=$bin BMOE_REPACK=1 BMOE_CPUMASK=1f0 BMOE_NICE=-16 ./libbmoe-cli.so -m $M -t 5 -c 4096 \
  --ubatch ${UB:-1280} --chatml --session --moe-stream --cache-mb ${CACHE:-2000} --io-threads 4 --overlap --dense-weights ahwb $EXTRA \
  < $D/$req > $D/$label.out 2> $D/$label.err &
pid=$!
# lowest MemAvailable while the engine runs
( lo=999999999; while kill -0 $pid 2>/dev/null; do m=$(sed -n "s/^MemAvailable: *\([0-9]*\).*/\1/p" /proc/meminfo); [ "$m" -lt "$lo" ] && lo=$m && echo $lo > $D/$label.memlo; sleep 0.5; done ) &
while kill -0 $pid 2>/dev/null; do grep -q "BMOE_READY" $D/$label.out && break; sleep 0.2; done
if [ "$dur" -gt 0 ]; then simpleperf record -p $pid --duration $dur -f 1000 -o $D/$label.perf > $D/$label.sp.log 2>&1; fi
wait $pid
echo "=== $label bin=$bin $* $EXTRA UB=${UB:-1280} CACHE=${CACHE:-2000} skin=$T0->$(skin) lowest_MemAvailable_MiB=$(( $(cat $D/$label.memlo 2>/dev/null || echo 0) / 1024 ))"
grep -E "^BMOE_DONE" $D/$label.out | sed -E "s/\"(text|reasoning)\":\"[^\"]*\",?//g" | tr "," "\n" | grep -E "\"(tok_s|prefill_s|n_prompt|tokens|cache_hit_pct|compute_s_tok|io_s_tok|stall_s_tok|cpu_s_tok|prefill_cpu_s|prefill_read_mib|prefill_stall_s|read_mib|load_s)\"" | tr "\n" " "; echo
grep -E "^BMOE_DONE" $D/$label.out | sed -E 's/.*"text":"([^"]{0,80}).*/text: \1/'
