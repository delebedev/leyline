# arena-ts

**Before working on this tool, run `just arena-ts --help` to see current commands.** The CLI evolves fast — the help output is the source of truth for what exists.

## Design philosophy

**Coordinates, not text targets.** Unity has no accessibility tree. Click by coordinates (960px ref) or named landmarks, never by OCR text match. OCR is a read-only sensor — for detection, verification, and hand card positioning.

**Scry-first.** Game state from Player.log via scry-ts internals (direct import, not subprocess). OCR only for what scry can't tell you (card visual positions, UI chrome text).

**No sleeps.** Every wait polls state via `waitFor`. Scripts express intent, framework absorbs timing. If you're writing `sleep(2000)`, you're doing it wrong — poll game state or scene instead.

**Window-ID capture.** Screenshots via `screencapture -l <wid> -o` — captures MTGA even behind other windows. Never `screencapture -R` (captures whatever is at those coords, including terminal).

**Landmarks over magic numbers.** Named coordinates in `landmarks.ts`. `click home-cta` not `click 866,533`. Add landmarks when you discover stable coordinates.

## Adding commands

Commands in `src/commands/<noun>.ts`. Export `async function <noun>Command(args: string[])`. Wire into the `commands` map in `cli.ts`.

Follow the pattern:
- `--help` as first check
- `--json` for machine-readable output
- Parse flags manually (no framework dependency)
- Use `liveState()` for game state, `currentScene()` for scene
- Use `findHandCard()` / `findAllHandCards()` for card positions
- Exit code: 0 = success, 1 = failure, 2 = preflight issue

## Native layer

Two compiled artifacts in `src/native/`, cached in `~/.arena/bin/`:

- **arena-shim.c** → dylib via `cc`. Mouse input (CGEvent, Sequoia-safe), window bounds, accessibility check, window ID. Called via Bun FFI.
- **ocr.swift** → binary via `swiftc`. Vision OCR with `--crop-bottom`, `--find`, `--json`.

Compile-on-use with content hash. Change the source → next invocation recompiles. Clear cache: `rm ~/.arena/bin/*`.

## OCR hand pipeline

The hand card detection pipeline (`hand.ts`):
1. Capture MTGA by window ID (works behind other windows)
2. Crop: bottom 20%, trim 280px from each side (retina pixels)
3. Upscale 2x for better OCR on small text
4. Vision OCR → fuzzy match against known card names from scry
5. Arc-adjusted position (cards fan in a curve)
6. Gap inference for undetectable overlapped cards

**Card display order ≠ zone order.** MTGA sorts hand by mana cost visually. Scry gives zone order. Only OCR knows the visual order. Never assume index = position.

## Scry-ts integration

Imports directly from `../scry-ts/src/`:
- `parser.ts` — Player.log parsing
- `accumulator.ts` — GSM state accumulation
- `games.ts` — game boundary detection
- `cards.ts` — grpId → card name resolution
- `format.ts` — enum formatting

Free to refactor shared interfaces. Scene detection uses GRE messages (not just SceneChange) to detect InGame on real servers.

## Known sharp edges

- **Adventure cards** resolve to adventure name, not card name (scry-ts bug, tracked in beads)
- **HTML tags** in card names from Arena DB (`<nobr>`) — resolver should strip
- **Cast actions** include everything legal, not just affordable — no mana filtering yet
- **Leftmost hand cards** often too overlapped for OCR — gap inference handles it
- **Bot match vs leyline**: real server games don't emit SceneChange for InGame, we detect via GRE presence
