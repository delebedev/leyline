---
summary: "Writing standard for the GRE protocol reference: voice, page templates per entity kind, Contract statements and IDs, and what a page must not contain."
read_when:
  - "writing or editing a page under docs/protocol"
  - "adding a Contract statement or deciding whether a sentence earns an ID"
  - "porting an entity description into the protocol reference"
---
# Authoring the protocol reference

This standard applies to every page under `docs/protocol/`. Read
[the reference overview](README.md) first for vocabulary, normative language,
statement IDs and citation rules. A page is finished when it passes the
completion checklist at the end of this document and `just docs-lint`.

## Voice

Write protocol behavior as present-tense declarations about messages. Open
with gameplay meaning, then the entity's protocol role. Keep a definition, its
constraints and its caveats together under the heading where a reader needs
them.

- Write "the client" or "Arena" for the other party, and "the server" for the
  side that publishes state and requests. Never name Leyline, its modules,
  classes, tests, issues or status on an entity page.
- Use the protocol's own names: message types, field names, enum values and
  detail keys exactly as they appear in messages, in code spans.
- Cite Magic rules as plain `CR <number>`, using the narrowest rule. The rules
  define game meaning; the page defines how that meaning travels in messages.
- Card names may appear as plain-text examples when they make a lifecycle
  concrete. Do not add card-database identifiers for them.
- Prompt IDs may appear when they are part of the contract. Never quote
  localized client text; describe what the prompt asks instead.

## What a page must not contain

A page states the contract, not how it was learned. Remove or rewrite:

- game, session, match, message, line or frame identifiers used as evidence
  (synthetic example values inside an illustrative message are fine);
- occurrence counts, sample sizes, percentages and phrases such as "in N
  cases", "every example so far", "the current set";
- links to anything outside this repository other than Comprehensive Rules
  references, and links to generated or private material;
- implementation status, owners, task lists, TODOs, or "implemented / missing /
  wired" language — these belong in [the implementation map](implementation.md);
- commands or tool output used to inspect messages.

When a sentence needs evidence to stand, choose one of three forms instead:
state it as contract, mark it *(provisional)* with its scope, or move it to
**Open points**. Do not soften a contract statement with "appears to" or
"typically"; scope it.

## Page layout

Every page has YAML frontmatter, one `#` title naming the subject only, and
the sections its kind requires below. Omit a section the subject does not
need; keep the order.

```yaml
---
summary: "SelectNReq: bounded selection prompt; families, request, response and lifecycle."
read_when:
  - "emitting, routing or parsing SelectNReq or SelectNResp"
  - "reviewing a change to a SelectN-routed prompt"
entity: prompt/SelectNReq
---
```

- `summary` is one line naming the entity and what the page covers.
- `read_when` lists the tasks that need the page.
- `entity` is `<kind>/<Name>` on entity pages. `<kind>` is one of `prompt`,
  `action`, `annotation`, `object_type`, `designation`, `mechanic`, `model` or
  `stream`. `<Name>` is the file name without `.md` and the prefix of every
  statement ID on the page. Shared pages under `model/` and `stream/` use a
  CamelCase name, for example `stream/Messages`. Family overviews, this page,
  the overview and the implementation map have no `entity` key and no
  Contract.

Use paragraphs for meaning, tables for field reference, numbered steps for
temporal sequences. Map materially different branches before shared fields.
Interpret fields; do not list which fields happen to be present. Add an
identity model whenever card, object, ability, seat, annotation, source or
destination identities could be conflated.

Link sibling entities by relative path, for example
[SelectNReq](prompts/SelectNReq.md). Name an entity that has no page yet in a
code span instead of linking it. Link the owner of a shared concept instead of
restating it. Every page is linked from its family overview.

## Templates by kind

Each template lists headings in order. **Contract** and **Open points** appear
on every entity page; the rest as the subject requires.

### Prompt — `prompts/<Type>.md`

1. Opening paragraph: the decision and the prompt's role.
2. `## Families` — table of materially different request branches and their
   discriminators.
3. `## Request` — envelope and payload field reference.
4. `## Candidate identity` — the identity domain of the offered values in each
   family.
5. `## Response` — response type, correlation, selected values, empty and
   default answers.
6. `## Lifecycle` — numbered GSM, request, response and result sequence.
7. `## Contract`
8. `## Open points`

Keep request, response, correlation and lifecycle on one page. The typed
payload is the semantic discriminator; a prompt ID selects text, not meaning.

### Annotation — `annotations/<Type>.md`

1. `## Meaning` — gameplay meaning and protocol role.
2. `## Rules grounding` — only the rules context needed to read the messages.
3. `## Variants` — rail (transient or persistent) and discriminator branches.
4. `## Fields` — `affectorId`, `affectedIds` and each detail key.
5. `## Identity model` — identity domains and linking keys.
6. `## Lifecycle` — creation, persistence, update and retirement.
7. `## Sibling shapes` — the nearest contrast that prevents a misreading.
8. `## Contract`
9. `## Open points`

