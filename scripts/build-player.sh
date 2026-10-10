#!/usr/bin/env bash
# Builds the patched dirplayer-rs web player into tools/dirplayer/polyfill-patched/.
#
# dirplayer-rs 0.8.1 gets several Director behaviours wrong that Teen Titans Battle Blitz
# depends on; patches/dirplayer/*.patch fixes them (see docs/PLAYER.md). This script:
#   1. checks out dirplayer-rs v0.8.1 into tools/dirplayer-src and applies the patches,
#   2. builds the Rust VM for WebAssembly (cargo) and its JS bindings (wasm-bindgen),
#   3. bundles the polyfill (vite) and stages it next to the released Ruffle/Xtra files.
#
# Needs: Rust with the wasm32-unknown-unknown target (rustup), Node.js 18+, git, curl.
# Set CARGO, NODE or WASM_BINDGEN to override the tools found on PATH / in tools/.
source "$(dirname "$0")/_common.sh"

DIRPLAYER_TAG="v${DIRPLAYER_VERSION}"
SRC="$TOOLS_DIR/dirplayer-src"
OUT="$TOOLS_DIR/dirplayer/polyfill-patched"
CARGO="${CARGO:-$(command -v cargo || echo "$HOME/.cargo/bin/cargo")}"
NODE="${NODE:-$( [[ -x "$TOOLS_DIR/node/node.exe" ]] && echo "$TOOLS_DIR/node/node.exe" || command -v node )}"
NPM_CLI="$(dirname "$NODE")/node_modules/npm/bin/npm-cli.js"

[[ -f "$DIRPLAYER_DIR/dirplayer-polyfill.js" ]] || { echo "Run scripts/setup.sh first (needs the released polyfill's Ruffle/Xtra files)." >&2; exit 1; }
[[ -x "$CARGO" ]] || { echo "cargo not found: install Rust from https://rustup.rs (plus: rustup target add wasm32-unknown-unknown)" >&2; exit 1; }
[[ -n "$NODE" && -x "$NODE" ]] || { echo "node not found: install Node.js 18+ or unzip it into tools/node" >&2; exit 1; }

# 1. Source at the pinned tag, with our patches.
if [[ ! -d "$SRC/.git" ]]; then
  git clone --depth 1 --branch "$DIRPLAYER_TAG" https://github.com/igorlira/dirplayer-rs.git "$SRC"
fi
(
  cd "$SRC"
  git checkout -q -- . && git clean -qfd -e node_modules -e vm-rust/target
  for p in "$ROOT"/patches/dirplayer/*.patch; do
    [[ -e "$p" ]] || continue
    echo "  applying $(basename "$p")"
    git apply --ignore-whitespace "$p"
  done
)

# 2. VM -> WebAssembly + bindings.
WB_VERSION="$(grep -A1 '^name = "wasm-bindgen"$' "$SRC/vm-rust/Cargo.lock" | grep version | cut -d'"' -f2)"
WASM_BINDGEN="${WASM_BINDGEN:-$TOOLS_DIR/wasm-bindgen/wasm-bindgen.exe}"
[[ -x "$WASM_BINDGEN" ]] || WASM_BINDGEN="$TOOLS_DIR/wasm-bindgen/wasm-bindgen"
if [[ ! -x "$WASM_BINDGEN" ]] || ! "$WASM_BINDGEN" --version | grep -q "$WB_VERSION"; then
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) triple=x86_64-pc-windows-msvc ;;
    Darwin) triple=$( [[ "$(uname -m)" == arm64 ]] && echo aarch64-apple-darwin || echo x86_64-apple-darwin ) ;;
    *) triple=x86_64-unknown-linux-musl ;;
  esac
  echo "Downloading wasm-bindgen $WB_VERSION..."
  rm -rf "$TOOLS_DIR/wasm-bindgen"; mkdir -p "$TOOLS_DIR/wasm-bindgen"
  curl -fL "https://github.com/wasm-bindgen/wasm-bindgen/releases/download/$WB_VERSION/wasm-bindgen-$WB_VERSION-$triple.tar.gz" \
    | tar -xz -C "$TOOLS_DIR/wasm-bindgen" --strip-components=1
  WASM_BINDGEN="$TOOLS_DIR/wasm-bindgen/wasm-bindgen$( [[ -f "$TOOLS_DIR/wasm-bindgen/wasm-bindgen.exe" ]] && echo .exe )"
fi
echo "Building the VM (cargo, release)..."
( cd "$SRC/vm-rust" && "$CARGO" build --release --target wasm32-unknown-unknown --quiet )
rm -rf "$SRC/vm-rust/pkg"
"$WASM_BINDGEN" --target web --out-dir "$SRC/vm-rust/pkg" "$SRC/vm-rust/target/wasm32-unknown-unknown/release/vm_rust.wasm"
cat > "$SRC/vm-rust/pkg/package.json" <<'EOF'
{"name": "vm-rust", "type": "module", "version": "0.1.0", "main": "vm_rust.js", "types": "vm_rust.d.ts",
 "files": ["vm_rust_bg.wasm", "vm_rust.js", "vm_rust.d.ts"], "sideEffects": ["./snippets/*"]}
EOF

# 3. Polyfill bundle.
( cd "$SRC" && { [[ -d node_modules ]] || "$NODE" "$NPM_CLI" ci --no-audit --no-fund; } \
  && "$NODE" node_modules/vite/bin/vite.js build -c vite.config.polyfill.js --logLevel warn )
rm -rf "$OUT"; mkdir -p "$OUT"
cp "$SRC/dist-polyfill/dirplayer-polyfill.js" "$OUT/"
cp -r "$DIRPLAYER_DIR/ruffle" "$DIRPLAYER_DIR/xtra-registry.json" "$DIRPLAYER_DIR"/*.wasm "$OUT/"
echo "Patched player: $OUT/dirplayer-polyfill.js"
