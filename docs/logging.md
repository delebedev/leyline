---
summary: "Runtime diagnostic logging contract for agent-searchable plain text logs."
read_when:
  - "adding or changing runtime log events"
  - "debugging a Leyline process from leyline.log"
  - "comparing runtime logs before and after a logging change"
---
# Runtime Logging

Leyline runtime diagnostics use SLF4J 2 key-value event builders and Logback's
plain-text pattern layout. The console and rolling `leyline.log` appenders both
render `%kvp`, so every structured event remains readable with `rg`, `tail`,
and other ordinary shell tools. JSONL is not a runtime logging format.

## Event contract

Each structured event has an `event` key with a stable, lower-case,
dot-separated name. Event names describe the completed or failed operation,
not the logger class. Additional keys use lower-case `snake_case`; use the
canonical keys below when they apply:

| Key | Meaning |
| --- | --- |
| `event` | Stable event name. Required for structured events. |
| `match_id` | Server-side match identity. |
| `seat` | Numeric system seat. |
| `game_state_id` | Client-visible game-state identity associated with a response or prompt. |
| `response_type` | Bounded client response message type. |
| `action_type` | Bounded action enum for an accepted or rejected action. |
| `prompt_type` | Bounded server prompt message type. |
| `turn` | Game turn number. |
| `phase` | Protocol/engine phase name. |
| `reason` | Protocol or lifecycle reason. |
| `horizon` | Committed game-state horizon at a coordinator boundary. |
| `ordinal` | Monotonic committed output ordinal. |
| `error_type` | Error class when a summary is needed without a throwable. |
| `message_type` | Bounded client protocol message type. |
| `template` | Bounded protocol template label. |
| `instance_id` | Client-visible action instance identity. |
| `forge_card_id` | Bounded local card identity used to explain action mapping. |
| `success` | Whether a local action mapping completed. |
| `subsystem` | Bounded runtime subsystem that owns a non-match failure. |
| `request` | Bounded request or boundary category for a non-match failure. |
| `payload_bytes` | Bounded request payload size for a decode failure. |
| `filename` | Bounded filename for a selected local database. |
| `bind_address` | Bounded server bind address. |
| `port` | Server listener port. |
| `kind` | Bounded payment or protocol kind. |
| `required` | Bounded count required by a payment operation. |
| `source_card` | Bounded card name owning a payment operation. |

Values are rendered as Logback key-value pairs after the human message. Values
are escaped by the backend; callers must not build a second serialized object
inside a message. One event should be one physical log line unless its owned
throwable needs the normal stack trace.

## Levels and ownership

- `DEBUG` is detailed state or mechanic diagnostics that are useful during a
  focused investigation.
- `INFO` is a meaningful lifecycle transition or completed operation.
- `WARN` is an actionable recoverable degradation that materially limits
  behavior and needs follow-up. Benign ignored input, routine recovery, and
  expected fallback belong at `INFO` or `DEBUG`.
- `ERROR` is an operation owned by this component that failed and needs
  investigation or intervention.

The component that owns the boundary logs the exception once with
`setCause(throwable)`. Lower layers propagate or return the failure and do not
print another copy. Do not log an exception and then log the same exception
again at a caller merely to add context. Sentry continues to receive `WARN`+
events from the dedicated appender.

## Privacy and output boundaries

Never log credentials, tokens, account identifiers, full client payloads,
private hand contents, card database paths, transport addresses, or unbounded
exception input. Pre-session lifecycle events omit tentative match and seat
values until a session has actually been bound.
Server-generated match, seat, turn, phase, and protocol identifiers are safe
when they are needed to explain an event. Keep values bounded and avoid card
lists or deck contents in routine events.

Intentional CLI output is user-facing command output. It may use `println` and
does not need the runtime event contract. Server and engine diagnostics use
SLF4J and go to the configured console/file/Sentry appenders. The durable
`sessions.jsonl` journal is the one deliberate JSONL exception: it is a small
machine-ingestion record with a versioned schema, separate from diagnostics.

