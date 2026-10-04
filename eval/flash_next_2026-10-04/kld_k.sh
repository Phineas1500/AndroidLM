#!/usr/bin/env bash
# Flash-Next: KL divergence of the next-token distributions with fewer experts per token (default
# 10 of 512), and a rounding-only control (the generic Q2_0 kernel), Mac CPU, 16 x 512 tokens.
cd /Volumes/T7/AndroidLM-dev/work/fnext
P=/Volumes/T7/AndroidLM-dev/androidlm-tools/BigMoeOnEdge/third_party/llama.cpp/build-mac/bin/llama-perplexity
A="-m /Volumes/T7/AndroidLM-dev/models/flash-next/Qwen3.8-Flash-Next-GSQ-RCO-Q2_0-00001-of-00002.gguf -f /Volumes/T7/AndroidLM-dev/work/fnext/kld_text.txt -c 512 --chunks 16 -t 8 -b 512 -ub 512"
$P $A --kl-divergence-base /Volumes/T7/AndroidLM-dev/work/fnext/kld_base_k10.bin > /Volumes/T7/AndroidLM-dev/work/fnext/kld_k10_base.log 2>&1; echo "base rc=$?"
GGML_IQK_Q2=0 $P $A --kl-divergence-base /Volumes/T7/AndroidLM-dev/work/fnext/kld_base_k10.bin --kl-divergence > /Volumes/T7/AndroidLM-dev/work/fnext/kld_ctrl.log 2>&1; echo "ctrl rc=$?"
for k in 8 6 4; do
  $P $A --override-kv qwen4exp.expert_used_count=int:$k --kl-divergence-base /Volumes/T7/AndroidLM-dev/work/fnext/kld_base_k10.bin --kl-divergence > /Volumes/T7/AndroidLM-dev/work/fnext/kld_k$k.log 2>&1; echo "k=$k rc=$?"
done
echo KLDK_DONE
