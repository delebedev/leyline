# Tool CLIs — catchall passthrough
# Usage: just scry-ts board

[group('tools'), positional-arguments]
scry-ts *ARGS:
    @cd "{{project_dir}}/tools/scry-ts" && bun run cli.ts "$@"
