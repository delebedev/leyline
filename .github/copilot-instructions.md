When reviewing changes:

- Prioritize gameplay correctness, regressions, concurrency, and lifecycle defects.
- Trace changed behavior through callers and shared state before reporting a defect.
- Explain the concrete trigger and user-visible consequence of each finding.
- Respect intentional Forge coupling and GRE protobuf use documented in AGENTS.md.
- Check that tests exercise observable behavior and preserve explicit player choices.
- Distinguish automated test coverage from client playthrough evidence.
- Prefer fixes at the shared owner over card-specific workarounds.
- Skip formatting findings covered by CI and speculative abstractions.
- Use repository-local references only.
