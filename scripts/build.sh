#!/usr/bin/env bash
# Rebuilds build/<name>.swf from src/<name>/swf.xml plus the ActionScript in src/<name>/scripts.
# Usage: scripts/build.sh [name ...]   (default: every SWF in original/)
source "$(dirname "$0")/_common.sh"

mkdir -p "$BUILD_DIR"
for name in $(swf_names "$@"); do
  src="$SRC_DIR/$name"
  [[ -f "$src/swf.xml" ]] || { echo "Missing $src/swf.xml; run scripts/decompile.sh first." >&2; exit 1; }

  echo "== $name"
  tmp="$BUILD_DIR/.$name.noscripts.swf"
  ffdec -xml2swf "$src/swf.xml" "$tmp" >/dev/null
  # importScript recompiles every script file present and leaves the rest of the SWF untouched.
  ffdec -importScript "$tmp" "$BUILD_DIR/$name.swf" "$src/scripts"
  rm -f "$tmp"
  echo "  built -> build/$name.swf"
done
