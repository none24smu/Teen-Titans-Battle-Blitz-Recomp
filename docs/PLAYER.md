# The web player

`platforms/web/shockwave.html` plays the decompiled game in the browser with
[dirplayer-rs](https://github.com/igorlira/dirplayer-rs), an open-source Shockwave player.
The game itself runs **unmodified**: `build/<game>/web/game.dir` is byte-identical to the
decompiled movie. Wherever dirplayer-rs 0.8.1 does something differently from Director,
it's fixed in the player, not worked around in the game.

## Building the patched player

```bash
scripts/setup.sh          # released polyfill (for its Ruffle/Xtra files) + decompilers
scripts/build-player.sh   # -> tools/dirplayer/polyfill-patched/
```

`build-player` checks out dirplayer-rs v0.8.1 into `tools/dirplayer-src`, applies
`patches/dirplayer/*.patch`, builds the Rust VM for WebAssembly, generates its bindings with
`wasm-bindgen` (downloaded at the exact version the lockfile pins), and bundles the polyfill
with Vite. It needs Rust with the `wasm32-unknown-unknown` target (from rustup.rs) and
Node.js 18+. On Windows the Node zip can be unpacked into `tools/node`.

The page uses the patched player when it exists, otherwise the released one (append
`?player=release` to force the released one). It runs dirplayer's Canvas2D renderer
(`?renderer=webgl2` switches to the GPU one, which doesn't have the film-loop fix).

## What the patch fixes

| Director behaviour | dirplayer 0.8.1 | Visible in Battle Blitz as |
|---|---|---|
| A sprite keeps `flipH`/`flipV`, `rotation` and `skew` when its member changes | reset them | health bars: flipped pieces drawn the wrong way round and out of place after every hit |
| `rect.intersect(other)` returns the overlap, or `rect(0,0,0,0)` | returned the union | every hit test passed, so fighters stun-locked each other the moment anyone moved |
| `go` inside `tell sprite(<film loop>)` moves that loop's playhead | moved the movie's | hovering START restarted the game |
| Film-loop sprites are drawn onto the stage with their own ink and blend, cropped to the loop | drawn into a transparent intermediate bitmap | the pulsing "selected" darkening on character select drew as solid black boxes |
| Assigning a film-loop member gives the sprite the loop's natural size | kept the channel's old size | the animated "selected" portraits drew oversized, over their neighbours |
| A blend the score changes partway through a sprite span takes effect on that frame, tweened or not | applied only tweened blend changes | the ending: the score, the Titans and the victory text stayed invisible, leaving only Slade's silhouette |

## Page behaviour

- **Menus by keyboard:** arrows/Tab move a highlight between the game's own buttons, which
  are recognised by their cast member names. Enter/Space clicks; Esc/Backspace presses Back.
- **Keyboard focus:** keys reach the game even when the player element doesn't have focus.
- **Local file:** opened from disk, the page jumps to `scripts/serve` if it's running.
