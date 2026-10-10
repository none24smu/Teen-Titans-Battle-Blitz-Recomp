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

# Names of the SWFs to process: the arguments given, or every SWF in original/.
swf_names() {
  if [[ $# -gt 0 ]]; then
    for n in "$@"; do basename "$n" .swf; done
  else
    for f in "$ORIGINAL_DIR"/*.swf; do
      [[ -e "$f" ]] || { echo "No SWF files in original/." >&2; exit 1; }
      basename "$f" .swf
    done
  fi
}
