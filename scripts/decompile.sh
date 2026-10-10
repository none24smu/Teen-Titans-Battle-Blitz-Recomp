#!/usr/bin/env bash
# Decompiles original/<name>.swf into src/<name>/.
# Usage: scripts/decompile.sh [name ...]   (default: every SWF in original/)
#
#   src/<name>/swf.xml   complete, lossless description of every tag (edit to change
#                        timelines, placements, shapes, sounds...)
#   src/<name>/scripts/  ActionScript source, compiled back in by build.sh
#   src/<name>/pcode/    ActionScript bytecode listing (reference only)
#   src/<name>/assets/   shapes (SVG), sprites/frames (PNG), sounds, images, fonts, texts
#   src/<name>/xfl/      Adobe Animate project (open the .xfl, publish to HTML5/AIR)
source "$(dirname "$0")/_common.sh"

for name in $(swf_names "$@"); do
  swf="$ORIGINAL_DIR/$name.swf"
  out="$SRC_DIR/$name"
  [[ -f "$swf" ]] || { echo "Missing $swf" >&2; exit 1; }

  echo "== $name"
  if [[ -d "$out" ]]; then
    echo "src/$name already exists; delete it first to re-decompile (it may hold your edits)." >&2
    exit 1
  fi
  mkdir -p "$out"

  echo "  swf.xml"
  ffdec -swf2xml "$swf" "$out/swf.xml" >/dev/null

  echo "  scripts + assets"
  ffdec -export all "$out/assets" "$swf" >/dev/null
  if [[ -d "$out/assets/scripts" ]]; then
    mv "$out/assets/scripts" "$out/scripts"
  else
    mkdir -p "$out/scripts"
  fi

  echo "  pcode"
  ffdec -format script:pcode -export script "$out/pcode" "$swf" >/dev/null

  echo "  xfl"
  ffdec -export xfl "$out/xfl" "$swf" >/dev/null

  echo "  done -> src/$name"
done
