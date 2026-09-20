#!/usr/bin/env bash
# Run a command as ubuntu in a systemd sandbox: no network, read-only system,
# home hidden except ~/androidlm. Usage: sbx.sh [MEM_MB|none] cmd...
MEM=$1; shift
PROPS=(-p User=ubuntu -p PrivateNetwork=yes -p ProtectSystem=strict -p ProtectHome=tmpfs
       -p BindPaths=/home/ubuntu/androidlm -p PrivateTmp=yes -p NoNewPrivileges=yes
       -p WorkingDirectory=/home/ubuntu/androidlm -p Environment=HOME=/home/ubuntu/androidlm)
[ "$MEM" != none ] && PROPS+=(-p MemoryMax=${MEM}M -p MemorySwapMax=0)
exec sudo systemd-run --wait --pipe --quiet --collect "${PROPS[@]}" "$@"
