# native

Native-client head: account, frontdoor, and matchdoor protocol surfaces for the local client.

## Limited Set Setup

- Client-visible limited events come from `domain/src/main/resources/fd-bootstrap/events.json`.
- Native set/format bootstrap comes from `domain/src/main/resources/fd-bootstrap/set-metadata.json` and `format-metadata.json`.
- Browser limited-set picker is separate: update `web/src/main/kotlin/leyline/web/WebRoutes.kt` `/api/sealed/sets` and `WebRoutesTest`.
- For a new draft set, mirror an existing Quick Draft event and verify `collationId` against the event being modeled; older sets may use base set collations, newer events may not.
- If the client does not ship an event title localization key for a set, reuse a known visible event title key until local event localization support exists.
