# internal.swf structure

These notes cover the Internet Archive copy (SHA-1 `115ee2ee7c4a808fe66719039419c73ead8ac90b`).
Run `scripts/decompile` to get the actual sources referenced here.

## What it is

This is the hub page of Cartoon Network's first Teen Titans site section
(`/tv_shows/titans/gen1/`), **not the Battle Blitz game**. It loads, plays an intro and stops
on a menu, and every menu button sends the browser to another page. The game itself was
a separate SWF embedded on `/tv_shows/titans/gen1/battleblitz/index.html`.

| property | value |
|---|---|
| SWF version | 6 (Flash Player 6), zlib-compressed |
| Language | ActionScript 1.0 (141 lines across 40 scripts) |
| Stage | 750 × 415 px, black background |
| Frame rate | 21 fps |
| Main timeline | 134 frames |

## Main timeline flow

| frame | label | what happens |
|---|---|---|
| 3 | | If frame 101 is already loaded (cached), skip the preloader and jump to `skipLoader`. |
| 14–16 | `loop` | Preloader. Computes the percent loaded, shows KB/percent text, and steps the loader clip (sprite 47) through its labels `level_1`…`level_11`. It loops until everything is loaded, then plays the loader's `outro` and continues at `begin`. |
| 17 | `begin` | Starts the intro animation. |
| 17–100 | | Intro animation with the streamed soundtrack (sound stream on the main timeline). |
| 101 | `generic` | Menu. The timeline stops here and waits for a button. |
| 102–130 | | Exit transition, started by every navigation button. |
| 130 | `end` | Opens the URL stored in the timeline variable `where` (in the same window). |
| 134 | | Stop. |

## Buttons

| button id | action |
|---|---|
| 123 | Calls the host page's JavaScript function `titansTrailer()` (the "view clip" button) |
| 125 | Goes to `http://www.cartoonnetwork.com` |
| 127, 174 | Go to `/tv_shows/titans/gen1/battleblitz/index.html` (the game page) |
| 137 | Go to `.../robin/index.html` |
| 144 | Go to `.../cyborg/index.html` |
| 153 | Go to `.../raven/index.html` |
| 163 | Go to `.../starfire/index.html` |
| 171 | Go to `.../beastboy/index.html` |
| 185 | Plays the exit transition |
| 179 / 183 | Music toggle: send the music clip (sprite 184) to its `off` / `on` label |

The relative links point at cartoonnetwork.com pages that no longer exist. For a standalone
port, change the `getURL` targets in `src/internal/scripts`, or handle `where` in your
engine.

## Assets

| kind | count | notes |
|---|---|---|
| shapes | 147 | vector art, exported as SVG |
| sprites (movie clips) | 167 | |
| buttons | 14 | 12 carry scripts |
| sounds | 4 | the main timeline stream plus event sounds 16, 42, 180 |
| bitmaps | 2 | ids 64 (JPEG), 76 (PNG) |
| fonts | 4 | Impact (×3 subsets), Eurostile Bold |
| text fields | 18 | menu labels: GAME, CASE FILES, TOWER TOUR, MIND TEASER, SCRAPBOOK, FAN CLUB, MUSIC ON/OFF, LOADING, PLAY, GO!, VIEW CLIP |

## Round-trip notes

- `swf.xml` → SWF re-encodes six shape records with smaller bit widths: zero-length moves
  stored in 0 bits instead of 1. The geometry is identical and every frame renders
  pixel-identically, but the file is 15 bytes smaller than the original.
- FFDec's ActionScript compiler emits slightly different bytecode than Macromedia's in 13
  places, such as constant-pool ordering. The rebuilt bytecode decompiles to exactly the same
  source. `scripts/verify` checks both properties.
