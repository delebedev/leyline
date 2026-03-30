# Tool CLIs — catchall passthrough
# Usage: just scry-ts board

[group('tools'), positional-arguments]
scry-ts *ARGS:
    @cd "{{project_dir}}/tools/scry-ts" && bun run cli.ts "$@"

# arena-ts: MTGA automation tool
# Usage: just arena-ts preflight

[group('tools'), positional-arguments]
arena-ts *ARGS:
    @cd "{{project_dir}}/tools/arena-ts" && bun run cli.ts "$@"
