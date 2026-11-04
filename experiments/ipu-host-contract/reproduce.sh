#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p results
cc -std=c11 -O2 -Wall -Wextra -Werror -Wpedantic -Isrc -c src/ic_ipu_candidate.c -o results/default.o
if nm results/default.o | grep -q 'ic_ipu_open_test'; then echo 'ERROR host opener leaked into default build'; exit 1; fi
if nm -u results/default.o | grep -Eq 'ioctl|open|mmap|vpu_|ic_pixels_rgb565'; then echo 'ERROR default contains physical backend or CPU converter'; exit 1; fi
cc -std=c11 -O2 -Wall -Wextra -Werror -Wpedantic -g -fsanitize=undefined -DIC_IPU_HOST_CONTRACT_TEST -Isrc src/ic_ipu_candidate.c tests/test_ipu_candidate.c -o results/test_ipu
./results/test_ipu
cc -std=c11 -O2 -Wall -Wextra -Werror -Wpedantic -Wno-array-parameter -g -fsanitize=undefined -DIC_IPU_HOST_CONTRACT_TEST -Isrc src/ic_ipu_candidate.c src/ic_protocol.c tests/test_pipe_bridge.c -o results/test_pipe_bridge
./results/test_pipe_bridge
