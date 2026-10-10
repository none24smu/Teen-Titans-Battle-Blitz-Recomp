#!/usr/bin/env bash
# Downloads the pinned JPEXS Free Flash Decompiler (FFDec) into tools/ffdec.
source "$(dirname "$0")/_common.sh"

if [[ -f "$FFDEC_JAR" ]]; then
  echo "FFDec already installed at $FFDEC_JAR"
  exit 0
fi

"$JAVA" -version >/dev/null 2>&1 || { echo "Java 11+ is required (set JAVA_HOME or put java on PATH)." >&2; exit 1; }

mkdir -p "$TOOLS_DIR"
zip="$TOOLS_DIR/ffdec_${FFDEC_VERSION}.zip"
echo "Downloading FFDec $FFDEC_VERSION..."
curl -fL -o "$zip" "$FFDEC_URL"

actual="$(sha256sum "$zip" 2>/dev/null || shasum -a 256 "$zip")"
actual="${actual%% *}"
if [[ "$actual" != "$FFDEC_SHA256" ]]; then
  echo "Checksum mismatch for $zip (got $actual)" >&2
  rm -f "$zip"
  exit 1
fi

rm -rf "$TOOLS_DIR/ffdec"
mkdir -p "$TOOLS_DIR/ffdec"
unzip -q "$zip" -d "$TOOLS_DIR/ffdec"
rm -f "$zip"
echo "FFDec installed at $FFDEC_JAR"
