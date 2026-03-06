---
paths:
  - "tooling/**"
  - "bin/**"
  - "just/fd.just"
---

# Tooling

- **Layout:** Kotlin module in `tooling/src/`, standalone scripts in `tooling/scripts/`, compiled Swift/shell in `bin/`. Docs stay in `docs/`.
- **No formal tests for leaf CLI tools.** They're tested by use against real recordings/data. If it breaks, fix in the moment.
- **If a tool's output looks broken, fix it before moving on.** No test suite catches tooling regressions — you're the last line. Don't deliver broken output.
- **Python is fine** for zero-dep inspector/analysis scripts (fast startup, pipe-friendly, no build step). Don't force everything into Kotlin.
