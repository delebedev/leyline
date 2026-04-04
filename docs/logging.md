---
summary: "SLF4J + Logback configuration per context (server/test/CLI), log level guidelines, and structured logging rules."
read_when:
  - "adjusting log levels or logback configuration"
  - "adding logging to new code"
  - "debugging noisy or missing log output in tests"
---
# Logging

SLF4J + Logback across leyline. No Kotlin wrappers — raw SLF4J is fine.

## Logback Configs

| File | Context | Root | Notes |
|------|---------|------|-------|
| `matchdoor/src/test/resources/logback-test.xml` | Tests | WARN | Bridge loggers at ERROR (timeout spam) |
| `app/src/main/resources/logback.xml` | Server | INFO | `leyline` at DEBUG, Sentry WARN+, DebugPanel appender |
| `app/src/main/resources/logback-cli.xml` | CLI tools | ERROR | Minimal — just tool output |
| `forge-gui/src/main/resources/logback.xml` | Desktop | ALL | Upstream — don't touch |

**Test logback takes priority** on test classpath. The test config controls what you see during `just test-*`.

## Log Levels — When to Use What

| Level | Meaning | Frequency | Example |
|-------|---------|-----------|---------|
| **ERROR** | Broken invariant, data loss, needs human | Rare | `"SELF-REF gsId"`, `"grpId=0 for card"`, `"Game loop crashed"` |
| **WARN** | Unexpected but recovered; potential bug | Per-incident | `"Action timed out, auto-passing"`, `"stale gsId, ignoring"`, `"no pending action — recovering"` |
| **INFO** | Lifecycle milestone; ≤2 per game phase | Per-game | `"game started"`, `"seat keeps hand"`, `"shutting down"` |
| **DEBUG** | Per-action detail for troubleshooting | Per-action | Event collector categories, annotation pipeline steps, protocol tap |
| **TRACE** | Per-byte / per-frame | Per-frame | Raw TCP relay in proxy mode |

### Rules of Thumb

- **WARN = something the developer should investigate if it happens in prod.** Bridge timeouts in tests are expected (auto-pass on unhandled phases) — that's why test logback suppresses them.
- **INFO should be skimmable.** If you can't follow a game lifecycle by reading only INFO lines, you're missing lifecycle events. If INFO is a wall of text, you have too many.
- **DEBUG is free when disabled.** Use SLF4J `{}` placeholders (already deferred). Don't build expensive strings inline.
- **Never `println` or `System.err`** in library/server code. CLI `main()` tools are the exception — they print to stdout by design.

## Hot-Path Loggers

These fire per game action and will spam at DEBUG. Keep them at DEBUG — that's the point. But never promote them to INFO.

| Logger | Fires per | Statements |
|--------|-----------|------------|
| `GameEventCollector` | Game event | 16 debug |
| `AnnotationPipeline` | Zone transfer / mechanic | 12 debug |
| `ProtocolTap` | Protocol message | 9 debug |
| `FrontDoorService` | FD command | 8 debug |
| `WebPlayerController` | Player choice | 9 debug |

## Test Logback Overrides

`matchdoor/src/test/resources/logback-test.xml` suppresses specific loggers:

```xml
<!-- Bridge timeouts are expected in tests (auto-pass on unhandled phases) -->
<logger name="forge.web.game.GameActionBridge" level="ERROR"/>
<logger name="forge.web.game.InteractivePromptBridge" level="ERROR"/>
<logger name="forge.web.game.MulliganBridge" level="ERROR"/>
<logger name="forge.web.game.BridgeTimeoutDiagnostic" level="ERROR"/>
```

**Why:** Each timeout emits WARN + 15-line stack trace. Integration tests trigger ~70 timeouts per test class → ~1000 lines of noise. These are expected — the engine auto-passes phases the test doesn't handle.

**Effect:** CombatFlowTest output: 1411 → 81 lines (94% reduction).

**To debug a specific timeout in a test:** temporarily set the logger back to WARN in the test class:

```kotlin
@BeforeClass
fun enableTimeoutDiag() {
    (LoggerFactory.getLogger("forge.web.game.GameActionBridge") as Logger).level = Level.WARN
}
```

## Engine stdout (forge-core)

4 `System.out.println` calls in upstream forge-core fire during card DB init:

```
Language 'en-US' loaded successfully.
(ThreadUtil first call): Running on a machine with 10 cpu core(s)
Read cards: 32120 files in 0 ms (25 parts) using thread pool
Read cards: 795 files in 0 ms (7 parts) using thread pool
```

These are upstream — don't fix. Harmless (4 lines total, once per JVM).

## Adding a New Logger

1. Declare in companion object: `private val log = LoggerFactory.getLogger(MyClass::class.java)`
2. Pick level per the table above
3. If it fires per-action (hot path): DEBUG only, use `{}` placeholders
4. If it fires per-incident and is recoverable: WARN
5. If it fires on startup/shutdown: INFO
6. If it's a test-expected condition (like bridge timeouts): add an ERROR override in `logback-test.xml`

## Sentry Integration

`app/src/main/resources/logback.xml` forwards WARN+ to Sentry via `SentryAppender`. Set `SENTRY_DSN` env var to enable. No-op when unset.

This means: every WARN you add will appear in error reporting in prod. Don't use WARN for expected conditions — use INFO or DEBUG.

## Maintenance Checklist

When adding a new WARN/ERROR log statement:
- [ ] Is this actually unexpected in prod? (If expected in tests, add logback-test.xml override)
- [ ] Does the message include enough context to diagnose without reading code? (Include IDs, phase, counts)
- [ ] Will Sentry alert be actionable? (WARN goes to Sentry)

When test output gets noisy:
- [ ] Check `just test-one <TestClass>` — is the noise from bridge timeouts or a new logger?
- [ ] Add targeted `<logger>` override in `logback-test.xml` — don't raise root level
