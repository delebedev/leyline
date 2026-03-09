# Session Log — Issue #77: Modal ETB Support

## Phase 1: Setup & Research (complete)
- Read issue #77 — full implementation plan for CastingTimeOptionsReq
- Explored current leyline matchdoor codebase — identified all insertion points
- Read old forge-nexus worktree — extracted all code to port
- Cards confirmed available: Charming Prince (93996), Trufflesnout (71994), Goblin Surprise (93913)

### Key files to modify:
- `bridge/WebPlayerController.kt` — chooseModeForAbility + playSpellAbilityNoStack fix
- `bridge/InteractivePromptBridge.kt` — modalSourceCardName field on PromptRequest
- `bridge/GameActionBridge.kt` — getPending() race fix
- `match/TargetingHandler.kt` — PendingModal, sendCastingTimeOptionsReq, onCastingTimeOptions
- `game/BundleBuilder.kt` — castingTimeOptionsBundle
- `game/CardDb.kt` — NEW: ModalAbilityInfo, lookupModalOptions
- `game/mapper/PromptIds.kt` — CASTING_TIME_OPTIONS = 38
- `match/MatchHandler.kt` — CastingTimeOptionsResp dispatch
- `match/MatchSession.kt` — onCastingTimeOptions handler
- `match/AutoPassEngine.kt` — drain before game-over

### New files:
- `ModalETBFlowTest.kt` — integration tests
- `modal-etb.pzl` — puzzle for live testing

## Phase 2: Implementation (complete)
- Branch: `fix/77-modal-etb`
- Bridge layer: `chooseModeForAbility` override, `playSpellAbilityNoStack` fix, `getPending()` race fix, `modalSourceCardName` on PromptRequest
- Server layer: `TargetingHandler` modal detection/routing, `BundleBuilder.castingTimeOptionsBundle`, `CardRepository.lookupModalOptions`, `PromptIds.CASTING_TIME_OPTIONS`, `MatchHandler` CTO dispatch, `MatchSession.onCastingTimeOptions`, Familiar mirror filtering
- Test layer: `ModalETBFlowTest` (2 tests passing), `castingTimeOptionsResp` in ProtoDsl, `respondModalChoice` in MatchFlowHarness
- Puzzle: `modal-etb.pzl` with Trufflesnout
- Both integration tests PASS

## Phase 3: Verify (complete)
- Full test suite: BUILD SUCCESSFUL (5m 17s), no regressions
- Live Arena test: `just serve-puzzle puzzles/modal-etb.pzl` + bot match
- Cast Trufflesnout → "Choose One" modal dialog appeared with both modes
- Chose "You gain 4 life" → life went 20→24 correctly
- Evidence gallery: https://pub-ee18c3c0efd64ad5967c2972fae3edd3.r2.dev/g/1773043728/index.html
- Key discovery: OCR coords are 960-scale, click coords are 1920-scale (2x on non-retina)

## Phase 4: Close (complete)
- Committed on branch `fix/77-modal-etb`
