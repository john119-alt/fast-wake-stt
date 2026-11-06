#!/usr/bin/env bash
# Wake-word regression tests. Each clip has an expected answer; the script
# prints PASS/FAIL per clip plus timing, and exits non-zero on any failure.
#
# usage: scripts/test.sh [build_dir] [models_dir] [threads]
set -u
cd "$(dirname "$0")/.."
BUILD=${1:-build}; MODELS=${2:-models}; THREADS=${3:-4}
BIN="$BUILD/wake_stt"
M="$MODELS/ggml-tiny.en-q8_0.bin $MODELS/ggml-silero-v5.1.2.bin"
export LD_LIBRARY_PATH="$BUILD/bin${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# clip                 expected wake
CASES="
hey                    YES
wake_question          YES
session                YES
nowake                 no
nearmiss_audrey        no
nearmiss_ordinary      no
jfk                    no
"

fail=0
printf "%-20s %-8s %-8s %-6s %s\n" CLIP EXPECTED GOT RESULT "SPEED / TRANSCRIPT"
while read -r clip want; do
    [ -z "$clip" ] && continue
    out=$("$BIN" $M "test-audio/$clip.wav" "$THREADS" 0 2>/dev/null)
    got=$(sed -n 's/^wake detected : //p' <<<"$out")
    rtf=$(sed -n 's/^stream RTF *: [0-9.]*  (\(.*\))/\1/p' <<<"$out")
    full=$(sed -n 's/^transcript *: //p' <<<"$out")
    text=$(cut -c1-60 <<<"$full")
    # A decoder loop: the same 3 words repeated three times in a row.
    loop=$(grep -ciE '([[:alnum:]]+ [[:alnum:]]+ [[:alnum:]]+)[^[:alnum:]]+\1[^[:alnum:]]+\1' <<<"$full")
    if [ "$got" = "$want" ] && [ "$loop" = 0 ]; then res=PASS; else res=FAIL; fail=1; fi
    [ "$loop" != 0 ] && got="$got+LOOP"
    printf "%-20s %-8s %-8s %-6s %s |%s\n" "$clip" "$want" "${got:-?}" "$res" "$rtf" "$text"
done <<<"$CASES"
exit $fail
