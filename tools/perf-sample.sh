#!/usr/bin/env bash
#
# perf-sample.sh — device-side perf sampler for LocaLL email analysis.
#
# Samples thermal / CPU / battery from the phone while you drive an email-analysis
# flow (KB import, research Q&A, inbox triage, thread summary). Pair it with the
# in-app EmailPerf logs: run this, then trigger the flow in the app.
#
# Usage:
#   tools/perf-sample.sh [-p pkg] [-i interval_sec] [-d duration_sec] [-o out.csv]
#
# Defaults: pkg=com.example.locall interval=2s duration=180s out=perf-YYYY... (stdout tee)
#
# Columns: t_s, batt_temp_c, cpu_max_c, gpu_max_c, big_cpu_c, thermal_status,
#          app_cpu_pct, app_cpu_ms, cpu_online, big_freq_mhz
#
set -euo pipefail

PKG="com.example.locall"
INTERVAL=2
DURATION=180
OUT=""
SERIAL=""

while getopts "p:i:d:o:s:h" opt; do
  case "$opt" in
    p) PKG="$OPTARG" ;;
    i) INTERVAL="$OPTARG" ;;
    d) DURATION="$OPTARG" ;;
    o) OUT="$OPTARG" ;;
    s) SERIAL="$OPTARG" ;;
    h) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "bad option" >&2; exit 1 ;;
  esac
done

ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

# Resolve the app pid once; re-resolve each loop in case it restarts.
pidof_app() { "${ADB[@]}" shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }

# One remote script reads everything in a single adb round-trip per sample.
# thermal zones: pick the hottest cpu* and gpu* zone, plus a representative big core.
remote_sample='
pid="__PID__"
# battery temp (deci-degC)
bt=$(dumpsys battery 2>/dev/null | sed -n "s/.*temperature: //p" | head -1)
# thermal zones
cpu_max=0; gpu_max=0; big=0
for z in /sys/class/thermal/thermal_zone*; do
  ty=$(cat "$z/type" 2>/dev/null); v=$(cat "$z/temp" 2>/dev/null)
  case "$ty" in
    cpu*|cpuss*) [ "${v:-0}" -gt "$cpu_max" ] && cpu_max=$v
                 case "$ty" in cpu-0-*) [ "${v:-0}" -gt "$big" ] && big=$v ;; esac ;;
    gpu*|gpuss*) [ "${v:-0}" -gt "$gpu_max" ] && gpu_max=$v ;;
  esac
done
# thermal headroom (0..1, 1=throttle imminent); forecast 10s. Matches in-app getThermalHeadroom.
# Output looks like: "Headroom in 10 seconds: 0.977"
hdr=$(cmd thermalservice headroom 10 2>/dev/null | tr -d "\n" | awk "{print \$NF}")
# thermal status int: 0=NONE 1=LIGHT 2=MODERATE 3=SEVERE 4=CRITICAL 5=EMERGENCY 6=SHUTDOWN
st=$(dumpsys thermalservice 2>/dev/null | sed -n "s/.*Thermal Status: //p" | head -1)
ts="${hdr}#${st}"
# app cpu%: toybox top single sample for the pid
acpu=""
if [ -n "$pid" ]; then
  acpu=$(top -b -n 1 -p "$pid" -o "%CPU" 2>/dev/null | tail -1 | awk "{print \$1}")
  # cumulative process cpu time (jiffies -> ms assuming 100Hz)
  ut=$(awk "{print \$14+\$15+\$16+\$17}" /proc/$pid/stat 2>/dev/null)
fi
online=$(cat /sys/devices/system/cpu/online 2>/dev/null)
# big-cluster current freq (cpu7 is typically prime/big on this SoC)
bf=$(cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq 2>/dev/null)
echo "${bt}|${cpu_max}|${gpu_max}|${big}|${ts}|${acpu}|${ut}|${online}|${bf}"
'

header="t_s,batt_temp_c,cpu_max_c,gpu_max_c,big_cpu_c,thermal_hdr,thermal_status,app_cpu_pct,app_cpu_ms,cpu_online,big_freq_mhz"
emit() { if [ -n "$OUT" ]; then echo "$1" | tee -a "$OUT"; else echo "$1"; fi; }
[ -n "$OUT" ] && : > "$OUT"
emit "$header"

start=$(date +%s)
while :; do
  now=$(date +%s); el=$((now - start))
  if [ "$el" -ge "$DURATION" ]; then break; fi
  pid=$(pidof_app || true)
  script="${remote_sample/__PID__/$pid}"
  raw=$("${ADB[@]}" shell "$script" 2>/dev/null | tr -d '\r')
  IFS='|' read -r bt cmax gmax big ts acpu ut online bf <<<"$raw"
  # convert deci/milli to degrees
  f1() { awk -v v="${1:-0}" 'BEGIN{ if(v=="")v=0; if(v>1000) printf "%.1f", v/1000; else printf "%.1f", v/10 }'; }
  battc=$(f1 "${bt:-0}")
  # thermal zone temps are milli-degC
  cpuc=$(awk -v v="${cmax:-0}" 'BEGIN{printf "%.1f", v/1000}')
  gpuc=$(awk -v v="${gmax:-0}" 'BEGIN{printf "%.1f", v/1000}')
  bigc=$(awk -v v="${big:-0}" 'BEGIN{printf "%.1f", v/1000}')
  bfm=$(awk -v v="${bf:-0}" 'BEGIN{printf "%d", v/1000}')
  utms=$(awk -v v="${ut:-0}" 'BEGIN{printf "%d", v*10}')  # 100Hz jiffies -> ms
  hdr="${ts%%#*}"; stat="${ts##*#}"
  emit "${el},${battc},${cpuc},${gpuc},${bigc},${hdr:-NA},${stat:-NA},${acpu:-NA},${utms},${online:-NA},${bfm}"
  sleep "$INTERVAL"
done

echo "# done: ${DURATION}s sampled every ${INTERVAL}s${OUT:+ -> $OUT}" >&2
