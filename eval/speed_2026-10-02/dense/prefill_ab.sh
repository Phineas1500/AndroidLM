#!/system/bin/sh
# The 1,216-token sources-first prompt read once (8 tokens written), the app's flags. Args: <label> <bin dir> [env...]
label=$1; bin=$2; shift 2
M=/data/local/tmp/bmoe-hold/Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf; D=/data/local/tmp/bench_tune
skin() { dumpsys thermalservice | sed -n "/Current temperatures/,\$p" | grep -m1 "mName=VIRTUAL-SKIN," | sed -E "s/.*mValue=([0-9.]+).*/\1/"; }
cd $bin
T0=$(skin)
env "$@" LD_LIBRARY_PATH=$bin BMOE_REPACK=1 BMOE_CPUMASK=1f0 BMOE_NICE=-16 ./libbmoe-cli.so -m $M -t 5 -c 4096 \
  --ubatch 1280 --chatml --session --moe-stream --cache-mb 5000 --io-threads 4 --overlap --dense-weights ahwb \
  < $D/prof_req.jsonl > $D/ab_$label.out 2> $D/ab_$label.err
echo "=== $label bin=$bin $* skin=$T0->$(skin)"
grep -E "^BMOE_DONE" $D/ab_$label.out | sed -E "s/\"text\":\"[^\"]*\",?//" | tr "," "\n" | grep -E "\"(prefill_s|prefill_cpu_s|prefill_io_s|n_prompt)\"" | tr "\n" " "; echo
grep -E "^BMOE_DONE" $D/ab_$label.out | sed -E 's/.*"text":"([^"]{0,60}).*/text: \1/'
