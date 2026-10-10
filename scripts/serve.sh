#!/usr/bin/env bash
# Serves the repo over http://localhost:<port>/ so the players in platforms/web can load files.
#   Game:      http://localhost:8000/platforms/web/shockwave.html
#   Flash hub: http://localhost:8000/platforms/web/
# Usage: scripts/serve.sh [port]    (Ctrl+C to stop; needs python3)
source "$(dirname "$0")/_common.sh"

port="${1:-8000}"
echo "Serving $ROOT at http://localhost:$port/"
echo "  Game:      http://localhost:$port/platforms/web/shockwave.html"
echo "  Flash hub: http://localhost:$port/platforms/web/"
exec python3 -I - "$ROOT" "$port" <<'EOF'
import functools, http.server, os, sys

root, port = sys.argv[1], int(sys.argv[2])
# Like Director, resolve a protected cast/movie name to its unprotected twin (the decompiled
# movies still link e.g. empty_Robin.cct, but src/ holds empty_Robin.cst).
TWINS = {".cct": ".cst", ".cxt": ".cst", ".dcr": ".dir", ".dxr": ".dir"}

class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {**http.server.SimpleHTTPRequestHandler.extensions_map,
                      ".wasm": "application/wasm", ".js": "text/javascript"}

    def translate_path(self, path):
        full = super().translate_path(path)
        base, ext = os.path.splitext(full)
        if not os.path.isfile(full) and ext.lower() in TWINS:
            return base + TWINS[ext.lower()]
        return full

http.server.ThreadingHTTPServer(("127.0.0.1", port),
                                functools.partial(Handler, directory=root)).serve_forever()
EOF
