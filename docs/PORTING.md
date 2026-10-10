# Porting to other platforms

## Battle Blitz (Director / Shockwave)

`scripts/decompile` turns the protected Shockwave files into ordinary, editable Director
files (`src/ttbattleblitz/movies/*.dir`, `*.cst`) plus the Lingo source as text
(`.../casts/*/*.ls`). From there:

### 1. Adobe Director (Windows, macOS, Shockwave)

Open `src/ttbattleblitz/movies/game.dir` in Director 8.5 or later (MX 2004 and 11.5 are the
most common). Keep the `char_*.cst` / `empty_*.cst` casts in the same folder. You can then
edit scripts and art, and publish to Shockwave (`.dcr`) or a Windows/macOS projector. Before
publishing, protect the external casts again (Xtras → Update Movies → Protect) so the
`.cct` names the game loads still resolve. Alternatively, change the cast file names set in
`Class_TitleScreen` and `Class_VersusScreen` (and the `DEBUG` movie script) to load `.cst`
directly.

### Play it now in the browser (dirplayer-rs)

```powershell
scripts\setup.ps1; scripts\decompile.ps1; scripts\build.ps1; scripts\serve.ps1
```

Then open <http://localhost:8000/platforms/web/shockwave.html>. Controls: arrow keys
(move, jump, crouch), <kbd>Z</kbd> punch, <kbd>X</kbd> kick, <kbd>P</kbd> pause, <kbd>Esc</kbd>
quit the match.

- `scripts/setup` installs the dirplayer-rs web polyfill (pinned, checksum-verified) into
  `tools/dirplayer/`.
- `scripts/build` writes `build/ttbattleblitz/web/`, the decompiled movie plus one
  compatibility patch. dirplayer runs `tell sprite(n) … go(1) … end tell` against the
  whole movie instead of the sprite, so hovering START sent the game back to frame 1 in an
  endless loop. The patch skips that rewind in the two places it appears (START button,
  fighter-select logo).
- `scripts/serve` serves the repo locally. Browsers refuse to load movies from `file://`.
  It also resolves the `.cct` cast names the movie asks for to the unprotected `.cst` files.
- The page forwards keyboard input to the player even when it doesn't have focus, and runs
  the game in Shockwave "plugin" mode. The projector code path stops on the title screen.

### 2. Open-source Director players (Linux, web, handhelds)

- **ScummVM** has a Director engine that runs many D2–D5 titles, with D6+ work in progress.
  ScummVM itself runs on Windows, macOS, Linux, Android, iOS, consoles and handhelds. Try the
  original `.dcr` or the unprotected `.dir`; how well a D8 title like this runs depends on the
  ScummVM version.
- **dirplayer-rs** is a Rust/WASM Shockwave player aimed at D8–D12 web content. It runs in
  the browser, so on any OS.

These replay the original movie, so they need no code changes beyond stubbing the
network/browser calls listed in [BATTLE_BLITZ.md](BATTLE_BLITZ.md).

### 3. Native port (any engine)

[BATTLE_BLITZ.md](BATTLE_BLITZ.md) maps the architecture. The Lingo is object-oriented and
splits into:

- **A small engine layer** to reimplement: `Actor`/`AnimatedActor`/`CollidableActor`,
  `Animation*`, `Scene`/`SpriteManager`, `KeyManager`, `AudioManager`, `Screen`.
- **Game classes** that translate almost one to one: `Fighter`, `Move` and each fighter's
  move/state classes, `KeyCombo`, the AI classes, HUD and screens.

To get the art and sound out as PNG/WAV for another engine, open the unprotected casts in
Director and export members, or use a cast-ripper tool on `src/ttbattleblitz/movies/*.cst`.
Bitmaps are stored in `BITD` chunks with palettes in `CLUT`, and sounds in `snd ` chunks.
`projectorrays decompile <file> -o <dir> --dump-chunks --dump-json` dumps them raw.

Good targets: Godot (GDScript is close to Lingo's style), Haxe/OpenFL, or
JavaScript/TypeScript with PixiJS for the web.

## Flash hub (`internal.swf`)

`scripts/build` produces a normal Flash SWF. Flash Player is gone, so there are three ways
to get it running elsewhere, in increasing order of effort.

## 1. Ruffle (web, Windows, macOS, Linux)

[Ruffle](https://ruffle.rs) is an open-source Flash Player emulator. It fully supports
AS1/AS2 content like this, and it is what the Internet Archive uses to run the file.

- **Web:** serve the repo root over HTTP and open `platforms/web/index.html`:
  ```bash
  python -m http.server 8000
  ```
  then browse to `http://localhost:8000/platforms/web/`. Add `?swf=<name>` to load a
  different `build/<name>.swf`. To publish it, copy `platforms/web/index.html` and the SWF
  to any static host and adjust the SWF path.
- **Desktop:** download the Ruffle desktop player for your OS from
  <https://ruffle.rs/downloads> and open `build/<name>.swf`.
- **Mobile:** use the web build in a WebView (Capacitor, Cordova, or a native WebView
  wrapper).

## 2. Adobe Animate (HTML5 Canvas, AIR desktop/mobile)

`src/<name>/xfl/` is an Animate project rebuilt from the SWF: library symbols, timelines,
frame scripts and assets.

1. Open `src/<name>/xfl/<name>.xfl` in Adobe Animate.
2. Publish as-is to SWF, or to **AIR for Desktop / Android / iOS** (the HARMAN AIR SDK keeps
   AS1/AS2 content working only inside a SWF wrapper. For a native AIR app, convert the
   document to AS3 and port the ~140 lines of script).
3. For **HTML5 Canvas**: convert the document type to HTML5 Canvas, then rewrite the frame
   scripts in JavaScript (`this.gotoAndStop(...)`, `window.open(...)`, etc.).

## 3. Full source port (any engine)

The decompiled output has everything an engine port needs:

- **Logic:** `src/<name>/scripts/` (the original ActionScript) and `docs/STRUCTURE.md` (the
  flow).
- **Timelines:** `src/<name>/swf.xml` lists every PlaceObject/RemoveObject per frame with
  depths, matrices and colour transforms. This is the data you replay to reproduce the
  animation.
- **Art:** `src/<name>/assets/shapes/*.svg` (vector) or the PNG renders in `sprites/`,
  `buttons/` and `frames/`.
- **Audio:** `src/<name>/assets/sounds/*.mp3`.
- **Fonts:** `src/<name>/assets/fonts/*.ttf`.

Engines that handle SWF-style content well:

- **OpenFL / Haxe:** can load SWF libraries directly (`openfl-swf` / `swflite`) and targets
  HTML5, Windows, macOS, Linux, Android and iOS.
- **Godot:** use the PNG/SVG assets with `AnimationPlayer`, and replay the timeline from
  `swf.xml`.
- **Plain JavaScript/Canvas:** the `canvas` export formats in FFDec
  (`-format shape:canvas,frame:canvas`) give drawing code you can embed.
