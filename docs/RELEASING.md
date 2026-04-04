---
summary: "Release checklist: CalVer tag format, version bump locations, CI pipeline, and platform artifact signing."
read_when:
  - "cutting a new release"
  - "debugging CI release pipeline failures"
  - "changing version bump or tagging process"
---
# Releasing

Leyline uses CalVer: `vYYYY.M.D` (e.g., `v2026.4.3`). Same-day hotfix: `v2026.4.3-2`.

## Checklist

1. **Update version** in both files (must match the tag):
   - `launcher/src-tauri/tauri.conf.json` → `"version": "2026.4.3"`
   - `launcher/package.json` → `"version": "2026.4.3"`

2. **Verify versions match:**
   ```bash
   just release-check
   ```

3. **Run the full gate locally** (optional but recommended):
   ```bash
   just test-gate
   just launcher-build
   ```

4. **Commit, tag, push:**
   ```bash
   git add launcher/src-tauri/tauri.conf.json launcher/package.json
   git commit -m "release: v2026.4.3"
   git tag v2026.4.3
   git push origin main --tags
   ```

5. **CI does the rest:**
   - Gate (fmt, detekt, tests) on Linux runner
   - macOS build (.dmg) on GitHub-hosted Apple Silicon
   - LLM-curated changelog (if `OPENROUTER_API_KEY` secret is set)
   - GitHub Release created with .dmg + release notes

6. **Verify** the release at `https://github.com/delebedev/leyline/releases`.

## Local changelog preview

```bash
just changelog                          # uses git tags to find range
OPENROUTER_API_KEY=sk-... just changelog  # LLM-curated version
```

## Required GitHub secrets

| Secret | Purpose |
|--------|---------|
| `APP_ID` + `APP_PRIVATE_KEY` | GitHub App token for forge submodule access |
| `OPENROUTER_API_KEY` | LLM changelog curation (optional — falls back to bullet list) |

## Platform support

macOS arm64 only for now. Windows builds will add a `build-windows` job to the same workflow.

## Unsigned builds

The .dmg is not code-signed or notarized. Users need to right-click → Open on first launch to bypass Gatekeeper. Signing will be added when an Apple Developer account is available.
