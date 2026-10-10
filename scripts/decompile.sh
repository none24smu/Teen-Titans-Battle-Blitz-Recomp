#!/usr/bin/env bash
# Decompiles everything in original/ into src/.
# Usage: scripts/decompile.sh [name ...]   (default: everything in original/)
#
# Flash: original/<name>.swf ->
#   src/<name>/swf.xml   complete, lossless description of every tag (edit to change
#                        timelines, placements, shapes, sounds...)
#   src/<name>/scripts/  ActionScript source, compiled back in by build.sh
#   src/<name>/pcode/    ActionScript bytecode listing (reference only)
#   src/<name>/assets/   shapes (SVG), sprites/frames (PNG), sounds, images, fonts, texts
#   src/<name>/xfl/      Adobe Animate project (open the .xfl, publish to HTML5/AIR)
#
# Director/Shockwave: original/<name>/ (folder of .dcr/.dir/.cct/.cxt/.dxr/.cst) ->
#   src/<name>/movies/<movie>.dir, <cast>.cst   unprotected, editable in Director 8.5+
#   src/<name>/movies/<movie>/casts/<cast>/*.ls  decompiled Lingo source
#   src/<name>/movies/<movie>/casts/<cast>/*.lasm Lingo bytecode listing (reference only)
source "$(dirname "$0")/_common.sh"

decompile_swf() {
  local name="$1" swf="$ORIGINAL_DIR/$1.swf" out="$SRC_DIR/$1"
  echo "== $name (Flash)"
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
}

decompile_director() {
  local name="$1" in="$ORIGINAL_DIR/$1" out="$SRC_DIR/$1/movies"
  echo "== $name (Director)"
  mkdir -p "$out"
  local f base ext
  for f in "$in"/*; do
    [[ -f "$f" ]] || continue
    ext="$(echo "${f##*.}" | tr "[:upper:]" "[:lower:]")"
    [[ " $DIRECTOR_EXTS " == *" $ext "* ]] || continue
    base="$(basename "$f")"; base="${base%.*}"
    echo "  $(basename "$f")"
    projectorrays decompile "$f" -o "$out" --dump-scripts >/dev/null
    # Already-unprotected inputs come out as <base>_decompiled.<ext>.
    for g in "$out/${base}_decompiled".*; do
      if [[ -e "$g" ]]; then mv -f "$g" "$out/$base.${g##*.}"; fi
    done
  done

  # Art, sound, palettes, text, labels and member metadata in open formats.
  local assets="$SRC_DIR/$name/assets"
  for f in "$out"/*.dir "$out"/*.cst; do
    [[ -f "$f" ]] || continue
    base="$(basename "$f")"; base="${base%.*}"
    echo "  assets: $base"
    director_extract "$f" "$assets/$base"
  done
}


did=0
for name in $(swf_names "$@"); do
  [[ -f "$ORIGINAL_DIR/$name.swf" ]] || continue
  if [[ -d "$SRC_DIR/$name" ]]; then
    echo "src/$name already exists; delete it first to re-decompile (it may hold your edits)." >&2
    exit 1
  fi
  decompile_swf "$name"
  did=1
done
for name in $(director_names "$@"); do
  [[ -d "$ORIGINAL_DIR/$name" ]] || continue
  if [[ -d "$SRC_DIR/$name" ]]; then
    echo "src/$name already exists; delete it first to re-decompile (it may hold your edits)." >&2
    exit 1
  fi
  decompile_director "$name"
  did=1
done

[[ $did -eq 1 ]] || { echo "Nothing to decompile in original/." >&2; exit 1; }
echo "Done."
