# Forge Mode (`just serve`) — Autoplay Observations

Differences from proxy mode that affect automation. Some are worth fixing server-side, some need prompt/tool workarounds.

## Priority & Resolve

- **Spells go on Stack → "Resolve" at 888,490** — after dragging a creature/spell, it sits on the stack. Click "Resolve" (same coord as Pass) to resolve it to battlefield.
- **Lands play directly** — no Resolve needed. Hand → Battlefield instantly.
- **`arena play` verification:** detects Hand → Stack zone change → reports ✓ for creatures. But lands sometimes timeout (Hand → BF timing is tight). Exit code 1 for lands but card is actually played.
- **More priority stops** — Forge gives priority after each spell/ability resolves. Agent needs to click through more pass/resolve cycles per turn.
- **Workflow per card:** `arena play "<name>"` → if creature: click 888,490 (Resolve) → click 888,490 (Pass). If land: click 888,490 (Pass).

## Combat

- **"Choose attackers" prompt** — shows "All Attack" (888,489) and "No Attacks" (888,456).
- **"All Attack" doesn't respond** to clicks at 888,489. Unknown why. "No Attacks" at 888,456 works reliably.
- **Workaround:** click individual creatures to select as attackers, then submit. Or use "No Attacks" to skip.
- **"No Attacks" coord is 888,456** — different from the main action button (888,490).

## Main Phase Skipping

- Game jumps from "My Turn" button straight to combat — Main1 is auto-skipped by smart phase skip when it detects no meaningful actions (but this is wrong: land plays are meaningful).
- **Impact:** must play lands in Main2 instead of Main1.
- **Fix needed:** smart phase skip should check for land plays.

## Interactive Prompts

- **"Look at top N, choose" cards freeze** — Commune with Beavers, Analyze the Pollen, etc. Bridge doesn't handle this prompt type → 120s timeout. Filed as #91.
- **Discard prompts auto-resolve** — `choose_cards` for "discard to hand size" works (TargetingHandler auto-resolves).
- **Single-target spells** — `selectTargetsInteractively` works when `mandatory=true`. Voluntary targeting may stall.

## Cards to Avoid in Test Decks

- Commune with Beavers / Commune with Nature / Adventurous Impulse (top-N choose)
- Analyze the Pollen (same)
- Any card with modal choices (choose one/two) — untested, may stall
- Auras — targeting prompt handling is fragile

## Cards That Work Well

- Vanilla creatures (any CMC)
- Mana dorks (Llanowar Elves, Druid of the Cowl — tested, resolve fine)
- Combat tricks (Giant Growth — targets attacker/blocker during combat)
- Lands (all types)
- ETB creatures with mandatory effects (should auto-resolve)

## Debug API

- `/api/best-play` — works! Returns `{bestPlay, phase, turn, reason}`. `null` when no beneficial play.
- `/api/id-map` — returns real data with `forgeZone` field (Hand, Battlefield, Stack, Graveyard, Library)
- `/api/state` — includes Forge-specific fields (`matchId`, `entryCount`)
- **id-map is the source of truth** for card zones. `scry` battlefield may be stale/empty.

## External Monitor / Non-Retina Display

- **Window overflow:** MTGA at 1920x1080 windowed = 1920x1108 with title bar (28px chrome). On a 1080p monitor the bottom 59px are off-screen. Hand cards sit at y~1060-1117 → **drags fail** because CGEvents can't reach off-screen pixels.
- **Fix: fullscreen mode.** `-screen-fullscreen 1` in `arena launch` (auto-detected on 1080p non-retina). Window at (0,0), capture is 1920x1080, hand cards at y~1060 are reachable.
- **Capture resize to 960-space:** `capture_window(hires=False)` resizes to 960px (REFERENCE_WIDTH) so detect/OCR coords match click coord space.
- **Text click scaling bug (fixed):** `cmd_click` text path now scales OCR pixel coords by `window_width / 960`.
- **Future:** consider 1920 as reference width (proportional) to avoid downscale and simplify mapping on non-retina.

## AI Turn Timeouts

- Sparky's `chooseSpellAbilityToPlay` can timeout (120s) on UPKEEP — human player's `WebPlayerController` gets priority during AI turn but no action button shown → bridge timeout.
- After timeout, game may become unstable (missed turns, unexpected graveyard moves).
- Intermittent: happened on first game of session, not on subsequent ones.
- Mitigation: smart phase skip should auto-pass during opponent turn when no playable instants.

## Lobby Navigation (Fullscreen)

- "Bot Match" visible on Home screen at ~(666,166). Click thumbnail above name label.
- Find Match tab → right panel shows format list. "Bot Match" at ~(842,396).
- **Deck selection:** click deck thumbnail (~80px above name label). One click — "Edit Deck" appearing confirms selection. Don't double-click.
- "Play" button at ~(867,519).
- After server restart: "Connection Lost" dialog appears. Click "Reconnect".

## Login

- Account server runs locally, credentials from `just seed-db`
- Client may auto-login from previous session — check with `arena ocr` first
