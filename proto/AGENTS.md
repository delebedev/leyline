# gre-proto

Dependency-light GRE protocol module. Owns the generated schema and shared
wire identifiers needed by consumers that must not depend on `engine`.

- Do not edit `src/main/proto/messages.proto`; edit `rename-map.sed` and run
  `just sync-proto` from the repository root.
