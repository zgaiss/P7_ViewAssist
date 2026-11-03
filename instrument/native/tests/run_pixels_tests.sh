#!/bin/sh
# Offline only. Build products stay in an isolated host temporary directory.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
native=$(CDPATH= cd -- "$here/.." && pwd)
build=$(mktemp -d "${TMPDIR:-/tmp}/p7-pixels-tests.XXXXXXXX")
# Resolve and validate the exact temporary deletion target before installing trap.
case "$build" in
  "${TMPDIR:-/tmp}"/p7-pixels-tests.*) ;;
  *) echo "unexpected test temporary directory" >&2; exit 1 ;;
esac
cleanup() {
  test -n "${build:-}" && test -d "$build" || return
  case "$build" in "${TMPDIR:-/tmp}"/p7-pixels-tests.*) rm -rf -- "$build" ;; esac
}
trap cleanup EXIT HUP INT TERM
# Default UBSan works in the macOS host sandbox. Optional stronger run:
# IC_TEST_SANITIZERS=address,undefined sh run_pixels_tests.sh
# ASan startup may block under restricted macOS sandboxes (before main).
# IC_TEST_SANITIZERS=none explicitly disables instrumentation.
sanitizers=${IC_TEST_SANITIZERS:-undefined}
if test "$sanitizers" = none; then
  set --
else
  set -- "-fsanitize=$sanitizers" -fno-omit-frame-pointer
fi
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -pedantic -O1 -g "$@" \
  -I"$native" "$native/ic_pixels.c" "$here/test_pixels.c" -o "$build/test_pixels"
"$build/test_pixels"
# Host display compilation is a non-operational ENOTSUP stub, never a device test.
"${CC:-cc}" -std=c11 -Wall -Wextra -Werror -pedantic -c \
  -I"$native" "$native/ic_display.c" -o "$build/ic_display_host.o"
