# Exported data formats (Director games)

`scripts/decompile` writes everything for a Director game under `src/<game>/`. This page
documents those files so a port or mod tool can use them without Director or this repo's
code. Field names follow Director's own terms (Lingo `sprite(n).ink`, `member.regPoint`, and
so on), so the Director 8 Lingo Dictionary describes what they do at runtime.

```
src/<game>/
  movies/                    unprotected .dir/.cst files and Lingo source (ProjectorRays)
    <movie>/casts/<castLib>/*.ls     one Lingo script per file
  assets/<movie or cast>/    open-format export (scripts/extract/DirectorExtract.java)
    labels.json
    score.json
    <castLib>/cast.json
    <castLib>/NNNN_<name>.(png|wav|act|palette.json|txt|filmloop.json|xmed.bin)
```

`NNNN` is the member number within its cast library. Member names are kept, with characters
that aren't allowed in file names replaced by `_`.

## Member references

Wherever a file refers to a cast member it uses `"<castLib>#<number> <name>"`, for example
`"select_screen#89 selection_highlight.robin"`. `castLib` is the cast library's name as listed
in the movie (`Lingo`, `hud`, `select_screen`, ..., including linked external casts such as
`char_Robin`). The name part is omitted when the member has none.

## cast.json

One per cast library:

```json
{
  "castLib": "select_screen",
  "fileName": "",
  "members": [
    {"number": 394, "name": "selection_robin.active", "type": "bitmap", "chunks": "BITD Thum",
     "width": 99, "height": 52, "regPoint": [49, 26], "depth": 8,
     "palette": "select_screen#47 SELECT-PALLETTE.act",
     "files": ["0394_selection_robin.active.png"]}
  ]
}
```

| field | meaning |
|---|---|
| `type` | `bitmap`, `filmLoop`, `text`, `palette`, `sound`, `button`, `shape`, `script`, `richText`, `xtra`, ... |
| `chunks` | the raw Director resources the member owns (for reference) |
| `files` | exported files next to cast.json |
| bitmaps: `width`, `height`, `regPoint`, `depth` | size, registration point (x, y) and stored bit depth (1, 2, 4, 8, 16 or 32) |
| bitmaps: `palette` | palette used for 1–8-bit images: a palette member reference, or a built-in palette (`systemMac`, `grayscale`, `systemWin`, ...) |
| sounds: `sampleRate`, `channels`, `bits`, `frames` | format of the exported WAV |
| film loops: `rect` | the loop's bounding rect `[left, top, right, bottom]` in its own coordinates |
| film loops: `frames` | frame count of the loop's timeline |
| `error` | present only if the member couldn't be exported (`scripts/verify` fails on it) |

## Images (`.png`)

- 1–8-bit bitmaps are **indexed PNGs** whose palette is the one in `palette`, so the original
  indices survive a round trip. 1-bit images use white (0) and black (1).
- 16-bit (5-5-5) and 32-bit bitmaps are RGB, or ARGB when the image has a real alpha channel.
- The registration point is not baked into the image. A sprite's `loc` is where its member's
  registration point is drawn.
- How a sprite is drawn on screen also depends on its `ink` (and `blend`), listed in score.json.
  Ink 0 is Copy, 8 Matte, 36 Background Transparent (the palette's background colour,
  usually white, is not drawn), 39 Darkest, and so on.

## Sounds (`.wav`)

Uncompressed PCM, converted from the Mac `snd ` resources Director stores. Battle Blitz's
sounds are all 22,050 Hz, mono, 16-bit.

## Palettes (`.act`, `.palette.json`)

`.act` is an Adobe Color Table (256 × RGB), which most paint programs load.
`.palette.json` is the same palette as a list of `"#rrggbb"` strings.

## labels.json

The movie's frame labels, which the Lingo uses to jump between screens
(`go("SCREEN_TITLE")`):

```json
[{"frame": 30, "label": "SCREEN_TITLE"}, {"frame": 40, "label": "SCREEN_TITLE_WAIT"}]
```

## score.json (and `.filmloop.json`)

The timeline. A film loop's file has the same shape, describing the loop's own small timeline.

```json
{
  "frameCount": 1198, "channels": 1006, "spriteChannels": 1000, "recordSize": 48, "framesVersion": 13,
  "frames": [
    {"frame": 45, "script": "Lingo#234 Frame_LoopHere",
     "sprites": [
       {"sprite": 4, "member": "title_screen#38 loop_title_start_button", "loc": [300, 383],
        "size": [93, 23], "ink": 0, "type": 16, "foreColor": 255, "backColor": 0,
        "blend": 100, "blendRaw": 0, "span": 10044}
     ]}
  ],
  "spans": {
    "10044": {"startFrame": 40, "endFrame": 45, "channel": 9,
              "behaviors": [{"script": "Lingo#31 Behavior_TitleScreenStartButton"}]}
  }
}
```

**Frames** list every frame (1-based), including empty ones. Each one gives the state of
the frame, not a change relative to the previous frame.

| field | meaning |
|---|---|
| `script` | the frame script (its `exitFrame` and so on run on this frame) |
| `tempo` | tempo channel, `{"mode", "value"}` as stored (mode 246 = frames per second in `value`) |
| `transition` | transition member applied when the frame is entered |
| `sound1`, `sound2` | sound channel members |
| `palette` | palette channel |
| `sprites` | every non-empty sprite channel |

**Sprites** use Lingo's sprite numbers (`sprite(4)`). In the raw score, sprite *n* is
channel *n* + 5.

| field | meaning |
|---|---|
| `member` | cast member shown |
| `loc` | registration point position on the stage, `[x, y]` |
| `size` | drawn width and height (differs from the member's size when stretched) |
| `ink` | ink number; `trails` and `stretch` flags when set |
| `blend` | opacity in percent (100 = opaque); `blendRaw` is the stored byte, `(255 - raw) * 100 / 255` |
| `foreColor`, `backColor` | palette indices used by colourising inks |
| `flipH`, `flipV`, `rotation`, `skew`, `editable`, `moveable` | present when set |
| `type` | the sprite type byte as stored |
| `span` | key into `spans` for this sprite's span |

**Spans** describe one stretch of a sprite channel across frames (Director's "sprite span"):

| field | meaning |
|---|---|
| `startFrame`, `endFrame` | first and last frame of the span |
| `channel` | raw score channel (sprite number + 5) |
| `behaviors` | behaviors attached to the sprite, in order: `script` member and optional `parameters` (the property list Director passes to the behavior, as Lingo text) |
| `name` | the sprite's authored name, when it has one |

Behaviors receive `beginSprite` when the playhead enters the span and `endSprite` when it
leaves. That is how the game's screens load (for example `Behavior_TitleScreen` on sprite 1 of
frames 30–45).

## What is not exported yet

- **Shape members** (5) and the one **Xtra member**: these stay in the `.dir`.
- **Styled text** (`XMED`) is saved as stored (`.xmed.bin`), not converted.
- **Score fields** that dirplayer and ScummVM read but this export doesn't decode: tween and
  keyframe data on spans, and colour values beyond the palette indices.