## Current examples

```text
INFO  ManagementServer - Management server listening event="server.management_started" bind_address="127.0.0.1" port="8091"
INFO  MatchSession - Match completed event="match.completed" match_id="m42" seat="1" winning_team="2" reason="Game"
ERROR MatchRuntimeDeliveryObserver - Runtime horizon delivery failed event="match.runtime_delivery_failed" match_id="m42" seat="1"
```

## Diagnostic ownership

These seams have intentionally different responsibilities:

| Seam | Producers and consumers | Decision |
| --- | --- | --- |
| `Tap` | `MatchConnection`, action dispatch, and pre-game senders produce protocol observations; runtime log search consumes them. | Keep only bounded protocol compression that adds wire-order, pre-session, template, or action-mapping facts. Emit it through the primary SLF4J/Logback stream as `client.*` events. Template labels are stable protocol concepts, and optional match/seat context is explicit. Emit `client.template_sent` only after its sink call succeeds. Do not duplicate accepted action facts or retain unused state/action summary helpers. |
| `PromptHistory` | `InteractivePromptBridge` records every engine prompt; tests and acceptance diagnostics read its bounded snapshot. | Keep the in-memory ring because it has unique consumers. It emits no runtime copy. Client-visible completion is owned by `SettledPromptOwner` as `match.prompt_completed`; non-interactive outcomes remain available in the ring. Do not add a file or second stream. |
| Prompt terminal evidence | Prompt runtimes produce `PendingPromptCut` or `PromptMaterializationDiagnostic`; `MatchCutTerminalRuntime` retains them in `PlaybackTerminalFailure` for tests and failure inspection. | Keep as typed in-memory evidence. It is not a logger and must not emit a second prompt-failure line. The engine lifecycle owner reports the terminal exception once. |
| Delivery and terminal failures | `MatchRuntimeDeliveryObserver` owns asynchronous client-delivery failures; `GameLoopController` owns uncaught engine-loop failures; coordinator/runtime code propagates typed terminal state. | Keep one stack at the owning boundary. Lower layers retain or propagate the failure without printing a copy. |
| `ScrySessionJournal` | `LeylineServer` writes one match-start record; Scry session tooling reads `sessions.jsonl`. | Keep only this fixed, versioned machine-ingestion record. It is the one deliberate JSONL exception and is not a runtime log mirror. |

Tap's transport receipt is retained only for pre-session or otherwise unowned
messages. Gameplay responses use the match event's admission record instead, so
the connection does not emit a duplicate receipt with the same response fields.
Accepted action details belong only to the match event; the separate Tap action
observation is retired. Template observations use bounded labels such as
`deal_hand`, `mulligan_request`, and `casting_time_options_hybrid_mana`.
Their optional `match_id` and `seat` keys come from the owning sender. The
`client.template_sent` event is emitted after the sink returns successfully, so
its presence means the corresponding delivery call did not fail.

## Runtime event catalog

The runtime uses these stable events. Optional fields are omitted when the
owning boundary does not have an honest value.

