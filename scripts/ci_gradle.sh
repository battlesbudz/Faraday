#!/usr/bin/env bash
# Run the unchanged release tasks with bounded, non-secret performance evidence.
set -euo pipefail
phase="$1"
shift
diagnostics="artifact/build-performance/$phase"
mkdir -p "$diagnostics"
{
  date -u
  nproc
  free -m
} > "$diagnostics/host.txt"
vmstat -w 5 > "$diagnostics/vmstat.txt" &
vmstat_pid=$!
(
  sample=0
  while true; do
    date -u
    ps -C java -o pid,pcpu,pmem,rss,etime,comm || true
    # Capture the compiler's actual busy stack if a future compile stalls.
    if (( sample % 6 == 0 )) && command -v jps >/dev/null && command -v jcmd >/dev/null; then
      while read -r compiler_pid; do
        timeout 10s jcmd "$compiler_pid" Thread.print > "$diagnostics/kotlin-threads-$compiler_pid-$sample.txt" 2>&1 || true
        timeout 10s jcmd "$compiler_pid" GC.heap_info > "$diagnostics/kotlin-heap-$compiler_pid-$sample.txt" 2>&1 || true
      done < <(jps -l | awk '/org.jetbrains.kotlin.daemon.KotlinCompileDaemon/ {print $1}')
    fi
    sample=$((sample + 1))
    sleep 30
  done
) > "$diagnostics/java-processes.txt" &
process_pid=$!
cleanup() {
  kill "$vmstat_pid" "$process_pid" 2>/dev/null || true
  wait "$vmstat_pid" "$process_pid" 2>/dev/null || true
}
trap cleanup EXIT
/usr/bin/time -v -o "$diagnostics/gradle-time.txt" \
  gradle --no-daemon --console=plain --profile "$@"
