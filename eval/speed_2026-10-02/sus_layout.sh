#!/system/bin/sh
# Three research-sized requests back to back in one session (1,216-token prompt, 200 tokens written,
# each from a cleared context), the shipped engine and the app's flags except the thread layout.
# Args: <label> <cpumask hex> <threads>. Prints prompt time and writing speed per request, skin before/after.
label=$1; mask=$2; th=$3
bin=/data/local/tmp/bmoe-prefix; M=/data/local/tmp/bmoe-hold/Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf; D=/data/local/tmp/bench_tune
skin() { dumpsys thermalservice | sed -n "/Current temperatures/,\$p" | grep -m1 "mName=VIRTUAL-SKIN," | sed -E "s/.*mValue=([0-9.]+).*/\1/"; }
cd $bin
T0=$(skin); t0=$(date +%s)
LD_LIBRARY_PATH=$bin BMOE_REPACK=1 BMOE_CPUMASK=$mask BMOE_NICE=-16 timeout 1500 ./libbmoe-cli.so -m $M -t $th -c 4096 \
  --ubatch 1280 --chatml --session --moe-stream --cache-mb 5000 --io-threads 4 --overlap --dense-weights ahwb \
  < $D/sus3.jsonl > $D/sus_$label.out 2> $D/sus_$label.err
rc=$?
T1=$(skin)
echo "=== $label mask=$mask t=$th rc=$rc skin=$T0->$T1 wall=$(( $(date +%s) - t0 ))s"
grep -E "^BMOE_DONE" $D/sus_$label.out | while read -r l; do
  echo "$l" | sed -E "s/\"text\":\"[^\"]*\",?//" | tr "," "\n" | grep -E "\"(id|tok_s|prefill_s|n_gen)\"" | tr "\n" " "; echo
done
