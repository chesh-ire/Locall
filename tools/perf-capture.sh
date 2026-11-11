#!/usr/bin/env bash
#
# perf-capture.sh — one-shot capture of an email-analysis run.
#
# Runs TWO recorders in parallel for DURATION seconds, into an output dir:
#   1. logcat filtered to the in-app EmailPerf tracer (per-inference wall/cpu/par/thermal/tokens)
#   2. tools/perf-sample.sh device sampler (battery/cpu/gpu temp, thermal status, app cpu%)
#
# Workflow:
#   1. Let the phone cool to thermal_status 0 (NONE) first — otherwise inference is
#      throttled to 1 thread and numbers are not representative.
#   2. Start this script.
#   3. Immediately trigger the flow in the app (e.g. Research > Import Gmail, or ask a
#      question, or open the Today briefing).
#
# Usage: tools/perf-capture.sh [-s serial] [-d duration_sec] [-o out_dir]
#
set -euo pipefail

SERIAL=""; DURATION=300; OUTDIR="perf-capture"
while getopts "s:d:o:h" opt; do
  case "$opt" in
    s) SERIAL="$OPTARG" ;;
    d) DURATION="$OPTARG" ;;
    o) OUTDIR="$OPTARG" ;;
    h) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "bad option" >&2; exit 1 ;;
  esac
done

ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUTDIR"
LOGF="$OUTDIR/emailperf.log"
CSVF="$OUTDIR/sample.csv"

echo "capturing ${DURATION}s -> $OUTDIR/  (trigger the flow in the app NOW)" >&2

# Clear old logs so the capture is clean, then stream only the EmailPerf tag.
"${ADB[@]}" logcat -c || true
"${ADB[@]}" logcat -v time -s EmailPerf:V > "$LOGF" &
LOGPID=$!

# Sampler writes its own CSV.
sampler_args=(-i 2 -d "$DURATION" -o "$CSVF")
[ -n "$SERIAL" ] && sampler_args+=(-s "$SERIAL")
bash "$HERE/perf-sample.sh" "${sampler_args[@]}" >/dev/null 2>&1 || true

# Sampler finished (duration elapsed) -> stop logcat.
kill "$LOGPID" 2>/dev/null || true
wait "$LOGPID" 2>/dev/null || true

echo "" >&2
echo "=== EmailPerf inference log (last 40 lines) ===" >&2
tail -40 "$LOGF" || true
echo "" >&2
echo "=== device sample peaks ===" >&2
awk -F, 'NR>1{if($3>cpu)cpu=$3; if($4>gpu)gpu=$4; if($2>bat)bat=$2;
             if($6!="NA"&&$6+0>hdr)hdr=$6; if($7!="NA"&&$7+0>st)st=$7}
         END{printf "  peak cpu=%s°C gpu=%s°C batt=%s°C  max headroom=%s  max thermal_status=%s\n",cpu,gpu,bat,hdr,st}' "$CSVF" || true
echo "  full data: $LOGF  $CSVF" >&2