### Action — `actions/<Type>.md`

1. `## Meaning and object model` — parent, face and child identities from the
   source zone through the stack to the destination.
2. `## Offer` — offered fields and their identities.
3. `## Lifecycle` — submission, identity changes, costs and resolution. State
   the accepted `UserActionTaken.actionType` integer; it is contract-bearing.
4. `## Sibling shapes` — discriminator, alternate-cost fields, stack identity
   and destination differences.
5. `## Contract`
6. `## Open points`

One page may own sibling actions only when they share one object and
lifecycle.

### Object type — `object_types/<Type>.md`

1. Opening paragraph: gameplay meaning and protocol role.
2. `## Object model` — intrinsic, parent, source and ability identities, and
   zone membership.
3. `## Zone and visibility` — projection to each viewer.
4. `## Lifecycle` — creation, remapping, transition and retirement.
5. `## Sibling shapes`
6. `## Contract`
7. `## Open points`

### Designation — `designations/<Name>.md`

Only multi-stage designations get a page; a simple flag is a row in the family
overview.

1. `## Meaning`
2. `## Rules grounding` — rules state versus message state.
3. `## State` — gain, persistence, loss, identity and detail semantics.
4. `## Lifecycle`
5. `## Sibling shapes`
6. `## Contract`
7. `## Open points`

### Shared page — `model/<topic>.md`, `stream/<topic>.md`

Meaning, then the cross-cutting contract (tables for field roles, numbered
steps for sequences), then `## Contract` and `## Open points`. Entity pages
link here instead of restating stream-wide rules such as response correlation
or rail semantics.

### Family overview — `<family>/README.md`

The opening paragraph defines what the family means. Then family-wide behavior
that helps a reader interpret its entities. Then a table with one row per
entity in the family: the entity name (linked when it has a page) and a
one-line role. Entities without a page are described only by their row.
Family overviews carry no Contract.

## Contract statements

The **Contract** section is a bullet list. Each item is one statement:

```markdown
- <a id="SelectNReq.resp-empty-ok"></a>**SelectNReq.resp-empty-ok.** An empty
  `selectNResp` `MUST` be accepted as a successful zero-value answer.
- <a id="SelectNReq.sacrifice-prompt"></a>**SelectNReq.sacrifice-prompt.**
  *(provisional)* On the permanent-sacrifice branch, …
```

- The anchor `id` and the bold ID are identical and match
  `^<Name>\.[a-z0-9-]+$`, where `<Name>` comes from the page's `entity`.
- *(provisional)* follows the ID directly when the statement is grounded only
  for the scope it names. State that scope in the sentence.
- Start with the branch the statement applies to, then the requirement. One
  requirement per statement; split "and" when the halves could fail
  independently.
- Use normative keywords only here. Elsewhere on the page, write plain
  declarative prose.

### When a sentence earns an ID

A statement earns an ID when both hold:

1. It constrains a message an implementation sends, or a response it must
   accept.
2. A check on the message stream or on response handling would fail if the
   statement were violated: presence or absence of a field on a named branch,
   an exact discriminator value, equality between two identities, cardinality,
   ordering within or across GSMs, or the GSM in which a row is created or
   retired.

Field glossaries, gameplay meaning, sibling contrasts and rules context stay
informative and carry no ID. If a field's meaning has a checkable consequence,
write that consequence as a statement.

### Changing a statement

- Rewording that keeps the requirement keeps the ID.
- A changed requirement gets a new ID; delete the old statement and update
  every `protocol:` citation and link to it in the same change.
- Promoting a provisional statement removes the marker and keeps the ID when
  the requirement is unchanged within the old scope.

## Open points

A bullet list of bounded unknowns, each phrased as a question about messages,
for example "What does `replacedEffectSource` identify?". No IDs, no
normative keywords, no evidence.

## The implementation map

[The implementation map](implementation.md) is the only place Leyline status
appears. When a page adds, removes or changes a statement, update the entity's
row and exceptions there in the same change, following its
**Maintaining this map** section.

## Completion checklist

- Frontmatter has `summary`, `read_when` and, on entity pages, `entity`.
- Sections follow the template for the page's kind, in order.
- Every contract-bearing identity and lifecycle transition is stated once,
  on the page that owns it.
- Every Contract item has an anchor and bold ID that match each other and the
  page's entity name.
- Normative keywords appear only inside **Contract**.
- Nothing from "What a page must not contain" remains.
- Unknowns are in **Open points**; nothing is left as a hedge.
- The family overview links the page.
- The implementation map has a row for the entity.
- `just docs-lint` and `just docs-orphans` report nothing for the page.
