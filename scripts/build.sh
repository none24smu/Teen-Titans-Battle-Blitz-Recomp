#!/usr/bin/env bash
# Rebuilds everything in src/ into build/.
# Usage: scripts/build.sh [name ...]   (default: everything in original/)
#
# Flash:    src/<name>/swf.xml + src/<name>/scripts -> build/<name>.swf
# Director: src/<name>/movies -> build/<name>/web/: the movies and casts, with their Lingo
#           (src/<name>/movies/<movie>/casts/*/*.ls) compiled back in by scripts/lingo/LingoBuild.java.
#           platforms/web/shockwave.html plays it.
source "$(dirname "$0")/_common.sh"

build_swf() {
  local name="$1" src="$SRC_DIR/$1"
  [[ -f "$src/swf.xml" ]] || { echo "Missing $src/swf.xml; run scripts/decompile.sh first." >&2; exit 1; }
  echo "== $name (Flash)"
  mkdir -p "$BUILD_DIR"
  local tmp="$BUILD_DIR/.$name.noscripts.swf"
  ffdec -xml2swf "$src/swf.xml" "$tmp" >/dev/null
  # importScript recompiles every script file present and leaves the rest of the SWF untouched.
  ffdec -importScript "$tmp" "$BUILD_DIR/$name.swf" "$src/scripts"
  rm -f "$tmp"
  echo "  built -> build/$name.swf"
}

build_director() {
  local name="$1" movies="$SRC_DIR/$1/movies" out="$BUILD_DIR/$1/web"
  [[ -d "$movies" ]] || { echo "Missing $movies; run scripts/decompile.sh first." >&2; exit 1; }
  echo "== $name (Director)"
  rm -rf "$out"
  mkdir -p "$out"
  local f base
  for f in "$movies"/*.dir "$movies"/*.cst; do
    [[ -f "$f" ]] || continue
    base="$(basename "$f")"
    if [[ -d "$movies/${base%.*}" ]]; then
      # compile the Lingo source (with any edits) back into the movie
      director_java LingoBuild "$f" "$movies/${base%.*}" "$out/$base"
    else
      cp "$f" "$out/$base"
    fi
  done
  echo "  built -> build/$name/web/"
}

did=0
for name in $(swf_names "$@"); do
  [[ -f "$ORIGINAL_DIR/$name.swf" ]] || continue
  build_swf "$name"
  did=1
done
for name in $(director_names "$@"); do
  [[ -d "$ORIGINAL_DIR/$name" ]] || continue
  build_director "$name"
  did=1
done
[[ $did -eq 1 ]] || { echo "Nothing to build in original/." >&2; exit 1; }
