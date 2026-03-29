# Forge Engine Quirks (vs Real Arena)

Differences when running `just serve` (local Forge) that affect automation. Proxy mode (`just serve-proxy`) behaves like real Arena.

## Priority & Resolve

- **Spells go on Stack then need "Resolve"** — after `arena play`, creature/spell sits on stack. Click 888,504 ("Resolve") to resolve to battlefield. Then click 888,504 again ("Pass") to continue.
- **Lands play directly** — no Resolve needed. Hand → Battlefield instantly.
- **More priority stops** — Forge gives priority after each spell/ability resolves. Expect 5-10 button clicks per turn vs 1-2 in real Arena.
- **Workflow per card:** `arena play "<name>"` → if spell: click 888,504 (Resolve) → click 888,504 (Pass). If land: click 888,504 (Pass).

## Combat

- **"All Attack" can be unresponsive** at 888,489. Workaround: click individual creatures to select as attackers, then submit.
- **"No Attacks" at 888,456** works reliably.

## Phase Skipping

- Smart phase skip may jump from "My Turn" straight to combat — Main1 auto-skipped when engine detects no meaningful actions (but land plays ARE meaningful).
- **Impact:** may need to play lands in Main2 instead of Main1.

## Interactive Prompts

- **Top-N choose cards freeze** — Commune with Beavers, Analyze the Pollen, Adventurous Impulse. Bridge doesn't handle this prompt type → 120s timeout. See #91.
- **Discard prompts auto-resolve** — `choose_cards` for "discard to hand size" works.
- **Single-target mandatory spells work.** Voluntary targeting may stall.

## Cards to Avoid in Test Decks

- Commune with Beavers / Commune with Nature / Adventurous Impulse (top-N choose)
- Analyze the Pollen (same)
- Modal choice cards (choose one/two) — untested, may stall
- Auras — targeting prompt handling is fragile

## Cards That Work Well

- Vanilla creatures (any CMC)
- Mana dorks (Llanowar Elves, Druid of the Cowl)
- Combat tricks (Giant Growth)
- Lands (all types)
- ETB creatures with mandatory effects

## AI Turn Timeouts

- Sparky's `chooseSpellAbilityToPlay` can timeout (120s) on UPKEEP — intermittent, happens on first game of session.
- After timeout, game may become unstable (missed turns, unexpected graveyard moves).

## Display / Fullscreen

- **1080p non-Retina:** MTGA at 1920x1080 windowed + title bar = 1920x1108. Bottom 59px off-screen. Hand cards unreachable for drags. Fix: `arena launch` auto-detects and uses fullscreen.
