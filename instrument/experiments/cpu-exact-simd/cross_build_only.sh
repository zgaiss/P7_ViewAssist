#!/bin/sh
# Compile/link only. Deliberately never executes or deploys an ARM program.
set -eu
: "${ARM_CC:?Set ARM_CC to a trusted ARMEL compiler command (without options)}"
: "${ARM_SYSROOT:?Set ARM_SYSROOT to a verified target-compatible glibc sysroot}"
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
N="$ROOT/../../native"
P="$ROOT/prototype"
B="$ROOT/arm-build"
mkdir -p "$B"
# For Clang set ARM_TARGET=--target=arm-linux-gnueabi. GCC needs no ARM_TARGET.
COMMON="${ARM_TARGET:-} --sysroot=$ARM_SYSROOT -march=armv7-a -mfloat-abi=soft -std=c11 -O2 -Wall -Wextra -Werror"
"$ARM_CC" $COMMON -I "$N" -Dic_pixels_rgb565=ic_pixels_rgb565_reference -c "$N/ic_pixels.c" -o "$B/reference.o"
"$ARM_CC" $COMMON -I "$P" -c "$P/row_scalar.c" -o "$B/row_scalar.o"
"$ARM_CC" $COMMON -mfloat-abi=softfp -mfpu=neon -I "$P" -c "$P/row_neon.c" -o "$B/row_neon.o"
"$ARM_CC" $COMMON -DP7_HAVE_NEON_OBJECT=1 -I "$N" -I "$P" -c "$P/ic_pixels_gather.c" -o "$B/gather.o"
"$ARM_CC" $COMMON -DP7_TEST_NEON=1 -I "$N" -I "$P" "$P/test_exact.c" "$B/reference.o" "$B/row_scalar.o" "$B/row_neon.o" "$B/gather.o" -o "$B/test_exact_armel"
"$ARM_CC" $COMMON -DP7_HAVE_NEON_OBJECT=1 -I "$N" -I "$P" "$P/bench_exact.c" "$B/reference.o" "$B/row_scalar.o" "$B/row_neon.o" "$B/gather.o" -o "$B/bench_exact_armel"
printf '%s\n' 'COMPILE/LINK COMPLETE ONLY. No ARM execution, hardware use or deployment.'
