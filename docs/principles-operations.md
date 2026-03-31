# Operations Principles

Rules for running, iterating, and debugging a system built against an
incomplete spec. Not code structure — workflow structure.

## 1. Tools are discoverable from the entrypoint

The task runner is the table of contents. Every operational task — run,
test, debug, inspect, record, deploy — is listed, grouped, and
self-described. If a tool requires knowing a path, classname, or
incantation to invoke, it's hidden and therefore doesn't exist.

Tools link forward: output names the next step. A recording command
prints how to inspect it. An error message names the debug endpoint
with details. A test failure references the playbook for that failure
class. Discovery doesn't end at the entrypoint — each output is a
signpost to the next action.

Tools link back: `--help` is the minimum. A pointer to the relevant
playbook or doc is better. If a tool has non-obvious behavior, the
help text says where to learn more.

## 2. Player.log is the spec

When the specification is a black box, captured real traffic is the
authoritative reference. Read the recording before writing code. The
schema tells you what fields *exist*; the recording tells you which
ones *matter* and in what *combination*.

Building from proto field names and documentation produces something
that looks right but breaks in ways you discover one crash at a time.
One decode session reveals all the structural differences at once.

## 3. Observable by default

Every run produces enough data to diagnose a failure without
reproducing it. Recordings, logs, state timelines, protocol traces —
always on, not opt-in. If diagnosing a bug requires "add logging,
reproduce, read, remove logging," the observability is insufficient.

The cost of always-on capture is storage. The cost of missing data is
hours.

## 4. Mode switch is atomic

Changing how the system runs is one action, not a checklist. A
command, a config swap, an env var — doesn't matter, as long as it's
one thing. If switching modes requires remembering "also do X, then Y,
and undo Z later," the mode boundary is wrong. The operator shouldn't
carry state in their head about which subsystems are active.

## 5. Minimize restart radius

The most expensive operation is restarting the outermost process.
Iteration should happen at the innermost layer that changed. Server
code change — restart server, not client. Board state change — inject,
not replay. If you're restarting something that didn't change, there's
a missing hot-path.

## 6. Inject state, don't replay to it

To test behavior at a specific point, construct that point directly.
Replays verify *sequences*; injection verifies *behavior at a
snapshot*. Different tools, different purposes. Don't use the slow one
when the fast one applies.

## 7. Constrain inputs, don't puppet outputs

When you can't command a black-box component, control what it's
allowed to do. One legal option = one possible response = deterministic
outcome. Design test scenarios by narrowing the choice space, not by
scripting the chooser.

## 8. Read the wire with a human

An agent optimizes for "does it work." A human notices "this doesn't
feel right." Decoded protocol messages are the shared artifact for
spotting cosmetic and UX gaps — missing highlights, no cancel button,
wrong prompt text — that functional testing doesn't catch. Do this for
any new protocol area, especially interactive flows.

## 9. Reflections compound

Post-fix, write down what would have found the bug faster. Not a
retrospective — a heuristic for next time. "Grep for X before
implementing Y" saves the next session the same multi-hour detour.
Delete reflections that are superseded by code changes: a guard
assertion in production code is better than a note telling you to
check manually.
