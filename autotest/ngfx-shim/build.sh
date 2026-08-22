#!/usr/bin/env bash
# Build the Nsight GPU-Trace shim. See vs_ngfx_shim.c for why it exists.
set -euo pipefail
cd "$(dirname "$0")"
SDK="${NGFX_SDK:-/opt/nsight-graphics/latest/SDKs/NsightGraphicsSDK/0.9.2/include}"
[ -d "$SDK" ] || { echo "Nsight SDK headers not found at $SDK" >&2; exit 2; }
gcc -shared -fPIC -O2 -I"$SDK" -o libvsngfx.so vs_ngfx_shim.c
echo "built $(pwd)/libvsngfx.so"
nm -D libvsngfx.so | grep " T vs_ngfx" | awk '{print "  " $3}'
