#!/system/bin/sh
# Runs ON THE PHONE (adb push to /data/local/tmp) next to phone_session.sh: every 5 s the uptime,
# the three clusters' clocks, /proc/stat's per-core times and the memory counters that show swap and
# refaults; every 10 s also every thread's /proc stat line of the app and of its child processes
# (the engine) and the skin temperature. scripts/heat_report.py reads the log.
# usage (phone): sh heat_sampler.sh <log>     stop: pkill -f heat_sampler.sh
PKG=io.github.phineas1500.androidlm.dev
OUT=$1
n=0
while true; do
  {
    echo "T $(date +%s) $(cut -d' ' -f1 /proc/uptime)"
    echo "F $(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq) $(cat /sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq) $(cat /sys/devices/system/cpu/cpufreq/policy8/scaling_cur_freq)"
    grep '^cpu' /proc/stat
    grep -E '^(pswpin|pswpout|pgmajfault|workingset_refault_file|workingset_refault_anon) ' /proc/vmstat | tr '\n' ' ' | sed 's/^/V /'; echo
    if [ $((n % 2)) = 0 ]; then
      echo "S $(dumpsys thermalservice | sed -n '/Current temperatures/,$p' | grep -m1 'mName=VIRTUAL-SKIN,' | sed -E 's/.*mValue=([0-9.]+).*/\1/')"
      app=$(pidof -s $PKG)
      if [ -n "$app" ]; then
        for p in $app $(ps -A -o PID=,PPID= | awk -v a="$app" '$2==a{print $1}'); do
          echo "P $p $(tr '\0' ' ' < /proc/$p/cmdline | cut -c1-60)"
          cat /proc/$p/task/*/stat 2>/dev/null | sed 's/^/X /'
        done
      fi
    fi
  } >> "$OUT"
  n=$((n + 1))
  sleep 5
done
