#!/usr/bin/env bash
# Renders every frame of original/<name>.swf and build/<name>.swf and checks they match,
# and checks the rebuilt scripts decompile to the same source as src/<name>/scripts.
# Usage: scripts/verify.sh [name ...]   (default: every SWF in original/)
source "$(dirname "$0")/_common.sh"

status=0
for name in $(swf_names "$@"); do
  built="$BUILD_DIR/$name.swf"
  [[ -f "$built" ]] || { echo "Missing $built; run scripts/build.sh first." >&2; exit 1; }

  echo "== $name"
  work="$BUILD_DIR/.verify-$name"
  rm -rf "$work"
  mkdir -p "$work"

  ffdec -export frame "$work/orig" "$ORIGINAL_DIR/$name.swf" >/dev/null
  ffdec -export frame "$work/built" "$built" >/dev/null
  (cd "$work/orig" && find . -type f -exec md5sum {} + | sort -k2) > "$work/orig.md5"
  (cd "$work/built" && find . -type f -exec md5sum {} + | sort -k2) > "$work/built.md5"
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
done

if [[ $status -eq 0 ]]; then
  echo "All checks passed."
else
  echo "Rebuild differs from the original. Expected if you edited src/." >&2
fi
exit $status
