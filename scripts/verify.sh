#!/usr/bin/env bash
# Checks the decompiled output against the originals.
# Usage: scripts/verify.sh [name ...]   (default: everything in original/)
#
# Flash:    renders every frame of original/<name>.swf and build/<name>.swf and checks they
#           match, and checks the rebuilt scripts decompile to src/<name>/scripts.
# Director: re-decompiles the unprotected movies/casts in src/<name>/movies and checks the
#           Lingo comes out identical, i.e. the unprotected files are complete and stable.
source "$(dirname "$0")/_common.sh"

status=0

verify_swf() {
  local name="$1" built="$BUILD_DIR/$1.swf"
  [[ -f "$built" ]] || { echo "Missing $built; run scripts/build.sh first." >&2; exit 1; }

  echo "== $name (Flash)"
  local work="$BUILD_DIR/.verify-$name"
  rm -rf "$work"
  mkdir -p "$work"

  ffdec -export frame "$work/orig" "$ORIGINAL_DIR/$name.swf" >/dev/null
  ffdec -export frame "$work/built" "$built" >/dev/null
  (cd "$work/orig" && find . -type f -exec md5sum {} + | sort -k2) > "$work/orig.md5"
  (cd "$work/built" && find . -type f -exec md5sum {} + | sort -k2) > "$work/built.md5"
  local frames
  frames=$(wc -l < "$work/orig.md5")
  if diff -q "$work/orig.md5" "$work/built.md5" >/dev/null; then
    echo "  frames:  OK ($frames frames render identically)"
  else
    echo "  frames:  DIFFERENT -- see $work/{orig,built}"
    diff "$work/orig.md5" "$work/built.md5" | head -20
    status=1
  fi

  ffdec -export script "$work/scripts" "$built" >/dev/null
  if [[ ! -d "$SRC_DIR/$name/scripts" ]] || diff -r "$SRC_DIR/$name/scripts" "$work/scripts/scripts" >/dev/null; then
    echo "  scripts: OK (rebuilt bytecode decompiles to src/$name/scripts)"
  else
    echo "  scripts: DIFFERENT"
    diff -r "$SRC_DIR/$name/scripts" "$work/scripts/scripts" | head -20
    status=1
  fi
}

verify_director() {
  local name="$1" movies="$SRC_DIR/$1/movies"
  [[ -d "$movies" ]] || { echo "Missing $movies; run scripts/decompile.sh first." >&2; exit 1; }

  echo "== $name (Director)"
  local work="$BUILD_DIR/.verify-$name"
  rm -rf "$work"
  mkdir -p "$work"

  local f base count=0 bad=0
  for f in "$movies"/*.dir "$movies"/*.cst; do
    [[ -f "$f" ]] || continue
    base="$(basename "$f")"; base="${base%.*}"
    projectorrays decompile "$f" -o "$work" --dump-scripts >/dev/null
    if [[ -d "$work/$base" ]]; then director_java LingoFixups "$f" "$work/$base" >/dev/null; fi
    if [[ -d "$movies/$base" || -d "$work/$base" ]]; then
      if ! diff -r "$movies/$base" "$work/$base" >/dev/null 2>&1; then
        echo "  $base: Lingo differs after re-decompiling"
        bad=1
      fi
    fi
    count=$((count + 1))
  done
  local scripts
  scripts=$(find "$movies" -name '*.ls' | wc -l)
  if [[ $bad -eq 0 ]]; then
    echo "  lingo:   OK ($count movies/casts, $scripts scripts re-decompile identically)"
  else
    status=1
  fi

  # Every script must compile back to the movie's own bytecode (scripts/lingo/LingoCompiler.java).
  local compile_bad=0
  for f in "$movies"/*.dir "$movies"/*.cst; do
    [[ -f "$f" ]] || continue
    base="$(basename "$f")"; base="${base%.*}"
    [[ -d "$movies/$base" ]] || continue
    if ! director_java LingoRoundTrip "$f" "$movies/$base" -v >"$work/$base.compile.txt" 2>&1; then
      echo "  $base: Lingo doesn't compile back to the original bytecode (see build/.verify-$name/$base.compile.txt)"
      compile_bad=1
    fi
  done
  if [[ $compile_bad -eq 0 ]]; then
    echo "  compile: OK ($scripts scripts compile back to identical bytecode)"
  else
    status=1
  fi

  # Writing the compiled scripts back must reproduce each movie exactly (scripts/lingo/LingoBuild.java).
  local build_bad=0 built=0
  for f in "$movies"/*.dir "$movies"/*.cst; do
    [[ -f "$f" ]] || continue
    base="$(basename "$f")"
    [[ -d "$movies/${base%.*}" ]] || continue
    director_java LingoBuild "$f" "$movies/${base%.*}" "$work/rebuilt-$base" >/dev/null
    if ! cmp -s "$f" "$work/rebuilt-$base"; then
      echo "  $base: rebuilding it from its Lingo source changes the file"
      build_bad=1
    fi
    built=$((built + 1))
  done
  if [[ $build_bad -eq 0 ]]; then
    echo "  build:   OK ($built movies/casts rebuild byte-identical from source)"
  else
    status=1
  fi

  local assets="$SRC_DIR/$name/assets"
  if [[ -d "$assets" ]]; then
    local errors pngs wavs
    errors=$({ grep -rl '"error"' "$assets" --include=cast.json || true; } | wc -l)
    pngs=$(find "$assets" -name '*.png' | wc -l)
    wavs=$(find "$assets" -name '*.wav' | wc -l)
    if [[ $errors -eq 0 ]]; then
      echo "  assets:  OK ($pngs images, $wavs sounds exported, no member failed)"
    else
      echo "  assets:  $errors cast(s) have members that failed to export -- see \"error\" in their cast.json"
      status=1
    fi
  fi
}

did=0
for name in $(swf_names "$@"); do
  [[ -f "$ORIGINAL_DIR/$name.swf" ]] || continue
  verify_swf "$name"
  did=1
done
for name in $(director_names "$@"); do
  [[ -d "$ORIGINAL_DIR/$name" ]] || continue
  verify_director "$name"
  did=1
done
[[ $did -eq 1 ]] || { echo "Nothing to verify in original/." >&2; exit 1; }

if [[ $status -eq 0 ]]; then
  echo "All checks passed."
else
  echo "Output differs from the original. Expected if you edited src/." >&2
fi
exit $status
