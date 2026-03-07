# Large File Splits

Hygiene pass — keep files under ~500 LOC. Do opportunistically when touching these files, not as a dedicated sprint.

## WebCostDecision.kt (1332 LOC)

`bridge/WebCostDecision.kt` — handles all cost payment prompts from Forge engine.

Split candidates:
- **ManaPaymentVisitor** — mana cost payment logic (largest section)
- **SacrificeVisitor** — sacrifice-as-cost decisions
- **DiscardVisitor** — discard-as-cost decisions
- **TapVisitor** — tap/untap cost decisions

Each visitor group is independent. Extract when adding new cost types.

## WebPlayerController.kt (1278 LOC)

`bridge/WebPlayerController.kt` — Forge's player controller overrides.

Already extracted: CombatHandler (403), TargetingHandler (222).

Remaining split candidates:
- **ScryHandler** — scry/surveil prompt handling
- **ChoiceHandler** — modal/choice prompt handling (choose one, choose N)

Extract when adding new prompt types.

## BundleBuilder.kt (866 LOC)

`game/BundleBuilder.kt` — GRE message bundle assembly.

Split candidates (per bundle-template plan):
- **BundleTemplate** — shared scaffold for GSM diff + actions + phase annotations
- **CombatBundler** — attacker/blocker declaration bundles
- **PhaseTransitionBundler** — phase transition diff bundles

Do this when adding sealed/draft/commander (new bundle types needed).

## RecordingDecoder.kt (775 LOC)

`recording/RecordingDecoder.kt` — offline decode of captured sessions.

Split candidates:
- **FdFrameParser** — FD frame parsing + JSONL output
- **MdMessageDecoder** — Match Door protobuf decode
- **SessionAnalyzer** — cross-frame analysis (action sequences, timing)

Low priority — recording tools change infrequently.

## AnnotationBuilder.kt (687 LOC)

`game/AnnotationBuilder.kt` — proto annotation constructors.

Close to the 500 LOC guideline. Split only if it grows significantly:
- **AnnotationCategoryResolver** — event → category mapping (already in AnnotationPipeline)
- **ProtoAnnotationFactory** — proto builder methods per annotation type

Low priority — builder methods are uniform and self-contained.
