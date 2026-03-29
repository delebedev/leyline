# Sealed & Draft Event Gotchas

Reference for manual sealed/draft automation. These flows will eventually become compound commands — until then, these gotchas apply.

## Sealed

- **"Open" text click hits header, not button.** Pack opening screen has "Open" in description AND button. Use `arena ocr --fmt` to find the button (higher cy, near bottom) and click by coords.
- **"Play" text click hits description.** Event blade body text contains "Play". Use `--bottom` or coord click for the actual Play button.
- **Deck building: grid shifts.** After adding cards, remaining cards shift left. Keep clicking same row positions — new cards fill in.
- **Result dismiss uses (480,300) not (210,482).** Event result screens differ from bot match — center click works.
- **Resign confirmation:** Dialog has "Cancel" and "OK". Use OCR to find "OK" coords — they vary.
- **Invalid Deck modal if Done with <40.** Check `arena ocr --find "40/40 Cards"` before clicking Done.
- **Fresh DB needed for clean events.** `just stop; trash data/player.db; just seed-db` to clear stale courses.

## Quick Draft

- **Double-click to pick.** `arena click <cx>,<cy> --double` bypasses "Confirm Pick" entirely. On 1x displays, "Confirm Pick" may be off-screen.
- **Dismiss tooltip after each pick.** Double-clicking shows a tooltip that blocks "Pack/Pick" header. Click `480,50` to dismiss.
- **41 server-side picks, not 42.** Pack 0: 14, Pack 1: 14, Pack 2: 13. Last card auto-granted.
- **Last card in pack has no OCR text.** Tiny thumbnail at (~68,140). Click by position.
- **Card grid shifts** as cards are picked. OCR each pack for current positions.
- **"Unable to finish draft" after last pick is expected.** CmdTypes 621/1908 stubbed as no-ops (#85). Dismiss error, then: `just stop` + restart server + `arena launch`.
- **"Build Your Deck" on resume.** After server restart, completed drafts go straight to deckbuilder.
- **Vault Progress overlay** may appear after final pick with "Okay" button. Dismiss it.

## General Event Notes

- **Event names need baked loc keys.** Custom event names won't render titles unless `titleLocKey` points to a client-known key (e.g. `Events/Event_Title_Sealed_FDN`). Without it, tile shows no title — hard to find via OCR.
- **Ghost courses after mode switch.** Switching serve modes leaves stale courses. "Resume" appears for events that don't exist. Fix: `just stop` + `trash data/player.db` + fresh `arena launch`.
- **`synthetic_opponent = true`** in `leyline.toml` required for event matchmaking. Without it, pairing hangs forever.
- **Click event tile ART, not title text.** Same as deck thumbnails — clickable area is the image ~40-50px above the label.
