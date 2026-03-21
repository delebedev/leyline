# Contributing

Contributions are welcome! Fork the repo, make your changes on a branch, and open a PR.

CI runs on maintainer infrastructure — your fork PR won't get automated checks. A maintainer will review and trigger CI after looking at the code.

## Content rules

This is a public repo. Every commit is visible. Please follow these rules:

- **No captured WotC data.** Don't commit server responses, recordings, card database files, or proxy captures. Test fixtures must use synthetic/hand-written data.
- **No private infra details.** No hardcoded IPs, internal hostnames, or absolute paths.
- **Interop data is fine.** grpIds, set codes, format names, CmdType numbers, loc keys — these are functional protocol identifiers required for compatibility.
