#!/usr/bin/env bash
# Downloads the pinned decompilers into tools/:
#   FFDec          (Flash .swf)          tools/ffdec/
#   ProjectorRays  (Director/Shockwave)  tools/projectorrays/   (Windows build only)
source "$(dirname "$0")/_common.sh"

mkdir -p "$TOOLS_DIR"

# download <url> <dest> <sha256>
download() {
  curl -fL -o "$2" "$1"
  local actual
  actual="$(sha256_of "$2")"
  if [[ "$actual" != "$3" ]]; then
    echo "Checksum mismatch for $2 (got $actual)" >&2
    rm -f "$2"
    exit 1
  fi
}

if [[ -f "$FFDEC_JAR" ]]; then
  echo "FFDec already installed at $FFDEC_JAR"
else
  "$JAVA" -version >/dev/null 2>&1 || { echo "Java 11+ is required (set JAVA_HOME or put java on PATH)." >&2; exit 1; }
  zip="$TOOLS_DIR/ffdec_${FFDEC_VERSION}.zip"
  echo "Downloading FFDec $FFDEC_VERSION..."
  download "$FFDEC_URL" "$zip" "$FFDEC_SHA256"
  rm -rf "$TOOLS_DIR/ffdec"
  mkdir -p "$TOOLS_DIR/ffdec"
  unzip -q "$zip" -d "$TOOLS_DIR/ffdec"
  rm -f "$zip"
  echo "FFDec installed at $FFDEC_JAR"
fi

if [[ -f "$DIRPLAYER_DIR/dirplayer-polyfill.js" ]]; then
  echo "dirplayer already installed at $DIRPLAYER_DIR"
else
  zip="$TOOLS_DIR/dirplayer-polyfill-${DIRPLAYER_VERSION}.zip"
  echo "Downloading dirplayer-rs polyfill $DIRPLAYER_VERSION..."
  download "$DIRPLAYER_URL" "$zip" "$DIRPLAYER_SHA256"
  rm -rf "$DIRPLAYER_DIR"
  mkdir -p "$DIRPLAYER_DIR"
  unzip -q "$zip" -d "$DIRPLAYER_DIR"
  rm -f "$zip"
  echo "dirplayer installed at $DIRPLAYER_DIR"
fi

if [[ -x "$PROJECTORRAYS" ]]; then
  echo "ProjectorRays found at $PROJECTORRAYS"
else
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
      echo "Downloading ProjectorRays $PROJECTORRAYS_VERSION..."
      mkdir -p "$TOOLS_DIR/projectorrays"
      download "$PROJECTORRAYS_URL" "$TOOLS_DIR/projectorrays/projectorrays.exe" "$PROJECTORRAYS_SHA256"
      echo "ProjectorRays installed at $TOOLS_DIR/projectorrays/projectorrays.exe"
      ;;
    *)
      echo "ProjectorRays has no prebuilt binary for this OS. Build it from"
      echo "https://github.com/ProjectorRays/ProjectorRays and set PROJECTORRAYS=/path/to/projectorrays"
      echo "(only needed for Director/Shockwave games)."
      ;;
  esac
fi
