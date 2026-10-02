# gre-proto

Generated GRE schema module.

- When updating upstream, migrate callers to the current schema without preserving compatibility with removed types or fields.
- Do not edit `src/main/proto/messages.proto`; edit `rename-map.sed` and run
  `just sync-proto` from the repository root.
