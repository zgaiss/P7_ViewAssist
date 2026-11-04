#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
N="$ROOT/../../native"
P="$ROOT/prototype"
B="$ROOT/host-build"
mkdir -p "$B"
CC=${CC:-cc}
FLAGS="-std=c11 -O2 -Wall -Wextra -Werror -fsanitize=address,undefined"
"$CC" $FLAGS -I "$N" -Dic_pixels_rgb565=ic_pixels_rgb565_reference -c "$N/ic_pixels.c" -o "$B/reference.o"
"$CC" $FLAGS -I "$N" -I "$P" "$P/ic_pixels_gather.c" "$P/row_scalar.c" "$P/test_exact.c" "$B/reference.o" -o "$B/test_exact"
ASAN_OPTIONS=detect_leaks=0 "$B/test_exact"
"$CC" $FLAGS -I "$N" -I "$P" "$P/ic_pixels_gather.c" "$P/row_scalar.c" "$N/tests/test_pixels.c" -o "$B/test_original_suite"
ASAN_OPTIONS=detect_leaks=0 "$B/test_original_suite"
