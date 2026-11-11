#!/usr/bin/env bash
#
# chunkperf-report.sh — fetch the 105-Chunk LLM Performance Analysis results off the device.
#
# The in-app runner (Perf Lab > 105-Chunk tab) writes reports to the app's private files dir
# and also logs them under the ChunkPerf logcat tag. This script pulls the saved report files
# via `run-as` (works on debuggable builds without root).
#
# What it fetches from <files>/perf/ :
#   chunkperf-latest-baseline.txt    — full report of the latest completed baseline run
#   chunkperf-latest-optimized.txt   — full report of the latest completed optimized run
#   chunkperf-compare.txt            — before/after comparison (after you tap "Compare")
#   chunkperf-<runId>.txt            — every individual run's report
#
# Usage: tools/chunkperf-report.sh [-s serial] [-o out_dir]
#
set -euo pipefail

SERIAL=""; OUTDIR="chunkperf-report"; PKG="com.example.locall"
while getopts "s:o:h" opt; do
  case "$opt" in
    s) SERIAL="$OPTARG" ;;
    o) OUTDIR="$OPTARG" ;;
    h) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "bad option" >&2; exit 1 ;;
  esac
done

ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
mkdir -p "$OUTDIR"

echo "listing $PKG files/perf/ ..." >&2
files="$("${ADB[@]}" exec-out run-as "$PKG" ls files/perf 2>/dev/null || true)"
if [ -z "$files" ]; then
  echo "no report files found yet — run a 105-Chunk session in the app first." >&2
  echo "(you can also watch live: adb logcat -s ChunkPerf:V)" >&2
  exit 0
fi

for f in $files; do
  case "$f" in
    *.txt)
      "${ADB[@]}" exec-out run-as "$PKG" cat "files/perf/$f" > "$OUTDIR/$f" 2>/dev/null || true
      echo "pulled $OUTDIR/$f" >&2
      ;;
  esac
done

echo "" >&2
for pref in chunkperf-latest-baseline chunkperf-latest-optimized chunkperf-compare; do
  if [ -s "$OUTDIR/$pref.txt" ]; then
    echo "=== $pref.txt ===" >&2
    cat "$OUTDIR/$pref.txt"
    echo "" >&2
  fi
done
