## Batch 2026-03-10_12-48-24
1. Mandate `arena play "<name>"` over `bin/arena drag` for card plays — raw drag has no retry/verify, raw drag succeeded only once across 3 runs.
2. Gate second land drag on turn-number: after first land played (hand count drops + gsId advances), set `land_played=true` for current turn_number. Do not attempt land drag again same turn.
3. Discard detection: if `len(hand) > 7`, discard before any other action. Run-2 stuck 173s because it tried to play lands while a mandatory discard was pending.
4. Forbid code-reading pivot when stuck on drag. Explicit prompt rule: "If drag fails twice, use `arena play`. Do NOT read source files."
5. Reduce harness stuck-kill timeout from 90s to 30s — all 3 games were visibly stuck within 30s, last ~60s was wasted polling.
6. Fix subagent token aggregation — all runs report 0 tokens/0 cost (same bug flagged in batch-08-52-59, still unresolved).
7. Provide land-play one-liner in prompt: `bin/scry state | python3 -c "import json,sys; h=json.load(sys.stdin)['hand']; land=next((c['name'] for c in h if c['name'] in ('Plains','Swamp','Forest','Island','Mountain')), None); print(land or '')"` → pipe result to `arena play`.
