#!/usr/bin/env bash
# Rebuilds everything in src/ into build/.
# Usage: scripts/build.sh [name ...]   (default: everything in original/)
#
# Flash:    src/<name>/swf.xml + src/<name>/scripts -> build/<name>.swf
# Director: src/<name>/movies -> build/<name>/web/, the decompiled movie and casts with the
#           dirplayer compatibility patches below applied (platforms/web/shockwave.html plays it).
source "$(dirname "$0")/_common.sh"

# Byte patches for running Director movies in dirplayer-rs, as "name|search hex|replace hex|count".
# dirplayer doesn't scope `tell sprite(n) ... go(1) ... end tell` to the sprite: instead of
# rewinding the button's own animation it sends the whole movie to frame 1, so hovering START
# restarted the game. The patch turns the push/call into a jump straight to `endtell`
# (starttell, jmp +8, [skipped bytes], endtell).
#
# dirplayer's method form `a.intersect(b)` returns the union of the rects, so every hit test
# passed and fighters stun-locked each other with zero-damage hits the moment anyone moved. Its
# function form `intersect(a, b)` is correct and takes the same argument list, so the patch
# turns `pusharglist 2; objcall #intersect` into `pusharglist 2; extcall #intersect` at the
# three hit tests (fighter attacks, AI attack planning, fighter overlap).
DIRECTOR_PATCHES=(
  "tell-go-1|1c8100014201a300911d|1c9300084201a300911d|2"
  "rect-intersect|4302a7011c|430297011c|3"
)

# patch_movie <in> <out> <search hex> <replace hex> -> prints the number of replacements
patch_movie() {
  perl -0777 -e '
    my ($in, $out, $s, $r) = @ARGV;
    open(my $fh, "<:raw", $in) or die "$in: $!"; my $d = do { local $/; <$fh> }; close $fh;
    my ($sb, $rb) = (pack("H*", $s), pack("H*", $r));
    my $n = () = $d =~ /\Q$sb\E/g;
    $d =~ s/\Q$sb\E/$rb/g;
    open(my $oh, ">:raw", $out) or die "$out: $!"; print $oh $d; close $oh;
    print $n;
  ' "$1" "$2" "$3" "$4"
}

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
  cp "$movies"/*.cst "$out"/ 2>/dev/null || true
  local f base p pname search replace want got tmp
  for f in "$movies"/*.dir; do
    [[ -f "$f" ]] || continue
    base="$(basename "$f")"
    cp "$f" "$out/$base"
    for p in "${DIRECTOR_PATCHES[@]}"; do
      IFS='|' read -r pname search replace want <<< "$p"
      tmp="$out/.$base.tmp"
      got="$(patch_movie "$out/$base" "$tmp" "$search" "$replace")"
      mv -f "$tmp" "$out/$base"
      if [[ "$got" -gt 0 ]]; then
        echo "  $base: patch $pname applied $got time(s)"
        if [[ "$got" -ne "$want" && "$base" == "game.dir" ]]; then
          echo "  warning: expected $want for game.dir (different game version?)" >&2
        fi
      fi
    done
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
