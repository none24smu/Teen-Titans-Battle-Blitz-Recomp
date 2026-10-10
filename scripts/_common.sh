# Shared settings for the pipeline scripts. Sourced, not executed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORIGINAL_DIR="$ROOT/original"
SRC_DIR="$ROOT/src"
BUILD_DIR="$ROOT/build"
TOOLS_DIR="$ROOT/tools"

FFDEC_VERSION="26.3.0"
FFDEC_URL="https://github.com/jindrapetrik/jpexs-decompiler/releases/download/version${FFDEC_VERSION}/ffdec_${FFDEC_VERSION}.zip"
FFDEC_SHA256="35f4930eb7c380afe66f2117f90b006deac0631473ad7500bb39c78f68645ecd"
FFDEC_JAR="$TOOLS_DIR/ffdec/ffdec.jar"

# ProjectorRays (Shockwave/Director decompiler) only ships a Windows build. Elsewhere, build it
# from https://github.com/ProjectorRays/ProjectorRays and point PROJECTORRAYS at the binary.
PROJECTORRAYS_VERSION="0.2.0"
PROJECTORRAYS_URL="https://github.com/ProjectorRays/ProjectorRays/releases/download/v${PROJECTORRAYS_VERSION}/projectorrays-${PROJECTORRAYS_VERSION}.exe"
PROJECTORRAYS_SHA256="e9814428ee503cf129b6f5cff54524177b7bdd63201a9095d8d19433535c70db"
PROJECTORRAYS="${PROJECTORRAYS:-$TOOLS_DIR/projectorrays/projectorrays.exe}"

# Director file types ProjectorRays understands.
DIRECTOR_EXTS="dir dxr dcr cst cxt cct"

# dirplayer-rs web polyfill (plays Director/Shockwave movies in the browser; platforms/web/shockwave.html).
DIRPLAYER_VERSION="0.8.1"
DIRPLAYER_URL="https://github.com/igorlira/dirplayer-rs/releases/download/v${DIRPLAYER_VERSION}/dirplayer-polyfill-${DIRPLAYER_VERSION}.zip"
DIRPLAYER_SHA256="6f5000412c50833345a630ade25fb70359dc4e1aae66352c80de7da77542162c"
DIRPLAYER_DIR="$TOOLS_DIR/dirplayer/polyfill"

if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi

ffdec() {
  if [[ ! -f "$FFDEC_JAR" ]]; then
    echo "FFDec not found. Run scripts/setup.sh first." >&2
    exit 1
  fi
  "$JAVA" -jar "$FFDEC_JAR" "$@"
}

projectorrays() {
  if [[ ! -x "$PROJECTORRAYS" ]]; then
    echo "ProjectorRays not found at $PROJECTORRAYS. Run scripts/setup.sh, or set PROJECTORRAYS." >&2
    exit 1
  fi
  "$PROJECTORRAYS" "$@"
}

# Exports a Director movie/cast's assets (scripts/extract/DirectorExtract.java), compiling the
# extractor into tools/ the first time or when its source changed.
director_extract() {
  local src="$ROOT/scripts/extract/DirectorExtract.java" classes="$TOOLS_DIR/extract"
  if [[ ! -f "$classes/DirectorExtract.class" || "$src" -nt "$classes/DirectorExtract.class" ]]; then
    local javac="javac"
    [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]] && javac="$JAVA_HOME/bin/javac"
    mkdir -p "$classes"
    "$javac" -nowarn -d "$classes" "$src"
  fi
  "$JAVA" -cp "$classes" DirectorExtract "$1" "$2"
}

sha256_of() {
  local s
  s="$(sha256sum "$1" 2>/dev/null || shasum -a 256 "$1")"
  echo "${s%% *}"
}

# Folders in original/ that hold a Director/Shockwave game: the arguments given, or all of them.
director_names() {
  if [[ $# -gt 0 ]]; then
    for n in "$@"; do basename "$n"; done
    return
  fi
  for d in "$ORIGINAL_DIR"/*/; do
    [[ -d "$d" ]] || continue
    for ext in $DIRECTOR_EXTS; do
      if compgen -G "$d*.$ext" >/dev/null; then basename "$d"; break; fi
    done
  done
}

# Names of the SWFs to process: the arguments given, or every SWF in original/.
swf_names() {
  if [[ $# -gt 0 ]]; then
    for n in "$@"; do basename "$n" .swf; done
  else
    for f in "$ORIGINAL_DIR"/*.swf; do
      [[ -e "$f" ]] && basename "$f" .swf
    done
    return 0
  fi
}