| Event | Owning boundary | Fields |
| --- | --- | --- |
| `match.started` | `Match` state transition | `match_id` |
| `match.teardown` | `MatchRegistry` cleanup | `match_id`, optional `seat`, `reason`, `sessions_removed`, `connections_removed`, `match_closed` |
| `match.disconnected` | `MatchConnection` transport lifecycle | optional `match_id`, optional `seat` |
| `match.connection_failed` | `MatchConnection` transport failure | optional `match_id`, optional `seat`, throwable |
| `match.response_accepted` | `MatchSession` envelope admission | `match_id`, `seat`, `response_type`, `game_state_id` |
| `match.response_rejected` | `MatchSession` envelope/prompt admission | `match_id`, `seat`, `response_type`, `game_state_id`, `reason` |
| `match.action_accepted` | `ActionPerformer` action-window claim | `match_id`, `seat`, `response_type`, `game_state_id`, `action_type`, `phase` |
| `match.action_rejected` | `ActionPerformer` action-window rejection | `match_id`, `seat`, `response_type`, `game_state_id`, `reason`, optional `phase` |
| `match.prompt_published` | `MatchSession` client prompt send | `match_id`, `seat`, `prompt_type`, `game_state_id` |
| `match.prompt_completed` | `SettledPromptOwner` completion | `match_id`, `seat`, `response_type`, `game_state_id` |
| `match.horizon_committed` | `CoordinatorCutInstaller` projection commit | `match_id`, `horizon`, `ordinal`, `batch_count` |
| `match.horizon_delivered` | `MatchRuntimeContinuation` client delivery | `match_id`, `seat` |
| `match.completed` | `MatchSession` result emission | `match_id`, `seat`, `winning_team`, `reason` |
| `match.result_reporting_failed` | `MatchSession` result coordinator boundary | `match_id`, `seat`, throwable |
| `match.runtime_delivery_failed` | `MatchRuntimeDeliveryObserver` async delivery failure | `match_id`, `seat`, throwable |
| `match.priority_skipped` | `PriorityPolicyRuntime` engine priority decision | optional `match_id`, `reason`, `phase`, `turn` |
| `annotations.reordered` | `AnnotationOrderEnforcer` corrected ordering | `annotation_count`, `violation_count` |
| `client.message_received` | `Tap` client message boundary | `message_type` |
| `client.gre_received` | `Tap` GRE message boundary for pre-session or otherwise unowned messages | `message_type`, `seat`, `game_state_id` |
| `client.template_sent` | `Tap` pre-game/template sender after sink success | `template`, optional `match_id`, optional `seat` |
| `client.action_result` | `Tap` local action mapping | `match_id`, `seat`, `action_type`, `instance_id`, `success`, optional `forge_card_id` |
| `server.management_started` | `ManagementServer` listener startup | `bind_address`, `port` |
| `card_database.opened` | `ClientCardDatabase` validated database open | `filename` |
| `payment.tap_unclassified` | `TapPaymentPolicy` unsupported tap-payment shape | `kind`, `required`, optional `source_card` |
| `frontdoor.request_failed` | `FrontDoorHandler` channel/request failure | `subsystem`, `request`, throwable |
| `frontdoor.envelope_decode_failed` | `FrontDoorHandler` envelope decode failure | `subsystem`, `request`, `payload_bytes`, throwable |

## Agent diagnosis

Use the primary text log at `logs/leyline.log` with ordinary shell tools. The
structured fields are stable, so an agent can narrow first by `match_id`, then
by event:

```sh
LOG=logs/leyline.log

# Show one match's causal spine: prompts, responses, actions, horizons, and result.
rg 'match_id="m42"' "$LOG" | rg 'event="match\.(prompt_published|response_accepted|action_accepted|horizon_committed|horizon_delivered|completed|teardown)"'

# Find the last successful prompt publication or client delivery.
rg 'event="match\.(prompt_published|horizon_delivered)".*match_id="m42"' "$LOG" | tail -n 1

# Classify the terminal result or failure.
rg 'event="match\.(completed|.*failed|connection_failed|teardown)".*match_id="m42"' "$LOG"

# Locate the owning stack for a match failure.
rg -n -A 8 'event="match\.(runtime_delivery_failed|result_reporting_failed|connection_failed)".*match_id="m42"' "$LOG"

# Explain a non-match failure from its stable subsystem and request context.
rg -n -A 8 'event="frontdoor\.(request_failed|envelope_decode_failed)"' "$LOG"

# Pivot from client delivery to engine chronology.
rg 'event="match\.horizon_committed".*match_id="m42"' "$LOG" | tail -n 5
```

`match.horizon_committed` is the engine-owned chronology pivot. Its `horizon`,
`ordinal`, and `batch_count` fields connect domain progression to client-visible
delivery without requiring a separate trace system. Owned failures carry one
throwable stack; the event line identifies the boundary and bounded context.
