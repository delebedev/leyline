# headless

In-process GRE client for repository-local puzzle tests.

- Drive lifecycle and gameplay through `HeadlessMatch` with typed GRE messages.
- Use `HeadlessClient` for claims derived from emitted output.
- Use `HeadlessEngine` only for state or fixture setup absent from emitted GRE.
  Call it after connection, between completed match inputs, and never concurrently.
  Its commands do not publish client output.
- Keep the public interface limited to headless-owned values, JDK types, and GRE
  schema types. Do not expose engine or Forge types.
- Put a puzzle test in `consumerTest` when its inputs and assertions fit this
  interface. Keep Forge probes, scripted AI, lifecycle controls, and engine
  diagnostics in the engine harness.
- Keep `leyline.headless` flat until separate public concepts require their own
  packages.

Run `./gradlew :headless:testConsumer`; run `just test-gate` at the PR boundary.
