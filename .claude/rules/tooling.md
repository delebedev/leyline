---
paths:
  - "tools/**"
  - "tooling/**"
  - "just/fd.just"
  - "just/tools.just"
---

# Tooling

- **No formal tests for leaf CLI tools.** They're tested by use against real recordings/data. If it breaks, fix in the moment.
- **If a tool's output looks broken, fix it before moving on.** No test suite catches tooling regressions — you're the last line. Don't deliver broken output.

## Language decision

Pick by what the tool imports, not by habit:

| Needs | Language | Location |
|---|---|---|
| Project classpath (proto types, Forge, domain model) | Kotlin | `tooling/src/` |
| File/JSON/text parsing, HTTP, analysis | TypeScript | `tools/<name>/` |
| Thin launcher/glue | Bash | `bin/` or justfile |

**Default to TypeScript** for new CLI tools unless they need project types.
