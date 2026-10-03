#!/system/bin/sh
# One session over mtp_reqs.jsonl (a 300-token answer-first draft, then a 1,216-token sources answer
# with 200 written) on the model with the MTP block, the app's flags plus "$@". Args: <label> [flags...]
label=$1; shift
bin=/data/local/tmp/bmoe-prefix; M=/data/local/tmp/bmoe-mtp/Qwen3.6-35B-A3B-UD-Q2_K_XL-mtp.gguf; D=/data/local/tmp/bench_tune
skin() { dumpsys thermalservice | sed -n "/Current temperatures/,\$p" | grep -m1 "mName=VIRTUAL-SKIN," | sed -E "s/.*mValue=([0-9.]+).*/\1/"; }
cd $bin
T0=$(skin)
LD_LIBRARY_PATH=$bin BMOE_REPACK=1 BMOE_CPUMASK=1f0 BMOE_NICE=-16 timeout 1500 ./libbmoe-cli.so -m $M -t 5 -c 4096 \
  --ubatch 1280 --chatml --session --moe-stream --cache-mb 5000 --io-threads 4 --overlap --dense-weights ahwb "$@" \
  < $D/mtp_reqs.jsonl > $D/mtp_$label.out 2> $D/mtp_$label.err &
pid=$!; low=99999999
while kill -0 $pid 2>/dev/null; do a=$(grep MemAvailable /proc/meminfo | awk '{print $2}'); [ "$a" -lt "$low" ] && low=$a; sleep 1; done
wait $pid; rc=$?
echo "=== $label rc=$rc skin=$T0->$(skin) lowest_MemAvailable=$((low/1024)) MiB flags: $*"
grep -E "^BMOE_DONE" $D/mtp_$label.out | while read -r l; do
  echo "$l" | sed -E "s/\"text\":\"[^\"]*\",?//" | tr "," "\n" | grep -E "\"(id|tok_s|prefill_s|n_gen|cache_hit_pct)\"" | tr "\n" " "; echo
done
grep -iE "mtp:|accepted|nextn|refus|error" $D/mtp_$label.err | head -6
