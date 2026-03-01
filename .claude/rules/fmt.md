# Formatting

- **Kotlin** (forge-web, forge-nexus): `just fmt` — spotless/ktlint. Removes unused imports, fixes spacing/indentation/trailing commas.
- **Svelte/TS** (forge-web-ui): `cd forge-web-ui && pnpm fmt`
- Don't manually fix imports or whitespace — let the tools do it.
- **Skip tests after fmt.** Formatting changes are inconsequential.
